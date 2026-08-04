package ai.koog.multiverse.discovery

import ai.koog.multiverse.registry.Capabilities
import ai.koog.multiverse.registry.CapabilityEntry
import ai.koog.multiverse.registry.DeviceRole
import ai.koog.multiverse.registry.DeviceStatus
import ai.koog.multiverse.registry.Endpoint
import ai.koog.multiverse.registry.NetInterface
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Static-config [DiscoveryService] provider (grillme_version2 Decision 7). Reads a JSON document
 * (default: `registry.json` on the classpath) describing known devices, and converts each entry's
 * relative `ttlSeconds` into an absolute epoch-millis expiry at [discover] time (so a fresh discovery
 * yields fresh TTLs — modeling a heartbeat).
 */
class StaticConfigDiscovery(
    private val json: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : DiscoveryService {

    @Serializable
    private data class FileEntry(
        val deviceId: String,
        val role: DeviceRole,
        val endpoint: Endpoint,
        val capabilities: Capabilities = Capabilities(),
        val interfaces: List<NetInterface> = emptyList(),
        val status: DeviceStatus = DeviceStatus(),
        val ttlSeconds: Double = 0.0,
    )

    @Serializable
    private data class FileRoot(val devices: List<FileEntry> = emptyList())

    private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun discover(): List<CapabilityEntry> {
        val now = clock()
        return parser.decodeFromString(FileRoot.serializer(), json).devices.map { fe ->
            CapabilityEntry(
                deviceId = fe.deviceId,
                role = fe.role,
                endpoint = fe.endpoint,
                capabilities = fe.capabilities,
                interfaces = fe.interfaces,
                status = fe.status,
                ttlExpiresAt = if (fe.ttlSeconds > 0) now + (fe.ttlSeconds * 1000).toLong() else 0,
            )
        }
    }

    companion object {
        /** Load the packaged `registry.json` resource. */
        fun fromResource(
            resource: String = "registry.json",
            clock: () -> Long = System::currentTimeMillis,
        ): StaticConfigDiscovery {
            val text = StaticConfigDiscovery::class.java.classLoader
                .getResourceAsStream(resource)?.bufferedReader()?.use { it.readText() }
                ?: error("$resource not found on classpath")
            return StaticConfigDiscovery(text, clock)
        }
    }
}
