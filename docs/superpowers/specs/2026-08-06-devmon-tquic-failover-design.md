# DevMon → TQUIC health-check failover — design

**Date:** 2026-08-06
**Status:** approved, ready for implementation planning
**Repo:** changes land in the `VideoShowCase` submodule (fork `sukoonsarin/VideoShowCase`, branch `hackathon26-arfood`)

## Problem

The phone has exactly one way to answer a capture: DevMon, over loopback HTTP to `127.0.0.1:8080`. DevMon can only answer when it has discovered a PC on the Wi-Fi that reports both an OpenAI-compatible endpoint and a vision-capable model. When it hasn't — app not running, no PC found, PC has no vision model — every capture fails, and the wearer hears an error.

Meanwhile the team has built a second, entirely independent route to a vision model: the phone's mpquic client app carries HTTP/3 requests over Multipath QUIC to an Ubuntu box that forwards to Ollama. It is running on the same phone, on a different port, and is currently unused by this app.

This design routes each capture to whichever backend can actually answer.

## Scope

**In scope:** deciding, per capture, whether to send to DevMon or to the TQUIC path; the TQUIC leg itself; and the failure handling around both.

**Out of scope:** anything inside DevMon, anything inside the mpquic apps, and the glasses. **No changes are requested of the DevMon developer** — this design works against DevMon exactly as merged in `32fee7a6`. The glasses do not learn which backend answered; `CaptureAnswer` is unchanged, so the `0x14` response frame and everything downstream of it stay as they are.

## Requirements

1. When DevMon can answer, it answers — the validated 24 s path is unchanged and stays the default.
2. When DevMon demonstrably cannot answer, the capture goes to the TQUIC path instead of failing.
3. A **slow** model is not a failure. A capture that is merely taking a long time must never be abandoned and restarted elsewhere.
4. Neither leg may push the total past the glasses' `RESPONSE_TIMEOUT_MS` (135 s) without a defined outcome.
5. Each leg must be selectable in isolation for testing, since the TQUIC leg is unproven.

## DevMon's health contract (observed, not negotiated)

Read from `HttpIngestServer.kt` at commit `32fee7a6`. Recorded here because it lives in another repo and this design depends on its exact behaviour.

`GET|POST /health`:

| Condition | Response |
|---|---|
| A discovered peer reports **both** an OpenAI endpoint **and** a vision-capable model | `200` `{"status":"ok","peerDiscovered":true,"visionModel":"..."}` |
| Anything else | **Silent socket close** — no status line, no body |

DevMon's own comment calls this "readiness, signalled by silence," and notes the cost: a silent close cannot be told apart from a crashed app or a dead network. **For failover that conflation is harmless** — all of those mean "do not send to DevMon" — which is why no change is being asked for.

This is a *readiness* signal, not a liveness one, which is exactly what this feature needs. `DevmonAnswerProvider.isHealthy()` already handles it correctly without modification: a silent close makes OkHttp throw, and the existing `catch (e: Exception) { false }` maps that to unhealthy. It has simply never been called — today it is dead code.

`POST /analyze` failure responses, from the same file:

| Status | Meaning |
|---|---|
| `400` | Malformed request, missing `image`/`query` part, bad `Content-Length` |
| `404` | Wrong method or path |
| `413` | Body over 8 MiB (our captures are ~61 KB, so unreachable in practice) |
| `503` | No peer reports both an endpoint and a vision model |
| `500` | The upstream call to the PC failed |

## Architecture

```
                                    ┌── healthy ──►  DevMon  ──HTTP/1.1──►  PC (mDNS)
CaptureServer ─► FailoverAnswerProvider
                                    └── unhealthy ─► TQUIC  ──HTTP/3──► 127.0.0.1:47443
                                                                         (mpquic client app)
                                                                              │ MPQUIC
                                                                              ▼
                                                                       Ubuntu terminus
                                                                              │ HTTP/1.1
                                                                              ▼
                                                                           Ollama
```

The existing `AnswerProvider` seam is the whole integration point. `CaptureServer` keeps calling `answer(captureId, image, query)` and does not learn that routing exists.

### Why not unify on one protocol

Considered and rejected. The team's stated goal was one protocol end-to-end to avoid translation at each node. It is not reachable: the inference servers (Ollama, LM Studio) serve HTTP/1.1 only, so the last hop translates no matter what — `tquic-vlm-server-interface` already documents this as its job. More decisively, DevMon has no HTTP/3 front door, so going H3-only on the phone would delete the working path rather than unify it. One translation, at the edge, is the correct end state.

## Components

**`HealthCheckedProvider`** *(new)* — `AnswerProvider` plus `name: String` (for logs) and `suspend fun isHealthy(): Boolean`.

**`DevmonAnswerProvider`** *(modified)* — implements `HealthCheckedProvider`. `isHealthy()` is unchanged in behaviour but gains a **dedicated OkHttpClient** with `HEALTH_CONNECT_TIMEOUT_SECONDS` = 2 and `HEALTH_READ_TIMEOUT_SECONDS` = 2. It must not share the answer client: a DevMon that accepts the connection but wedges would otherwise stall the *health probe* for the full 120 s read timeout, turning a safety feature into the worst hang in the system.

**`TquicAnswerProvider`** *(new)* — implements `HealthCheckedProvider`. Speaks HTTP/3 to `127.0.0.1:47443` via `KwikH3Transport`.

**`FailoverAnswerProvider`** *(new)* — the router. Probes primary health, picks a leg, delegates, and retries on the spare for the retriable failures below.

### Health probe cadence

Probed on demand at the start of each capture, and the result cached for `HEALTH_TTL_MS` (10 s).

On demand rather than background polling: polling needs a lifecycle to start, stop, and tie to the activity, and would burn battery between captures for no gain. The probe is cheap — sub-millisecond when DevMon is dead (connection refused on loopback), a few milliseconds when alive.

The cache exists so a burst of captures does not re-probe every time, while a backend that dies is still noticed within one TTL rather than staying selected indefinitely. A consequence worth accepting: for up to 10 s after DevMon fails, one capture may still be routed to it — that capture then falls back via the `503`/`500` path or, failing that, returns an error.

### The HTTP/3 client — what was planned, and what shipped

This design originally named `TquicNative` (`ai.koog.http.client.tquic`), on the grounds that it was the team's own stack and already in-tree. **That was wrong.** It is in-tree as Kotlin declarations only: `phone/shared/tquic-design/implementation-plan.md` states plainly that the `tquic-jni` crate behind them "has never been written," and no `libtquic_jni.so` exists anywhere. The recommendation was made without checking whether anything was behind the binding.

Nothing off the shelf fills the gap either:

| Candidate | Why not |
|---|---|
| OkHttp | No HTTP/3 support at all |
| `TquicNative` | Native library never written |
| `libmpquic_jni.so` | Exposes `nativeH3Listen` — a server; cannot originate a request |
| Cronet | Cannot be made to trust the tunnel's self-signed, SAN-less certificate |
| kwik + flupke | flupke is built on `java.net.http`, which Android does not ship |

What shipped is `KwikH3Transport`: **kwik** for QUIC (it does run on Android, and offers `noServerCertificateCheck()` for the tunnel's cert), **`tech.kwik:qpack`** for header compression, and a hand-written HTTP/3 framing layer covering the few frames one request/response needs. About 200 lines, with the framing unit-tested against the worked examples in RFC 9000 §16.

**The trap, recorded because it will bite anyone who touches this:** `Encoder.compressHeaders` returns its buffer still in *write* mode — position and limit both at the end — so reading `remaining()` bytes yields an **empty** header block. The QUIC connection succeeds, and only then does the peer reject the request with `qpack decode error: BufferTooShort`, which reads like a transport fault and is not one. `encodedHeaderBlock` flips it (through `java.nio.Buffer`, so Android's pre-Java-9 `ByteBuffer` signature is the one invoked), and a test pins the behaviour from both directions.

**Operational finding:** the MPQUIC app's engine dies with `socket send_to(): PermissionDenied` when Android backgrounds it, which happens as soon as VideoShowCase comes to the foreground. Exempting it fixes this and needs no app change:

```
adb shell dumpsys deviceidle whitelist +com.mpquic.client
adb shell cmd appops set com.mpquic.client RUN_ANY_IN_BACKGROUND allow
```

Without that exemption the fallback fails on any real capture, since the wearer's app is by definition in the foreground.

### TQUIC request and response shape

All of the following was **verified live on 2026-08-06** — see "Step 0" under Testing.

| Property | Value |
|---|---|
| Target | `https://127.0.0.1:47443` |
| Method / path | `POST /infer` |
| Content-Type | `application/json` |
| Body | OpenAI `/chat/completions` shape, JPEG base64 in a `data:` URI |
| Success | `200`, body is the backend's raw JSON |
| Answer field | `choices[0].message.content` |

The mpquic tunnel runs in `answer_mode: "forward"`, which POSTs the tunneled body **verbatim** to the configured backend ("no packaging/repackaging of any kind" — `forward.rs`). The body must therefore already be what Ollama accepts.

Note this is deliberately *not* the simpler `{"jpeg","prompt"}` → `text/plain` contract. That one applies only when talking directly to `tquic-vlm-server-interface`, which would bypass the tunnel and the multipath work this route exists to use.

**Read `content`, never `reasoning`.** The observed model (`qwen3-vl:8b` on Ollama) returns a `reasoning` field alongside `content` in the same message object, containing its chain of thought — *"So, let's look at the image. First, it's a workspace with various tech items…"*. Since the glasses speak `speak` verbatim, reading the wrong field would have the wearer listening to the model's internal monologue. This is a live hazard, not a hypothetical: it was present in both verification responses.

**Base64 inflation:** a 62,323 B JPEG became an 83,291 B request body (~1.34×). Comfortably under the tunnel's limits, but it means the TQUIC leg sends roughly a third more bytes than the DevMon leg for the same capture.

## Failover policy

**Fail over to TQUIC on:**

| Signal | Meaning |
|---|---|
| Health probe fails (refused, silent close, timeout) | DevMon down, no peer, or no vision model |
| `503` from `/analyze` | The peer vanished *between* the health check and the request |
| `500` from `/analyze` | Upstream failed — PC unreachable or the model errored |

The `503` case is what justifies handling request failures at all rather than health alone: health passes, the PC drops off Wi-Fi a second later, and the capture is still answered.

**Do not fail over on:**

| Signal | Why |
|---|---|
| `400` / `404` / `413` | Our request is malformed or oversized. TQUIC would fail identically; retrying hides the bug and doubles the wearer's wait |
| **Timeout** | A slow model is a working model — see below |

### Why a timeout is not a failure

Requirement 3, stated explicitly because it was the most tempting mistake in this design. DevMon sets no timeout on its call to the PC (Koog's Ktor client, unconfigured; observed still waiting at 45 s), so a cold or slow model produces no error — it simply takes a long time. Treating that as unhealthy would abandon a request that is about to succeed and restart on a possibly-cold second path, making the wait longer rather than shorter.

A timeout therefore produces a spoken error, exactly as it does today. This design does not make the cold-model case better, and does not pretend to.

## Time budget

`FailoverAnswerProvider` computes a **deadline** on entry, `DEADLINE_MS` (125 s) from the start of the call, leaving ~10 s of margin under the glasses' 135 s `RESPONSE_TIMEOUT_MS`.

The TQUIC leg is given whatever remains of that deadline, not a fixed timeout. Without this, a DevMon failure at 60 s followed by a fixed 100 s TQUIC attempt would run to 160 s — well past the point the glasses stopped listening, so the work is thrown away and the wearer waits for nothing.

**If less than `MIN_FALLBACK_BUDGET_MS` (15 s) remains, the fallback is skipped** and DevMon's error is returned. Attempting a call that cannot finish in time is strictly worse than answering immediately. 15 s is chosen as comfortably below the 24 s measured round trip: any budget smaller than that could not have succeeded even on the warm path.

DevMon's own 120 s read timeout and the glasses' 135 s are both unchanged.

## Configuration

`MainActivity` gains `FAILOVER` as an `answer_provider` value, and it becomes the default. `DEVMON` is retained meaning "DevMon only, no fallback" so either leg can be exercised alone — necessary because the TQUIC leg is unproven and a failure needs to be attributable to one side.

A `tquic_port` extra defaults to `47443`.

## Error handling

| Failure | Behavior |
|---|---|
| DevMon unhealthy, TQUIC answers | Answer delivered; log records which leg served it |
| DevMon unhealthy, TQUIC also fails | Spoken error, glasses return to `READY` |
| DevMon `503`/`500`, TQUIC answers | Answer delivered |
| DevMon `400`/`404`/`413` | Spoken error, no failover attempted |
| DevMon times out | Spoken error, no failover attempted |
| Budget exhausted before the fallback | DevMon's error returned, fallback skipped |
| Health probe hangs | Capped at ~2 s by its dedicated client, then treated as unhealthy |

Every routing decision is logged with the capture id and the chosen leg's `name`, so a demo-day failure can be attributed from logcat alone.

## Testing

### Step 0 — prove the TQUIC path exists — **DONE, passed 2026-08-06**

This gate existed because nobody had sent an image through phone → mpquic → EC2 → Ollama, and building a careful failover to a destination that cannot answer would have been the largest available waste. It has now been run twice, live.

Topology (from `tquic-vlm-server-interface/docs/mpquic-tunnel-verification.md`): the phone's mpquic client tunnels over the internet to **EC2 `54.190.37.190:10000`**, running `tquic-vlm-server-interface --mpquic-bind 0.0.0.0:10000` in `answer_mode=forward`, which forwards to Ollama (`qwen3-vl:8b`) on the same box.

Reproduce with:

```
pip install aioquic
python mpquic/tools/h3_sender.py 10.73.51.71 request.json \
  --port 47443 --path /infer --content-type application/json --timeout 120
```

Prerequisites: mpquic client app running, server address set to the EC2 endpoint, **Connect** tapped, then **Start HTTP/3 RX** on 47443. The h3 listener dies with the tunnel, so connect first.

Results:

| Payload | Request body | Response | Time |
|---|---|---|---|
| Repo sample image | 112,217 B | `200`, 2,907 B | 14.0 s |
| **Real 640×480 glasses capture** | 83,291 B | `200`, 3,915 B | **18.7 s** |

The second run used the actual capture and query from the 2026-08-06 device test (`cap_1786037877556_d411b084`, "What am I looking at") and returned a correct description of the same scene DevMon described. **TQUIC 18.7 s versus DevMon 24.4 s** — the fallback is not a slow path, and both sit far inside the 125 s deadline.

### Unit (no device)

Following the existing pattern in this module — pure, `android.util.Log`-free functions, testable on the JVM:

- **Routing:** fake `HealthCheckedProvider`s; healthy → primary, unhealthy → fallback
- **Retriable-status table:** `503`/`500` retriable; `400`/`404`/`413` not. Table-driven
- **TQUIC response mapping:** raw Ollama JSON → `CaptureAnswer` via `choices[0].message.content`; plus malformed JSON, missing `choices`, empty content. Mirrors `parseAnalyzeResponse`
- **Health cache TTL:** injectable clock; probe once inside the window, re-probe after it
- **Budget:** a fallback is skipped when the remaining deadline is too small

### On device — **run 2026-08-06**

Run against the phone's real `CaptureServer` on 8889 using a stand-in for the glasses
(`fake_glasses.py`, which speaks the `VSCQ` protocol) rather than by driving the P2P pairing flow
across two devices. Every change in this design is phone-side, so the glasses add nothing to what
is under test while adding considerable setup fragility.

| # | Scenario | Result |
|---|---|---|
| A | DevMon healthy | ✅ `routing to DevMon`, answered in **24.9 s** vs the 24.4 s baseline — no regression. Health probe → routing decision in **3 ms** |
| B | DevMon force-stopped | ✅ probe refused, `DevMon unhealthy, routing to TQUIC`, whole capture resolved in **0.2 s** — no hang |
| C | `answer_provider=DEVMON` with DevMon down | ✅ zero router log lines; DevMon's own error surfaced |
| D | Peer lost between probe and request | ✅ **fired for real, unplanned**: `200 /health` then `503` from `/analyze` then `DevMon failed, retrying on TQUIC` |

Test D is the one worth keeping. It was not staged — DevMon genuinely passed its health check and then rejected the request — and it is exactly the race that justifies handling request failures at all rather than health alone. Had this design been health-only, that capture would simply have failed.

**The installed DevMon does not match the merged source.** Its `/health` returned
`200 {"status":"ok","peerDiscovered":false,"visionModel":null}` — a response `respondHealth` in
`32fee7a6` calls "unreachable by construction", since it should close the socket silently with no
peer. The APK on the phone was last updated 2026-08-06 10:12; the endpoint was committed at 11:39
and merged at 12:30. **DevMon needs rebuilding and reinstalling from main before the health check
behaves as this design assumes.**

Until it is, `/health` answers `200` unconditionally, so the health probe never reports unhealthy
and the `503` retry path is carrying the entire feature on its own. That it works anyway is the
argument for having built both mechanisms.

### Still unverified

- Slow model → waits it out on DevMon, no failover (needs a genuinely slow model; the rule itself
  is covered by unit tests on `isDevmonTimeout`)
- Both backends down → glasses return to `READY` and accept the next capture (needs the real
  glasses, whose state machine is untouched by this work)
- Regression: live streaming and image-only capture (untouched paths)

## Open items

- **The fallback depends on manual phone setup that nothing enforces.** The mpquic client app must be running, connected to EC2, and have HTTP/3 RX started — three UI taps, lost on every app restart, and the h3 listener dies with the tunnel. A wearer cannot know this failed. `TquicAnswerProvider.isHealthy()` can detect it (nothing bound on 47443), but nothing can *fix* it from inside this app. Treat it as a demo-day checklist item.
- **The EC2 endpoint is hardcoded into someone's phone, not into any config.** `54.190.37.190` is an EC2 public IP, which changes if the instance is stopped and started. If the tunnel stops working, re-check this address before debugging anything in this app.
- The cold/slow-model case remains uncovered by design (requirement 3). If it later proves to be the dominant failure, the fix belongs in DevMon — a bounded upstream timeout returning `500` — not in this router.
- **Both backends return long, markdown-heavy prose.** The TQUIC leg has exactly the same problem already seen with DevMon: `**bold**`, bullet lists, and answers long enough to exceed the glasses' 60 s playback watchdog. Being addressed by prompt tuning, not by this design, but the fallback inherits it rather than escaping it.
