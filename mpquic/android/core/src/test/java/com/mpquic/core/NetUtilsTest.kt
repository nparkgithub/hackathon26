package com.mpquic.core

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetUtilsTest {

    @Test
    fun stripScope_removesZoneId() {
        assertEquals("fe80::1", NetUtils.stripScope("fe80::1%wlan0"))
        assertEquals("2001:db8::7", NetUtils.stripScope("2001:db8::7%3"))
        assertEquals("192.168.1.5", NetUtils.stripScope("192.168.1.5"))
    }

    @Test
    fun isUsableForPath_rejectsNonRoutableAddresses() {
        for (bad in listOf("127.0.0.1", "::1", "fe80::1", "169.254.10.1", "0.0.0.0", "::")) {
            assertFalse(bad, NetUtils.isUsableForPath(InetAddress.getByName(bad)))
        }
        for (good in listOf("192.168.1.5", "10.60.0.2", "2001:db8::1", "2607:fb90::2")) {
            assertTrue(good, NetUtils.isUsableForPath(InetAddress.getByName(good)))
        }
    }

    @Test
    fun defaultLocalAddresses_flattensWlanFirstV4ThenV6() {
        val ifaces = listOf(
            IfaceAddrs("wlan0", listOf("192.168.1.7"), listOf("2001:db8::7")),
            IfaceAddrs("rmnet_data1", listOf("10.60.0.2"), listOf("2607:fb90::2")),
        )
        assertEquals(
            listOf("192.168.1.7", "2001:db8::7", "10.60.0.2", "2607:fb90::2"),
            NetUtils.defaultLocalAddresses(ifaces),
        )
    }

    @Test
    fun defaultLocalAddresses_excludesCarrierLocalRmnetV4() {
        val ifaces = listOf(
            // 192.x on wlan is a normal home LAN address — keep it.
            IfaceAddrs("wlan0", listOf("192.168.1.7"), emptyList()),
            // 192.x on rmnet_data is the CLAT/NAT-internal address — drop it.
            IfaceAddrs("rmnet_data0", listOf("192.0.0.2"), listOf("2607:fb90::2")),
        )
        assertEquals(
            listOf("192.168.1.7", "2607:fb90::2"),
            NetUtils.defaultLocalAddresses(ifaces),
        )
    }

    @Test
    fun parseIfconfigTx_readsToyboxFormat() {
        val sample = """
            |wlan0     Link encap:UNSPEC
            |          inet addr:10.73.51.71  Bcast:10.73.51.255  Mask:255.255.255.0
            |          UP BROADCAST RUNNING MULTICAST  MTU:1500  Metric:1
            |          RX packets:1000 errors:0 dropped:0 overruns:0 frame:0
            |          TX packets:900 errors:0 dropped:0 overruns:0 carrier:0
            |          RX bytes:123456 TX bytes:654321
            |
            |rmnet_data0 Link encap:UNSPEC
            |          inet addr:192.0.0.2  Mask:255.255.255.248
            |          RX bytes:111 TX bytes:2222
            |
            |lo        Link encap:Local Loopback
            |          RX bytes:5 TX bytes:5
        """.trimMargin()
        assertEquals(
            listOf("wlan0" to 654321L, "rmnet_data0" to 2222L),
            NetUtils.parseIfconfigTx(sample),
        )
    }

    @Test
    fun hostOf_handlesV4AndBracketedV6() {
        assertEquals("10.73.51.51", NetUtils.hostOf("10.73.51.51:4433"))
        assertEquals("::1", NetUtils.hostOf("[::1]:4433"))
        assertEquals("2607:fb90::2", NetUtils.hostOf("[2607:fb90::2]:52612"))
        assertEquals("0.0.0.0", NetUtils.hostOf("0.0.0.0:4433"))
    }

    @Test
    fun defaultLocalAddresses_deduplicates() {
        val ifaces = listOf(
            IfaceAddrs("wlan0", listOf("1.2.3.4"), emptyList()),
            IfaceAddrs("wlan1", listOf("1.2.3.4"), emptyList()),
        )
        assertEquals(listOf("1.2.3.4"), NetUtils.defaultLocalAddresses(ifaces))
    }
}
