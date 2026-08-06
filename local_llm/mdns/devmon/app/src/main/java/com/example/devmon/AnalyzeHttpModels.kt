package com.example.devmon

import org.json.JSONObject

/** Error codes for non-2xx `/analyze` responses. Wire values and statuses per the DevMon HTTP API contract. */
enum class AnalyzeErrorCode(val wireValue: String, val httpStatus: Int) {
    NO_PEER("no_peer", 503),
    NO_VISION_MODEL("no_vision_model", 503),
    UPSTREAM_FAILED("upstream_failed", 502),
    BAD_REQUEST("bad_request", 400),
}

fun buildSuccessJson(answer: String, model: String?, endpoint: String?): String =
    JSONObject()
        .put("answer", answer)
        .put("model", model ?: "")
        .put("endpoint", endpoint ?: "")
        .toString()

fun buildErrorJson(code: AnalyzeErrorCode, message: String): String =
    JSONObject()
        .put("error", code.wireValue)
        .put("message", message)
        .toString()

fun buildHealthJson(peerDiscovered: Boolean, visionModel: String?): String =
    JSONObject()
        .put("status", "ok")
        .put("peerDiscovered", peerDiscovered)
        // JSONObject.put(key, null) removes the key rather than writing a JSON null, so the
        // absent case needs JSONObject.NULL spelled out explicitly.
        .put("visionModel", visionModel ?: JSONObject.NULL)
        .toString()

/** Renders up to 4 levels of cause chain, e.g. for surfacing an upstream failure's root cause. */
fun Throwable.describeCauseChain(): String =
    generateSequence(this) { it.cause }
        .take(4)
        .joinToString(" <- ") { it.message ?: it.javaClass.simpleName }
