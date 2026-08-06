# DevMon → TQUIC Failover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route each capture to DevMon when it reports itself ready, and to the TQUIC path when it does not.

**Architecture:** A `FailoverAnswerProvider` sits behind the existing `AnswerProvider` seam and delegates to one of two `HealthCheckedProvider`s. DevMon's leg is unchanged except for adopting the interface and gaining a short-timeout health client. The TQUIC leg builds an OpenAI-shaped body and hands it to an `H3Transport` — an interface, because no HTTP/3 client is available to this app yet (see Blocker).

**Tech Stack:** Kotlin, OkHttp 4.12, `org.json`, kotlinx-coroutines, JUnit 4.

## Blocker — read before starting

**The TQUIC leg cannot make a real network call yet.** The design named `TquicNative` as the H3 client; it is Kotlin declarations over a native library that, per `phone/shared/tquic-design/implementation-plan.md:14`, *"has never been written."* There is no `libtquic_jni.so`. The alternatives are all unavailable too: `libmpquic_jni.so` exposes `nativeH3Listen` (a server) but no client, and Cronet cannot trust the tunnel's self-signed no-SAN certificate.

This plan therefore builds **everything except the transport**, behind `H3Transport`. Task 5 ships `UnavailableH3Transport`, which is honest: it never pretends to work, so failover degrades to a spoken error exactly as the design's "both backends down" row specifies. When a real transport exists it is one new class implementing one interface, plus one line in `MainActivity`.

Everything else in this plan — routing, health, budget, retry classification, response mapping — is fully implemented and unit-tested.

## Global Constraints

- Module: `VideoShowCase/app` (the phone app). Package `com.example.video.show.demo.capture`.
- **No `android.util.Log` in any class that has JVM unit tests** — the android.jar stub throws `RuntimeException("Stub!")`. Put logging in the caller, or keep parsing in top-level functions as `DevmonAnswerProvider.kt` already does.
- **No `android.os.SystemClock` inside unit-tested classes** for the same reason. Time is injected as `nowMs: () -> Long`.
- `org.json` is available in unit tests via `testImplementation("org.json:json:20240303")` — already in `app/build.gradle.kts:69`.
- The glasses' `CaptureActivity.RESPONSE_TIMEOUT_MS` is **135_000 ms**. Nothing here may exceed it.
- Reuse `shortenForDisplay(text, maxLength = 60)` — `internal`, top-level in `KoogAnswerProvider.kt`, same package. Do not duplicate it.
- Build: `cd VideoShowCase && ./gradlew :app:assembleDebug` and `:app:testDebugUnitTest`.
- Commit after every task. Do **not** push.

---

### Task 1: `HealthCheckedProvider` seam and DevMon's adoption of it

**Files:**
- Create: `app/src/main/java/com/example/video/show/demo/capture/HealthCheckedProvider.kt`
- Modify: `app/src/main/java/com/example/video/show/demo/capture/DevmonAnswerProvider.kt`
- Test: `app/src/test/java/com/example/video/show/demo/capture/AnswerAttemptTest.kt`

**Interfaces:**
- Consumes: `AnswerProvider`, `CaptureAnswer` (both existing).
- Produces: `HealthCheckedProvider` with `val name: String`, `suspend fun isHealthy(): Boolean`, `suspend fun attempt(captureId: String, image: File, query: String?): AnswerAttempt`; and `data class AnswerAttempt(val answer: CaptureAnswer, val retriable: Boolean)`. Task 4 consumes both.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.video.show.demo.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AnswerAttempt] carries the one bit [FailoverAnswerProvider] needs that a bare [CaptureAnswer]
 * cannot: whether the failure it represents is worth trying the other backend for.
 */
class AnswerAttemptTest {

    @Test
    fun `answered attempt is not retriable`() {
        val attempt = AnswerAttempt.answered(CaptureAnswer(speak = "hi", display = "hi"))
        assertFalse(attempt.retriable)
        assertEquals("hi", attempt.answer.speak)
    }

    @Test
    fun `retriable attempt still carries a speakable answer`() {
        // The answer is the fallback text used when no other backend can be tried, so it must
        // never be null or blank -- the glasses speak it verbatim.
        val attempt = AnswerAttempt.retriable(CaptureAnswer(speak = "no peer", display = "no peer"))
        assertTrue(attempt.retriable)
        assertTrue(attempt.answer.speak.isNotBlank())
    }
}
```

Add the import `org.junit.Assert.assertEquals` alongside the others.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd VideoShowCase && ./gradlew :app:testDebugUnitTest --tests "*AnswerAttemptTest*"`
Expected: FAIL — `Unresolved reference: AnswerAttempt`

- [ ] **Step 3: Write minimal implementation**

Create `HealthCheckedProvider.kt`:

```kotlin
package com.example.video.show.demo.capture

import java.io.File

/**
 * One attempt at answering a capture.
 *
 * [retriable] is the single bit [FailoverAnswerProvider] needs and a bare [CaptureAnswer] cannot
 * carry: whether this failure is worth trying the other backend for. [answer] is always
 * speakable, because when there is no other backend to try — or no time left to try it — it is
 * what the wearer hears.
 */
data class AnswerAttempt(
    val answer: CaptureAnswer,
    val retriable: Boolean,
) {
    companion object {
        fun answered(answer: CaptureAnswer) = AnswerAttempt(answer, retriable = false)
        fun retriable(answer: CaptureAnswer) = AnswerAttempt(answer, retriable = true)
    }
}

/**
 * An [AnswerProvider] that can report whether its backend is currently able to answer, and can
 * distinguish a failure worth retrying elsewhere from one that is not.
 *
 * [isHealthy] is a *readiness* check, not a liveness one: it answers "could this backend serve a
 * capture right now", which is the only question routing cares about.
 */
interface HealthCheckedProvider : AnswerProvider {

    /** Short name for logs, e.g. "DevMon" / "TQUIC". */
    val name: String

    suspend fun isHealthy(): Boolean

    /** Like [answer], but reports whether a failure is worth failing over for. */
    suspend fun attempt(captureId: String, image: File, query: String?): AnswerAttempt

    /** Providers implement [attempt]; [answer] is the seam-compatible view of it. */
    override suspend fun answer(captureId: String, image: File, query: String?): CaptureAnswer =
        attempt(captureId, image, query).answer
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd VideoShowCase && ./gradlew :app:testDebugUnitTest --tests "*AnswerAttemptTest*"`
Expected: PASS

- [ ] **Step 5: Make `DevmonAnswerProvider` a `HealthCheckedProvider`**

In `DevmonAnswerProvider.kt`, change the class declaration to add the dedicated health client and the interface. Replace the constructor's closing `) : AnswerProvider {` with:

```kotlin
    /**
     * Separate from [client] on purpose, with far shorter timeouts.
     *
     * A DevMon that accepts the TCP connection but never replies would otherwise stall the health
     * probe for [READ_TIMEOUT_SECONDS] — two minutes — before routing had even begun, turning a
     * safety check into the worst hang in the system. The probe's job is to answer fast or be
     * treated as unhealthy.
     */
    private val healthClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(HEALTH_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(HEALTH_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build(),
) : HealthCheckedProvider {

    override val name: String = "DevMon"
```

Rename the existing `override suspend fun answer(` to `override suspend fun attempt(`, change its return type to `AnswerAttempt`, and wrap each returned `CaptureAnswer`:

- `devmonErrorAnswer(detail)` on a non-2xx becomes:
  ```kotlin
  if (isRetriableDevmonStatus(response.code)) {
      AnswerAttempt.retriable(devmonErrorAnswer(detail))
  } else {
      AnswerAttempt.answered(devmonErrorAnswer(detail))
  }
  ```
- `result.answer` on success becomes `AnswerAttempt.answered(result.answer)`
- **The `IOException` branch must separate a timeout from a refusal.** A read timeout is an `IOException` too, and requirement 3 says a slow model is a *working* model that must never be abandoned. Treating them alike would fail over on exactly the case the design forbids. Replace the branch with:

  ```kotlin
  } catch (e: IOException) {
      if (isDevmonTimeout(e)) {
          // Requirement 3: the model is probably still working. Abandoning it would restart a
          // request that was about to succeed on a possibly-cold path, lengthening the wait.
          Log.w(TAG, "capture=$captureId DevMon timed out; not failing over")
          AnswerAttempt.answered(devmonTimedOutAnswer())
      } else {
          // Refused, no route, DNS -- DevMon isn't running. Exactly what the spare is for.
          Log.w(TAG, "capture=$captureId could not reach DevMon: ${e.message}")
          AnswerAttempt.retriable(unreachableDevmonAnswer())
      }
  }
  ```

  `isDevmonTimeout` is a top-level function (Task 3) rather than a `catch` clause on purpose: as a `catch` this rule would be untestable off-device, and it is the single most important rule in the design.

- `devmonErrorAnswer(detail = null)` in the catch-all `Exception` branch becomes `AnswerAttempt.answered(...)` — an unexpected crash in our own code is not evidence the other backend would fare better

Add the new answer alongside the other top-level helpers at the bottom of the file:

```kotlin
/**
 * DevMon accepted the request but did not answer in time.
 *
 * Deliberately distinct from [unreachableDevmonAnswer]: this one means the model is probably
 * still working, which is why it does not trigger a failover.
 */
internal fun devmonTimedOutAnswer(): CaptureAnswer = CaptureAnswer(
    speak = "The assistant is taking too long to answer. Please try again.",
    display = "Timed out",
)
```

In `isHealthy()`, change `client.newCall(request)` to `healthClient.newCall(request)`.

Add to the `companion object`:

```kotlin
        private const val HEALTH_CONNECT_TIMEOUT_SECONDS = 2L
        private const val HEALTH_READ_TIMEOUT_SECONDS = 2L
```

`isRetriableDevmonStatus` arrives in Task 3; until then this will not compile. Implement Task 3 before running the build, or stub it locally and replace it.

- [ ] **Step 6: Commit**

```bash
cd VideoShowCase
git add app/src/main/java/com/example/video/show/demo/capture/HealthCheckedProvider.kt \
        app/src/main/java/com/example/video/show/demo/capture/DevmonAnswerProvider.kt \
        app/src/test/java/com/example/video/show/demo/capture/AnswerAttemptTest.kt
git commit -m "Add HealthCheckedProvider seam and give DevMon a short-timeout health client"
```

---

### Task 2: Parse the OpenAI chat-completion response

**Files:**
- Create: `app/src/main/java/com/example/video/show/demo/capture/OpenAiResponse.kt`
- Test: `app/src/test/java/com/example/video/show/demo/capture/OpenAiResponseTest.kt`

**Interfaces:**
- Consumes: `CaptureAnswer`, `shortenForDisplay` (existing).
- Produces: `internal fun parseOpenAiChatCompletion(rawBody: String): CaptureAnswer`. Task 5 consumes it.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.video.show.demo.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verified against real bytes from the tunnel on 2026-08-06 (see the design's Step 0), not
 * against an invented shape.
 */
class OpenAiResponseTest {

    @Test
    fun `reads the assistant content`() {
        val body = """
            {"id":"chatcmpl-775","model":"qwen3-vl:8b",
             "choices":[{"index":0,"message":{"role":"assistant","content":"An office workspace."}}]}
        """.trimIndent()
        assertEquals("An office workspace.", parseOpenAiChatCompletion(body).speak)
    }

    @Test
    fun `ignores the reasoning field entirely`() {
        // qwen3-vl:8b returns its chain of thought next to the answer. The glasses speak `speak`
        // verbatim, so leaking this would have the wearer listening to the model think.
        val body = """
            {"choices":[{"message":{
              "content":"An office workspace.",
              "reasoning":"So, let's look at the image. First, it's a workspace with tech items."}}]}
        """.trimIndent()
        val answer = parseOpenAiChatCompletion(body)
        assertEquals("An office workspace.", answer.speak)
        assertTrue("reasoning must never reach the wearer", !answer.speak.contains("let's look"))
        assertTrue(!answer.display.contains("let's look"))
    }

    @Test
    fun `shortens the display line`() {
        val long = "A ".repeat(80) + "end"
        val body = """{"choices":[{"message":{"content":"$long"}}]}"""
        val answer = parseOpenAiChatCompletion(body)
        assertTrue(answer.display.length <= 61)   // 60 + the ellipsis
        assertEquals(long, answer.speak)          // speak is never truncated
    }

    @Test
    fun `malformed json yields a speakable error`() {
        val answer = parseOpenAiChatCompletion("not json at all")
        assertTrue(answer.speak.isNotBlank())
        assertEquals("Bad response", answer.display)
    }

    @Test
    fun `empty choices yields a speakable error`() {
        val answer = parseOpenAiChatCompletion("""{"choices":[]}""")
        assertTrue(answer.speak.isNotBlank())
        assertEquals("No answer", answer.display)
    }

    @Test
    fun `blank content yields a speakable error`() {
        val answer = parseOpenAiChatCompletion("""{"choices":[{"message":{"content":"   "}}]}""")
        assertEquals("No answer", answer.display)
    }

    @Test
    fun `unknown fields are ignored`() {
        val body = """
            {"id":"x","object":"chat.completion","created":1786046735,"usage":{"total_tokens":9},
             "choices":[{"finish_reason":"stop","message":{"content":"ok","refusal":null}}]}
        """.trimIndent()
        assertEquals("ok", parseOpenAiChatCompletion(body).speak)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd VideoShowCase && ./gradlew :app:testDebugUnitTest --tests "*OpenAiResponseTest*"`
Expected: FAIL — `Unresolved reference: parseOpenAiChatCompletion`

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.example.video.show.demo.capture

import org.json.JSONException
import org.json.JSONObject

/**
 * Maps an OpenAI-shaped chat-completion body onto a [CaptureAnswer].
 *
 * This is what the MPQUIC tunnel returns: `answer_mode: "forward"` relays the VLM backend's raw
 * JSON unmodified, so what arrives here is Ollama's own response, not something the tunnel shaped.
 *
 * Free of `android.util.Log` so it runs in JVM unit tests, matching [parseAnalyzeResponse].
 * Never throws — every malformed shape becomes a speakable [CaptureAnswer].
 */
internal fun parseOpenAiChatCompletion(rawBody: String): CaptureAnswer {
    val json = try {
        JSONObject(rawBody)
    } catch (e: JSONException) {
        return malformedTquicResponseAnswer()
    }

    // Only `content` is read. `message.reasoning` sits right beside it holding the model's chain
    // of thought ("So, let's look at the image..."), and the glasses speak `speak` verbatim --
    // reading the wrong key would have the wearer listening to the model think.
    val content = json.optJSONArray("choices")
        ?.optJSONObject(0)
        ?.optJSONObject("message")
        ?.optString("content")
        .orEmpty()
        .trim()

    if (content.isBlank()) return emptyTquicResponseAnswer()

    return CaptureAnswer(speak = content, display = shortenForDisplay(content))
}

/** The tunnel answered, but the body wasn't parseable JSON at all. */
internal fun malformedTquicResponseAnswer(): CaptureAnswer = CaptureAnswer(
    speak = "The assistant sent a response I couldn't understand.",
    display = "Bad response",
)

/** Valid JSON, but no usable answer text in it. */
internal fun emptyTquicResponseAnswer(): CaptureAnswer = CaptureAnswer(
    speak = "The assistant didn't return an answer.",
    display = "No answer",
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd VideoShowCase && ./gradlew :app:testDebugUnitTest --tests "*OpenAiResponseTest*"`
Expected: PASS — 7 tests

- [ ] **Step 5: Commit**

```bash
cd VideoShowCase
git add app/src/main/java/com/example/video/show/demo/capture/OpenAiResponse.kt \
        app/src/test/java/com/example/video/show/demo/capture/OpenAiResponseTest.kt
git commit -m "Map OpenAI chat-completion bodies to CaptureAnswer, ignoring the reasoning field"
```

---

### Task 3: Classify which DevMon failures are worth failing over for

**Files:**
- Create: `app/src/main/java/com/example/video/show/demo/capture/RetriableStatus.kt`
- Test: `app/src/test/java/com/example/video/show/demo/capture/RetriableStatusTest.kt`

**Interfaces:**
- Produces: `internal fun isRetriableDevmonStatus(status: Int): Boolean`. Task 1 consumes it.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.video.show.demo.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetriableStatusTest {

    @Test
    fun `503 no-peer is retriable`() {
        // DevMon lost its peer between the health check and the request -- the exact race the
        // fallback exists to cover.
        assertTrue(isRetriableDevmonStatus(503))
    }

    @Test
    fun `500 upstream failure is retriable`() {
        assertTrue(isRetriableDevmonStatus(500))
    }

    @Test
    fun `400 is not retriable`() {
        // Our own request is malformed. TQUIC would reject it identically, so retrying only
        // doubles the wearer's wait and hides the bug.
        assertFalse(isRetriableDevmonStatus(400))
    }

    @Test
    fun `404 is not retriable`() {
        assertFalse(isRetriableDevmonStatus(404))
    }

    @Test
    fun `413 is not retriable`() {
        assertFalse(isRetriableDevmonStatus(413))
    }

    @Test
    fun `success is not retriable`() {
        assertFalse(isRetriableDevmonStatus(200))
    }

    @Test
    fun `unknown 5xx is retriable`() {
        // Unrecognised server-side failures get the benefit of the doubt; unrecognised
        // client-side ones do not.
        assertTrue(isRetriableDevmonStatus(502))
        assertFalse(isRetriableDevmonStatus(418))
    }

    // --- requirement 3: a slow model is a working model ---

    @Test
    fun `a read timeout is a timeout, not a refusal`() {
        assertTrue(isDevmonTimeout(java.net.SocketTimeoutException("timeout")))
    }

    @Test
    fun `a refused connection is not a timeout`() {
        // DevMon isn't running -- this one SHOULD fail over.
        assertFalse(isDevmonTimeout(java.net.ConnectException("Connection refused")))
    }

    @Test
    fun `an unknown host is not a timeout`() {
        assertFalse(isDevmonTimeout(java.net.UnknownHostException("nope")))
    }

    @Test
    fun `a plain IO error is not a timeout`() {
        assertFalse(isDevmonTimeout(java.io.IOException("broken pipe")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd VideoShowCase && ./gradlew :app:testDebugUnitTest --tests "*RetriableStatusTest*"`
Expected: FAIL — `Unresolved reference: isRetriableDevmonStatus`

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.example.video.show.demo.capture

/**
 * Whether a DevMon `/analyze` status is worth trying the other backend for.
 *
 * The split is "whose fault is it": a 5xx means DevMon or the PC behind it could not serve a
 * request we sent correctly, so a different backend plausibly can. A 4xx means we sent something
 * wrong -- malformed multipart, oversized body -- and the other backend would reject it the same
 * way, so retrying costs the wearer a second wait and buys nothing.
 *
 * Free of `android.util.Log` so it runs in JVM unit tests.
 */
internal fun isRetriableDevmonStatus(status: Int): Boolean = status in 500..599

/**
 * Whether a failed DevMon call timed out, as opposed to never connecting.
 *
 * This is the design's requirement 3 in one line, and the reason it is a function rather than a
 * `catch` clause: a slow model is a *working* model. Failing over on a timeout would abandon a
 * request that was about to succeed and restart it on a possibly-cold second path, lengthening
 * the wearer's wait rather than shortening it. As a `catch` clause the rule could only be
 * verified on a device with a genuinely slow model; here it is verified every build.
 *
 * `SocketTimeoutException` is the only timeout OkHttp raises for connect and read alike.
 */
internal fun isDevmonTimeout(e: Exception): Boolean = e is java.net.SocketTimeoutException
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd VideoShowCase && ./gradlew :app:testDebugUnitTest --tests "*RetriableStatusTest*"`
Expected: PASS — 11 tests

- [ ] **Step 5: Build the whole module, closing Task 1's dangling reference**

Run: `cd VideoShowCase && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — `DevmonAnswerProvider` now resolves `isRetriableDevmonStatus`.

- [ ] **Step 6: Commit**

```bash
cd VideoShowCase
git add app/src/main/java/com/example/video/show/demo/capture/RetriableStatus.kt \
        app/src/test/java/com/example/video/show/demo/capture/RetriableStatusTest.kt
git commit -m "Classify 5xx from DevMon as retriable and 4xx as ours to fix"
```

---

### Task 4: The router — health cache, routing, and time budget

**Files:**
- Create: `app/src/main/java/com/example/video/show/demo/capture/FailoverAnswerProvider.kt`
- Test: `app/src/test/java/com/example/video/show/demo/capture/FailoverAnswerProviderTest.kt`

**Interfaces:**
- Consumes: `HealthCheckedProvider`, `AnswerAttempt` (Task 1).
- Produces: `class FailoverAnswerProvider(primary, fallback, nowMs, healthTtlMs, deadlineMs, minFallbackBudgetMs) : AnswerProvider`. Task 6 consumes it.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.video.show.demo.capture

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** A stand-in backend whose health, answer, and retriability are all dictated by the test. */
private class FakeProvider(
    override val name: String,
    var healthy: Boolean = true,
    var attempt: AnswerAttempt = AnswerAttempt.answered(CaptureAnswer("ok", "ok")),
) : HealthCheckedProvider {
    var healthChecks = 0
    var attempts = 0
    override suspend fun isHealthy(): Boolean { healthChecks++; return healthy }
    override suspend fun attempt(captureId: String, image: File, query: String?): AnswerAttempt {
        attempts++
        return attempt
    }
}

class FailoverAnswerProviderTest {

    private val image = File("unused.jpg")

    private fun router(
        primary: FakeProvider,
        fallback: FakeProvider,
        clock: () -> Long = { 0L },
    ) = FailoverAnswerProvider(primary, fallback, nowMs = clock)

    @Test
    fun `healthy primary answers and the fallback is untouched`() = runBlocking {
        val p = FakeProvider("DevMon", healthy = true,
            attempt = AnswerAttempt.answered(CaptureAnswer("from devmon", "d")))
        val f = FakeProvider("TQUIC")
        assertEquals("from devmon", router(p, f).answer("c1", image, "q").speak)
        assertEquals(0, f.attempts)
    }

    @Test
    fun `unhealthy primary routes straight to the fallback without attempting it`() = runBlocking {
        val p = FakeProvider("DevMon", healthy = false)
        val f = FakeProvider("TQUIC",
            attempt = AnswerAttempt.answered(CaptureAnswer("from tquic", "t")))
        assertEquals("from tquic", router(p, f).answer("c1", image, "q").speak)
        assertEquals(0, p.attempts)
        assertEquals(1, f.attempts)
    }

    @Test
    fun `retriable primary failure falls over to the fallback`() = runBlocking {
        val p = FakeProvider("DevMon", healthy = true,
            attempt = AnswerAttempt.retriable(CaptureAnswer("devmon broke", "d")))
        val f = FakeProvider("TQUIC",
            attempt = AnswerAttempt.answered(CaptureAnswer("from tquic", "t")))
        assertEquals("from tquic", router(p, f).answer("c1", image, "q").speak)
    }

    @Test
    fun `non-retriable primary failure is returned as-is`() = runBlocking {
        val p = FakeProvider("DevMon", healthy = true,
            attempt = AnswerAttempt.answered(CaptureAnswer("your request was bad", "d")))
        val f = FakeProvider("TQUIC")
        assertEquals("your request was bad", router(p, f).answer("c1", image, "q").speak)
        assertEquals(0, f.attempts)
    }

    @Test
    fun `health is cached within the ttl and re-probed after it`() = runBlocking {
        val p = FakeProvider("DevMon", healthy = true)
        val f = FakeProvider("TQUIC")
        var now = 0L
        val r = FailoverAnswerProvider(p, f, nowMs = { now })
        r.answer("c1", image, null)
        now = 5_000L
        r.answer("c2", image, null)
        assertEquals("cached inside the 10s window", 1, p.healthChecks)
        now = 11_000L
        r.answer("c3", image, null)
        assertEquals("re-probed after the window", 2, p.healthChecks)
    }

    @Test
    fun `fallback is skipped when too little of the deadline remains`() = runBlocking {
        // DevMon fails retriably, but so late that TQUIC could not finish before the glasses stop
        // listening. Answering now beats starting work that will be thrown away.
        val p = FakeProvider("DevMon", healthy = true,
            attempt = AnswerAttempt.retriable(CaptureAnswer("devmon broke", "d")))
        val f = FakeProvider("TQUIC")
        var now = 0L
        val r = FailoverAnswerProvider(p, f, nowMs = {
            val t = now
            now += 120_000L   // the first call starts the clock, the next reads it 120s later
            t
        })
        assertEquals("devmon broke", r.answer("c1", image, "q").speak)
        assertEquals(0, f.attempts)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd VideoShowCase && ./gradlew :app:testDebugUnitTest --tests "*FailoverAnswerProviderTest*"`
Expected: FAIL — `Unresolved reference: FailoverAnswerProvider`

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.example.video.show.demo.capture

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Sends each capture to whichever backend can answer, preferring [primary].
 *
 * Deliberately free of `android.util.Log` and `android.os.SystemClock` so the routing rules are
 * unit-testable on the JVM; the caller supplies [nowMs] and does the logging.
 */
class FailoverAnswerProvider(
    private val primary: HealthCheckedProvider,
    private val fallback: HealthCheckedProvider,
    private val nowMs: () -> Long,
    private val healthTtlMs: Long = HEALTH_TTL_MS,
    private val deadlineMs: Long = DEADLINE_MS,
    private val minFallbackBudgetMs: Long = MIN_FALLBACK_BUDGET_MS,
    private val onRoute: (String) -> Unit = {},
) : AnswerProvider {

    private val mutex = Mutex()
    private var cachedHealthy: Boolean? = null
    private var checkedAtMs = 0L

    override suspend fun answer(captureId: String, image: File, query: String?): CaptureAnswer {
        val startedAtMs = nowMs()

        if (!primaryHealthy()) {
            onRoute("capture=$captureId ${primary.name} unhealthy, routing to ${fallback.name}")
            return fallback.attempt(captureId, image, query).answer
        }

        onRoute("capture=$captureId routing to ${primary.name}")
        val first = primary.attempt(captureId, image, query)
        if (!first.retriable) return first.answer

        // Health said yes and the request still failed in a way another backend might survive --
        // most often DevMon's peer vanishing in between. Spend what's left of the deadline on it.
        val remaining = deadlineMs - (nowMs() - startedAtMs)
        if (remaining < minFallbackBudgetMs) {
            onRoute("capture=$captureId ${primary.name} failed with ${remaining}ms left; " +
                "too little to try ${fallback.name}")
            return first.answer
        }

        onRoute("capture=$captureId ${primary.name} failed, retrying on ${fallback.name}")
        return fallback.attempt(captureId, image, query).answer
    }

    /**
     * Cached so a burst of captures doesn't re-probe every time. The cost is that a backend which
     * dies mid-window stays selected for up to [healthTtlMs] — that capture then falls back via
     * the retriable path instead, which is why both mechanisms exist.
     */
    private suspend fun primaryHealthy(): Boolean = mutex.withLock {
        val now = nowMs()
        val cached = cachedHealthy
        if (cached != null && now - checkedAtMs < healthTtlMs) return@withLock cached
        primary.isHealthy().also {
            cachedHealthy = it
            checkedAtMs = now
        }
    }

    companion object {
        const val HEALTH_TTL_MS = 10_000L

        /**
         * Total wall clock this router will spend before giving up, ~10s under the glasses'
         * `CaptureActivity.RESPONSE_TIMEOUT_MS` (135_000 ms) so an answer still has time to reach
         * a listener.
         */
        const val DEADLINE_MS = 125_000L

        /**
         * Below this, starting the fallback cannot pay off: the measured warm round trip is ~19s
         * (TQUIC) to ~24s (DevMon), so a smaller budget buys work that will be discarded.
         */
        const val MIN_FALLBACK_BUDGET_MS = 15_000L
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd VideoShowCase && ./gradlew :app:testDebugUnitTest --tests "*FailoverAnswerProviderTest*"`
Expected: PASS — 6 tests

- [ ] **Step 5: Commit**

```bash
cd VideoShowCase
git add app/src/main/java/com/example/video/show/demo/capture/FailoverAnswerProvider.kt \
        app/src/test/java/com/example/video/show/demo/capture/FailoverAnswerProviderTest.kt
git commit -m "Route captures by DevMon health, with a retry path and a shared deadline"
```

---

### Task 5: The TQUIC leg and its transport seam

**Files:**
- Create: `app/src/main/java/com/example/video/show/demo/capture/H3Transport.kt`
- Create: `app/src/main/java/com/example/video/show/demo/capture/TquicAnswerProvider.kt`
- Test: `app/src/test/java/com/example/video/show/demo/capture/TquicAnswerProviderTest.kt`

**Interfaces:**
- Consumes: `HealthCheckedProvider`, `AnswerAttempt` (Task 1); `parseOpenAiChatCompletion` (Task 2).
- Produces: `interface H3Transport` with `suspend fun post(path: String, contentType: String, body: ByteArray, timeoutMs: Long): H3Result` and `val available: Boolean`; `sealed interface H3Result` with `Success(status, body)` / `Failure(reason)`; `object UnavailableH3Transport : H3Transport`; `class TquicAnswerProvider(transport, model, nowMs)`. Task 6 consumes `TquicAnswerProvider` and `UnavailableH3Transport`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.video.show.demo.capture

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private class FakeTransport(
    override val available: Boolean = true,
    var result: H3Result = H3Result.Success(200, """{"choices":[{"message":{"content":"hi"}}]}"""),
) : H3Transport {
    var lastPath: String? = null
    var lastContentType: String? = null
    var lastBody: ByteArray? = null
    override suspend fun post(
        path: String, contentType: String, body: ByteArray, timeoutMs: Long,
    ): H3Result {
        lastPath = path; lastContentType = contentType; lastBody = body
        return result
    }
}

class TquicAnswerProviderTest {

    private val image = File.createTempFile("cap", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Test
    fun `posts an OpenAI-shaped body to the path the tunnel expects`() = runBlocking {
        val t = FakeTransport()
        TquicAnswerProvider(t, model = "qwen3-vl:8b").attempt("c1", image, "what is this")
        assertEquals("/infer", t.lastPath)
        assertEquals("application/json", t.lastContentType)
        val sent = String(t.lastBody!!)
        assertTrue(sent.contains("\"model\":\"qwen3-vl:8b\""))
        assertTrue("prompt must be carried verbatim", sent.contains("what is this"))
        assertTrue("jpeg must be a base64 data URI", sent.contains("data:image/jpeg;base64,"))
    }

    @Test
    fun `substitutes a default prompt when the wearer asked nothing`() = runBlocking {
        val t = FakeTransport()
        TquicAnswerProvider(t, model = "m").attempt("c1", image, "   ")
        assertTrue(String(t.lastBody!!).contains(DevmonAnswerProvider.DEFAULT_QUERY_PROMPT))
    }

    @Test
    fun `maps a 200 through the OpenAI parser`() = runBlocking {
        val t = FakeTransport(result = H3Result.Success(
            200, """{"choices":[{"message":{"content":"An office.","reasoning":"hmm"}}]}"""))
        val attempt = TquicAnswerProvider(t, model = "m").attempt("c1", image, "q")
        assertEquals("An office.", attempt.answer.speak)
        assertFalse(attempt.retriable)
    }

    @Test
    fun `a non-200 becomes a speakable error, never retriable`() = runBlocking {
        // TQUIC is the last resort -- there is nothing to fail over to from here.
        val t = FakeTransport(result = H3Result.Success(502, "vlm backend error"))
        val attempt = TquicAnswerProvider(t, model = "m").attempt("c1", image, "q")
        assertTrue(attempt.answer.speak.isNotBlank())
        assertFalse(attempt.retriable)
    }

    @Test
    fun `a transport failure becomes a speakable error`() = runBlocking {
        val t = FakeTransport(result = H3Result.Failure("connection refused"))
        val attempt = TquicAnswerProvider(t, model = "m").attempt("c1", image, "q")
        assertTrue(attempt.answer.speak.isNotBlank())
    }

    @Test
    fun `an unavailable transport reports unhealthy and never posts`() = runBlocking {
        val t = FakeTransport(available = false)
        val p = TquicAnswerProvider(t, model = "m")
        assertFalse(p.isHealthy())
        val attempt = p.attempt("c1", image, "q")
        assertTrue(attempt.answer.speak.isNotBlank())
        assertEquals("no request should be attempted", null, t.lastBody)
    }

    @Test
    fun `the shipped stub transport is unavailable`() {
        assertFalse(UnavailableH3Transport.available)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd VideoShowCase && ./gradlew :app:testDebugUnitTest --tests "*TquicAnswerProviderTest*"`
Expected: FAIL — `Unresolved reference: H3Transport`

- [ ] **Step 3: Write `H3Transport.kt`**

```kotlin
package com.example.video.show.demo.capture

/** The outcome of one HTTP/3 request. */
sealed interface H3Result {
    /** The peer replied. [status] may still be an error status. */
    data class Success(val status: Int, val body: String) : H3Result

    /** No reply: refused, timed out, or the transport is not usable. */
    data class Failure(val reason: String) : H3Result
}

/**
 * A minimal HTTP/3 client, as much of one as [TquicAnswerProvider] needs.
 *
 * This is an interface rather than a concrete class because **the app currently has no way to
 * speak HTTP/3**. `TquicNative` in the koog tree declares an H3 client but its native library was
 * never written (`phone/shared/tquic-design/implementation-plan.md`), `libmpquic_jni.so` exposes
 * a listener but no client, and Cronet cannot be made to trust the tunnel's self-signed no-SAN
 * certificate. Isolating the transport keeps that gap to one class instead of spreading it
 * through the routing logic.
 */
interface H3Transport {

    /** False when this transport cannot make requests at all; callers must not post. */
    val available: Boolean

    suspend fun post(
        path: String,
        contentType: String,
        body: ByteArray,
        timeoutMs: Long,
    ): H3Result
}

/**
 * The transport that ships today: honest about being unable to do anything.
 *
 * It never pretends a request succeeded, so failover degrades to a spoken error rather than to
 * silence or a fabricated answer. Replace it — and only it — once an HTTP/3 client exists.
 */
object UnavailableH3Transport : H3Transport {
    override val available = false
    override suspend fun post(
        path: String, contentType: String, body: ByteArray, timeoutMs: Long,
    ): H3Result = H3Result.Failure("no HTTP/3 client is available in this build")
}
```

- [ ] **Step 4: Write `TquicAnswerProvider.kt`**

```kotlin
package com.example.video.show.demo.capture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * Asks the TQUIC path for an answer, via the mpquic client app's local HTTP/3 intake.
 *
 * The body must already be exactly what Ollama accepts: the tunnel runs in
 * `answer_mode: "forward"`, which relays it verbatim with no repackaging. The reply is Ollama's
 * own raw JSON, which is why [parseOpenAiChatCompletion] does the mapping.
 *
 * Verified against the live tunnel on 2026-08-06: an 83,291 B body answered `200` in 18.7s.
 *
 * Never retriable — this is the last resort, and there is nothing behind it to fail over to.
 */
class TquicAnswerProvider(
    private val transport: H3Transport,
    private val model: String = DEFAULT_MODEL,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : HealthCheckedProvider {

    override val name: String = "TQUIC"

    /**
     * Readiness of the transport itself. It cannot confirm the tunnel, EC2, or Ollama are up —
     * reaching the local listener proves only that the mpquic app has one — so this is a floor,
     * not a guarantee.
     */
    override suspend fun isHealthy(): Boolean = transport.available

    override suspend fun attempt(
        captureId: String,
        image: File,
        query: String?,
    ): AnswerAttempt = withContext(Dispatchers.IO) {
        if (!transport.available) {
            return@withContext AnswerAttempt.answered(tquicUnavailableAnswer())
        }

        val prompt = query?.trim().takeUnless { it.isNullOrBlank() }
            ?: DevmonAnswerProvider.DEFAULT_QUERY_PROMPT

        val body = try {
            buildChatCompletionRequest(model, image.readBytes(), prompt).toByteArray()
        } catch (e: Exception) {
            return@withContext AnswerAttempt.answered(tquicUnavailableAnswer())
        }

        when (val result = transport.post(INFER_PATH, JSON_CONTENT_TYPE, body, timeoutMs)) {
            is H3Result.Failure -> AnswerAttempt.answered(tquicUnreachableAnswer())
            is H3Result.Success ->
                if (result.status == 200) {
                    AnswerAttempt.answered(parseOpenAiChatCompletion(result.body))
                } else {
                    AnswerAttempt.answered(tquicErrorAnswer(result.status))
                }
        }
    }

    companion object {
        const val DEFAULT_MODEL = "qwen3-vl:8b"
        const val INFER_PATH = "/infer"
        const val JSON_CONTENT_TYPE = "application/json"

        /** Matches DevMon's ceiling; the router's deadline is what actually bounds this. */
        const val DEFAULT_TIMEOUT_MS = 120_000L
    }
}

/**
 * Builds the OpenAI vision chat-completion body the VLM backend expects, with the JPEG inlined as
 * a base64 `data:` URI. Base64 inflates the payload by roughly a third — 62,323 B of JPEG became
 * an 83,291 B body in the 2026-08-06 verification run.
 *
 * Top-level and `Log`-free so it is unit-testable.
 */
internal fun buildChatCompletionRequest(model: String, jpeg: ByteArray, prompt: String): String {
    // java.util.Base64, not android.util.Base64: the latter is stubbed in JVM unit tests and
    // throws "Stub!", which would make this function untestable off-device.
    val dataUri = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)
    val content = JSONArray()
        .put(JSONObject().put("type", "text").put("text", prompt))
        .put(
            JSONObject()
                .put("type", "image_url")
                .put("image_url", JSONObject().put("url", dataUri)),
        )
    return JSONObject()
        .put("model", model)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        .toString()
}

/** The transport cannot make requests in this build. */
internal fun tquicUnavailableAnswer(): CaptureAnswer = CaptureAnswer(
    speak = "The backup assistant isn't available on this build.",
    display = "Backup unavailable",
)

/** The local HTTP/3 intake didn't reply — most likely the MPQUIC client app isn't running. */
internal fun tquicUnreachableAnswer(): CaptureAnswer = CaptureAnswer(
    speak = "I couldn't reach the backup assistant. Make sure the MPQUIC client app is connected.",
    display = "Backup unreachable",
)

/** The tunnel replied, but with an error status. */
internal fun tquicErrorAnswer(status: Int): CaptureAnswer = CaptureAnswer(
    speak = "The backup assistant couldn't answer that right now.",
    display = "Backup error ($status)",
)
```

- [ ] **Step 5: Run tests**

Run: `cd VideoShowCase && ./gradlew :app:testDebugUnitTest --tests "*TquicAnswerProviderTest*"`
Expected: PASS — 7 tests.

`java.util.Base64` requires API 26; confirm `minSdk` in `app/build.gradle.kts` is at least that before assuming the build is broken. It produces standard base64 with no line wrapping, which is what a `data:` URI needs — the equivalent of `android.util.Base64.NO_WRAP`.

- [ ] **Step 6: Commit**

```bash
cd VideoShowCase
git add app/src/main/java/com/example/video/show/demo/capture/H3Transport.kt \
        app/src/main/java/com/example/video/show/demo/capture/TquicAnswerProvider.kt \
        app/src/test/java/com/example/video/show/demo/capture/TquicAnswerProviderTest.kt
git commit -m "Add the TQUIC leg behind an H3Transport seam, with an honest unavailable stub"
```

---

### Task 6: Wire `FAILOVER` into `MainActivity` as the default

**Files:**
- Modify: `app/src/main/java/com/example/video/show/demo/MainActivity.kt:279-289`

**Interfaces:**
- Consumes: `FailoverAnswerProvider` (Task 4), `TquicAnswerProvider` + `UnavailableH3Transport` (Task 5), `DevmonAnswerProvider` (Task 1).

- [ ] **Step 1: Replace the provider selection**

Replace lines 279-289 with:

```kotlin
        answerProvider = when (recognised) {
            "ECHO" -> EchoAnswerProvider()
            // DEVMON pins the app to one backend with no fallback, which is how a failure gets
            // attributed to a single leg during testing.
            "DEVMON" -> DevmonAnswerProvider(baseUrl = devmonBaseUrl)
            "KOOG" -> KoogAnswerProvider()
            "FAILOVER" -> buildFailoverProvider(devmonBaseUrl)
            else -> buildFailoverProvider(devmonBaseUrl)
        }

        Log.i(TAG, "Answer provider: ${recognised ?: "FAILOVER"}, DevMon base URL: $devmonBaseUrl")
    }

    /**
     * DevMon primary, TQUIC spare.
     *
     * The TQUIC leg is wired to [UnavailableH3Transport] because this build has no HTTP/3 client
     * — see the class doc on [H3Transport]. It therefore reports unhealthy and answers with a
     * clear message rather than pretending, so routing is exercised and observable today and only
     * this one argument changes when a real transport lands.
     */
    private fun buildFailoverProvider(devmonBaseUrl: String): AnswerProvider =
        FailoverAnswerProvider(
            primary = DevmonAnswerProvider(baseUrl = devmonBaseUrl),
            fallback = TquicAnswerProvider(transport = UnavailableH3Transport),
            nowMs = { android.os.SystemClock.elapsedRealtime() },
            onRoute = { Log.i(TAG, it) },
        )
```

Add the imports `com.example.video.show.demo.capture.FailoverAnswerProvider`, `com.example.video.show.demo.capture.TquicAnswerProvider`, `com.example.video.show.demo.capture.UnavailableH3Transport`, and `com.example.video.show.demo.capture.AnswerProvider` if not already present.

- [ ] **Step 2: Update the `EXTRA_ANSWER_PROVIDER` KDoc**

In the KDoc block ending at line 62, add `FAILOVER` to the documented values and change the stated default:

```
         * `adb shell am start -S -n com.example.video.show.demo/.MainActivity --es answer_provider FAILOVER`
         *
         * FAILOVER (the default) routes to DevMon while it reports itself ready, and to the TQUIC
         * path when it does not. DEVMON pins to DevMon alone, which is how a failure gets
         * attributed to one leg during testing.
```

- [ ] **Step 3: Build and run the full test suite**

Run: `cd VideoShowCase && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 4: Commit**

```bash
cd VideoShowCase
git add app/src/main/java/com/example/video/show/demo/MainActivity.kt
git commit -m "Make FAILOVER the default answer provider, keeping DEVMON for single-leg testing"
```

---

### Task 7: Device verification

**Files:** none — this task runs the built app on hardware.

- [ ] **Step 1: Install both APKs**

```bash
cd VideoShowCase
./gradlew :app:assembleDebug :glass:assembleDebug
adb -s R3CW80VT0ET install -r app/build/outputs/apk/debug/app-debug.apk
adb -s A06B4A5D094C483 install -r glass/build/outputs/apk/debug/glass-debug.apk
```

- [ ] **Step 2: Confirm the healthy path is unchanged**

With DevMon running and its PC discovered, take a capture from the glasses.

Expected in `adb -s R3CW80VT0ET logcat`:
```
MainActivity: Answer provider: FAILOVER, DevMon base URL: http://127.0.0.1:8080
FailoverAnswerProvider: capture=cap_... routing to DevMon
DevmonAnswerProvider: capture=cap_... DevMon answered via model=...
```
Round trip should still be ~24 s. **This is the regression check: FAILOVER must not change the working path.**

- [ ] **Step 3: Confirm failover fires when DevMon is down**

```bash
adb -s R3CW80VT0ET shell am force-stop com.example.devmon
```
Take a capture. Expected:
```
FailoverAnswerProvider: capture=cap_... DevMon unhealthy, routing to TQUIC
```
and the wearer hears "The backup assistant isn't available on this build." — the correct outcome for a build with no H3 client. **Routing is what's being verified here, not the TQUIC call.**

- [ ] **Step 4: Confirm the health probe is fast**

Time between the capture arriving and the routing log line should be well under a second, not two minutes. This is what the dedicated 2 s health client buys.

- [ ] **Step 5: Confirm `DEVMON` still pins to one leg**

```bash
adb -s R3CW80VT0ET shell am start -S -n com.example.video.show.demo/.MainActivity --es answer_provider DEVMON
```
With DevMon still force-stopped, take a capture. Expected: no `FailoverAnswerProvider` line at all, and the wearer hears DevMon's own "couldn't reach the assistant" message.

- [ ] **Step 6: Restore and re-run the regression**

Restart DevMon, relaunch the app with no extras, and confirm Step 2's behaviour again.

- [ ] **Step 7: Commit any fixes**

```bash
cd VideoShowCase
git add -A
git commit -m "Fix issues found in device verification of the failover router"
```

---

## Deferred — the real H3 transport

Not in this plan, because none of the options can be executed without a decision or a spike:

1. **Build `tquic-jni`.** `phone/shared/tquic-design/implementation-plan.md` already specifies the crate in detail; it simply was never written. Largest effort, best fit, and it unblocks koog's client too.
2. **Ask the mpquic owner for a plain-TCP intake** that tunnels the body. Smallest change overall — `TquicAnswerProvider` would swap `H3Transport` for OkHttp and keep everything else.
3. **Bundle `libmpquic_jni.so` into this app** and open our own MPQUIC connection to EC2, reproducing `RelayFrame` framing from `h3relay.rs`. Removes the mpquic app and its three manual UI taps from the picture entirely, but duplicates its job.
4. **A pure-Java QUIC/H3 client** (kwik/flupke). Self-contained and can disable certificate validation, but unproven on Android and the biggest unknown.

Whichever lands, the change is one class implementing `H3Transport` plus one argument in `MainActivity.buildFailoverProvider`.
