# Session handoff — 2026-08-06

Written so work can resume from a cold start with no conversation history.
Supersedes nothing; `docs/session_handoff_2026-08-04.md` covers the earlier session
and is still accurate for what it covers.

---

## 1. What this project is

**ARFood** — a wearable allergen checker built for a hackathon (week of 2026-08-03).

You wear RayNeo X3 AR glasses. You look at food, tap to capture, and speak a question
("can I eat this"). The photo and the question travel to your phone over Wi-Fi Direct.
The phone sends them to a vision LLM, gets an answer back, decides whether the food is
safe **for your specific allergy profile**, and sends the answer back to the glasses,
which display it on the lens and read it aloud — colour-coded red if it is unsafe.

### Scope ownership

The user owns:

- **everything on the RayNeo X3 glasses side**, and
- **interfacing the phone app with both the glasses and the backend agent service.**

Teammates own the **DevMon** app (phone→PC LLM bridge), the **MPQUIC** tunnel app, and
the **EC2/Ollama** backend. A hard constraint throughout: *"team doesn't want any changes
on the other apps."* Everything built here adapts to their interfaces as they already
exist.

---

## 2. Repository layout

Two repos, one nested inside the other as a submodule.

### Parent — `nparkgithub/hackathon26`

- Remote: `git@github.com:nparkgithub/hackathon26.git`, branch `main`
- Shared with teammates. Their work lives in `mpquic/`,
  `tquic-vlm-server-interface/`, and the presentation docs.
- Our work in the parent is confined to `docs/superpowers/` (specs and plans) and the
  `VideoShowCase` gitlink.
- **Zero file overlap** with teammates' work — verified before the last rebase.
- Head at handoff: **`9f8e83f0`**, in sync with origin (`0 0`).

### Submodule — `VideoShowCase`

- Path: `VideoShowCase/`
- Fork: `git@github.com:sukoonsarin/VideoShowCase.git` (remote `origin`)
- Upstream: `https://github.com/rayneo-develop/VideoShowCase.git` (remote `upstream`) —
  this is RayNeo's official SDK sample app, which the whole thing is built on top of.
- Branch: **`hackathon26-arfood`**
- Head at handoff: **`a5fec1c`**, in sync with origin (`0 0`)
- 42 commits ahead of the fork point.

Both repos are clean and fully pushed. The gitlink in the parent points at `a5fec1c`.

**After any fresh clone or pull, teammates must run:**

```bash
git submodule update --init --recursive
```

Without it they get the pre-ARFood VideoShowCase.

### Untracked on purpose

- `docs/session_handoff_2026-08-04.md`
- `docs/videoshowcase-doc/`

Left alone all session because the user never asked for them. Not accidental.

### Two Gradle modules inside VideoShowCase

| Module | Runs on | Package |
|---|---|---|
| `app` | the **phone** (Samsung, targetSdk 36) | `com.example.video.show.demo` |
| `glass` | the **RayNeo X3 glasses** (Android 12) | `com.example.video.show.glass` |

---

## 3. The full pipeline

```
 GLASSES (RayNeo X3)                PHONE (ARFood)                    BACKEND
 ─────────────────────             ────────────────                  ─────────
 tap → camera still                CaptureServer :8889
 speech-to-text query      ──VSCQ──►  ↓
                                   FailoverAnswerProvider
                                     ├─ healthy? ──► DevMon HTTP/1.1 ──► PC LLM
                                     │              127.0.0.1:8080
                                     └─ else ─────► TQUIC HTTP/3 ──────► MPQUIC app
                                                    127.0.0.1:47443        ↓
                                                                    ┌──────┴──────┐
                                                                  wlan0        rmnet0
                                                                  (IPv4)       (IPv6)
                                                                    └──────┬──────┘
                                                                     EC2 54.190.37.190:10000
                                                                     and [2600:1f14:…]:10000
                                                                           ↓
                                                                     Ollama qwen3-vl:8b
 lens text + TTS           ◄─VSCQ──  answer + verdict  ◄────────────────────┘
```

### Leg 1 — glasses ↔ phone: Wi-Fi Direct + the VSCQ protocol

Wi-Fi Direct P2P. **The phone is the Group Owner** and always lands on `192.168.49.1`.
The glasses join the group and dial the phone.

Custom TCP protocol on port **8889**, defined identically in both modules'
`capture/CaptureProtocol.kt`:

- Header: magic `"VSCQ"`, 8 bytes, version 1
- Frames: `[type: 1 byte][length: 4 bytes big-endian][payload]`
- Max payload 16 MB

| Type | Name | Direction |
|---|---|---|
| `0x10` | `TYPE_CAPTURE_BEGIN` | glasses → phone |
| `0x11` | `TYPE_IMAGE_JPEG` | glasses → phone |
| `0x12` | `TYPE_QUERY_TEXT` | glasses → phone |
| `0x13` | `TYPE_CAPTURE_END` | glasses → phone |
| `0x14` | `TYPE_RESPONSE` | **phone → glasses** |

Types start at `0x10` so they can never collide with the pre-existing streaming types
(`0x01`/`0x02`) on the same transport.

### Leg 2 — phone → LLM: two backends, health-routed

See section 5.

---

## 4. Phone app (`VideoShowCase/app`)

All new code lives in
`app/src/main/java/com/example/video/show/demo/capture/`.

### Files and what each one is for

| File | Purpose |
|---|---|
| `CaptureProtocol.kt` | VSCQ constants (pre-existing, extended) |
| `CaptureServer.kt` | TCP server on 8889; receives captures, writes the response frame |
| `AnswerProvider.kt` | The original seam: "given an image and a query, produce an answer" |
| `CaptureAnswer.kt` | The answer value type. **Gained `verdict: String?`** |
| `HealthCheckedProvider.kt` | Extends the seam with `name`, `computeLabel`, `isHealthy()`, `attempt()`. Defines `AnswerAttempt(answer, retriable)` |
| `FailoverAnswerProvider.kt` | The router. Picks DevMon or TQUIC by health, retries, enforces a shared deadline |
| `DevmonAnswerProvider.kt` | HTTP/1.1 leg to DevMon (teammates' app) |
| `TquicAnswerProvider.kt` | HTTP/3 leg — builds the OpenAI body, posts it, parses the reply |
| `H3Transport.kt` | Transport seam. `H3Result.Success/Failure` + `UnavailableH3Transport` |
| `KwikH3Transport.kt` | The real HTTP/3 client, built on kwik |
| `H3Framing.kt` | Hand-written HTTP/3 framing and QUIC varints |
| `OpenAiResponse.kt` | Parses `choices[0].message.content` |
| `RetriableStatus.kt` | Decides which failures are worth failing over on |
| `PromptShaping.kt` | Builds the silent system prompt; parses the verdict back out |
| `AllergyProfile.kt` | Loads the user's allergy list |
| `CaptureImage.kt` | EXIF-correct decoding, downsampling, thumbnails |
| `CaptureHistoryPanel.kt` | The scrolling capture history UI (replaced `CapturePanel.kt`, deleted) |
| `KoogAnswerProvider.kt` | Pre-existing alternative provider, unused by default |

### Resources added

- `res/layout/item_capture.xml` — one history row
- `res/values/colors.xml` — the real palette
- `res/drawable/bg_card.xml`, `bg_image_frame.xml`,
  `bg_pill_safe.xml`, `bg_pill_unsafe.xml`, `bg_pill_unknown.xml`
- `res/menu/main_menu.xml` — the overflow menu
- `assets/allergy_profile.json`

---

## 5. The failover design

**The problem.** DevMon (phone→PC LLM) is not always up. When it isn't, captures used to
just fail. We wanted an automatic second route — but without asking the DevMon or MPQUIC
teams to change anything.

**The rule.** Try DevMon. If DevMon says it isn't ready, or fails in a way that suggests
the server is broken, go to the HTTP/3 route instead.

### What "healthy" means

DevMon exposes `GET /health`. A teammate implemented it. It returns **200 only when a
peer has reported both an OpenAI-compatible endpoint and a vision model.** Otherwise it
**closes the socket silently** — no status code at all.

That silent close is the single most important behaviour to know. It is why the health
client exists separately from the main client (below).

### Two OkHttp clients, deliberately

`DevmonAnswerProvider` holds **two** clients:

- `client` — read timeout **120 s**, for the actual inference call. A cold vision model
  genuinely takes that long.
- `healthClient` — connect/read timeouts of ~**2 s**, for `/health` only.

Without the split, a health probe against a dead DevMon would hang for two minutes
*before routing had even begun*, turning a fallback into a worse outage than no fallback
at all.

### A slow model is a working model

Explicit user decision: **timeouts are not unhealthy.** `RetriableStatus.kt` encodes it:

```kotlin
fun isRetriableDevmonStatus(status: Int) = status in 500..599
fun isDevmonTimeout(e: Throwable) = e is SocketTimeoutException
```

- **5xx** → retriable, fail over.
- **4xx** → our bug, don't fail over, don't hide it.
- **Timeout** → the model is just slow. Not a health failure.

The reasoning was explicitly to avoid asking the DevMon developer to change anything.

### Router constants (`FailoverAnswerProvider.kt`)

| Constant | Value | Why |
|---|---|---|
| `HEALTH_TTL_MS` | 10 s | Cache the health verdict; don't probe on every capture |
| `DEADLINE_MS` | 125 s | Total budget shared across both legs |
| `MIN_FALLBACK_BUDGET_MS` | 15 s | Don't start the fallback if less than this remains — a doomed second attempt just delays the error |
| `LOG_TAG` | `"FailoverAnswerProvider"` | Routing decisions log under their **own** tag, not the caller's |

Callbacks `onRoute` / `onPathChosen` drive the phone's compute-path indicator.

Timeout chain, all deliberately ordered:
`glasses RESPONSE_TIMEOUT_MS 135 s` > `DEADLINE_MS 125 s` > `DevMon read 120 s`.

---

## 6. The HTTP/3 client — the hardest part of the project

### A correction worth preserving

An earlier recommendation to use `TquicNative` was **wrong**. The `tquic-jni` binding
**has never been written** — there is no native library behind it. Anything that assumes
otherwise is a dead end. This was discovered mid-implementation, which is why
`H3Transport` is a seam with an honest `UnavailableH3Transport` stub: the rest of the
system was built and tested against the seam while a real transport did not exist.

### What was built instead

```kotlin
implementation("tech.kwik:kwik:0.10.8")   // raw QUIC
implementation("tech.kwik:qpack:2.0.1")   // header compression
```

**kwik only, deliberately.** Its companion HTTP/3 library, *flupke*, is built on
`java.net.http`, **which Android does not ship**. So the HTTP/3 framing is hand-written
on top of kwik's raw QUIC streams (`H3Framing.kt`), and only header compression — the
part genuinely not worth hand-rolling — comes from qpack.

`H3Framing.kt` implements `encodeVarint` / `readVarint` (QUIC varints, RFC 9000 §16),
`h3Frame`, `readExactly`, `statusFromHeaders`, and `encodedHeaderBlock`.

`KwikH3Transport.kt` opens a QUIC connection with ALPN `h3` and
`noServerCertificateCheck()`, sends a control stream with SETTINGS, then HEADERS + DATA.

| Constant | Value |
|---|---|
| `DEFAULT_HOST` | `127.0.0.1` |
| `DEFAULT_PORT` | `47443` |
| `ALPN_H3` | `h3` |
| `INFER_PATH` | `/infer` |
| `JSON_CONTENT_TYPE` | `application/json` |
| `DEFAULT_MODEL` | `qwen3-vl:8b` |

### The QPACK bug — worth remembering

`qpack`'s `compressHeaders` returns a buffer in **write mode** (`remaining() == 0`).
Sending it directly produced an empty header block and a `BufferTooShort` error on the
far side.

Fix: `encodedHeaderBlock()` flips the buffer — but casting to **`java.nio.Buffer`**
before calling `flip()`. Calling `flip()` on the `ByteBuffer` type directly compiles
against Java 9's covariant return type and throws `NoSuchMethodError` on Android.

---

## 7. What the MPQUIC app must keep doing

Our HTTP/3 leg depends on **exactly four things** in the teammates' MPQUIC client. They
can change everything else freely.

| # | Requirement | Where it lives in their source |
|---|---|---|
| 1 | HTTP/3 intake on **UDP 47443**, loopback | `mpquic/android/client/src/main/res/layout/activity_main.xml:234` |
| 2 | ALPN **`h3`** | `mpquic/mpquic-jni/src/config.rs` → `default_alpn()` |
| 3 | `answer_mode = "forward"` | `mpquic/mpquic-jni/src/config.rs`, `forward.rs` |
| 4 | `forward_url` pointing at Ollama | their app config |

**#3 is the load-bearing one.** `forward.rs` POSTs our request body **verbatim** and
returns the backend's response **verbatim, unexamined**. That means our OpenAI JSON and
our parsing never touch their code — they can rewrite the tunnel internals entirely and
we stay working. If `answer_mode` ever moves off `forward`, our `TquicAnswerProvider`
breaks. Tell them explicitly: *keep forward mode on our route.*

**We do not care about the path.** We send `/infer`, but forward mode ignores it and
POSTs to `forward_url` regardless. Not a coupling point.

If they do change one of the four, each is a one-line fix on our side:

- port → `KwikH3Transport.kt:133`
- ALPN → `KwikH3Transport.kt:135`
- a real TLS cert → nothing, we already accept anything

### Re-setup after any MPQUIC rebuild

Package: **`com.mpquic.client`**

```bash
adb shell dumpsys deviceidle whitelist +com.mpquic.client
adb shell cmd appops set com.mpquic.client RUN_ANY_IN_BACKGROUND allow
```

Both are idempotent. **An uninstall clears them.** Without them Samsung's background
restriction kills the tunnel's socket and you get
`socket send_to(): PermissionDenied` — a failure that cost real debugging time.

Then, by hand in their app:

1. **Server address** — `54.190.37.190:10000`
2. **Server address, alt family** — `[2600:1f14:2054:7dfa:cd55:3b81:b63a:3bd6]:10000`.
   Optional: leave it blank and the tunnel runs single-path over Wi-Fi, which is a
   perfectly good demo. Fill it in and the tunnel also uses cellular (see below).
3. **Connect**
4. **Start HTTP/3 RX**, port `47443`
5. Do not touch it again

As of 2026-08-07 the app **remembers 1, 2 and 4** across restarts and `install -r`, so
in practice this is now Connect → Start HTTP/3 RX. Only a full uninstall clears them and
brings back the emulator default `10.0.2.2:4433`.

### Multipath (Wi-Fi + cellular), and why it is now safe to leave on

Filling in the alt address makes the tunnel run over **both** wlan0 and rmnet0 at once.
It needs the alt address because the two interfaces are usually different address
families — cellular is commonly IPv6-only in practice (its IPv4 is a CGNAT/464xlat
address that cannot source traffic), so one remote address cannot serve both paths.

**This used to be actively dangerous for us and no longer is.** Android keeps cellular's
addresses visible but does not authorize a socket to route over cellular while Wi-Fi is
the default network, so the first send on the rmnet path failed `ENETUNREACH` — and that
error killed the **whole engine**, taking the healthy Wi-Fi path *and the 47443 listener
we depend on* down with it. Our leg would have gone from "slower" to "gone". Fixed
2026-08-07: the app now routes that one socket through `Network.bindSocket()` before the
engine uses it. Verified on an SM-A356U with both paths carrying a real transfer
concurrently, `lost=0` on each.

**None of this changes the four requirements above** — it is all below the loopback
HTTP/3 hop, so `KwikH3Transport` and `TquicAnswerProvider` are untouched by it. If the
alt address is wrong or cellular is unavailable, the tunnel simply runs single-path;
it does not fail.

### Verifying their side alone

```bash
python mpquic/tools/h3_sender.py 127.0.0.1 request.json -p 47443 --path /infer
```

Their own tool, same protocol we speak. If that returns a completion, our app will work.
If it doesn't, the problem is on their side — saves debugging across two apps.

---

## 8. The allergy verdict system

### The silent system prompt

`PromptShaping.buildPrompt()` wraps the user's spoken question with instructions the
user never sees:

- answer in **about five sentences**
- **no markdown**
- state a verdict about the user's specific allergies
- say what the verdict is **based on** — never promise safety outright

That last point was a deliberate revision: the model should say what it saw, not make a
medical guarantee.

### Parsing it back

`parseVerdict()` returns `Verdict` / `VerdictResult`. Two regexes:

- `VERDICT_TOKEN` — removes the **token** from anywhere in the text
- `VERDICT_PROSE` — reads the verdict but **never deletes** the prose

**Why token-not-line:** the model appends `VERDICT: SAFE` to the *end of the last prose
sentence*, not on its own line. An earlier implementation removed the whole line and
therefore deleted the answer, and separately left the token visible on screen with an
amber badge on a SAFE result. Removing the substring, not the line, is the fix.

### The profile

`AllergyProfile.kt` — `parseAllergyProfile`, `AllergyProfile.load(context)`.

Ships as an asset, overridable at runtime by pushing a file into the app's files dir:

```json
{ "allergies": ["peanuts", "tree nuts", "shellfish"] }
```

### Verdict display

| Verdict | Phone pill | Glasses text |
|---|---|---|
| SAFE | green | white (`0xFFFFFFFF`) |
| UNSAFE | red | red (`0xFFFF5252`) |
| unknown | amber, **"CHECK BEFORE EATING"** | amber (`0xFFFFC107`) |

**"CHECK BEFORE EATING", not "CHECK THE LABEL".** A user correction: a plated dish has
no label to check. Worth preserving — it is the kind of detail that gets re-broken.

The verdict travels over the wire as a new `verdict: String?` field on both
`CaptureAnswer` (phone) and `CaptureResponse` (glasses).

---

## 9. Phone UI

Rebranded to **ARFood**. Rebuilt over many small iterations.

### Structure (`res/layout/activity_main.xml`)

Toolbar → status card → capture card → empty state → history `ScrollView`.

### The capture history

Designed in `docs/superpowers/specs/2026-08-06-capture-history-design.md`.

- Accordion: **one row expanded at a time**, **newest at the top**, newest auto-expanded
- Each row: thumbnail, query, verdict pill, chevron, timestamp
- Expanded row adds the full image, status/cancel, and the answer
- Tap the image → full screen; tap again to dismiss

**The memory decision.** A decoded 640×480 capture is ~1.2 MB. Each row therefore holds
only a **file path plus a ~120 KB thumbnail**; the full bitmap is decoded on expand and
recycled on collapse, so **exactly one full-size bitmap exists at any moment**. Twenty
rows cost ~2.4 MB instead of 24 MB.

**No RecyclerView, deliberately.** A `LinearLayout` in a `ScrollView`. RecyclerView
exists for thousands of rows; a demo produces tens. It isn't currently a dependency and
adding one plus an adapter to avoid a harmless allocation is the wrong trade.

**Session-only.** History resets on app restart. Images and queries are on disk but
**answers are not**, so a restored list would show rows with no answers — worse than
showing nothing.

### Other UI decisions

- **Compute path** shown **with the answer**, not while waiting — it isn't known until
  the route resolves
- **Frame rate** field hidden unless a stream is actually running
- **Resolution** filled from the captured image's real size
- **RTMP URL field and cloud streaming button removed**
- Group creation moved behind a **burger/overflow menu** — it's rarely used
- **Cancel button**, so you don't wait out a 2-minute timeout
- Status lines merged into one
- Copy fix: "open the menu to **start Wi-Fi Direct**"

---

## 10. Glasses app (`VideoShowCase/glass`)

### What changed

- `CaptureResponse.kt` — added `verdict: String?`
- `CaptureActivity.kt` — verdict colours; **one** capture action; the **whole** answer
  spoken and shown; `stopSpeaking()`; `setCaptureLabel()` toggling
  `READY_LABEL` = "Tap to capture and ask" ↔ `STOP_LABEL` = "Tap to stop"
- `Speaker.kt` — added `stop()`, which **drops** `pendingOnDone` rather than posting it
- `MainActivity.kt` — `shownPeerAddresses: List<String>? = null` dedupe guard
- `res/layout/activity_capture.xml` — answer `ScrollView` is `wrap_content` with
  `maxHeight="300dp"`

### One capture action, not two

Image-only capture was removed. With two focus targets, a tap meant different things
depending on which held focus — **and that focus is invisible to someone wearing the
glasses.** One target makes a tap unconditional.

### The layout constraint

Lens is 1280×480 at density 160 → **640×480 per eye**. The answer ScrollView uses
`wrap_content` + `maxHeight`, **not weight**: weight claimed all leftover height even
when empty, stranding the capture button at the bottom of the lens with a gap above it.
Sized to content, the button sits directly under the text and rides up when there is
none. The 300dp cap is a little under the ~350dp left after the status line and button.

### Capture settings (`StillCapture.kt`)

| Constant | Value | Note |
|---|---|---|
| `TARGET_HEIGHT_PX` | 480 | Lowered from 720 to cut transfer time. **If quality is a problem, put this back to 720** and accept slower transfer/model time |
| `JPEG_QUALITY` | 85 (a `Byte`, not an `Int`) | |
| `FALLBACK_SIZE` | 1280×720 | |
| `CAPTURE_TIMEOUT_MS` | 8 000 | |
| `JPEG_ORIENTATION_DEGREES` | 90 | Why the phone must apply EXIF rotation |
| `RESPONSE_TIMEOUT_MS` | 135 000 | In `CaptureActivity` |
| `SPEAKING_TIMEOUT_MS` | 60 000 | |

### The temple button — settled, do not retry

The RayNeo X3's right-temple shortcut button maps to **keycode 289**. It **cannot be
bound from an app**: the system consumes it in
`PhoneWindowManager.interceptKeyBeforeQueueing`, above the app layer, and it opens
RayNeo's voice recorder. This was investigated thoroughly and proven. The binding
attempt was written and then **reverted** (`19d6a37` then `7c894ba`) at the user's
request: *"lets not do that change for now."*

**Do not propose this again without new information.** The commit history preserves both
the attempt and the finding.

### MercurySDK

RayNeo's SDK, used by the glasses module: `TempleAction`, `FocusHolder`, `FocusInfo`,
`BaseMirrorActivity`, `FixPosFocusTracker`.

---

## 11. Debugging playbook — real bugs and their fixes

Preserved because several are non-obvious and easy to reintroduce.

| Symptom | Cause | Fix |
|---|---|---|
| `TquicNative` unresolved | The `tquic-jni` binding was never written | Hand-built HTTP/3 on kwik |
| QPACK `BufferTooShort` | `compressHeaders` returns a **write-mode** buffer | `encodedHeaderBlock()` flips it, casting to `java.nio.Buffer` |
| `NoSuchMethodError` on `flip()` | Java 9 covariant return type on `ByteBuffer.flip()` | Cast to `java.nio.Buffer` first |
| `VERDICT: SAFE` visible, amber badge on a safe answer | Model appends the token to the end of the prose line | Remove the **token**, not the line |
| **Glasses discovery screen broke** | Dedupe guard compared against `emptyList()`, but the **first** call is `rebuildFocusHolder(emptyList())`, so it returned early and no focus system was ever built | Initial value `null`, not `emptyList()` (`c977525`) |
| Black `SurfaceView` bleeding through the capture card (twice) | Transparent panel, then unclipped rounded corners | Opaque panel background + `clipToOutline="true"` |
| Nav bar overlaying content | targetSdk 36 forces edge-to-edge | `ViewCompat.setOnApplyWindowInsetsListener` in `applyWindowInsets()` |
| Toolbar title invisible | `?attr/colorPrimary` resolves pale under `Theme.AppCompat.Light` | Literal `#2E7D32` |
| MPQUIC engine dying, `send_to(): PermissionDenied` | Samsung background restriction | `deviceidle whitelist` + `appops RUN_ANY_IN_BACKGROUND` |
| DevMon `/health` behaving like an old build | Installed APK was **2 hours older** than the merged `/health` endpoint | Rebuild/reinstall DevMon |
| Discovery highlight duplicated | Duplicate P2P broadcast arriving **21 ms** apart | Dedupe guard (above) |

### A methodology mistake worth not repeating

When extracting tap coordinates from `uiautomator` dumps, an early script grabbed *all
digits on a line*, so taps landed in the wrong place and a cancel test produced a **false
negative**. Extract the `bounds="..."` attribute specifically. `uitap.py` (see §14) does
this correctly.

---

## 12. Testing

### Running everything

```bash
cd VideoShowCase
./gradlew :app:assembleDebug :glass:assembleDebug \
          :app:testDebugUnitTest :glass:testDebugUnitTest
```

Last full run at handoff: **BUILD SUCCESSFUL — tests=166, skipped=0, failures=0, errors=0.**

### JVM unit-test gotcha — important

In plain JVM unit tests, `android.util.Log`, `android.util.Base64`, and `org.json` are
**stubbed and throw `"Stub!"`**.

This is why `TquicAnswerProvider.buildChatCompletionRequest` uses **`java.util.Base64`**,
not Android's. Anything testable must avoid the stubbed APIs.

### Test files

**Phone** (`app/src/test/.../capture/`): `AllergyProfileTest`, `AnswerAttemptTest`,
`CaptureAnswerTest`, `CaptureImageTest`, `DevmonAnswerProviderTest`,
`EchoAnswerProviderTest`, `FailoverAnswerProviderTest`, `H3FramingTest`,
`KoogAnswerProviderTest`, `OpenAiResponseTest`, `PromptShapingTest`, `QpackProbeTest`,
`RetriableStatusTest`, `TquicAnswerProviderTest`.

**Glasses** (`glass/src/test/.../`): `CaptureResponseTest`, `FrameReaderTest`,
`PhoneResponseFrameContractTest`, `ExampleUnitTest`.

### Verified on hardware

- Real glasses capture through the TQUIC tunnel end to end: **18.7 s**
- The full capture → answer → speak pipeline, many times
- **The `UNSAFE` → red path** — confirmed working by the user on 2026-08-06
- **A full dry run on the current builds** — confirmed working by the user on 2026-08-06

Both of the last two had been open caveats for most of the session. **They are now
closed.** Do not re-raise them.

---

## 13. Demo runbook

Kept manual on purpose.

**Before anyone is watching:**

1. Phone and glasses charged, both on.
2. Confirm the MPQUIC background exemptions are still applied (§7). Re-run them if the
   app was reinstalled.

**The order that matters:**

1. **Bring the MPQUIC app up first.** Its address fields are remembered now, so this is
   usually just **Connect**, then **Start HTTP/3 RX** on 47443 — check the addresses
   match §7 first. **Then leave it alone** — do not switch back to it.
   To show multipath, confirm the stats block lists **two** paths (wlan0 and rmnet0)
   before moving on; one path means the alt address is missing or cellular is down,
   which still demos fine, just single-path.
2. Open **ARFood** on the phone. Overflow menu → start Wi-Fi Direct group.
3. On the glasses, discover and connect to the phone.
4. Tap to capture, speak the question.
5. Answer appears on the lens, colour-coded, and is read aloud. Tap to stop the speech.
6. The phone shows the same capture in its history with the verdict pill and which
   backend served it.

**If DevMon is up**, captures route to it. **If it isn't**, they silently fall over to the
HTTP/3 route — which is the point of the whole failover feature and worth narrating.

---

## 14. Scratchpad tools

Built during the session, outside the repo, in the session scratchpad:

- **`fake_glasses.py`** — a VSCQ stand-in for the glasses. Sends a real capture frame
  sequence to the phone over TCP 8889. Lets the phone side be tested with no glasses
  present. Most UI work after the last real pairing was verified against this.
- **`uitap.py`** — taps a phone UI element by `resource-id`, via `uiautomator` dump.
  Extracts the `bounds="..."` attribute specifically (see §11).

These are **not in the repo**. Recreate them if needed; both are short.

---

## 15. Design docs in the repo

Under `docs/superpowers/`, all committed:

**Specs** (`specs/`):
- `2026-08-06-devmon-tquic-failover` (dated in commit `c02a8d32`)
- `2026-08-06-phone-capture-panel-design.md`
- `2026-08-06-allergy-verdict-design.md`
- `2026-08-06-compute-path-indicator` design
- `2026-08-06-capture-history-design.md`

**Plans** (`plans/`):
- failover implementation (7 tasks)
- phone capture panel (4 tasks)
- allergy verdict (6 tasks)

These were produced with the `superpowers:brainstorming` → `writing-plans` →
`executing-plans` workflow. All tasks are complete.

---

## 16. State at handoff

- Both repos **clean and fully in sync** (`0 0` each direction)
- Parent `9f8e83f0`, submodule `a5fec1c`
- **166 tests passing**, both APKs build
- All 17 tracked implementation tasks **complete**
- Both hardware caveats **closed by user confirmation**
- The RayNeo voice recorder is disabled on the glasses so a stray temple press cannot
  hijack the screen

### Known open items

- A teammate is about to **modify the MPQUIC app.** §7 is the contract to hand them and
  the checklist for afterwards.
- `docs/session_handoff_2026-08-04.md` and `docs/videoshowcase-doc/` remain untracked by
  choice.
