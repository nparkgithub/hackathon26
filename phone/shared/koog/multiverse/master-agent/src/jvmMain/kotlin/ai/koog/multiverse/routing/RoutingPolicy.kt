package ai.koog.multiverse.routing

import ai.koog.multiverse.registry.CapabilityEntry

/** Where a request should execute. */
enum class Target { LOCAL, REMOTE }

/**
 * The Routing Engine's decision (INTERNAL — never returned to the phone app). Carries the chosen
 * target/device/model and whether multipath is *eligible*; the actual path selection is delegated to
 * TQUIC (orchestration-vs-transport separation).
 */
data class RouteDecision(
    val target: Target,
    val deviceId: String,
    val model: String,
    val endpointHost: String,
    val endpointPort: Int,
    val useMultipath: Boolean,
    val localAddresses: List<String> = emptyList(),
)

/**
 * Pluggable routing policy (grillme_version2 Decision 11). Given the live candidates and the requested
 * model, pick the preferred remote target (or null to fall back to local).
 *
 * [rankRemote]/[rankLocal] expose the *full* ranked candidate order behind [chooseRemote]/[chooseLocal]
 * (best first) — used by routing diagnostics to show the whole priority order, not just the winner.
 * Implementations should define `choose* = rank*(candidates).firstOrNull()` so the two can never disagree.
 */
interface RoutingPolicy {
    val requestedModel: String
    fun chooseRemote(candidates: List<CapabilityEntry>): CapabilityEntry?
    fun chooseLocal(candidates: List<CapabilityEntry>): CapabilityEntry?
    fun rankRemote(candidates: List<CapabilityEntry>): List<CapabilityEntry>
    fun rankLocal(candidates: List<CapabilityEntry>): List<CapabilityEntry>
}
