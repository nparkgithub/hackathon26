# MPQUIC Linux CLI (client + server)

Console frontends over the **same Rust engine** the Android apps use
(`mpquic-jni`'s `config`/`engine`/`output` modules — only the JNI exports are
Android-specific). Feature parity with the apps: multipath (one QUIC path per
local IP), scheduler/congestion/log-level selection, echo server, per-path
send-complete summaries.

## Layout

| Path | What |
|---|---|
| `mpquic-cli/` | Cargo crate with two binaries: `mpquic-client`, `mpquic-server`. |
| `certs/` | Demo self-signed cert/key (same ones the Android server uses). |
| `bin/x86_64/` | Prebuilt x86-64 Linux executables. |

## Run

```sh
# terminal 1 — echo server
cd linux
bin/x86_64/mpquic-server --listen 0.0.0.0:4433 --cert certs/server.crt --key certs/server.key

# terminal 2 — client: connect, send 5 MB, print the per-path summary, exit
bin/x86_64/mpquic-client --connect 127.0.0.1:4433 --send-mb 5 --oneshot

# multipath: one QUIC path per local IP (first = initial path)
bin/x86_64/mpquic-client --connect <server:4433> --local 192.168.1.10,10.60.0.2 \
    --scheduler redundant --send-mb 10 --oneshot
```

```sh
# HTTP/3 intake: run a local h3 server whose requests are tunneled over
# MPQUIC (same feature as the Android client's "Start HTTP/3 RX" button)
bin/x86_64/mpquic-client --connect 127.0.0.1:4433 --local 127.0.0.1 \
    --h3-port 24443 --cert certs/server.crt --key certs/server.key
# then, from anywhere:  python ../tools/h3_sender.py <host> -p 24443 photo.jpg
```

`--help` on either binary lists all flags. The client understands
`--message TEXT`, `--stats`, `--no-multipath`, IPv6 (`[::1]:4433`), and the
same schedulers/CC algorithms as the apps.

## Build from source

On any x86-64 Linux box (needs cmake, ninja or make, NASM, gcc/clang, and
Rust 1.90 — the boringssl submodule builds as part of tquic):

```sh
cd linux/mpquic-cli
cargo build --release
# binaries in target/release/mpquic-{client,server}
```

Cross-compiling from an aarch64 Linux host (how the checked-in binaries were
made — e.g. from WSL on a Windows-on-ARM machine):

```sh
sudo apt install cmake ninja-build nasm gcc-x86-64-linux-gnu g++-x86-64-linux-gnu
rustup target add x86_64-unknown-linux-gnu
cd linux/mpquic-cli
export CC_x86_64_unknown_linux_gnu=x86_64-linux-gnu-gcc
export CXX_x86_64_unknown_linux_gnu=x86_64-linux-gnu-g++
export CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER=x86_64-linux-gnu-gcc
export CMAKE_GENERATOR=Ninja
cargo build --release --target x86_64-unknown-linux-gnu
```
