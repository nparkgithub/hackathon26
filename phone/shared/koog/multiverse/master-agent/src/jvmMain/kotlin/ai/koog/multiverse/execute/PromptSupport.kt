package ai.koog.multiverse.execute

import ai.koog.multiverse.model.AssessmentResult
import ai.koog.multiverse.model.MasterRequest
import ai.koog.multiverse.model.UseCase
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.Prompt
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import kotlinx.serialization.json.Json

/**
 * Shared prompt construction (image + instruction + JSON schema request) and response parsing used by
 * the local and remote executors. UC1 vs UC2 differ only in the system instruction and which part of
 * [AssessmentResult] the model fills.
 */
object PromptSupport {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val UC1_SYSTEM = """
You are a food-allergen analysis assistant. Analyze the food image and the user's instruction.
Return ONLY a JSON object with this shape:
{"answer": string, "confidence": number 0..1, "detail": string,
 "allergens": [{"name": string, "present": "present|possible|absent",
                "matchesUserAllergen": boolean, "evidence": string, "confidence": number}]}
"""

    private const val UC2_SYSTEM = """
You are a menu-analysis assistant. Read the menu image and the user's stated allergen. Suggest items
that do NOT contain that allergen. Return ONLY a JSON object with this shape:
{"answer": string, "confidence": number 0..1, "detail": string,
 "menuSuggestions": [{"name": string, "safe": boolean,
                      "containsAllergens": [string], "note": string}]}
"""

    fun buildPrompt(request: MasterRequest, priorContext: String): Prompt {
        val system = if (request.useCase == UseCase.UC2) UC2_SYSTEM else UC1_SYSTEM
        return prompt("multiverse-${request.useCase}") {
            system(system.trim())
            user {
                if (priorContext.isNotBlank()) {
                    +"Prior context:\n$priorContext\n"
                }
                +request.query
                image(
                    AttachmentSource.Image(
                        content = AttachmentContent.Binary.Bytes(request.imageBytes),
                        format = request.imageFormat,
                        fileName = "capture.${request.imageFormat}",
                    )
                )
            }
        }
    }

    /** Extract concatenated assistant text. */
    fun assistantText(message: Message.Assistant): String =
        message.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }

    /** Parse assistant text into [AssessmentResult], tolerating code fences / surrounding prose. */
    fun parse(text: String): AssessmentResult {
        val jsonText = extractJsonObject(text) ?: text
        return json.decodeFromString(AssessmentResult.serializer(), jsonText)
    }

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start in 0 until end) text.substring(start, end + 1) else null
    }
}
