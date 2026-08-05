# MPQUIC Android — Multipath QUIC client/server apps on TQUIC

Two Android apps (client + server) built on [Tencent TQUIC](https://github.com/Tencent/tquic)
that carry **arbitrary application payload** (opaque bytes on QUIC streams — no
application protocol imposed) over **Multipath QUIC**.

## Layout

| Path | What |
|---|---|
| `tquic/` | Clone of Tencent/tquic (BoringSSL submodule included). One local patch in `src/build.rs` (see below). |
| `mpquic-jni/` | Rust crate (`cdylib`) — JNI bridge + mio event loop driving a tquic `Endpoint` in client or server role. |
| `android/` | Gradle project: `:core` (shared Kotlin + jniLibs + TLS cert assets), `:client`, `:server`. |
| `gradle-8.13/` | Local Gradle distribution used to build. |

## Features (configurable in both app UIs)

- **Multipath**: on/off, plus client-side list of local IPs — one QUIC path is
  created per IP (first = initial path, others added after handshake via
  `conn.add_path`).
- **Multipath scheduler**: `minrtt`, `redundant`, `roundrobin`.
- **Congestion control**: `bbr`, `cubic`, `bbr3`, `copa`.
- **Log level**: `off` … `trace` (tquic's own logs streamed into the app UI).
- Server: echo toggle; both: live per-path stats (SRTT, cwnd, bytes, loss).

## Kotlin ⇄ JNI ⇄ Rust: how the pieces connect

Both apps use the same three-layer bridge that lives in the shared `:core`
module (so client and server differ only in their UI and the JSON config they
build):

```
MainActivity (client or server app)
   │  builds config JSON, wires buttons, renders logs/stats
   ▼
EngineController (core, Kotlin)          ← lifecycle + 200 ms UI-thread poller
   │  start(json) / stop() / send(bytes)
   ▼
TquicBridge (core, Kotlin object)        ← the JNI boundary
   │  System.loadLibrary("mpquic_jni")
   │  external fun nativeStart/nativeStop/nativeSend/nativePoll
   ▼
libmpquic_jni.so (Rust, mpquic-jni crate)
   │  #[no_mangle] Java_com_mpquic_core_TquicBridge_nativeStart(...)  etc.
   │  spawns the "mpquic-engine" thread
   ▼
engine thread: mio poll loop → tquic Endpoint → UDP sockets (one per path)
```

### 1. Symbol binding — how Kotlin finds the Rust functions

`TquicBridge` is a Kotlin `object` whose `init` block calls
`System.loadLibrary("mpquic_jni")`, which loads
`lib/<abi>/libmpquic_jni.so` out of the APK. Its `external fun`s have **no
Kotlin body** — at first call the ART runtime resolves each one by *name
mangling convention*: package + class + method with dots turned into
underscores:

| Kotlin (`com.mpquic.core.TquicBridge`) | Rust export in `mpquic-jni/src/lib.rs` |
|---|---|
| `nativeStart(configJson: String): Int` | `Java_com_mpquic_core_TquicBridge_nativeStart` |
| `nativeStop()` | `Java_com_mpquic_core_TquicBridge_nativeStop` |
| `nativeSend(data: ByteArray): Int` | `Java_com_mpquic_core_TquicBridge_nativeSend` |
| `nativePoll(): String` | `Java_com_mpquic_core_TquicBridge_nativePoll` |

The Rust side marks these `#[no_mangle] pub extern "system"` so the symbol
names survive compilation exactly. That's the whole contract — rename either
side and the link breaks at runtime (`UnsatisfiedLinkError`).

### 2. Crossing the boundary — what each call does

- **`nativeStart(json)`** — the *only* configuration channel. Kotlin builds a
  `JSONObject` (role, `connect_to`/`listen`, `local_addresses[]`, multipath
  on/off, scheduler, congestion control, log level, cert paths…), Rust
  deserializes it with serde into `BridgeConfig`. Returns `0` on success.
  Internally it creates a mio `Poll` + `Waker`, an mpsc command channel, and
  spawns the engine thread; handles are stashed in a global
  `Mutex<Option<EngineHandle>>` (one engine per process — which is why client
  and server are separate APKs/processes).
- **`nativeSend(bytes)`** — copies the `ByteArray` into a `Vec<u8>`, pushes a
  `Cmd::Send` onto the channel, and pokes the `Waker` so the engine thread
  wakes immediately even if it's parked in `poll()`. The engine writes the
  bytes to a QUIC bidi stream (created lazily per connection). The payload is
  never interpreted — any protocol's bytes ride through unchanged.
- **`nativePoll()`** — the *return* channel. Rust never calls up into Kotlin
  (calling Java from a random Rust thread needs JNIEnv attachment and is easy
  to get wrong). Instead, everything the engine wants to tell the app — tquic
  log lines and structured events — is pushed as strings into a global
  bounded queue (`output.rs`). `EngineController` polls this every 200 ms on
  the UI thread and gets one string with records joined by ASCII 0x1E:
  - `L|<level>|<message>` — a log line from the `log` crate (tquic's own
    logging), pre-filtered by the configured log level;
  - `E|{json}` — engine events: `connected`, `path_added`, `data` (with byte
    count + printable preview), `stats` (per-path SRTT/cwnd/bytes/loss every
    second), `error`, `stopped`…
  `EngineController` splits, parses, and dispatches them to the activity's
  `onLog` / `onEvent` callbacks, which append to the log view / stats panel.
- **`nativeStop()`** — flips the engine's `AtomicBool`, wakes it, joins the
  thread. The engine closes the QUIC connection(s) on the way out.

### 3. Threading model

Only two threads matter: the **UI thread** (all Kotlin; JNI calls are cheap
and non-blocking — start/send/stop just hand work to the engine) and the
**engine thread** (owns the tquic `Endpoint`, all sockets, and all QUIC
state; nothing tquic-related is shared across threads). The mpsc channel +
waker carries commands in; the output queue carries logs/events out. Both
sides are panic-fenced (`catch_unwind`) so a Rust bug can't take down the JVM
with an abort.

## How data flows

Kotlin UI → `TquicBridge.nativeSend(bytes)` → engine thread → QUIC bidi stream
→ (multipath scheduling in tquic) → server engine → `data` event to server UI,
and echoed back if enabled. Payload is protocol-agnostic raw bytes.

TLS: server uses a bundled self-signed cert (`android/core/src/main/assets/`);
tquic clients don't verify certs by default (demo setup).

## Build

Prereqs: Rust 1.90 (`x86_64-pc-windows-msvc` host on this ARM64 machine —
there is no ARM64 MSVC linker installed), cargo-ndk, Android NDK 27.2 + cmake
3.22 (via sdkmanager), JDK 17, VS Build Tools (vcvarsall).

```bat
:: 1) Native lib for both ABIs (from a vcvarsall x64 shell)
set ANDROID_NDK_HOME=%LOCALAPPDATA%\Android\Sdk\ndk\27.2.12479018
set CMAKE_GENERATOR=Ninja
set PATH=%LOCALAPPDATA%\Android\Sdk\cmake\3.22.1\bin;%PATH%
cd mpquic-jni
cargo ndk -t arm64-v8a -t x86_64 -o ..\android\core\src\main\jniLibs build --release

:: 2) APKs
cd ..\android
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot
..\gradle-8.13\bin\gradle :client:assembleDebug :server:assembleDebug
```

APKs land in `android/client/build/outputs/apk/debug/` and
`android/server/build/outputs/apk/debug/`.

### Local patch to tquic

`tquic/src/build.rs` — `get_boringssl_build_sub_dir()` used
`cfg!(target_env = "msvc")`, which reflects the *host* when cross-compiling
from Windows to Android, so it looked for BoringSSL libs in `build/Release`
while the Ninja generator emits them in `build/`. Patched to check
`CARGO_CFG_TARGET_ENV` (the target) instead. Worth upstreaming.

## Using the apps

1. **Server device**: install server APK, pick listen address (default
   `0.0.0.0:4433`), choose algorithms + log level, Start. Its IPs are shown at
   the top.
2. **Client device**: enter `serverIP:4433`, optionally list its own local IPs
   (e.g. Wi-Fi + cellular) comma-separated for multipath, choose scheduler
   (`redundant` duplicates every packet on all paths), Connect, then Send text
   or the 1 MiB test payload. Stats show per-path SRTT/cwnd/bytes so you can
   watch traffic split across paths.

Android note: sending from a source IP whose network is not the default route
(e.g. cellular while Wi-Fi is up) may require holding that network via
`ConnectivityManager.requestNetwork`; on typical demo setups (two interfaces
both usable, or emulator) plain per-IP socket binding works.

## Emulator quick test

Server app on emulator A: listen `0.0.0.0:4433`. Client app on emulator B:
connect to `10.0.2.2:<hostport>` after `adb -s emulator-A forward` /
`redir add udp:...`, or run both apps on one emulator with client target
`127.0.0.1:4433`.
