# Repository Guidelines

## Project Structure & Module Organization

This repository contains a small LAN device-monitoring system.

- `local_llm/mdns/discover_and_report.py` is the Windows/Python reporter. It discovers or connects to an Android peer and sends newline-delimited telemetry JSON.
- `local_llm/mdns/llm_info.json` holds the local-LLM labels sent with telemetry; keep it as a JSON array of objects.
- `local_llm/mdns/devmon/` is the Android Gradle project. Kotlin sources are under `app/src/main/java/com/example/devmon/`; resources and the manifest are in `app/src/main/res/` and `app/src/main/`.
- `local_llm/mdns/presentation.html` documents the architecture and mDNS constraints. Read it before changing discovery or networking behavior.

## Build, Test, and Development Commands

From `local_llm/mdns/`:

```powershell
py -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt
.venv\Scripts\python discover_and_report.py --list
.venv\Scripts\python discover_and_report.py --connect HOST:PORT
```

`--list` performs discovery only; `--connect` exercises the TCP/JSON path without mDNS. Build the Android app from `local_llm/mdns/devmon/` with `./gradlew assembleDebug` (or `gradlew.bat assembleDebug` on Windows). Use JDK 17 and a machine-local `local.properties` pointing to the Android SDK.

## Coding Style & Naming Conventions

Follow the surrounding code: four-space indentation for Python and Kotlin; Kotlin uses `PascalCase` types and `camelCase` functions/properties. Keep Python functions and variables `snake_case`. Prefer small, explicit changes over new abstractions in the single-file Python client. Do not commit Android build outputs, `.gradle/`, `.idea/`, or `local.properties`.

Keep the wire contract synchronized: updates to Python telemetry fields must be reflected in `Telemetry.kt`. The Android service type remains `_devmon._tcp.local.`; if changing `AdvertiserService.FIXED_PORT`, also update Python's scan-port default.

## Testing Guidelines

There is no automated test suite or coverage requirement. Verify changes manually: build the APK, run the reporter with `--connect` for TCP payload changes, and use a physical Android device on the same Wi-Fi for real mDNS discovery. Emulator networking cannot validate cross-device multicast discovery.

## Commit & Pull Request Guidelines

Recent commits use short, imperative subjects such as `fix the mdns` and `supporting json for multiple llm`. Keep subjects concise and scoped. Pull requests should explain behavior changes, list manual verification performed, link relevant issues, and include screenshots for Android UI changes. Call out any changes to telemetry JSON or the mDNS service contract explicitly.
