# Glasses Response Path Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Send an answer from the relay phone back to the glasses over the existing capture connection, display it on the lens, and speak it aloud.

**Architecture:** The glasses already hold an open TCP connection to the phone (`CaptureClient`, port 8889) that is write-only today and idle between captures. A new frame type `0x14` travels phone → glasses on that same connection, carrying a small JSON payload. The glasses gain a read loop and a TTS wrapper; the phone gains a response writer and a pluggable `AnswerProvider` seam with an echo stub standing in for the not-yet-built Koog call.

**Tech Stack:** Kotlin, Android SDK 35 (min 32), Gradle 9.3.1 / AGP 9.1.0, kotlinx-coroutines, `org.json` (Android built-in), `android.speech.tts.TextToSpeech`, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-08-05-glasses-response-path-design.md`

## Global Constraints

- All code lands in the `VideoShowCase` submodule, branch `hackathon26-arfood`. Run git commands from `/Users/sukoon/Documents/Hackathon26/hackathon26/VideoShowCase`.
- Builds require `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` — there is no system JDK on this machine.
- Building the `glass` module requires `aos.keystore` at the VideoShowCase root (gitignored; copy from `/Users/sukoon/Downloads/VideoShowCase/VideoShowCase/aos.keystore`).
- **Commit after every task. Do NOT `git push` — the user pushes manually.**
- Commit messages: no `Co-Authored-By` trailers, no Claude/Anthropic attribution. Author is `Sukoon Sarin <sukoonsarin@gmail.com>`.
- New frame types stay in the `0x1x` block so they cannot collide with streaming types `0x01`/`0x02`.
- The response payload parser MUST ignore unknown JSON fields, so the phone can add fields later without a glasses rebuild.
- Device serials: glasses `A06B4A5D094C483`, phone `R3CW80VT0ET`.
- `RESPONSE_TIMEOUT_MS = 120_000` (2 minutes).

**Verified environment facts (do not re-derive):**
- `org.json.JSONObject` throws `RuntimeException("Stub!")` in JVM unit tests unless `testImplementation("org.json:json:20240303")` is on the test classpath. This was confirmed empirically. Task 1 adds it for `glass`; Task 4 adds it for `app`.
- `android.util.Log` throws the same way in JVM unit tests. Therefore **no `android.util.Log` calls in any class covered by unit tests** (`CaptureResponse`, `FrameReader`, `CaptureAnswer`, `EchoAnswerProvider`). Log at the call sites instead, which are not unit-tested.

---

### Task 1: Response payload parsing (glasses)

**Files:**
- Modify: `glass/src/main/java/com/example/video/show/glass/capture/CaptureProtocol.kt:45`
- Modify: `glass/build.gradle.kts:67`
- Create: `glass/src/main/java/com/example/video/show/glass/capture/CaptureResponse.kt`
- Test: `glass/src/test/java/com/example/video/show/glass/capture/CaptureResponseTest.kt`

**Interfaces:**
- Consumes: nothing (first task)
- Produces: `CaptureProtocol.TYPE_RESPONSE: Byte` (= `0x14`); `CaptureResponse(captureId: String, speak: String, display: String, confidence: String?)` with `CaptureResponse.fromJson(json: String): CaptureResponse?`

- [ ] **Step 1: Add the test dependency**

In `glass/build.gradle.kts`, directly after the line `testImplementation(libs.junit)`:

```kotlin
    // Real org.json for JVM unit tests: the android.jar stub throws "Stub!" at runtime.
    testImplementation("org.json:json:20240303")
```

- [ ] **Step 2: Write the failing test**

Create `glass/src/test/java/com/example/video/show/glass/capture/CaptureResponseTest.kt`:

```kotlin
package com.example.video.show.glass.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureResponseTest {

    @Test
    fun `parses a complete payload`() {
        val parsed = CaptureResponse.fromJson(
            """{"captureId":"cap_1","speak":"Contains peanuts.","display":"Peanuts","confidence":"high"}"""
        )
        assertEquals("cap_1", parsed?.captureId)
        assertEquals("Contains peanuts.", parsed?.speak)
        assertEquals("Peanuts", parsed?.display)
        assertEquals("high", parsed?.confidence)
    }

    @Test
    fun `confidence is optional`() {
        val parsed = CaptureResponse.fromJson(
            """{"captureId":"cap_1","speak":"hello","display":"hi"}"""
        )
        assertEquals("cap_1", parsed?.captureId)
        assertNull(parsed?.confidence)
    }

    @Test
    fun `blank confidence is treated as absent`() {
        val parsed = CaptureResponse.fromJson(
            """{"captureId":"cap_1","speak":"hello","display":"hi","confidence":""}"""
        )
        assertNull(parsed?.confidence)
    }

    @Test
    fun `unknown fields are ignored`() {
        val parsed = CaptureResponse.fromJson(
            """{"captureId":"cap_1","speak":"hello","display":"hi","detail":"long text","allergens":[{"name":"peanut"}]}"""
        )
        assertEquals("cap_1", parsed?.captureId)
        assertEquals("hello", parsed?.speak)
    }

    @Test
    fun `returns null when captureId is missing`() {
        assertNull(CaptureResponse.fromJson("""{"speak":"hello","display":"hi"}"""))
    }

    @Test
    fun `returns null when speak is missing`() {
        assertNull(CaptureResponse.fromJson("""{"captureId":"cap_1","display":"hi"}"""))
    }

    @Test
    fun `returns null when display is missing`() {
        assertNull(CaptureResponse.fromJson("""{"captureId":"cap_1","speak":"hello"}"""))
    }

    @Test
    fun `returns null when a required field is blank`() {
        assertNull(CaptureResponse.fromJson("""{"captureId":"","speak":"hello","display":"hi"}"""))
    }

    @Test
    fun `returns null on malformed json`() {
        assertNull(CaptureResponse.fromJson("not json at all"))
    }

    @Test
    fun `returns null on empty string`() {
        assertNull(CaptureResponse.fromJson(""))
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

```bash
cd /Users/sukoon/Documents/Hackathon26/hackathon26/VideoShowCase
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :glass:testDebugUnitTest --tests "*CaptureResponseTest*" --console=plain
```

Expected: FAIL — compilation error, `Unresolved reference: CaptureResponse`.

- [ ] **Step 4: Add the protocol constant**

In `glass/src/main/java/com/example/video/show/glass/capture/CaptureProtocol.kt`, after the `TYPE_CAPTURE_END` declaration (line 45):

```kotlin

    /**
     * Answer for a capture, phone -> glasses; payload is UTF-8 JSON.
     *
     * The first frame that travels in this direction — 0x10-0x13 all flow glasses -> phone.
     * Payload shape: {"captureId": String, "speak": String, "display": String, "confidence": String?}
     * Unknown fields are ignored by the parser so the phone can add more without a glasses rebuild.
     */
    const val TYPE_RESPONSE: Byte = 0x14
```

- [ ] **Step 5: Write the implementation**

Create `glass/src/main/java/com/example/video/show/glass/capture/CaptureResponse.kt`:

```kotlin
package com.example.video.show.glass.capture

import org.json.JSONObject

/**
 * An answer to one capture, as received from the relay phone.
 *
 * Parsing lives here rather than in [CaptureClient] so the transport stays dumb and this stays
 * unit-testable without a socket. Deliberately free of `android.util.Log`: that class is stubbed
 * in JVM unit tests and throws, so callers do the logging.
 */
data class CaptureResponse(
    val captureId: String,
    val speak: String,
    val display: String,
    val confidence: String? = null,
) {
    companion object {
        /**
         * @return the parsed response, or null if the JSON is malformed or a required field is
         *         missing or blank. A bad payload must never crash the capture screen.
         */
        fun fromJson(json: String): CaptureResponse? {
            return try {
                val obj = JSONObject(json)
                val captureId = obj.optString("captureId").takeIf { it.isNotBlank() } ?: return null
                val speak = obj.optString("speak").takeIf { it.isNotBlank() } ?: return null
                val display = obj.optString("display").takeIf { it.isNotBlank() } ?: return null
                CaptureResponse(
                    captureId = captureId,
                    speak = speak,
                    display = display,
                    confidence = obj.optString("confidence").takeIf { it.isNotBlank() },
                )
            } catch (e: Exception) {
                // Malformed JSON — unknown fields are fine, but a broken document is not.
                null
            }
        }
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

```bash
./gradlew :glass:testDebugUnitTest --tests "*CaptureResponseTest*" --console=plain
```

Expected: PASS, 10 tests.

- [ ] **Step 7: Commit**

```bash
git add glass/build.gradle.kts \
        glass/src/main/java/com/example/video/show/glass/capture/CaptureProtocol.kt \
        glass/src/main/java/com/example/video/show/glass/capture/CaptureResponse.kt \
        glass/src/test/java/com/example/video/show/glass/capture/CaptureResponseTest.kt
git commit -m "Add response frame type and payload parsing

Adds 0x14, the first frame that travels phone -> glasses, and the parser for
its JSON payload. Unknown fields are ignored so the phone can add detail,
allergens, or warnings later without a glasses rebuild.

Parsing is kept out of CaptureClient so it is testable without a socket, and
free of android.util.Log, which is stubbed and throws in JVM unit tests. The
same stubbing applies to org.json, so the real implementation is added to the
unit test classpath."
```

---

### Task 2: Frame reader (glasses)

**Files:**
- Create: `glass/src/main/java/com/example/video/show/glass/capture/FrameReader.kt`
- Test: `glass/src/test/java/com/example/video/show/glass/capture/FrameReaderTest.kt`

**Interfaces:**
- Consumes: `CaptureProtocol.FRAME_HEADER_SIZE`, `CaptureProtocol.MAX_PAYLOAD_BYTES`, `CaptureProtocol.TYPE_RESPONSE` (Task 1)
- Produces: `Frame(type: Byte, payload: ByteArray)`; `FrameReader.readFrame(input: InputStream, maxPayload: Int = CaptureProtocol.MAX_PAYLOAD_BYTES): Frame?`

Extracted from `CaptureClient` so it can be tested against a `ByteArrayInputStream` instead of a live socket.

- [ ] **Step 1: Write the failing test**

Create `glass/src/test/java/com/example/video/show/glass/capture/FrameReaderTest.kt`:

```kotlin
package com.example.video.show.glass.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class FrameReaderTest {

    /** Builds a wire frame: [type 1][length 4 big-endian][payload]. */
    private fun frameBytes(type: Byte, payload: ByteArray): ByteArray {
        val out = ByteArray(5 + payload.size)
        out[0] = type
        out[1] = ((payload.size shr 24) and 0xFF).toByte()
        out[2] = ((payload.size shr 16) and 0xFF).toByte()
        out[3] = ((payload.size shr 8) and 0xFF).toByte()
        out[4] = (payload.size and 0xFF).toByte()
        payload.copyInto(out, 5)
        return out
    }

    @Test
    fun `reads a single frame`() {
        val payload = "hello".toByteArray()
        val input = ByteArrayInputStream(frameBytes(CaptureProtocol.TYPE_RESPONSE, payload))
        val frame = FrameReader.readFrame(input)
        assertEquals(CaptureProtocol.TYPE_RESPONSE, frame?.type)
        assertArrayEquals(payload, frame?.payload)
    }

    @Test
    fun `reads two frames in sequence`() {
        val first = frameBytes(CaptureProtocol.TYPE_RESPONSE, "one".toByteArray())
        val second = frameBytes(CaptureProtocol.TYPE_RESPONSE, "two".toByteArray())
        val input = ByteArrayInputStream(first + second)
        assertArrayEquals("one".toByteArray(), FrameReader.readFrame(input)?.payload)
        assertArrayEquals("two".toByteArray(), FrameReader.readFrame(input)?.payload)
    }

    @Test
    fun `reads a zero length payload`() {
        val input = ByteArrayInputStream(frameBytes(0x13, ByteArray(0)))
        val frame = FrameReader.readFrame(input)
        assertEquals(0x13.toByte(), frame?.type)
        assertEquals(0, frame?.payload?.size)
    }

    @Test
    fun `returns null at clean EOF`() {
        assertNull(FrameReader.readFrame(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `returns null on a truncated header`() {
        assertNull(FrameReader.readFrame(ByteArrayInputStream(byteArrayOf(0x14, 0x00))))
    }

    @Test
    fun `returns null on a truncated payload`() {
        val full = frameBytes(CaptureProtocol.TYPE_RESPONSE, "hello".toByteArray())
        val truncated = full.copyOfRange(0, full.size - 2)
        assertNull(FrameReader.readFrame(ByteArrayInputStream(truncated)))
    }

    @Test
    fun `returns null when the declared length exceeds the cap`() {
        val bogus = byteArrayOf(CaptureProtocol.TYPE_RESPONSE, 0x7F, 0x7F, 0x7F, 0x7F)
        assertNull(FrameReader.readFrame(ByteArrayInputStream(bogus), maxPayload = 1024))
    }

    @Test
    fun `returns null on a negative declared length`() {
        val negative = byteArrayOf(CaptureProtocol.TYPE_RESPONSE, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertNull(FrameReader.readFrame(ByteArrayInputStream(negative)))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :glass:testDebugUnitTest --tests "*FrameReaderTest*" --console=plain
```

Expected: FAIL — `Unresolved reference: FrameReader`.

- [ ] **Step 3: Write the implementation**

Create `glass/src/main/java/com/example/video/show/glass/capture/FrameReader.kt`:

```kotlin
package com.example.video.show.glass.capture

import java.io.InputStream

/**
 * One protocol frame. A plain class rather than a data class: the generated `equals` on a
 * `ByteArray` property compares references, which would be a trap for callers.
 */
class Frame(val type: Byte, val payload: ByteArray)

/**
 * Reads [Frame]s off a stream using the capture protocol's framing:
 * `[type 1][length 4 big-endian][payload]`.
 *
 * Reads exactly the declared number of bytes rather than accumulating into a fixed buffer, so
 * there is no ceiling on payload size — the streaming path's 256 KB buffer cannot dispatch a
 * frame larger than itself, and this must not inherit that limit.
 *
 * Free of `android.util.Log` so it stays usable from JVM unit tests; callers log instead.
 */
object FrameReader {

    /**
     * @return the next frame, or null on clean EOF, a truncated frame, or an implausible
     *         declared length. Null always means "stop reading this connection".
     */
    fun readFrame(
        input: InputStream,
        maxPayload: Int = CaptureProtocol.MAX_PAYLOAD_BYTES,
    ): Frame? {
        val header = ByteArray(CaptureProtocol.FRAME_HEADER_SIZE)
        if (!readFully(input, header)) return null

        val length = readIntBe(header, 1)
        if (length < 0 || length > maxPayload) return null

        val payload = ByteArray(length)
        if (length > 0 && !readFully(input, payload)) return null

        return Frame(header[0], payload)
    }

    /** Reads exactly [buffer].size bytes; false on EOF. */
    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n <= 0) return false
            offset += n
        }
        return true
    }

    private fun readIntBe(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :glass:testDebugUnitTest --tests "*FrameReaderTest*" --console=plain
```

Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add glass/src/main/java/com/example/video/show/glass/capture/FrameReader.kt \
        glass/src/test/java/com/example/video/show/glass/capture/FrameReaderTest.kt
git commit -m "Add frame reader for the capture connection

Extracted rather than inlined into CaptureClient so it can be tested against a
ByteArrayInputStream instead of a live socket, covering truncation, EOF, and
implausible declared lengths.

Reads exactly the declared byte count instead of accumulating into a fixed
buffer, so payload size has no ceiling."
```

---

### Task 3: Wire the reader into CaptureClient (glasses)

**Files:**
- Modify: `glass/src/main/java/com/example/video/show/glass/capture/CaptureClient.kt` (imports; add reader field and loop; call from `connect`; stop in `closeQuietly`)

**Interfaces:**
- Consumes: `FrameReader.readFrame` and `Frame` (Task 2), `CaptureResponse.fromJson` (Task 1), `CaptureProtocol.TYPE_RESPONSE` (Task 1)
- Produces: `CaptureClient.onResponse: ((CaptureResponse) -> Unit)?` — invoked on an IO thread whenever a valid `0x14` frame arrives

- [ ] **Step 1: Add the imports**

In `glass/src/main/java/com/example/video/show/glass/capture/CaptureClient.kt`, add to the existing import block:

```kotlin
import kotlinx.coroutines.Job
import java.io.InputStream
```

- [ ] **Step 2: Add the callback and reader job fields**

Immediately after the existing `isConnected` property declaration (around line 35–37), add:

```kotlin
    /**
     * Fired on an IO thread for each valid response frame. The capture screen is responsible for
     * hopping to the main thread before touching UI.
     */
    var onResponse: ((CaptureResponse) -> Unit)? = null

    /**
     * Fired on an IO thread when the connection ends. Lets a screen waiting on an answer give up
     * immediately instead of sitting through a two-minute timeout for a reply that can never come.
     */
    var onDisconnected: (() -> Unit)? = null

    private var readerJob: Job? = null
```

- [ ] **Step 3: Start the reader once connected**

In `connect`, immediately after the line `isConnected = true` and before the existing `Log.d(TAG, "Connected to ...")` call, add:

```kotlin
                startReader(s.getInputStream())
```

- [ ] **Step 4: Add the reader loop**

Add this method directly above the existing `private fun writeFrame(`:

```kotlin
    /**
     * Reads frames until the connection ends. The socket has always been bidirectional; until the
     * response path existed nothing read from it.
     */
    private fun startReader(input: InputStream) {
        readerJob?.cancel()
        readerJob = ioScope.launch {
            try {
                while (true) {
                    val frame = FrameReader.readFrame(input) ?: break
                    when (frame.type) {
                        CaptureProtocol.TYPE_RESPONSE -> {
                            val json = String(frame.payload, Charsets.UTF_8)
                            val response = CaptureResponse.fromJson(json)
                            if (response == null) {
                                // Stay silent to the caller: a malformed payload must not clear a
                                // legitimately pending capture. The screen's timeout is the only
                                // escape hatch for "nothing usable is coming".
                                Log.w(TAG, "Ignoring malformed response payload: $json")
                            } else {
                                Log.d(TAG, "Response for ${response.captureId}")
                                onResponse?.invoke(response)
                            }
                        }
                        else -> Log.w(TAG, "Ignoring unexpected frame type: ${frame.type}")
                    }
                }
                Log.d(TAG, "Reader reached end of stream")
            } catch (e: Exception) {
                Log.w(TAG, "Reader stopped", e)
            } finally {
                // The peer is gone; fail the next send fast rather than writing into a dead socket.
                isConnected = false
                onDisconnected?.invoke()
            }
        }
    }
```

- [ ] **Step 5: Stop the reader on close**

In `closeQuietly`, as the very first statement of the method body (before the existing `try { out?.flush() }`), add:

```kotlin
        readerJob?.cancel()
        readerJob = null
```

- [ ] **Step 6: Verify it compiles**

```bash
./gradlew :glass:compileDebugKotlin --console=plain
```

Expected: BUILD SUCCESSFUL. (Behaviour is exercised on-device in Task 7; the frame parsing itself is already covered by Task 2's unit tests.)

- [ ] **Step 7: Commit**

```bash
git add glass/src/main/java/com/example/video/show/glass/capture/CaptureClient.kt
git commit -m "Read response frames on the capture connection

The socket was always bidirectional; nothing read from it until now. Adds a
reader coroutine that dispatches 0x14 frames to an onResponse callback and
marks the client disconnected when the stream ends, so the next send fails
fast instead of writing into a dead socket.

An onDisconnected callback lets a screen waiting on an answer give up as soon
as the peer goes away, rather than sitting through the full timeout for a
reply that can never arrive.

A malformed payload is logged and dropped rather than surfaced, so it cannot
clear a legitimately pending capture; the screen timeout covers that case."
```

---

### Task 4: Response payload construction and writer (phone)

**Files:**
- Modify: `app/src/main/java/com/example/video/show/demo/capture/CaptureProtocol.kt:20`
- Modify: `app/build.gradle.kts` (test dependency)
- Create: `app/src/main/java/com/example/video/show/demo/capture/CaptureAnswer.kt`
- Modify: `app/src/main/java/com/example/video/show/demo/capture/CaptureServer.kt` (retain stream, add senders)
- Test: `app/src/test/java/com/example/video/show/demo/capture/CaptureAnswerTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks (phone side is independent until Task 7)
- Produces: `CaptureAnswer(speak: String, display: String, confidence: String?)` with `toJson(captureId: String): String`; `CaptureServer.sendResponse(captureId: String, answer: CaptureAnswer): Boolean`; `CaptureServer.sendRawResponse(json: String): Boolean`

- [ ] **Step 1: Add the test dependency**

In `app/build.gradle.kts`, directly after the line `testImplementation(libs.junit)`:

```kotlin
    // Real org.json for JVM unit tests: the android.jar stub throws "Stub!" at runtime.
    testImplementation("org.json:json:20240303")
```

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/com/example/video/show/demo/capture/CaptureAnswerTest.kt`:

```kotlin
package com.example.video.show.demo.capture

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureAnswerTest {

    @Test
    fun `serialises all fields`() {
        val json = JSONObject(
            CaptureAnswer(speak = "Contains peanuts.", display = "Peanuts", confidence = "high")
                .toJson("cap_1")
        )
        assertEquals("cap_1", json.getString("captureId"))
        assertEquals("Contains peanuts.", json.getString("speak"))
        assertEquals("Peanuts", json.getString("display"))
        assertEquals("high", json.getString("confidence"))
    }

    @Test
    fun `omits confidence when null`() {
        val json = JSONObject(CaptureAnswer(speak = "hello", display = "hi").toJson("cap_1"))
        assertFalse(json.has("confidence"))
    }

    @Test
    fun `escapes quotes so the payload stays parseable`() {
        val json = JSONObject(
            CaptureAnswer(speak = """He said "no" loudly""", display = "quoted").toJson("cap_1")
        )
        assertEquals("""He said "no" loudly""", json.getString("speak"))
    }

    @Test
    fun `survives newlines in the spoken text`() {
        val json = JSONObject(
            CaptureAnswer(speak = "line one\nline two", display = "two lines").toJson("cap_1")
        )
        assertEquals("line one\nline two", json.getString("speak"))
    }

    @Test
    fun `output round trips as valid json`() {
        val raw = CaptureAnswer(speak = "a", display = "b", confidence = "low").toJson("cap_x")
        assertTrue(raw.startsWith("{"))
        assertEquals("cap_x", JSONObject(raw).getString("captureId"))
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*CaptureAnswerTest*" --console=plain
```

Expected: FAIL — `Unresolved reference: CaptureAnswer`.

- [ ] **Step 4: Add the protocol constant**

In `app/src/main/java/com/example/video/show/demo/capture/CaptureProtocol.kt`, after the `TYPE_CAPTURE_END` declaration (line 20):

```kotlin

    /** Answer for a capture, phone -> glasses; payload is UTF-8 JSON. */
    const val TYPE_RESPONSE: Byte = 0x14
```

- [ ] **Step 5: Write CaptureAnswer**

Create `app/src/main/java/com/example/video/show/demo/capture/CaptureAnswer.kt`:

```kotlin
package com.example.video.show.demo.capture

import org.json.JSONObject

/**
 * A phone-side answer to one capture, serialised into the 0x14 payload.
 *
 * Deliberately carries no `captureId`: [CaptureServer] already knows which capture it is
 * answering and stamps the id when serialising, so a provider cannot return a mismatched one.
 *
 * Free of `android.util.Log` — that class is stubbed and throws in JVM unit tests.
 */
data class CaptureAnswer(
    /** Full text read aloud on the glasses. */
    val speak: String,
    /** Short line rendered on the lens. */
    val display: String,
    /** "high" | "medium" | "low"; omitted from the payload when null. */
    val confidence: String? = null,
) {
    fun toJson(captureId: String): String = JSONObject().apply {
        put("captureId", captureId)
        put("speak", speak)
        put("display", display)
        confidence?.let { put("confidence", it) }
    }.toString()
}
```

- [ ] **Step 6: Run the test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "*CaptureAnswerTest*" --console=plain
```

Expected: PASS, 5 tests.

- [ ] **Step 7: Retain the active output stream in CaptureServer**

In `app/src/main/java/com/example/video/show/demo/capture/CaptureServer.kt`, add to the imports:

```kotlin
import java.io.OutputStream
```

Add these fields immediately after the existing `private var serverSocket: ServerSocket? = null` declaration:

```kotlin
    /**
     * Output stream of the connection currently being served, so a response can be pushed from
     * outside the read loop. One capture at a time means a single reference suffices — no
     * connection registry.
     */
    @Volatile
    private var activeOut: OutputStream? = null

    /** Guards writes, which originate on a different thread from the reader. */
    private val writeLock = Any()
```

- [ ] **Step 8: Populate and clear the stream in handleClient**

In `handleClient`, immediately after the existing line `val input = BufferedInputStream(s.getInputStream(), 64 * 1024)`, add:

```kotlin
                activeOut = s.getOutputStream()
```

Then change the closing of the `handleClient` method so the stream is cleared when the connection ends. Replace the existing final `catch` block:

```kotlin
        } catch (e: Exception) {
            Log.e(TAG, "Capture client error", e)
            onError?.invoke(e.message ?: "capture client error")
        }
```

with:

```kotlin
        } catch (e: Exception) {
            Log.e(TAG, "Capture client error", e)
            onError?.invoke(e.message ?: "capture client error")
        } finally {
            activeOut = null
        }
```

- [ ] **Step 9: Add the senders**

Add these methods immediately above the existing `private fun validateHeader(`:

```kotlin
    /**
     * Sends an answer for [captureId] to the glasses.
     * @return false when no glasses connection is open or the write fails.
     */
    fun sendResponse(captureId: String, answer: CaptureAnswer): Boolean =
        sendRawResponse(answer.toJson(captureId))

    /**
     * Sends an already-serialised payload. Exists so the debug stub can emit deliberately
     * malformed JSON to exercise the glasses' parse-failure path; normal callers use
     * [sendResponse].
     */
    fun sendRawResponse(json: String): Boolean {
        val out = activeOut
        if (out == null) {
            Log.w(TAG, "No capture connection open; dropping response")
            return false
        }
        return try {
            val payload = json.toByteArray(Charsets.UTF_8)
            synchronized(writeLock) {
                out.write(byteArrayOf(CaptureProtocol.TYPE_RESPONSE))
                out.write(intToBe(payload.size))
                out.write(payload)
                out.flush()
            }
            Log.d(TAG, "Sent response (${payload.size} B)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send response", e)
            false
        }
    }

    private fun intToBe(v: Int) = byteArrayOf(
        ((v shr 24) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )
```

- [ ] **Step 10: Verify the module compiles and tests still pass**

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 11: Commit**

```bash
git add app/build.gradle.kts \
        app/src/main/java/com/example/video/show/demo/capture/CaptureProtocol.kt \
        app/src/main/java/com/example/video/show/demo/capture/CaptureAnswer.kt \
        app/src/main/java/com/example/video/show/demo/capture/CaptureServer.kt \
        app/src/test/java/com/example/video/show/demo/capture/CaptureAnswerTest.kt
git commit -m "Send answers back over the capture connection

CaptureServer keeps the active connection's output stream so a response can be
pushed from outside the read loop, guarded by a lock because writes originate
on a different thread from the reader.

CaptureAnswer carries no captureId: the server already knows which capture it
is answering and stamps the id when serialising, so a provider cannot return a
mismatched one. sendRawResponse exists so the debug stub can emit deliberately
malformed JSON to exercise the glasses' parse-failure path."
```

---

### Task 5: Answer provider seam and echo stub (phone)

**Files:**
- Create: `app/src/main/java/com/example/video/show/demo/capture/AnswerProvider.kt`
- Modify: `app/src/main/java/com/example/video/show/demo/MainActivity.kt:135-153` (`startCaptureServer`)
- Test: `app/src/test/java/com/example/video/show/demo/capture/EchoAnswerProviderTest.kt`

**Interfaces:**
- Consumes: `CaptureAnswer`, `CaptureServer.sendResponse`, `CaptureServer.sendRawResponse` (Task 4)
- Produces: `AnswerProvider` interface; `EchoAnswerProvider(var mode: EchoAnswerProvider.Mode)` with `Mode.NORMAL | NEVER_ANSWER | ANSWER_LATE | ANSWER_GARBAGE`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/video/show/demo/capture/EchoAnswerProviderTest.kt`:

```kotlin
package com.example.video.show.demo.capture

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EchoAnswerProviderTest {

    private val image = File("/dev/null")

    @Test
    fun `echoes the query back as the spoken answer`() = runBlocking {
        val answer = EchoAnswerProvider().answer("cap_1", image, "what allergens are in this food")
        assertTrue(answer.speak.contains("what allergens are in this food"))
    }

    @Test
    fun `display line is shorter than the spoken text`() = runBlocking {
        val answer = EchoAnswerProvider().answer(
            "cap_1", image, "a fairly long spoken question about the contents of this dish"
        )
        assertTrue(answer.display.length <= answer.speak.length)
    }

    @Test
    fun `handles an image-only capture with no query`() = runBlocking {
        val answer = EchoAnswerProvider().answer("cap_1", image, null)
        assertTrue(answer.speak.isNotBlank())
        assertTrue(answer.display.isNotBlank())
    }

    @Test
    fun `handles a blank query the same as none`() = runBlocking {
        val answer = EchoAnswerProvider().answer("cap_1", image, "   ")
        assertTrue(answer.speak.isNotBlank())
    }

    @Test
    fun `reports a confidence value`() = runBlocking {
        val answer = EchoAnswerProvider().answer("cap_1", image, "hello")
        assertEquals("high", answer.confidence)
    }

    @Test
    fun `defaults to normal mode`() {
        assertEquals(EchoAnswerProvider.Mode.NORMAL, EchoAnswerProvider().mode)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*EchoAnswerProviderTest*" --console=plain
```

Expected: FAIL — `Unresolved reference: EchoAnswerProvider`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/example/video/show/demo/capture/AnswerProvider.kt`:

```kotlin
package com.example.video.show.demo.capture

import java.io.File

/**
 * Produces an answer for a capture.
 *
 * The seam between this app and whatever actually answers. [EchoAnswerProvider] stands in today;
 * a Koog-backed implementation POSTing multipart to /v1/compute replaces it later without the
 * glasses or the transport changing. Mapping a Koog response onto [CaptureAnswer] belongs on this
 * side of the seam, which is what keeps the glasses decoupled from a schema the team still owns.
 */
interface AnswerProvider {
    suspend fun answer(captureId: String, image: File, query: String?): CaptureAnswer
}

/**
 * Echoes the transcribed query back, so the full round trip is demoable before a real compute
 * backend exists.
 *
 * [mode] drives the failure paths the glasses need to handle; without it, the timeout,
 * stale-response, and malformed-payload tests are aspirational rather than testable. It is read
 * by the caller (see MainActivity), not acted on here, because "never answer" means "do not
 * send", not "suspend forever".
 */
class EchoAnswerProvider(
    @Volatile var mode: Mode = Mode.NORMAL,
) : AnswerProvider {

    enum class Mode {
        /** Answer immediately. */
        NORMAL,

        /** Send nothing, so the glasses hit their timeout. */
        NEVER_ANSWER,

        /** Answer only after the glasses have already timed out, to test the stale-id guard. */
        ANSWER_LATE,

        /** Send a deliberately malformed payload to test the parse-failure path. */
        ANSWER_GARBAGE,
    }

    override suspend fun answer(captureId: String, image: File, query: String?): CaptureAnswer {
        val spoken = query?.takeIf { it.isNotBlank() }
        return if (spoken == null) {
            CaptureAnswer(
                speak = "I received your image, but no question came with it.",
                display = "Image received, no question",
                confidence = "high",
            )
        } else {
            CaptureAnswer(
                speak = "You asked: $spoken. This is an echo; no model has answered yet.",
                display = "Echo: ${spoken.take(40)}",
                confidence = "high",
            )
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "*EchoAnswerProviderTest*" --console=plain
```

Expected: PASS, 6 tests.

- [ ] **Step 5: Wire the provider into MainActivity**

In `app/src/main/java/com/example/video/show/demo/MainActivity.kt`, add to the imports:

```kotlin
import com.example.video.show.demo.capture.AnswerProvider
import com.example.video.show.demo.capture.EchoAnswerProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
```

Add these fields immediately after the existing `private var captureCount = 0`:

```kotlin
    /** Swap for a Koog-backed provider once /v1/compute exists; nothing else needs to change. */
    private val answerProvider: AnswerProvider = EchoAnswerProvider()
    private val answerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
```

Replace the existing `server.onCaptureReceived` block (lines 142–151) with:

```kotlin
        server.onCaptureReceived = { captureId, dir, queryText ->
            runOnUiThread {
                captureCount++
                val queryLine = if (queryText.isNullOrBlank()) "image only" else "\"$queryText\""
                binding.tvCapture.text = "Captures: $captureCount · latest $captureId · $queryLine\n${dir.absolutePath}"
            }
            answerScope.launch { respondTo(server, captureId, dir, queryText) }
        }
```

- [ ] **Step 6: Add the responder**

Add this method immediately after `startCaptureServer` in `MainActivity`:

```kotlin
    /**
     * Produces an answer and sends it back to the glasses.
     *
     * The debug modes live here rather than inside the provider because "never answer" means
     * "do not send a frame", which is a transport decision, not something a provider can express
     * by returning a value.
     */
    private suspend fun respondTo(
        server: CaptureServer,
        captureId: String,
        dir: java.io.File,
        queryText: String?,
    ) {
        val provider = answerProvider
        val mode = (provider as? EchoAnswerProvider)?.mode ?: EchoAnswerProvider.Mode.NORMAL

        if (mode == EchoAnswerProvider.Mode.NEVER_ANSWER) {
            runOnUiThread { binding.tvCapture.append("\n(debug: withholding answer)") }
            return
        }
        if (mode == EchoAnswerProvider.Mode.ANSWER_LATE) {
            // Longer than the glasses' RESPONSE_TIMEOUT_MS (120s), so the answer lands after the
            // capture is no longer pending and must be discarded by the stale-id guard.
            delay(135_000)
        }
        if (mode == EchoAnswerProvider.Mode.ANSWER_GARBAGE) {
            server.sendRawResponse("{ this is not valid json")
            return
        }

        val answer = provider.answer(captureId, java.io.File(dir, "image.jpg"), queryText)
        val sent = server.sendResponse(captureId, answer)
        runOnUiThread {
            binding.tvCapture.append(if (sent) "\nanswered" else "\nanswer failed to send")
        }
    }
```

- [ ] **Step 7: Cancel the scope on destroy**

In `onDestroy`, immediately before the existing `wifiServer.release()` call, add:

```kotlin
        answerScope.cancel()
```

- [ ] **Step 8: Build and install the phone app**

```bash
./gradlew :app:assembleDebug --console=plain
adb -s R3CW80VT0ET install -r -g app/build/outputs/apk/debug/app-debug.apk
```

Expected: BUILD SUCCESSFUL, then `Success`.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/example/video/show/demo/capture/AnswerProvider.kt \
        app/src/main/java/com/example/video/show/demo/MainActivity.kt \
        app/src/test/java/com/example/video/show/demo/capture/EchoAnswerProviderTest.kt
git commit -m "Answer captures through a pluggable provider

Introduces the seam a Koog-backed implementation will slot into, with an echo
stub behind it so the full round trip is demoable before /v1/compute exists.
Mapping a compute response onto CaptureAnswer stays on the phone, which keeps
the glasses decoupled from a schema the team still owns.

The stub's debug modes are read by the caller rather than acted on inside the
provider: withholding an answer means not sending a frame, which a provider
cannot express by returning a value. Without those modes the timeout,
stale-response, and malformed-payload paths could not be tested at all."
```

---

### Task 6: Text-to-speech playback (glasses)

**Files:**
- Create: `glass/src/main/java/com/example/video/show/glass/capture/Speaker.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks
- Produces: `Speaker(context: Context)` with `init(onReady: (Boolean) -> Unit)`, `speak(text: String, onDone: () -> Unit)`, `isReady: Boolean`, `shutdown()`

- [ ] **Step 1: Write the implementation**

Create `glass/src/main/java/com/example/video/show/glass/capture/Speaker.kt`:

```kotlin
package com.example.video.show.glass.capture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Speaks answers aloud on the glasses.
 *
 * Three things make this more than a one-line wrapper:
 *  - initialisation is asynchronous and can fail, so readiness is tracked and reported rather
 *    than silently swallowed;
 *  - `speak` returning does not mean the utterance finished, so completion comes from
 *    [UtteranceProgressListener] — the capture screen needs the real end of playback before it
 *    accepts another capture;
 *  - the engine is pinned to Google's rather than following the system default. `tts_default_synth`
 *    is subject to the same reset that already bit speech recognition: Android clears the setting
 *    when the configured package enters the stopped state, and the Mercury launcher force-stops
 *    the Google package when it leaves the foreground.
 */
class Speaker(private val context: Context) {

    companion object {
        private const val TAG = "Speaker"
        private const val GOOGLE_TTS_PKG = "com.google.android.tts"
        private const val UTTERANCE_ID = "capture-answer"
    }

    private var tts: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isReady: Boolean = false
        private set

    /** Callback for the utterance in flight; cleared once it finishes or fails. */
    @Volatile
    private var pendingOnDone: (() -> Unit)? = null

    /**
     * Starts the engine. [onReady] fires on the main thread with false when TTS is unavailable —
     * callers must still show the answer on screen in that case.
     */
    fun init(onReady: (Boolean) -> Unit) {
        val engine = TextToSpeech(
            context,
            { status ->
                val ok = status == TextToSpeech.SUCCESS
                if (ok) {
                    val result = tts?.setLanguage(Locale.US)
                    if (result == TextToSpeech.LANG_MISSING_DATA ||
                        result == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        // Voice data for en-US is absent. Synthesis will not produce audio, so
                        // report not-ready and let the caller fall back to on-screen text.
                        Log.w(TAG, "en-US voice data unavailable (result=$result)")
                        isReady = false
                        mainHandler.post { onReady(false) }
                        return@TextToSpeech
                    }
                    isReady = true
                    Log.d(TAG, "TTS ready")
                } else {
                    Log.w(TAG, "TTS init failed with status $status")
                    isReady = false
                }
                mainHandler.post { onReady(ok && isReady) }
            },
            GOOGLE_TTS_PKG,
        )
        tts = engine

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                finishUtterance()
            }

            @Deprecated("Required override; the int-code variant below carries the reason")
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "Utterance error")
                finishUtterance()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "Utterance error: $errorCode")
                finishUtterance()
            }
        })
    }

    /**
     * Speaks [text], calling [onDone] on the main thread when playback ends — whether it
     * completed or failed. Returns false without calling [onDone] if the engine is not ready, so
     * callers can take the on-screen-only path.
     */
    fun speak(text: String, onDone: () -> Unit): Boolean {
        val engine = tts
        if (engine == null || !isReady) {
            Log.w(TAG, "speak() called while not ready")
            return false
        }
        pendingOnDone = onDone
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak() rejected with $result")
            pendingOnDone = null
            return false
        }
        return true
    }

    /** Runs the pending completion exactly once, on the main thread. */
    private fun finishUtterance() {
        val callback = pendingOnDone ?: return
        pendingOnDone = null
        mainHandler.post(callback)
    }

    fun shutdown() {
        pendingOnDone = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Shutdown failed", e)
        }
        tts = null
        isReady = false
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :glass:compileDebugKotlin --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add glass/src/main/java/com/example/video/show/glass/capture/Speaker.kt
git commit -m "Add text-to-speech playback for answers

Pins the engine to Google's rather than following the system default:
tts_default_synth is subject to the same reset that already broke speech
recognition, where Android clears the setting once the configured package
enters the stopped state and the Mercury launcher force-stops that package on
leaving the foreground.

Completion comes from UtteranceProgressListener because speak() returning does
not mean playback finished, and the capture screen must not accept another
capture until it has. Missing en-US voice data reports not-ready rather than
failing silently, so the caller can fall back to on-screen text."
```

---

### Task 7: Capture screen state machine (glasses)

**Files:**
- Modify: `glass/src/main/java/com/example/video/show/glass/capture/CaptureActivity.kt` (replace the `busy` flag with a state machine; wire response, timeout, and playback)

**Interfaces:**
- Consumes: `CaptureClient.onResponse` (Task 3), `CaptureResponse` (Task 1), `Speaker` (Task 6)
- Produces: nothing — this is the top of the glasses stack

- [ ] **Step 1: Add imports**

In `glass/src/main/java/com/example/video/show/glass/capture/CaptureActivity.kt`, add:

```kotlin
import android.os.Handler
import android.os.Looper
```

- [ ] **Step 2: Add the state machine, timeout constant, and fields**

Replace the existing companion object:

```kotlin
    companion object {
        private const val TAG = "CaptureActivity"
        const val EXTRA_HOST = "host"
    }
```

with:

```kotlin
    companion object {
        private const val TAG = "CaptureActivity"
        const val EXTRA_HOST = "host"

        /**
         * How long to wait for an answer before giving up.
         *
         * Deliberately generous: a remote compute route can be far slower than the ~2s a local
         * one takes, and a demo that recovers on its own beats one that needs restarting. Lower
         * it once the round-trip times logged on each response show what is realistic.
         */
        private const val RESPONSE_TIMEOUT_MS = 120_000L
    }

    /** Capture taps are accepted only in [State.READY]. */
    private enum class State { READY, CAPTURING, WAITING, SPEAKING }
```

Then replace the existing field:

```kotlin
    /** Guards against a second capture starting while one is mid-flight. */
    private var busy = false
```

with:

```kotlin
    private lateinit var speaker: Speaker

    private var state: State = State.READY

    /** The capture awaiting an answer; also the stale-response guard. */
    private var pendingCaptureId: String? = null

    /** Wall-clock start of the wait, for logging the measured round trip. */
    private var waitStartedAtMs: Long = 0L

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { onResponseTimeout() }
```

- [ ] **Step 3: Initialise the speaker and response callback**

In `onCreate`, replace this existing block:

```kotlin
        stillCapture = StillCapture(this)
        speechToText = SpeechToText(this)
        captureClient = CaptureClient()
```

with:

```kotlin
        stillCapture = StillCapture(this)
        speechToText = SpeechToText(this)
        captureClient = CaptureClient()
        speaker = Speaker(this)

        speaker.init { ready ->
            Log.i(TAG, "TTS ready: $ready")
            if (!ready) {
                // Answers will still be shown; only playback is lost.
                updateQuery("speech ready · no TTS")
            }
        }

        captureClient.onResponse = { response -> runOnUiThread { onResponseReceived(response) } }
        captureClient.onDisconnected = { runOnUiThread { onConnectionLost() } }
```

- [ ] **Step 4: Gate captures on state**

Replace the opening of `startCapture`:

```kotlin
    private fun startCapture(withQuery: Boolean) {
        if (busy) return
        if (!captureClient.isConnected) {
            updateStatus("Not connected to relay")
            return
        }
        busy = true
```

with:

```kotlin
    private fun startCapture(withQuery: Boolean) {
        if (state != State.READY) {
            Log.d(TAG, "Ignoring tap in state $state")
            return
        }
        if (!captureClient.isConnected) {
            updateStatus("Not connected to relay")
            return
        }
        state = State.CAPTURING
```

- [ ] **Step 5: Reset state on capture failure**

Inside `startCapture`, replace:

```kotlin
            if (jpeg == null) {
                updateStatus("Capture failed")
                busy = false
                return@capture
            }
```

with:

```kotlin
            if (jpeg == null) {
                updateStatus("Capture failed")
                state = State.READY
                return@capture
            }
```

- [ ] **Step 6: Enter WAITING after a successful send**

Replace the whole body of the existing `send` method:

```kotlin
    private fun send(jpeg: ByteArray, queryText: String?) {
        updateStatus("Sending to relay...")
        captureClient.sendCapture(jpeg, queryText) { success, info ->
            runOnUiThread {
                updateStatus(
                    if (success) {
                        val kind = if (queryText.isNullOrBlank()) "image only" else "image + query"
                        "Sent ($kind)\nTap to capture again"
                    } else {
                        "Send failed: ${info ?: "unknown"}"
                    }
                )
                busy = false
            }
        }
    }
```

with:

```kotlin
    private fun send(jpeg: ByteArray, queryText: String?) {
        updateStatus("Sending to relay...")
        captureClient.sendCapture(jpeg, queryText) { success, info ->
            runOnUiThread {
                if (success) {
                    // `info` is the capture id on success.
                    pendingCaptureId = info
                    waitStartedAtMs = System.currentTimeMillis()
                    state = State.WAITING
                    timeoutHandler.postDelayed(timeoutRunnable, RESPONSE_TIMEOUT_MS)
                    updateStatus("Waiting for answer...")
                } else {
                    updateStatus("Send failed: ${info ?: "unknown"}")
                    state = State.READY
                }
            }
        }
    }
```

- [ ] **Step 7: Handle the response**

Add these methods immediately after `send`:

```kotlin
    /**
     * A response arrived. Ignored unless it matches the pending capture: a late answer for a
     * capture that already timed out must not speak over a newer one.
     */
    private fun onResponseReceived(response: CaptureResponse) {
        val pending = pendingCaptureId
        if (state != State.WAITING || pending == null || response.captureId != pending) {
            Log.w(TAG, "Ignoring response for ${response.captureId} (state=$state, pending=$pending)")
            return
        }
        val elapsedMs = System.currentTimeMillis() - waitStartedAtMs
        // The number that tells us whether RESPONSE_TIMEOUT_MS is set anywhere near right.
        Log.i(TAG, "Response received in ${elapsedMs}ms for ${response.captureId}")

        timeoutHandler.removeCallbacks(timeoutRunnable)
        pendingCaptureId = null

        val confidenceSuffix = response.confidence?.let { " ($it)" } ?: ""
        updateQuery(response.display + confidenceSuffix)

        state = State.SPEAKING
        updateStatus("Answer received")

        val started = speaker.speak(response.speak) {
            state = State.READY
            updateStatus("Tap to capture again")
        }
        if (!started) {
            // No audio, but the answer is on screen — never lose it to a TTS failure.
            state = State.READY
            updateStatus("Answer shown (no audio)\nTap to capture again")
        }
    }

    private fun onResponseTimeout() {
        if (state != State.WAITING) return
        Log.w(TAG, "Timed out waiting for a response to $pendingCaptureId")
        pendingCaptureId = null
        state = State.READY
        updateStatus("No answer (timed out)\nTap to capture again")
    }

    /**
     * The connection ended. If an answer was outstanding it can never arrive, so give up now
     * rather than leaving the user staring at "Waiting for answer..." for the full timeout.
     */
    private fun onConnectionLost() {
        Log.w(TAG, "Connection lost (state=$state)")
        timeoutHandler.removeCallbacks(timeoutRunnable)
        pendingCaptureId = null
        if (state != State.SPEAKING) {
            // Let an utterance already in flight finish; its completion returns us to READY.
            state = State.READY
        }
        updateStatus("Connection lost")
    }
```

- [ ] **Step 8: Release resources on destroy**

Replace the existing `onDestroy`:

```kotlin
    override fun onDestroy() {
        speechToText.destroy()
        stillCapture.release()
        captureClient.release()
        super.onDestroy()
    }
```

with:

```kotlin
    override fun onDestroy() {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        speechToText.destroy()
        stillCapture.release()
        captureClient.release()
        speaker.shutdown()
        super.onDestroy()
    }
```

- [ ] **Step 9: Build and install**

```bash
./gradlew :glass:assembleDebug --console=plain
adb -s A06B4A5D094C483 install -r -g glass/build/outputs/apk/debug/glass-debug.apk
```

Expected: BUILD SUCCESSFUL, then `Success`.

- [ ] **Step 10: Run the unit suites to confirm nothing regressed**

```bash
./gradlew :glass:testDebugUnitTest :app:testDebugUnitTest --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 11: Commit**

```bash
git add glass/src/main/java/com/example/video/show/glass/capture/CaptureActivity.kt
git commit -m "Wait for and play back the answer on the capture screen

Replaces the busy flag with an explicit READY/CAPTURING/WAITING/SPEAKING state
machine. Taps are accepted only in READY, which resumes after playback ends
rather than when the answer arrives, so a tap during speech is ignored.

A response is accepted only when its captureId matches the pending capture, so
an answer that arrives after its capture timed out cannot speak over a newer
one. Every response logs its measured round trip, which is what will make the
timeout constant an informed choice rather than a guess.

A TTS failure falls back to the answer on screen: the text is never lost to a
playback problem."
```

---

### Task 8: End-to-end verification

**Files:** none modified — this task runs the spec's test matrix against real hardware.

**Interfaces:**
- Consumes: everything from Tasks 1–7
- Produces: a verified feature, or defects to fix before claiming completion

**Setup:** phone `R3CW80VT0ET` and glasses `A06B4A5D094C483` both connected. Start a log tail in a second terminal:

```bash
adb -s A06B4A5D094C483 logcat -c
adb -s A06B4A5D094C483 logcat | grep -E "CaptureActivity|CaptureClient|Speaker|SpeechToText"
```

Pair the devices first: on the phone tap **Create Wi-Fi Direct Group**; on the glasses **Discover devices** → select the phone → **Capture + query**.

- [ ] **Step 1: Happy path**

Tap **Capture + speak query**, say "what allergens are in this food", wait.

Expected: glasses show `Waiting for answer...`, then the echoed answer appears on the query line and is spoken aloud, then `Tap to capture again`. Logcat shows `Response received in NNNNms`. **Record that number** — it is the evidence for choosing a final `RESPONSE_TIMEOUT_MS`.

- [ ] **Step 2: Taps ignored while waiting**

Repeat Step 1, and tap the capture button several times during `Waiting for answer...`.

Expected: logcat shows `Ignoring tap in state WAITING`; the phone's capture count increases by exactly one.

- [ ] **Step 3: Taps ignored during playback**

Repeat Step 1 with a long spoken query, and tap while the answer is being spoken.

Expected: `Ignoring tap in state SPEAKING`; no second capture is sent.

- [ ] **Step 4: Timeout**

Set the stub to withhold answers by changing `EchoAnswerProvider()` to `EchoAnswerProvider(EchoAnswerProvider.Mode.NEVER_ANSWER)` in `MainActivity`, then rebuild and reinstall the phone app:

```bash
./gradlew :app:assembleDebug --console=plain
adb -s R3CW80VT0ET install -r -g app/build/outputs/apk/debug/app-debug.apk
```

Capture once and wait two minutes.

Expected: glasses show `No answer (timed out)` and return to `READY`; a subsequent tap starts a new capture.

- [ ] **Step 5: Stale response is discarded**

Switch the stub to `EchoAnswerProvider.Mode.ANSWER_LATE`, rebuild, reinstall. Capture once and wait past the timeout, then keep watching for a further ~15 seconds.

Expected: the timeout fires as in Step 4, and when the late answer arrives logcat shows `Ignoring response for cap_... (state=READY, pending=null)`. Nothing is spoken.

- [ ] **Step 6: Malformed payload**

Switch the stub to `EchoAnswerProvider.Mode.ANSWER_GARBAGE`, rebuild, reinstall. Capture once.

Expected: logcat shows `Ignoring malformed response payload: { this is not valid json`; the glasses stay in `Waiting for answer...` and then time out. Nothing crashes.

- [ ] **Step 7: TTS unavailable**

Restore `EchoAnswerProvider()` to normal mode, rebuild, reinstall. Then disable Google TTS on the glasses:

```bash
adb -s A06B4A5D094C483 shell pm disable-user --user 0 com.google.android.tts
```

Restart the glasses app and capture once.

Expected: the answer appears on screen with `Answer shown (no audio)`; no crash, no hang, and the screen returns to `READY`. Re-enable afterwards:

```bash
adb -s A06B4A5D094C483 shell pm enable com.google.android.tts
```

- [ ] **Step 8: Connection lost mid-wait**

Capture once, then force-stop the phone app while the glasses wait:

```bash
adb -s R3CW80VT0ET shell am force-stop com.example.video.show.demo
```

Expected: logcat shows `Connection lost (state=WAITING)` **within a second or two** — not after the two-minute timeout — and the glasses display `Connection lost` and return to `READY`.

- [ ] **Step 9: Regression — existing flows still work**

- Live streaming: connect, choose **Live streaming**, complete resolution → frame rate → audio, start streaming, confirm video on the phone.
- Image-only capture: choose **Capture + query** → **Capture image only**, confirm `image.jpg` lands with no `query.txt`.

- [ ] **Step 10: Record findings and commit any fixes**

If every step passed, note the observed round-trip times from Step 1 in the spec's Open Items and consider lowering `RESPONSE_TIMEOUT_MS`. If any step failed, fix it and re-run the affected steps before claiming completion.

```bash
git add -A
git commit -m "Record end-to-end verification results for the response path"
```

---

## Notes for the implementer

- **Do not push.** Commit only; the user pushes manually.
- **`sendCapture`'s success callback passes the capture id as its second argument** — Task 7 Step 6 relies on this. It is the existing behaviour of `CaptureClient.sendCapture`, not something this plan changes.
- **Unit tests cannot touch `android.util.Log` or `org.json` without help.** Both are stubbed in the JVM test classpath and throw. The `org.json` dependency added in Tasks 1 and 4 solves the latter; the former is handled by keeping `Log` out of the four unit-tested classes entirely.
- **The `EchoAnswerProvider.Mode` switch requires a rebuild** of the phone app. That is deliberate: it is a debug affordance for Tasks 8.4–8.6, not a runtime feature, and does not warrant a settings surface.
