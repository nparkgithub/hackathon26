package ai.koog.multiverse.routing

import ai.koog.multiverse.registry.CapabilityEntry
import ai.koog.multiverse.registry.DeviceRole

/**
 * Default policy (grillme_version2 Decision 11): prefer a healthy REMOTE agent advertising the model
 * (offload to the more powerful host), with the lowest GPU load; fall back to LOCAL otherwise.
 */
class PreferRemotePolicy(override val requestedModel: String) : RoutingPolicy {

    override fun chooseRemote(candidates: List<CapabilityEntry>): CapabilityEntry? =
        rankRemote(candidates).firstOrNull()

    override fun chooseLocal(candidates: List<CapabilityEntry>): CapabilityEntry? =
        rankLocal(candidates).firstOrNull()

    override fun rankRemote(candidates: List<CapabilityEntry>): List<CapabilityEntry> =
        candidates
            .filter { it.role == DeviceRole.remote_agent && it.status.reachable && it.servesModel(requestedModel) }
            .sortedBy { it.status.gpuLoad }

    override fun rankLocal(candidates: List<CapabilityEntry>): List<CapabilityEntry> =
        candidates
            .filter { it.role == DeviceRole.local_agent && it.status.reachable && it.servesModel(requestedModel) }
            .sortedBy { it.status.gpuLoad }
}
