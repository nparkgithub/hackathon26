package ai.koog.multiverse.model

import kotlinx.serialization.Serializable

/** One registry entry as returned by `GET /v1/registry/devices` (registry menu). */
@Serializable
data class DeviceInfo(
    val deviceId: String,
    val role: String,
    val host: String,
    val port: Int,
    val multipath: String,
    val models: List<String>,
    val reachable: Boolean,
    val gpuLoad: Double,
)

@Serializable
data class DevicesBody(val devices: List<DeviceInfo>)

/** One ranked candidate within a [PolicyTierBody]. */
@Serializable
data class RankedCandidateBody(val deviceId: String, val gpuLoad: Double, val selected: Boolean)

/** One fallback tier of the routing policy, fully ranked (best first). */
@Serializable
data class PolicyTierBody(val tier: Int, val name: String, val candidates: List<RankedCandidateBody>)

/** Full priority-order report as returned by `GET /v1/routing/policy` (routing-policy menu). */
@Serializable
data class RoutingPolicyBody(
    val requestedModel: String,
    val wifiUp: Boolean,
    val g5Up: Boolean,
    val tiers: List<PolicyTierBody>,
    val decisionTarget: String?,
    val decisionDeviceId: String?,
    val useMultipath: Boolean,
    val error: String?,
)
