# TQUIC — Build Artifacts & Android Viability

**Repo:** https://github.com/Tencent/tquic (cloned to `./tquic`, v1.6.0, `--recurse-submodules` for BoringSSL)
**Date:** 2026-08-03
**License:** Apache-2.0
**Rust:** edition 2021, `rust-version = 1.70.0`, pinned toolchain `1.90.0` (`rust-toolchain.toml`)

> Findings derived from `Cargo.toml`, `tools/Cargo.toml`, `src/build.rs`, `cbindgen.toml`, and
> `.github/workflows/rust.yml`. **No build was executed** — there is no Rust toolchain installed in
> the environment where this was investigated.

---

## 1. Build artifact options

### 1.1 Crate types

`Cargo.toml:83` — `crate-type = ["lib", "staticlib", "cdylib"]`. A single build emits all three.

| `crate-type` | Artifact | Use |
|---|---|---|
| `lib`       | `libtquic.rlib`                        | Rust consumers |
| `staticlib` | `libtquic.a` / `tquic.lib`             | Static link into C/C++/mobile apps |
| `cdylib`    | `libtquic.so` / `.dylib` / `tquic.dll` | Dynamic link, JNI-loadable |

Output lands in `./target/{debug,release}/` (`.\target\release\` on Windows).

### 1.2 Feature flags

`Cargo.toml:32-42`:

- `default = ["qlog", "h3"]`
- **`ffi`** — the C API (`src/ffi.rs`, ~2,645 lines). **Not enabled by default**; every non-Rust
  consumer must pass `-F ffi` / `--features ffi`.
- **`qlog`** — qlog tracing output. Pulls in `serde`, `serde_json`, `serde_derive`, `serde_with`.
- **`h3`** — HTTP/3 + QPACK (`src/h3/`). Pulls in `sfv`.

Dropping `qlog` and `h3` (`--no-default-features`) gives a transport-only QUIC library with a
noticeably smaller dependency graph — relevant for mobile binary size.

### 1.3 C/C++ headers

Pre-generated and checked into the repo (cbindgen, `cbindgen.toml`):

- `include/tquic.h`
- `include/tquic_def.h`

Config notes: `language = "C"`, `cpp_compat = true`, `include_guard = "_TQUIC_H_"`. The generated
header `#include`s `openssl/ssl.h`, so **C consumers also need BoringSSL headers on the include
path**. The API is renamed to a `quic_*_t` / `http3_*_t` C convention (e.g. `Connection` →
`quic_conn_t`, `Endpoint` → `quic_endpoint_t`).

### 1.4 BoringSSL — the one heavyweight decision

`src/build.rs:163-184` offers two modes:

1. **Default — build the vendored submodule via CMake.** Requires `cmake` on PATH; on Windows also
   `nasm` (CI installs it via Chocolatey). This dominates build time.
2. **`BORINGSSL_LIB_DIR=<path>`** — link a prebuilt static BoringSSL instead. Much faster.
   Caveat documented in `build.rs:165-167`: that prebuilt lib must have been compiled with
   `CMAKE_POSITION_INDEPENDENT_CODE` if you want to produce the tquic `cdylib`.

Either way the crate emits `cargo:rustc-link-lib=static=ssl` and `=static=crypto`.

MSVC note: `get_boringssl_build_sub_dir()` (`build.rs:141-161`) resolves BoringSSL's per-config
output subdir (`Release` / `RelWithDebInfo` / `MinSizeRel` / `Debug`) from `DEBUG` + `OPT_LEVEL`.

### 1.5 Binaries (the `tools` workspace member)

`cargo build --all` additionally builds `tools/` → `tquic_client` and `tquic_server`
(`tools/Cargo.toml:35-41`). These are demo / interop / benchmark tools, **not** part of the library
deliverable. They pull in `mio`, `clap` (pinned `=4.2.5`), `env_logger`, `statrs`, `signal-hook`, and
`tikv-jemallocator` on `cfg(unix)`.

### 1.6 Other build targets in-tree (not shipped artifacts)

- `fuzz/` — `client_conn.rs`, `server_conn.rs` fuzz targets
- `benches/timer_queue.rs` — criterion bench, `harness = false`
- `interop/` — Dockerfile + `run_endpoint.sh` for the QUIC interop runner

### 1.7 Platform matrix (from CI, `.github/workflows/rust.yml`)

| Platform | Job | Command |
|---|---|---|
| Linux    | `build_linux`   | `cargo build --all -F ffi` |
| macOS    | `build_macos`   | `cargo build --all -F ffi && cargo test` |
| FreeBSD  | `build_freebsd` | same, inside a FreeBSD VM |
| Windows  | `build_windows` | same, `x86_64-pc-windows-msvc` + nasm |
| iOS      | `build_ios`     | `x86_64-apple-ios`, `--features ffi`; **CI strips `cdylib` from `Cargo.toml` first** |
| Android  | `build_android` | `cargo ndk -t arm64-v8a -p 21 -- build --features ffi` |
| Harmony  | `build_harmony` | `ohrs build -- --features ffi --release` |

Documented release commands (tquic.net/docs/getting_started/installation):

```sh
cargo build --release --all                              # Linux / macOS / FreeBSD / Windows
cargo ndk -t arm64-v8a -p 21 -- build --features ffi --release   # Android
cargo lipo --features ffi --release                      # iOS
ohrs build -- --features ffi --release                   # Harmony / OpenHarmony
```

---

## 2. Android on a non-rooted device — **yes, supported**

### 2.1 Android is a first-class target

- `src/build.rs:16-21` — explicit NDK ABI mapping: `aarch64`→`arm64-v8a`, `arm`→`armeabi-v7a`,
  `x86`→`x86`, `x86_64`→`x86_64`.
- `src/build.rs:76-95` — the `"android"` branch requires `ANDROID_NDK_HOME`, wires up
  `build/cmake/android.toolchain.cmake`, and sets `ANDROID_NATIVE_API_LEVEL = 21`,
  `ANDROID_STL = c++_shared`.
- `.github/workflows/rust.yml:98-127` — dedicated `build_android` job: NDK **r25**, API level **21**,
  target `aarch64-linux-android`, ABI `arm64-v8a`, via `cargo-ndk ^3.0.0`.

### 2.2 Why root is irrelevant

**The library performs no socket I/O.** Grepping `src/` for `UdpSocket` finds hits only inside
`src/endpoint.rs` test code (a fault-injection socket at `endpoint.rs:1403-1432`). Production paths
use `std::net::IpAddr`/`SocketAddr` as value types only.

Instead, the app owns the socket: TQUIC hands outbound datagrams to a `PacketSendHandler` callback
(`quic_packet_send_methods_t` in the C API) and you feed inbound datagrams back in. Consequences:

- Runs entirely on a plain `DatagramSocket` / `DatagramChannel` from an ordinary app sandbox.
- No raw sockets (`SOCK_RAW`), no `setsockopt` requiring privilege, no `CAP_NET_ADMIN`, no kernel
  module, no `/proc` or `/sys` writes.
- Unprivileged UDP to port 443 — exactly the normal QUIC client case — is all that's needed.

### 2.3 Integration caveats

1. **No JNI/Java/Kotlin bindings exist in this repo.** Verified by grep — the only `java`/`jni`
   substring hits are inside `src/h3/qpack/` and `src/qlog/` (unrelated: header names,
   `application/javascript` MIME strings). You must either:
   - write your own JNI shim over the C API in `include/tquic.h`, or
   - consume the Rust crate directly from a Rust-authored `.so`.
2. **C++ runtime dependency.** `ANDROID_STL = c++_shared` means the resulting `.so` links against
   `libc++_shared.so`. That file must be packaged into the APK (from the NDK sysroot, or via
   Gradle `packagingOptions`) or the library will fail to load at runtime.
3. **Per-ABI builds.** Build each ABI separately and place under `src/main/jniLibs/<abi>/`. Only
   `arm64-v8a` is CI-verified upstream; `armeabi-v7a`, `x86`, `x86_64` are wired into `build.rs` but
   untested there.
4. **minSdk 21** is baked into the BoringSSL CMake config (`ANDROID_NATIVE_API_LEVEL`).
5. **`--features ffi` is mandatory** for any JNI-based integration — the C API is off by default.
6. **The `tools/` binaries are not part of an Android build.** `cargo ndk -- build` at the workspace
   root builds the root package only, so the `tikv-jemallocator` `cfg(unix)` dependency in
   `tools/Cargo.toml` is not pulled in.
7. **Toolchain prerequisites:** Android NDK (r25 is the CI-validated version), `ANDROID_NDK_HOME`
   set, `rustup target add aarch64-linux-android` (plus any other ABIs), `cargo-ndk ^3.0.0`, and
   `cmake` for the BoringSSL build.

### 2.4 The real-world risk is not permissions

Root/permissions are a non-issue. The actual deployment risk for QUIC on mobile is networks
(carriers, enterprise Wi-Fi, captive portals) that block or throttle UDP/443. Plan an app-level
TCP/TLS fallback path.

---

## 3. Quick reference — recommended build invocations

```sh
# Rust library only, minimal deps (no qlog, no h3)
cargo build --release --no-default-features

# C API, static + dynamic + headers already in include/
cargo build --release --features ffi

# Everything incl. tquic_client / tquic_server tools
cargo build --release --all --features ffi

# Reuse a prebuilt BoringSSL (must be built with CMAKE_POSITION_INDEPENDENT_CODE)
BORINGSSL_LIB_DIR=/path/to/boringssl/build cargo build --release --features ffi

# Android, arm64
export ANDROID_NDK_HOME=/path/to/android-ndk-r25
rustup target add aarch64-linux-android
cargo install cargo-ndk
cargo ndk -t arm64-v8a -p 21 -- build --features ffi --release
```

---

## 4. What an Android app must provide — Android client ↔ Windows peer

Scenario: an Android app talks to a TQUIC-enabled app on a Windows device. One side acts as the
QUIC **server** (`is_server = true`), the other as the **client**. TQUIC has no peer-to-peer or
NAT-traversal layer — one side must be reachable, or you supply your own rendezvous/relay.

TQUIC is a *sans-I/O* library: it owns protocol state (packet encode/decode, TLS handshake, loss
recovery, congestion control, flow control, streams) and **nothing else**. Everything below is the
app's responsibility.

### 4.1 The five things the app must supply

| # | App must provide | API |
|---|---|---|
| 1 | **The UDP socket** — bind, send, receive, close | Not TQUIC's; app-owned |
| 2 | **A packet-send callback** (mandatory) | `quic_packet_send_methods_t.on_packets_send` |
| 3 | **Inbound datagrams + 4-tuple metadata** | `quic_endpoint_recv()` + `quic_packet_info_t` |
| 4 | **The event loop and timer** | `quic_endpoint_timeout()` / `quic_endpoint_on_timeout()` / `quic_endpoint_process_connections()` |
| 5 | **TLS material** (certs/keys on the server side, trust anchors on the client) | `quic_tls_config_*` |

Plus optional transport-event callbacks (`quic_transport_methods_t`) to learn about connection and
stream lifecycle.

### 4.2 Sockets — app-owned, TQUIC never touches them

`quic_endpoint_new()` (`include/tquic.h:914-919`) takes a `quic_packet_send_methods_t` whose single
member is **mandatory**:

```c
int (*on_packets_send)(void *psctx, quic_packet_out_spec_t *pkts, unsigned int count);
```

Each `quic_packet_out_spec_t` (`tquic.h:227-234`) carries `iov`/`iovlen` plus **`src_addr` and
`dst_addr`** — TQUIC tells you which local address to send from and which remote to send to; you do
the `sendto`/`sendmmsg`. Return the number of messages actually sent; a short count causes TQUIC to
retry the remainder on a later call.

Inbound: the app reads datagrams itself and pushes them in via

```c
int quic_endpoint_recv(quic_endpoint_t *endpoint, uint8_t *buf, size_t buf_len,
                       const quic_packet_info_t *info);
```

`quic_packet_info_t` (`tquic.h:280-285`) requires **both** `src` and `dst` `sockaddr`s. Getting the
true local destination address matters (it drives path validation and connection migration), so on
Android use `IP_PKTINFO`/`IPV6_RECVPKTINFO` via `recvmsg`, or bind one socket per local address.

**Android implication:** a `DatagramSocket`/`DatagramChannel` created in Java gives you a raw
`fd` via `ParcelFileDescriptor` / `DatagramSocketImpl`, which you can pass to native code — or
create the socket entirely in native code with `socket(2)`. Both work unprivileged. If the app uses
a `VpnService` or needs to bypass the VPN, `VpnService.protect(fd)` requires the fd, which is
another reason to hold it explicitly. Also call `ConnectivityManager.bindProcessToNetwork()` /
`Network.bindSocket(fd)` if you want to pin traffic to Wi-Fi vs cellular.

### 4.3 Event loop and timers — app-driven

TQUIC has no threads and no clock of its own. The app runs the loop (see
`tools/src/bin/tquic_client.rs:645-665` for the reference shape):

1. Wait for socket readability **with a timeout of `quic_endpoint_timeout()`**.
2. On readable → `quic_endpoint_recv()` for each datagram.
3. Unconditionally call `quic_endpoint_on_timeout()` (the poll API can't distinguish a timeout).
4. Call `quic_endpoint_process_connections()` — this is what drives `on_packets_send()` and the
   transport callbacks.

On Android this must live on a dedicated background thread (never the main/UI thread). Note that
Doze/App Standby will suspend your timer thread; expect idle timeouts to fire on wake, and set
`quic_config_set_max_idle_timeout()` accordingly or use a foreground service.

### 4.4 TLS material — the part with the most Android-specific friction

QUIC mandates TLS 1.3; there is **no unauthenticated mode**. Roles:

**Windows side as server** must provide a certificate + private key:
```c
quic_tls_config_t *tls = quic_tls_config_new_server_config(
    cert_file, key_file, protos, proto_num, enable_early_data);
```
`cert_file`/`key_file` are **PEM file paths** (`tquic.h:820-824`). There is no in-memory
(byte-buffer) setter — `set_certificate_file` / `set_private_key_file` / `set_ca_certs` all take
paths (`src/tls/tls.rs:162-181`).

**Android side as client**:
```c
const char *const protos[] = {"your-app-proto"};   // ALPN — must match on both ends
quic_tls_config_t *tls = quic_tls_config_new_client_config(protos, 1, false);
quic_tls_config_set_verify(tls, true);
quic_tls_config_set_ca_certs(tls, "/data/data/<pkg>/files/ca.pem");
```

Key facts:

- **ALPN is mandatory and must match.** Pick your own protocol id for an app-to-app link; `h3` only
  if you actually run HTTP/3. Mismatch = handshake failure.
- **`quic_tls_config_set_ca_certs()` accepts a file *or* a directory** — `src/tls/tls.rs:172-181`
  probes with `path.is_file()` and calls `load_verify_locations_from_file` or
  `..._from_directory` accordingly.
- **Hostname verification is real.** The `server_name` argument to `quic_endpoint_connect()` flows
  into `set_host_name()`, which sets both SNI (`SSL_set_tlsext_host_name`) **and**
  `X509_VERIFY_PARAM_set1_host` (`src/tls/boringssl/tls.rs:604-616`). So the Windows peer's
  certificate must contain a SAN matching whatever name the Android side passes. For a
  self-signed peer cert on a LAN IP, issue the cert with an IP SAN or a stable synthetic hostname.
- **`quic_tls_config_set_verify(tls, false)` disables verification entirely** — fine for a lab
  bring-up, unacceptable in production. It maps straight to `SSL_CTX_set_verify(mode=0)`
  (`src/tls/boringssl/tls.rs:372-378`).
- **Android has no usable "cert file path" for app assets.** APK assets are not real files. The app
  must copy the PEM (own CA / pinned peer cert) from `assets` or `res/raw` into
  `context.getFilesDir()` on first run and pass that absolute path. The same applies if the Android
  side acts as the *server* and needs its own cert+key on disk — store them in app-private storage
  (ideally with the key material generated on-device and kept in the Keystore, using the
  `quic_tls_config_new_with_ssl_ctx()` escape hatch below).
- **Escape hatch for in-memory / hardware-backed keys:** `quic_tls_config_new_with_ssl_ctx(SSL_CTX*)`
  (`tquic.h:802`) lets you build a BoringSSL `SSL_CTX` yourself — load certs from memory, install a
  custom private-key method backed by the Android Keystore, or install a custom verify callback.
  Caveat documented in `src/tls/tls.rs:96-103`: with a raw `SSL_CTX`, session resumption
  (`TlsSession::session()`) and `set_keylog()` stop working, and you own the `SSL_CTX` lifetime.
- **Mutual TLS** for app-to-app: give each side both a cert/key and the other's CA; the server side
  additionally needs `set_verify(true)` to require a client cert.
- **Android system trust store** (`/system/etc/security/cacerts`) can be passed to
  `set_ca_certs()` as a directory, but for app-to-app you almost certainly want a private CA or
  pinned cert instead — a public CA can't issue for a peer device.

### 4.5 Connection setup, client side (Android)

```c
quic_config_t *cfg = quic_config_new();
quic_config_set_max_idle_timeout(cfg, ...);
quic_config_set_congestion_control_algorithm(cfg, QUIC_CONGESTION_CONTROL_ALGORITHM_BBR);
quic_config_set_tls_config(cfg, tls);           // Config does NOT take ownership

quic_endpoint_t *ep = quic_endpoint_new(cfg, /*is_server=*/false,
                                        &transport_methods, transport_ctx,
                                        &send_methods,      send_ctx);

uint64_t index;
quic_endpoint_connect(ep, local_sa, local_len, remote_sa, remote_len,
                      "peer.example",          // server_name → SNI + hostname verification
                      session, session_len,    // optional: resumption ticket for 0-RTT
                      token, token_len,        // optional: NEW_TOKEN from a previous conn
                      NULL, &index);
```

Then `quic_conn_new_stream()` / `quic_stream_write()` / `quic_stream_read()` for data, or layer
HTTP/3 on top via the `http3_*` API if the `h3` feature is enabled.

### 4.6 Memory ownership — a JNI-specific hazard

`quic_endpoint_new()`'s doc comment (`tquic.h:910-913`) is explicit: *"The endpoint doesn't own the
underlying resources provided by the C caller. It is the responsibility of the caller to ensure that
these resources outlive the endpoint."* The `quic_transport_methods_t` and
`quic_packet_send_methods_t` structs, their context pointers, and the `quic_config_t` /
`quic_tls_config_t` must all be kept alive for the endpoint's whole lifetime. In a JNI integration
this means holding them in a native struct owned by a long-lived Java object — not stack-allocating
them in the JNI function that creates the endpoint. Likewise, callbacks fire on your loop thread, so
any `JNIEnv` use inside them needs `AttachCurrentThread` plus a **global** ref to the callback
object.

### 4.7 Network changes and multipath (bonus for mobile)

Because the app owns the sockets, Wi-Fi↔cellular transitions are the app's job to detect (via
`ConnectivityManager.NetworkCallback`) and act on. TQUIC exposes:

- `quic_conn_migrate_path()` (`tquic.h:1090`) — move to a new local address
- `quic_conn_add_path()` / `quic_conn_abandon_path()` (`tquic.h:1071`, `1081`)
- `quic_config_enable_multipath()` + `quic_config_set_multipath_algorithm()` (`tquic.h:686`, `692`)
  — use Wi-Fi and cellular simultaneously

These need the peer to support the same extensions, and each path needs its own socket bound to the
corresponding local address.

### 4.8 Summary checklist for the Android integrator

- [ ] UDP socket(s), created and owned by the app (`VpnService.protect` / `Network.bindSocket` as needed)
- [ ] `on_packets_send` implementation doing `sendto`/`sendmmsg` honoring `src_addr`/`dst_addr`
- [ ] Receive loop calling `quic_endpoint_recv` with accurate src **and** dst `sockaddr`s
- [ ] Background thread running poll → recv → `on_timeout` → `process_connections`
- [ ] Matching ALPN string on both Android and Windows
- [ ] Server-side cert + private key as PEM **files**; client-side CA/pinned cert as a **file path**
      in app-private storage (copied out of assets at runtime)
- [ ] `server_name` matching a SAN in the peer certificate (IP SAN if connecting by IP)
- [ ] Long-lived native storage for config/methods structs and context pointers
- [ ] JNI global refs + `AttachCurrentThread` for callbacks crossing into Java
- [ ] `libc++_shared.so` packaged in the APK (see §2.3)

---

## 5. TQUIC FFI — C API surface vs. the full Rust API

**Method:** for every module, `pub fn` names were split into "real" vs. "test-only" by locating
that file's `#[cfg(test)] mod tests { ... }` boundary and checking each `pub fn`'s line number
against it — a first pass conflated the two and produced a wrong finding (§5.4 has the correction).
`ffi.rs` is 2,645 lines and exports 155 `pub extern "C" fn` (`quic_*` transport + `http3_*` HTTP/3).
It is a curated subset of the Rust API, not a mirror — the two use different interaction models
(§5.4/§5.7), and a handful of real Rust methods have no C entry point at all.

### 5.1 `Config` / `TlsConfig` — effectively complete

`Config` has 54 real `pub fn` (`src/lib.rs`, all before the `#[cfg(test)]` boundary at
`lib.rs:1146`) against ~50 `quic_config_*` wrappers — every setter is wrapped **except**
`Config::set_ack_eliciting_threshold` (`lib.rs:503`), which has no `quic_config_set_ack_eliciting_threshold`.
`TlsConfig` is fully wrapped (cert/key files, raw `SSL_CTX*`, CA certs, ALPN, session
ticket key/timeout, cert compression, verify mode, SNI selector).

### 5.2 `Endpoint` — complete except trace-id

`Endpoint`'s 12 real `pub fn` (`src/endpoint.rs`) map to 11 `quic_endpoint_*` functions
(`ffi.rs:867-1042`: `new`, `free`, `set_cid_generator`, `connect`, `recv`, `timeout`,
`on_timeout`, `process_connections`, `exist_connection`, `get_connection`, `close`). Gap:
`Endpoint::set_trace_id` / `trace_id` (`endpoint.rs:807,812`) have no FFI equivalent — only
the per-**connection** `quic_conn_trace_id` exists, not an endpoint-level one.

### 5.3 `Connection` — event-model swap plus four real gaps

Rust's `Connection` is largely **pull-style**: `stream_readable_iter()` / `stream_writable_iter()`
/ `stream_iter()` (`connection.rs:3847-3857`) hand back iterators you walk yourself. The C API
replaces this with a **push-style** callback table, `TransportMethods` (`ffi.rs:1739-1774`:
`on_conn_created`, `on_conn_established`, `on_conn_closed`, `on_stream_created`,
`on_stream_readable`, `on_stream_writable`, `on_stream_closed`, `on_new_token`), registered once
via `quic_endpoint_new`. Same information, different shape — not a gap.

Genuine gaps (all confirmed **real**, i.e. above the `#[cfg(test)]` boundary at `connection.rs:4552`):

| Rust method | Location | Why it's missing / how it's reachable instead |
|---|---|---|
| `recv()` | `connection.rs:404` | No per-connection recv in FFI — `quic_endpoint_recv` (`ffi.rs:974-987`) is the only ingest point; the endpoint dispatches to the right connection internally. |
| `set_session()` / `set_token()` | `connection.rs:332,346` | Not exposed as standalone setters — reachable only as the `session`/`token` byte params baked into `quic_endpoint_connect` (`ffi.rs:916-929`) at connect time. |
| `dcid()` / `scid()` / `dcid_iter()` / `scid_iter()` / `zero_length_dcid()` / `zero_length_scid()` | `connection.rs:3577-3626` | No FFI equivalent at all. You can install a CID **generator** callback (`ConnectionIdGeneratorMethods`, `ffi.rs:2066`) but can't query the connection's current CIDs. |
| `get_path()` (arbitrary path lookup by address) | `connection.rs:3793` | Only the active path (`quic_conn_active_path`) and full iteration (`quic_conn_paths`/`quic_conn_path_iter_next`) are exposed — no lookup-by-address. |

### 5.4 HTTP/3 — near-complete, three real gaps (and one earlier miscall, corrected)

`Http3Connection`'s real API is 16 methods, all above the `#[cfg(test)]` boundary at
`h3/connection.rs:2156`: `new_with_quic_conn`, `set_events_handler`, `stream_new`,
`stream_new_with_priority`, `stream_close`, `stream_destroy`, `stream_set_priority`,
`send_headers`, `send_body`, `recv_body`, `send_priority_update_for_request`,
`take_priority_update`, `send_goaway`, `peer_raw_settings`, `poll`, `process_streams`
(`h3/connection.rs:182-1998`). This maps almost 1:1 onto the 23 `http3_*` functions.

**Correction:** an earlier pass over this same file flagged `send_request`, `send_response`,
`client_send_frame`, `server_send_frame`, `client_send_custom_stream_data`, and
`server_send_custom_stream_data` as real-but-unexposed API. Re-checked against the
`#[cfg(test)]` boundary: all six live at `h3/connection.rs:2310-2477`, *inside* `mod tests`, as
methods on a private `Session` test-harness struct — not on `Http3Connection` at all. They
aren't part of the Rust API surface, so their absence from `http3_*` is expected, not a gap.
Kept here as a methodology note: grep hits on `pub fn` need the test-boundary check before they
count as findings.

Real gaps:

| Rust method | Location | Note |
|---|---|---|
| `stream_destroy()` | `h3/connection.rs:333` | No `http3_stream_destroy`. |
| `poll()` | `h3/connection.rs:1960` | No pull-style alternative in FFI at all — `http3_conn_set_events_handler` (`ffi.rs:2205`) + `Http3Methods` (`ffi.rs:2518-2536`) + `http3_conn_process_streams` is callback-only, no escape hatch. |
| `peer_raw_settings()` | `h3/connection.rs:836` | No direct getter; `http3_for_each_setting` covers similar data via iteration/callback, not a like-for-like wrapper. |

### 5.5 Stats structs — cross the boundary as plain `#[repr(C)]` data

- `ConnectionStats` (`connection.rs:4477`, `#[repr(C)]`): `recv_count`, `recv_bytes`, `sent_count`,
  `sent_bytes`, `lost_count`, `lost_bytes` — 6 `u64` fields.
- `PathStats` (`lib.rs:1071`, `#[repr(C)]`): the above 6, plus `acked_count`/`acked_bytes`,
  `init_cwnd`/`final_cwnd`/`max_cwnd`/`min_cwnd`, `max_inflight`, `loss_event_count`,
  `cwnd_limited_count`/`cwnd_limited_duration`, `min_rtt`/`max_rtt`/`srtt`/`rttvar`,
  `in_slow_start`, `pacing_rate`/`min_pacing_rate`, `pto_count` — ~20 fields total.

Both are returned by pointer (`quic_conn_stats`, `quic_conn_path_stats`) with no per-field
getter needed, since `#[repr(C)]` guarantees a stable layout — see §6.3/§6 for what this means
for a JNI struct-marshaling layer specifically.

### 5.6 Net effect

A C (or JNI) caller gets essentially the full transport + HTTP/3 feature set — congestion
control tuning, multipath, 0-RTT, qlog/keylog, per-path stats — but is locked into the
callback/event-handler architecture rather than Rust's iterator/poll style, and loses direct
CID introspection plus the handful of HTTP/3 items in §5.4. None of the gaps block a normal
client or server implementation.

---

## 6. JNI — additional restrictions on top of the C API

TQUIC ships **no Java/JNI code** (§2.3.1 already established this by grep). This section goes
past that "you write it yourself" fact to the concrete constraints the C API's design (§5)
puts on that shim, beyond the general Android points already in §2.3/§4.6.

### 6.1 Callbacks need trampolines + JNI global refs

`TransportMethods` (`ffi.rs:1739`) and `Http3Methods` (`ffi.rs:2518`) are tables of **plain C
function pointers** (`fn(tctx: *mut c_void, ...)`), not closures — a Java method reference
can't cross that boundary directly. The shim needs static `extern "C"` trampoline functions
registered once at `quic_endpoint_new` (`ffi.rs:867`) / `http3_conn_set_events_handler`
(`ffi.rs:2205`), and the `tctx`/`context` void pointer — the *only* per-endpoint identity handed
back on every callback — must be a JNI **global** ref (`NewGlobalRef`), released with
`DeleteGlobalRef` when the matching `quic_endpoint_free` (`ffi.rs:891`) runs. A local ref reused
across separate JNI entry points is a dangling-reference bug, not a style choice.

### 6.2 TQUIC is synchronous — callbacks fire on whatever thread calls in

Verified no internal threading: no `tokio` dependency in `Cargo.toml`, and the only
`thread::spawn` in the crate is inside `#[cfg(test)] mod tests` in `endpoint.rs`
(boundary at `endpoint.rs:1101`; spawns at `1191`/`1208` are test-only). TQUIC does no I/O and
owns no thread — the app must drive `quic_endpoint_recv` / `on_timeout` / `process_connections`
itself (§4.3), and every registered callback fires **synchronously, inline, on that calling
thread**.

For JNI: if the event loop runs on a dedicated native thread (the recommended shape for
performance — see §6.5), that thread starts out **not attached to the JVM**. Every callback
into Java needs `AttachCurrentThread` — once, when the thread starts, not per-callback, which
is expensive — and `DetachCurrentThread` at thread exit. A `JNIEnv*` obtained on one thread must
never be reused from another.

### 6.3 Buffer boundary — copy semantics, no zero-copy path

`quic_stream_read` (`ffi.rs:1532-1546`), `quic_stream_write` (`ffi.rs:1550-1563`), and
`quic_endpoint_recv` (`ffi.rs:974-987`) all take a caller-owned raw pointer + length, with copy
semantics (`quic_stream_write` does `Bytes::copy_from_slice` internally). There is no zero-copy
path into a `jbyteArray`.

Recommendation: back the buffer with `java.nio.ByteBuffer.allocateDirect(...)` and pass
`GetDirectBufferAddress`'s result straight to TQUIC — that pointer already lives in
JVM-managed native memory, avoiding the extra copy `GetByteArrayRegion`/`SetByteArrayRegion`
would add (and avoiding `GetPrimitiveArrayCritical`, which forbids blocking/reentrant JNI calls
while the array is pinned — a real risk given callbacks can reenter, §6.2).

`quic_conn_session()` (`ffi.rs:1113`) returns a pointer **into TQUIC's own internal memory** —
not caller-owned, not guaranteed valid past the next call on that connection. The shim must
copy it into a `jbyteArray` immediately; it cannot be wrapped and handed to Java for later use.

### 6.4 No native `sockaddr` in Java

`quic_endpoint_connect` (`ffi.rs:916-929`) and `quic_endpoint_recv`'s `PacketInfo`
(`ffi.rs:2037-2043`) take raw `sockaddr*`/`socklen_t`. Java's `InetSocketAddress` has no
wire-compatible representation — the shim itself must build the `sockaddr_in`/`sockaddr_in6`
bytes (from an IP string or `InetAddress.getAddress()` + port); TQUIC provides no marshaling
helper for this, C or otherwise.

### 6.5 Keep packet I/O entirely native

`PacketOutSpec` (`ffi.rs:2056-2064`) is `iovec`-based, built for a tight native send loop, not
per-packet JNI round-trips. Bridging every inbound/outbound UDP datagram through JNI into
`DatagramSocket`/`DatagramChannel` Java code defeats the point of that batched API and adds a
JNI transition per packet. Recommended architecture: do the actual `recvfrom`/`sendto` (or
`sendmmsg`) syscalls in native code — Bionic supports POSIX sockets/`iovec` natively, which is
easier here than it would be on Windows — and cross into Java only for connection/stream-level
events. For `VpnService`, hand the tunnel fd into native code once via JNI and run the I/O loop
there, not per-datagram from Java.

### 6.6 String encoding mismatch

`*const c_char` params (`server_name`, cert/key file paths, ALPN protocol strings) are plain
NUL-terminated C strings. JNI's `GetStringUTFChars` produces **modified UTF-8** (2-byte-encoded
NUL, CESU-8 surrogate pairs) — not standard UTF-8. This is a non-issue for ASCII hostnames and
file paths, but an IDN `server_name` with non-ASCII characters needs re-encoding to real UTF-8
before it reaches TQUIC; nothing in the NDK does this conversion automatically.

### 6.7 Alloc/free pairing needs explicit lifecycle, not `finalize()`

Five matched constructor/destructor pairs cross the boundary: `quic_config_new`/`free`,
`quic_endpoint_new`/`free`, `quic_tls_config_new*`/`free` (four constructor variants, one free),
`http3_config_new`/`free`, `http3_conn_new`/`free`. Each is a natural `AutoCloseable` candidate.
Relying solely on `finalize()`/`Cleaner` is risky here specifically because §6.1's global-ref
`tctx` and §4.6's "caller must keep these resources alive for the endpoint's whole lifetime"
requirement mean two Java wrappers accidentally pointing at the same native handle produces a
double-free, not just a delayed one — give these explicit `close()` methods.

### 6.8 Summary checklist — JNI shim specifics (beyond §4.8)

- [ ] Static trampoline functions for every `TransportMethods`/`Http3Methods` callback, registered once
- [ ] `tctx`/`context` pointers are JNI **global** refs, `DeleteGlobalRef`'d on endpoint/conn teardown
- [ ] `AttachCurrentThread` once per native I/O thread (not per callback), `DetachCurrentThread` on exit
- [ ] Stream/packet buffers backed by direct `ByteBuffer`s, not `jbyteArray`, to avoid a second copy
- [ ] `quic_conn_session()`'s returned pointer copied into a `jbyteArray` immediately, never retained raw
- [ ] `sockaddr` bytes constructed natively — no attempt to pass `InetSocketAddress` across JNI directly
- [ ] Packet I/O (`recvfrom`/`sendto`) done natively, not bridged through Java sockets per-datagram
- [ ] Non-ASCII `server_name` re-encoded from JNI modified-UTF-8 to real UTF-8 before crossing in
- [ ] All five `_new`/`_free` pairs wrapped in explicit `AutoCloseable`s, not left to `finalize()`
