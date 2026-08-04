package ai.koog.multiverse.discovery

import ai.koog.multiverse.registry.CapabilityEntry

/**
 * Discovers remote/local agents and yields their registry entries. In v1 this is a static-config
 * provider; mDNS is a later provider implementing the same interface (grillme_version2 Decision 7).
 */
interface DiscoveryService {
    /** Return the currently discovered devices (their capability-registry entries). */
    fun discover(): List<CapabilityEntry>
}
