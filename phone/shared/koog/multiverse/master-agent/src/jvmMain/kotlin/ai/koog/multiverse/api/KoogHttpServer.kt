package ai.koog.multiverse.api

import ai.koog.multiverse.MasterAgent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.sse.SSE
import kotlinx.serialization.json.Json

/**
 * Embedded Ktor HTTP server that exposes the Master Agent to the VideoShowCase phone app
 * (grillme_version2 Sec 3). Runs over loopback (same phone) or LAN (separate machine) unchanged.
 */
class KoogHttpServer(
    private val agent: MasterAgent,
    private val host: String = "0.0.0.0",
    private val port: Int = 8080,
    private val version: String = "0.1.0",
) {
    fun build(): EmbeddedServer<*, *> = embeddedServer(CIO, port = port, host = host) {
        module(agent, version)
    }

    /** Start the server, optionally blocking the calling thread. */
    fun start(wait: Boolean = true): EmbeddedServer<*, *> = build().start(wait = wait)
}

/** Ktor application module: installs plugins and mounts the compute routes. */
fun Application.module(agent: MasterAgent, version: String = "0.1.0") {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(SSE)
    computeRoutes(agent, version)
}
