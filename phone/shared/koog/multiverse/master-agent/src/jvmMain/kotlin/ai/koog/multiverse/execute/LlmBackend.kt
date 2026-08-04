package ai.koog.multiverse.execute

import ai.koog.http.client.tquic.TquicKoogHttpClient
import ai.koog.http.client.tquic.TquicSessionParams
import ai.koog.multiverse.routing.RouteDecision
import ai.koog.multiverse.routing.Target
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/**
 * Pluggable LLM backend (grillme_version2 Decision: pluggable executor, mock default). Chooses how the
 * [ComputeExecutor] for a given route is built:
 *  - [Mock]: deterministic [MockComputeExecutor], no network/API key (default, always runnable).
 *  - [OpenAICompatible]: local route -> Koog OpenAI client over Ktor; remote route -> OpenAI client
 *    whose transport is the TQUIC [TquicKoogHttpClient] (injected into the client constructor).
 */
sealed interface LlmBackend {

    /** Build the executor for the resolved route decision. */
    fun executorFor(decision: RouteDecision, tquicParams: TquicSessionParams): ComputeExecutor

    /** Offline deterministic backend. */
    object Mock : LlmBackend {
        override fun executorFor(decision: RouteDecision, tquicParams: TquicSessionParams): ComputeExecutor =
            MockComputeExecutor()
    }

    /**
     * OpenAI-compatible backend. [localBaseUrl] fronts the XElite endpoint (Ollama/LM Studio/etc.);
     * the remote route reaches the AWS OpenAI-compatible H3 endpoint over TQUIC.
     */
    class OpenAICompatible(
        private val localBaseUrl: String,
        private val apiKey: String,
        private val model: LLModel = OpenAIModels.Chat.GPT4o,
    ) : LlmBackend {

        override fun executorFor(decision: RouteDecision, tquicParams: TquicSessionParams): ComputeExecutor =
            when (decision.target) {
                Target.LOCAL -> {
                    // Local route: OpenAI-compatible over the default (Ktor) transport, custom base URL.
                    val client = OpenAILLMClient(
                        apiKey = apiKey,
                        settings = OpenAIClientSettings(baseUrl = localBaseUrl),
                    )
                    LocalExecutor(MultiLLMPromptExecutor(LLMProvider.OpenAI to client), model)
                }
                Target.REMOTE -> {
                    // Remote route: inject the TQUIC transport directly into the OpenAI client ctor,
                    // bypassing ServiceLoader (which allows only one factory on the classpath).
                    val baseUrl = "https://${decision.endpointHost}:${decision.endpointPort}"
                    val transport = TquicKoogHttpClient(
                        clientName = "tquic-openai",
                        baseUrl = baseUrl,
                        params = tquicParams.copy(serverName = decision.endpointHost),
                    )
                    val client = OpenAILLMClient(
                        settings = OpenAIClientSettings(baseUrl = baseUrl),
                        httpClient = transport,
                    )
                    RemoteExecutor(MultiLLMPromptExecutor(LLMProvider.OpenAI to client), model)
                }
            }
    }
}
