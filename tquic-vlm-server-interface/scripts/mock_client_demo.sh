#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CRATE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-19500}"
PROMPT="${PROMPT:-Tell me if there are peanuts in this image}"
IMAGE_URL="${IMAGE_URL:-https://en.wikipedia.org/wiki/Special:FilePath/Mixed_nuts_small_white1.jpg}"

BIN=""
for candidate in "$CRATE_DIR/target/release/tquic-vlm-test-client" "$CRATE_DIR/target/debug/tquic-vlm-test-client"; do
  if [ -x "$candidate" ]; then
    BIN="$candidate"
    break
  fi
done
if [ -z "$BIN" ]; then
  echo "tquic-vlm-test-client not built; building (see docs/build-guide.md for prerequisites)..." >&2
  (cd "$CRATE_DIR" && cargo build --release --bin tquic-vlm-test-client)
  BIN="$CRATE_DIR/target/release/tquic-vlm-test-client"
fi

TMP_IMAGE="$(mktemp --suffix=.jpg)"
trap 'rm -f "$TMP_IMAGE"' EXIT

echo "Downloading sample image from $IMAGE_URL ..." >&2
curl -fsSL "$IMAGE_URL" -o "$TMP_IMAGE"

echo "Sending image + prompt to ${HOST}:${PORT} ..." >&2
exec "$BIN" --host "$HOST" --port "$PORT" --image "$TMP_IMAGE" --prompt "$PROMPT"
