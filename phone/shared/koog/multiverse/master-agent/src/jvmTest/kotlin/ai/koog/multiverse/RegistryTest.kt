package ai.koog.multiverse

import ai.koog.multiverse.registry.CapabilityRegistry
import ai.koog.multiverse.registry.Capabilities
import ai.koog.multiverse.registry.CapabilityEntry
import ai.koog.multiverse.registry.DeviceRole
import ai.koog.multiverse.registry.Endpoint
import ai.koog.multiverse.registry.MpQuicSupport
import ai.koog.multiverse.discovery.StaticConfigDiscovery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistryTest {

    private fun entry(id: String, ttl: Long, mp: MpQuicSupport = MpQuicSupport.supported) = CapabilityEntry(
        deviceId = id,
        role = DeviceRole.remote_agent,
        endpoint = Endpoint("10.0.3.2", 8443),
        capabilities = Capabilities(mpquic = mp, models = listOf("llama3.2-vision")),
        ttlExpiresAt = ttl,
    )

    @Test
    fun snapshotPrunesExpiredEntries() {
        var now = 1_000L
        val reg = CapabilityRegistry(clock = { now })
        reg.register(entry("live", ttl = 5_000))
        reg.register(entry("expired", ttl = 2_000))

        now = 3_000L // "expired" (ttl 2000) is now past; "live" (5000) remains
        val snap = reg.snapshot()
        assertEquals(listOf("live"), snap.map { it.deviceId })
        assertNull(reg.get("expired"))
    }

    @Test
    fun findByModelFiltersReachableAndModel() {
        val reg = CapabilityRegistry(clock = { 0 })
        reg.register(entry("a", ttl = 0)) // ttl 0 = never expires
        val found = reg.findByModel("llama3.2-vision")
        assertEquals(1, found.size)
        assertTrue(found.first().supportsMpQuic())
    }

    @Test
    fun staticDiscoveryLoadsResourceRegistry() {
        val discovery = StaticConfigDiscovery.fromResource("registry.json") { 10_000 }
        val devices = discovery.discover()
        assertEquals(setOf("aws-remote-01", "xelite-local"), devices.map { it.deviceId }.toSet())
        val aws = devices.first { it.deviceId == "aws-remote-01" }
        assertTrue(aws.supportsMpQuic())
        assertTrue(aws.ttlExpiresAt > 10_000) // relative ttlSeconds converted to absolute epoch-ms
    }
}
