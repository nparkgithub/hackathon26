package com.mpquic.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpIngestTest {

    @Test
    fun receivesDatagramsAndStopsCleanly() {
        val received = LinkedBlockingQueue<Pair<ByteArray, String>>()
        val ingest = UdpIngest(onData = { d, f -> received.offer(d to f) }, onLog = {})
        try {
            assertTrue(ingest.start(0))
            val port = ingest.localPort
            assertTrue("expected a bound port, got $port", port > 0)

            DatagramSocket().use { s ->
                for (msg in listOf("hello-udp", "second")) {
                    val payload = msg.toByteArray()
                    s.send(
                        DatagramPacket(payload, payload.size, InetAddress.getLoopbackAddress(), port)
                    )
                }
            }
            val first = received.poll(5, TimeUnit.SECONDS)
            assertNotNull("no datagram received", first)
            assertArrayEquals("hello-udp".toByteArray(), first!!.first)
            val second = received.poll(5, TimeUnit.SECONDS)
            assertNotNull("second datagram lost", second)
            assertArrayEquals("second".toByteArray(), second!!.first)
        } finally {
            ingest.stop()
        }
        assertFalse(ingest.isRunning)
        assertEquals(-1, ingest.localPort)

        // Restart after stop must work.
        assertTrue(ingest.start(0))
        assertTrue(ingest.localPort > 0)
        ingest.stop()
    }

    @Test
    fun bindConflictFailsLoudly() {
        val logs = LinkedBlockingQueue<String>()
        val a = UdpIngest({ _, _ -> }, {})
        try {
            assertTrue(a.start(0))
            val b = UdpIngest({ _, _ -> }, { logs.offer(it) })
            assertFalse("second bind on same port must fail", b.start(a.localPort))
            assertTrue(logs.poll(2, TimeUnit.SECONDS)?.contains("bind failed") == true)
        } finally {
            a.stop()
        }
    }

    @Test
    fun stopIsIdempotentAndStopWithoutStartIsSafe() {
        val ingest = UdpIngest({ _, _ -> }, {})
        ingest.stop()
        assertTrue(ingest.start(0))
        ingest.stop()
        ingest.stop()
        assertFalse(ingest.isRunning)
    }
}
