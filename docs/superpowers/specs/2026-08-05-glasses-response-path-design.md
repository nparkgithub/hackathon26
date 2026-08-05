# Glasses response path — design

**Date:** 2026-08-05
**Status:** approved, ready for implementation planning
**Repo:** changes land in the `VideoShowCase` submodule (fork `sukoonsarin/VideoShowCase`, branch `hackathon26-arfood`)

## Problem

The capture flow is one-way. The glasses take a JPEG and a spoken query, transcribe it on-device, and send both to the relay phone, which writes `image.jpg` and `query.txt` to disk. Nothing comes back. The user gets no answer, which is the entire point of the use case.

This design covers the return leg: phone → glasses, plus display and spoken playback on the glasses.

## Scope

**In scope:** transporting an answer from the phone to the glasses, rendering it on the lens, and speaking it aloud.

**Out of scope:** how the phone obtains the answer. The phone → Koog `/v1/compute` call belongs to a separate design and depends on an endpoint the team is still building. This design defines the seam it will plug into (see `AnswerProvider`) and ships a stub behind that seam so the return path is testable today.

## Requirements

1. When an answer arrives, the glasses **speak the full text** and **display a short summary line**.
2. The payload is **structured**, not a bare string, so the display line and spoken text can differ and confidence can be shown.
3. **One capture at a time.** After sending, the glasses wait; further capture taps are ignored until the flow returns to `READY` — which is after playback finishes, not merely when the answer arrives. A tap during playback is ignored.
4. The response travels over the **existing capture connection** — no new socket, no new port.
5. A TTS failure must not lose the answer: the text still appears on screen.

## Architecture

### Transport

The glasses already hold an open TCP connection to the phone: `CaptureClient` opens it once on entering the capture screen and reuses it for every capture. TCP is bidirectional and this connection sits idle between captures, so the response reuses it rather than introducing a second channel.

Rejected alternatives:

- **Reverse socket** (glasses listen, phone connects) — needs a second listener, a second port, and the phone to learn the glasses' address. More moving parts on demo day for no functional gain.
- **Polling** (glasses ask "ready yet?") — adds a latency-versus-chattiness tradeoff to tune, and solves a problem that does not exist given the connection is already open.

### State machine (glasses)

```
READY ──tap──> CAPTURING ──sent──> WAITING ──response──> SPEAKING ──done──> READY
                   │                  │                      │
                   └─fail─> READY     ├─timeout─> READY      └─TTS fail─> READY
                                      └─error──> READY
```

Capture taps are accepted only in `READY`. This is what makes the one-at-a-time model cheap: no queue, no out-of-order handling, and `captureId` serves as a correctness check rather than routing machinery.

## Protocol

One new frame type, reusing the existing framing (`[type 1][length 4 BE][payload]`):

```kotlin
const val TYPE_RESPONSE: Byte = 0x14   // phone -> glasses, payload = UTF-8 JSON
```

It sits in the `0x10`–`0x13` block already allocated for capture, so it still cannot collide with the streaming types (`0x01`/`0x02`).

**This is the first frame that travels phone → glasses.** `0x10`–`0x13` all flow the other way. The connection becomes genuinely bidirectional, so `CaptureClient` gains a reader and `CaptureServer` gains a writer — neither has one today.

### Payload

```json
{
  "captureId": "cap_1785887705359_a3f2b8c1",
  "speak":      "Contains peanuts. Not safe for you — peanut sauce and crushed peanut garnish detected.",
  "display":    "Contains peanuts — not safe",
  "confidence": "high"
}
```

| Field | Required | Notes |
|---|---|---|
| `captureId` | yes | Must match the pending capture; mismatches are ignored |
| `speak` | yes | Full text read aloud |
| `display` | yes | Short line rendered on the lens |
| `confidence` | no | `high` \| `medium` \| `low`; omitted renders nothing |

Parsed with `org.json.JSONObject`, which ships with Android — the glass module has no JSON dependency today and does not need one added.

**Unknown fields are ignored**, so the phone can later add `detail`, `allergens[]`, or `warnings[]` without a glasses rebuild. This mirrors the forward-compatibility rule the master-agent plan sets for the phone ↔ Koog contract.

## Components

### Glasses

**`CaptureClient`** *(modified)* — gains a read loop coroutine started on `connect()`, using the same read-exactly-N approach the phone already uses. Dispatches `0x14` to `onResponse`, logs and skips unknown types, exits on EOF or close and marks itself disconnected so the next send fails fast instead of writing into a dead socket.

**`CaptureResponse`** *(new)* — data class plus `fromJson(String): CaptureResponse?`. Parsing lives here rather than in `CaptureClient` so the transport stays dumb and parsing is unit-testable without a socket. Returns null on malformed JSON or a missing required field; a bad payload must not crash the capture screen.

**`Speaker`** *(new)* — wraps `android.speech.tts.TextToSpeech`. Three things make it more than a one-liner:

1. Initialisation is asynchronous and can fail, so readiness is tracked and reported rather than silently swallowed.
2. Knowing when speech *finishes* requires `UtteranceProgressListener`; `speak()` returning does not mean the utterance completed, and the state machine needs the real completion to return to `READY`.
3. The engine is **pinned explicitly** to `com.google.android.tts` via the `TextToSpeech(context, listener, engine)` constructor. `tts_default_synth` is a secure setting subject to the same reset that already bit the recognizer: Android clears it when the configured package enters the stopped state, and the Mercury launcher force-stops the Google package when it leaves the foreground. Pinning sidesteps a failure mode we have already diagnosed once.

**`CaptureActivity`** *(modified)* — owns the state machine, gates taps on it, starts the timeout on entering `WAITING`, renders `display` plus confidence, and drives `Speaker`.

### Phone

**`CaptureServer`** *(modified)* — retains the active connection's `OutputStream` so a response can be pushed from outside the read loop. Writes are guarded by a lock since they originate on a different thread from the reader. One capture at a time means a single current-connection reference suffices; no connection registry.

**`AnswerProvider`** *(new)* — the seam for the out-of-scope work:

```kotlin
/** Phone-side answer, serialised into the 0x14 payload. */
data class CaptureAnswer(
    val speak: String,
    val display: String,
    val confidence: String? = null,   // "high" | "medium" | "low"
)

interface AnswerProvider {
    suspend fun answer(captureId: String, image: File, query: String?): CaptureAnswer
}
```

`CaptureAnswer` deliberately omits `captureId` — the server already knows which capture it is answering and stamps it when serialising, so a provider cannot return a mismatched id.

- **`EchoAnswerProvider`** — built now. Returns the transcribed query as the answer, making the full loop demoable immediately.
- **`KoogAnswerProvider`** — built later. POSTs multipart to `/v1/compute` and maps `ComputeResponse` to `CaptureAnswer`.

Swapping implementations is a one-line change in `MainActivity`. The mapping from Koog's schema to `{speak, display, confidence}` lives on the phone, which is what keeps the glasses decoupled from a schema the team still controls.

## Timeout

```kotlin
const val RESPONSE_TIMEOUT_MS = 120_000   // 2 minutes
```

A single named constant. Changing it is a one-line edit plus a rebuild, which takes roughly fifteen seconds with the current toolchain.

**Every response logs its measured round-trip time** (`response received in 3184ms`). Picking a good timeout is limited by knowing real latencies, not by how fast a new value can be applied — after a handful of end-to-end runs this constant can be set once, with evidence.

An intent-extra override was considered and rejected: the app is launched through the glasses UI (launcher → discover → connect → mode select → capture), so an extra would only ever be set when launching the activity directly over adb, which bypasses the real P2P flow. It would be a knob reachable only outside the situation it needs to tune.

## Error handling

| Failure | Behavior |
|---|---|
| No response arrives | Timeout → "No answer (timed out)" → `READY` |
| TTS unavailable or init failed | Show `display`, skip speech, log → `READY` |
| TTS errors mid-utterance | Text stays on screen → `READY` |
| Malformed JSON | Log, ignore, **stay** `WAITING` |
| `captureId` mismatch | Log, ignore, stay `WAITING` |
| Socket dies while waiting | Reader exits, mark disconnected, "Connection lost" → `READY` |
| Activity destroyed mid-wait | Shut down TTS, close client; no leaked engine or thread |

**Why malformed JSON stays in `WAITING`.** Dropping to `READY` would discard a subsequent valid response as unexpected. Staying costs at most one timeout and cannot lose a good answer. The timeout is the single escape hatch for "nothing usable is coming", which keeps the state machine's exits few and predictable.

**The TTS fallback is load-bearing.** The Google TTS voice-data download fails on this device because it has no Play Services, and synthesis from the APK's bundled voices is still unverified. If TTS does not work at all, this fallback is what keeps the feature demoable — text on the lens and no audio, rather than a broken flow.

## Testing

**Unit (no device):** `CaptureResponse.fromJson` — valid payload, missing required field, malformed JSON, unknown fields ignored, `confidence` absent versus null.

**On-device, with `EchoAnswerProvider`:**

1. Happy path — capture with query, answer speaks and displays, returns to `READY`
2. Taps ignored while waiting — tap repeatedly during `WAITING`, confirm exactly one capture is sent
3. Timeout — stub does not answer, "timed out" fires, returns to `READY`
4. Stale response — stub answers after the timeout, response ignored via `captureId`, no double-speak
5. Malformed payload — stub sends garbage, ignored, timeout fires
6. TTS absent — force-stop or disable Google TTS, text displays, no crash or hang
7. Connection lost — kill the phone app mid-wait, "Connection lost", recovers to `READY`

Tests 3–5 require the stub to be pushed into those behaviors, so `EchoAnswerProvider` carries a debug toggle for *never answer* / *answer late* / *answer garbage*. Without it those failure paths are aspirational rather than tested.

**Regression:** re-run live streaming and image-only capture. The streaming path is untouched, but the shared socket and protocol neighborhood makes it worth confirming.

## Open items

- Whether TTS produces audio at all on this device is unverified. Test 6 covers the fallback either way, but if synthesis proves impossible the spoken half of requirement 1 is unmet and the feature degrades to display-only.
- `RESPONSE_TIMEOUT_MS` starts at 2 minutes as a deliberate over-estimate; expect to lower it once the logged round-trip times show real latencies.
