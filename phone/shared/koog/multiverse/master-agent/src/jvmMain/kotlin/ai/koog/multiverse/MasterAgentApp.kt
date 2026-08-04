package ai.koog.multiverse

import ai.koog.multiverse.api.KoogHttpServer
import ai.koog.multiverse.confidence.ConfidenceManager
import ai.koog.multiverse.config.TquicConfigLoader
import ai.koog.multiverse.discovery.StaticConfigDiscovery
import ai.koog.multiverse.execute.LlmBackend
import ai.koog.multiverse.registry.CapabilityRegistry
import ai.koog.multiverse.routing.RoutingEngine
import ai.koog.multiverse.routing.SimulatedNetworkStatus
import ai.koog.multiverse.session.SessionManager
import java.io.File

/**
 * Entry point for the standalone Koog Master Agent app. Loads the TQUIC config and device registry
 * from packaged resources, selects an LLM backend (mock by default; OpenAI-compatible via env), builds
 * the [MasterAgent], and starts the embedded HTTP server.
 *
 * Env:
 *   LLM_BACKEND       = "mock" (default) | "openai"
 *   LOCAL_BASE_URL    = OpenAI-compatible base URL for the local route (default http://127.0.0.1:11434)
 *   OPENAI_API_KEY    = API key for the openai backend (default "" - fine for local Ollama/LM Studio)
 *   MASTER_AGENT_PORT = HTTP port (default 8080)
 *   WIFI_UP / G5_UP   = "true"/"false" to simulate path availability (default true/true)
 *   TQUIC_CONFIG_XML  = path to an external tquic_config.xml to load instead of the bundled default
 *                       (edit + restart to change transport settings without rebuilding)
 */
fun main() {
    val tquicConfig = System.getenv("TQUIC_CONFIG_XML")?.let(::File)?.takeIf { it.isFile }
        ?.let { file -> file.inputStream().use { TquicConfigLoader.load(it) } }
        ?: TquicConfigLoader.loadDefault()
    val discovery = StaticConfigDiscovery.fromResource("registry.json")

    val backend = when (System.getenv("LLM_BACKEND")?.lowercase()) {
        "openai" -> LlmBackend.OpenAICompatible(
            localBaseUrl = System.getenv("LOCAL_BASE_URL") ?: "http://127.0.0.1:11434",
            apiKey = System.getenv("OPENAI_API_KEY") ?: "",
        )
        else -> LlmBackend.Mock
    }

    val network = SimulatedNetworkStatus(
        wifiUp = System.getenv("WIFI_UP")?.toBooleanStrictOrNull() ?: true,
        g5Up = System.getenv("G5_UP")?.toBooleanStrictOrNull() ?: true,
    )

    val agent = MasterAgent(
        discovery = discovery,
        registry = CapabilityRegistry(),
        sessionManager = SessionManager(),
        routingEngine = RoutingEngine(),
        network = network,
        confidenceManager = ConfidenceManager(),
        backend = backend,
        tquicConfig = tquicConfig,
    )

    val port = System.getenv("MASTER_AGENT_PORT")?.toIntOrNull() ?: 8080
    println("Koog Master Agent starting on http://0.0.0.0:$port (backend=${backend::class.simpleName})")
    println("Registry devices: ${agent.registryDeviceCount()}  |  multipath=${tquicConfig.enableMultipath} algo=${tquicConfig.multipathAlgorithm}")
    KoogHttpServer(agent, port = port).start(wait = true)
}
