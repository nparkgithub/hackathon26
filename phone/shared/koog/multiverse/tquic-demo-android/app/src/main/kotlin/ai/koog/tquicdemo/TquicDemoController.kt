package ai.koog.tquicdemo

import ai.koog.http.client.tquic.TquicNative
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "tquic_demo"

/**
 * Single-peer session logic for both roles -- the simplified counterpart to
 * the deferred multi-device `TquicSessionManager` from the Koog integration
 * work. Owns its own `CoroutineScope`; every `TquicNative` call runs inside
 * `withContext(Dispatchers.IO)` regardless of which coroutine initiates it --
 * never `Dispatchers.Default` (a handful of blocked reads would starve the
 * whole pool) or Main.
 *
 * Client and server halves are independent (`startClient`/`stopClient` vs.
 * `startServer`/`stopServer`) -- nothing here enforces "one role at a time".
 * That's a deliberate choice for this scaffold: the underlying
 * `tquic-jni` reactor genuinely supports running both simultaneously in one
 * process (the client and server Endpoints are independent halves of the
 * same reactor thread), which is exactly what makes a same-device loopback
 * round trip possible as a fast, no-second-phone-needed proof before
 * testing across two real devices. The eventual polished UI (a later phase)
 * is what will enforce "pick one role" as a *product* decision, not this
 * class.
 */
class TquicDemoController(private val context: Context) {

    sealed interface SendStatus {
        data object Idle : SendStatus
        data object Sending : SendStatus
        data class Ok(val at: String) : SendStatus
        data class Failed(val reason: String) : SendStatus
    }

    data class ReceivedMessage(val at: String, val method: String, val path: String, val text: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // -- client state --
    private val _sendStatus = MutableStateFlow<SendStatus>(SendStatus.Idle)
    val sendStatus: StateFlow<SendStatus> = _sendStatus.asStateFlow()

    private val _clientRunning = MutableStateFlow(false)
    val clientRunning: StateFlow<Boolean> = _clientRunning.asStateFlow()

    @Volatile private var session: Long = 0
    private var sendJob: Job? = null

    // -- server state --
    private val _serverRunning = MutableStateFlow(false)
    val serverRunning: StateFlow<Boolean> = _serverRunning.asStateFlow()

    private val _receivedLog = MutableStateFlow<List<ReceivedMessage>>(emptyList())
    val receivedLog: StateFlow<List<ReceivedMessage>> = _receivedLog.asStateFlow()

    @Volatile private var server: Long = 0
    private var acceptJob: Job? = null

    // ================================================================================
    // Client
    // ================================================================================

    /**
     * Opens an outbound session to [peerIp]:[peerPort] and starts sending
     * whatever [message] currently returns, once per second. [message] is
     * read fresh on every tick -- editing the text in the UI mid-run takes
     * effect on the next send without needing to restart.
     */
    suspend fun startClient(peerIp: String, peerPort: Int, message: () -> String) {
        check(session == 0L) { "client already running -- call stopClient() first" }
        TquicNative.ensureLoaded()

        session = withContext(Dispatchers.IO) {
            TquicNative.openSession(
                remoteHost = peerIp,
                remotePort = peerPort,
                // Verification is off (verifyPeer=false, the "quick and insecure" TLS
                // decision), so this name is never checked against a certificate SAN --
                // it only shows up in logs/SNI. A real deployment using verifyPeer=true
                // would need this to match a name in the peer's cert.
                serverName = "tquic-demo-peer",
                alpn = "h3",
                primaryLocalAddr = "0.0.0.0:0",
                primaryFd = -1,
                enableMultipath = false,
                multipathAlgorithm = "minrtt",
                congestionControl = "bbr",
                idleTimeoutMs = 30_000,
                initialRttMs = 100,
                connectTimeoutMs = 10_000,
                caCertsPath = null,
                verifyPeer = false,
                clientCertPath = null,
                clientKeyPath = null,
                enableEarlyData = false,
            )
        }
        _clientRunning.value = true
        _sendStatus.value = SendStatus.Idle

        sendJob = scope.launch {
            while (isActive) {
                sendOnce(message())
                delay(1000)
            }
        }
    }

    /**
     * Cancels the send loop and waits for it to actually finish (a
     * possibly-in-flight `h3AwaitResponse`/`closeRequest` in [sendOnce]
     * included) before closing the session -- closing out from under a
     * still-running send would race the native handle registry, not just
     * "stop new work a little late".
     */
    suspend fun stopClient() {
        sendJob?.cancelAndJoin()
        sendJob = null
        val s = session
        session = 0
        if (s != 0L) {
            withContext(Dispatchers.IO) { TquicNative.closeSession(s) }
        }
        _clientRunning.value = false
        _sendStatus.value = SendStatus.Idle
    }

    private suspend fun sendOnce(text: String) {
        _sendStatus.value = SendStatus.Sending
        val bytes = text.toByteArray(Charsets.UTF_8)

        val req = try {
            withContext(Dispatchers.IO) {
                TquicNative.h3Request(
                    session, "POST", "/message",
                    arrayOf("content-type"), arrayOf("text/plain"), bytes,
                )
            }
        } catch (e: Exception) {
            // TquicNative throws several distinct types depending on failure kind
            // (TquicTransportException/TquicClosedException/... from jerr.rs's
            // ExcClass mapping, but also plain IllegalArgumentException/
            // IllegalStateException/RuntimeException for argument-validation and
            // panic cases, which are NOT TquicException subclasses) -- catch
            // broadly rather than assuming they're all one hierarchy.
            Log.w(TAG, "h3Request failed", e)
            _sendStatus.value = SendStatus.Failed(describe(e))
            return
        }

        try {
            val status = withContext(Dispatchers.IO) { TquicNative.h3AwaitResponse(req, 2000) }
            _sendStatus.value = when (status) {
                200 -> SendStatus.Ok(nowTime())
                TquicNative.TIMEOUT -> SendStatus.Failed("timeout awaiting response")
                TquicNative.CANCELLED -> SendStatus.Failed("cancelled")
                else -> SendStatus.Failed("peer returned status=$status")
            }
        } catch (e: Exception) {
            Log.w(TAG, "h3AwaitResponse failed", e)
            _sendStatus.value = SendStatus.Failed(describe(e))
        } finally {
            withContext(Dispatchers.IO) {
                runCatching { TquicNative.closeRequest(req) }
                    .onFailure { Log.w(TAG, "closeRequest failed (non-fatal)", it) }
            }
        }
    }

    // ================================================================================
    // Server
    // ================================================================================

    /**
     * Provisions the bundled cert/key and binds an inbound listener on
     * [bindPort]. Returns once the socket is bound; does not wait for any
     * connection.
     */
    suspend fun startServer(bindPort: Int) {
        check(server == 0L) { "server already running -- call stopServer() first" }
        TquicNative.ensureLoaded()

        val provisioned = withContext(Dispatchers.IO) { CertProvisioner.provision(context) }
        server = withContext(Dispatchers.IO) {
            TquicNative.serverStart(
                bindAddr = "0.0.0.0:$bindPort",
                certPath = provisioned.certPath,
                keyPath = provisioned.keyPath,
                alpn = "h3",
                caCertsPath = null,
                requireClientCert = false,
                idleTimeoutMs = 30_000,
                congestionControl = "bbr",
            )
        }
        _serverRunning.value = true

        acceptJob = scope.launch {
            while (isActive) {
                // Bounded: serverAccept's own timeout IS the cancellation point for
                // this loop -- no separate cross-thread cancel call is needed, unlike
                // a design that blocked indefinitely.
                val h = withContext(Dispatchers.IO) { TquicNative.serverAccept(server, 500) }
                if (h != 0L) {
                    // One coroutine per inbound request on the shared scope -- a slow
                    // or failing request must not stall the accept loop from picking
                    // up the next one.
                    scope.launch { handleRequest(h) }
                }
            }
        }
    }

    /**
     * Cancels the accept loop and waits for it to finish, then stops the
     * server. Any request handlers still in flight at that point will
     * observe [TquicNative.CANCELLED]/[TquicNative.EOF] from their native
     * calls and finish on their own via `handleRequest`'s `finally` -- this
     * does not forcibly join them, mirroring `serverStop`'s own documented
     * "graceful=false behaves like an immediate close" scope-3 simplification.
     */
    suspend fun stopServer() {
        acceptJob?.cancelAndJoin()
        acceptJob = null
        val s = server
        server = 0
        if (s != 0L) {
            withContext(Dispatchers.IO) { TquicNative.serverStop(s, false) }
        }
        _serverRunning.value = false
    }

    private suspend fun handleRequest(req: Long) {
        try {
            val buf = ByteArray(4096)
            val out = ByteArrayOutputStream()
            readLoop@ while (true) {
                val n = withContext(Dispatchers.IO) {
                    TquicNative.reqReadBody(req, buf, 0, buf.size, 5000)
                }
                when (n) {
                    TquicNative.EOF -> break@readLoop
                    TquicNative.TIMEOUT, TquicNative.CANCELLED -> break@readLoop
                    else -> out.write(buf, 0, n)
                }
            }
            val text = out.toString(Charsets.UTF_8.name())
            val method = withContext(Dispatchers.IO) {
                runCatching { TquicNative.reqMethod(req) }.getOrDefault("?")
            }
            val path = withContext(Dispatchers.IO) {
                runCatching { TquicNative.reqPath(req) }.getOrDefault("?")
            }
            Log.i(TAG, "inbound $method $path (${text.length} chars): $text")
            _receivedLog.value = listOf(ReceivedMessage(nowTime(), method, path, text)) + _receivedLog.value

            withContext(Dispatchers.IO) {
                TquicNative.respondHeaders(req, 200, emptyArray(), emptyArray(), true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleRequest failed", e)
        } finally {
            withContext(Dispatchers.IO) {
                runCatching { TquicNative.closeInboundRequest(req) }
                    .onFailure { Log.w(TAG, "closeInboundRequest failed (non-fatal)", it) }
            }
        }
    }

    // ================================================================================
    // VLM inference (tquic-vlm-server-interface)
    // ================================================================================

    sealed interface VlmStatus {
        data object Idle : VlmStatus
        data object Sending : VlmStatus
        data class Ok(val answer: String) : VlmStatus
        data class Failed(val reason: String) : VlmStatus
    }

    private val _vlmStatus = MutableStateFlow<VlmStatus>(VlmStatus.Idle)
    val vlmStatus: StateFlow<VlmStatus> = _vlmStatus.asStateFlow()

    /**
     * One-shot request/response, unlike [startClient]'s repeating session --
     * a VLM inference call is a single question and answer, not a heartbeat.
     * Opens its own session, sends one TLV-framed (`VlmFrames`) request to
     * `/v1/infer`, waits for the answer, and tears the session down again.
     */
    suspend fun sendVlmInference(peerIp: String, peerPort: Int, jpeg: ByteArray, prompt: String) {
        _vlmStatus.value = VlmStatus.Sending
        TquicNative.ensureLoaded()

        val session = withContext(Dispatchers.IO) {
            TquicNative.openSession(
                remoteHost = peerIp,
                remotePort = peerPort,
                // Same non-verifying posture as startClient() above -- the server's
                // cert is self-signed with no SAN, so this name is cosmetic only.
                serverName = "tquic-vlm-server-interface",
                alpn = "h3",
                primaryLocalAddr = "0.0.0.0:0",
                primaryFd = -1,
                enableMultipath = false,
                multipathAlgorithm = "minrtt",
                congestionControl = "bbr",
                idleTimeoutMs = 30_000,
                initialRttMs = 100,
                connectTimeoutMs = 10_000,
                caCertsPath = null,
                verifyPeer = false,
                clientCertPath = null,
                clientKeyPath = null,
                enableEarlyData = false,
            )
        }
        try {
            val body = VlmFrames.writeFrames(jpeg, prompt)
            val req = withContext(Dispatchers.IO) {
                TquicNative.h3Request(session, "POST", "/v1/infer", emptyArray(), emptyArray(), body)
            }
            try {
                // Real inference took ~5-10s in prior testing over this exact server;
                // generous margin, matching the server's own --vlm-timeout-ms default
                // of 120_000.
                val status = withContext(Dispatchers.IO) { TquicNative.h3AwaitResponse(req, 90_000) }
                if (status == 200) {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    readLoop@ while (true) {
                        val n = withContext(Dispatchers.IO) {
                            TquicNative.h3ReadBody(req, buf, 0, buf.size, 15_000)
                        }
                        when (n) {
                            TquicNative.EOF -> break@readLoop
                            TquicNative.TIMEOUT, TquicNative.CANCELLED -> break@readLoop
                            else -> out.write(buf, 0, n)
                        }
                    }
                    _vlmStatus.value = VlmStatus.Ok(out.toString(Charsets.UTF_8.name()))
                } else {
                    _vlmStatus.value = VlmStatus.Failed(
                        when (status) {
                            TquicNative.TIMEOUT -> "timeout awaiting response"
                            TquicNative.CANCELLED -> "cancelled"
                            else -> "server returned status=$status"
                        }
                    )
                }
            } finally {
                withContext(Dispatchers.IO) {
                    runCatching { TquicNative.closeRequest(req) }
                        .onFailure { Log.w(TAG, "closeRequest failed (non-fatal)", it) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendVlmInference failed", e)
            _vlmStatus.value = VlmStatus.Failed(describe(e))
        } finally {
            withContext(Dispatchers.IO) {
                runCatching { TquicNative.closeSession(session) }
                    .onFailure { Log.w(TAG, "closeSession failed (non-fatal)", it) }
            }
        }
    }

    private fun describe(e: Exception) = "${e::class.simpleName}: ${e.message}"

    private fun nowTime(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
}
