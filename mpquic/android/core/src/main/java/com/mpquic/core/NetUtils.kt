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
        ifaces
            .flatMap { iface -> iface.all.filterNot { isCarrierLocal(iface.name, it) } }
            .distinct()

    /**
     * Carrier-internal cellular addresses — 192.x.x.x on rmnet_data* (e.g.
     * the 464xlat CLAT address 192.0.0.2) — are NATed and not usable as a
     * multipath source toward external servers, so they are left out of the
     * default fill. They still appear in the interface status line and can
     * be typed into the (always editable) address field manually.
     */
    fun isCarrierLocal(ifaceName: String, ip: String): Boolean =
        ifaceName.startsWith("rmnet_data") && ip.startsWith("192.")

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

    /** Extract the host from "ip:port" / "[v6]:port" ("[::1]:443" -> "::1"). */
    fun hostOf(hostPort: String): String {
        val s = hostPort.trim()
        return if (s.startsWith("[")) {
            s.substringAfter('[').substringBefore(']')
        } else {
            s.substringBeforeLast(':')
        }
    }

    /** Interface label for a path endpoint: "wlan0", "any" for wildcard, "?" unknown. */
    fun ifaceLabelFor(hostPort: String): String {
        val host = hostOf(hostPort)
        if (host == "0.0.0.0" || host == "::") return "any"
        return ifaceNameForIp(host) ?: "?"
    }

    /** Name of the local interface owning [ip], or null if none matches. */
    fun ifaceNameForIp(ip: String): String? {
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                for (addr in nif.inetAddresses) {
                    if (stripScope(addr.hostAddress.orEmpty()) == ip) return nif.name
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * TX byte counters per interface as reported by the `ifconfig` binary
     * (per user request — no /sys//proc kernel-tree reads), restricted to
     * interfaces matching [prefixes]. Empty on any failure.
     */
    fun ifconfigTx(prefixes: List<String> = DEFAULT_IFACE_PREFIXES): List<Pair<String, Long>> =
        try {
            val proc = Runtime.getRuntime().exec("ifconfig")
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            parseIfconfigTx(out, prefixes)
        } catch (_: Exception) {
            emptyList()
        }

    /** Parse toybox `ifconfig` output into (interface, TX bytes) pairs. */
    fun parseIfconfigTx(
        output: String,
        prefixes: List<String> = DEFAULT_IFACE_PREFIXES,
    ): List<Pair<String, Long>> {
        val result = mutableListOf<Pair<String, Long>>()
        var current: String? = null
        for (line in output.lines()) {
            if (line.isNotEmpty() && !line[0].isWhitespace()) {
                current = line.substringBefore(' ').trim().removeSuffix(":")
            }
            val m = TX_BYTES_RE.find(line) ?: continue
            val iface = current ?: continue
            if (prefixes.any { iface.startsWith(it) }) {
                result.add(iface to m.groupValues[1].toLong())
            }
        }
        return result
    }

    private val TX_BYTES_RE = Regex("""TX bytes:(\d+)""")

    /**
     * One-line per-interface TX summary. Tries `ifconfig` first; on modern
     * Android SELinux denies /proc/net/dev to apps (which ifconfig reads
     * under the hood), so fall back to the public TrafficStats API:
     * mobile = all rmnet traffic, wifi = total - mobile.
     */
    fun ifaceTxSummary(): String {
        val tx = ifconfigTx()
        if (tx.isNotEmpty()) {
            return "ifconfig TX: " + tx.joinToString(", ") { (n, b) -> "$n=$b B" }
        }
        val mobile = android.net.TrafficStats.getMobileTxBytes()
        val total = android.net.TrafficStats.getTotalTxBytes()
        if (total <= 0) return "interface TX counters unavailable"
        val wifi = (total - mobile.coerceAtLeast(0)).coerceAtLeast(0)
        return "TX since boot (TrafficStats; ifconfig blocked by Android): " +
            "wifi=$wifi B, mobile/rmnet=${mobile.coerceAtLeast(0)} B"
    }

    /** Copy a bundled asset to filesDir and return its absolute path. */
    fun assetToFile(context: Context, assetName: String): String {
        val outFile = File(context.filesDir, assetName)
        context.assets.open(assetName).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        return outFile.absolutePath
    }
}
