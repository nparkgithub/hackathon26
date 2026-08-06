# Allergy verdict and response shaping — design

**Date:** 2026-08-06
**Status:** approved, ready for implementation planning
**Repo:** changes land in the `VideoShowCase` submodule (fork `sukoonsarin/VideoShowCase`, branch `hackathon26-arfood`)

## Problem

Three faults in what the wearer currently hears, all with the same root cause: the model is asked a bare question and answers however it likes.

1. **Answers are far too long.** A real capture on 2026-08-06 took **57.7 s to speak** against the glasses' 60 s playback watchdog — it completed with 2.3 s to spare. A slightly longer answer gets cut off mid-sentence.
2. **Answers contain markdown.** `**office workspace**` is spoken as asterisks and rendered as literal characters on the phone panel.
3. **The allergy question is never actually answered.** The whole point of the product is "can I eat this", and nothing in the request asks for that.

This design shapes the request so the answer is short, plain, and ends with an allergy verdict the app can act on.

## Scope

**In scope:** what gets sent with the query, how the answer is parsed, an on-device allergy profile, and colouring the answer on both phone and glasses.

**Out of scope:** the capture pipeline, the failover router, the transports, and the model itself. Nothing here changes *how* a request travels — only what it contains and how the reply is read.

## Requirements

1. Every query carries the same instructions, on **both** backend legs.
2. Responses are at most 5 sentences, plain text, no markdown characters.
3. The response ends with a statement of whether the food is safe for this user.
4. The app derives a machine-readable verdict without parsing prose.
5. The verdict never reaches the wearer's ears as a raw token.
6. An unsafe verdict is visibly red on the phone and the glasses.
7. A missing or unparseable verdict is **never** treated as safe.

## Why the model decides, not the device

Considered and rejected: keeping the allergy list on-device and scanning the returned text for it.

Keyword matching fails in both directions. It misses derivatives and synonyms a user would expect to be caught — *casein* and *whey* for milk, *groundnut* for peanut, *semolina* for wheat. And it false-alarms on negation: "contains **no peanuts**" contains "peanut", so the safest possible label would turn the screen red. A warning that fires on safe food trains the wearer to ignore warnings, which is worse than not having one.

The model handles synonyms and negation as a matter of course. What it cannot be trusted to do is answer in a form the app can branch on — hence the token.

## The prompt

One shared builder, used by both legs:

```kotlin
internal fun buildPrompt(userQuery: String, allergies: List<String>): String
```

With allergies configured, it produces:

```
Answer in at most 5 sentences of plain text.
Do not use markdown, asterisks, bullet points, or any formatting characters.
The user is allergic to: peanuts, tree nuts, shellfish.
Your final sentence must state whether this food is safe for the user.
Then output exactly one final line, nothing after it:
VERDICT: SAFE  or  VERDICT: UNSAFE  or  VERDICT: UNKNOWN
Use UNKNOWN if you cannot identify the food or read its ingredients.

Question: <userQuery>
```

With an empty allergy list, the three allergy lines and the `VERDICT` instruction are omitted entirely — asking for a verdict against no allergies would invite a meaningless `SAFE`.

**It goes in the query text, not an OpenAI `system` message.** DevMon builds its own OpenAI call from the `query` form field, so the query string is the only channel both legs share. Using a proper system role on the TQUIC leg and prepended text on the DevMon leg would mean two prompts to keep in sync and two sets of behaviour to debug.

## Parsing the verdict

```kotlin
enum class Verdict { SAFE, UNSAFE, UNKNOWN }

data class VerdictResult(val verdict: Verdict, val text: String)

internal fun parseVerdict(raw: String): VerdictResult
```

Removes **every** line whose first non-space characters are `VERDICT:` (case-insensitive), wherever it appears, and takes the verdict from the last such line. The stripped text is what becomes `speak` and `display`, so requirement 5 holds by construction — the wearer cannot hear the token because it is gone before `CaptureAnswer` is built.

Scanning all lines rather than only the last one is deliberate. A model that adds a trailing pleasantry after the token would otherwise leave the raw `VERDICT: UNSAFE` sitting in the middle of the spoken text, which is exactly the failure requirement 5 exists to prevent.

**Anything unrecognised returns `UNKNOWN`.** A model that ignored the instruction, a truncated response, or an error string must not produce `SAFE`. This is the single most important line in the feature: the failure mode of a food-allergy assistant is telling someone a food is fine when it does not know.

**`UNKNOWN` is not the same as "no verdict".** When the allergy profile is empty the prompt never asks for a verdict, so there is nothing to be unknown about — the provider sets `CaptureAnswer.verdict = null` and skips colouring entirely. `parseVerdict` still strips any stray token in that case; only its verdict is discarded. Without this distinction an empty profile would paint every answer amber.

## Allergy profile

```json
{ "allergies": ["peanuts", "tree nuts", "shellfish"] }
```

Loaded once at startup. A default ships in `assets/allergy_profile.json`; if `<app files dir>/allergy_profile.json` exists it wins, so the list can be changed with `adb push` between demo runs without a rebuild.

No settings UI. A profile editor is a real feature with real design questions and it is not what makes the demo work — the file is enough to show the behaviour.

Missing file, malformed JSON, or an empty array all resolve to an empty list, logged, which disables the allergy half of the prompt and all colouring. A broken profile costs the verdict, never the answer.

## Protocol change

`CaptureAnswer` gains `verdict: String?`, serialised into the existing `0x14` payload:

```json
{ "captureId": "...", "speak": "...", "display": "...", "verdict": "UNSAFE" }
```

The glasses' `CaptureResponse` gains the same optional field. This is additive and backward compatible in one direction only: an old glasses build ignores it (unknown fields were already specified as ignored), but colouring requires a glasses rebuild. Both are rebuilt together here.

`verdict` is carried as a `String?` rather than the enum so the wire format stays a plain JSON string and an unrecognised value degrades to `UNKNOWN` on the far side rather than failing to parse.

## Colour coding

| Verdict | Phone `tvCaptureAnswer` | Glasses `tvStatus` |
|---|---|---|
| `UNSAFE` | red | red |
| `UNKNOWN` | amber | amber |
| `SAFE` | default white | default white |
| absent (no profile) | default white | default white |

`UNKNOWN` is amber rather than plain deliberately: "I could not tell" must not look identical to "this is fine". The distinction only matters when it matters most — a label the model could not read.

Colour is set on the existing single `TextView` on each side. No layout changes.

## Components

| Component | Kind | Responsibility |
|---|---|---|
| `buildPrompt` | pure fn | Assemble instructions + allergies + query |
| `parseVerdict` | pure fn | Extract the verdict, strip the token |
| `AllergyProfile.load(context)` | loader | Read JSON, never throw |
| `CaptureAnswer.verdict` | field | Carry the verdict to the glasses |
| `CapturePanel.showAnswer(text, verdict)` | UI | Colour the phone text |
| `CaptureActivity` render | UI | Colour the glasses text |

The two pure functions carry all the logic worth testing and neither touches Android, so both run on the JVM.

## Error handling

| Case | Behavior |
|---|---|
| No allergy profile | Prompt omits allergy lines; no verdict requested; no colouring |
| Malformed profile JSON | Treated as empty, logged |
| Model ignores the format | No token found → `UNKNOWN` → amber |
| Model returns a bogus verdict word | → `UNKNOWN` → amber |
| Backend error / timeout | Existing error `CaptureAnswer` flows through with no verdict → no colouring |
| Old glasses build | Ignores `verdict`, shows the answer uncoloured — degraded, not broken |

## Testing

**Unit (no device):**

- `buildPrompt` — with allergies, without allergies, allergy names joined correctly, user query appended last, empty query
- `parseVerdict` — each of `SAFE` / `UNSAFE` / `UNKNOWN`; lowercase; extra whitespace; token absent; token in the middle rather than the end; empty string; token-only response. Assert in every case that the returned text no longer contains `VERDICT:`
- `AllergyProfile` parsing — valid, missing key, empty array, malformed JSON, non-string entries

**On device:**

1. Capture a food item with a known allergen → response ≤ 5 sentences, no asterisks, ends with a safety statement, text is red
2. Capture a safe food → text is white
3. Capture a non-food → amber, and the wearer does not hear the word "verdict"
4. Empty allergy profile → answer still works, no colouring
5. Both legs — force the DevMon path and the TQUIC path, confirm identical shaping
6. Regression: the answer still speaks and the panel still shows the image

## Risks

- **The allergy list leaves the device** with every query, to a LAN PC or an EC2 box. Acceptable for a hackathon and stated here so it is a decision rather than an oversight. A real product keeps health data local, which would mean on-device inference or an ingredient list the phone matches itself.
- **A 4–8B vision model is making a safety call.** The token makes the verdict *legible*, not *correct*. The spoken sentence should stay hedged — "based on what I can read on the label" — rather than absolute, and the demo should not claim more than that.
- **5 sentences is a request, not a guarantee.** If answers still overrun the glasses' 60 s playback watchdog, the watchdog is the backstop and the number comes down.
