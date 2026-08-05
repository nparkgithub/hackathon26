#!/usr/bin/env python3
"""Send test UDP packets to the MPQUIC client app's UDP-ingest port.

The client app (Start UDP RX, default port 47474) forwards every datagram it
receives into its Multipath-QUIC connection, so this script lets any desktop
on the same network drive traffic through the tunnel:

    python udp_sender.py 10.73.51.51                     # 10 x 1000 B, 2/s
    python udp_sender.py 10.73.51.51 -c 100 -s 1200 -i 0.1
    python udp_sender.py 10.73.51.51 -m "hello from desktop"
    python udp_sender.py 2607:fc20::1234 -p 47474        # IPv6 works too

Each generated packet starts with an ASCII header like "pkt-0007/0020 " so
it is easy to spot in the app's log preview and to check ordering.
"""

import argparse
import socket
import sys
import time


def build_payload(seq: int, count: int, size: int) -> bytes:
    header = f"pkt-{seq:04d}/{count:04d} ".encode()
    if size <= len(header):
        return header[:size]
    pattern = bytes((33 + (i % 94)) for i in range(size - len(header)))
    return header + pattern


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("host", help="phone IP running the client APK (v4 or v6)")
    parser.add_argument("-p", "--port", type=int, default=47474, help="UDP-ingest port (default 47474)")
    parser.add_argument("-s", "--size", type=int, default=1000, help="payload bytes per packet (default 1000, max 65507)")
    parser.add_argument("-c", "--count", type=int, default=10, help="number of packets (default 10)")
    parser.add_argument("-i", "--interval", type=float, default=0.5, help="seconds between packets (default 0.5)")
    parser.add_argument("-m", "--message", help="send this text once instead of generated packets")
    args = parser.parse_args()

    if not 1 <= args.size <= 65507:
        parser.error("--size must be within 1..65507 (UDP payload limit)")

    try:
        family, _, _, _, addr = socket.getaddrinfo(
            args.host, args.port, type=socket.SOCK_DGRAM
        )[0]
    except socket.gaierror as e:
        print(f"cannot resolve {args.host}: {e}", file=sys.stderr)
        return 1

    sock = socket.socket(family, socket.SOCK_DGRAM)
    try:
        if args.message is not None:
            payload = args.message.encode()
            sock.sendto(payload, addr)
            print(f"sent {len(payload)} B message to {args.host}:{args.port}")
            return 0

        total = 0
        start = time.monotonic()
        for seq in range(1, args.count + 1):
            payload = build_payload(seq, args.count, args.size)
            try:
                sock.sendto(payload, addr)
            except OSError as e:
                print(f"send failed at packet {seq}: {e}", file=sys.stderr)
                return 1
            total += len(payload)
            print(f"\rsent {seq}/{args.count} packets ({total} B)", end="", flush=True)
            if seq != args.count:
                time.sleep(args.interval)
        elapsed = time.monotonic() - start
        rate = total / elapsed if elapsed > 0 else float("inf")
        print(f"\ndone: {total} B in {elapsed:.1f} s ({rate:.0f} B/s) -> {args.host}:{args.port}")
        return 0
    finally:
        sock.close()


if __name__ == "__main__":
    sys.exit(main())
