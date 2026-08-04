package ai.koog.multiverse.routing

import ai.koog.multiverse.registry.CapabilityEntry

/** One ranked candidate within a [PolicyTier], with whether it's the tier's/overall winner. */
data class RankedCandidate(val deviceId: String, val gpuLoad: Double, val selected: Boolean)

/** A single fallback tier of the routing policy, fully ranked (best first). */
data class PolicyTier(val tier: Int, val name: String, val candidates: List<RankedCandidate>)

/** Full priority-order report for the current registry snapshot, plus the actual routing decision. */
data class RoutingDiagnosticsReport(
    val requestedModel: String,
    val wifiUp: Boolean,
    val g5Up: Boolean,
    val tiers: List<PolicyTier>,
    val decisionTarget: Target?,
    val decisionDeviceId: String?,
    val useMultipath: Boolean,
    val error: String?,
)

/**
 * Verification view over the real routing logic (grillme_version2 routing menu). Composes
 * [RoutingPolicy.rankRemote]/[RoutingPolicy.rankLocal]/[RoutingEngine.anyReachableServing] into the
 * full ranked priority order for every fallback tier, and cross-references the actual
 * [RoutingEngine.select] decision to mark the winner — so this view can never disagree with the real
 * routing outcome.
 */
object RoutingDiagnostics {

    fun evaluate(
        snapshot: List<CapabilityEntry>,
        network: NetworkStatus,
        policy: RoutingPolicy,
        engine: RoutingEngine,
    ): RoutingDiagnosticsReport {
        val decision = runCatching { engine.select(snapshot, network, policy) }
        val selectedDeviceId = decision.getOrNull()?.deviceId

        val tiers = listOf(
            PolicyTier(1, "remote_preferred", rank(policy.rankRemote(snapshot), selectedDeviceId)),
            PolicyTier(2, "local_fallback", rank(policy.rankLocal(snapshot), selectedDeviceId)),
            PolicyTier(
                3,
                "any_reachable_fallback",
                rank(RoutingEngine.anyReachableServing(snapshot, policy.requestedModel), selectedDeviceId),
            ),
        )

        return RoutingDiagnosticsReport(
            requestedModel = policy.requestedModel,
            wifiUp = network.wifiUp,
            g5Up = network.g5Up,
            tiers = tiers,
            decisionTarget = decision.getOrNull()?.target,
            decisionDeviceId = selectedDeviceId,
            useMultipath = decision.getOrNull()?.useMultipath ?: false,
            error = decision.exceptionOrNull()?.message,
        )
    }

    private fun rank(candidates: List<CapabilityEntry>, selectedDeviceId: String?): List<RankedCandidate> =
        candidates.map { RankedCandidate(it.deviceId, it.status.gpuLoad, it.deviceId == selectedDeviceId) }
}
