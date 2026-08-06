#!/usr/bin/env python3
"""Exercises the DevMon /health and /analyze HTTP endpoints over an adb port forward.

Automatically runs 'adb forward tcp:<port> tcp:<port>' before making requests
(auto-detects adb on PATH or under the Android SDK's platform-tools; pass --adb
to point at it explicitly, or --no-adb-forward to manage the forward yourself).

Usage:
    python test_devmon.py
    python test_devmon.py --image test.jpg --query "What allergens are in this food?"
    python test_devmon.py --adb "C:\\Users\\me\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe"
"""
import argparse
import json
import mimetypes
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
import uuid
from typing import Optional


def find_adb() -> Optional[str]:
    on_path = shutil.which("adb")
    if on_path:
        return on_path
    for env_var in ("ANDROID_HOME", "ANDROID_SDK_ROOT", "LOCALAPPDATA"):
        base = os.environ.get(env_var)
        if not base:
            continue
        candidate = os.path.join(base, "Android", "Sdk", "platform-tools", "adb.exe") if env_var == "LOCALAPPDATA" \
            else os.path.join(base, "platform-tools", "adb.exe")
        if os.path.isfile(candidate):
            return candidate
    return None


def adb_forward(adb_path: str, port: int) -> bool:
    try:
        result = subprocess.run(
            [adb_path, "forward", f"tcp:{port}", f"tcp:{port}"],
            capture_output=True, text=True, timeout=15,
        )
    except (OSError, subprocess.TimeoutExpired) as e:
        print(f"adb forward failed to run: {e}")
        return False
    if result.returncode != 0:
        print(f"adb forward failed: {result.stderr.strip()}")
        return False
    print(f"adb forward tcp:{port} tcp:{port} -> ok")
    return True


def get_health(base_url: str) -> None:
    url = f"{base_url}/health"
    print(f"GET {url}")
    try:
        with urllib.request.urlopen(url, timeout=10) as resp:
            body = json.load(resp)
            print(f"  {resp.status} {json.dumps(body)}")
    except urllib.error.HTTPError as e:
        print(f"  {e.code} {e.read().decode('utf-8', errors='replace')}")
    except urllib.error.URLError as e:
        print(f"  request failed: {e.reason}")


def post_analyze(base_url: str, image_path: str, query: str) -> None:
    url = f"{base_url}/analyze"
    print(f"POST {url} (image={image_path!r}, query={query!r})")

    with open(image_path, "rb") as f:
        image_bytes = f.read()

    content_type = mimetypes.guess_type(image_path)[0] or "application/octet-stream"
    boundary = uuid.uuid4().hex
    body = build_multipart_body(boundary, image_bytes, content_type, image_path, query)

    request = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as resp:
            print(f"  {resp.status} {json.dumps(json.load(resp))}")
    except urllib.error.HTTPError as e:
        print(f"  {e.code} {e.read().decode('utf-8', errors='replace')}")
    except urllib.error.URLError as e:
        print(f"  request failed: {e.reason}")


def build_multipart_body(boundary: str, image_bytes: bytes, content_type: str, filename: str, query: str) -> bytes:
    crlf = b"\r\n"
    parts = []

    parts.append(f"--{boundary}".encode())
    parts.append(b'Content-Disposition: form-data; name="query"')
    parts.append(b"")
    parts.append(query.encode("utf-8"))

    parts.append(f"--{boundary}".encode())
    basename = os.path.basename(filename)
    parts.append(f'Content-Disposition: form-data; name="image"; filename="{basename}"'.encode())
    parts.append(f"Content-Type: {content_type}".encode())
    parts.append(b"")
    parts.append(image_bytes)

    parts.append(f"--{boundary}--".encode())
    parts.append(b"")

    return crlf.join(parts)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1", help="DevMon HTTP server host (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=47532, help="DevMon HTTP server port (default: 47532)")
    parser.add_argument("--image", help="Path to an image file to POST to /analyze")
    parser.add_argument("--query", default="What allergens are in this food?", help="Question to ask about the image")
    parser.add_argument("--no-adb-forward", action="store_true", help="Skip running 'adb forward' automatically")
    parser.add_argument("--adb", help="Path to adb executable (default: auto-detect on PATH / SDK location)")
    args = parser.parse_args()

    if not args.no_adb_forward:
        adb_path = args.adb or find_adb()
        if not adb_path:
            print("adb not found on PATH or common SDK locations; skipping port forward.")
            print("Pass --adb <path to adb.exe>, or run 'adb forward tcp:%d tcp:%d' yourself first.\n" % (args.port, args.port))
        elif not adb_forward(adb_path, args.port):
            print("Continuing anyway - requests below will likely fail if the forward isn't set up.\n")

    base_url = f"http://{args.host}:{args.port}"

    get_health(base_url)

    if args.image:
        post_analyze(base_url, args.image, args.query)
    else:
        print("\n(no --image given, skipping /analyze)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
