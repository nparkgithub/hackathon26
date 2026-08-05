package com.mpquic.core

import android.content.Context
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Usable addresses of one network interface, both families. */
data class IfaceAddrs(val name: String, val ipv4: List<String>, val ipv6: List<String>) {
    val all: List<String> get() = ipv4 + ipv6
}

object NetUtils {

    /**
     * Interfaces used to auto-fill multipath local addresses: Wi-Fi (wlan*)
     * first — it becomes the initial path — then cellular (rmnet_data*).
     */
    val DEFAULT_IFACE_PREFIXES = listOf("wlan", "rmnet_data")

    /** All usable addresses of this device (both families), with interface names. */
    fun deviceAddresses(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (isUsableForPath(addr)) {
                        out.add(nif.name to stripScope(addr.hostAddress.orEmpty()))
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /**
     * Per-interface usable IPv4 + IPv6 addresses, restricted to interfaces
     * whose name starts with one of [prefixes], ordered by prefix (wlan
     * before rmnet_data by default) then name.
     */
    fun interfaceAddresses(prefixes: List<String> = DEFAULT_IFACE_PREFIXES): List<IfaceAddrs> {
        val out = mutableListOf<IfaceAddrs>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                if (prefixes.none { nif.name.startsWith(it) }) continue
                val v4 = mutableListOf<String>()
                val v6 = mutableListOf<String>()
                for (addr in nif.inetAddresses) {
                    if (!isUsableForPath(addr)) continue
                    val host = stripScope(addr.hostAddress.orEmpty())
                    when (addr) {
                        is Inet4Address -> v4.add(host)
                        is Inet6Address -> v6.add(host)
                    }
                }
                if (v4.isNotEmpty() || v6.isNotEmpty()) out.add(IfaceAddrs(nif.name, v4, v6))
            }
        } catch (_: Exception) {
        }
        return out.sortedWith(
            compareBy(
                { iface -> prefixes.indexOfFirst { iface.name.startsWith(it) } },
                { it.name },
            )
        )
    }

    /** Flatten [ifaces] into the local_addresses config list (v4 then v6 per iface). */
    fun defaultLocalAddresses(ifaces: List<IfaceAddrs>): List<String> =
        ifaces.flatMap { it.all }.distinct()

    /**
     * Whether an address can serve as a QUIC path source: loopback,
     * link-local (fe80::/10, 169.254/16), wildcard, and multicast addresses
     * are not routable to a remote peer.
     */
    fun isUsableForPath(addr: InetAddress): Boolean =
        !addr.isLoopbackAddress && !addr.isLinkLocalAddress &&
            !addr.isAnyLocalAddress && !addr.isMulticastAddress

    /** Drop the IPv6 zone id ("fe80::1%wlan0" -> "fe80::1"). */
    fun stripScope(host: String): String = host.substringBefore('%')

    /** Copy a bundled asset to filesDir and return its absolute path. */
    fun assetToFile(context: Context, assetName: String): String {
        val outFile = File(context.filesDir, assetName)
        context.assets.open(assetName).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        return outFile.absolutePath
    }
}
