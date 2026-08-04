package ai.koog.multiverse.execute

import ai.koog.multiverse.model.AssessmentResult
import ai.koog.multiverse.model.MasterRequest
import ai.koog.multiverse.routing.RouteDecision
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel

/**
 * Remote route executor (grillme_version2 Decision 4/5): calls the REMOTE AWS target through a Koog
 * [PromptExecutor] whose LLM client is wired to the TQUIC transport (http-client-tquic). Same
 * prompt/parse flow as [LocalExecutor]; the difference is the injected transport.
 *
 * Until the Rust tquic-jni native library is built, executing this path throws a clear
 * "TQUIC bridge not yet implemented" error from the transport layer (by design, this pass).
 */
class RemoteExecutor(
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
