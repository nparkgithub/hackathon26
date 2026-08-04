package ai.koog.multiverse

import ai.koog.multiverse.registry.Capabilities
import ai.koog.multiverse.registry.CapabilityEntry
import ai.koog.multiverse.registry.DeviceRole
import ai.koog.multiverse.registry.DeviceStatus
import ai.koog.multiverse.registry.Endpoint
import ai.koog.multiverse.registry.MpQuicSupport
import ai.koog.multiverse.routing.PreferRemotePolicy
import ai.koog.multiverse.routing.RoutingEngine
import ai.koog.multiverse.routing.SimulatedNetworkStatus
import ai.koog.multiverse.routing.Target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutingEngineTest {

    private val model = "llama3.2-vision"

    private fun remote(mp: MpQuicSupport, reachable: Boolean = true) = CapabilityEntry(
        deviceId = "aws-remote-01",
        role = DeviceRole.remote_agent,
        endpoint = Endpoint("10.0.3.2", 8443),
        capabilities = Capabilities(mpquic = mp, models = listOf(model)),
        status = DeviceStatus(gpuLoad = 0.3, reachable = reachable),
    )

    private fun local() = CapabilityEntry(
        deviceId = "xelite-local",
        role = DeviceRole.local_agent,
        endpoint = Endpoint("127.0.0.1", 11434),
        capabilities = Capabilities(mpquic = MpQuicSupport.unsupported, models = listOf(model)),
    )

    private val engine = RoutingEngine()
    private fun policy() = PreferRemotePolicy(model)

    @Test
    fun remoteMultipathWhenSupportedAndBothPathsUp() {
        val d = engine.select(listOf(remote(MpQuicSupport.supported), local()), SimulatedNetworkStatus(true, true), policy())
        assertEquals(Target.REMOTE, d.target)
        assertTrue(d.useMultipath)
    }

    @Test
    fun remoteSinglePathWhenMpUnsupported() {
        val d = engine.select(listOf(remote(MpQuicSupport.unsupported), local()), SimulatedNetworkStatus(true, true), policy())
        assertEquals(Target.REMOTE, d.target)
        assertFalse(d.useMultipath)
    }

    @Test
    fun remoteSinglePathWhenOnlyWifiUp() {
        val d = engine.select(listOf(remote(MpQuicSupport.supported), local()), SimulatedNetworkStatus(wifiUp = true, g5Up = false), policy())
        assertEquals(Target.REMOTE, d.target)
        assertFalse(d.useMultipath)
    }

    @Test
    fun mpUnknownIsNotEligibleForMultipath() {
        val d = engine.select(listOf(remote(MpQuicSupport.unknown), local()), SimulatedNetworkStatus(true, true), policy())
        assertFalse(d.useMultipath)
    }

    @Test
    fun fallsBackToLocalWhenNoRemote() {
        val d = engine.select(listOf(local()), SimulatedNetworkStatus(true, true), policy())
        assertEquals(Target.LOCAL, d.target)
        assertFalse(d.useMultipath)
    }

    @Test
    fun throwsWhenNoTargetServesModel() {
        assertFailsWith<RoutingEngine.NoTargetAvailableException> {
            engine.select(emptyList(), SimulatedNetworkStatus(true, true), policy())
        }
    }

    @Test
    fun rankRemoteOrdersByAscendingGpuLoadAndMatchesChooseRemote() {
        val busy = remote(MpQuicSupport.supported).copy(deviceId = "busy-remote", status = DeviceStatus(gpuLoad = 0.9))
        val idle = remote(MpQuicSupport.supported).copy(deviceId = "idle-remote", status = DeviceStatus(gpuLoad = 0.1))
        val candidates = listOf(busy, idle)

        val ranked = policy().rankRemote(candidates)
        assertEquals(listOf("idle-remote", "busy-remote"), ranked.map { it.deviceId })
        assertEquals(ranked.firstOrNull(), policy().chooseRemote(candidates))
    }

    @Test
    fun rankLocalOrdersByAscendingGpuLoadAndMatchesChooseLocal() {
        val busy = local().copy(deviceId = "busy-local", status = DeviceStatus(gpuLoad = 0.8))
        val idle = local().copy(deviceId = "idle-local", status = DeviceStatus(gpuLoad = 0.2))
        val candidates = listOf(busy, idle)

        val ranked = policy().rankLocal(candidates)
        assertEquals(listOf("idle-local", "busy-local"), ranked.map { it.deviceId })
        assertEquals(ranked.firstOrNull(), policy().chooseLocal(candidates))
    }

    @Test
    fun rankRemoteExcludesUnreachableAndNonServingDevices() {
        val unreachable = remote(MpQuicSupport.supported, reachable = false)
        val wrongModel = remote(MpQuicSupport.supported).copy(
            deviceId = "other-model",
            capabilities = Capabilities(mpquic = MpQuicSupport.supported, models = listOf("other-model-name")),
        )
        assertTrue(policy().rankRemote(listOf(unreachable, wrongModel)).isEmpty())
    }
}
