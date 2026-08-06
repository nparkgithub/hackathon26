package com.example.devmon

/** Which peer (if any) should service the next `/analyze` call. */
sealed interface AnalysisTarget {
    data class Found(val endpoint: String, val model: Telemetry.Llm) : AnalysisTarget

    /** No peer has reported an OpenAI-compatible endpoint yet. */
    object NoPeer : AnalysisTarget

    /** At least one peer has an endpoint, but none reported a vision-capable model. */
    object NoVisionModel : AnalysisTarget
}

/** Picks the first peer that reports both an endpoint and a vision-capable model. */
fun selectAnalysisTarget(peers: Map<String, Telemetry>): AnalysisTarget {
    val withEndpoint = peers.values.filter { it.openAiEndpoint != null }
    if (withEndpoint.isEmpty()) return AnalysisTarget.NoPeer

    val found = withEndpoint.firstNotNullOfOrNull { telemetry ->
        telemetry.llms.firstOrNull { it.vision }?.let { model ->
            AnalysisTarget.Found(telemetry.openAiEndpoint!!, model)
        }
    }
    return found ?: AnalysisTarget.NoVisionModel
}
