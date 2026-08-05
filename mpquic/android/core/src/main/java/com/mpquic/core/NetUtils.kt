package com.mpquic.core

import android.content.Context
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {
    /** All non-loopback IPv4 addresses of this device, with interface names. */
    fun deviceIpv4Addresses(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address) {
                        out.add(nif.name to addr.hostAddress.orEmpty())
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
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
