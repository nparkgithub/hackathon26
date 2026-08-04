# Koog Master Agent - Android app

## What this is

The **Master Agent** is an orchestrator that receives an image + text query from a client, decides
whether to run it against a local or remote LLM (based on a device capability registry and routing
policy), executes the request, and returns a structured answer with a confidence score. It exposes
this over an embedded HTTP API and is meant to be called by any client app (e.g. a phone app on the
same device or LAN) that captures an image and a question.

**`koog/multiverse/master-agent`** is the Kotlin/JVM core: the orchestration logic (registry, routing,
sessions, confidence scoring) plus the embedded Ktor HTTP server that exposes it. It can run as a
plain JVM process.

**`master-agent-android`** (this module) packages that same core as an installable **Android APK** so
it can run on-device (e.g. a Galaxy S25) instead of a desktop/server JVM. It does not duplicate any
orchestration logic — it reuses the `master-agent` Kotlin source directly and adds only the
Android-specific wiring: a foreground `Service` to host the embedded HTTP server while the app is
backgrounded, asset loading (config/registry files from the APK instead of the classpath), and a
status/diagnostics UI (`MainActivity`).

## Why a separate Android build

- koog's main build pins AGP 8.12.3 (needs Android build-tools 35, not installed here). This build pins
  **AGP 8.2.2 + build-tools 33.0.1 + compileSdk 34** so it works with the local SDK.
- It **reuses the master-agent Kotlin source directly** (`android.sourceSets["main"].kotlin.srcDirs(...)`)
  plus the `http-client-tquic` source, and pulls koog framework classes from **Maven Central**
  (`ai.koog:*:1.1.1`, Android variants). No koog-build changes, no publish step.

## Structure
```
master-agent-android/
  settings.gradle.kts, build.gradle.kts, gradle.properties, local.properties  # local.properties -> SDK path
  app/
    build.gradle.kts            # AGP 8.1.4, kotlin 2.3.10, reuses ../../master-agent + http-client-tquic source
    src/main/AndroidManifest.xml
    src/main/assets/            # tquic_config.xml + registry.json (same files as the JVM app)
    src/main/kotlin/ai/koog/multiverse/android/
      AndroidConfig.kt          # loads assets, builds MasterAgent (mock backend)
      MasterAgentService.kt     # foreground Service hosting KoogHttpServer (Ktor CIO) on :8080
      MainActivity.kt           # status UI: start/stop + /v1/health + registry/routing diagnostics
```

## Build
```bash
cd koog/multiverse/master-agent-android
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

## Install / run (needs a device or emulator)
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
# open the app, tap "Start agent", then from a client app (or adb shell):
#   curl -F image=@food.jpg -F 'query=allergens? peanuts' -F useCase=UC1 http://127.0.0.1:8080/v1/compute
```

## API

Everything below is hosted by the embedded Ktor server (`KoogHttpServer`, port `8080` by default) —
the same API whether running as a plain JVM process or inside this Android app.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/v1/compute` | Submit an image + text query. Multipart form fields: `image` (required, JPEG bytes), `query` (required, text), `useCase` (optional, `UC1`\|`UC2`, default `UC1`), `sessionId` (optional, resumes a prior session). Returns a `ComputeResponse` JSON: `answer`, `confidence`, `confidenceLabel`, `detail`, plus use-case-specific fields (`allergens` for `UC1`, `menuSuggestions` for `UC2`). Errors: `400` missing image/query, `503` no compute target available or transport unavailable. |
| `GET` | `/v1/sessions/{sessionId}` | Inspect a session's context. Not yet implemented — always returns `501`. |
| `GET` | `/v1/health` | Readiness probe: `status`, `registryDevices` count, `tquic` transport state, `version`. |
| `GET` | `/v1/registry/devices` | Every registered device's id, host:port, multipath support, served LLM models, GPU load and reachability. Backs the app's **View devices** button. |
| `GET` | `/v1/routing/policy` | The routing policy's full priority order for the agent's default model: every fallback tier (`remote_preferred` -> `local_fallback` -> `any_reachable_fallback`), fully ranked by GPU load, with the winning device marked and the actual routing decision (target/device/multipath). This calls the same policy/engine code the `/v1/compute` request path uses, so it reflects the real routing decision rather than a separate reimplementation. Backs the app's **View routing policy** button. |

```bash
curl http://127.0.0.1:8080/v1/health
curl http://127.0.0.1:8080/v1/registry/devices
curl http://127.0.0.1:8080/v1/routing/policy
curl -F image=@food.jpg -F 'query=allergens? peanuts' -F useCase=UC1 http://127.0.0.1:8080/v1/compute
```

## Change TQUIC config without rebuilding

`AndroidConfig.buildAgent` checks the app's external files dir
(`/sdcard/Android/data/ai.koog.multiverse.android/files/tquic_config.xml`) first, falling back to the
bundled asset if it's not there. Push an edited file there and restart the agent — no rebuild needed:

1. Start the app once (tap **Start agent**) so Android creates its external files dir.
2. Copy `app/src/main/assets/tquic_config.xml` from the repo and edit the copy as needed.
3. Push it and restart:
   ```bash
   adb push tquic_config.xml /storage/emulated/0/Android/data/ai.koog.multiverse.android/files/tquic_config.xml
   ```
   Tap **Stop agent** then **Start agent** in the app to reload it.

## Notes / limits
- LLM backend defaults to **Mock** (runs with no API key/network). Switching to the OpenAI-compatible
  backend on-device would be a small change in `AndroidConfig.buildAgent`.
- Remote TQUIC route is still the **scaffold** (no `libtquic_jni.so`); the local/mock route works.
- Validated on-device with a headless x86_64 emulator (`android-34;google_apis;x86_64` system image,
  `avdmanager`/`emulator` under the Android SDK, KVM-accelerated): agent start/stop, `/v1/health`,
  `/v1/compute` (UC1/UC2, session resume, error paths), the two diagnostics menus, and the external
  `tquic_config.xml` override all confirmed working end to end via `adb forward`/`adb push` + `curl`
  and the in-app buttons/dialogs.
