# Glasses → Phone → Koog integration — handoff spec

**From:** Sukoon (glasses + relay phone app)
**For:** whoever owns the Koog Master Agent
**Date:** 2026-08-05
**Status:** phone side built and unit-tested; not yet run against a live Koog instance

---

## TL;DR

The glasses-to-phone half is done and verified on hardware. The phone can now call
`POST /v1/compute` — that code exists and is committed, just not switched on yet.

**I believe I need nothing changed on the Koog side.** I read `ComputeRoutes.kt`,
`ComputeResponse.kt`, `PromptSupport.kt`, `AndroidConfig.kt` and `KoogHttpServer.kt` and built
against them as they are. What I need from you is the APK, confirmation of a couple of
assumptions, and a decision about the LLM backend.

---

## What already works

**Glasses → phone** (verified on hardware today): the glasses capture a JPEG, transcribe a spoken
question on-device, and send both to the phone over Wi-Fi Direct on TCP 8889. The phone saves them
as `image.jpg` + `query.txt`. The phone can send an answer back on the same socket, and the
glasses display it and read it aloud. Round trip measured at 84–269 ms with a local stub.

**Phone → Koog** (built, not switched on): `KoogAnswerProvider` does the multipart POST and maps
the response. It sits behind an `AnswerProvider` interface, so switching from the current echo stub
to the real thing is a one-line change once your APK is on the device.

---

## The contract I built against

### Request — `POST http://127.0.0.1:8080/v1/compute`, `multipart/form-data`

| Part | Kind | Sent? | Notes |
|---|---|---|---|
| `image` | **file part** | always | raw JPEG bytes, `Content-Type: image/jpeg`. Sent as a file part because `parseComputeRequest` only reads `PartData.FileItem`. |
| `query` | form field | always, never blank | see the note below |
| `useCase` | form field | always | `UC1` |
| `sessionId` | form field | never (for now) | session resume is out of scope until `/v1/sessions` is implemented |

**On `query` never being blank.** The glasses support capturing *without* speaking — it is an
explicit button, and it is also the automatic fallback when speech recognition fails or hears
nothing (a real, observed condition on the RayNeo). Your code 400s on a blank query, so rather
than ask you to relax that, **the phone substitutes a default prompt** in that case:

> `"Identify the ingredients or allergens in this food item."`

So you will always receive a non-blank query and never need to handle the empty case. If you would
rather own that default so it lives next to the prompt templates, say so and I will drop it.

### Response — `200`

I read these three fields and ignore the rest:

| Field | Used for |
|---|---|
| `answer` | spoken aloud on the glasses, verbatim |
| `answer` (shortened) | the short line shown on the lens |
| `confidenceLabel` | rendered as a suffix — your `high`/`medium`/`low` already matches my enum exactly |

Ignored for now but no problem to send: `sessionId`, `useCase`, `status`, `confidence`, `detail`,
`allergens[]`, `menuSuggestions[]`, `totalMs`, `warnings[]`. My parser ignores unknown fields, so
you can add more without breaking me.

**`answer` is read aloud verbatim**, so it wants to be a sentence a person can listen to — not
JSON, not a stack trace, not something with markup.

### Errors

I handle `400 bad_request`, `503 no_target_available`, `503 transport_unavailable`, plus timeouts
and connection-refused. Every one produces a short spoken sentence for the wearer; the detail goes
to logcat. Nothing throws, and a failure never wedges the capture screen.

### Health

I also use `GET /v1/health` to detect "Koog isn't running" and say so, instead of failing
mysteriously. Only that the call succeeds matters to me, not the body.

---

## What I need from you

1. **The `master-agent-android` APK** (or tell me to build it — your README covers it, and I have
   the Android SDK set up). It is not currently installed on my phone.

2. **Confirm port 8080.** `KoogHttpServer` defaults to it and `MasterAgentService` hosts it there,
   so that is what I hardcoded. It is one constant on my side if it changes.

3. **Confirm the foreground Service keeps the port alive while your app is backgrounded.** My app
   is in the foreground during a capture, so yours will be backgrounded at exactly the moment I
   call it. Your README says the Service exists for this reason — I just want it confirmed on a
   real device, since the RayNeo/Mercury launcher aggressively force-stops backgrounded apps and
   Android may treat yours the same way.

4. **A decision on the LLM backend.** `AndroidConfig.kt` sets `LlmBackend.Mock` with the comment
   *"Mock backend by default so the app runs on-device with no API key or network."*
   `MockComputeExecutor` returns `"Contains peanuts - not safe for you."` regardless of the image.

   That is genuinely useful for wiring — but it also means an end-to-end run will *look* like a
   success while proving nothing about real answers, which is an easy way to fool ourselves before
   a demo. Please tell me:
   - Is a real backend configured anywhere yet?
   - How do I switch to it (API key? asset? build flag?)
   - Should the demo run on mock or live?

---

## How to test this together

**Step 1 — plumbing (I can do this alone once I have your APK).** Install Koog, start the service,
then swap `EchoAnswerProvider` for `KoogAnswerProvider` in my `MainActivity`. Capture from the
glasses. Success = the mock's canned answer appears on the lens and is spoken aloud. This proves
the transport, multipart encoding, and response mapping. It proves nothing about the model.

**Step 2 — real answers (needs you).** With a live backend configured, capture a real food item and
check the answer actually describes it. This is the first point at which the feature is genuinely
working.

Useful while debugging:
```bash
# is Koog up?
adb -s <phone> shell curl -s http://127.0.0.1:8080/v1/health

# my side
adb -s <phone> logcat | grep -E "KoogAnswerProvider|CaptureServer"

# glasses side
adb -s <glasses> logcat | grep -E "CaptureActivity|CaptureClient|Speaker"
```

---

## Things I noticed while reading your code

Not requests, just things you may not have on your radar:

- **`GET /v1/sessions/{id}` returns 501.** Session resume (UC3 in the plan) is not implemented, so
  I am not sending `sessionId`. Fine by me — just flagging that UC3 is not reachable yet.
- **`KoogHttpServer` binds `0.0.0.0`, not `127.0.0.1`.** On a phone with wifi that exposes the
  agent to the whole local network with no auth. Harmless on a hackathon LAN, worth a thought
  before it goes anywhere real.
- **Real JPEGs from the glasses are 3.8–4.2 MB.** Larger than you might have assumed if you tested
  with small fixtures — worth checking there is no size limit in the multipart handling.

---

## Where the code is

| What | Where |
|---|---|
| My HTTP client | `VideoShowCase/app/.../capture/KoogAnswerProvider.kt` |
| The swappable seam | `VideoShowCase/app/.../capture/AnswerProvider.kt` |
| Current stub (stays as fallback) | `EchoAnswerProvider` in the same file |
| Your routes | `phone/shared/koog/multiverse/master-agent/src/jvmMain/kotlin/ai/koog/multiverse/api/ComputeRoutes.kt` |
| Your Android wiring | `phone/shared/koog/multiverse/master-agent-android/` |

`VideoShowCase` is a submodule pointing at `sukoonsarin/VideoShowCase`, branch `hackathon26-arfood`.
