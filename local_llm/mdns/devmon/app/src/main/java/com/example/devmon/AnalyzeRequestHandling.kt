package com.example.devmon

/** Routes a parsed HTTP request to the `/health` or `/analyze` handler; anything else is a 404. */
suspend fun routeRequest(
    request: ParsedHttpRequest,
    peersProvider: () -> Map<String, Telemetry>,
    analyze: suspend (endpoint: String, model: Telemetry.Llm, imageBytes: ByteArray, mimeType: String, query: String) -> String,
): HttpResponse = when {
    request.method == "GET" && request.path == "/health" -> handleHealth(peersProvider)
    request.method == "POST" && request.path == "/analyze" -> handleAnalyze(request, peersProvider, analyze)
    else -> HttpResponse(404, "application/json", buildErrorJson(AnalyzeErrorCode.BAD_REQUEST, "Unknown route ${request.method} ${request.path}"))
}

private fun handleHealth(peersProvider: () -> Map<String, Telemetry>): HttpResponse {
    val target = selectAnalysisTarget(peersProvider())
    val peerDiscovered = target !is AnalysisTarget.NoPeer
    val visionModel = (target as? AnalysisTarget.Found)?.model?.name
    return HttpResponse(200, "application/json", buildHealthJson(peerDiscovered, visionModel))
}

private suspend fun handleAnalyze(
    request: ParsedHttpRequest,
    peersProvider: () -> Map<String, Telemetry>,
    analyze: suspend (String, Telemetry.Llm, ByteArray, String, String) -> String,
): HttpResponse {
    val contentType = request.headers["content-type"]
    val boundary = contentType?.let { extractBoundary(it) }
    if (boundary == null) {
        return badRequest("Missing or malformed multipart/form-data Content-Type header.")
    }

    val parts = runCatching { parseMultipart(request.body, boundary) }.getOrNull()
        ?: return badRequest("Malformed multipart body.")

    val imagePart = parts.firstOrNull { it.name == "image" }
    val query = parts.firstOrNull { it.name == "query" }?.content?.toString(Charsets.UTF_8)?.trim()

    if (imagePart == null || imagePart.content.isEmpty()) {
        return badRequest("Missing or empty 'image' file part.")
    }
    if (query.isNullOrBlank()) {
        return badRequest("Missing or blank 'query' field.")
    }

    return when (val target = selectAnalysisTarget(peersProvider())) {
        is AnalysisTarget.NoPeer -> HttpResponse(
            AnalyzeErrorCode.NO_PEER.httpStatus,
            "application/json",
            buildErrorJson(AnalyzeErrorCode.NO_PEER, "No PC with a vision model discovered yet."),
        )
        is AnalysisTarget.NoVisionModel -> HttpResponse(
            AnalyzeErrorCode.NO_VISION_MODEL.httpStatus,
            "application/json",
            buildErrorJson(AnalyzeErrorCode.NO_VISION_MODEL, "A peer is connected, but none reported a vision-capable model."),
        )
        is AnalysisTarget.Found -> {
            val mimeType = imagePart.contentType ?: "image/jpeg"
            runCatching { analyze(target.endpoint, target.model, imagePart.content, mimeType, query) }.fold(
                onSuccess = { answer ->
                    HttpResponse(200, "application/json", buildSuccessJson(answer, target.model.name, target.endpoint))
                },
                onFailure = { e ->
                    HttpResponse(
                        AnalyzeErrorCode.UPSTREAM_FAILED.httpStatus,
                        "application/json",
                        buildErrorJson(AnalyzeErrorCode.UPSTREAM_FAILED, e.describeCauseChain()),
                    )
                },
            )
        }
    }
}

private fun badRequest(message: String): HttpResponse =
    HttpResponse(AnalyzeErrorCode.BAD_REQUEST.httpStatus, "application/json", buildErrorJson(AnalyzeErrorCode.BAD_REQUEST, message))
