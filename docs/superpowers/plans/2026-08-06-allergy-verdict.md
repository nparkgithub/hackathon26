# Allergy Verdict Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Shape every answer to 5 plain-text sentences ending in an allergy verdict, and colour the answer red when the food is unsafe.

**Architecture:** Two pure functions do all the work — `buildPrompt` prepends instructions to the query, `parseVerdict` pulls the `VERDICT:` token back out and strips it. Both providers call them, so the two backend legs behave identically. The verdict rides to the glasses as a new optional field on the existing `0x14` payload.

**Tech Stack:** Kotlin, `org.json`, JUnit 4.

## Global Constraints

- Modules: `VideoShowCase/app` (most of it) and `VideoShowCase/glass` (verdict field + colouring).
- **No `android.util.Log` in unit-tested classes** — the android.jar stub throws `RuntimeException("Stub!")`.
- **`UNKNOWN` ≠ no verdict.** `UNKNOWN` means the model could not tell; `null` means we never asked because the allergy profile is empty. Only `null` disables colouring.
- **Nothing unrecognised may ever resolve to `SAFE`.**
- The verdict token must never survive into `speak` or `display`.
- Build: `cd VideoShowCase && ./gradlew :app:assembleDebug :glass:assembleDebug` and `:app:testDebugUnitTest`.
- `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`.
- Commit after every task. Do **not** push.

---

### Task 1: Prompt building and verdict parsing

**Files:**
- Create: `app/src/main/java/com/example/video/show/demo/capture/PromptShaping.kt`
- Test: `app/src/test/java/com/example/video/show/demo/capture/PromptShapingTest.kt`

**Interfaces:**
- Produces: `enum class Verdict { SAFE, UNSAFE, UNKNOWN }`, `data class VerdictResult(val verdict: Verdict, val text: String)`, `internal fun buildPrompt(userQuery: String, allergies: List<String>): String`, `internal fun parseVerdict(raw: String): VerdictResult`. Tasks 3 and 4 consume all four.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.video.show.demo.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptShapingTest {

    private val allergies = listOf("peanuts", "tree nuts", "shellfish")

    // --- buildPrompt ---

    @Test
    fun `prompt carries the user question last`() {
        val prompt = buildPrompt("What are the ingredients", allergies)
        assertTrue(prompt.trimEnd().endsWith("Question: What are the ingredients"))
    }

    @Test
    fun `prompt lists every allergy`() {
        val prompt = buildPrompt("q", allergies)
        assertTrue(prompt.contains("peanuts, tree nuts, shellfish"))
    }

    @Test
    fun `prompt always constrains length and formatting`() {
        val prompt = buildPrompt("q", allergies)
        assertTrue(prompt.contains("at most 5 sentences"))
        assertTrue(prompt.contains("Do not use markdown"))
    }

    @Test
    fun `prompt tells the model to say what it observed rather than promise safety`() {
        val prompt = buildPrompt("q", allergies)
        assertTrue(prompt.contains("Do not promise the food is safe"))
    }

    @Test
    fun `with no allergies the verdict is never requested`() {
        // Asking for a verdict against an empty allergy list invites a meaningless SAFE.
        val prompt = buildPrompt("q", emptyList())
        assertFalse(prompt.contains("VERDICT"))
        assertFalse(prompt.contains("allergic"))
        assertTrue(prompt.contains("at most 5 sentences"))
        assertTrue(prompt.trimEnd().endsWith("Question: q"))
    }

    // --- parseVerdict ---

    @Test
    fun `reads each verdict value`() {
        assertEquals(Verdict.SAFE, parseVerdict("Fine.\nVERDICT: SAFE").verdict)
        assertEquals(Verdict.UNSAFE, parseVerdict("Careful.\nVERDICT: UNSAFE").verdict)
        assertEquals(Verdict.UNKNOWN, parseVerdict("No idea.\nVERDICT: UNKNOWN").verdict)
    }

    @Test
    fun `the token never survives into the spoken text`() {
        val result = parseVerdict("This has peanuts in it.\nVERDICT: UNSAFE")
        assertEquals("This has peanuts in it.", result.text)
        assertFalse(result.text.contains("VERDICT"))
    }

    @Test
    fun `tolerates lowercase and stray whitespace`() {
        val result = parseVerdict("Fine.\n   verdict:   safe   ")
        assertEquals(Verdict.SAFE, result.verdict)
        assertFalse(result.text.contains("verdict"))
    }

    @Test
    fun `a token in the middle is still stripped`() {
        // A model that adds a pleasantry after the token would otherwise leave the raw token
        // sitting in the middle of what the wearer hears.
        val result = parseVerdict("Contains nuts.\nVERDICT: UNSAFE\nHope that helps!")
        assertEquals(Verdict.UNSAFE, result.verdict)
        assertFalse(result.text.contains("VERDICT"))
        assertTrue(result.text.contains("Contains nuts."))
        assertTrue(result.text.contains("Hope that helps!"))
    }

    @Test
    fun `the last token wins when there are several`() {
        assertEquals(Verdict.UNSAFE, parseVerdict("VERDICT: SAFE\nActually no.\nVERDICT: UNSAFE").verdict)
    }

    @Test
    fun `a missing token is UNKNOWN, never SAFE`() {
        // The single most important assertion here: a model that ignored the instruction must not
        // be read as clearing the food.
        val result = parseVerdict("Some prose with no token at all.")
        assertEquals(Verdict.UNKNOWN, result.verdict)
        assertEquals("Some prose with no token at all.", result.text)
    }

    @Test
    fun `an unrecognised verdict word is UNKNOWN, never SAFE`() {
        assertEquals(Verdict.UNKNOWN, parseVerdict("x\nVERDICT: PROBABLY_FINE").verdict)
    }

    @Test
    fun `empty input is UNKNOWN`() {
        assertEquals(Verdict.UNKNOWN, parseVerdict("").verdict)
    }

    @Test
    fun `tolerates mixed case`() {
        // removePrefix is case-sensitive, so a capitalised "Verdict:" is exactly the case that
        // slips through a naive implementation while the all-lower and all-upper tests pass.
        assertEquals(Verdict.SAFE, parseVerdict("Fine.\nVerdict: Safe").verdict)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd VideoShowCase && ./gradlew :app:testDebugUnitTest --tests "*PromptShapingTest*"`
Expected: FAIL — `Unresolved reference: buildPrompt`

- [ ] **Step 3: Write the implementation**

```kotlin
package com.example.video.show.demo.capture

/**
 * Shaping the request and reading the reply.
 *
 * Both backend legs go through here so they cannot drift: DevMon builds its own OpenAI call from
 * the `query` form field, so the query string is the only channel the two legs share. That is why
 * the instructions are prepended to the query rather than sent as an OpenAI `system` message.
 *
 * Free of `android.util.Log` so all of it runs in JVM unit tests.
 */

/** What the model concluded about this food, for this user. */
enum class Verdict { SAFE, UNSAFE, UNKNOWN }

/** A parsed response: the verdict, and the text with the token removed. */
data class VerdictResult(val verdict: Verdict, val text: String)

/**
 * Builds the full prompt: instructions, then the user's question.
 *
 * With [allergies] empty the allergy and verdict instructions are dropped entirely -- asking for a
 * verdict when no allergies are configured invites a meaningless SAFE.
 *
 * The two "say what you observed" lines do not make the verdict more accurate. They change what
 * the wearer is told it means, so a doubtful label still gets checked by a human.
 */
internal fun buildPrompt(userQuery: String, allergies: List<String>): String = buildString {
    appendLine("Answer in at most 5 sentences of plain text.")
    appendLine("Do not use markdown, asterisks, bullet points, or any formatting characters.")
    if (allergies.isNotEmpty()) {
        appendLine("The user is allergic to: ${allergies.joinToString(", ")}.")
        appendLine("Your final sentence must state whether this food is safe for the user.")
        appendLine("Say what you based that on, such as the ingredients you could read.")
        appendLine("Do not promise the food is safe; describe what you observed.")
        appendLine("Then output exactly one final line, nothing after it:")
        appendLine("$VERDICT_PREFIX SAFE  or  $VERDICT_PREFIX UNSAFE  or  $VERDICT_PREFIX UNKNOWN")
        appendLine("Use UNKNOWN if you cannot identify the food or read its ingredients.")
    }
    appendLine()
    append("Question: $userQuery")
}

/**
 * Pulls the verdict out of a response and removes every trace of the token.
 *
 * Strips *all* lines beginning with the token, not just a trailing one: a model that adds a
 * pleasantry after the verdict would otherwise leave a raw `VERDICT: UNSAFE` in the middle of what
 * the wearer hears. The last such line decides, since that is the model's final answer.
 *
 * **Anything unrecognised is [Verdict.UNKNOWN], never [Verdict.SAFE].** A model that ignored the
 * instruction, a truncated reply, or an error string must not read as clearing the food.
 */
internal fun parseVerdict(raw: String): VerdictResult {
    val lines = raw.lines()
    val verdictLines = lines.filter { it.trim().startsWith(VERDICT_PREFIX, ignoreCase = true) }

    val verdict = verdictLines.lastOrNull()
        ?.trim()
        // drop(), not removePrefix(): the line already matched case-insensitively, and
        // removePrefix is case-sensitive -- "Verdict: safe" would slip through both a
        // removePrefix("VERDICT:") and a removePrefix("verdict:").
        ?.drop(VERDICT_PREFIX.length)
        ?.trim()
        ?.let { word -> Verdict.entries.firstOrNull { it.name.equals(word, ignoreCase = true) } }
        ?: Verdict.UNKNOWN

    val text = lines.filterNot { it.trim().startsWith(VERDICT_PREFIX, ignoreCase = true) }
        .joinToString("\n")
        .trim()

    return VerdictResult(verdict, text)
}

private const val VERDICT_PREFIX = "VERDICT:"
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd VideoShowCase && ./gradlew :app:testDebugUnitTest --tests "*PromptShapingTest*"`
Expected: PASS — 13 tests

- [ ] **Step 5: Commit**

```bash
cd VideoShowCase
git add app/src/main/java/com/example/video/show/demo/capture/PromptShaping.kt \
        app/src/test/java/com/example/video/show/demo/capture/PromptShapingTest.kt
git commit -m "Shape the request and read the verdict back out of the reply"
```

---

### Task 2: The allergy profile

**Files:**
- Create: `app/src/main/assets/allergy_profile.json`
- Create: `app/src/main/java/com/example/video/show/demo/capture/AllergyProfile.kt`
- Test: `app/src/test/java/com/example/video/show/demo/capture/AllergyProfileTest.kt`

**Interfaces:**
- Produces: `internal fun parseAllergyProfile(json: String): List<String>` and `object AllergyProfile { fun load(context: Context): List<String> }`. Task 3 consumes `load`.

- [ ] **Step 1: Create the bundled default**

`app/src/main/assets/allergy_profile.json`:

```json
{ "allergies": ["peanuts", "tree nuts", "shellfish"] }
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.example.video.show.demo.capture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A broken profile must cost the verdict, never the answer -- so every failure path here returns
 * an empty list rather than throwing.
 */
class AllergyProfileTest {

    @Test
    fun `reads the allergy list`() {
        val json = """{ "allergies": ["peanuts", "tree nuts"] }"""
        assertEquals(listOf("peanuts", "tree nuts"), parseAllergyProfile(json))
    }

    @Test
    fun `trims and drops blanks`() {
        val json = """{ "allergies": ["  peanuts  ", "", "   "] }"""
        assertEquals(listOf("peanuts"), parseAllergyProfile(json))
    }

    @Test
    fun `an empty array is an empty list`() {
        assertEquals(emptyList<String>(), parseAllergyProfile("""{ "allergies": [] }"""))
    }

    @Test
    fun `a missing key is an empty list`() {
        assertEquals(emptyList<String>(), parseAllergyProfile("""{ "other": 1 }"""))
    }

    @Test
    fun `malformed json is an empty list, not a crash`() {
        assertEquals(emptyList<String>(), parseAllergyProfile("{ not json"))
    }

    @Test
    fun `non-string entries are skipped`() {
        val json = """{ "allergies": ["peanuts", 7, null, "milk"] }"""
        assertEquals(listOf("peanuts", "milk"), parseAllergyProfile(json))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd VideoShowCase && ./gradlew :app:testDebugUnitTest --tests "*AllergyProfileTest*"`
Expected: FAIL — `Unresolved reference: parseAllergyProfile`

- [ ] **Step 4: Write the implementation**

```kotlin
package com.example.video.show.demo.capture

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Parses an allergy profile document.
 *
 * Every failure path returns an empty list rather than throwing: a broken profile must cost the
 * verdict, never the answer. Free of `android.util.Log` so it is unit testable; the caller logs.
 */
internal fun parseAllergyProfile(json: String): List<String> = try {
    val array = JSONObject(json).optJSONArray("allergies")
    if (array == null) {
        emptyList()
    } else {
        (0 until array.length())
            .mapNotNull { i -> array.opt(i) as? String }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
} catch (e: Exception) {
    emptyList()
}

/**
 * Where the allergy list comes from.
 *
 * A default ships in assets; a file in the app's files directory overrides it, so the list can be
 * changed between demo runs with `adb push` and no rebuild:
 *
 * `adb push profile.json /sdcard/Android/data/com.example.video.show.demo/files/allergy_profile.json`
 *
 * There is deliberately no editor UI. A profile editor is its own feature with its own design
 * questions, and it is not what makes this work.
 */
object AllergyProfile {

    const val FILE_NAME = "allergy_profile.json"

    /** Never throws. An unreadable profile yields an empty list, which disables the verdict. */
    fun load(context: Context): List<String> {
        val override = File(context.filesDir, FILE_NAME)
        val json = try {
            if (override.isFile) {
                override.readText()
            } else {
                context.assets.open(FILE_NAME).bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return parseAllergyProfile(json)
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd VideoShowCase && ./gradlew :app:testDebugUnitTest --tests "*AllergyProfileTest*"`
Expected: PASS — 6 tests

- [ ] **Step 6: Commit**

```bash
cd VideoShowCase
git add app/src/main/assets/allergy_profile.json \
        app/src/main/java/com/example/video/show/demo/capture/AllergyProfile.kt \
        app/src/test/java/com/example/video/show/demo/capture/AllergyProfileTest.kt
git commit -m "Load the allergy profile, overridable by adb push"
```

---

### Task 3: Carry the verdict, and use the prompt on both legs

**Files:**
- Modify: `app/src/main/java/com/example/video/show/demo/capture/CaptureAnswer.kt`
- Modify: `app/src/main/java/com/example/video/show/demo/capture/DevmonAnswerProvider.kt`
- Modify: `app/src/main/java/com/example/video/show/demo/capture/OpenAiResponse.kt`
- Modify: `app/src/main/java/com/example/video/show/demo/capture/TquicAnswerProvider.kt`
- Modify: `app/src/main/java/com/example/video/show/demo/MainActivity.kt`
- Test: `app/src/test/java/com/example/video/show/demo/capture/CaptureAnswerTest.kt` (add), `OpenAiResponseTest.kt` (add)

**Interfaces:**
- Consumes: `buildPrompt`, `parseVerdict`, `Verdict` (Task 1); `AllergyProfile.load` (Task 2).
- Produces: `CaptureAnswer.verdict: String?`, serialised as `"verdict"` in the `0x14` JSON. Task 5 reads it on the glasses.

- [ ] **Step 1: Add `verdict` to `CaptureAnswer`**

In `CaptureAnswer.kt`, add the field and serialise it:

```kotlin
data class CaptureAnswer(
    /** Full text read aloud on the glasses. */
    val speak: String,
    /** Short line rendered on the lens. */
    val display: String,
    /** "high" | "medium" | "low"; omitted from the payload when null. */
    val confidence: String? = null,
    /**
     * "SAFE" | "UNSAFE" | "UNKNOWN", or null when no allergy profile is configured and no verdict
     * was ever requested. Null and "UNKNOWN" are different: null disables colouring entirely,
     * "UNKNOWN" means the model could not tell and is shown in amber.
     *
     * Carried as a String rather than the [Verdict] enum so an unrecognised value degrades on the
     * glasses instead of failing to parse.
     */
    val verdict: String? = null,
) {
    fun toJson(captureId: String): String = JSONObject().apply {
        put("captureId", captureId)
        put("speak", speak)
        put("display", display)
        confidence?.let { put("confidence", it) }
        verdict?.let { put("verdict", it) }
    }.toString()
}
```

- [ ] **Step 2: Add the serialisation tests**

Append to `CaptureAnswerTest.kt`:

```kotlin
    @Test
    fun `verdict is serialised when present`() {
        val json = CaptureAnswer("s", "d", verdict = "UNSAFE").toJson("cap_1")
        assertTrue(json.contains("\"verdict\":\"UNSAFE\""))
    }

    @Test
    fun `verdict is omitted when null`() {
        // No allergy profile means no verdict was asked for; the field must not appear at all
        // rather than appearing as null, which the glasses would have to special-case.
        val json = CaptureAnswer("s", "d").toJson("cap_1")
        assertFalse(json.contains("verdict"))
    }
```

Add `import org.junit.Assert.assertFalse` if absent.

- [ ] **Step 3: Strip the verdict in the DevMon response parser**

A token-less response now yields `verdict = "UNKNOWN"` where it previously yielded `null`, so any
existing test comparing whole `CaptureAnswer` objects would break. Checked: `DevmonAnswerProviderTest`
and `OpenAiResponseTest` both assert field by field, so none do. Step 8's full-suite run is the
backstop if that ever changes.

In `DevmonAnswerProvider.kt`, replace the body of `parseAnalyzeResponse`'s answer construction. The existing lines are:

```kotlin
    val answer = if (answerText.isBlank()) {
        CaptureAnswer(
            speak = "The assistant didn't return an answer.",
            display = "No answer",
        )
    } else {
        CaptureAnswer(
            speak = answerText,
            display = shortenForDisplay(answerText),
        )
    }
```

Replace with:

```kotlin
    // Strip the verdict token *before* building speak/display, so it cannot reach the wearer's
    // ears or the lens through either field.
    val parsed = parseVerdict(answerText)
    val answer = if (parsed.text.isBlank()) {
        CaptureAnswer(
            speak = "The assistant didn't return an answer.",
            display = "No answer",
        )
    } else {
        CaptureAnswer(
            speak = parsed.text,
            display = shortenForDisplay(parsed.text),
            verdict = parsed.verdict.name,
        )
    }
```

- [ ] **Step 4: Strip the verdict in the OpenAI response parser**

In `OpenAiResponse.kt`, replace:

```kotlin
    if (content.isBlank()) return emptyTquicResponseAnswer()

    return CaptureAnswer(speak = content, display = shortenForDisplay(content))
```

with:

```kotlin
    // Same reason as the DevMon parser: the token is removed before speak/display exist.
    val parsed = parseVerdict(content)
    if (parsed.text.isBlank()) return emptyTquicResponseAnswer()

    return CaptureAnswer(
        speak = parsed.text,
        display = shortenForDisplay(parsed.text),
        verdict = parsed.verdict.name,
    )
```

- [ ] **Step 5: Add an OpenAI parser test**

Append to `OpenAiResponseTest.kt`:

```kotlin
    @Test
    fun `the verdict token is stripped and captured`() {
        val body = """{"choices":[{"message":{"content":"Contains peanuts.\nVERDICT: UNSAFE"}}]}"""
        val answer = parseOpenAiChatCompletion(body)
        assertEquals("Contains peanuts.", answer.speak)
        assertEquals("UNSAFE", answer.verdict)
        assertTrue(!answer.display.contains("VERDICT"))
    }

    @Test
    fun `no token yields UNKNOWN rather than a null verdict`() {
        val body = """{"choices":[{"message":{"content":"Just some prose."}}]}"""
        assertEquals("UNKNOWN", parseOpenAiChatCompletion(body).verdict)
    }
```

- [ ] **Step 6: Take allergies in both providers and apply the prompt**

In `DevmonAnswerProvider.kt`, add a constructor parameter after `baseUrl`:

```kotlin
    /** Empty disables the allergy half of the prompt and the verdict entirely. */
    private val allergies: List<String> = emptyList(),
```

In `attempt`, replace:

```kotlin
            val effectiveQuery = query?.trim().takeUnless { it.isNullOrBlank() } ?: DEFAULT_QUERY_PROMPT
```

with:

```kotlin
            val userQuery = query?.trim().takeUnless { it.isNullOrBlank() } ?: DEFAULT_QUERY_PROMPT
            val effectiveQuery = buildPrompt(userQuery, allergies)
```

Then, where the successful result is returned, drop the verdict when no profile is configured. Replace:

```kotlin
                        AnswerAttempt.answered(result.answer)
```

with:

```kotlin
                        // No allergy profile means no verdict was requested; UNKNOWN would paint
                        // every answer amber, so it becomes null instead.
                        val answer = if (allergies.isEmpty()) {
                            result.answer.copy(verdict = null)
                        } else {
                            result.answer
                        }
                        AnswerAttempt.answered(answer)
```

In `TquicAnswerProvider.kt`, add the same constructor parameter after `model`:

```kotlin
    /** Empty disables the allergy half of the prompt and the verdict entirely. */
    private val allergies: List<String> = emptyList(),
```

In `attempt`, replace:

```kotlin
        val prompt = query?.trim().takeUnless { it.isNullOrBlank() }
            ?: DevmonAnswerProvider.DEFAULT_QUERY_PROMPT
```

with:

```kotlin
        val userQuery = query?.trim().takeUnless { it.isNullOrBlank() }
            ?: DevmonAnswerProvider.DEFAULT_QUERY_PROMPT
        val prompt = buildPrompt(userQuery, allergies)
```

and in the `H3Result.Success` 200 branch replace:

```kotlin
                    val answer = parseOpenAiChatCompletion(result.body)
```

with:

```kotlin
                    val parsedAnswer = parseOpenAiChatCompletion(result.body)
                    val answer = if (allergies.isEmpty()) {
                        parsedAnswer.copy(verdict = null)
                    } else {
                        parsedAnswer
                    }
```

- [ ] **Step 7: Load the profile and pass it in**

In `MainActivity.kt`, add the imports:

```kotlin
import com.example.video.show.demo.capture.AllergyProfile
```

Add a field beside `devmonBaseUrl`:

```kotlin
    @Volatile
    private var allergies: List<String> = emptyList()
```

In `applyAnswerProvider`, before the `answerProvider = when (recognised)` block:

```kotlin
        allergies = AllergyProfile.load(this)
        Log.i(TAG, "Allergy profile: ${if (allergies.isEmpty()) "none (verdict disabled)" else allergies.joinToString(", ")}")
```

Pass it to both providers — replace the `"DEVMON" ->` arm and `buildFailoverProvider`:

```kotlin
            "DEVMON" -> DevmonAnswerProvider(baseUrl = devmonBaseUrl, allergies = allergies)
```

```kotlin
    private fun buildFailoverProvider(devmonBaseUrl: String): AnswerProvider =
        FailoverAnswerProvider(
            primary = DevmonAnswerProvider(baseUrl = devmonBaseUrl, allergies = allergies),
            fallback = TquicAnswerProvider(
                transport = KwikH3Transport(),
                allergies = allergies,
                onLog = { Log.i(TquicAnswerProvider.LOG_TAG, it) },
            ),
            nowMs = { android.os.SystemClock.elapsedRealtime() },
            onRoute = { Log.i(FailoverAnswerProvider.LOG_TAG, it) },
        )
```

- [ ] **Step 8: Build and run the full suite**

Run: `cd VideoShowCase && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 9: Commit**

```bash
cd VideoShowCase
git add app/src/main/java/com/example/video/show/demo/capture/CaptureAnswer.kt \
        app/src/main/java/com/example/video/show/demo/capture/DevmonAnswerProvider.kt \
        app/src/main/java/com/example/video/show/demo/capture/OpenAiResponse.kt \
        app/src/main/java/com/example/video/show/demo/capture/TquicAnswerProvider.kt \
        app/src/main/java/com/example/video/show/demo/MainActivity.kt \
        app/src/test/java/com/example/video/show/demo/capture/CaptureAnswerTest.kt \
        app/src/test/java/com/example/video/show/demo/capture/OpenAiResponseTest.kt
git commit -m "Send the shaped prompt on both legs and carry the verdict back"
```

---

### Task 4: Colour the answer on the phone

**Files:**
- Modify: `app/src/main/java/com/example/video/show/demo/capture/CapturePanel.kt`
- Modify: `app/src/main/java/com/example/video/show/demo/MainActivity.kt`

**Interfaces:**
- Consumes: `CaptureAnswer.verdict` (Task 3).
- Produces: `CapturePanel.showAnswer(text: String, verdict: String?)`.

- [ ] **Step 1: Colour in `CapturePanel`**

Change `showAnswer` to take the verdict and set the colour:

```kotlin
    /**
     * Answered: the answer appears below the image, which stays visible and halves in height.
     *
     * [verdict] is "SAFE" / "UNSAFE" / "UNKNOWN", or null when no allergy profile is configured.
     * Null and "UNKNOWN" differ on purpose -- null means nothing was asked, so the text stays
     * default; "UNKNOWN" means the model could not tell, which must not look like "this is fine".
     */
    fun showAnswer(text: String, verdict: String?) {
        answerView.text = text
        answerView.setTextColor(verdictColor(verdict))
        answerScroll.scrollTo(0, 0)
        answerScroll.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
    }
```

Add to the companion:

```kotlin
    private companion object {
        const val NO_QUESTION = "(no question asked)"

        const val COLOR_DEFAULT = 0xFFFFFFFF.toInt()
        const val COLOR_UNSAFE = 0xFFFF5252.toInt()
        const val COLOR_UNKNOWN = 0xFFFFC107.toInt()

        /** Unrecognised verdict strings fall through to default rather than guessing. */
        fun verdictColor(verdict: String?): Int = when (verdict?.uppercase()) {
            "UNSAFE" -> COLOR_UNSAFE
            "UNKNOWN" -> COLOR_UNKNOWN
            else -> COLOR_DEFAULT
        }
    }
```

Also reset the colour in `clear()`, so a red answer does not bleed into the next capture. In `clear()`, after `answerView.text = ""`:

```kotlin
        answerView.setTextColor(COLOR_DEFAULT)
```

- [ ] **Step 2: Pass the verdict from `MainActivity`**

In `respondTo`, change:

```kotlin
                capturePanel.showAnswer(answer.speak)
```

to:

```kotlin
                capturePanel.showAnswer(answer.speak, answer.verdict)
```

- [ ] **Step 3: Build**

Run: `cd VideoShowCase && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 4: Commit**

```bash
cd VideoShowCase
git add app/src/main/java/com/example/video/show/demo/capture/CapturePanel.kt \
        app/src/main/java/com/example/video/show/demo/MainActivity.kt
git commit -m "Colour the phone answer red when the food is unsafe, amber when unknown"
```

---

### Task 5: Carry and colour the verdict on the glasses

**Files:**
- Modify: `glass/src/main/java/com/example/video/show/glass/capture/CaptureResponse.kt`
- Modify: `glass/src/main/java/com/example/video/show/glass/capture/CaptureActivity.kt`
- Test: `glass/src/test/java/com/example/video/show/glass/capture/CaptureResponseTest.kt` (add)

**Interfaces:**
- Consumes: the `"verdict"` field in the `0x14` payload (Task 3).

- [ ] **Step 1: Add the field to `CaptureResponse`**

Add to the data class, after `confidence`:

```kotlin
    /**
     * "SAFE" | "UNSAFE" | "UNKNOWN", or null when the phone had no allergy profile and never
     * asked. Optional: an older phone build simply omits it.
     */
    val verdict: String? = null,
```

and to `fromJson`'s construction, after `confidence = obj.stringOrNull("confidence"),`:

```kotlin
                    verdict = obj.stringOrNull("verdict"),
```

- [ ] **Step 2: Add the parsing tests**

Append to `CaptureResponseTest.kt`:

```kotlin
    @Test
    fun `verdict is parsed when present`() {
        val json = """{"captureId":"c1","speak":"s","display":"d","verdict":"UNSAFE"}"""
        assertEquals("UNSAFE", CaptureResponse.fromJson(json)?.verdict)
    }

    @Test
    fun `a payload without a verdict still parses`() {
        // An older phone build omits the field entirely; that must not fail the response.
        val json = """{"captureId":"c1","speak":"s","display":"d"}"""
        val parsed = CaptureResponse.fromJson(json)
        assertNotNull(parsed)
        assertNull(parsed?.verdict)
    }
```

Add `import org.junit.Assert.assertNotNull` and `import org.junit.Assert.assertNull` if absent.

- [ ] **Step 3: Colour the lens text**

In `CaptureActivity.kt`, change `updateQuery` to take a colour:

```kotlin
    /**
     * Renders the answer line on the lens.
     *
     * [color] carries the allergy verdict: red for unsafe, amber for unknown, default otherwise.
     * "Unknown" is deliberately not the same as safe -- a label the model could not read must not
     * look identical to one it cleared.
     */
    private fun updateQuery(text: String?, color: Int = ANSWER_COLOR_DEFAULT) {
        mBindingPair.updateView {
            tvQuery.text = "Query: ${text ?: "—"}"
            tvQuery.setTextColor(color)
        }
    }
```

At the response site, replace:

```kotlin
        val confidenceSuffix = response.confidence?.let { " ($it)" } ?: ""
        updateQuery(response.display + confidenceSuffix)
```

with:

```kotlin
        val confidenceSuffix = response.confidence?.let { " ($it)" } ?: ""
        updateQuery(response.display + confidenceSuffix, verdictColor(response.verdict))
```

Add to the `companion object`:

```kotlin
        private const val ANSWER_COLOR_DEFAULT = 0xFFFFFFFF.toInt()
        private const val ANSWER_COLOR_UNSAFE = 0xFFFF5252.toInt()
        private const val ANSWER_COLOR_UNKNOWN = 0xFFFFC107.toInt()

        /** Unrecognised verdict strings fall through to default rather than guessing. */
        private fun verdictColor(verdict: String?): Int = when (verdict?.uppercase()) {
            "UNSAFE" -> ANSWER_COLOR_UNSAFE
            "UNKNOWN" -> ANSWER_COLOR_UNKNOWN
            else -> ANSWER_COLOR_DEFAULT
        }
```

- [ ] **Step 4: Build and test both modules**

Run: `cd VideoShowCase && ./gradlew :app:assembleDebug :glass:assembleDebug :app:testDebugUnitTest :glass:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
cd VideoShowCase
git add glass/src/main/java/com/example/video/show/glass/capture/CaptureResponse.kt \
        glass/src/main/java/com/example/video/show/glass/capture/CaptureActivity.kt \
        glass/src/test/java/com/example/video/show/glass/capture/CaptureResponseTest.kt
git commit -m "Colour the lens answer by allergy verdict"
```

---

### Task 6: Device verification

**Files:** none.

- [ ] **Step 1: Install both**

```bash
cd VideoShowCase
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug :glass:assembleDebug
adb -s R3CW80VT0ET install -r app/build/outputs/apk/debug/app-debug.apk
adb -s A06B4A5D094C483 install -r glass/build/outputs/apk/debug/glass-debug.apk
adb -s R3CW80VT0ET shell am start -S -n com.example.video.show.demo/.MainActivity
```

Confirm in logcat: `Allergy profile: peanuts, tree nuts, shellfish`.

- [ ] **Step 2: Capture something containing an allergen**

Point at a package listing nuts. Expected: answer is at most 5 sentences, contains no `*` characters, ends with a statement about safety, and the phone answer text is **red**. The wearer must not hear the word "verdict".

- [ ] **Step 3: Capture something safe**

Point at a package with none of the profile's allergens. Expected: white text.

- [ ] **Step 4: Capture a non-food**

Expected: **amber** text — the model could not identify a food, which must not read as safe.

- [ ] **Step 5: Confirm the glasses colour matches**

The lens line must be the same colour as the phone for the same capture.

- [ ] **Step 6: Empty profile disables it**

```bash
printf '{ "allergies": [] }' > /tmp/empty.json
adb -s R3CW80VT0ET push /tmp/empty.json /sdcard/Android/data/com.example.video.show.demo/files/allergy_profile.json
adb -s R3CW80VT0ET shell am start -S -n com.example.video.show.demo/.MainActivity
```

Confirm `Allergy profile: none (verdict disabled)`, then capture. Expected: a normal answer, still ≤5 sentences and markdown-free, with **no colouring** — not amber.

- [ ] **Step 7: Restore the profile**

```bash
adb -s R3CW80VT0ET shell rm /sdcard/Android/data/com.example.video.show.demo/files/allergy_profile.json
adb -s R3CW80VT0ET shell am start -S -n com.example.video.show.demo/.MainActivity
```

- [ ] **Step 8: Check answer length against the playback watchdog**

The previous long answer took 57.7 s to speak against a 60 s watchdog. Confirm from logcat that no `Playback timed out` fires. If it does, 5 sentences is not tight enough and the number comes down.

- [ ] **Step 9: Commit any fixes**

```bash
cd VideoShowCase
git add -A
git commit -m "Fix issues found in device verification of the allergy verdict"
```
