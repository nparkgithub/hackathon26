package ai.koog.multiverse.execute

import ai.koog.multiverse.model.AssessmentResult
import ai.koog.multiverse.model.MasterRequest
import ai.koog.multiverse.routing.RouteDecision
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel

/**
 * Local route executor (grillme_version2 Decision 5): calls the LOCAL XElite target through a Koog
 * [PromptExecutor] (plain HTTP via Ktor, no TQUIC). Builds the vision prompt, executes, parses JSON.
 */
class LocalExecutor(
    private val executor: PromptExecutor,
    private val model: LLModel,
) : ComputeExecutor {

    override suspend fun run(
        request: MasterRequest,
        decision: RouteDecision,
        priorContext: String,
    ): AssessmentResult {
        val prompt = PromptSupport.buildPrompt(request, priorContext)
        val assistant = executor.execute(prompt, model)
        return PromptSupport.parse(PromptSupport.assistantText(assistant))
    }
}
