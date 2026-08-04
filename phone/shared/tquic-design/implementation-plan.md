# TQUIC → JNI → Koog/Kotlin Integration Plan

## Context

The Koog master-agent app needs a multipath-QUIC transport so its remote route can reach LLM
backends over MP-QUIC instead of Ktor/TCP. A prior pass scaffolded the Kotlin side, but **nothing
behind it exists**:

- `TquicNative.kt` declares seven `external fun`s and loads `libtquic_jni.so` — no implementation.
- `TquicKoogHttpClient.kt` implements `KoogHttpClient` but throws `NotImplementedError` everywhere.
- `RemoteExecutor` is wired to use it, so the remote route is dead until the native library lands.
- Verified across all three trees (`hackathon26/`, WSL `~`, `multiverse-hackathon-work/`): zero
  `.rs`, zero `Cargo.toml`, zero `CMakeLists.txt`, zero `.so`, no `jniLibs` directory. The
  `tquic-jni` crate referenced throughout the Kotlin docs **has never been written**.

Goal: make the remote route work across multiple end devices, with Android able to both call out to
devices and accept inbound QUIC.

Prior research: `multiverse-hackathon-work\tquic-work\findings.md` §1–§6 (build artifacts, Android
viability, the sans-I/O contract, C-FFI vs Rust API, JNI hazards). Cited, not repeated.

### Already validated this session

TQUIC builds for Android `arm64-v8a` from WSL — the toolchain risk is retired. NDK r29,
`cargo-ndk` 4.1.2, WSL Ubuntu 22.04 aarch64, `qemu-user-static` + amd64 multiarch glibc running the
x86_64-only NDK clang under emulation. Note `libtquic.so` from that build is **not** the deliverable
(it's tquic's own cdylib with the C FFI); the JNI crate builds *itself* as the cdylib and links tquic
as an rlib.

---

## Decisions taken

| # | Decision | Rationale |
|---|---|---|
| 1 | **Rust `tquic-jni` crate** over TQUIC's **Rust API** | No C-FFI gaps, no tquic fork, no cbindgen (a manual undocumented step upstream), reuses the proven `cargo ndk` build. Gets `Http3Connection::poll()`, which the C API doesn't expose — a pull model maps cleanly onto Koog's `Flow`. Shim bugs are handle-lifecycle bugs; Rust ownership prevents the class that manifests as native memory corruption. |
| 2 | No changes to `tquic/src/ffi.rs` or `include/tquic.h` | Follows from #1. |
| 3 | Missing algorithms **out of scope** | `ecf`/`erf`/`thle`/`thlev2`, `lia`/`olia`, PATFB, file-size scheduling don't exist in TQUIC (verified both checkouts). Upstream has only `minrtt`/`redundant`/`roundrobin` and `cubic`/`bbr`/`bbr3`/`copa`/`dummy`. **Validate and reject** — never silent fallback. |
| 4 | Android runs a **TQUIC server** plus N outbound client sessions | User requirement; server surface specified from scratch. |
| 5 | **ABI revised**, and it's the team's to change | `openSession` takes no TLS params, so as declared it cannot complete a handshake. Also replacing the `-1` would-block `h3ReadBody` with a blocking read. |

---

## Reconciled architecture

Three planners designed this in parallel; these are the cross-cutting resolutions.

### R1. One reactor thread, two Endpoints — not thread-per-session

**`Endpoint` is `!Send`** (`endpoint.rs:78,86` — `Rc<RefCell<ConnectionQueues>>`, `Rc<dyn
PacketSendHandler>`). It must be created, used, and dropped on one thread forever. **Never
`unsafe impl Send` a wrapper around it** — add `assert_not_impl_any!(Endpoint: Send)` with a comment.

One planner proposed a thread per session; that's wrong here because **one `Endpoint` hosts many
connections** (`connect()` returns an index, `conn_get_mut(idx)` retrieves it). So:

```
Reactor thread "tquic-reactor" (one, lazily started)
  ├─ mio::Poll + mio::Waker          ← woken by every JNI submit
  ├─ Unit 0: client Endpoint (is_server=false) — N connections, one per device
  └─ Unit 1: server Endpoint (is_server=true)  — M bind sockets, K inbound conns
```

N+1 threads means N+1 timer wheels waking the CPU independently — a real battery/Doze cost on a
phone. Driver is generic over `Vec<Box<dyn DrivenEndpoint>>` so both units share one loop.

**Rules:** no JNI entry point ever touches a tquic object — it submits a `Cmd` and parks on a
condvar. `debug_assert_eq!(thread::current().id(), reactor_tid)` in every reactor fn. **No `JavaVM`
is stored anywhere in the crate**, making Java upcalls structurally impossible. mio tokens are
`(unit_id << 16) | socket_id` — unit-test the decode; a collision silently misroutes datagrams.
Clamp the poll timeout to `[1ms, 1000ms]`: `Endpoint::timeout()` returns `TIMER_GRANULARITY`
whenever anything is pending (`endpoint.rs:471-479`), so a naive loop spins at kHz.

### R2. Pull-based only — zero native→Java callbacks

`TransportHandler` callbacks fire **synchronously inside `endpoint.recv()`**. A Java upcall there
runs class loading and GC pauses on the reactor thread, stalling ACKs and loss detection for every
connection — inflating measured RTT, which feeds BBR and the multipath scheduler directly. It's also
reentrancy-unsafe (`&mut Endpoint` re-entry) and needs `AttachCurrentThread` + global refs +
`ExceptionCheck` after every call. Kotlin pulls via blocking reads instead. Cost: one parked thread
per in-flight blocking call — bounded by `Dispatchers.IO.limitedParallelism` and an explicit
`timeoutMs` on every blocking call (no infinite waits anywhere in the ABI).

### R3. Return codes — `EOF = -1`, `TIMEOUT = -2`, `CANCELLED = -3`, never `0`

Named constants on `TquicNative`. `-1` for EOF matches `java.io.InputStream`; `0` is ambiguous with a
zero-length read. The current KDoc (`TquicNative.kt:67`) says the opposite (`0` EOF, `-1`
would-block) — if Rust implements the old doc, `lines()` spins forever. The loopback test asserts
these explicitly.

### R4. Sockets created in Kotlin, fds passed in

`openSession(..., boundFds: IntArray, ...)` — empty array means Rust creates its own. Required
because pinning a QUIC path to Wi-Fi vs cellular needs `Network.bindSocket(fd)`; binding to an
interface IP alone does **not** route over that interface. Kotlin uses
`ParcelFileDescriptor.fromDatagramSocket(sock).detachFd()` (`detachFd` **transfers** ownership —
`fromFd` dups, and closing the `DatagramSocket` afterwards would yank the fd out from under Rust).
This subsumes any two-phase `socketFd()`/`startSession()` design.

### R5. TLS: synthetic hostnames, and mTLS is not enforceable

- **IP-SAN certificates can never be verified by a tquic client.** `set_host_name()` calls only
  `X509_VERIFY_PARAM_set1_host` (`boringssl/tls.rs:605-620`); nothing anywhere calls `set1_ip`. Use
  `serverName = "${deviceId}.${suffix}"` with a matching dNSName SAN, dialing the IP separately —
  `Endpoint::connect(local, remote, server_name, …)` takes address and name as separate arguments,
  so no DNS is needed. Add an optional `serverName` to `CapabilityEntry` (additive; `StaticConfigDiscovery`
  parses with `ignoreUnknownKeys`).
- **tquic clients do not verify certificates by default.** `Context::new()` never calls
  `SSL_CTX_set_verify` (`boringssl/tls.rs:203-216`), so BoringSSL's `SSL_VERIFY_NONE` applies and
  upstream's own client never sets it. Call `set_verify` unconditionally, `verifyPeer` defaulting
  true, `WARN` on every session open when false.
- **`set_ca_certs` silently succeeds on a nonexistent path** (`tls/tls.rs:172-181`): not-a-file takes
  the directory branch, `SSL_CTX_load_verify_locations(NULL, dir)` returns 1, you get an empty trust
  store and an opaque failure far from the cause. **`Path::exists()` pre-check every cert/key/CA
  path** — ten minutes, saves an afternoon.
- **mTLS is advisory only.** `set_verify(true)` maps to `SSL_VERIFY_PEER` *without*
  `FAIL_IF_NO_PEER_CERT` (`boringssl/tls.rs:369-378`), so a client with no certificate still
  handshakes — and `Connection::peer_cert()` is unreachable (private field, `connection.rs:109`). Use
  an app-level `x-quad-device-id` + HMAC header as the real identity. Day-2: patch tquic to expose
  `peer_cert()` and pass `3` to `SSL_CTX_set_verify`.

### R6. Server terminates H3 natively; proxy-vs-direct is a Kotlin handler swap

Ktor CIO is HTTP/1.1-over-TCP, so the shim must fully terminate QUIC+TLS+H3 either way — there is no
"forward the UDP" option. The only question is what happens to an already-terminated request, and
that's a Kotlin `KoogRequestHandler` implementation, **same ABI**. Ship the loopback-proxy handler
first (proves H3 end-to-end with zero routing changes), then swap for direct dispatch by refactoring
the `ComputeRoutes.kt` route bodies into transport-agnostic functions.

---

## Workstream A — the Rust crate

**Location:** `http-client/http-client-tquic/native/tquic-jni/` — colocated with the ABI
declaration. ABI drift between `TquicNative.kt` and the Rust symbols is the top failure mode of this
design and surfaces only at runtime; colocating makes a signature change one diff, one review.

**Dependency:** `tquic = { version = "1.6.0", default-features = false, features = ["h3"] }` from
crates.io — verified published, and its `include` list ships the full BoringSSL source, so there's no
submodule dance. Both local checkouts are the same version and commit, so nothing is lost. Escape
hatch for patching: `[patch.crates-io]` → path, in a **gitignored** `.cargo/config.toml`.

**`Cargo.toml`:** `crate-type = ["cdylib"]` only; `name = "tquic_jni"`. Deps `jni = "0.21"` (pin —
0.22 API drift unverified), `mio`, `socket2`, `bytes`, `slab`, `android_logger`.
**`[profile.release] panic = "unwind"`** — `panic = "abort"` makes `catch_unwind` a no-op and turns
every recoverable bug into a process kill. `qlog` off by default (pulls serde, real `.so` bloat).
**Do not enable `ffi`** — that was for tquic's own C layer.

**`rust-toolchain.toml` pinning `1.90.0`** — tquic pins it, and the Android std libs were installed
only on 1.90.0. Without this file cargo uses 1.97.1, which has no `core`/`std` for
`aarch64-linux-android`, and the error points at the crate rather than the toolchain.

**Modules:** `lib.rs` (JNI exports, thin), `jerr.rs` (error macro, exception cache), `handle.rs`,
`config.rs` (all validation/rejection), `runtime/` (**shared by client and server** — `socketset`,
`cmd`, `driver`, `waker`), `client/`, `server/`, `stats.rs`.

**Handles:** generation-tagged slab registry, not `Box::into_raw`. Layout `(gen << 32) | (idx+1)`;
`0` always invalid; kind tag per registry. Double-close is *likely* here, not theoretical — `sse()`
returns a `Flow` whose `onCompletion`, `finally`, and `closeSession` teardown all race under
cancellation. With raw pointers that's heap corruption and a native-only tombstone; with generation
tags it's a readable exception. `get()` clones an `Arc` and drops the registry lock immediately, so
no lock is held across a blocking op.

**Errors:** every export wrapped in a `catch_unwind` macro — a panic across `extern "system"` is UB.
`panic::set_hook` in `JNI_OnLoad` logging payload + backtrace to logcat. **Wrap the reactor loop
too**: on panic, mark all handles closed and `notify_all()`, or every parked reader hangs forever.
Exception classes resolved into a `OnceLock<GlobalRef>` at `JNI_OnLoad` (`FindClass` off-thread
resolves against the system classloader and can't see app classes). Hierarchy: `TquicException :
IOException` with `TquicConfigException` / `TquicTlsException` / `TquicTransportException` /
`TquicClosedException` — config errors must be distinguishable so callers don't retry forever.

**`JNI_OnLoad` + `RegisterNatives`, not bare `#[no_mangle]`.** ART resolves symbols lazily on first
call, so a stale `.so` passes `System.loadLibrary` and blows up weeks later mid-request.
`RegisterNatives` validates every name+descriptor at load. Keep `abiVersion()` as the *semantic*
guard (same signature, changed meaning — exactly the `h3ReadBody` change) and `abiInfo()` for
diagnostics. Note `TquicNative` is a Kotlin `object`, so natives are **instance** methods — the
second JNI arg is `JObject`, not `JClass`.

### The one subtle correctness requirement

**`Http3Event::Data` is a one-shot latch.** `trigger_data_event()` (`h3/stream.rs:587-595`) returns
`false` on every call after the first; the latch clears *only* inside `read_data_from_quic` on
`Error::Done` or `!stream_readable` (`h3/stream.rs:561-563,572-575`). So if the driver stops calling
`recv_body` to exert backpressure, **`poll()` never emits `Data` for that stream again and the
request stalls forever** — looking exactly like a network hang.

Mitigation: keep `blocked: HashSet<u64>` and call `pump_body(sid)` for every member on *every* loop
turn, independent of `poll()`, plus on `Cmd::RequestDrained`. HIWAT 512 KB / LOWAT 128 KB, edge-
triggered notify. **This needs a dedicated regression test** (loopback server sends 4 MB while the
reader sleeps 2 s; assert completion).

### Other verified gotchas

- `set_max_idle_timeout` is **milliseconds** (`lib.rs:413-416`) — the `tquic_client` CLI comment
  saying microseconds is stale. Maps 1:1, no conversion.
- `add_path` rejects before handshake completion (`connection.rs:3729-3731`) — bind the socket
  immediately but queue the call until `on_conn_established`. `addPath` returns "bound and queued",
  not "validated".
- Default `active_connection_id_limit` is 2 = exactly one extra path. Set 4 when multipath is on.
- Reject unspecified local addresses when multipath is on — `add_path` keys on `(local, remote)` and
  `0.0.0.0:0` yields indistinguishable locals.
- `on_packets_send` runs *inside* `process_connections()`, so `SocketSet::add()` must only be called
  from `drain_commands()` (before it) or the `RefCell` double-borrows.
- Lowercase every caller-supplied header name — RFC 9114 requires it and a capitalised name is a
  connection kill on strict servers. Reject the forbidden set (`connection`, `transfer-encoding`,
  `keep-alive`, `upgrade`, `proxy-connection`); no `connection: keep-alive` on SSE.
- No `IP_PKTINFO` needed: port upstream's `QuicSocket` (`tools/src/common.rs:114-236`), which takes
  `dst` from `socket.local_addr()`. One socket per local address is upstream-blessed and required for
  multipath anyway.

---

## Workstream B — Kotlin, Gradle, and the build boundary

### Build wiring: staged `.so`, never `externalNativeBuild`

The only validated build runs **in WSL**, against an x86_64 NDK under `qemu-user-static`, on a
toolchain pinned to 1.90.0. AGP's CMake integration runs on **Windows** and cannot reach that
without a chain of adapters that will break silently. So:

- **Required path:** the `.so` is a *staged binary input*. `native/prebuilt/android/arm64-v8a/`
  contains `libtquic_jni.so` **and `libc++_shared.so`** (tquic's `build.rs` sets
  `ANDROID_STL=c++_shared`; without it `dlopen` fails at runtime). The WSL script stages both, so
  Gradle needs no NDK knowledge at all.
- **Opt-in convenience:** a Gradle `Exec` task behind `-Ptquic.buildNative=true` invoking
  `wsl.exe -d <distro> -- bash -lc '…'`. Must be `bash -lc` (login) — a non-login shell doesn't source
  `~/.profile`, so `cargo` and `ANDROID_NDK_HOME` go missing and the failure reads as
  "cargo: command not found".
- **Rejected:** `externalNativeBuild` → CMake → cargo. Two build systems lying to each other across
  the WSL boundary, with unreadable error chains.

`app/build.gradle.kts` additions: `ndk { abiFilters += "arm64-v8a" }` (without it AGP ships an APK
with empty ABI dirs that dies on an x86_64 emulator naming the wrong cause),
`sourceSets["main"].jniLibs.srcDirs(stage)`, `packaging { jniLibs { useLegacyPackaging = false;
keepDebugSymbols += "**/libtquic_jni.so" } }`, and a `verifyTquicNative` task failing at build time
with an actionable message rather than at runtime with `UnsatisfiedLinkError`.

Build script details that matter: `CARGO_TARGET_DIR` inside the WSL filesystem (not `/mnt/c` — ~10×
slower for the thousands of BoringSSL files, and it can't represent the +x bits cargo expects);
`cargo ndk -t arm64-v8a -P 21` (**capital `-P`**); a trailing `llvm-nm -D | grep Java_` so symbol
problems appear in build output rather than a later debugging session. Add
`native/scripts/*.sh text eol=lf` to `.gitattributes` — a CRLF shebang produces "cannot execute:
required file not found", which reads like a missing file.

### `TquicKoogHttpClient`

`get`/`post`: `withContext(dispatcher)` → `h3Request` → `h3AwaitResponse` → loop `h3ReadBody` to EOF
→ decode. Non-2xx throws `KoogHttpClientException` with the same shape as the Ktor client so callers
can't tell the transports apart.

`sse`/`lines`: `flow { … }.buffer(n).flowOn(dispatcher)`. **`flowOn`, never `withContext { emit() }`**
— emitting from a different context throws `Flow invariant is violated`, and this is the standard
mistake when porting a blocking source into a `Flow`. `flowOn`'s channel *is* the backpressure: a
slow collector fills it, `emit` suspends, reads stop, the QUIC window stops opening, the peer stalls.
Never `Channel.UNLIMITED` — a fast LLM stream will OOM the phone.

**Cancellation needs two mechanisms.** `runInterruptible` does **not** work: `Thread.interrupt()`
sets a JVM flag a blocked native poll never observes. Instead (a) bound the native block —
`h3ReadBody(timeoutMs = 250)` returns `TIMEOUT`, loop calls `ensureActive()` each iteration, so
cancellation works even with no native cancel primitive; (b) `cancelRequest(handle)` from
`finally`/`invokeOnCancellation` for prompt shutdown. Put the "not `runInterruptible`" reason in a
comment so nobody simplifies it away.

Framing: split on the `\n` **byte** before decoding UTF-8 — `0x0A` can't occur inside a multi-byte
sequence, so this is correct by construction and needs no stateful decoder. `LineSplitter` needs a
`maxLine` guard or a peer that never sends a newline is an unbounded leak. SSE parsing must be
written here (Ktor supplied it before); `[DONE]` is not special-cased — `dataFilter` handles it.
`buf.copyOf(n)` before `emit` is mandatory, since `flowOn` buffering hands the array downstream while
the producer overwrites it.

Dispatchers: `Dispatchers.IO.limitedParallelism(n)` per session. Never `Dispatchers.Default`
(CPU-sized — a few blocked readers deadlock everything) or Main.

### Config validation — keep the enums, reject at the boundary

**`TquicConfigLoader.enumOf` silently returns the default for an unparseable value**
(`TquicConfigLoader.kt:173-176`). So *pruning* `ecf/erf/thle/thlev2` and `lia/olia` from the enums
would produce exactly the silent fallback decision #3 forbids — `thlev2` would quietly become
`minrtt`. Keep the constants for parse fidelity; reject in `TquicConfig.validate()` (already called
from `load()`, so bad XML fails at **app start**, before any request). Add a string-based
`TquicSessionParams.validate()` in `http-client-tquic` as defence in depth, since `LlmBackend.kt:56-60`
builds params by hand and bypasses `TquicConfig` entirely. Drop `enablePatfb`/`fileSizeMpScheduler`
from the JNI DTO — the config layer keeps the vocabulary, the transport DTO carries only what crosses
the wire. Annotate the XML with `choices=` (supported) and `rejected=` (refused); **both copies** of
`tquic_config.xml` are duplicated and must be edited.

Surfacing: config errors → **500, not 503** (retrying won't help) with a new branch in
`ComputeRoutes.kt`; `/v1/health`'s hard-coded `"tquic":"scaffold"` becomes live state from
`manager.state`.

### TLS provisioning

APK assets aren't real files, so PEMs must be copied to `filesDir` before `serverStart`. Use a
**content-hash compare, not an exists-check** — an app update ships new PEMs but `filesDir` survives,
so an exists-check pins the old cert forever and fails months later as "certificate expired". Write
to `.tmp` then `renameTo`. Never external storage for the key. Prefer EC P-256 and **PKCS#8** PEM
(BoringSSL may reject PKCS#1). An APK is world-readable — never ship a real private key in assets;
fine for the demo, generate on-device otherwise. Set `allowBackup="false"` (currently `true`,
`AndroidManifest.xml:10`) or the private key lands in cloud backups.

### Two real bugs in existing code, surfaced by this work

1. **`LlmBackend.kt:52-66` constructs a new `TquicKoogHttpClient` per request and never closes it.**
   Harmless while everything throws; becomes a full QUIC handshake plus a leaked session per request
   the moment the transport is real. Fix by injecting a `transportFor` provider defaulting to the old
   behaviour.
2. **`RoutingEngine.kt:44` puts the *peer's* interface IPs into `RouteDecision.localAddresses`**,
   which `MasterAgent.kt:106-107` feeds into `primaryLocalAddr` — i.e. into `bind()`. Binding a
   remote peer's IP is `EADDRNOTAVAIL`. Rename to `peerInterfaceAddresses` and source real local
   addresses from a new `AndroidNetworkStatus` (`NetworkStatus` currently exposes only
   `wifiUp`/`g5Up`, both hard-coded true).

---

## Workstream C — multi-device sample

`TquicSessionManager` (in `http-client-tquic`, keyed on a plain `TquicPeer` DTO — `master-agent`
depends on `http-client-tquic`, so the reverse edge is illegal; see `TquicSessionParams.kt:5-6`),
plus a `MultiverseTquicTransport` adapter in `master-agent` mapping `CapabilityEntry` → `TquicPeer`.

- **`TquicSession` is refcounted `AutoCloseable`**, not `Cleaner`/`finalize`. `close()` cancels live
  requests first, *then* waits for in-flight native calls, *then* frees. If the grace period expires,
  **leak the handle rather than free it** — a leaked session on a shutting-down phone beats a SIGSEGV
  that takes the foreground service with it.
- **Exactly one `TquicSession` per native handle**, created only by the manager. Clients hold
  `ownsSession = false` — the structural guarantee against double-free.
- Lazy connect by default (`warmUp()` for the demo path): the registry lists devices that may be
  unreachable, and eagerly handshaking N sessions burns radio and turns one dead device into a
  startup failure. `Mutex` per link (not `Deferred`, which stays failed forever and can't retry).
  Exponential backoff **with full jitter** — N devices failing on one Wi-Fi drop must not retry in
  lockstep. Idle eviction re-checks `lastUsed` under the lock or a request racing the sweep gets a
  session being torn down.
- `TquicServerEndpoint` uses a **bounded** `serverAccept(timeoutMs)`, so the accept loop's own
  `isActive` check is the cancellation point and no cross-thread native cancel is needed. Shutdown
  ≤500 ms, inside `onDestroy`'s existing budget.
- **Shutdown order matters:** Ktor → server endpoint → session manager (blocks briefly on in-flight
  JNI) → `scope.cancel()`. Cancelling the scope first abandons coroutines blocked inside JNI. The
  current `MasterAgentService.onDestroy` has this inverted.
- Sample covers: bring-up from `registry.json`, N pairs, streaming `sse()` from one device while
  another stays Idle, inbound serving, clean shutdown. `retryWhen` only on connect failure — never
  a half-consumed stream, or an LLM completion duplicates 200 emitted tokens.

**Android runtime:** add `WAKE_LOCK` and `ACCESS_NETWORK_STATE` (neither present). Change
`foregroundServiceType` to `dataSync|connectedDevice` and add
`FOREGROUND_SERVICE_CONNECTED_DEVICE` — Android 14 caps `dataSync` FGS at ~6 h/day then force-stops
it. Acquire a `WifiLock(WIFI_MODE_FULL_LOW_LATENCY)`: **Wi-Fi power save drops inbound UDP when the
screen is off**, the classic cause of "my listener stopped receiving". Design *for* Doze — treat
every wake as reconnect, driven by `NetworkCallback`; don't try to hold a session across a long nap.
Inbound-to-phone works on a LAN but not over cellular CGNAT.

---

## Phasing

| Phase | Deliverable |
|---|---|
| **0** | `.so` skeleton: `JNI_OnLoad` + `RegisterNatives` + `abiVersion`/`abiInfo`/`version`. Proves build → jniLibs → `System.loadLibrary` → JNI string round trip. No QUIC code. Run the IP-SAN and PKCS#8 spikes. |
| **1** | `handle.rs` + `jerr.rs` with double-free/panic tests; `runtime/` (socketset + driver) driven by a hardcoded connect. **Freeze `runtime/` here** — both client and server build on it. |
| **2** | `config.rs` rejection matrix; client request flow incl. the **blocked-stream re-pump + its regression test**; first real `get()` from Kotlin. Cancellation + thread-leak test. |
| **3** | Server endpoint + `serverAccept`/`req*`/`respond*`, with the Kotlin **loopback-proxy handler** — proves H3 end-to-end without touching `ComputeRoutes.kt`. |
| **4** | Refactor `ComputeRoutes` into a transport-agnostic handler; swap out the proxy. Session manager + multi-device sample. |
| **5** | Multipath (`addPath` + `Network.bindSocket`), stats into `/v1/routing/policy`, per-pairing certs. |

---

## Verification

A ladder, cheapest rung first — each eliminates a failure class that would otherwise be diagnosed
through `adb logcat`.

0. **Symbol contract.** `llvm-nm -D --defined-only | grep Java_` in WSL vs `javap -p -s` on the
   compiled class in Windows. `javap -s` is the only authoritative source for descriptors. Check in
   a golden `native/abi/TquicNative.descriptors.txt` and diff it in a test. Make `TquicNative`
   `public object` (currently `internal`) to remove all doubt about Kotlin name mangling.
1. **Desktop JVM before Android — the key de-risking step.** `http-client-tquic` is a plain JVM
   module, so build the crate for `aarch64-unknown-linux-gnu` with plain `cargo build` (no NDK, no
   qemu, no cargo-ndk — fast native compile), stage in `native/prebuilt/desktop/`, and run the tests
   under a WSL JDK with `java.library.path` set as a JVM arg (it's read at JVM start; setting it at
   runtime doesn't work). Exercises JNI, TLS, blocking reads, cancellation, and the SSE parser on a
   five-second edit-test loop with zero Android moving parts.
2. **Load smoke test** — `tryLoad()` + `version()`. Add a `lastLoadError` field; the current
   `tryLoad` swallows `Throwable` (`TquicNative.kt:18-27`), making "not available" indistinguishable
   from "present but missing `libc++_shared.so`". Add `loadFrom(path)` using `System.load` for tests.
3. **Loopback client ↔ server in one process** — the highest-value single test. 200 + JSON round
   trip; non-2xx → `KoogHttpClientException`; an SSE event split across two reads; a multi-byte UTF-8
   char split across a chunk boundary; `flow.take(3)` cancels within ~`POLL_MS`; `session.close()`
   while a reader is blocked → `CANCELLED`, no SIGSEGV; 200 sequential requests → no fd growth.
   **Do not subclass `BaseKoogHttpClientTest`** — its `MockWebServer` is Ktor CIO over TCP and cannot
   speak H3. Mirror its scenarios against a native H3 fixture.
4. **On-device** — `unzip -l app.apk | grep lib/` (expect **both** `.so`s), `adb logcat -s
   tquic_jni:V DEBUG:V`, `run-as … ls -l files/tquic` for the PEMs, `curl localhost:8080/v1/health`.

An `UnsatisfiedLinkError` playbook belongs in `native/README.md` — the distinct causes (not
packaged, ABI filtered out, missing STL, symbol mismatch, wrong target, API level, 16 KB page
alignment on Android 15+) have near-identical symptoms and very different fixes.

---

## Risks

| # | Risk | Mitigation |
|---|---|---|
| 1 | **`Data`-event latch deadlock** — backpressure permanently stalls a stream | Blocked-stream re-pump every loop turn + dedicated regression test. The highest-severity correctness risk in the crate. |
| 2 | **ABI drift** between `TquicNative.kt` and Rust | Colocated crate, `RegisterNatives`, golden descriptor file, `nm` in the build script |
| 3 | **WSL↔Windows build boundary** | Staged `.so` is the required path; `bash -lc`; `CARGO_TARGET_DIR` on ext4; `.gitattributes` for LF |
| 4 | **Panic across FFI = abort**, native-only tombstone | `catch_unwind` macro on every export + reactor supervisor; `panic = "unwind"` asserted in CI |
| 5 | **Use-after-free on handles** | Generation-tagged registry; refcounted session; single-owner rule; leak-over-crash on close timeout |
| 6 | **Certs**: no verification by default; `set_ca_certs` silently succeeds; IP SANs unverifiable | `set_verify` unconditional; `Path::exists()` pre-check; synthetic hostnames; log presented SANs on failure |
| 7 | **Doze / FGS caps / Wi-Fi power save** kill a long-lived listener | `connectedDevice` FGS type, `WifiLock`, design for reconnect-on-wake |
| 8 | **UDP/443 blocked** by carrier or enterprise Wi-Fi | Per-device Ktor fallback, visible in `/v1/health` — test the demo network before demo day |
| 9 | **Two independent Gradle builds**; app resolves `http-client-core` from Maven Central 1.1.1 while compiling `http-client-tquic` from source | Document that only the Android build packages the `.so`; verify any `http-client-core` API used exists in the published 1.1.1 jar, or vendor the helper |
| 10 | **Doc drift** — README claims AGP 8.2.2, build file says 8.12.3 | Fix alongside; the native build instructions land in the same README and inherited distrust is expensive |

### Unverified — do not treat as established

- `jni` 0.22 API shape (pinned to 0.21 deliberately).
- Whether `caCertsPath = "/system/etc/security/cacerts"` gives a working Android system trust store —
  `set_default_verify_paths()` uses BoringSSL's compiled-in paths, which don't exist on Android.
  Spike on-device; bundling a pinned CA is the right answer for a private server regardless.
- Whether `ai.koog:http-client-core:1.1.1` on Maven Central contains `mergeHeaders`.
- `rcgen` cross-compiling cleanly for `aarch64-linux-android` (if on-device cert generation is wanted).
