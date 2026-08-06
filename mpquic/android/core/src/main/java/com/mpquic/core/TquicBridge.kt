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

    /**
     * Start a local HTTP/3 listener on [port]; requests it receives are
     * tunneled over the MPQUIC connection and the peer's responses are
     * returned to the HTTP/3 client. Returns 0 on success.
     */
    external fun nativeH3Listen(port: Int, certPath: String, keyPath: String): Int

    /** Stop the local HTTP/3 listener. Returns 0 on success. */
    external fun nativeH3Stop(): Int
}
