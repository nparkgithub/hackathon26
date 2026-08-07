package com.mpquic.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper

/**
 * Watches Wi-Fi and cellular connectivity and reports the usable
 * wlan* / rmnet_data* addresses (IPv4 + IPv6) whenever they change.
 *
 * Besides observing, it *requests* the cellular network: without an active
 * request Android parks cellular while Wi-Fi is the default route, so
 * rmnet_data would lose its addresses and the second multipath path would
 * have nothing to bind to.
 *
 * [onChange] is delivered on the main thread.
 */
class NetworkMonitor(
    context: Context,
    private val onChange: (List<IfaceAddrs>) -> Unit,
) {
    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
    private var started = false

    /**
     * The live cellular [Network] handle, if the held cellular request
     * currently has one. `requestNetwork()` keeps rmnet's addresses alive so
     * they show up in [onChange], but a plain socket still won't route over
     * it while Wi-Fi is default -- callers that need to actually send on this
     * network must call `network.bindSocket(...)` on this object themselves
     * (see `MainActivity`'s cellular-path handling).
     */
    @Volatile
    var cellularNetwork: Network? = null
        private set

    private val rescan = Runnable { onChange(NetUtils.interfaceAddresses()) }

    fun start() {
        if (started) return
        started = true

        // Observe both transports for address/availability changes.
        for (transport in intArrayOf(
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_CELLULAR,
        )) {
            register(NetworkRequest.Builder().addTransportType(transport).build(), request = false)
        }
        // Hold cellular up alongside Wi-Fi, and track its Network handle so
        // callers can bindSocket() onto it.
        val cellularCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellularNetwork = network
                refresh()
            }
            override fun onLost(network: Network) {
                if (cellularNetwork == network) cellularNetwork = null
                refresh()
            }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = refresh()
        }
        try {
            cm.requestNetwork(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cellularCb,
            )
            callbacks.add(cellularCb)
        } catch (_: Exception) {
        }
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(rescan)
        for (cb in callbacks) {
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
        callbacks.clear()
        cellularNetwork = null
    }

    /** Re-enumerate now (debounced, on the main thread). */
    fun refresh() {
        handler.removeCallbacks(rescan)
        handler.postDelayed(rescan, DEBOUNCE_MS)
    }

    private fun register(req: NetworkRequest, request: Boolean) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = refresh()
        }
        try {
            if (request) cm.requestNetwork(req, cb) else cm.registerNetworkCallback(req, cb)
            callbacks.add(cb)
        } catch (_: Exception) {
            // e.g. missing permission or airplane mode edge cases — the UI
            // still gets the initial one-shot scan from refresh().
        }
    }

    private companion object {
        // Interface addresses can settle slightly after the callback fires.
        const val DEBOUNCE_MS = 300L
    }
}
