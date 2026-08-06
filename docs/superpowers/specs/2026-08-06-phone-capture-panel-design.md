# Phone capture panel — design

**Date:** 2026-08-06
**Status:** approved, ready for implementation planning
**Repo:** changes land in the `VideoShowCase` submodule (fork `sukoonsarin/VideoShowCase`, branch `hackathon26-arfood`)

## Problem

The phone's largest UI element is a black `FrameLayout` wrapping a `SurfaceView`, built for the live-streaming mode. In capture mode — the mode that actually gets used — nothing ever renders there. It stays black for the whole session.

Meanwhile the only feedback a capture produces on the phone is one line of `tvCapture` text. When an answer is wrong there is no way to tell, from the phone, whether the model misread the scene or the glasses took a bad photo.

This design fills that black box with the capture and its answer.

## Scope

**In scope:** displaying the captured image, the spoken query, and the answer, inside the existing black `FrameLayout`.

**Out of scope:** the glasses (unchanged), the capture protocol (unchanged), the answer providers (unchanged), and live streaming (only hidden, never altered). Nothing here changes what is sent, received, or spoken — this is display only, and a failure in it must never affect an answer reaching the wearer.

## Requirements

1. When a capture arrives, show its image and the spoken query.
2. Keep them visible for the whole wait — the wait is 15–40 s and the screen must not look idle.
3. When the answer arrives, show it **below the image**, with the image still visible.
4. The answer is scrollable and complete, not truncated.
5. Nothing here may cover live video if streaming is in use.

## States

Three states in one panel, all by visibility — no view is created or destroyed:

| State | `tvCaptureQuery` | `ivCaptureImage` | `svCaptureAnswer` |
|---|---|---|---|
| **Idle** | panel `GONE` | panel `GONE` | panel `GONE` |
| **Waiting** | visible | visible, weight 1 | `GONE` |
| **Answered** | visible | visible, weight 1 | visible, weight 1 |

The split between waiting and answered is free: a `GONE` child contributes no weight to a `LinearLayout`, so the image fills the box while the answer is hidden and drops to half when it appears. `showAnswer` sets one visibility flag; no measurement or resize code exists.

The query stays visible in both states, so what was asked is always next to what came back.

## Layout

Added inside the existing black `FrameLayout` in `activity_main.xml`, as a sibling stacked above `surfaceView`:

```
capturePanel        LinearLayout, vertical, visibility=gone
├── tvCaptureQuery  TextView, wrap_content
├── ivCaptureImage  ImageView, height=0dp weight=1, scaleType=fitCenter
└── svCaptureAnswer ScrollView, height=0dp weight=1, visibility=gone
    └── tvCaptureAnswer  TextView
```

`surfaceView` is untouched and keeps its position, so the streaming path is unaffected when the panel is `GONE`.

## Components

**`CapturePanel`** *(new)* — owns the four views and the state transitions:

```kotlin
class CapturePanel(
    private val panel: View,
    private val queryView: TextView,
    private val imageView: ImageView,
    private val answerScroll: View,
    private val answerView: TextView,
) {
    fun showCapture(image: Bitmap?, query: String?)  // -> Waiting
    fun showAnswer(text: String)                     // -> Answered
    fun clear()                                      // -> Idle, releases the bitmap
}
```

**Every method touches views only and must be called on the main thread.** `showCapture` therefore takes an already-decoded `Bitmap`, not a `File`: decoding is I/O plus a rotation and does not belong on the UI thread, and the call sites are already inside `runOnUiThread` guards (see Wiring). Decoding is a separate top-level function run before the hop:

```kotlin
internal fun decodeCaptureImage(file: File): Bitmap?
```

It returns null on a missing or undecodable file rather than throwing — `showCapture` accepts null and leaves the image area blank.

It exists as its own class rather than more methods on `MainActivity`, which is already ~570 lines and carries Wi-Fi Direct, streaming, the capture server, and provider selection. A fifth concern belongs outside it.

**EXIF rotation.** `StillCapture` on the glasses sets `JPEG_ORIENTATION = 90` — an EXIF *tag*; it does not rotate pixels. `BitmapFactory` ignores EXIF, so a plain decode renders every capture sideways. `CapturePanel` reads the tag via `androidx.exifinterface` and rotates on load.

The tag→degrees mapping is a top-level pure function so it can be unit tested without a device:

```kotlin
internal fun exifRotationDegrees(orientation: Int): Int
```

`ORIENTATION_ROTATE_90` → 90, `_180` → 180, `_270` → 270, `ORIENTATION_NORMAL`/`UNDEFINED`/anything unrecognised → 0. Unrecognised must return 0 rather than throwing: a capture that displays upright-but-wrong is far better than one that crashes the relay.

## Wiring

Two existing call sites in `MainActivity`, no new plumbing:

| Site | Call |
|---|---|
| `server.onCaptureReceived` | decode first, then `showCapture(bitmap, queryText)` |
| `respondTo`, after `provider.answer(...)` | `showAnswer(answer.speak)` |
| `startStreamReceiver`, on success | `clear()` |

`answer.speak` rather than `answer.display`: the phone has room to show the whole thing, and reading the full answer is the point of putting it here at all.

`onCaptureReceived`'s lambda body already runs on a `CaptureServer` IO thread and posts its UI work with `runOnUiThread`. `decodeCaptureImage` is called in the lambda body — on that IO thread, before the hop — and only the finished bitmap crosses to the main thread:

```kotlin
server.onCaptureReceived = { captureId, dir, queryText ->
    val bitmap = decodeCaptureImage(File(dir, "image.jpg"))   // IO thread
    runOnUiThread {
        if (!isDestroyedOrFinishing()) {
            capturePanel.showCapture(bitmap, queryText)
            // ...existing tvCapture update...
        }
    }
    answerScope.launch { respondTo(server, captureId, dir, queryText) }
}
```

All three panel calls sit inside the existing `runOnUiThread { if (!isDestroyedOrFinishing()) ... }` guards — a callback landing after the activity dies must not touch the views.

## Error handling

| Case | Behavior |
|---|---|
| Timeout, unreachable backend, model error | Renders like any other answer — those paths already return a `CaptureAnswer`, so the failure text simply appears |
| Image-only capture (no speech) | Query header reads "no question" |
| Image file missing or undecodable | Panel still shows the query; image area stays blank. Never throws — display must not break the answer path |
| Second capture during a wait | Panel resets to Waiting with the new image; the late answer for the previous capture is already discarded upstream by `captureId` |
| Live streaming starts | `clear()` — the panel can never cover video |
| Activity destroyed mid-capture | Existing `isDestroyedOrFinishing()` guards apply unchanged |

The bitmap is released when replaced or cleared, so a long session of captures does not accumulate ~1.2 MB each.

## Testing

**Unit (no device):** `exifRotationDegrees` — each of the four recognised orientations, plus undefined and an unrecognised value both mapping to 0.

The rest is view manipulation with no logic worth mocking; it is verified on device.

**On device:**

1. Capture with a spoken query → image and query appear, image fills the box
2. Answer arrives → answer appears below, image shrinks to half, both readable
3. Long answer → scrolls, nothing truncated
4. Image is upright, not sideways (the EXIF case)
5. Image-only capture → header reads "no question"
6. Backend down → error text renders in the answer area
7. Second capture → panel resets, no stale answer
8. Regression: live streaming still renders, panel does not cover it

## Open items

- With the answer in half the box, a ~1000-character response renders small and needs scrolling. That is the accepted cost of keeping the photo visible, which is the reason this layout was chosen over replacing the image.
