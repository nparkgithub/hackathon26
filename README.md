# AllergenAR — AI-Powered Allergen Detection with AR Glasses, Edge AI, and Multipath QUIC

> **"Local When Possible. Cloud When Needed. Safety Always."**

AllergenAR lets a person wearing RayNeo AR glasses look at any food, ask a spoken question ("Are there allergens in this?"), and receive an answer overlaid on their lens in seconds — without touching a phone.

A Samsung Galaxy S25 Ultra acts as the orchestration brain. It tries a nearby Snapdragon X Elite PC first (Qwen3-VL 4B, running locally via LM Studio, zero cloud cost, offline-capable). If no local host is found, it falls back transparently to an AWS EC2 server running Qwen3-VL 8B over a Multipath QUIC connection that rides Wi-Fi and 5G simultaneously, surviving a dropped network without dropping the request.

---

## Team

| Name | Email |
|---|---|
| Daniel Park | npark@qti.qualcomm.com |
| Chaitanya Mehta | chaimeht@qti.qualcomm.com |
| Gautam Fotedar | gfotedar@qti.qualcomm.com |
| Sukoon Sarin | sukosari@qti.qualcomm.com |
| Eunice Koh | eunicek@qti.qualcomm.com |

---

## End-to-End Architecture

```
RayNeo AR Glasses (com.example.video.show.glass)
  JPEG + transcribed question → TCP 8889, Wi-Fi Direct
  ↓
Samsung S25 Ultra – Phone App (com.example.video.show.demo)
  ├─ mDNS discovery: finds nearby AI host on LAN?
  │    YES → POST JPEG + query to LM Studio on Snapdragon X Elite (HTTP/1.1, LAN)
  │           Qwen3-VL 4B answers on-device, sub-second, no cloud
  │    NO  → Multipath QUIC (Wi-Fi + 5G simultaneously) to AWS EC2
  │           tquic-vlm-server-interface (Rust H3 server) → Ollama → Qwen3-VL 8B
  ↓
Answer rendered as AR overlay on glasses + spoken aloud by TTS
```

Supporting component — **devmon** (`local_llm/mdns/`): the Snapdragon X Elite advertises itself on the LAN via `_devmon._tcp.local.` (mDNS/NSD), letting the phone discover it automatically with no manual IP entry.

---

## Repository Layout

| Path | What |
|---|---|
| `VideoShowCase/` | Git submodule — AR glasses app (`glass` module) + phone relay app (`app` module) |
| `mpquic/` | Multipath QUIC Android apps (client + server) + Linux CLI + Rust JNI bridge to Tencent `tquic` |
| `tquic-vlm-server-interface/` | Rust binary: QUIC/H3 server (Ubuntu x86_64) bridging requests to a local VLM |
| `local_llm/mdns/` | LAN device monitor — Windows/Python mDNS reporter + Android `devmon` app |
| `phone/shared/koog/` | Koog AI agent framework (JetBrains, git submodule) |
| `scripts/` | `adb`-based device setup and permission-granting helpers |
| `docs/` | Architecture specs, integration notes, presentation plans |

### Pre-built APKs (checked in)

| File | What |
|---|---|
| `mpquic/apks/client-debug.apk` | MPQUIC Android client app |
| `mpquic/apks/server-debug.apk` | MPQUIC Android server app |

---

## Setup from Scratch

### Prerequisites by component

| Component | Required tools |
|---|---|
| All Android apps | Android SDK (API 36), ADB in `PATH` |
| devmon / VideoShowCase | **JDK 17 or 21** on `JAVA_HOME` — AGP 8.7.3 breaks on JDK 25 |
| mpquic Android | JDK 17, Rust 1.90, `cargo-ndk`, Android NDK 27.2, CMake 3.22, VS Build Tools (Windows ARM64 host) |
| mpquic Linux CLI | Rust 1.90, `cmake`, `ninja-build`, `nasm`, `gcc` |
| tquic-vlm-server-interface | Rust 1.90, `build-essential cmake perl pkg-config` (Ubuntu x86_64) |
| Python reporter | Python 3.10+, `pip` |

### 1. Clone with submodules

```bash
git clone https://github.com/nparkgithub/hackathon26
cd hackathon26

# VideoShowCase submodule (needs SSH access to github.com:sukoonsarin/VideoShowCase)
git submodule update --init VideoShowCase

# tquic is vendored via crates.io — no submodule fetch needed for building
```

### 2. tquic-vlm-server-interface — Ubuntu x86_64 server

```bash
sudo apt update && sudo apt install -y build-essential cmake perl pkg-config
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
source "$HOME/.cargo/env"
# rust-toolchain.toml pins 1.90.0 — rustup installs it automatically

cd tquic-vlm-server-interface
cargo build --release
```

Open the QUIC port (UDP — not TCP):

```bash
sudo ufw allow 19500/udp
```

### 3. mpquic Android apps — Windows ARM64 host (vcvarsall x64 shell required)

```bat
:: Prerequisites: Rust 1.90, cargo-ndk, Android NDK 27.2, CMake 3.22, VS Build Tools

:: 1) Build native .so for both ABIs
set ANDROID_NDK_HOME=%LOCALAPPDATA%\Android\Sdk\ndk\27.2.12479018
set CMAKE_GENERATOR=Ninja
set PATH=%LOCALAPPDATA%\Android\Sdk\cmake\3.22.1\bin;%PATH%

cd mpquic\mpquic-jni
cargo ndk -t arm64-v8a -t x86_64 -o ..\android\core\src\main\jniLibs build --release

:: 2) Build APKs
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot
cd ..\android
..\gradle-8.13\bin\gradle :client:assembleDebug :server:assembleDebug
```

APKs land in:
- `mpquic/android/client/build/outputs/apk/debug/client-debug.apk`
- `mpquic/android/server/build/outputs/apk/debug/server-debug.apk`

Pre-built copies are already at `mpquic/apks/`.

### 4. mpquic Linux CLI

Pre-built x86-64 binaries are already at `mpquic/linux/bin/x86_64/` — no build step needed.

To build from source (x86-64 Linux):

```bash
sudo apt install -y cmake ninja-build nasm gcc
cd mpquic/linux/mpquic-cli
cargo build --release
# binaries: target/release/mpquic-{client,server}
```

Cross-compiling from an aarch64 Linux host:

```bash
sudo apt install cmake ninja-build nasm gcc-x86-64-linux-gnu g++-x86-64-linux-gnu
rustup target add x86_64-unknown-linux-gnu
cd mpquic/linux/mpquic-cli
export CC_x86_64_unknown_linux_gnu=x86_64-linux-gnu-gcc
export CXX_x86_64_unknown_linux_gnu=x86_64-linux-gnu-g++
export CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER=x86_64-linux-gnu-gcc
export CMAKE_GENERATOR=Ninja
cargo build --release --target x86_64-unknown-linux-gnu
```

### 5. devmon Android app (LAN device monitor / mDNS advertiser)

```bash
cd local_llm/mdns/devmon
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 or 21 on `JAVA_HOME`. Create `local.properties` in `devmon/`:

```
sdk.dir=/path/to/android/sdk
```

### 6. Python reporter (Windows, LAN device monitor)

```powershell
cd local_llm\mdns
py -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt
```

If `pip install` fails with `SSLCertVerificationError` behind a corporate proxy, run once with:

```powershell
.venv\Scripts\python -m pip install --trusted-host pypi.org --trusted-host files.pythonhosted.org -r requirements.txt
```

### 7. Device setup (after installing APKs)

```bash
# Grant permissions and configure both glasses and phone for demo
./scripts/glass_dev_setup.sh both

# If targeting a specific device: DEVICE_SERIAL=<serial> ./scripts/glass_dev_setup.sh glass
```

---

## Running and Usage

### AllergenAR demo — full end-to-end

**Step 1 — Start the local AI host (Snapdragon X Elite or any LAN PC)**

Start LM Studio, load `Qwen/Qwen3-VL-4B-Instruct`, and enable the local server on port 1234.

Install the `devmon` APK on an Android device (or emulator) attached to the same Wi-Fi, start it, and tap **Start Advertising**. The phone will now discover this host automatically.

**Step 2 — (Optional) Start the cloud fallback on AWS EC2**

```bash
# On the Ubuntu x86_64 EC2 instance:
./target/release/tquic-vlm-server-interface \
  --bind 0.0.0.0:19500 \
  --vlm-base-url http://127.0.0.1:11434/v1 \
  --vlm-model qwen3-vl:8b
# --help lists all flags: congestion control, body-size cap, timeouts, mpquic scheduler
```

Ollama must be running on the same EC2 instance with `ollama pull qwen3-vl:8b`.

**Step 3 — Start the phone app and glasses app**

Install `VideoShowCase` glass APK on the RayNeo glasses and phone APK on the Samsung device. Launch both; the glasses connect to the phone over Wi-Fi Direct automatically.

**Step 4 — Ask a question**

Wear the glasses, look at food, press the capture button (or use the wake word), and ask aloud "Are there any allergens in this?". The glasses capture a JPEG + transcript, the phone routes it locally or to cloud, and the answer appears on the lens and is spoken aloud.

---

### MPQUIC apps — standalone Multipath QUIC demo

Install the pre-built APKs:

```bash
adb install mpquic/apks/server-debug.apk
adb install mpquic/apks/client-debug.apk
```

1. **Server device**: open the server app, choose listen address (`0.0.0.0:4433`), pick scheduler and congestion control, tap **Start**.
2. **Client device**: enter `<server-IP>:4433`. The local-IP list pre-fills with Wi-Fi + cellular interfaces. Choose scheduler (`minrtt` / `redundant` / `roundrobin`), tap **Connect**, then **Send** a test payload (1–100 MB).
3. Per-path graphs (bytes/s per interface) update every 2 s. Kill Wi-Fi to watch traffic shift to cellular in real time.

**H3 tunnel mode** — pipe JPEG images over MPQUIC from a desktop:

```bash
# On the client device: tap "Start HTTP/3 RX" (default port 47443)
# From a desktop on the same network:
pip install aioquic
python mpquic/tools/h3_sender.py <client-ip> photo.jpg
# or: python mpquic/tools/h3_sender.py <client-ip> --size-mb 4
```

---

### MPQUIC Linux CLI — quick test

```bash
cd mpquic/linux

# Terminal 1 — echo server
bin/x86_64/mpquic-server --listen 0.0.0.0:4433 \
  --cert certs/server.crt --key certs/server.key

# Terminal 2 — client, send 5 MB, exit
bin/x86_64/mpquic-client --connect 127.0.0.1:4433 --send-mb 5 --oneshot

# Multipath: two local IPs, roundrobin scheduler
bin/x86_64/mpquic-client --connect <server:4433> \
  --local 192.168.1.10,10.60.0.2 --scheduler redundant --send-mb 10 --oneshot
```

---

### tquic-vlm-server-interface — testing without a phone

```bash
cd tquic-vlm-server-interface

# Unit tests (frame parser + mockito VLM client — no network needed)
cargo test

# End-to-end smoke test: start a stub OpenAI endpoint, then:
./target/release/tquic-vlm-test-client \
  --host 127.0.0.1 --port 19500 \
  --image path/to/food.jpg \
  --prompt "Are there any allergens in this dish?"

# Passthrough shape (pre-built OpenAI JSON body):
./target/release/tquic-vlm-test-client \
  --host 127.0.0.1 --port 19500 \
  --raw-body path/to/openai-request.json
```

---

### LAN device monitor (mDNS devmon + Python reporter)

```powershell
# Windows — discover nearby devmon instances and stream telemetry
cd local_llm\mdns
.venv\Scripts\python discover_and_report.py              # discover + connect + report
.venv\Scripts\python discover_and_report.py --list       # mDNS discovery only, then exit
.venv\Scripts\python discover_and_report.py --connect 192.168.1.42:47531  # skip mDNS
.venv\Scripts\python discover_and_report.py --scan  --openai-port 1234      # Actual use option with devices TCP-probe /24 (use when AP blocks mDNS multicast)
.venv\Scripts\python discover_and_report.py --interval 5 -q              # 5-second interval, quiet
```

Edit `local_llm/mdns/llm_info.json` to describe the local LLM(s) on this machine — these labels appear on the Android display:

```json
[
  {"name": "Qwen3-VL-4B-Instruct", "parameters": "4B", "quantization": "Q4_K_M", "context_length": 8192, "family": "qwen3-vl"}
]
```

---

## Networking Notes

- **mDNS (service discovery)** is link-local (TTL 1): both phone and host must be on the **same subnet**. Some corporate/guest networks block device-to-device multicast (AP client isolation) while unicast TCP still works — use `--scan` on the Python reporter as a fallback, or ensure devices are on the same non-isolated Wi-Fi.
- **QUIC transport** uses **UDP** port 19500 by default. Unlike the TCP paths, `adb forward` cannot tunnel it. Ensure the EC2 security group allows inbound UDP on that port.
- TLS is mandatory for QUIC; the demo uses a self-signed cert (`tquic-vlm-server-interface/certs/server.crt`). The phone connects with certificate verification disabled (demo posture — not for production).
- Real JPEG images from the RayNeo glasses are **3.8–4.2 MB**. Verify there is no restrictive body-size limit in any multipart handler if you modify that path.

---

## Key Wire Contracts (kept in sync manually — no shared schema)

| What | Files |
|---|---|
| Telemetry JSON fields | `local_llm/mdns/discover_and_report.py` ↔ `devmon/…/Telemetry.kt` |
| Fixed TCP port (47531) | `AdvertiserService.FIXED_PORT` ↔ `SCAN_PORT_DEFAULT` in reporter |
| JNI export names | `mpquic-jni/src/lib.rs` `#[no_mangle]` exports ↔ `TquicBridge.kt` `external fun` names |
| H3 request shapes | `tquic-vlm-server-interface/src/frames.rs` ↔ Android Koog client |

---

## Third-Party Components

See [`OPEN-SOURCE-COMPONENTS.md`](OPEN-SOURCE-COMPONENTS.md) for a full table of every open-source dependency, its upstream source, license, and version.

**License summary:**
- All Android and Rust components: Apache-2.0 or MIT
- `python-zeroconf` (mDNS discovery): **LGPL-2.1-or-later** — dynamic-linked, ship license text
- BoringSSL (vendored inside TQUIC): mixed OpenSSL/SSLeay + ISC — reproduce its `LICENSE` verbatim
- Qwen3-VL model weights (4B and 8B): Apache-2.0

---

## License

This project is released under the [MIT License](LICENSE).

```
MIT License

Copyright (c) 2026 AllergenAR Hackathon Team

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
