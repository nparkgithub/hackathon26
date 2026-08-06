# DevMon HTTP API — request from the glasses/relay phone app

**What this is for:** the VideoShowCase relay phone app captures a photo + a spoken question from
the AR glasses and needs an answer. DevMon already knows how to reach the PC's vision LLM
(`OpenAiAnalysisClient`), so the phone app wants to hand it the image and get the answer back.

Both apps run on the **same phone**, so this is loopback HTTP — nothing leaves the device on this
hop.

**Status:** the phone-app client is written and committed against this contract. The DevMon side
does not exist yet — this is the spec for building it.

---

## What DevMon needs to add

1. **An HTTP server on port `47532`.** Port `47531` is already taken by DevMon's existing telemetry
   `ServerSocket`, so this is a second, separate listener. Any embedded server is fine — NanoHTTPD,
   Ktor CIO, or a hand-rolled one; the phone app only cares about the HTTP contract below.

2. **`OpenAiAnalysisClient.analyze(...)` must accept and use a query string.** It currently
   hardcodes `ALLERGY_PROMPT` and takes no user question. The whole point of the glasses flow is
   that the user *speaks* a question, so that text has to reach the model — otherwise every capture
   gets the same canned prompt and the speech-to-text half of the feature does nothing.

   Suggested: keep `ALLERGY_PROMPT` as the default when the incoming query is blank, and use the
   caller's query otherwise. The phone app already guarantees a non-blank query, so in practice the
   default should never fire — but keep it as a safety net.

---

## `POST /analyze`

`Content-Type: multipart/form-data`

| Part | Kind | Required | Content-Type | Notes |
|---|---|---|---|---|
| `image` | **file part** | yes | `image/jpeg` | Raw JPEG bytes, **not** base64. Currently ~3.7–4.0 MB per capture; will shrink if we lower capture resolution. |
| `query` | form field | yes | — | The user's spoken question, plain UTF-8 text. Never blank — the phone substitutes a default if the user captured without speaking. |

`image` is sent as a **file part** (with a `filename`), not a plain form field. If your server
distinguishes the two, make sure it reads file parts.

### Example request on the wire

```http
POST /analyze HTTP/1.1
Host: 127.0.0.1:47532
Content-Type: multipart/form-data; boundary=----Boundary7a3f9c2e
Content-Length: 4232891

------Boundary7a3f9c2e
Content-Disposition: form-data; name="image"; filename="image.jpg"
Content-Type: image/jpeg

<4,232,530 raw JPEG bytes>
------Boundary7a3f9c2e
Content-Disposition: form-data; name="query"

what allergens are in this food
------Boundary7a3f9c2e--
```

### Success — `200 OK`, `application/json`

```json
{
  "answer": "This looks like a peanut sauce noodle dish. Visible ingredients suggest peanuts...",
  "model": "llava:7b",
  "endpoint": "http://192.168.1.20:11434"
}
```

| Field | Required | Notes |
|---|---|---|
| `answer` | **yes** | The analysis text, straight from the model. **Read aloud verbatim on the glasses**, so it should be a plain spoken-language sentence — no markdown, no JSON, no code fences. |
| `model` | no | Which model answered. Logged only, not shown to the user. |
| `endpoint` | no | Which PC served it. Logged only. |

Extra fields are ignored by the client, so you can add more freely without breaking it.

**There is no `confidence` field** and none is expected — the OpenAI-compatible path doesn't produce
one. The glasses simply omit the confidence indicator.

### Errors — JSON body, non-2xx

```json
{ "error": "no_peer", "message": "No PC with a vision model discovered yet." }
```

| Status | `error` | When |
|---|---|---|
| `503` | `no_peer` | No PC discovered over mDNS yet |
| `503` | `no_vision_model` | Peer found, but no vision-capable model advertised |
| `502` | `upstream_failed` | The PC's LLM call failed or timed out |
| `400` | `bad_request` | Missing image or query |

`message` is for logs and diagnostics. The phone app does **not** read it aloud — the wearer hears
a short generic sentence instead, so feel free to make `message` technical and specific.

---

## `GET /health`

Used to warn the user that DevMon isn't running *before* they take a capture, rather than failing
afterwards.

```json
{ "status": "ok", "peerDiscovered": true, "visionModel": "llava:7b" }
```

Only the HTTP status matters to the client right now — `200` means reachable. The body is for
humans debugging. Returning `200` with `"peerDiscovered": false` is fine and useful.

---

## Behaviour notes

- **Timeouts:** the phone allows a short connect timeout and a generous read timeout, since a
  vision model on a PC can take a while. Don't return early with a partial answer — take the time
  you need.
- **One request at a time.** The glasses enforce a single in-flight capture, so DevMon will not see
  concurrent `/analyze` calls from this client.
- **Payload size.** ~4 MB per request today. If your server framework has a default body-size cap
  (many do, often 1–2 MB), it will need raising.
- **Cleartext.** DevMon's manifest already sets `usesCleartextTraffic="true"`, so nothing to change
  there. The phone app permits cleartext to loopback only.

---

## Quick test without the phone app

Once the endpoint exists, this should work from a shell on the phone:

```bash
adb shell curl -s -X POST http://127.0.0.1:47532/analyze \
  -F "image=@/sdcard/test.jpg;type=image/jpeg" \
  -F "query=what allergens are in this food"

adb shell curl -s http://127.0.0.1:47532/health
```

If curl works and the phone app doesn't, the bug is on our side — tell us and we'll fix it.

---

## Contact points

| What | Where |
|---|---|
| Phone-app client | `VideoShowCase/app/.../capture/DevmonAnswerProvider.kt` |
| The swappable interface | `VideoShowCase/app/.../capture/AnswerProvider.kt` |
| DevMon's existing analyzer | `local_llm/mdns/devmon/app/.../OpenAiAnalysisClient.kt` |
| DevMon's existing telemetry server (port 47531) | `local_llm/mdns/devmon/app/.../AdvertiserService.kt` |

Anything in this contract is negotiable — it's written to match what DevMon can already do. If a
different shape is easier on your side, say so and we'll change the client.
