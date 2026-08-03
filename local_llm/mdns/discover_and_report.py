#!/usr/bin/env python3
"""
Discover the Android advertiser over mDNS, then stream this host's telemetry to it.

Roles:
  Android app  -> advertises _devmon._tcp.local. and runs a TCP server
  this script  -> discovers that service, connects, pushes newline-delimited JSON
                  containing our IP, the outbound network interface, and CPU usage.

Modes:
  (default)     discover + connect + report
  --list        discover only, print what is on the network, exit
  --connect     skip mDNS, connect straight to HOST:PORT (emulator / adb forward)
  --advertise   act as a stand-in for the Android app (advertise + receive),
                so the discovery half can be tested with no phone involved.
"""

from __future__ import annotations

import argparse
import ipaddress
import json
import platform
import socket
import socketserver
import sys
import threading
import time

import psutil
from zeroconf import IPVersion, ServiceBrowser, ServiceInfo, ServiceListener, Zeroconf

SERVICE_TYPE = "_devmon._tcp.local."
DEFAULT_INTERVAL = 2.0

# Static description of whatever local LLM this host is set up to run. Not
# queried from a live server -- just a fixed label so the phone can display
# which model this PC is paired with. Edit these fields to match your setup.
LLM_INFO = {
    "name": "llama3.1",
    "parameters": "8B",
    "quantization": "Q4_K_M",
    "context_length": 128000,
    "family": "llama",
}


# --------------------------------------------------------------------------- #
# Telemetry
# --------------------------------------------------------------------------- #

def outbound_ip(peer_ip: str, peer_port: int) -> str | None:
    """Local IP the OS would use to reach the peer.

    Uses a connectionless UDP socket: connect() only sets the route, no packet
    is sent, so this works even if the peer is unreachable at that port.
    """
    family = socket.AF_INET6 if ":" in peer_ip else socket.AF_INET
    s = socket.socket(family, socket.SOCK_DGRAM)
    try:
        s.connect((peer_ip, peer_port or 9))
        return s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()


def is_loopback(ip: str | None) -> bool:
    if not ip:
        return False
    try:
        return ipaddress.ip_address(ip.split("%")[0]).is_loopback
    except ValueError:
        return False


def lan_ip() -> str | None:
    """Best-guess LAN address, independent of any peer.

    Routing to a public address makes the OS pick the default-route interface.
    No packet is sent (UDP connect only sets the route), and 8.8.8.8 need not be
    reachable for this to work.
    """
    return outbound_ip("8.8.8.8", 53)


def iface_for_ip(ip: str | None) -> str | None:
    """Map a local IP back to the adapter name Windows shows (e.g. 'Wi-Fi')."""
    if not ip:
        return None
    for name, addrs in psutil.net_if_addrs().items():
        for a in addrs:
            if a.family in (socket.AF_INET, socket.AF_INET6) and a.address.split("%")[0] == ip:
                return name
    return None


def interface_snapshot() -> list[dict]:
    """Every up interface with an IPv4 address, plus link speed and MAC."""
    stats = psutil.net_if_stats()
    out = []
    for name, addrs in psutil.net_if_addrs().items():
        st = stats.get(name)
        ipv4 = next((a.address for a in addrs if a.family == socket.AF_INET), None)
        mac = next((a.address for a in addrs if a.family == psutil.AF_LINK), None)
        if ipv4 is None:
            continue
        out.append(
            {
                "name": name,
                "ipv4": ipv4,
                "mac": mac,
                "up": bool(st and st.isup),
                "speed_mbps": (st.speed if st and st.speed else None),
            }
        )
    return out


def telemetry(peer_ip: str | None, peer_port: int) -> dict:
    """One snapshot. cpu_percent uses interval=None: it reports the average since
    the previous call, so the reporting loop's own period becomes the window."""
    local_ip = outbound_ip(peer_ip, peer_port) if peer_ip else None

    # Over an `adb forward` tunnel the peer looks like 127.0.0.1, so the route
    # resolves to loopback and the reported IP/interface would be useless to the
    # phone. Fall back to the default-route address, which is what the user
    # actually wants to see, and say so via `ip_via_tunnel`.
    tunneled = is_loopback(local_ip)
    if tunneled:
        local_ip = lan_ip()

    vm = psutil.virtual_memory()
    return {
        "type": "telemetry",
        "ts": time.time(),
        "host": platform.node(),
        "os": f"{platform.system()} {platform.release()}",
        "ip": local_ip,
        "interface": iface_for_ip(local_ip),
        "ip_via_tunnel": tunneled,
        "cpu_percent": psutil.cpu_percent(interval=None),
        "cpu_count": psutil.cpu_count(logical=True),
        "per_cpu_percent": psutil.cpu_percent(interval=None, percpu=True),
        "mem_percent": vm.percent,
        "interfaces": interface_snapshot(),
        "llm": LLM_INFO,
    }


# --------------------------------------------------------------------------- #
# Reporting to one peer
# --------------------------------------------------------------------------- #

class Reporter(threading.Thread):
    """Holds a TCP connection to one Android device and pushes telemetry."""

    def __init__(self, key: str, host: str, port: int, interval: float, verbose: bool):
        super().__init__(daemon=True, name=f"report:{key}")
        self.key, self.host, self.port = key, host, port
        self.interval, self.verbose = interval, verbose
        self._stop = threading.Event()

    def stop(self) -> None:
        self._stop.set()

    def run(self) -> None:
        while not self._stop.is_set():
            try:
                with socket.create_connection((self.host, self.port), timeout=5) as sock:
                    sock.settimeout(None)
                    print(f"[+] connected to {self.host}:{self.port} ({self.key})")
                    self._pump(sock)
            except OSError as e:
                if not self._stop.is_set():
                    print(f"[!] {self.host}:{self.port} {e} - retrying in 3s")
            self._stop.wait(3)

    def _pump(self, sock: socket.socket) -> None:
        while not self._stop.is_set():
            payload = telemetry(self.host, self.port)
            sock.sendall((json.dumps(payload) + "\n").encode())
            if self.verbose:
                print(json.dumps(payload, indent=2))
            self._stop.wait(self.interval)


# --------------------------------------------------------------------------- #
# mDNS discovery
# --------------------------------------------------------------------------- #

class Listener(ServiceListener):
    def __init__(self, interval: float, verbose: bool, report: bool):
        self.interval, self.verbose, self.report = interval, verbose, report
        self.reporters: dict[str, Reporter] = {}

    @staticmethod
    def _describe(info: ServiceInfo) -> tuple[str | None, int]:
        addrs = info.parsed_addresses(IPVersion.V4Only) or info.parsed_addresses()
        return (addrs[0] if addrs else None), (info.port or 0)

    def add_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        info = zc.get_service_info(type_, name, timeout=3000)
        if not info:
            print(f"[?] {name} announced but returned no records")
            return
        ip, port = self._describe(info)
        props = {
            k.decode(errors="replace"): (v.decode(errors="replace") if v else "")
            for k, v in (info.properties or {}).items()
        }
        print(f"[*] found {name}\n      addr {ip}:{port}  host {info.server}\n      txt  {props}")
        if self.report and ip and name not in self.reporters:
            r = Reporter(name, ip, port, self.interval, self.verbose)
            self.reporters[name] = r
            r.start()

    def update_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        # Address or port may have changed: tear the reporter down and re-add.
        self.remove_service(zc, type_, name)
        self.add_service(zc, type_, name)

    def remove_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        r = self.reporters.pop(name, None)
        if r:
            print(f"[-] gone: {name}")
            r.stop()

    def shutdown(self) -> None:
        for r in self.reporters.values():
            r.stop()


# --------------------------------------------------------------------------- #
# --advertise: stand in for the Android app
# --------------------------------------------------------------------------- #

class _Handler(socketserver.StreamRequestHandler):
    def handle(self) -> None:
        peer = f"{self.client_address[0]}:{self.client_address[1]}"
        print(f"[+] telemetry stream from {peer}", flush=True)
        try:
            for raw in self.rfile:
                try:
                    d = json.loads(raw)
                except json.JSONDecodeError:
                    print(f"    !! bad frame from {peer}: {raw[:80]!r}", flush=True)
                    continue
                print(
                    f"    {d.get('host')}  cpu {d.get('cpu_percent')}%  "
                    f"ip {d.get('ip')} via {d.get('interface')}",
                    flush=True,
                )
        except (ConnectionResetError, ConnectionAbortedError, TimeoutError):
            pass  # client vanished (killed, WiFi dropped) - normal, not an error
        print(f"[-] stream closed {peer}", flush=True)


def run_advertiser(name: str) -> None:
    srv = socketserver.ThreadingTCPServer(("0.0.0.0", 0), _Handler)
    srv.daemon_threads = True
    port = srv.server_address[1]

    ip = lan_ip() or "127.0.0.1"
    info = ServiceInfo(
        SERVICE_TYPE,
        f"{name}.{SERVICE_TYPE}",
        addresses=[socket.inet_aton(ip)],
        port=port,
        properties={"role": "stub-advertiser", "impl": "python"},
        server=f"{socket.gethostname()}.local.",
    )
    zc = Zeroconf(ip_version=IPVersion.V4Only)
    zc.register_service(info)
    print(f"[*] advertising {SERVICE_TYPE} as '{name}' on {ip}:{port} - Ctrl+C to stop")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        zc.unregister_service(info)
        zc.close()
        srv.shutdown()


# --------------------------------------------------------------------------- #

def main() -> int:
    # Line-buffer stdout so progress is visible when piped to a file or tee.
    try:
        sys.stdout.reconfigure(line_buffering=True)
    except AttributeError:
        pass

    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--list", action="store_true", help="discover and print only, do not report")
    p.add_argument("--connect", metavar="HOST:PORT", help="bypass mDNS (use with adb forward)")
    p.add_argument("--advertise", nargs="?", const=socket.gethostname(), metavar="NAME",
                   help="act as the Android side: advertise and receive")
    p.add_argument("--interval", type=float, default=DEFAULT_INTERVAL, help="seconds between samples")
    p.add_argument("-q", "--quiet", action="store_true", help="do not echo each sample")
    args = p.parse_args()

    psutil.cpu_percent(interval=None)  # prime; first call always reads 0.0

    if args.advertise:
        run_advertiser(args.advertise)
        return 0

    if args.connect:
        host, _, port = args.connect.rpartition(":")
        if not host or not port.isdigit():
            p.error("--connect expects HOST:PORT")
        r = Reporter("manual", host, int(port), args.interval, not args.quiet)
        r.start()
        try:
            while r.is_alive():
                r.join(0.5)
        except KeyboardInterrupt:
            r.stop()
        return 0

    listener = Listener(args.interval, not args.quiet, report=not args.list)
    zc = Zeroconf(ip_version=IPVersion.V4Only)
    ServiceBrowser(zc, SERVICE_TYPE, listener)
    print(f"[*] browsing {SERVICE_TYPE} - Ctrl+C to stop")
    try:
        if args.list:
            time.sleep(5)
        else:
            while True:
                time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        listener.shutdown()
        zc.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
