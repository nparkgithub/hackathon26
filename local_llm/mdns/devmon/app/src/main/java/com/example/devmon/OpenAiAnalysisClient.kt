package com.example.devmon

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource

/** Calls the reporter-supplied OpenAI-compatible server through Koog. */
object OpenAiAnalysisClient {
    private const val LOCAL_COMPATIBILITY_KEY = "not-needed"
    private const val ALLERGY_PROMPT = """
        Find visible allergy-related information in this image, such as ingredient names,
        allergen statements, warnings, and possible allergen sources. Do not diagnose an
        allergy or claim that the image is complete. Clearly state uncertainty and advise
        the user to check the original packaging or ask a qualified clinician for medical
        decisions.
    """

    suspend fun analyze(
        endpoint: String,
        model: Telemetry.Llm,
        imageBytes: ByteArray,
        mimeType: String,
    ): String {
        val image = AttachmentSource.Image(
            content = AttachmentContent.Binary.Bytes(imageBytes),
            format = mimeType.substringAfter('/', missingDelimiterValue = "jpeg"),
            mimeType = mimeType,
            fileName = "allergy-image",
        )
        val request = prompt("allergy-image-analysis") {
            user {
                text(ALLERGY_PROMPT.trimIndent())
                image(image)
            }
        }
        val llmModel = LLModel(
            provider = LLMProvider.OpenAI,
            id = model.name,
            capabilities = listOf(
                LLMCapability.OpenAIEndpoint.Completions,
                LLMCapability.Completion,
                LLMCapability.Temperature,
                LLMCapability.Vision.Image,
            ),
            contextLength = model.contextLength.coerceAtLeast(1).toLong(),
            maxOutputTokens = null,
        )

        // Koog appends `v1/chat/completions`; the reporter sends only the base URL.
        // Local OpenAI-compatible servers commonly ignore this placeholder key.
        val client = OpenAILLMClient(
            apiKey = LOCAL_COMPATIBILITY_KEY,
            settings = OpenAIClientSettings(baseUrl = endpoint),
            httpClientFactory = KtorKoogHttpClient.Factory(),
        )
        return try {
            client.execute(request, llmModel).textContent()
                .ifBlank { error("OpenAI-compatible server returned an empty response") }
        } finally {
            client.close()
        }
    }
}
