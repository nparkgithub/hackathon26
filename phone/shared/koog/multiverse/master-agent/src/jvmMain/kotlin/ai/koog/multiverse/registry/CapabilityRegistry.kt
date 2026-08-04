package ai.koog.multiverse.registry

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory capability registry keyed on `device_id`. Static capabilities are refreshed on
 * re-registration ([register]); dynamic status on heartbeat ([updateStatus]). A [snapshot] returns
 * only entries whose TTL has not expired (expired ones are pruned).
 *
 * This is a routing INPUT, not the transport (grillme_version2 Sec 4 / PDF design note).
 */
class CapabilityRegistry(private val clock: () -> Long = System::currentTimeMillis) {

    private val entries = ConcurrentHashMap<String, CapabilityEntry>()

    /** Add or replace an entry (re-registration refreshes static capabilities). */
    fun register(entry: CapabilityEntry) {
        entries[entry.deviceId] = entry
    }

    fun registerAll(all: Collection<CapabilityEntry>) = all.forEach(::register)

    /** Refresh dynamic status + TTL for an existing device (heartbeat). No-op if unknown. */
    fun updateStatus(deviceId: String, status: DeviceStatus, ttlExpiresAt: Long) {
        entries.computeIfPresent(deviceId) { _, e -> e.copy(status = status, ttlExpiresAt = ttlExpiresAt) }
    }

    fun remove(deviceId: String) {
        entries.remove(deviceId)
    }

    /** Live entries (expired ones pruned as a side effect). */
    fun snapshot(): List<CapabilityEntry> {
        val now = clock()
        val expired = entries.values.filter { it.isExpired(now) }
        expired.forEach { entries.remove(it.deviceId) }
        return entries.values.toList()
    }

    fun get(deviceId: String): CapabilityEntry? = entries[deviceId]?.takeUnless { it.isExpired(clock()) }

    /** Live, reachable entries advertising [model] and matching [role]. */
    fun findByModel(model: String, role: DeviceRole? = null): List<CapabilityEntry> =
        snapshot().filter { it.status.reachable && it.servesModel(model) && (role == null || it.role == role) }
}
