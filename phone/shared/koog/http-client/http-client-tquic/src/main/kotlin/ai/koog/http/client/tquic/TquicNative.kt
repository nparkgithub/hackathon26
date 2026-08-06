package ai.koog.http.client.tquic

/**
 * JNI surface to the Rust `tquic-jni` wrapper crate. This declares the ABI that
 * `native/tquic-jni/src/lib.rs` actually implements (confirmed against the prebuilt
 * `libtquic_jni.so` staged in `native/prebuilt/android/`) -- a prior version of this file
 * declared an older, smaller, never-built stub ABI that `TquicDemoController.kt`/
 * `MainActivity.kt` had already moved past, which broke compilation the moment those
 * files were built for real. `ABI_VERSION` in `lib.rs` and [EXPECTED_ABI_VERSION] below
 * must be bumped together on any further signature/semantic change.
 */
object TquicNative {

    const val EOF = -1
    const val TIMEOUT = -2
    const val CANCELLED = -3

    /** Checked against `lib.rs::ABI_VERSION` immediately after load, in [tryLoad]. */
    const val EXPECTED_ABI_VERSION = 1

    @Volatile
    private var loaded = false

    @Volatile
    var lastLoadError: Throwable? = null
        private set

    /** Attempt to load libtquic_jni; returns false (and sets [lastLoadError]) if unavailable. */
    @Synchronized
    fun tryLoad(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("tquic_jni")
            val actual = abiVersion()
            check(actual == EXPECTED_ABI_VERSION) {
                "tquic_jni ABI mismatch: this app expects $EXPECTED_ABI_VERSION, loaded library reports $actual"
            }
            loaded = true
            true
        } catch (t: Throwable) {
            lastLoadError = t
            false
        }
    }

    fun ensureLoaded() {
        check(tryLoad()) {
            "tquic_jni native library not available: ${lastLoadError?.let { "${it::class.simpleName}: ${it.message}" }}"
        }
    }

    // ================================================================================
    // Diagnostics
    // ================================================================================

    external fun abiVersion(): Int
    external fun abiInfo(): String
    external fun version(): String

    // ================================================================================
    // Client
    // ================================================================================

    /** Open an outbound H3 session. Returns an opaque session handle (>0) or throws. */
    external fun openSession(
        remoteHost: String,
        remotePort: Int,
        serverName: String,
        alpn: String,
        primaryLocalAddr: String,
        primaryFd: Int,
        enableMultipath: Boolean,
        multipathAlgorithm: String,
        congestionControl: String,
        idleTimeoutMs: Long,
        initialRttMs: Long,
        connectTimeoutMs: Long,
        caCertsPath: String?,
        verifyPeer: Boolean,
        clientCertPath: String?,
        clientKeyPath: String?,
        enableEarlyData: Boolean,
    ): Long

    /** Add an extra local address as a path to an open session. */
    external fun addPath(session: Long, localAddr: String, fd: Int): Boolean

    external fun closeSession(session: Long)

    /** Send one H3 request (headers + optional body, sent whole/up-front). Returns a request handle. */
    external fun h3Request(
        session: Long,
        method: String,
        pathAndQuery: String,
        headerNames: Array<String>,
        headerValues: Array<String>,
        body: ByteArray?,
    ): Long

    /** Blocks (up to [timeoutMs]) for response headers. Returns the status code, [TIMEOUT], or [CANCELLED]. */
    external fun h3AwaitResponse(request: Long, timeoutMs: Long): Int

    /** Flattened name/value pairs, valid once [h3AwaitResponse] has returned a status. */
    external fun h3ResponseHeaders(request: Long): Array<String>

    /** Reads up to `len` bytes. Returns bytes read, [EOF], [TIMEOUT], or [CANCELLED]. */
    external fun h3ReadBody(request: Long, buf: ByteArray, off: Int, len: Int, timeoutMs: Long): Int

    external fun cancelRequest(request: Long)
    external fun closeRequest(request: Long)

    // ================================================================================
    // Server
    // ================================================================================

    external fun serverStart(
        bindAddr: String,
        certPath: String,
        keyPath: String,
        alpn: String,
        caCertsPath: String?,
        requireClientCert: Boolean,
        idleTimeoutMs: Long,
        congestionControl: String,
    ): Long

    /** Blocks (up to [timeoutMs]) for an inbound request. Returns a request handle, or 0 on timeout. */
    external fun serverAccept(server: Long, timeoutMs: Long): Long

    external fun reqMethod(request: Long): String
    external fun reqPath(request: Long): String
    external fun reqHeaders(request: Long): Array<String>

    /** Reads up to `len` bytes of the inbound request body. Returns bytes read, [EOF], [TIMEOUT], or [CANCELLED]. */
    external fun reqReadBody(request: Long, buf: ByteArray, off: Int, len: Int, timeoutMs: Long): Int

    external fun respondHeaders(
        request: Long,
        status: Int,
        headerNames: Array<String>,
        headerValues: Array<String>,
        fin: Boolean,
    )

    /** Returns bytes actually accepted (bounded by QUIC flow control) -- callers retry any remainder. */
    external fun respondBody(request: Long, buf: ByteArray, off: Int, len: Int, fin: Boolean): Int

    external fun closeInboundRequest(request: Long)
    external fun serverStop(server: Long, graceful: Boolean)
}
