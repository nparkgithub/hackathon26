package com.mpquic.core

/**
 * JNI bridge to the TQUIC engine (libmpquic_jni.so).
 * One engine (client or server) runs per process.
 */
object TquicBridge {
    init {
        System.loadLibrary("mpquic_jni")
    }

    /** Start the engine with a JSON config. Returns 0 on success. */
    external fun nativeStart(configJson: String): Int

    /** Stop the engine and join its thread. */
    external fun nativeStop()

    /** Send opaque bytes over the QUIC connection(s). Returns 0 on success. */
    external fun nativeSend(data: ByteArray): Int

    /** Drain pending log lines / events, joined by U+001E. */
    external fun nativePoll(): String
}
