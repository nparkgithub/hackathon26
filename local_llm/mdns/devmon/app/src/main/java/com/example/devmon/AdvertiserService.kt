package com.example.devmon

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Advertises `_devmon._tcp` over mDNS and accepts telemetry streams from desktop peers.
 *
 * The socket is bound to port 0 (kernel picks a free port) *before* registering, because
 * the mDNS SRV record must carry the real port. NsdManager rewrites the service name on
 * collision, so the registered name is read back from the callback rather than assumed.
 */
class AdvertiserService(context: Context) {

    companion object {
        private const val TAG = "DevMon"
        const val SERVICE_TYPE = "_devmon._tcp."

        /** Fixed TCP port so peers can find this service by subnet-scanning when
         * mDNS multicast can't get through (e.g. AP client isolation). Must match
         * SCAN_PORT_DEFAULT in discover_and_report.py. */
        const val FIXED_PORT = 47531
    }

    sealed interface State {
        object Idle : State
        object Registering : State
        data class Advertising(val serviceName: String, val port: Int) : State
        data class Failed(val reason: String) : State
    }

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** Latest snapshot per peer host, keyed by remote address. */
    private val _peers = MutableStateFlow<Map<String, Telemetry>>(emptyMap())
    val peers: StateFlow<Map<String, Telemetry>> = _peers

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null

    private fun logLine(msg: String) {
        val line = "[${timeFmt.format(Date())}] $msg"
        Log.i(TAG, line)
        _log.value = (_log.value + line).takeLast(200)
    }

    fun start() {
        if (_state.value is State.Advertising || _state.value is State.Registering) return
        _state.value = State.Registering

        val sock = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(FIXED_PORT)) }
        } catch (e: IOException) {
            _state.value = State.Failed("bind failed: ${e.message}")
            return
        }
        server = sock
        val port = sock.localPort
        logLine("TCP server listening on port $port")

        scope.launch { acceptLoop(sock) }
        register(port)
    }

    private fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "devmon-${android.os.Build.MODEL}".replace(Regex("[^A-Za-z0-9-]"), "-")
            serviceType = SERVICE_TYPE
            setPort(port)
            // TXT records: readable by the desktop side without opening a connection.
            setAttribute("role", "android-advertiser")
            setAttribute("model", android.os.Build.MODEL)
            setAttribute("sdk", android.os.Build.VERSION.SDK_INT.toString())
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                // Name may differ from requested if it collided on the network.
                _state.value = State.Advertising(info.serviceName, port)
                logLine("registered as '${info.serviceName}' $SERVICE_TYPE port $port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                _state.value = State.Failed("registration failed (code $errorCode)")
                logLine("registration FAILED code=$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                _state.value = State.Idle
                logLine("unregistered '${info.serviceName}'")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                logLine("unregistration failed code=$errorCode")
            }
        }
        registration = listener
        logLine("sending advertisement: '${info.serviceName}' $SERVICE_TYPE port $port")
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private suspend fun acceptLoop(sock: ServerSocket) {
        while (!sock.isClosed) {
            val client = try {
                sock.accept()
            } catch (e: IOException) {
                if (!sock.isClosed) logLine("accept error: ${e.message}")
                return  // socket closed by stop()
            }
            scope.launch { readPeer(client) }
        }
    }

    /** Reads newline-delimited JSON frames until the peer disconnects. */
    private fun readPeer(client: Socket) {
        val who = client.inetAddress?.hostAddress ?: "unknown"
        logLine("peer connected: $who")
        try {
            client.keepAlive = true
            client.getInputStream().bufferedReader().use { reader: BufferedReader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    try {
                        val t = Telemetry.from(line)
                        _peers.value = _peers.value + (who to t)
                    } catch (e: Exception) {
                        logLine("bad frame from $who: ${e.message}")
                    }
                }
            }
        } catch (e: IOException) {
            logLine("peer $who dropped: ${e.message}")
        } finally {
            runCatching { client.close() }
            _peers.value = _peers.value - who
            logLine("peer disconnected: $who")
        }
    }

    fun stop() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        runCatching { server?.close() }
        server = null
        _peers.value = emptyMap()
        _state.value = State.Idle
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }
}
