# Multiverse Master Agent (Koog side)

## What it does

The Master Agent receives an image + a text query, decides whether to run it against a **local** or
**remote** LLM target, executes the request, and returns a structured answer with a confidence score.
It is the server; any client (phone app, `curl`, etc.) calls it over HTTP. It is **not** a phone app.

Request lifecycle (`MasterAgent.handle`): resolve/create a session -> refresh the device registry from
discovery -> pick a target via the routing policy -> execute the prompt on that target -> normalize
the result into a confidence-scored response -> record the turn for session resume.

## Modules

- **`multiverse/master-agent`** (this module) — the orchestration core and the embedded Ktor HTTP API
  that exposes it. Everything below lives here.
- **`http-client/http-client-tquic`** — see [Relation to `http-client-tquic`](#relation-to-http-client-tquic)
  below.
- **`multiverse/tquic-config-gui`** — see [TQUIC configuration](#tquic-configuration) below.
- **`multiverse/master-agent-android`** — packages this module as an Android APK (same core, same API,
  reused source); see its own README.

## Core functionality

| Component | Package | Responsibility |
|---|---|---|
| Discovery + Registry | `discovery`, `registry` | `DiscoveryService` yields known devices (`StaticConfigDiscovery` reads `registry.json`); `CapabilityRegistry` holds live entries keyed by `deviceId`, pruning ones past their TTL. Each entry separates static `capabilities` (models, multipath support) from dynamic `status` (GPU load, reachability). |
| Routing Engine | `routing` | `RoutingPolicy` (default: `PreferRemotePolicy`, lowest GPU load wins) picks a target; `RoutingEngine` falls back remote -> local -> any reachable device, and decides multipath eligibility (`mpquic == supported && wifi/5G both up`). `RoutingDiagnostics` re-runs the same policy/engine to report the full ranked priority order for inspection. |
| Session + Context | `session` | `SessionManager` creates a new session or resumes one by id (`SessionStore`, in-memory by default), carrying prior-turn context into follow-up requests. |
| Execution | `execute` | `ComputeExecutor` runs the prompt on the chosen target: `LocalExecutor` (Koog `PromptExecutor` over plain HTTP/Ktor), `RemoteExecutor` (same, but over the TQUIC transport), `MockComputeExecutor` (deterministic, offline, no network/API key — the default `LlmBackend`). |
| Confidence Manager | `confidence` | Normalizes the LLM's structured output into the client-facing response: clamps confidence to `[0,1]`, buckets it into `high`/`medium`/`low`, derives `status` (`ok`/`partial`/`no_answer`), and strips use-case-irrelevant fields. |
| TQUIC config | `config` | `TquicConfig` + `TquicConfigLoader` — see [TQUIC configuration](#tquic-configuration). |
| HTTP API | `api` | `KoogHttpServer` (embedded Ktor/CIO) + `ComputeRoutes` — see [API](#api) below. |

## Run the agent

```bash
# From koog/
./gradlew :multiverse:master-agent:runMasterAgent          # mock backend, offline, port 8080
```

Environment:
- `LLM_BACKEND=mock` (default) | `openai`
- `LOCAL_BASE_URL=http://127.0.0.1:11434`  (OpenAI-compatible local endpoint, e.g. Ollama)
- `OPENAI_API_KEY=...`  (for the openai backend)
- `MASTER_AGENT_PORT=8080`
- `WIFI_UP=true` / `G5_UP=true`  (simulate path availability for multipath eligibility)

## API

All endpoints are hosted by the embedded Ktor server (`KoogHttpServer`).

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/v1/compute` | Submit an image + text query. Multipart fields: `image` (required, JPEG bytes), `query` (required, text), `useCase` (optional, `UC1`\|`UC2`, default `UC1`), `sessionId` (optional, resumes a session). Returns a `ComputeResponse`: `sessionId, useCase, status, answer, confidence, confidenceLabel, detail, allergens\|menuSuggestions, totalMs, warnings`. Routing/transport internals (which target/route/path served it) are intentionally never returned. Errors: `400` missing image/query, `503` no target available or transport unavailable. |
| `GET` | `/v1/sessions/{sessionId}` | Inspect a session's context. Not yet implemented — always `501`. |
| `GET` | `/v1/health` | Readiness probe: `status`, `registryDevices` count, `tquic` transport state, `version`. |
| `GET` | `/v1/registry/devices` | Every registered device's id, host:port, multipath support, served models, GPU load, reachability. |
| `GET` | `/v1/routing/policy` | The routing policy's full ranked priority order for the agent's default model — every fallback tier, the winning device, and the actual routing decision (target/device/multipath). Calls the same policy/engine code `/v1/compute` uses, so it reflects the real decision. |

```bash
curl -F image=@food.jpg -F 'query=Identify allergens; I am allergic to peanuts' -F useCase=UC1 \
  http://127.0.0.1:8080/v1/compute

curl http://127.0.0.1:8080/v1/health
curl http://127.0.0.1:8080/v1/registry/devices
curl http://127.0.0.1:8080/v1/routing/policy
```

## Relation to `http-client-tquic`

`http-client-tquic` implements Koog's `KoogHttpClient` transport interface over multipath QUIC (via a
JNI bridge to a Rust `tquic-jni` crate). The Master Agent depends on it as a library:
`RemoteExecutor` wraps a Koog `PromptExecutor` whose LLM client is constructed with a
`TquicKoogHttpClient` as its transport (`LlmBackend.OpenAICompatible`, remote branch) — this client is
injected **directly** into the LLM client's constructor rather than registered via Koog's
`ServiceLoader`, so the local (plain Ktor) and remote (TQUIC) transports can coexist on the same
classpath. `LocalExecutor` uses the plain Ktor transport instead — only the remote route goes over
TQUIC. `MasterAgent.toSessionParams` maps its own `TquicConfig` onto `http-client-tquic`'s
`TquicSessionParams` (server name, ALPN, multipath algorithm, congestion control, etc.) on every
request, so transport behavior always reflects the currently loaded config.

The Rust native library (`libtquic_jni.so`) is **not built yet** — `TquicNative` fails fast with a
clear "TQUIC bridge not yet implemented" error, so the remote route is scaffolded but not functional
until that library is built and placed on `java.library.path`. The local/mock route is unaffected and
fully functional today.

## TQUIC configuration

`config/TquicConfig.kt` is the full parameter set (connection/timeouts, flow control, congestion
control, BBR/COPA tuning, multipath, PATFB, TLS, logging) that gets passed to the TQUIC transport on
every remote-route request. `config/TquicConfigLoader.kt` loads it from **`tquic_config.xml`**
(`src/jvmMain/resources/tquic_config.xml`), the source of truth for defaults, validating ranges (e.g.
`cidLen <= 20`) as it loads.

### Change TQUIC config without rebuilding

`MasterAgentApp` reads `TQUIC_CONFIG_XML` at startup: if set to an existing file, it loads that instead
of the bundled default — no rebuild needed, just edit the file and restart the process.

1. Copy the shipped file so you have something to edit: `cp src/jvmMain/resources/tquic_config.xml /tmp/tquic_config.xml`
2. Edit `/tmp/tquic_config.xml` directly (plain XML, `<param key="..." default="..."/>` per setting), or
   with the optional `multiverse/tquic-config-gui` desktop editor (below).
3. Run (or restart) the agent pointing at it:
   ```bash
   TQUIC_CONFIG_XML=/tmp/tquic_config.xml ./gradlew :multiverse:master-agent:runMasterAgent
   ```

### `multiverse/tquic-config-gui` (optional editor)

A **Compose Desktop** app — a native window on your own machine, not part of the agent process and not
an Android app — for editing a `tquic_config.xml` file with per-parameter validation instead of by
hand. It reads and writes the file only; it does not talk to a running agent.

```bash
cd multiverse/tquic-config-gui
./gradlew run --args=/tmp/tquic_config.xml
```

Requires a graphical environment and a **non-headless JRE** (needs `libawt_xawt.so`, part of the
`openjdk-*-jre` package, not `-jre-headless`) — on a headless build server this will fail to open a
window (`UnsatisfiedLinkError: libawt_xawt.so`); hand-editing the XML is simpler there. The module
didn't have a Gradle wrapper or a clean build yet as of this writing; copy one from a sibling
standalone module (e.g. `../master-agent-android/gradle*`) if `./gradlew` is missing.

## Tests

```bash
./gradlew :multiverse:master-agent:jvmTest
```

Covers registry TTL + JSON, routing table (mpquic x network states) and ranked-priority diagnostics,
session create/resume, confidence normalization, TQUIC config load/validate/round-trip, and the full
HTTP API (`/v1/compute`, `/v1/health`, `/v1/registry/devices`, `/v1/routing/policy`) against the mock
backend.

## Not yet implemented (follow-up)

- Rust `tquic-jni` crate + real `libtquic_jni.so` (remote route is scaffolded only).
- Real multipath/PATFB end-to-end transport test.
- `GET /v1/sessions/{sessionId}` (session inspection).
- mDNS discovery, whisper ASR, JDBC session persistence, Android network status.
