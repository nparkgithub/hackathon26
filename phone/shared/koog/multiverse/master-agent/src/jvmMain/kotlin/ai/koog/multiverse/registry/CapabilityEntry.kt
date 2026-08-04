package ai.koog.multiverse.registry

import kotlinx.serialization.Serializable

/** MP-QUIC support level advertised by a device (the routing input for multipath eligibility). */
@Serializable
enum class MpQuicSupport { supported, unsupported, unknown }

/** Role of a registered device. */
@Serializable
enum class DeviceRole { remote_agent, local_agent }

/** Registered service endpoint (host + port). */
@Serializable
data class Endpoint(val host: String, val port: Int)

/** A network interface a device exposes (used to add transport paths). */
@Serializable
data class NetInterface(val name: String, val ip: String, val port: Int)

/** Static capabilities — refreshed only on re-registration (grillme_version2 Sec 4). */
@Serializable
data class Capabilities(
    val mpquic: MpQuicSupport = MpQuicSupport.unknown,
    val paths: List<String> = emptyList(),
    val models: List<String> = emptyList(),
)

/** Dynamic status — refreshed on heartbeat. */
@Serializable
data class DeviceStatus(
    val gpuLoad: Double = 0.0,
    val reachable: Boolean = true,
)

/**
 * A capability-registry entry (nested schema, grillme_version2 Sec 4). Static [capabilities] are kept
 * separate from dynamic [status] per the PDF design note. Keyed on a stable [deviceId].
 *
 * [ttlExpiresAt] is epoch-millis; entries past it are pruned from a registry snapshot.
 */
@Serializable
data class CapabilityEntry(
    val deviceId: String,
    val role: DeviceRole,
    val endpoint: Endpoint,
    val capabilities: Capabilities = Capabilities(),
    val interfaces: List<NetInterface> = emptyList(),
    val status: DeviceStatus = DeviceStatus(),
    val ttlExpiresAt: Long = 0,
) {
    fun isExpired(nowMillis: Long): Boolean = ttlExpiresAt in 1 until nowMillis
    fun servesModel(model: String): Boolean = model in capabilities.models
    fun supportsMpQuic(): Boolean = capabilities.mpquic == MpQuicSupport.supported
}
