package ai.koog.multiverse.android

import android.content.Context
import ai.koog.multiverse.MasterAgent
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
 * Android wiring for the Master Agent. Loads `tquic_config.xml` and `registry.json` from APK assets
 * (instead of the JVM classpath) and reuses the unchanged core loaders/modules to build a [MasterAgent].
 *
 * `tquic_config.xml` in the app's external files dir (`getExternalFilesDir(null)`), if present, takes
 * priority over the bundled asset — this lets you change transport settings via `adb push` without
 * rebuilding the APK (see README "Change TQUIC config without rebuilding").
 */
object AndroidConfig {

    fun buildAgent(context: Context): MasterAgent {
        val assets = context.assets

        val externalConfig = context.getExternalFilesDir(null)?.let { File(it, "tquic_config.xml") }
            ?.takeIf { it.isFile }
        val tquicConfig = externalConfig?.let { file -> file.inputStream().use { TquicConfigLoader.load(it) } }
            ?: assets.open("tquic_config.xml").use { TquicConfigLoader.load(it) }
        val registryJson = assets.open("registry.json").bufferedReader().use { it.readText() }
        val discovery = StaticConfigDiscovery(registryJson)

        // Mock backend by default so the app runs on-device with no API key or network.
        val backend: LlmBackend = LlmBackend.Mock

        return MasterAgent(
            discovery = discovery,
            registry = CapabilityRegistry(),
            sessionManager = SessionManager(),
            routingEngine = RoutingEngine(),
            network = SimulatedNetworkStatus(wifiUp = true, g5Up = true),
            confidenceManager = ConfidenceManager(),
            backend = backend,
            tquicConfig = tquicConfig,
        )
    }

    const val PORT = 8080
}
