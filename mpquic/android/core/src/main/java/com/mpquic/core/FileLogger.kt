package com.mpquic.core

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    init {
        var f = File("/sdcard/mpquic", "$role.log")
        var w = tryOpen(f)
        if (w == null) {
            f = File(File(context.getExternalFilesDir(null), "mpquic"), "$role.log")
            w = tryOpen(f)
        }
        file = f
        writer = w
        write("---- session start ----")
    }

    private fun tryOpen(f: File): FileWriter? = try {
        f.parentFile?.mkdirs()
        FileWriter(f, true)
    } catch (_: Exception) {
        null
    }

    @Synchronized
    fun write(line: String) {
        try {
            writer?.apply {
                write("${fmt.format(Date())} $line\n")
                flush()
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun close() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
    }
}
