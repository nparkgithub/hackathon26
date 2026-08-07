package com.mpquic.core

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * Appends every app/engine log line to a file so runs can be inspected after
 * the fact (adb pull, file manager, ...).
 *
 * Preferred location: /sdcard/mpquic/<role>.log — writable when the app has
 * the "All files access" grant (MANAGE_EXTERNAL_STORAGE; grant via Settings,
 * or `adb shell appops set <pkg> MANAGE_EXTERNAL_STORAGE allow`). Without it
 * Android blocks arbitrary /sdcard paths, so it falls back to the app's own
 * external dir /sdcard/Android/data/<pkg>/files/mpquic/<role>.log.
 *
 * [file] is the actual location; show it in the UI.
 */
class FileLogger(context: Context, role: String) {
    val file: File
    private var writer: FileWriter?
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    /** Holds log lines plus [POISON]; see [close]. */
    private val queue = LinkedBlockingQueue<Any>()
    private val thread: Thread

    init {
        var f = File("/sdcard/mpquic", "$role.log")
        var w = tryOpen(f)
        if (w == null) {
            f = File(File(context.getExternalFilesDir(null), "mpquic"), "$role.log")
            w = tryOpen(f)
        }
        file = f
        writer = w
        // Daemon: the log must never be the reason the process stays alive.
        thread = Thread(::drainLoop, "mpquic-filelog").apply {
            isDaemon = true
            start()
        }
        write("---- session start ----")
    }

    private fun tryOpen(f: File): FileWriter? = try {
        f.parentFile?.mkdirs()
        FileWriter(f, true)
    } catch (_: Exception) {
        null
    }

    /**
     * Timestamp now, write later: callers hand off a formatted line and the
     * writer thread does the I/O.
     *
     * The flush-per-line below is deliberate — a crash or ANR is exactly when
     * this file matters most, so buffering it away would lose the evidence.
     * But that flush is a synchronous write to external storage, and callers
     * include the UI thread during a bulk transfer that logs hundreds of
     * lines a second, which is enough to ANR the app on its own. Queueing
     * keeps the durability and takes the stall off the caller.
     */
    fun write(line: String) {
        queue.offer("${fmt.format(Date())} $line\n")
    }

    private fun drainLoop() {
        while (true) {
            val item = try {
                queue.take()
            } catch (_: InterruptedException) {
                return
            }
            if (item === POISON) return
            try {
                writer?.apply {
                    write(item as String)
                    flush()
                }
            } catch (_: Exception) {
                // A failed line must not kill the writer thread; later lines
                // may still land (e.g. transient storage unavailability).
            }
        }
    }

    fun close() {
        queue.offer(POISON)
        // Bounded so a wedged filesystem can't hold up teardown.
        try {
            thread.join(2_000)
        } catch (_: InterruptedException) {
        }
        synchronized(this) {
            try {
                writer?.close()
            } catch (_: Exception) {
            }
            writer = null
        }
    }

    private companion object {
        /** Stop marker; a distinct object so no real log line can ever be mistaken for it. */
        val POISON = Any()
    }
}
