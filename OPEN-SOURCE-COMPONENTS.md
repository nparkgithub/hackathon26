# Open Source Components

Third-party components used by this project, with upstream sources, licenses, and the versions
this repository actually pins. Verified against upstream on 2026-08-06.

## Summary

| Component | Upstream | License | Version here |
|---|---|---|---|
| Android TTS + STT | [aosp-mirror/platform_frameworks_base][aosp] | Apache-2.0 (API only) | platform API, `glass` module |
| Video Showcase (RayNeo X3 Pro) | [rayneo-develop/VideoShowCase][vsc] | MIT | submodule @ `41f728d` |
| mDNS — python-zeroconf | [python-zeroconf/python-zeroconf][zc] | **LGPL-2.1-or-later** | `zeroconf>=0.132` |
| psutil | [giampaolo/psutil][psutil] | BSD-3-Clause | `psutil>=5.9` |
| Koog AI Agent | [JetBrains/koog][koog] | Apache-2.0 | `1.0.0-preview7` |
| Tencent QUIC (TQUIC) | [Tencent/tquic][tquic] | Apache-2.0 | `1.6.0`, feature `h3` |
| Qwen3-VL 4B (on-device) | [QwenLM/Qwen3-VL][qwen] · [HF 4B][qwen4b] | Apache-2.0 | `qwen/qwen3-vl-4b` |
| Qwen3-VL 8B (server) | [QwenLM/Qwen3-VL][qwen] · [HF 8B][qwen8b] | Apache-2.0 | — |
| Ktor | [ktorio/ktor][ktor] | Apache-2.0 | `3.3.3` (OkHttp engine) |
| OkHttp | [square/okhttp][okhttp] | Apache-2.0 | via Ktor engine |
| BoringSSL | [google/boringssl][bssl] | Mixed (OpenSSL/SSLeay + ISC) | vendored by TQUIC |

## Components

### Android TTS and STT — spoken input and voice output on the glasses

Platform APIs, not bundled dependencies:

- `android.speech.tts.TextToSpeech` — speaks answers aloud
- `android.speech.SpeechRecognizer` / `RecognizerIntent` — turns a spoken query into text

Both run in the **`glass` module of Video Showcase** — the RayNeo X3 Pro side — not in
`local_llm/mdns/devmon`:

- `glass/src/main/java/com/example/video/show/glass/capture/Speaker.kt`
- `glass/src/main/java/com/example/video/show/glass/capture/SpeechToText.kt`

Sources for the API surface:

- <https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/speech/tts/TextToSpeech.java>
- <https://developer.android.com/reference/android/speech/tts/TextToSpeech>
- <https://developer.android.com/reference/android/speech/SpeechRecognizer>

> **The APIs are Apache-2.0; the engines behind them are not open source.** This code deliberately
> pins both to Google's proprietary implementations rather than following the system default —
> `com.google.android.tts` for synthesis, and Google's `RecognitionService` component for
> recognition. The reason is recorded in `Speaker.kt`: Android clears `tts_default_synth` (and the
> equivalent recognizer setting) when the configured package enters the stopped state, which the
> Mercury launcher triggers by force-stopping the Google package when it leaves the foreground.
>
> Attribute the AOSP API surface as Apache-2.0. The speech engines themselves are proprietary
> Google components present on the device, and belong in a "proprietary platform services" line
> rather than an open-source list.

### Video Showcase — video capture and playback on RayNeo smart glasses

Android Wi-Fi Direct streaming project: a `glass` module captures H.264 video (plus optional
64 kbps AAC audio) on RayNeo X3 Pro glasses and streams it over TCP to an `app` module on a relay
phone, which plays it locally and republishes via RTMP.

Modules: `glass` (runs on the glasses — capture, plus the TTS/STT voice loop above) and `app` (the
relay phone).

- Repository: <https://github.com/rayneo-develop/VideoShowCase> (MIT)
- Wired in as a submodule: `.gitmodules` → path `VideoShowCase`, branch `hackathon26-arfood`

> The submodule is **declared but not initialized** in this checkout — `git submodule status` shows
> a leading `-` and `VideoShowCase/` is empty. Run `git submodule update --init VideoShowCase`
> (needs SSH access to the `git@github.com:` remote) before building or auditing it.

> **Attribution note:** upstream is the `rayneo-develop` account, which is an individual GitHub
> user rather than a verified RayNeo organization — so the name implies affiliation but does not
> establish it. Credit it as an MIT-licensed project by that author. RayNeo's own platform is
> separate and not open source; cite <https://www.rayneo.com/pages/developer> and
> <https://rayneo-en.gitbook.io/rayneo-devdoc/> if you need to reference the hardware SDK.
>
> **URL mismatch:** `.gitmodules` still points the submodule at
> `git@github.com:sukoonsarin/VideoShowCase.git`, a different account from the upstream cited here.
> Reconcile the two before publishing, so the documented source matches what actually gets cloned.

### mDNS (python-zeroconf) — zero-config device and service discovery

Pure-Python multicast DNS service discovery. Drives the PC-side discovery of the Android
`_devmon._tcp.local.` service: PTR → SRV → A/AAAA resolution, preferring IPv4 over IPv6 link-local.

- Repository: <https://github.com/python-zeroconf/python-zeroconf>
- Used by: `local_llm/mdns/discover_and_report.py`, pinned in `local_llm/mdns/requirements.txt`

### Koog AI Agent — AI workflow orchestration over an OpenAI-compatible API

JetBrains' JVM/Kotlin agent framework. Used here for its OpenAI-compatible client: prompt
construction with image attachments, and the call into the reporter-supplied endpoint.

- Repository: <https://github.com/JetBrains/koog> · Docs: <https://docs.koog.ai/>
- Used by: `local_llm/mdns/devmon/app/src/main/java/com/example/devmon/OpenAiAnalysisClient.kt`
- Declared in: `local_llm/mdns/devmon/app/build.gradle.kts`
  (`ai.koog:prompt-executor-openai-client`, `ai.koog:http-client-ktor`)

> `1.0.0-preview7` is a preview build, not a stable release.

### Tencent QUIC (TQUIC) — secure, low-latency, multi-path transport

IETF QUIC implementation in Rust with HTTP/3, multipath, and pluggable congestion control
(CUBIC / BBR / COPA). Carries the phone → server vision-inference path over HTTP/3.

- Repository: <https://github.com/Tencent/tquic> · Docs: <https://tquic.net/>
- Cargo dependency: `tquic-vlm-server-interface/Cargo.toml` → `tquic = "1.6.0", features = ["h3"]`
- Also declared as a submodule: `.gitmodules` → path `mpquic/tquic`

> **Three copies in this tree.** The Cargo dependency resolves from crates.io; the `mpquic/tquic`
> submodule is declared but not initialized; and an untracked `tquic/` clone sits at the repo root.
> Confirm which one actually builds before citing a version.

### Qwen3-VL — vision-language inference

Alibaba Cloud's multimodal model series. Two deployments: 4B on-device for low-latency local
inference, 8B server-side for stronger reasoning.

- Repository: <https://github.com/QwenLM/Qwen3-VL>
- Weights: <https://huggingface.co/Qwen/Qwen3-VL-4B-Instruct> · <https://huggingface.co/Qwen/Qwen3-VL-8B-Instruct>
- The local model label is declared in `local_llm/mdns/llm_info.json` and sent to the phone in each
  telemetry frame.

### Ktor and OkHttp — HTTP client stack

Ktor is Koog's HTTP layer; the OkHttp engine backs it on Android. Every OpenAI-compatible call in
`OpenAiAnalysisClient` goes through this stack over TCP (HTTP/1.1 or HTTP/2 — neither speaks
HTTP/3, which is why the TQUIC path exists separately).

- Repositories: <https://github.com/ktorio/ktor> · <https://github.com/square/okhttp>
- Declared in: `local_llm/mdns/devmon/app/build.gradle.kts` (`io.ktor:ktor-client-okhttp:3.3.3`)

### BoringSSL — TLS for QUIC

Not a direct dependency. TQUIC vendors and cmake-builds it, which is why building the Rust side
requires `build-essential cmake perl pkg-config` rather than Cargo alone.

- Repository: <https://github.com/google/boringssl>
- Vendored at: `tquic/deps/boringssl`

## Subsystem detail: `local_llm/mdns` (devmon)

The zero-config device monitor — an Android app that advertises `_devmon._tcp.local.` and ingests
telemetry, plus a Python client that discovers it. This is the complete dependency set for that
subsystem, at a finer grain than the headline table above.

### Android app — declared in `local_llm/mdns/devmon/app/build.gradle.kts`

| Dependency | Version | License | Role here |
|---|---|---|---|
| `androidx.core:core-ktx` | 1.13.1 | Apache-2.0 | Kotlin extensions over platform APIs |
| `androidx.appcompat:appcompat` | 1.7.0 | Apache-2.0 | `AppCompatActivity` base for `MainActivity` |
| `com.google.android.material:material` | 1.12.0 | Apache-2.0 | Material 3 theme and widgets |
| `androidx.constraintlayout:constraintlayout` | 2.1.4 | Apache-2.0 | main activity layout |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.8.7 | Apache-2.0 | `lifecycleScope` / `repeatOnLifecycle` driving `render()` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.9.0 | Apache-2.0 | `StateFlow` state model; accept loops in `AdvertiserService` and `HttpIngestServer` |
| `ai.koog:prompt-executor-openai-client` | 1.0.0-preview7 | Apache-2.0 | OpenAI-compatible vision call in `OpenAiAnalysisClient` |
| `ai.koog:http-client-ktor` | 1.0.0-preview7 | Apache-2.0 | Koog's HTTP abstraction layer |
| `io.ktor:ktor-client-okhttp` | 3.3.3 | Apache-2.0 | concrete HTTP engine Android needs at runtime; pulls in OkHttp transitively |

### Build tooling

| Tool | Version | License |
|---|---|---|
| Android Gradle Plugin | 8.7.3 | Apache-2.0 |
| Kotlin Android plugin | 2.3.10 | Apache-2.0 |
| Gradle (wrapper) | 8.13 | Apache-2.0 |

Requires JDK 17 or 21 on `JAVA_HOME`; this AGP/Kotlin combination cannot parse newer JDK version
strings. `compileSdk` 36, `minSdk` 35 (Koog's Android OpenAI transport requires API 35).

### Platform APIs — used, not bundled

Not third-party dependencies, but worth listing since they do load-bearing work:

- **`android.net.nsd.NsdManager`** — mDNS service registration in `AdvertiserService.register()`.
- **`org.json`** — `Telemetry.from()` and every JSON response body. Android ships a clean-room
  Apache-2.0 implementation, *not* the original json.org code with its "Good, not Evil" clause, so
  this carries no unusual license obligation.
- **`java.net.ServerSocket` / `Socket`** — the TCP telemetry server and the HTTP ingest server.

Worth noting what is *absent*: there is no embedded HTTP server library, no JSON parsing library,
and no multipart parser. `HttpIngestServer.kt` hand-rolls all three against the platform socket
APIs, which keeps this subsystem's third-party surface to the nine artifacts above.

### Python client — declared in `local_llm/mdns/requirements.txt`

| Dependency | Version | License | Role here |
|---|---|---|---|
| `zeroconf` | >= 0.132 | **LGPL-2.1-or-later** | mDNS discovery: PTR → SRV → A/AAAA resolution |
| `psutil` | >= 5.9 | BSD-3-Clause | CPU, memory and network-interface telemetry |

## Transport protocols: why two HTTP stacks

This project carries both an HTTP/1.1 stack and an HTTP/3 stack. They are not redundant — the split
is forced by a capability gap, and it explains why TQUIC and BoringSSL appear in the dependency list
at all.

### Where each protocol is spoken

| Protocol | Transport | Component | Where in this repo |
|---|---|---|---|
| HTTP/1.1 | TCP | hand-rolled server | `HttpIngestServer.kt` — `POST /analyze`, `GET\|POST /health` on :8080 |
| HTTP/1.1 or /2 | TCP | Ktor + OkHttp | `OpenAiAnalysisClient.kt` → the peer's OpenAI-compatible endpoint |
| HTTP/3 | **QUIC over UDP** | TQUIC (Rust) | `tquic-vlm-server-interface` — `POST /v1/infer`, ALPN `h3` |
| — (not HTTP) | TCP | newline-delimited JSON | `AdvertiserService` telemetry stream on :47531 |

### What changes between HTTP/1.1 and HTTP/3

The request/response semantics are identical — a method, a path, headers, a body, a status code.
Only framing and transport differ:

| | HTTP/1.1 | HTTP/2 | HTTP/3 |
|---|---|---|---|
| Transport | TCP | TCP | **QUIC over UDP** |
| Framing | ASCII text, `\r\n` delimited | binary frames, HPACK | binary frames, QPACK |
| Multiplexing | none | many streams, one connection | many streams, one connection |
| Head-of-line blocking | per connection | **transport-wide** — one lost packet stalls every stream | **per stream only** |
| TLS | optional layer above | optional layer above | mandatory, fused into the handshake |
| Handshake cost | TCP + TLS ≈ 2–3 RTT | TCP + TLS ≈ 2–3 RTT | 1 RTT, or 0 RTT on resumption |
| Survives an IP change | no — keyed on the 4-tuple | no | **yes** — keyed on a Connection ID |
| Congestion control | kernel | kernel | userspace, selectable (CUBIC / BBR / COPA) |

### Why both stacks exist here

**No JVM HTTP/3 client or server exists.** OkHttp speaks HTTP/1.1 and HTTP/2 only; Ktor has no H3
engine. Since Koog delegates to Ktor, every OpenAI-compatible call from Android is necessarily TCP.
Reaching HTTP/3 from the JVM therefore requires a native QUIC implementation — which is exactly why
this project vendors TQUIC (Rust) and, transitively, BoringSSL, rather than adding another Gradle
dependency.

**The two stacks serve different links.** HTTP/3's advantages — per-stream loss isolation, 0-RTT
resumption, connection migration across an IP change — pay off on lossy, high-latency, or roaming
links, i.e. the phone-to-server hop. They are worth close to nothing on loopback or a clean
single-subnet LAN, which is what `local_llm/mdns/` targets. Keeping HTTP/1.1 there is a deliberate
fit to the link, not a legacy leftover.

### Practical consequences of the HTTP/3 path

- **UDP, not TCP** — firewall rules differ (`ufw allow 19500/udp`), and some corporate or guest
  networks throttle or drop UDP wholesale.
- **TLS is not optional** — there is no plaintext QUIC, so the H3 server needs a certificate
  (`certs/server.crt`) even for local testing.
- **`adb forward` does not help** — it is TCP-only, so the H3 path cannot be tunnelled to a desktop
  for testing the way the `:8080` and `:47531` TCP paths can.
- **Standard tools mostly do not work** — `curl` needs a build linked against a QUIC-capable TLS
  backend, which most distribution packages are not. This is why the crate ships its own
  `src/bin/test_client.rs` and `scripts/mock_client_demo.sh` instead of relying on `curl`.

## License notes

Everything here is permissive (Apache-2.0 / MIT) **except**:

- **python-zeroconf is LGPL-2.1-or-later** — the only copyleft component. Normal use (`pip install`
  plus `import`) is dynamic linking, which does not impose source-disclosure obligations on your own
  code, but the component must be identified as LGPL rather than grouped with the Apache/MIT
  entries. Ship the license text and note that the library may be replaced by the user.
- **BoringSSL is mixed-license** — largely the OpenSSL/SSLeay dual license, with newer files under
  ISC/MIT. It is the fiddliest attribution in the tree; reproduce its `LICENSE` file verbatim rather
  than summarizing it.

Model weights carry their own terms separately from the inference code: Qwen3-VL is Apache-2.0 for
both the 4B and 8B Instruct checkpoints.

Everything the `mdns/devmon` Android app links is Apache-2.0, so that APK's notice file is a single
license text plus a copyright list. The LGPL obligation sits entirely on the **Python** side, which
ships separately and is not redistributed inside the app.

[aosp]: https://github.com/aosp-mirror/platform_frameworks_base
[vsc]: https://github.com/rayneo-develop/VideoShowCase
[zc]: https://github.com/python-zeroconf/python-zeroconf
[koog]: https://github.com/JetBrains/koog
[tquic]: https://github.com/Tencent/tquic
[qwen]: https://github.com/QwenLM/Qwen3-VL
[qwen4b]: https://huggingface.co/Qwen/Qwen3-VL-4B-Instruct
[qwen8b]: https://huggingface.co/Qwen/Qwen3-VL-8B-Instruct
[ktor]: https://github.com/ktorio/ktor
[okhttp]: https://github.com/square/okhttp
[bssl]: https://github.com/google/boringssl
[psutil]: https://github.com/giampaolo/psutil
