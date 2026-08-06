# Compute path indicator — design

**Date:** 2026-08-06
**Status:** approved, ready for implementation planning
**Repo:** changes land in the `VideoShowCase` submodule (fork `sukoonsarin/VideoShowCase`, branch `hackathon26-arfood`)

## Problem

The phone chooses between two backends per capture — DevMon on the LAN, or the TQUIC tunnel to EC2 — and nothing on screen says which. The routing decision is visible only in logcat.

That hides the most interesting behaviour in the system. A failover that nobody can see is indistinguishable from no failover at all.

## Scope

**In scope:** one status line on the phone showing the chosen backend.

**Out of scope:** the routing logic itself, the glasses, and the answer. This is display only; a failure here must not affect an answer reaching the wearer.

## Requirements

1. The line names which backend served the capture, in plain language.
2. It updates **when the decision is made**, not when the answer returns — the wait is 15–25 s and the information is wanted during it.
3. It updates again if a retriable failure moves the request to the other backend mid-flight.
4. A backend pinned at launch (`answer_provider=DEVMON`) shows its label fixed.

## Layout

One `TextView` added to `activity_main.xml` directly after `tvFrameRate`, matching its 12sp style:

```
Compute: —
```

## Where the label lives

`HealthCheckedProvider` gains:

```kotlin
val computeLabel: String
```

| Provider | Label |
|---|---|
| `DevmonAnswerProvider` | `Local compute (DevMon → PC)` |
| `TquicAnswerProvider` | `Remote compute (TQUIC → EC2)` |

On the provider rather than a `when` in `MainActivity`: a third backend added later cannot leave a stale branch mapping it to the wrong path. The provider is the only thing that knows what it is.

## How it updates

`FailoverAnswerProvider` gains:

```kotlin
private val onPathChosen: (String) -> Unit = {},
```

Called with the chosen provider's `computeLabel` at each of the three points the router commits to a backend:

| Router decision | Reports |
|---|---|
| Primary healthy → primary | DevMon's label |
| Primary unhealthy → fallback | TQUIC's label |
| Primary failed retriably → fallback | TQUIC's label |

Called **before** delegating, so the line is populated for the whole wait. Requirement 3 follows from the third row: the line changes from Local to Remote as the failover happens, which is what makes it read as live rather than decorative.

`MainActivity` sets `binding.tvComputePath.text = "Compute: $label"` inside the existing `runOnUiThread { if (!isDestroyedOrFinishing()) ... }` guard that already wraps the routing log.

For a pinned provider, `MainActivity` reads `computeLabel` once at launch and sets the line fixed. `EchoAnswerProvider` and `KoogAnswerProvider` are not `HealthCheckedProvider`s and leave it at `—`.

## Error handling

| Case | Behavior |
|---|---|
| Backend fails after being chosen | Line keeps showing the backend that was tried — it is a record of the route, not of success |
| Activity destroyed mid-capture | Existing `isDestroyedOrFinishing()` guard applies |
| `ECHO` / `KOOG` selected | Line stays `—` |

## Testing

**Unit:** `FailoverAnswerProvider` already has a fake-provider test harness. Add: healthy primary reports the primary's label; unhealthy primary reports the fallback's; a retriable failure reports the primary's label and then the fallback's, in that order.

**On device:** capture with DevMon healthy → line reads `Local compute`; force-stop DevMon and capture → line reads `Remote compute`, and it appears while the spinner is still going, not after the answer.

## Open items

None. The feature is one text line and one callback.

## Device verification — 2026-08-06

Line renders as `Compute: Remote compute (TQUIC → EC2)` under Frame rate.

Confirmed by a screenshot taken **five seconds into a capture**, with the answer not yet returned —
the panel still showed only the photo and the question. Requirement 2 holds: the line is populated
during the wait, not with the answer.

Unit-covered: healthy primary reports the primary's label; unhealthy reports the fallback's; a
retriable failure reports both in order, which is the case that makes a live failover visible.
