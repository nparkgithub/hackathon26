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
    fun defaultLocalAddresses_deduplicates() {
        val ifaces = listOf(
            IfaceAddrs("wlan0", listOf("1.2.3.4"), emptyList()),
            IfaceAddrs("wlan1", listOf("1.2.3.4"), emptyList()),
        )
        assertEquals(listOf("1.2.3.4"), NetUtils.defaultLocalAddresses(ifaces))
    }
}
