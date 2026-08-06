# Implement DevMon's `/analyze` + `/health` HTTP server

Implementation plan for the contract specified in [`devmon-http-api.md`](https://raw.githubusercontent.com/nparkgithub/hackathon26/refs/heads/docs/devmon-http-api/docs/devmon-http-api.md).

## Context

`VideoShowCase`'s `DevmonAnswerProvider.kt` already calls `http://127.0.0.1:47532/analyze` (multipart POST) and `/health` (GET), expecting DevMon — a separate Android app on the same phone — to answer. That endpoint doesn't exist yet; DevMon currently only has mDNS peer discovery (`AdvertiserService.kt`, TCP telemetry on port 47531) and a manual "pick image, tap Analyze" UI button (`MainActivity.kt`) that calls `OpenAiAnalysisClient.analyze()` (already built on Koog's OpenAI-compatible client) directly.

The goal: add the missing HTTP server to DevMon, reusing the existing peer-discovery state and Koog-based analysis call, so VideoShowCase's glasses-capture flow can get real vision-LLM answers instead of failing with "DevMon unreachable."

The server is **hand-rolled on `java.net.ServerSocket`**, matching the exact style already used by `AdvertiserService.kt`'s TCP telemetry listener — no new HTTP framework dependency (the spec permits NanoHTTPD, Ktor CIO, or hand-rolled; ruled out the first two here for zero new dependencies and style consistency). The server **always starts in `MainActivity.onCreate()`**, independent of the "Start advertising" toggle, so `/health` reflects "DevMon is running," not "peer is connected."

## Contract being implemented (frozen — do not modify `DevmonAnswerProvider.kt`)

Loopback only, `http://127.0.0.1:47532`:

- **`POST /analyze`** — multipart/form-data: `image` (file part with filename, JPEG bytes, ~3.7–4.0 MB) + `query` (text field, always non-blank).
  - `200`: `{"answer": "...", "model": "...", "endpoint": "..."}` — `answer` required (plain spoken-language sentence, no markdown/JSON/code fences); `model`/`endpoint` optional/informational, logged only. No `confidence` field.
  - non-2xx: `{"error": "no_peer"|"no_vision_model"|"upstream_failed"|"bad_request", "message": "..."}` — client only reads `message`; `error`/status code are for DevMon's own semantics.
    - `503 no_peer` — no PC discovered over mDNS yet
    - `503 no_vision_model` — a peer was found but advertises no vision-capable model
    - `502 upstream_failed` — the PC's LLM call failed or timed out
    - `400 bad_request` — missing/malformed image or query
- **`GET /health`** — `200` with JSON body `{"status": "ok", "peerDiscovered": bool, "visionModel": string|null}`. Only the HTTP status matters to the client today (`isSuccessful`); `peerDiscovered: false` is a valid `200`.

**Behavioral requirements from the spec:**
- No artificial request-body size cap below ~4 MB (hand-rolled server reads exactly `Content-Length` bytes, so this is naturally unbounded by any framework default).
- No server-side read timeout while waiting on the upstream vision-model call — "take the time you need," don't return partial answers early.
- No concurrency handling needed — client only ever has one `/analyze` request in flight at a time.

## Design

### 1. `AnalyzeHttpServer` — socket lifecycle (new file, mirrors `AdvertiserService.kt`)

```kotlin
class AnalyzeHttpServer(private val peersProvider: () -> Map<String, Telemetry>) {
    companion object { const val PORT = 47532 }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null

    fun start() {
        val sock = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("127.0.0.1", PORT)) }
        server = sock
        scope.launch { acceptLoop(sock) }
    }

    private suspend fun acceptLoop(sock: ServerSocket) {
        while (!sock.isClosed) {
            val client = try { sock.accept() } catch (e: IOException) { if (sock.isClosed) return else continue }
            scope.launch { handleConnection(client) }
        }
    }

    private suspend fun handleConnection(socket: Socket) {
        socket.use {
            val response = try {
                val request = readHttpRequest(it.getInputStream())
                routeRequest(request, peersProvider, OpenAiAnalysisClient::analyze)
            } catch (e: Exception) {
                HttpResponse(400, "application/json", buildErrorJson(AnalyzeErrorCode.BAD_REQUEST, "Malformed request"))
            }
            it.getOutputStream().write(writeHttpResponse(response))
        }
    }

    fun stop() { server?.close(); scope.cancel() }
}
```

Binds explicitly to `127.0.0.1` (not `0.0.0.0`) — `/analyze` must stay loopback-only, matching the spec's "nothing leaves the device on this hop." Started unconditionally in `onCreate()`; stopped in `onDestroy()` alongside `advertiser.shutdown()`. No per-request socket read timeout is set on the accepted `Socket` (default `soTimeout` is 0/infinite), so a slow upstream vision-model call is never killed prematurely — the client itself has the generous 45s read timeout. Same caveat as `AdvertiserService`: only alive while the Activity is in memory — no foreground Service, matching existing app behavior, called out as a known limitation rather than solved here.

### 2. Pure, JVM-testable core

Everything that isn't raw socket I/O is pulled into plain functions/data classes with **no Android, no socket dependency**, so it unit-tests exactly like `AdvertiserService`'s sibling `Telemetry.kt` parsing does:

**`HttpParsing.kt`** (new):
- `readHttpRequest(input: InputStream): ParsedHttpRequest` — reads the request line + headers off a stream, then exactly `Content-Length` body bytes (no size cap — spec requires headroom above ~4 MB). `ParsedHttpRequest(method, path, headers: Map<String,String>, body: ByteArray)`.
- `parseMultipart(body: ByteArray, boundary: String): List<MultipartPart>` — splits on the boundary, parses each part's `Content-Disposition`/`Content-Type` header and raw bytes. `MultipartPart(name: String, filename: String?, contentType: String?, content: ByteArray)`.
- `writeHttpResponse(response: HttpResponse): ByteArray` — serializes status line + `Content-Type`/`Content-Length`/`Connection: close` headers + body.
- These take/return only strings, byte arrays, and plain data classes — fully unit-testable with hand-built byte arrays as fixtures (e.g. a canned multipart body), no socket needed.

**`AnalyzeRequestHandling.kt`** (new) — the routing/decision logic, decoupled from I/O so it's testable via a plain function call:
```kotlin
suspend fun routeRequest(
    request: ParsedHttpRequest,
    peersProvider: () -> Map<String, Telemetry>,
    analyze: suspend (String, Telemetry.Llm, ByteArray, String, String) -> String,
): HttpResponse
```
- `GET /health` → build `AnalysisTarget` from `selectAnalysisTarget(peersProvider())`; respond `200` `{"status":"ok","peerDiscovered": target !is NoPeer, "visionModel": (target as? Found)?.model?.name}`.
- `POST /analyze` → parse multipart (missing/empty `image` part or blank `query` → `bad_request`/400) → `selectAnalysisTarget(peersProvider())` → `NoPeer` → `503 no_peer` / `NoVisionModel` → `503 no_vision_model` → `Found` → call `analyze(...)` wrapped in `runCatching` → success → `200` JSON; failure → **`502 upstream_failed`** with `describeCauseChain()` message.
- Because `analyze` is passed in as a parameter (defaulting to `OpenAiAnalysisClient::analyze` at the real call site), tests inject a fake suspend lambda — no real network/Koog call needed to test routing decisions.

**`PeerSelection.kt`** (new) — refines the existing inline `MainActivity` peer-pick logic to distinguish the two error cases the HTTP contract needs (today's logic only has one "no target" outcome):
```kotlin
sealed interface AnalysisTarget {
    data class Found(val endpoint: String, val model: Telemetry.Llm) : AnalysisTarget
    object NoPeer : AnalysisTarget          // no peer, or none has openAiEndpoint
    object NoVisionModel : AnalysisTarget   // a peer has an endpoint but no vision-capable llm
}
fun selectAnalysisTarget(peers: Map<String, Telemetry>): AnalysisTarget
```
Reused by both the new handler (both routes) and a refactored `MainActivity.analyzeSelectedImage()` (replacing its current inline `firstNotNullOfOrNull` lambda).

**`AnalyzeHttpModels.kt`** (new):
- `AnalyzeErrorCode` enum with wire string + HTTP status: `NO_PEER` (`no_peer`, 503), `NO_VISION_MODEL` (`no_vision_model`, 503), `UPSTREAM_FAILED` (`upstream_failed`, **502**), `BAD_REQUEST` (`bad_request`, 400).
- `buildSuccessJson(answer, model, endpoint)` / `buildErrorJson(code, message)` / `buildHealthJson(peerDiscovered, visionModel)` using `org.json.JSONObject` (same style as `Telemetry.kt`, no new JSON dependency).
- Promoted `Throwable.describeCauseChain()` (currently a private extension in `MainActivity.kt`) moved here so both `MainActivity` and the new handler share one implementation.

### 3. `OpenAiAnalysisClient.kt` — thread the query through

Add a `query: String` parameter to `analyze(...)`. Per the spec: **"keep `ALLERGY_PROMPT` as the default when the incoming query is blank, and use the caller's query otherwise"** — no wrapping/templating, just:
```kotlin
suspend fun analyze(
    endpoint: String,
    model: Telemetry.Llm,
    imageBytes: ByteArray,
    mimeType: String,
    query: String = "",
): String {
    val promptText = query.trim().ifBlank { ALLERGY_PROMPT.trimIndent() }
    // ... use promptText where ALLERGY_PROMPT.trimIndent() was used directly
}
```
Default empty string preserves `MainActivity`'s existing 4-arg call site unchanged (falls through to `ALLERGY_PROMPT`); the new HTTP handler passes the client's `query` field directly.

### 4. `MainActivity.kt` wiring

- Construct `AnalyzeHttpServer { advertiser.peers.value }` in `onCreate()`, call `.start()`.
- Call `.stop()` in `onDestroy()` next to `advertiser.shutdown()`.
- Refactor `analyzeSelectedImage()` to call `selectAnalysisTarget(advertiser.peers.value)` and branch on the sealed result instead of the current inline lambda.
- Remove the private `describeCauseChain()`, import the promoted shared one.

### 5. Gradle / manifest

- **No new runtime dependency** (hand-rolled sockets, `org.json` already available on-device).
- Add a `testImplementation` block (module currently has none): `junit:junit:4.13.2`, `org.json:json:20240303` (JVM-side stub, since Android's built-in `org.json` throws off-device — same reasoning VideoShowCase already applies), `kotlinx-coroutines-test:1.9.0` (for testing the suspend `routeRequest`).
- `AndroidManifest.xml`: no change — `INTERNET` + `usesCleartextTraffic="true"` already present and sufficient for a loopback plaintext server.

## Files

**New:**
- `local_llm/mdns/devmon/app/src/main/java/com/example/devmon/AnalyzeHttpServer.kt`
- `local_llm/mdns/devmon/app/src/main/java/com/example/devmon/HttpParsing.kt`
- `local_llm/mdns/devmon/app/src/main/java/com/example/devmon/AnalyzeRequestHandling.kt`
- `local_llm/mdns/devmon/app/src/main/java/com/example/devmon/PeerSelection.kt`
- `local_llm/mdns/devmon/app/src/main/java/com/example/devmon/AnalyzeHttpModels.kt`
- `local_llm/mdns/devmon/app/src/test/java/com/example/devmon/HttpParsingTest.kt`
- `local_llm/mdns/devmon/app/src/test/java/com/example/devmon/AnalyzeRequestHandlingTest.kt`
- `local_llm/mdns/devmon/app/src/test/java/com/example/devmon/PeerSelectionTest.kt`
- `local_llm/mdns/devmon/app/src/test/java/com/example/devmon/AnalyzeHttpModelsTest.kt`

**Modified:**
- `local_llm/mdns/devmon/app/src/main/java/com/example/devmon/OpenAiAnalysisClient.kt` — add `query` parameter, default-to-`ALLERGY_PROMPT`-when-blank logic.
- `local_llm/mdns/devmon/app/src/main/java/com/example/devmon/MainActivity.kt` — wire server lifecycle, refactor peer selection, drop local `describeCauseChain()`.
- `local_llm/mdns/devmon/app/build.gradle.kts` — add `testImplementation` deps.

**Not touched:** `VideoShowCase/.../DevmonAnswerProvider.kt` (frozen contract), `AdvertiserService.kt`, `Telemetry.kt` (read-only reuse).

## Verification

**Automated:**
- `./gradlew :app:testDebugUnitTest` in `local_llm/mdns/devmon` — covers `HttpParsing`, `AnalyzeRequestHandling` (with a fake `analyze` lambda and fake peer map, no network/socket), `PeerSelection`, `AnalyzeHttpModels`.

**Manual, on-device/emulator (API 35+, matches `minSdk`):**
1. Launch DevMon; confirm (Logcat) the server bound `127.0.0.1:47532`.
2. `adb shell curl -s http://127.0.0.1:47532/health` → `200` with `{"status":"ok","peerDiscovered":false,"visionModel":null}` before any peer connects.
3. No peer connected: `adb shell curl -s -X POST http://127.0.0.1:47532/analyze -F "image=@/sdcard/test.jpg;type=image/jpeg" -F "query=what allergens are in this food"` → `503` `no_peer`.
4. Start advertising, connect a mock peer (adapt `discover_and_report.py`) reporting an endpoint but no vision LLM → repeat `/health` (expect `peerDiscovered:true`, `visionModel:null`) and `/analyze` (expect `503 no_vision_model`).
5. Mock/real peer with a vision LLM → `/health` shows `visionModel` populated; `/analyze` → `200` with a plausible `answer`.
6. Run VideoShowCase on the same device; trigger a capture; confirm `CaptureAnswer.speak` matches DevMon's `answer` and `confidence` is null.
7. Kill DevMon, retry a VideoShowCase capture → confirm the existing `unreachableDevmonAnswer()` path fires (no crash).
