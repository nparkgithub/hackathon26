package ai.koog.multiverse.session

import ai.koog.multiverse.model.ComputeResponse
import ai.koog.multiverse.model.UseCase

/** One interaction turn stored in a session (for UC3 context resume). */
data class SessionTurn(
    val query: String,
    val useCase: UseCase,
    val answer: String,
    val confidence: Double,
    val atEpochMs: Long,
)

/**
 * A logical Master-Agent session (grillme_version2 "Session + Context" module). Keyed by [sessionId];
 * retains prior request context so a returning user (UC3) can continue without resending everything.
 */
data class Session(
    val sessionId: String,
    val createdAtEpochMs: Long,
    val turns: MutableList<SessionTurn> = mutableListOf(),
) {
    fun record(query: String, useCase: UseCase, response: ComputeResponse, atEpochMs: Long) {
        turns.add(SessionTurn(query, useCase, response.answer, response.confidence, atEpochMs))
    }

    /** Compact prior-context summary the executors can prepend to a prompt on resume. */
    fun priorContextSummary(): String =
        if (turns.isEmpty()) "" else turns.joinToString("\n") { "- Q: ${it.query} -> ${it.answer}" }
}
