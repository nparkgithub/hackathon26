package com.mpquic.core

import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * Wraps TquicBridge with a UI-thread poller that dispatches log lines and
 * structured events to listeners.
 */
class EngineController(
    private val onLog: (String) -> Unit,
    private val onEvent: (JSONObject) -> Unit,
) {
    companion object {
        /** ASCII record separator (0x1E), matching the Rust side. */
        private val RECORD_SEP = 30.toChar()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    private val pollTask = object : Runnable {
        override fun run() {
            if (!polling) return
            drain()
            handler.postDelayed(this, 200)
        }
    }

    /**
     * [cellularFd], when >= 0, is an already-bound, already-
     * `Network.bindSocket()`-authorized UDP socket fd for the cellular local
     * address; the engine reuses it instead of binding its own socket for
     * that path. Omit (or pass -1) for the normal single-network path.
     */
    fun start(configJson: String, cellularFd: Int = -1): Boolean {
        val rc = if (cellularFd >= 0) {
            TquicBridge.nativeStartWithCellularFd(configJson, cellularFd)
        } else {
            TquicBridge.nativeStart(configJson)
        }
        if (rc != 0) {
            drain()
            onLog("E: engine failed to start (rc=$rc)")
            return false
        }
        polling = true
        handler.post(pollTask)
        return true
    }

    fun stop() {
        TquicBridge.nativeStop()
        drain()
        polling = false
        handler.removeCallbacks(pollTask)
    }

    fun send(data: ByteArray): Boolean = TquicBridge.nativeSend(data) == 0

    /** Start the local HTTP/3 listener that tunnels requests over MPQUIC. */
    fun h3Listen(port: Int, certPath: String, keyPath: String): Boolean =
        TquicBridge.nativeH3Listen(port, certPath, keyPath) == 0

    /** Stop the local HTTP/3 listener. */
    fun h3Stop(): Boolean = TquicBridge.nativeH3Stop() == 0

    private fun drain() {
        val joined = TquicBridge.nativePoll()
        if (joined.isEmpty()) return
        for (line in joined.split(RECORD_SEP)) {
            if (line.isEmpty()) continue
            when {
                line.startsWith("L|") -> {
                    val rest = line.substring(2)
                    onLog(rest.replaceFirst("|", ": "))
                }
                line.startsWith("E|") -> {
                    try {
                        onEvent(JSONObject(line.substring(2)))
                    } catch (_: Exception) {
                        onLog("W: bad event: $line")
                    }
                }
                else -> onLog(line)
            }
        }
    }
}
