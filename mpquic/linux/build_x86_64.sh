#!/usr/bin/env bash
# Build the MPQUIC Linux CLIs for x86-64 Linux.
#
# Works on an x86-64 host (plain build) or an aarch64 host (cross-build via
# the x86_64-linux-gnu toolchain) — e.g. WSL Ubuntu on a Windows-on-ARM
# machine. Installs its own prerequisites (Debian/Ubuntu, needs root).
#
#   bash linux/build_x86_64.sh [--native-test]
#
# --native-test additionally builds natively for the host arch and runs a
# loopback client/server echo smoke test.
#
# Sources are rsynced to $WORK (default ~/mpquic-build) first so the build
# runs on a spaces-free native filesystem; resulting binaries are copied
# back to linux/bin/x86_64/ in the repo.
set -euo pipefail

SRC="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK:-$HOME/mpquic-build}"
RUST_VERSION=1.90.0

echo "== installing prerequisites =="
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y --no-install-recommends \
    cmake ninja-build perl gcc g++ curl ca-certificates rsync \
    gcc-x86-64-linux-gnu g++-x86-64-linux-gnu

if ! command -v cargo >/dev/null 2>&1 && [ ! -f "$HOME/.cargo/env" ]; then
    echo "== installing rust $RUST_VERSION =="
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs |
        sh -s -- -y --default-toolchain "$RUST_VERSION" --profile minimal
fi
# shellcheck disable=SC1091
source "$HOME/.cargo/env"
rustup toolchain install "$RUST_VERSION" --profile minimal
rustup target add --toolchain "$RUST_VERSION" x86_64-unknown-linux-gnu

echo "== syncing sources to $WORK =="
mkdir -p "$WORK"
rsync -a --delete --exclude 'target/' --exclude '.git/' \
    "$SRC/tquic" "$SRC/mpquic-jni" "$SRC/linux" "$WORK/"

cd "$WORK/linux/mpquic-cli"
export CMAKE_GENERATOR=Ninja

if [ "${1:-}" = "--native-test" ]; then
    echo "== native build + loopback echo smoke test =="
    cargo build --release
    ./target/release/mpquic-server --listen 127.0.0.1:24433 \
        --cert "$WORK/linux/certs/server.crt" \
        --key "$WORK/linux/certs/server.key" >"$WORK/server.log" 2>&1 &
    SRV=$!
    sleep 1
    if ./target/release/mpquic-client --connect 127.0.0.1:24433 \
        --local 127.0.0.1 --send-mb 1 --oneshot >"$WORK/client.log" 2>&1; then
        echo "client exited cleanly"
    fi
    kill "$SRV" 2>/dev/null || true
    if grep -q "send complete" "$WORK/client.log" &&
        grep -q "recv" "$WORK/client.log"; then
        echo "SMOKE TEST PASSED"
        grep -E "connected|send complete|path |recv" "$WORK/client.log" | tail -6
    else
        echo "SMOKE TEST FAILED"
        tail -20 "$WORK/client.log"
        exit 1
    fi
fi

echo "== cross build x86_64-unknown-linux-gnu =="
export CC_x86_64_unknown_linux_gnu=x86_64-linux-gnu-gcc
export CXX_x86_64_unknown_linux_gnu=x86_64-linux-gnu-g++
export AR_x86_64_unknown_linux_gnu=x86_64-linux-gnu-ar
export CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER=x86_64-linux-gnu-gcc
cargo build --release --target x86_64-unknown-linux-gnu

echo "== copying executables back to $SRC/linux/bin/x86_64 =="
mkdir -p "$SRC/linux/bin/x86_64"
cp target/x86_64-unknown-linux-gnu/release/mpquic-client \
    target/x86_64-unknown-linux-gnu/release/mpquic-server \
    "$SRC/linux/bin/x86_64/"
file "$SRC/linux/bin/x86_64/"* || true
echo "BUILD OK"
