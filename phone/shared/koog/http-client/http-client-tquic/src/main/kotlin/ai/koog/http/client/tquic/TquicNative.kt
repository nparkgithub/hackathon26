package ai.koog.http.client.tquic

/**
 * JNI surface to the Rust `tquic-jni` wrapper crate (which links the full tquic `lib`, so PATFB /
 * ThleV2 / file-size scheduling are reachable). This declares the native methods only; the Rust
 * implementation (`libtquic_jni.so`) is a separate task and is NOT built yet.
 *
 * Until the library is present, [ensureLoaded] fails fast with a clear message so callers surface a
 * meaningful "TQUIC bridge not yet implemented" error instead of an obscure UnsatisfiedLinkError.
 */
internal object TquicNative {

    @Volatile
    private var loaded = false

    /** Attempt to load libtquic_jni; returns false if unavailable (expected until Rust is built). */
    @Synchronized
    fun tryLoad(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("tquic_jni")
            loaded = true
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun ensureLoaded() {
        check(tryLoad()) {
            "TQUIC bridge not yet implemented: native library 'tquic_jni' is not available. " +
                "Build the Rust tquic-jni crate and place libtquic_jni.so on java.library.path."
        }
    }

    // --- Native methods (implemented in Rust tquic-jni; signatures are the intended ABI) ---

    /** Open a multipath H3 session. Returns an opaque session handle (>0) or throws. */
    external fun openSession(
        remoteHost: String,
        remotePort: Int,
        serverName: String,
        alpn: String,
        primaryLocalAddr: String,
        enableMultipath: Boolean,
        multipathAlgorithm: String,
        enablePatfb: Boolean,
        fileSizeMpScheduler: Long,
        congestionControl: String,
        idleTimeoutMs: Long,
        initialRttMs: Long,
    ): Long

    /** Add an extra local address as a path to an open session. */
    external fun addPath(session: Long, localAddr: String): Boolean

    /** Send one H3 request (headers + optional body, fin). Returns an opaque request handle. */
    external fun h3Request(
        session: Long,
        method: String,
        path: String,
        headerNames: Array<String>,
        headerValues: Array<String>,
        body: ByteArray?,
    ): Long

    /** Read up to buf.size bytes of the response body. Returns bytes read, 0 on EOF, -1 on would-block. */
    external fun h3ReadBody(request: Long, buf: ByteArray): Int

    /** Response status code for a completed request handle. */
    external fun h3ResponseStatus(request: Long): Int

    external fun closeRequest(request: Long)
    external fun closeSession(session: Long)
}
