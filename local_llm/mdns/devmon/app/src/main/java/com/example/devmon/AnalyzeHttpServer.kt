package com.example.devmon

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Hand-rolled HTTP server for the DevMon `/analyze` + `/health` contract, bound to loopback only.
 *
 * Mirrors [AdvertiserService]'s `ServerSocket`/coroutine-per-connection style rather than pulling
 * in an HTTP framework - see docs/devmon-http-api-implementation-plan.md for why.
 */
class AnalyzeHttpServer(private val peersProvider: () -> Map<String, Telemetry>) {

    companion object {
        private const val TAG = "DevMon"
        const val PORT = 47532
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        val sock = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("127.0.0.1", PORT)) }
        } catch (e: IOException) {
            Log.w(TAG, "AnalyzeHttpServer bind failed: ${e.message}")
            return
        }
        server = sock
        Log.i(TAG, "AnalyzeHttpServer listening on 127.0.0.1:$PORT")
        scope.launch { acceptLoop(sock) }
    }

    private suspend fun acceptLoop(sock: ServerSocket) {
        while (!sock.isClosed) {
            val client = try {
                sock.accept()
            } catch (e: IOException) {
                if (!sock.isClosed) Log.w(TAG, "AnalyzeHttpServer accept error: ${e.message}")
                return
            }
            scope.launch { handleConnection(client) }
        }
    }

    private suspend fun handleConnection(socket: Socket) {
        socket.use {
            val response = try {
                val request = readHttpRequest(it.getInputStream())
                routeRequest(request, peersProvider, OpenAiAnalysisClient::analyze)
            } catch (e: Exception) {
                Log.w(TAG, "AnalyzeHttpServer request failed: ${e.message}")
                HttpResponse(
                    AnalyzeErrorCode.BAD_REQUEST.httpStatus,
                    "application/json",
                    buildErrorJson(AnalyzeErrorCode.BAD_REQUEST, "Malformed request"),
                )
            }
            try {
                it.getOutputStream().write(writeHttpResponse(response))
            } catch (e: IOException) {
                Log.w(TAG, "AnalyzeHttpServer write failed: ${e.message}")
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }
}
