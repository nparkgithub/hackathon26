package ai.koog.multiverse.execute

import ai.koog.multiverse.model.AssessmentResult
import ai.koog.multiverse.model.MasterRequest
import ai.koog.multiverse.routing.RouteDecision

/**
 * Runs a compute request against a chosen target and returns the structured [AssessmentResult].
 * Implementations: [LocalExecutor] (Koog PromptExecutor over Ktor), [RemoteExecutor] (TQUIC bridge),
 * and [MockComputeExecutor] (offline, deterministic).
 */
interface ComputeExecutor {
    suspend fun run(request: MasterRequest, decision: RouteDecision, priorContext: String): AssessmentResult
}
