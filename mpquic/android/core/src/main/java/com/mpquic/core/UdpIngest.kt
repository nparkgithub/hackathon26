package com.mpquic.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * Listens on a local UDP port and hands every received datagram's payload to
 * [onData] (called on the receive thread, with the sender "ip:port"), so the
 * app can inject it into the QUIC connection. Plain UDP in, MPQUIC out.
 *
 * Robustness notes: bind failures (port in use, privileged port) are reported
 * via [onLog] and start() returns false; stop() closes the socket, which
 * unblocks the receive loop deterministically; unexpected receive errors are
 * logged and tolerated up to a small consecutive-error cap so a broken socket
 * cannot spin the thread. reuseAddress is intentionally NOT set so a port
 * conflict fails loudly instead of silently splitting traffic.
 */
class UdpIngest(
    private val onData: (ByteArray, String) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    val isRunning: Boolean
        @Synchronized get() = socket != null

    /** Actual bound port (useful when starting with port 0), or -1. */
    val localPort: Int
        @Synchronized get() = socket?.localPort ?: -1

    /** Bind [port] and start the receive loop. Idempotent while running. */
    @Synchronized
    fun start(port: Int): Boolean {
        if (socket != null) return true
        val sock = try {
            DatagramSocket(null).apply { bind(InetSocketAddress(port)) }
        } catch (e: Exception) {
            onLog("E: UDP bind failed on port $port: ${e.message}")
            return false
        }
        socket = sock
        thread = Thread {
            val buf = ByteArray(MAX_DATAGRAM)
            val pkt = DatagramPacket(buf, buf.size)
            var consecutiveErrors = 0
            while (true) {
                try {
                    pkt.setData(buf, 0, buf.size)
                    sock.receive(pkt)
                    consecutiveErrors = 0
                    if (pkt.length > 0) {
                        val data = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
                        onData(data, "${pkt.address.hostAddress}:${pkt.port}")
                    }
                } catch (e: SocketException) {
                    // stop() closed the socket — normal shutdown.
                    break
                } catch (e: Exception) {
                    consecutiveErrors++
                    onLog("E: UDP receive: ${e.message}")
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        onLog("E: UDP ingest giving up after $consecutiveErrors errors")
                        break
                    }
                }
            }
        }.apply {
            name = "udp-ingest"
            isDaemon = true
            start()
        }
        return true
    }

    /** Close the socket and join the receive thread. Safe to call twice. */
    fun stop() {
        val (sock, th) = synchronized(this) {
            val pair = socket to thread
            socket = null
            thread = null
            pair
        }
        try {
            sock?.close()
        } catch (_: Exception) {
        }
        try {
            th?.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val MAX_DATAGRAM = 65535
        const val MAX_CONSECUTIVE_ERRORS = 16
    }
}
