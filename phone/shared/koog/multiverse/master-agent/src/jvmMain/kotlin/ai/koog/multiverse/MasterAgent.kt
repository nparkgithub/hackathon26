package ai.koog.multiverse

import ai.koog.http.client.tquic.TquicSessionParams
import ai.koog.multiverse.confidence.ConfidenceManager
import ai.koog.multiverse.config.TquicConfig
import ai.koog.multiverse.discovery.DiscoveryService
import ai.koog.multiverse.execute.LlmBackend
import ai.koog.multiverse.model.ComputeResponse
import ai.koog.multiverse.model.MasterRequest
import ai.koog.multiverse.registry.CapabilityEntry
import ai.koog.multiverse.registry.CapabilityRegistry
import ai.koog.multiverse.routing.NetworkStatus
import ai.koog.multiverse.routing.PreferRemotePolicy
import ai.koog.multiverse.routing.RoutingDiagnostics
import ai.koog.multiverse.routing.RoutingDiagnosticsReport
import ai.koog.multiverse.routing.RoutingEngine
import ai.koog.multiverse.session.SessionManager

/**
 * The Master Agent Core (grillme_version2 / PDF Figure 2). Composes the four modules as the pipeline
 * the strategy graph describes:
 *
 *   receive -> session resolve -> refresh registry + route -> execute (local|remote) -> confidence -> respond
 *
 * Discovery+Registry, Session+Context, Routing Engine and Confidence Manager are separate collaborators
 * so each can be tested in isolation; the actual LLM calls run through Koog's PromptExecutor inside the
 * chosen [ai.koog.multiverse.execute.ComputeExecutor]. Routing/transport internals never leave this class.
 */
class MasterAgent(
    private val discovery: DiscoveryService,
    private val registry: CapabilityRegistry,
    private val sessionManager: SessionManager,
    private val routingEngine: RoutingEngine,
    private val network: NetworkStatus,
    private val confidenceManager: ConfidenceManager,
    private val backend: LlmBackend,
    private val tquicConfig: TquicConfig,
    private val defaultModel: String = "llama3.2-vision",
) {
    init {
        // Seed the registry from discovery at startup (static-config provider in v1).
        registry.registerAll(discovery.discover())
    }

    /** Run one compute request end to end and produce the client response. */
    suspend fun handle(request: MasterRequest): ComputeResponse {
        val start = sessionManager.now()

        // Session + Context: create or resume (UC3).
        val session = sessionManager.getOrCreate(request.sessionId)
        val priorContext = session.priorContextSummary()

        // Discovery + Registry: refresh from discovery, then take a live snapshot (prunes expired TTLs).
        registry.registerAll(discovery.discover())
        val snapshot = registry.snapshot()

        // Routing Engine: choose target + multipath eligibility (INTERNAL decision).
        val policy = PreferRemotePolicy(requestedModel = defaultModel)
        val decision = routingEngine.select(snapshot, network, policy)

        // Transport params from the TQUIC config (only meaningful for the remote route).
        val tquicParams = toSessionParams(decision.localAddresses)

        // Execute on the selected target.
        val executor = backend.executorFor(decision, tquicParams)
        val assessment = executor.run(request, decision, priorContext)

        // Confidence Manager: normalize into the client response.
        val elapsed = sessionManager.now() - start
        val response = confidenceManager.evaluate(session.sessionId, request.useCase, assessment, elapsed)

        // Record turn for UC3 resume.
        session.record(request.query, request.useCase, response, sessionManager.now())
        sessionManager.save(session)

        return response
    }

    fun registryDeviceCount(): Int = registry.snapshot().size

    /** Live registry snapshot (registry menu / `GET /v1/registry/devices`). */
    fun registrySnapshot(): List<CapabilityEntry> {
        registry.registerAll(discovery.discover())
        return registry.snapshot()
    }

    /** Full routing priority order against the live snapshot (routing menu / `GET /v1/routing/policy`). */
    fun routingDiagnostics(): RoutingDiagnosticsReport =
        RoutingDiagnostics.evaluate(
            snapshot = registrySnapshot(),
            network = network,
            policy = PreferRemotePolicy(requestedModel = defaultModel),
            engine = routingEngine,
        )

    private fun toSessionParams(localAddresses: List<String>) = TquicSessionParams(
        serverName = "",
        alpn = tquicConfig.alpn,
        enableMultipath = tquicConfig.enableMultipath,
        multipathAlgorithm = tquicConfig.multipathAlgorithm.name,
        enablePatfb = tquicConfig.enablePathArrivalTimeFeedback,
        fileSizeMpScheduler = tquicConfig.fileSizeMpScheduler,
        congestionControl = tquicConfig.congestionControl.name,
        idleTimeoutMs = tquicConfig.maxIdleTimeoutMs,
        initialRttMs = tquicConfig.initialRttMs,
        primaryLocalAddr = localAddresses.firstOrNull() ?: "0.0.0.0:0",
        additionalLocalAddresses = localAddresses.drop(1),
    )
}
