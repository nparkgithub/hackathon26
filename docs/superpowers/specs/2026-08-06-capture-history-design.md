# Capture history — design

**Date:** 2026-08-06
**Status:** approved, ready for implementation
**Repo:** changes land in the `VideoShowCase` submodule (fork `sukoonsarin/VideoShowCase`, branch `hackathon26-arfood`)

## Problem

The phone shows exactly one capture: the current one. Each new capture destroys the last, so there is no way to compare two answers, revisit what was asked a minute ago, or show a sequence of results to anyone watching.

The captures themselves are not lost — every one is written to disk as `image.jpg` (plus `query.txt` when a question was asked). Only the screen forgets them.

## Scope

**In scope:** a scrolling list of this session's captures on the phone, each collapsible to a row and expandable to its full image and answer.

**Out of scope:** the glasses, the capture protocol, the answer providers, and persistence across app restarts.

## Requirements

1. Each capture is a row: thumbnail, query, verdict, and an expand affordance.
2. Tapping a row expands it to the full image and answer.
3. **One row expanded at a time.** Opening one closes the others.
4. **Newest at the top**, and expanded on arrival — a fresh capture behaves as it does today.
5. The in-flight capture shows its spinner and Cancel in its own row.
6. Memory must not grow with the number of captures held.

## Structure

```
┌─────────────────────────────────────┐
│ [img]  "Can I eat this"          ▲  │  expanded (newest)
│        SAFE FOR YOU                 │
│        ┌───────────────────────┐    │
│        │      full photo       │    │
│        └───────────────────────┘    │
│        The ingredients list...      │
├─────────────────────────────────────┤
│ [img]  "What are the ingredients" ▼ │  collapsed
│        COULDN'T TELL                │
└─────────────────────────────────────┘
```

Accordion rather than free expansion: with several open you scroll past full-size photos to reach the next row, and the list stops working as a list. Newest auto-expands so nothing about the single-capture experience changes.

## Memory

The load-bearing decision. A decoded 640×480 capture is ~1.2 MB; twenty of them is 24 MB held live in an app that also runs a capture server and a QUIC stack.

Each record therefore holds **the file path and a thumbnail**, not a full bitmap:

| | Held | Cost |
|---|---|---|
| Every row | thumbnail, decoded with `inSampleSize` | ~120 KB |
| Expanded row only | full-size bitmap | ~1.2 MB |

The full bitmap is decoded when a row expands and recycled when it collapses, so **exactly one full-size bitmap exists at any moment** — the same as today. Twenty rows cost ~2.4 MB of thumbnails rather than 24 MB.

## Session-only

History resets when the app restarts. Images and queries are on disk but **answers are not**, so a restored list would show rows with no answers — worse than showing nothing. Persisting answers is a separate change and is not what makes the demo work.

## No RecyclerView

A `LinearLayout` inside a `ScrollView`, one child view per record. RecyclerView exists to recycle views across thousands of rows; a demo session produces tens. It is not currently a dependency, and adding one plus an adapter to avoid an allocation that does not hurt is the wrong trade.

## Components

**`CaptureRecord`** *(new)* — one capture: `captureId`, `imageFile`, `query`, and the mutable `answer`, `verdict`, `cancelled` filled in when the reply lands.

**`CaptureHistoryPanel`** *(replaces `CapturePanel`)* — owns the list container and the empty state:

```kotlin
fun addCapture(captureId: String, imageFile: File, query: String?)
fun setAnswer(captureId: String, text: String, verdict: String?)
fun setCancelled(captureId: String)
fun clear()
fun dismissFullScreen()
```

It keeps records newest-first, inflates a row per record, and tracks which row is expanded. The states it renders per row are the ones `CapturePanel` already had — waiting, answered, cancelled — just repeated per row instead of once.

**`item_capture.xml`** *(new)* — the row: a header (thumbnail, query, verdict pill, chevron) always visible, and a body (full image, status/cancel, answer) shown only when expanded.

**`captureThumbnail(file, maxPx)`** *(new, in `CaptureImage.kt`)* — decodes a downsampled, EXIF-rotated thumbnail. Sits beside `decodeCaptureImage` because it shares the rotation logic that already exists there.

## Error handling

| Case | Behavior |
|---|---|
| Image missing or undecodable | Row still shows query and answer; image area blank. Never throws — display must not break the answer path |
| Answer for an unknown id | Ignored; a capture that was cleared cannot be filled in |
| Cancelled capture | Row shows "Request cancelled" where the answer would be |
| Second capture while one is in flight | New row on top and expanded; the older row collapses with whatever it has |
| Live streaming starts | `clear()` — list and empty state both hidden so the video is not covered |
| Activity destroyed | Full-screen dialog dismissed; bitmaps released |

## Testing

**Unit:** `captureThumbnail` returns a bitmap no larger than the requested bound, and null for a missing file. The row-state logic is view manipulation with nothing to mock and is verified on device.

**On device:**

1. Capture → row appears at the top, expanded, with the spinner
2. Answer arrives → same row fills in with photo, verdict pill, answer
3. Second capture → new row on top, expanded; the first collapses to thumbnail + query + verdict
4. Tap a collapsed row → it expands and the other collapses
5. Tap the expanded photo → full screen, tap to dismiss
6. Cancel on the in-flight row → that row shows cancelled, others untouched
7. Ten captures → scrolls, and memory does not climb by ~1.2 MB per capture

## Open items

- With one row expanded the photo is roughly half the card, as it is today. The full-screen tap remains how a label is actually read.
