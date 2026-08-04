package ai.koog.multiverse.routing

import ai.koog.multiverse.registry.CapabilityEntry

/**
 * Chooses the execution target and whether multipath is *eligible* (grillme_version2 Sec 9). Preserves
 * the PDF separation principle: the engine decides target + eligibility; TQUIC decides which paths.
 *
 *   useMultipath = target.mpquic == supported && net.wifiUp && net.g5Up
 *
 * Fallback order: prefer-remote (policy) -> local -> any reachable remote -> throw if nothing.
 */
class RoutingEngine {

    class NoTargetAvailableException(model: String) :
        RuntimeException("No healthy compute target for model '$model'")

    fun select(
        snapshot: List<CapabilityEntry>,
        network: NetworkStatus,
        policy: RoutingPolicy,
    ): RouteDecision {
        policy.chooseRemote(snapshot)?.let { remote ->
            return remoteDecision(remote, policy.requestedModel, network)
        }
        policy.chooseLocal(snapshot)?.let { local ->
            return localDecision(local, policy.requestedModel)
        }
        // Last resort: any reachable device advertising the model, even if policy skipped it.
        anyReachableServing(snapshot, policy.requestedModel).firstOrNull()
            ?.let { return remoteDecision(it, policy.requestedModel, network) }

        throw NoTargetAvailableException(policy.requestedModel)
    }

    private fun remoteDecision(entry: CapabilityEntry, model: String, network: NetworkStatus) =
        RouteDecision(
            target = Target.REMOTE,
            deviceId = entry.deviceId,
            model = model,
            endpointHost = entry.endpoint.host,
            endpointPort = entry.endpoint.port,
            useMultipath = entry.supportsMpQuic() && network.bothPathsUp(),
            localAddresses = entry.interfaces.map { it.ip },
        )

    private fun localDecision(entry: CapabilityEntry, model: String) =
        RouteDecision(
            target = Target.LOCAL,
            deviceId = entry.deviceId,
            model = model,
            endpointHost = entry.endpoint.host,
            endpointPort = entry.endpoint.port,
            useMultipath = false,
        )

    companion object {
        /** Devices reachable and serving [model], regardless of role — the tier-3 fallback filter. */
        fun anyReachableServing(candidates: List<CapabilityEntry>, model: String): List<CapabilityEntry> =
            candidates.filter { it.status.reachable && it.servesModel(model) }
    }
}
