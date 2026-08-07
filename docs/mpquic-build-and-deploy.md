# mpquic + tquic-vlm-server-interface: build and deploy reference

This is a from-scratch build/deploy reference for everything built and verified in the MPQUIC /
`tquic-vlm-server-interface` effort: the multipath QUIC engine (`mpquic-jni`), the Android apps built
on it, the EC2-side MPQUIC tunnel terminus that forwards to a local VLM, and the toolchain/host
choices that actually worked versus the ones that didn't. Every command below was actually run;
where something failed first, the failure and fix are included rather than only the final working
form — those are the parts most likely to bite the next person.

## 1. Overview

```
Phone (wlan0 and/or rmnet)
   │  MPQUIC tunnel (mpquic-jni, "client" role) -- opaque relay-framed bytes
   │  over a genuine multipath QUIC connection (ALPN hq-interop)
   ▼
EC2: tquic-vlm-server-interface's embedded mpquic-jni "server" role
   │  answer_mode=forward: POSTs the tunneled request body verbatim,
   │  no repackaging, to --vlm-base-url
   ▼
Ollama (127.0.0.1:11434, same EC2 host) -- OpenAI-compatible /v1/chat/completions
```

A second, independent path exists side by side: `tquic-vlm-server-interface` also runs a plain
HTTP/3-JSON listener (`--bind`, default port 19500, path `/v1/infer`) for the older `ai.koog.tquicdemo`
Android app, which speaks a two-shape JSON contract (see that app's own `TquicDemoController.kt`) and
never touches the MPQUIC tunnel machinery at all. This document covers the MPQUIC path; the plain
path needs no separate build steps beyond the same `cargo build --release` for
`tquic-vlm-server-interface`.

## 2. Repo layout

| Path | What | Existing docs |
|---|---|---|
| `mpquic/mpquic-jni/` | The engine: Rust `cdylib`+`rlib`, JNI bridge + plain-library API, mio-driven QUIC/multipath reactor over `tquic`. | `mpquic/README.md` |
| `mpquic/android/{core,client,server}` | Gradle/Kotlin apps: `:core` (shared JNI bridge + assets), `:client`, `:server`. | `mpquic/README.md` |
| `mpquic/linux/mpquic-cli` | Thin console frontend over the same engine, no JNI/Android. | `mpquic/linux/README.md` |
| `mpquic/tquic/` | **Gitignored** clone of Tencent/tquic + BoringSSL submodule. `mpquic-jni`'s `Cargo.toml` expects it as a sibling path (`tquic = { path = "../tquic" }`). | — |
| `mpquic/apks/` | Checked-in prebuilt debug APKs (`client-debug.apk`, `server-debug.apk`). | — |
| `mpquic/tools/` | `h3_sender.py` (raw HTTP/3 POST via aioquic), `build_vlm_request.py` (base64+JSON-wrap an image+prompt into the OpenAI chat-completion shape), `udp_sender.py`. | — |
| `tquic-vlm-server-interface/` | Separate binary at repo root. `tquic` from **crates.io** here, not a local clone. `mpquic-jni` is a sibling-path dependency (`../mpquic/mpquic-jni`), so this crate and `mpquic/` must be siblings on disk. | `tquic-vlm-server-interface/README.md`, `tquic-vlm-server-interface/docs/interface-guide.md`, `tquic-vlm-server-interface/docs/mpquic-tunnel-verification.md` |

## 3. Build dependencies

### 3a. `tquic-vlm-server-interface` (any x86_64 Linux host, e.g. the EC2 box)

- Rust 1.90+ (`rustup`).
- `build-essential cmake perl pkg-config` — `tquic` (from crates.io here) vendors and cmake-builds
  BoringSSL as part of its own build script; these are its build-time requirements, not anything
  `tquic-vlm-server-interface` uses directly.

### 3b. `mpquic-jni` native library, cross-compiled for Android

- Rust + the Android rustup targets:
  ```
  rustup target add aarch64-linux-android x86_64-linux-android
  ```
- [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk): `cargo install cargo-ndk`.
- Android NDK (r27c tested; matches `mpquic/README.md`'s documented `27.2` expectation). Get the
  Linux x86_64 build from
  `https://dl.google.com/android/repository/android-ndk-r27c-linux.zip`.

  **Extract this with real `unzip`, not Python's `zipfile` module.** The NDK's Clang toolchain is
  full of symlinks (e.g. `clang -> clang-18`); `python3 -m zipfile -e` does not restore them — it
  silently writes the link *target string* as a tiny regular file instead. The build then fails deep
  into the BoringSSL compile with `... failed to start: Permission denied (os error 13)`, which reads
  like a permissions problem and has nothing obviously to do with the real cause. Symptom vs. fix:
  ```
  $ ls -la android-ndk-r27c/.../bin/clang
  -rwxrwxr-x 1 ubuntu ubuntu 8 ...  clang        # WRONG: 8-byte "file", not a symlink
  ```
  ```bash
  sudo apt-get install -y unzip     # if not already present
  rm -rf android-ndk-r27c
  curl -sL -o ndk.zip https://dl.google.com/android/repository/android-ndk-r27c-linux.zip
  unzip -q ndk.zip && rm ndk.zip
  ls -la android-ndk-r27c/toolchains/llvm/prebuilt/linux-x86_64/bin/clang
  # correct: lrwxrwxrwx ... clang -> clang-18
  ```
- A local `tquic` clone with the BoringSSL submodule initialized, as a sibling of `mpquic-jni/`:
  ```bash
  git clone --branch v1.6.0 https://github.com/tencent/tquic.git mpquic/tquic
  cd mpquic/tquic && git submodule update --init --recursive
  ```
  (A shallow `--depth 1` clone does **not** fetch submodules on its own — the above two-step form is
  required, or add `--recurse-submodules` to the initial clone instead.)

### 3c. Android APK packaging (Gradle)

- JDK 17 (`sudo apt-get install -y openjdk-17-jdk-headless` on Linux). AGP here is 8.7.3, which needs
  JDK 17 or 21 — **not** anything newer (a JDK whose version string AGP 8.7.3 can't parse, e.g. JDK
  25, fails project configuration with a cryptic `IllegalArgumentException: 25.0.2` — this is a
  known issue with this exact AGP version across this whole hackathon repo, not specific to mpquic).
- Android SDK: `platform-tools`, `platforms;android-35`, `build-tools;35.0.0` (matches this project's
  `compileSdk`/`targetSdk 35`). Via the command-line tools + `sdkmanager`:
  ```bash
  mkdir -p android-sdk/cmdline-tools && cd android-sdk/cmdline-tools
  curl -sL -o tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q tools.zip && mv cmdline-tools latest && rm tools.zip
  cd ../.. 
  yes | android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$(pwd)/android-sdk" \
    "platform-tools" "platforms;android-35" "build-tools;35.0.0"
  ```
- Gradle 8.13. **No wrapper (`gradlew`) is checked into `mpquic/android/`** and no bundled
  distribution either — despite `mpquic/README.md` referencing a local `gradle-8.13/` folder, it
  isn't present on disk. Fetch the distribution directly:
  ```bash
  curl -sL -o gradle.zip https://services.gradle.org/distributions/gradle-8.13-bin.zip
  unzip -q gradle.zip && rm gradle.zip
  ```

## 4. Where to build — and why it matters

**Build on a genuine x86_64 Linux host if at all possible (EC2 already has one).** This was a real,
measured difference this session, not a theoretical concern:

- This Windows dev machine is **ARM64**. It has no local Rust/Cargo install at all — every Rust build
  all session ran over SSH on EC2.
- A WSL2 distro on this same machine is also ARM64 Linux. It already had `cargo-ndk` + NDK r29
  installed from prior work, so the native `mpquic-jni` build was attempted there first — but the
  NDK's prebuilt Clang toolchain is **x86_64-only**, so every single compiler invocation ran through
  `qemu-binfmt` user-mode emulation. Minutes in, it was still compiling individual BoringSSL crypto
  source files one at a time.
- EC2 (`54.190.37.190`) is genuinely `x86_64` (`uname -m`). Installing the same toolchain there (JDK
  17, NDK r27c, `cargo-ndk`, Android SDK, Gradle 8.13 — none of it present before) and running the
  identical `cargo ndk` build for **both** ABIs (arm64-v8a + x86_64) finished in **about a minute** —
  no emulation anywhere in the chain. The follow-on Gradle/AAPT2/D8 APK packaging step (which also
  risks ARM64-host emulation for the SDK's native x86_64 build-tools) took **~1m35s** there too.

Bottom line: unless you specifically need to build on ARM64, do the Android native cross-compile and
the Gradle packaging step on x86_64 Linux. If EC2 already hosts `tquic-vlm-server-interface`, it's
also the natural place to build the Android side — no new infrastructure needed, and it already has
Rust and a `tquic` clone.

## 5. Building `tquic-vlm-server-interface`

```bash
# On EC2 (or any x86_64 Linux host with the 3a dependencies):
cd ~/tquic-vlm-server-interface
cargo build --release
```

Cert/key: defaults to `certs/server.crt`/`certs/server.key` (self-signed demo cert, same posture as
the Android demo apps — `verify_peer=false` on every known client, so cert *content* is
non-blocking). `--mpquic-cert`/`--mpquic-key` override for the MPQUIC listener specifically; both
default to `--cert`/`--key` if unset.

**Running it** (the actual invocation used this session):
```bash
RUST_LOG=info nohup ./target/release/tquic-vlm-server-interface \
  --bind 0.0.0.0:19500 \
  --mpquic-bind '[::]:10000' \
  --vlm-base-url http://127.0.0.1:11434/v1 \
  --vlm-model qwen3-vl:8b \
  --vlm-timeout-ms 120000 \
  < /dev/null > ~/server-mpquic.log 2>&1 &
disown
```
- `--bind`: the plain HTTP/3-JSON listener (`ai.koog.tquicdemo`'s target).
- `--mpquic-bind`: the MPQUIC tunnel terminus. **Dual-stack (`[::]`) by default** as of this session —
  relies on the OS accepting IPv4 as v4-mapped on the same socket
  (`net.ipv6.bindv6only=0`, the Linux default; confirmed on this EC2 host via `sysctl
  net.ipv6.bindv6only`). If that sysctl were `1`, the dual-stack bind would silently become
  IPv6-only and break every IPv4 client — worth checking on any new host before relying on this.
- For a real second (rmnet/cellular) network path to actually reach this listener over IPv6, the
  **host itself** needs a real, externally-routable global IPv6 address — this was independently
  verified this session (`2600:1f14:2054:7dfa:cd55:3b81:b63a:3bd6`, a genuine round trip from a phone
  on real cellular data, not just same-host loopback). Enabling this on a fresh EC2 instance is a
  VPC/subnet "auto-assign IPv6" + a security-group rule for inbound UDP/IPv6 — outside this document's
  scope, but necessary if that host doesn't already have one.

## 6. Building the mpquic Android client APK

The exact sequence proven this session, on EC2 (having done §3b/§3c above):

```bash
# 1. Native library, both ABIs, straight into the Android project's jniLibs:
export ANDROID_NDK_HOME=~/android-ndk-r27c
cd ~/mpquic/mpquic-jni
cargo ndk -t arm64-v8a -t x86_64 -o ../android/core/src/main/jniLibs build --release

# 2. Point Gradle at the SDK:
echo "sdk.dir=$(pwd)/../../android-sdk" > ~/mpquic/android/local.properties   # adjust path as needed

# 3. Build the APK:
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64   # path varies by distro
cd ~/mpquic/android
~/gradle-8.13/bin/gradle :client:assembleDebug
# APK lands at android/client/build/outputs/apk/debug/client-debug.apk
```

`:server:assembleDebug` builds the counterpart server app the same way, if needed.

**Debug-signature gotcha**: a fresh Gradle build signs with whatever debug keystore Gradle
auto-generates on that machine — which will **not** match the signature of any previously-installed
build from a different machine (e.g. the prebuilt APKs checked into `mpquic/apks/`). Installing over
a mismatched signature fails with:
```
INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.mpquic.client signatures do not match newer version; ignoring!
```
Fix: `adb uninstall com.mpquic.client` (or `com.mpquic.server`) before installing the new build, on
any device that already has a differently-signed copy.

## 7. Process management on EC2

The pattern used all session for starting/restarting the server: `nohup ... & disown`, redirecting
output to a log file, then **verifying via a fresh SSH connection** rather than trusting the
backgrounded command's own output — backgrounding a process this way over an `ssh user@host "cmd &
disown"` invocation reliably hangs the *invoking* SSH channel itself (the child keeps the channel's
file descriptors open), so the tool running that command sees it "time out" and gets moved to
background, even though the remote command itself started fine. The reliable check is a second,
independent SSH connection:
```bash
ssh ubuntu@<host> "pgrep -af tquic-vlm-server-interface; sudo ss -ulnp | grep -E '10000|19500'"
```
To restart: find the PID via `pgrep -af tquic-vlm-server-interface`, `kill <pid>`, confirm the port
is free (`ss -ulnp`), then re-run the start command above.

## 8. Verifying it works

**Basic request/response**, reusable end to end:
```bash
python mpquic/tools/build_vlm_request.py <path-to.jpg> --prompt "..." -o request.json
python mpquic/tools/h3_sender.py <phone-ip> request.json --port 47443 --path infer \
  --content-type application/json
```
(`47443` assumes the MPQUIC Client app's "HTTP/3 RX" listener is running on its default port; the
phone must already be connected to the EC2 `--mpquic-bind` address via the app's Connect button.)

A real success is corroborated across **three independent sources**, not just a `200`:
- The laptop's own `h3_sender.py` output (`response 200, N B body ...`).
- The phone's `client.log` (`/sdcard/mpquic/client.log`): `h3 request complete on stream 0, N B body`
  and `h3 response 200 N B <- tunnel stream ...` — byte counts should match the laptop's exactly.
- On EC2, Ollama's own journal (`journalctl -u ollama`): a `[GIN] ... 200 ... 127.0.0.1 ... POST
  "/v1/chat/completions"` line in the same time window, confirming the request actually reached
  Ollama and wasn't, say, silently short-circuited somewhere in between.

**Multipath-specific checks**, in `client.log`:
- `connected (multipath=true)` — confirms the server actually negotiated the capability, not just
  that the client requested it (`enable_multipath` defaults to `false` server-side if the config key
  is ever omitted — this was a real, previously-shipped bug this session, not a hypothetical).
- `path added <local> -> <remote>` for each additional local address — vs. `path_skipped` /
  `"address family differs from server"`, which means that local candidate's family didn't match any
  configured remote.
- The app's stats panel (or `client.log`, though per-path byte counts are UI-only and not logged to
  file) showing **non-zero `tx`/`rx` on more than one path after a real transfer** — the only way to
  confirm paths are actually carrying traffic simultaneously, not just that more than one was added.

## 9. Known gaps / not yet done

- **rmnet+wlan mixed-family multipath is implemented and built, not yet device-verified.** The
  `connect_to_alt`/`remote_for()` change (client picks whichever of two configured remote addresses
  matches a given local candidate's family) compiles clean and passes the full existing test suite,
  and the network path itself (EC2's dual-stack listener, external IPv6 reachability) is independently
  proven — but whether tquic's `Connection::add_path` genuinely tolerates a *different remote IP* per
  path (as opposed to only the local-address diversity already proven this session) hasn't been
  exercised on a real device yet.
- No automated CI or test harness for the Android side — verification this session was real-device,
  manual, evidence-gathered-by-hand (screen recordings, log pulls, cross-referenced timestamps).
- Single EC2 instance, no redundancy/HA; the process-management pattern in §7 is manual, not a
  systemd unit or equivalent.
