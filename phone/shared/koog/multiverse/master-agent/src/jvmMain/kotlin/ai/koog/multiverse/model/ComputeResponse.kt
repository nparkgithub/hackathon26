package ai.koog.multiverse.model

import kotlinx.serialization.Serializable

/**
 * The wire response returned to the phone app for `POST /v1/compute` (and the SSE `result` event).
 * This is the canonical client contract from grillme_version2 Sec 3.7.
 *
 * Deliberately excludes all orchestration/transport internals (which target served it, device id,
 * model, multipath, paths, scheduler): per the PDF's orchestration-vs-transport separation, the phone
 * app neither needs nor receives that. It lives in Koog's own logs, not this response.
 */
@Serializable
data class ComputeResponse(
    val sessionId: String,
    val useCase: UseCase,
    val status: ResultStatus = ResultStatus.ok,
    val answer: String,
    val confidence: Double,
    val confidenceLabel: ConfidenceLabel,
    val detail: String = "",
    val allergens: List<AllergenFinding> = emptyList(),
    val menuSuggestions: List<MenuItem> = emptyList(),
    val totalMs: Long? = null,
    val warnings: List<String> = emptyList(),
)

/** Error body returned for non-2xx responses (v2 Sec 3.5). */
@Serializable
data class ErrorBody(
    val error: String,
    val message: String,
    val sessionId: String? = null,
)

/** Health probe body for `GET /v1/health` (v2 Sec 3.4). */
@Serializable
data class HealthBody(
    val status: String,
    val registryDevices: Int,
    val tquic: String,
    val version: String,
)
