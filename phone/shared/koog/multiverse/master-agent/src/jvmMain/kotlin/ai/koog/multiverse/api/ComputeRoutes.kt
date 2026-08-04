package ai.koog.multiverse.api

import ai.koog.multiverse.MasterAgent
import ai.koog.multiverse.model.DeviceInfo
import ai.koog.multiverse.model.DevicesBody
import ai.koog.multiverse.model.ErrorBody
import ai.koog.multiverse.model.HealthBody
import ai.koog.multiverse.model.MasterRequest
import ai.koog.multiverse.model.PolicyTierBody
import ai.koog.multiverse.model.RankedCandidateBody
import ai.koog.multiverse.model.RoutingPolicyBody
import ai.koog.multiverse.model.UseCase
import ai.koog.multiverse.routing.RoutingEngine
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * The `/v1` API routes (grillme_version2 Sec 3). `POST /v1/compute` accepts multipart form-data
 * (raw JPEG + query + useCase + optional sessionId) and returns the [ai.koog.multiverse.model.ComputeResponse]
 * JSON. Routing/transport internals are never exposed in the response.
 */
fun Application.computeRoutes(agent: MasterAgent, version: String = "0.1.0") {
    routing {
        post("/v1/compute") {
            val parsed = runCatching { parseComputeRequest(call.receiveMultipart()) }.getOrNull()
            if (parsed == null || parsed.imageBytes.isEmpty() || parsed.query.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorBody("bad_request", "Both a non-empty 'image' part and a 'query' part are required."),
                )
                return@post
            }
            try {
                call.respond(agent.handle(parsed))
            } catch (e: RoutingEngine.NoTargetAvailableException) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorBody("no_target_available", e.message ?: "No compute target available.", parsed.sessionId),
                )
            } catch (e: NotImplementedError) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorBody("transport_unavailable", e.message ?: "Transport not available.", parsed.sessionId),
                )
            }
        }

        get("/v1/sessions/{sessionId}") {
            // Session inspection is intentionally minimal in v1; return 404 shape when unknown.
            call.respond(
                HttpStatusCode.NotImplemented,
                ErrorBody("not_implemented", "Session inspection endpoint is not implemented in this build."),
            )
        }

        get("/v1/health") {
            call.respond(
                HealthBody(
                    status = "ok",
                    registryDevices = agent.registryDeviceCount(),
                    tquic = "scaffold",
                    version = version,
                ),
            )
        }

        get("/v1/registry/devices") {
            val devices = agent.registrySnapshot().map { e ->
                DeviceInfo(
                    deviceId = e.deviceId,
                    role = e.role.name,
                    host = e.endpoint.host,
                    port = e.endpoint.port,
                    multipath = e.capabilities.mpquic.name,
                    models = e.capabilities.models,
                    reachable = e.status.reachable,
                    gpuLoad = e.status.gpuLoad,
                )
            }
            call.respond(DevicesBody(devices))
        }

        get("/v1/routing/policy") {
            val report = agent.routingDiagnostics()
            call.respond(
                RoutingPolicyBody(
                    requestedModel = report.requestedModel,
                    wifiUp = report.wifiUp,
                    g5Up = report.g5Up,
                    tiers = report.tiers.map { t ->
                        PolicyTierBody(
                            tier = t.tier,
                            name = t.name,
                            candidates = t.candidates.map { c ->
                                RankedCandidateBody(c.deviceId, c.gpuLoad, c.selected)
                            },
                        )
                    },
                    decisionTarget = report.decisionTarget?.name,
                    decisionDeviceId = report.decisionDeviceId,
                    useMultipath = report.useMultipath,
                    error = report.error,
                ),
            )
        }
    }
}

/** Parse the multipart body into a [MasterRequest]. */
internal suspend fun parseComputeRequest(multipart: io.ktor.http.content.MultiPartData): MasterRequest {
    var image: ByteArray = ByteArray(0)
    var imageFormat = "jpg"
    var query = ""
    var useCase = UseCase.UC1
    var sessionId: String? = null

    multipart.forEachPart { part ->
        when (part) {
            is PartData.FileItem -> {
                if (part.name == "image") {
                    image = part.streamProvider().readBytes()
                    val ct = part.contentType?.contentSubtype
                    if (!ct.isNullOrBlank()) imageFormat = ct
                }
            }
            is PartData.FormItem -> when (part.name) {
                "query" -> query = part.value
                "useCase" -> useCase = runCatching { UseCase.valueOf(part.value.trim().uppercase()) }.getOrDefault(UseCase.UC1)
                "sessionId" -> sessionId = part.value.trim().ifBlank { null }
            }
            else -> {}
        }
        part.dispose()
    }

    return MasterRequest(
        sessionId = sessionId,
        query = query,
        useCase = useCase,
        imageBytes = image,
        imageFormat = if (imageFormat == "jpeg") "jpg" else imageFormat,
    )
}
