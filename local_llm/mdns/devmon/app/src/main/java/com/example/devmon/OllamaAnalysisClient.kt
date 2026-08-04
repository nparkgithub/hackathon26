package com.example.devmon

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.ContentPart

/** Executes the image request through Koog against an endpoint supplied by telemetry. */
object OllamaAnalysisClient {
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
        val image = ContentPart.Image(
            content = AttachmentContent.Binary.Bytes(imageBytes),
            format = mimeType.substringAfter('/', missingDelimiterValue = "jpeg"),
            mimeType = mimeType,
            fileName = "allergy-image",
        )
        val request = prompt("allergy-image-analysis") {
            user(ALLERGY_PROMPT.trimIndent(), listOf(image))
        }
        val llmModel = LLModel(
            provider = LLMProvider.Ollama,
            id = model.name,
            capabilities = listOf(LLMCapability.Vision.Image),
            contextLength = model.contextLength.coerceAtLeast(1).toLong(),
            maxOutputTokens = null,
        )

        // The endpoint is validated as reporter-supplied by Telemetry.from().
        // Never create a default/localhost client here.
        val client = OllamaClient(baseUrl = endpoint)
        return try {
            client.execute(request, llmModel).joinToString("\n") { it.content }
                .ifBlank { error("Ollama returned an empty response") }
        } finally {
            client.close()
        }
    }
}
