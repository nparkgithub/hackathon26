package ai.koog.multiverse

import ai.koog.multiverse.registry.Capabilities
import ai.koog.multiverse.registry.CapabilityEntry
import ai.koog.multiverse.registry.DeviceRole
import ai.koog.multiverse.registry.DeviceStatus
import ai.koog.multiverse.registry.Endpoint
import ai.koog.multiverse.registry.MpQuicSupport
import ai.koog.multiverse.routing.PreferRemotePolicy
import ai.koog.multiverse.routing.RoutingDiagnostics
import ai.koog.multiverse.routing.RoutingEngine
import ai.koog.multiverse.routing.SimulatedNetworkStatus
import ai.koog.multiverse.routing.Target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutingDiagnosticsTest {

    private val model = "llama3.2-vision"
    private val engine = RoutingEngine()
    private fun policy() = PreferRemotePolicy(model)

    private fun remote(id: String, gpuLoad: Double, reachable: Boolean = true) = CapabilityEntry(
        deviceId = id,
        role = DeviceRole.remote_agent,
        endpoint = Endpoint("10.0.3.2", 8443),
        capabilities = Capabilities(mpquic = MpQuicSupport.supported, models = listOf(model)),
        status = DeviceStatus(gpuLoad = gpuLoad, reachable = reachable),
    )

    private fun local(id: String, gpuLoad: Double) = CapabilityEntry(
        deviceId = id,
        role = DeviceRole.local_agent,
        endpoint = Endpoint("127.0.0.1", 11434),
        capabilities = Capabilities(mpquic = MpQuicSupport.unsupported, models = listOf(model)),
        status = DeviceStatus(gpuLoad = gpuLoad),
    )

    @Test
    fun tiersRankByGpuLoadAndDecisionMatchesEngineSelect() {
        val busyRemote = remote("busy-remote", 0.9)
        val idleRemote = remote("idle-remote", 0.1)
        val idleLocal = local("idle-local", 0.05)
        val snapshot = listOf(busyRemote, idleRemote, idleLocal)
        val network = SimulatedNetworkStatus(true, true)

        val report = RoutingDiagnostics.evaluate(snapshot, network, policy(), engine)
        val actual = engine.select(snapshot, network, policy())

        assertEquals(model, report.requestedModel)
        assertEquals(Target.REMOTE, report.decisionTarget)
        assertEquals(actual.deviceId, report.decisionDeviceId)
        assertEquals(actual.useMultipath, report.useMultipath)
        assertNull(report.error)

        val tier1 = report.tiers.single { it.tier == 1 }
        assertEquals(listOf("idle-remote", "busy-remote"), tier1.candidates.map { it.deviceId })
        assertTrue(tier1.candidates.single { it.deviceId == "idle-remote" }.selected)
        assertTrue(tier1.candidates.none { it.deviceId != "idle-remote" && it.selected })

        val tier2 = report.tiers.single { it.tier == 2 }
        assertEquals(listOf("idle-local"), tier2.candidates.map { it.deviceId })
        assertTrue(tier2.candidates.none { it.selected })
    }

    @Test
    fun fallsBackToLocalTierWhenNoRemoteAndTiersReflectIt() {
        val idleLocal = local("idle-local", 0.2)
        val snapshot = listOf(idleLocal)
        val network = SimulatedNetworkStatus(true, true)

        val report = RoutingDiagnostics.evaluate(snapshot, network, policy(), engine)

        assertEquals(Target.LOCAL, report.decisionTarget)
        assertEquals("idle-local", report.decisionDeviceId)
        assertTrue(report.tiers.single { it.tier == 1 }.candidates.isEmpty())
        assertTrue(report.tiers.single { it.tier == 2 }.candidates.single().selected)
    }

    @Test
    fun reportsErrorWhenNoTargetAvailable() {
        val report = RoutingDiagnostics.evaluate(emptyList(), SimulatedNetworkStatus(true, true), policy(), engine)

        assertNull(report.decisionTarget)
        assertNull(report.decisionDeviceId)
        assertTrue(report.error?.contains(model) == true)
        assertTrue(report.tiers.all { it.candidates.isEmpty() })
    }

    @Test
    fun tierThreeShowsAnyReachableFallbackEvenWhenUnusedByEngine() {
        // Remote wins tier 1, but tier 3 (any-reachable) still reports the same remote for visibility.
        val idleRemote = remote("idle-remote", 0.1)
        val report = RoutingDiagnostics.evaluate(
            listOf(idleRemote),
            SimulatedNetworkStatus(true, true),
            policy(),
            engine,
        )
        val tier3 = report.tiers.single { it.tier == 3 }
        assertEquals(listOf("idle-remote"), tier3.candidates.map { it.deviceId })
    }
}
