#!/usr/bin/env python3
"""POST a file (e.g. a large JPEG) over HTTP/3 to the MPQUIC client app.

The client app's "Start HTTP/3 RX" button runs a real HTTP/3 server on the
phone; whatever it receives is tunneled over Multipath QUIC to the server
app, and the server's response is returned over the same HTTP/3 request.

    pip install aioquic
    python h3_sender.py 10.73.51.51 photo.jpg
    python h3_sender.py 10.73.51.51 photo.jpg -p 47443 --path /upload.jpg
    python h3_sender.py 10.73.51.51 --size-mb 4      # generated test JPEG

The apps use a self-signed demo certificate, so verification is disabled.
"""

import argparse
import asyncio
import ssl
import sys
import time
from typing import Optional

try:
    from aioquic.asyncio.client import connect
    from aioquic.asyncio.protocol import QuicConnectionProtocol
    from aioquic.h3.connection import H3Connection
    from aioquic.h3.events import DataReceived, HeadersReceived
    from aioquic.quic.configuration import QuicConfiguration
    from aioquic.quic.events import QuicEvent
except ImportError:
    sys.exit("aioquic is required: pip install aioquic")


class H3Poster(QuicConnectionProtocol):
    """Sends one POST and collects the response."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._http = H3Connection(self._quic)
        self._done = asyncio.Event()
        self.status: Optional[int] = None
        self.received = 0
        self.body = bytearray()

    async def post(self, path: str, authority: str, body: bytes, timeout: float, content_type: str = "image/jpeg"):
        stream_id = self._quic.get_next_available_stream_id()
        self._http.send_headers(
            stream_id=stream_id,
            headers=[
                (b":method", b"POST"),
                (b":scheme", b"https"),
                (b":authority", authority.encode()),
                (b":path", path.encode()),
                (b"content-type", content_type.encode()),
                (b"content-length", str(len(body)).encode()),
            ],
        )
        self._http.send_data(stream_id=stream_id, data=body, end_stream=True)
        self.transmit()
        try:
            await asyncio.wait_for(self._done.wait(), timeout)
        except asyncio.TimeoutError:
            pass

    def quic_event_received(self, event: QuicEvent) -> None:
        for h3_event in self._http.handle_event(event):
            if isinstance(h3_event, HeadersReceived):
                for name, value in h3_event.headers:
                    if name == b":status":
                        self.status = int(value)
                if h3_event.stream_ended:
                    self._done.set()
            elif isinstance(h3_event, DataReceived):
                self.received += len(h3_event.data)
                self.body.extend(h3_event.data)
                if h3_event.stream_ended:
                    self._done.set()


def make_jpeg(size_mb: int) -> bytes:
    """A byte blob with real JPEG SOI/APP0 markers, padded to size."""
    header = bytes([0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00])
    filler = bytes((i % 251) for i in range(65536))
    body = bytearray(header)
    while len(body) < size_mb * 1024 * 1024:
        body.extend(filler)
    del body[size_mb * 1024 * 1024:]
    body.extend(b"\xff\xd9")  # EOI
    return bytes(body)


async def run(host: str, port: int, path: str, body: bytes, timeout: float, content_type: str) -> int:
    config = QuicConfiguration(is_client=True, alpn_protocols=["h3"])
    config.verify_mode = ssl.CERT_NONE
    # Room for a multi-MB response coming back through the tunnel.
    config.max_data = 256 * 1024 * 1024
    config.max_stream_data = 128 * 1024 * 1024

    started = time.monotonic()
    async with connect(host, port, configuration=config, create_protocol=H3Poster) as client:
        print(f"sending {len(body)} B ({content_type}) to https://{host}:{port}{path}")
        await client.post(path, host, body, timeout, content_type)
        elapsed = time.monotonic() - started
        if client.status is None:
            print("no response received (timeout)", file=sys.stderr)
            return 1
        rate = (len(body) + client.received) / elapsed / 1024 if elapsed else 0
        print(
            f"response {client.status}, {client.received} B body "
            f"in {elapsed:.1f}s ({rate:.0f} KB/s round trip)"
        )
        if client.received == len(body):
            print("echoed payload matches the request size")
        if client.received and client.received != len(body):
            print(f"response body: {bytes(client.body)[:2000]!r}")
        return 0 if client.status == 200 else 1


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("host", help="IP running the MPQUIC client (phone or Linux CLI)")
    ap.add_argument("file", nargs="?", help="file to POST (default: generated JPEG)")
    ap.add_argument("-p", "--port", type=int, default=47443, help="HTTP/3 port (default 47443)")
    ap.add_argument("--path", default="/upload.jpg", help="request path (default /upload.jpg)")
    ap.add_argument("--size-mb", type=int, default=2, help="generated JPEG size when no file given")
    ap.add_argument("--timeout", type=float, default=60.0, help="seconds to wait for the response")
    ap.add_argument("--content-type", default="image/jpeg", help="request content-type (default image/jpeg)")
    args = ap.parse_args()

    if args.file:
        with open(args.file, "rb") as fh:
            body = fh.read()
    else:
        body = make_jpeg(args.size_mb)
        print(f"generated {len(body)} B test JPEG")

    return asyncio.run(run(args.host, args.port, args.path, body, args.timeout, args.content_type))


if __name__ == "__main__":
    sys.exit(main())
