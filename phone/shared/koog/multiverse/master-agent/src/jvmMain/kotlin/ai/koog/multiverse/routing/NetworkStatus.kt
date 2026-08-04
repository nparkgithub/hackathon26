package ai.koog.multiverse.routing

/**
 * Path availability the Routing Engine reads to decide multipath eligibility. v1 provider is
 * simulated/testbed-driven; an Android ConnectivityManager provider comes later
 * (grillme_version2 Decision 12).
 */
interface NetworkStatus {
    val wifiUp: Boolean
    val g5Up: Boolean
    fun bothPathsUp(): Boolean = wifiUp && g5Up
}

/** Simulated network status (config/testbed driven). Defaults to both paths up. */
class SimulatedNetworkStatus(
    override val wifiUp: Boolean = true,
    override val g5Up: Boolean = true,
) : NetworkStatus
