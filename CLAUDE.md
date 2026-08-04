# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

`mdns` is a zero-config device monitor with two halves that talk over mDNS + TCP on the same
LAN/WiFi subnet:

- **Android app** (`com.example.devmon`, package `devmon`) advertises `_devmon._tcp.local.` via
  `NsdManager` and runs a plain TCP server. It is the *server*: it advertises and accepts
  connections but never dials out.
- **Windows/Python client** (`local_llm/mdns/discover_and_report.py`) discovers that service via
  `zeroconf`, connects to it, and pushes its own telemetry (IP, active network interface, CPU/mem,
  a static local-LLM description) as newline-delimited JSON, roughly every 2 seconds.

The asymmetry (phone = server/advertiser, PC = client/reporter) is intentional — the PC has the
interesting telemetry to push, and the phone just needs to render it.

mDNS is link-local (TTL 1): both devices must be on the same subnet. This governs most of the
gotchas in this repo (see `local_llm/mdns/presentation.html` for the full writeup, including the
"emulator cannot do real discovery" limitation, RFC 6762 announce/TTL behavior, and corporate-proxy
pip/cert issues).

## Repository layout

Everything lives under `local_llm/mdns/`:

- `discover_and_report.py` — the Windows/Python client. Single-file, no local package structure.
- `llm_info.json` — a JSON *array* of local-LLM labels, one object per model, sent in each
  telemetry frame (see "Static LLM labels" below). Checked in with three placeholder records; these
  are illustrative, not read from a live inference server, so correct them per machine.
- `requirements.txt` — `zeroconf`, `psutil`.
- `devmon/` — the Android Gradle project, checked in as plain source (previously shipped as a zip
  with build artifacts baked in — it's been unpacked and pruned down to source only).
  - Source: `devmon/app/src/main/java/com/example/devmon/{AdvertiserService,MainActivity,Telemetry}.kt`
  - Manifest: `devmon/app/src/main/AndroidManifest.xml`
  - Gradle wrapper (`devmon/gradlew`, `devmon/gradlew.bat`) is checked in; `devmon/.gitignore` keeps
    `build/`, `.gradle/`, `.idea/`, `.kotlin/`, and `local.properties` (machine-specific SDK path)
    out of the repo.
- `presentation.html` — a self-contained reveal.js deck (loads reveal.js/Font Awesome from CDN)
  documenting the architecture, the mDNS protocol mechanics, what was verified by testing, and
  known environment friction. Treat it as the design doc / project narrative for this repo — read
  it before making non-trivial changes to either side, especially the mDNS discovery/reporting
  logic, since it documents *why* things are built the way they are (e.g. why IPv4 is preferred
  over IPv6 link-local, why `sendall` not `send`, why the peer's address is fed back into the
  telemetry call).

There is no top-level README, no CI config, and no automated test suite for either half.
Verification so far has been manual/scripted end-to-end runs (see "Verified, not assumed" and
"Two emulator modes, one gap" slides in `presentation.html`) — be straightforward about this when
asked about test coverage rather than assuming hidden test infra exists.

## Commands

### Python client (`local_llm/mdns/`)

```
py -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt
.venv\Scripts\python discover_and_report.py                # discover + connect + report (default)
.venv\Scripts\python discover_and_report.py --list          # discover only, print, exit
.venv\Scripts\python discover_and_report.py --connect HOST:PORT   # skip mDNS (e.g. adb forward)
.venv\Scripts\python discover_and_report.py --advertise [NAME]    # stand in for the Android side
.venv\Scripts\python discover_and_report.py --scan                # skip mDNS; TCP-probe the /24 subnet on FIXED_PORT
.venv\Scripts\python discover_and_report.py --interval 5 -q       # custom interval, quiet
.venv\Scripts\python discover_and_report.py --llm-info other.json # LLM label from another file
```

If `pip install` fails with `SSLCertVerificationError` (common behind a corporate TLS-intercepting
proxy on Windows): either bootstrap-upgrade pip once with `--trusted-host pypi.org --trusted-host
files.pythonhosted.org` (pip ≥ 24.2 reads the Windows cert store natively, no flags needed
afterward), or export the Windows root store to a PEM and point pip at it. See the "Why a
certificate bundle?" slide in `presentation.html` for the full rationale — this is an environment
issue, not a bug in the script.

### Android app (`local_llm/mdns/devmon/`)

```
cd local_llm/mdns/devmon
./gradlew assembleDebug        # or gradlew.bat on Windows cmd
```

Needs a JDK 17 or 21 on `JAVA_HOME` — this project's Kotlin Gradle plugin (via AGP 8.7.3) cannot
parse newer JDK version strings (e.g. JDK 25, which is what Android Studio's bundled JBR ships as
of writing) and fails project configuration with a cryptic `IllegalArgumentException: 25.0.2`.
Point `JAVA_HOME` at a JDK 17/21 install (e.g. Eclipse Temurin) rather than Android Studio's `jbr`.

`local.properties` (gitignored) must contain `sdk.dir=` pointing at this machine's Android SDK —
regenerate it per machine rather than trusting a copy from another checkout.

Real cross-device mDNS discovery **cannot be tested on the emulator** — the AVD's default network
sits behind QEMU user-mode NAT, which does not forward multicast. A physical device on the same
WiFi is required to verify discovery end-to-end. The emulator can still exercise the `NsdManager`
registration path in isolation after bringing its virtual WiFi up
(`adb shell cmd wifi connect-network AndroidWifi open`), and the TCP/JSON path can be exercised
without mDNS at all via `adb forward` + `discover_and_report.py --connect`. Details and the exact
failure signatures are in the "The emulator cannot discover" and "Two emulator modes, one gap"
slides in `presentation.html`.

The app's TCP server binds to a **fixed port**, `AdvertiserService.FIXED_PORT` (currently `47531`),
rather than a kernel-assigned one — this was changed from `bind(InetSocketAddress(0))` specifically
so the Python side can find it by TCP-probing the subnet (`--scan`) when mDNS multicast is blocked
(see "mDNS multicast blocked by AP client isolation" below). If you change `FIXED_PORT`, update
`SCAN_PORT_DEFAULT` in `discover_and_report.py` to match.

## Architecture notes

**Wire format**: newline-delimited JSON, one object per sample, PC → phone only. Chosen so the
Kotlin side can parse with a single `BufferedReader.readLine()` (see `Telemetry.from()` in
`Telemetry.kt`). The Python `telemetry()` payload shape in `discover_and_report.py` and the
`Telemetry` data class in `Telemetry.kt` must be kept in sync manually — there's no shared schema.

**mDNS service contract**: type `_devmon._tcp.local.`, TXT records `role`, `model`, `sdk` set by
the Android side in `AdvertiserService.register()`. The Python `Listener` in
`discover_and_report.py` resolves PTR → SRV → A/AAAA via `zeroconf.get_service_info`, preferring
IPv4 over IPv6 link-local (avoids scope-id handling on Windows), and rebuilds its `Reporter` thread
whenever `update_service`/`remove_service` fires (e.g. the advertised port changed).

**Route-aware telemetry, not naive**: both `outbound_ip()`/`lan_ip()` in `discover_and_report.py`
and `describeSelf()` in `MainActivity.kt` resolve "my IP" by asking the OS which interface would
route to a given peer (via a UDP connect that sends no packet), rather than grabbing the first
network interface — a host can have many interfaces and only one is the real route to the peer.
`telemetry()` is called with the *peer's* address for this reason. Over an `adb forward` tunnel the
peer address resolves to loopback; `is_loopback()` detects this and falls back to `lan_ip()`,
flagging the substitution via `ip_via_tunnel` in the payload.

**Reporter lifecycle** (`discover_and_report.py`): one daemon thread per discovered peer
(`Reporter`), each independently reconnecting with a 3s backoff on `OSError`, so multiple phones
stream concurrently and one dropping doesn't affect others.

**Android state model** (`AdvertiserService.kt`): a sealed `State` (`Idle` / `Registering` /
`Advertising` / `Failed`) plus `peers` (`Map<address, Telemetry>`) and a rolling `log`, all exposed
as `StateFlow`s and combined/rendered in `MainActivity.render()`. `NsdManager` may rename the
service on collision, so the advertised name is always read back from `onServiceRegistered`, never
assumed from what was requested.

**Static LLM labels**: the payload describes whichever local LLMs this PC is set up to serve
(name/params/quantization/context length) — fixed labels, *not* queried from a live inference
server. Read once at startup by `load_llm_info()` from `llm_info.json` (path resolved next to
`discover_and_report.py`, so cwd doesn't matter; override with `--llm-info PATH`) into the
`LLM_INFO` list. Edit that JSON when the local setup changes; **multiple models are supported**,
one object per record.

The canonical file shape is a JSON array. A bare object is still accepted and treated as a
one-record list, so files written before multi-model support keep working.

*Two keys on the wire, deliberately.* `llms` carries every record; `llm` repeats the first one so a
phone build predating this change still renders something rather than nothing — the two schemas are
synced by hand, so assume stale APKs exist. **A reader that understands `llms` must ignore `llm`,
or the first model renders twice.** `Telemetry.from()` does this: it parses `llms` when present and
only falls back to `optJSONObject("llm")` when the array is absent. `MainActivity.render()` then
prints a `Local LLMs (n):` block, or nothing at all when the list is empty.

Loading is deliberately non-fatal — a broken config costs the labels, not the telemetry stream. Every
failure path warns and returns `[]` (missing file, unreadable, malformed JSON, wrong top-level type,
no usable records). Non-object entries inside an array are dropped individually with a count, so one
bad record doesn't cost the others their labels.

Keys are passed through verbatim; `Telemetry.Llm` reads
`name`/`parameters`/`quantization`/`context_length`/`family` and ignores extras, so adding a field
here stays invisible to the phone until that Kotlin data class is updated to match.

All of the above was verified on a physical SM-S911U1 (API 36) over `adb forward` + `--connect`:
a 3-record file rendered all three with no duplicated primary, a legacy `llm`-only frame rendered
one, and `{"llms": [], "llm": null}` rendered no LLM section.

**mDNS multicast blocked by AP client isolation**: on some networks (confirmed on a
`Public`-classified corporate/guest WiFi) mDNS discovery silently finds nothing even though both
devices are on the same `/24` and plain unicast TCP between them works fine (verified with
`--connect`). Packet-level testing (a self-test multicast packet was received; a phone-originated
multicast announce, forced by toggling advertising off/on, was not) confirmed the AP drops
device-to-device multicast/broadcast while still routing unicast — i.e. AP/client isolation, not a
bug in either side's code. `--scan` exists as a workaround: it never touches multicast, so it works
on isolated networks as long as unicast between the two devices isn't also blocked.
