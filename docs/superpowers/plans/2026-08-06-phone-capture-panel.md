# Phone Capture Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fill the phone's idle black `SurfaceView` frame with the captured image and spoken query while waiting, then add the answer below the image when it arrives.

**Architecture:** A `LinearLayout` stacked above the existing `SurfaceView` inside the black `FrameLayout`, driven by a small `CapturePanel` class with three states. State changes are pure visibility flips — a `GONE` child contributes no weight, so the image fills the box until the answer appears and halves itself when it does.

**Tech Stack:** Kotlin, Android view binding, `androidx.exifinterface`, JUnit 4.

## Global Constraints

- Module: `VideoShowCase/app`. Package `com.example.video.show.demo` (panel goes in `.capture`).
- **No `android.util.Log` in unit-tested classes** — the android.jar stub throws `RuntimeException("Stub!")`.
- View binding is already enabled (`app/build.gradle.kts:45-47`); new view ids are reachable as `binding.<id>`.
- **Display must never affect the answer path.** No method added here may throw into `onCaptureReceived` or `respondTo`; a broken image must cost the image, not the answer.
- All `CapturePanel` methods touch views and must be called on the main thread, inside the existing `runOnUiThread { if (!isDestroyedOrFinishing()) ... }` guards.
- Build: `cd VideoShowCase && ./gradlew :app:assembleDebug` and `:app:testDebugUnitTest`.
- `JAVA_HOME` must point at Android Studio's JBR: `/Applications/Android Studio.app/Contents/jbr/Contents/Home`.
- Commit after every task. Do **not** push.

---

### Task 1: EXIF rotation and image decoding

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/example/video/show/demo/capture/CaptureImage.kt`
- Test: `app/src/test/java/com/example/video/show/demo/capture/CaptureImageTest.kt`

**Interfaces:**
- Produces: `internal fun exifRotationDegrees(orientation: Int): Int` and `internal fun decodeCaptureImage(file: File): Bitmap?`. Task 2 consumes `decodeCaptureImage`'s return type; Task 3 calls it.

- [ ] **Step 1: Add the exifinterface dependency**

In `gradle/libs.versions.toml`, add to `[versions]`:

```toml
exifinterface = "1.3.7"
```

and to `[libraries]`:

```toml
androidx-exifinterface = { group = "androidx.exifinterface", name = "exifinterface", version.ref = "exifinterface" }
```

In `app/build.gradle.kts`, next to the other `implementation(libs.androidx...)` lines:

```kotlin
    // Reads the JPEG orientation tag the glasses set. StillCapture sets JPEG_ORIENTATION = 90 as
    // EXIF metadata without rotating pixels, and BitmapFactory ignores EXIF, so every capture
    // renders sideways without this.
    implementation(libs.androidx.exifinterface)
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.example.video.show.demo.capture

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The glasses set JPEG_ORIENTATION as an EXIF tag and never rotate pixels, so this mapping is the
 * only thing standing between the operator and a sideways photo on every capture.
 */
class CaptureImageTest {

    @Test
    fun `rotate 90 maps to 90 degrees`() {
        // What StillCapture actually writes -- JPEG_ORIENTATION_DEGREES = 90.
        assertEquals(90, exifRotationDegrees(ExifInterface.ORIENTATION_ROTATE_90))
    }

    @Test
    fun `rotate 180 maps to 180 degrees`() {
        assertEquals(180, exifRotationDegrees(ExifInterface.ORIENTATION_ROTATE_180))
    }

    @Test
    fun `rotate 270 maps to 270 degrees`() {
        assertEquals(270, exifRotationDegrees(ExifInterface.ORIENTATION_ROTATE_270))
    }

    @Test
    fun `normal orientation needs no rotation`() {
        assertEquals(0, exifRotationDegrees(ExifInterface.ORIENTATION_NORMAL))
    }

    @Test
    fun `undefined orientation needs no rotation`() {
        assertEquals(0, exifRotationDegrees(ExifInterface.ORIENTATION_UNDEFINED))
    }

    @Test
    fun `an unrecognised orientation is treated as upright, never thrown`() {
        // Mirrored/transposed orientations exist and this app will not honour them. Showing an
        // un-rotated photo is a cosmetic miss; throwing here would break the relay's UI thread on
        // a display concern, which is far worse.
        assertEquals(0, exifRotationDegrees(ExifInterface.ORIENTATION_TRANSPOSE))
        assertEquals(0, exifRotationDegrees(9999))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd VideoShowCase && ./gradlew :app:testDebugUnitTest --tests "*CaptureImageTest*"`
Expected: FAIL — `Unresolved reference: exifRotationDegrees`

- [ ] **Step 4: Write the implementation**

Create `CaptureImage.kt`:

```kotlin
package com.example.video.show.demo.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * Loading a capture for display on the phone.
 *
 * Separate from the answer path on purpose: nothing here may throw into `onCaptureReceived`. A
 * capture that cannot be decoded must cost the picture, not the answer the wearer is waiting for.
 */

/**
 * Degrees to rotate a capture so it displays upright.
 *
 * `StillCapture` on the glasses sets `JPEG_ORIENTATION = 90`, which writes an EXIF *tag* and
 * leaves the pixels alone. `BitmapFactory` ignores EXIF entirely, so without this every capture
 * renders on its side.
 *
 * Anything unrecognised -- including the mirrored and transposed orientations this app does not
 * handle -- maps to 0 rather than throwing. Free of `android.util.Log` so it is unit testable.
 */
internal fun exifRotationDegrees(orientation: Int): Int = when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90 -> 90
    ExifInterface.ORIENTATION_ROTATE_180 -> 180
    ExifInterface.ORIENTATION_ROTATE_270 -> 270
    else -> 0
}

/**
 * Decodes a capture and rotates it upright.
 *
 * Does file I/O and bitmap work, so it must be called off the main thread -- see the call site in
 * `MainActivity.onCaptureReceived`, which runs it before hopping to the UI thread.
 *
 * Returns null for a missing, empty, or undecodable file. Callers render the rest of the panel
 * without a picture rather than failing.
 */
internal fun decodeCaptureImage(file: File): Bitmap? = try {
    val decoded = BitmapFactory.decodeFile(file.absolutePath)
    when {
        decoded == null -> null
        else -> {
            val degrees = exifRotationDegrees(
                ExifInterface(file.absolutePath)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
            )
            if (degrees == 0) {
                decoded
            } else {
                val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                    .also { rotated -> if (rotated != decoded) decoded.recycle() }
            }
        }
    }
} catch (e: Exception) {
    // Includes OutOfMemoryError's checked cousins, unreadable files, and malformed JPEG data.
    null
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd VideoShowCase && ./gradlew :app:testDebugUnitTest --tests "*CaptureImageTest*"`
Expected: PASS — 6 tests

- [ ] **Step 6: Commit**

```bash
cd VideoShowCase
git add gradle/libs.versions.toml app/build.gradle.kts \
        app/src/main/java/com/example/video/show/demo/capture/CaptureImage.kt \
        app/src/test/java/com/example/video/show/demo/capture/CaptureImageTest.kt
git commit -m "Decode captures upright, honouring the EXIF tag the glasses set"
```

---

### Task 2: The panel layout and its state machine

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Create: `app/src/main/java/com/example/video/show/demo/capture/CapturePanel.kt`

**Interfaces:**
- Consumes: nothing from Task 1 at compile time.
- Produces: `class CapturePanel(panel: View, queryView: TextView, imageView: ImageView, answerScroll: View, answerView: TextView)` with `fun showCapture(image: Bitmap?, query: String?)`, `fun showAnswer(text: String)`, `fun clear()`. Task 3 constructs it and calls all three.

- [ ] **Step 1: Add the panel to the layout**

In `app/src/main/res/layout/activity_main.xml`, replace the existing `FrameLayout` block (the one with `android:background="#000"`) with:

```xml
    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:background="#000"
        android:minHeight="200dp">

        <SurfaceView
            android:id="@+id/surfaceView"
            android:layout_width="match_parent"
            android:layout_height="match_parent" />

        <!--
            Stacked above surfaceView so it covers the black frame in capture mode. Gone by
            default, so the streaming path is untouched until a capture arrives.
        -->
        <LinearLayout
            android:id="@+id/capturePanel"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:orientation="vertical"
            android:padding="8dp"
            android:visibility="gone">

            <TextView
                android:id="@+id/tvCaptureQuery"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:textColor="#FFF"
                android:textSize="14sp"
                android:textStyle="bold"
                android:paddingBottom="6dp" />

            <ImageView
                android:id="@+id/ivCaptureImage"
                android:layout_width="match_parent"
                android:layout_height="0dp"
                android:layout_weight="1"
                android:adjustViewBounds="true"
                android:contentDescription="@null"
                android:scaleType="fitCenter" />

            <!--
                weight 1 alongside the image, but GONE contributes no weight - so the image fills
                the box while waiting and drops to half the moment this appears. That is the whole
                resize mechanism; there is no measurement code.
            -->
            <ScrollView
                android:id="@+id/svCaptureAnswer"
                android:layout_width="match_parent"
                android:layout_height="0dp"
                android:layout_weight="1"
                android:paddingTop="6dp"
                android:visibility="gone">

                <TextView
                    android:id="@+id/tvCaptureAnswer"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:textColor="#FFF"
                    android:textSize="13sp" />
            </ScrollView>
        </LinearLayout>
    </FrameLayout>
```

- [ ] **Step 2: Write `CapturePanel`**

Create `CapturePanel.kt`:

```kotlin
package com.example.video.show.demo.capture

import android.graphics.Bitmap
import android.view.View
import android.widget.ImageView
import android.widget.TextView

/**
 * Drives the phone's capture display: the image and spoken query while an answer is pending, then
 * the answer added *below* the image rather than replacing it.
 *
 * Keeping the picture visible next to the answer is the point. When an answer looks wrong, it is
 * the only way to tell from the phone whether the model misread the scene or the glasses took a
 * bad photo.
 *
 * Its own class rather than more methods on `MainActivity`, which already carries Wi-Fi Direct,
 * streaming, the capture server, and provider selection.
 *
 * **Main thread only.** Every method touches views; [showCapture] takes an already-decoded bitmap
 * precisely so the file I/O and rotation happen before the caller hops threads.
 *
 * Free of `android.util.Log`: nothing here is worth a log line, and staying stub-free keeps the
 * file usable from a JVM test if one is ever wanted.
 */
class CapturePanel(
    private val panel: View,
    private val queryView: TextView,
    private val imageView: ImageView,
    private val answerScroll: View,
    private val answerView: TextView,
) {

    /**
     * Held so it can be recycled when replaced. A 640x480 capture is ~1.2 MB decoded; over a long
     * session of captures that accumulates if each is simply dropped on the floor.
     */
    private var current: Bitmap? = null

    /** Waiting: image and query shown, answer area hidden so the image fills the frame. */
    fun showCapture(image: Bitmap?, query: String?) {
        setBitmap(image)
        queryView.text = if (query.isNullOrBlank()) NO_QUESTION else "“${query.trim()}”"
        answerView.text = ""
        answerScroll.visibility = View.GONE
        panel.visibility = View.VISIBLE
    }

    /**
     * Answered: the answer appears below the image, which stays visible and halves in height.
     *
     * Deliberately does not touch the image or the query -- seeing all three together is why this
     * layout was chosen over replacing the picture.
     */
    fun showAnswer(text: String) {
        answerView.text = text
        answerScroll.scrollTo(0, 0)
        answerScroll.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
    }

    /** Idle: hide everything and release the bitmap. Used when live streaming takes the frame. */
    fun clear() {
        panel.visibility = View.GONE
        answerScroll.visibility = View.GONE
        answerView.text = ""
        queryView.text = ""
        setBitmap(null)
    }

    private fun setBitmap(image: Bitmap?) {
        imageView.setImageBitmap(image)
        current?.takeIf { it != image }?.recycle()
        current = image
    }

    private companion object {
        const val NO_QUESTION = "(no question asked)"
    }
}
```

- [ ] **Step 3: Build to verify the layout and class compile**

Run: `cd VideoShowCase && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. View binding generates `binding.capturePanel`, `binding.tvCaptureQuery`, `binding.ivCaptureImage`, `binding.svCaptureAnswer`, `binding.tvCaptureAnswer`.

- [ ] **Step 4: Commit**

```bash
cd VideoShowCase
git add app/src/main/res/layout/activity_main.xml \
        app/src/main/java/com/example/video/show/demo/capture/CapturePanel.kt
git commit -m "Add the capture panel: query and image, with room for the answer below"
```

---

### Task 3: Wire the panel into MainActivity

**Files:**
- Modify: `app/src/main/java/com/example/video/show/demo/MainActivity.kt`

**Interfaces:**
- Consumes: `decodeCaptureImage(file: File): Bitmap?` (Task 1); `CapturePanel(...)` with `showCapture(Bitmap?, String?)`, `showAnswer(String)`, `clear()` (Task 2).

- [ ] **Step 1: Add the imports and the field**

Add to the imports:

```kotlin
import com.example.video.show.demo.capture.CapturePanel
import com.example.video.show.demo.capture.decodeCaptureImage
```

Add a field next to the other `private var` declarations near `captureServer`:

```kotlin
    /** Built in onCreate, after the binding exists. */
    private lateinit var capturePanel: CapturePanel
```

- [ ] **Step 2: Construct it in `onCreate`**

In `onCreate`, immediately after `setContentView(binding.root)`:

```kotlin
        capturePanel = CapturePanel(
            panel = binding.capturePanel,
            queryView = binding.tvCaptureQuery,
            imageView = binding.ivCaptureImage,
            answerScroll = binding.svCaptureAnswer,
            answerView = binding.tvCaptureAnswer,
        )
```

- [ ] **Step 3: Show the capture when one arrives**

In `startCaptureServer`, replace the whole `server.onCaptureReceived = { ... }` block with:

```kotlin
        server.onCaptureReceived = { captureId, dir, queryText ->
            // Decode here, on the CaptureServer IO thread that delivers this callback, so the
            // file read and EXIF rotation never land on the UI thread. Only the finished bitmap
            // crosses over.
            val bitmap = decodeCaptureImage(java.io.File(dir, "image.jpg"))
            runOnUiThread {
                if (!isDestroyedOrFinishing()) {
                    captureCount++
                    val queryLine = if (queryText.isNullOrBlank()) "image only" else "\"$queryText\""
                    binding.tvCapture.text = "Captures: $captureCount · latest $captureId · $queryLine\n${dir.absolutePath}"
                    capturePanel.showCapture(bitmap, queryText)
                }
            }
            answerScope.launch { respondTo(server, captureId, dir, queryText) }
        }
```

- [ ] **Step 4: Show the answer when it arrives**

In `respondTo`, replace the final `runOnUiThread { ... }` block — the one that appends "answered" — with:

```kotlin
        runOnUiThread {
            if (!isDestroyedOrFinishing()) {
                binding.tvCapture.append(if (sent) "\nanswered" else "\nanswer failed to send")
                // The full spoken text, not the short lens line: the phone has the room, and
                // being able to read the whole answer is why it is shown here at all.
                capturePanel.showAnswer(answer.speak)
            }
        }
```

- [ ] **Step 5: Clear it when live streaming takes the frame**

In `startStreamReceiver`, inside `runOnUiThread`, in the `if (success)` branch, immediately after `updateStatus("Receiving audio/video")`:

```kotlin
                    // The panel sits above surfaceView, so a stale capture would cover the video.
                    capturePanel.clear()
```

- [ ] **Step 6: Build and run the full test suite**

Run: `cd VideoShowCase && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 7: Commit**

```bash
cd VideoShowCase
git add app/src/main/java/com/example/video/show/demo/MainActivity.kt
git commit -m "Show each capture and its answer on the phone instead of a black box"
```

---

### Task 4: Device verification

**Files:** none — runs the built app on hardware.

- [ ] **Step 1: Install**

```bash
cd VideoShowCase
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
adb -s R3CW80VT0ET install -r app/build/outputs/apk/debug/app-debug.apk
adb -s R3CW80VT0ET shell am start -S -n com.example.video.show.demo/.MainActivity
```

- [ ] **Step 2: Capture with a spoken query**

Take a capture from the glasses with a question.

Expected on the phone, while waiting: the black box shows the query in quotes at the top and the photo filling the rest.

- [ ] **Step 3: Confirm the photo is upright**

The photo must not be rotated 90°. This is the EXIF path from Task 1 — if it is sideways, `decodeCaptureImage` is not reading the tag.

- [ ] **Step 4: Confirm the answer appears below the image**

When the answer arrives (15–40 s), the image must shrink to roughly half the box and the answer text must appear underneath it. **The image must still be visible** — that is the whole point of this layout.

- [ ] **Step 5: Confirm a long answer scrolls**

Swipe the answer area. A ~1000-character answer must scroll rather than being cut off.

- [ ] **Step 6: Confirm a second capture resets the panel**

Take another capture. The panel must show the new image and query with **no answer text left over** from the previous one.

- [ ] **Step 7: Confirm an error renders**

```bash
adb -s R3CW80VT0ET shell am force-stop com.example.devmon
```
With the MPQUIC tunnel also down, take a capture. The failure text ("Backup unreachable" or similar) must appear in the answer area like any other answer.

- [ ] **Step 8: Regression — live streaming**

Switch the glasses to streaming mode. Video must render, and no stale capture panel may cover it.

- [ ] **Step 9: Commit any fixes**

```bash
cd VideoShowCase
git add -A
git commit -m "Fix issues found in device verification of the capture panel"
```
