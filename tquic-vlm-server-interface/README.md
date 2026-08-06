# tquic-vlm-server-interface

A standalone Rust binary that runs a QUIC/HTTP-3 server (via Tencent's [`tquic`](https://github.com/tencent/tquic)
1.6.0) and bridges inbound requests to a local OpenAI-compatible VLM/LLM service.

It is the Ubuntu x86_64-side counterpart to the Android TQUIC demo at
`phone/shared/koog/multiverse/tquic-demo-android/` (backed by the JNI bridge in
`phone/shared/koog/http-client/http-client-tquic/native/tquic-jni/`). This crate has **no
JNI/JVM/Android involvement** — it's a plain Rust binary, kept at the repo root (not nested inside
that Gradle module) for the same reason `local_llm/mdns/` keeps its PC-side Python client as a flat
sibling of the Android project it talks to, rather than nested inside its build tree.

`tquic-jni`'s Rust source is the reference for how to correctly drive `tquic`'s Rust API (Config/TLS
setup, the mio reactor loop, H3 event handling) but is not a code dependency of this crate — several
of its patterns (a generation-tagged handle registry, a `Cmd` enum for cross-thread JNI calls) exist
solely to survive JNI's constraints and are deliberately not reused here; see the doc comments in
`src/reactor/` for what's simplified and why.

## Protocol

A phone dials this server over HTTP/3 (ALPN `h3`) and sends a single `POST /v1/infer` (configurable)
whose body is one JSON document, in one of two shapes told apart by which keys are present
(`src/frames.rs::read_request`) -- no separate endpoint, no discriminator field:

```json
{"jpeg": "<base64-encoded JPEG>", "prompt": "<text>"}
```
The server decodes the base64 and builds a standard OpenAI vision chat-completion request itself
(`src/vlm_client.rs::infer`), or:
```json
{"model": "...", "messages": [...]}
```
already OpenAI-shaped -- forwarded to the VLM backend verbatim, unexamined further
(`src/vlm_client.rs::infer_raw`).

Either way the backend is `{--vlm-base-url}/chat/completions`. The response also varies: the first
shape returns the extracted answer text (`Content-Type: text/plain`); the second relays the VLM
backend's raw JSON response unmodified (`Content-Type: application/json`) -- the client already
understands that shape, since it built the request. See `src/error.rs` for the status-code mapping
on failure, and `docs/interface-guide.md` §3 for the full spec.

TLS uses the same self-signed demo cert already checked into the Android demo
(`certs/server.crt`/`server.key`, copied verbatim) — the phone connects with certificate
verification disabled, matching the existing demo's posture. No client cert is required.

## Building

This binary has real C/assembly build steps (`tquic` vendors and cmake-builds BoringSSL), so
building it is not "just Rust." Two paths:

**On the actual Ubuntu x86_64 machine (simplest):**

```bash
sudo apt update && sudo apt install -y build-essential cmake perl pkg-config
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
source "$HOME/.cargo/env"
rustup toolchain install 1.90.0   # rust-toolchain.toml auto-selects it
cargo build --release
sudo ufw allow 19500/udp          # QUIC is UDP -- match your --bind port
```

**Cross-compiling from an ARM64 dev machine** (e.g. Windows on ARM, no Docker): see the qemu-chroot
recipe in the top-level plan this crate was built from, or any `qemu-user-static` + `debootstrap`
x86_64 chroot tutorial — `tquic`'s C/asm build runs correctly under emulation, just slower.

## Running

```bash
./target/release/tquic-vlm-server-interface \
  --bind 0.0.0.0:19500 \
  --vlm-base-url http://127.0.0.1:8080/v1 \
  --vlm-model <model-name>
```

Run `--help` for the full flag list (congestion control, timeouts, body size cap,
concurrency cap on in-flight VLM calls). Nothing is hardcoded to a specific VLM backend — any
process serving a plain OpenAI-compatible `/v1/chat/completions` endpoint works, in any language.

Phone and server must be on the same LAN/subnet — this is direct connectivity, no NAT
traversal/STUN, matching this repo's existing mDNS/direct-LAN assumptions elsewhere
(`local_llm/mdns/`).

## Testing without a phone

`cargo test` runs the frame-parser and VLM-client unit tests (the latter against a local
`mockito` mock, no real network).

For an end-to-end smoke test, run a trivial stub OpenAI-compatible endpoint (a few lines of Python
`http.server` returning `{"choices":[{"message":{"content":"stub answer"}}]}`), start
`tquic-vlm-server-interface` pointed at it, then use the companion binary:

```bash
./target/release/tquic-vlm-test-client --host 127.0.0.1 --port 19500 \
  --image path/to/some.jpg --prompt "what is this?"
```

Or exercise the passthrough shape with a pre-built OpenAI-shaped JSON file instead:

```bash
./target/release/tquic-vlm-test-client --host 127.0.0.1 --port 19500 \
  --raw-body path/to/openai-request.json
```

Either way it opens a real H3 client session (`verify_peer=false`, matching the server's self-signed
cert), sends the request, and prints the status + response text. Once that round-trips, point the
Android demo's client at this server's LAN IP:port for the real device test — see the top-level
plan for the (separately-scoped) change needed on the Android side to send one of these JSON shapes
instead of its current TLV-framed demo body.
