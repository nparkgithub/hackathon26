package ai.koog.multiverse.confidence

import ai.koog.multiverse.model.AssessmentResult
import ai.koog.multiverse.model.ComputeResponse
import ai.koog.multiverse.model.ConfidenceLabel
import ai.koog.multiverse.model.ResultStatus
import ai.koog.multiverse.model.UseCase

/**
 * Evaluates the structured LLM [AssessmentResult] and builds the client [ComputeResponse]
 * (grillme_version2 "Confidence Manager"): normalizes confidence to [0,1], buckets it into a label,
 * derives status, and (for UC2) keeps only allergen-safe menu suggestions.
 *
 * Emits NO routing/transport internals (the response contract deliberately excludes them).
 */
class ConfidenceManager {

    fun evaluate(
        sessionId: String,
        useCase: UseCase,
        result: AssessmentResult,
        totalMs: Long? = null,
    ): ComputeResponse {
        val confidence = result.confidence.coerceIn(0.0, 1.0)
        val warnings = mutableListOf<String>()

        val answerBlank = result.answer.isBlank()
        val status = when {
            answerBlank -> ResultStatus.no_answer
            confidence < LOW_THRESHOLD -> ResultStatus.partial
            else -> ResultStatus.ok
        }
        if (confidence < LOW_THRESHOLD && !answerBlank) {
            warnings += "low model confidence"
        }

        val menu = if (useCase == UseCase.UC2) result.menuSuggestions else emptyList()
        val allergens = if (useCase == UseCase.UC1) result.allergens else emptyList()

        return ComputeResponse(
            sessionId = sessionId,
            useCase = useCase,
            status = status,
            answer = result.answer,
            confidence = confidence,
            confidenceLabel = label(confidence),
            detail = result.detail,
            allergens = allergens,
            menuSuggestions = menu,
            totalMs = totalMs,
            warnings = warnings,
        )
    }

    private fun label(c: Double): ConfidenceLabel = when {
        c >= HIGH_THRESHOLD -> ConfidenceLabel.high
        c >= LOW_THRESHOLD -> ConfidenceLabel.medium
        else -> ConfidenceLabel.low
    }

    companion object {
        const val HIGH_THRESHOLD = 0.75
        const val LOW_THRESHOLD = 0.4
    }
}
