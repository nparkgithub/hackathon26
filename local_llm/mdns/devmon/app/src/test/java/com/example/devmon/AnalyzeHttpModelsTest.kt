package com.example.devmon

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzeHttpModelsTest {

    @Test
    fun `success json contains all fields`() {
        val json = JSONObject(buildSuccessJson("Contains peanuts.", "llava:7b", "http://192.168.1.20:11434"))
        assertEquals("Contains peanuts.", json.getString("answer"))
        assertEquals("llava:7b", json.getString("model"))
        assertEquals("http://192.168.1.20:11434", json.getString("endpoint"))
    }

    @Test
    fun `success json handles null model and endpoint`() {
        val json = JSONObject(buildSuccessJson("answer text", null, null))
        assertEquals("", json.getString("model"))
        assertEquals("", json.getString("endpoint"))
    }

    @Test
    fun `error json shape`() {
        val json = JSONObject(buildErrorJson(AnalyzeErrorCode.NO_PEER, "No PC with a vision model discovered yet."))
        assertEquals("no_peer", json.getString("error"))
        assertEquals("No PC with a vision model discovered yet.", json.getString("message"))
    }

    @Test
    fun `error code wire values and statuses`() {
        assertEquals("no_peer" to 503, AnalyzeErrorCode.NO_PEER.wireValue to AnalyzeErrorCode.NO_PEER.httpStatus)
        assertEquals("no_vision_model" to 503, AnalyzeErrorCode.NO_VISION_MODEL.wireValue to AnalyzeErrorCode.NO_VISION_MODEL.httpStatus)
        assertEquals("upstream_failed" to 502, AnalyzeErrorCode.UPSTREAM_FAILED.wireValue to AnalyzeErrorCode.UPSTREAM_FAILED.httpStatus)
        assertEquals("bad_request" to 400, AnalyzeErrorCode.BAD_REQUEST.wireValue to AnalyzeErrorCode.BAD_REQUEST.httpStatus)
    }

    @Test
    fun `health json with peer and vision model`() {
        val json = JSONObject(buildHealthJson(peerDiscovered = true, visionModel = "llava:7b"))
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getBoolean("peerDiscovered"))
        assertEquals("llava:7b", json.getString("visionModel"))
    }

    @Test
    fun `health json with no peer has null vision model`() {
        val json = JSONObject(buildHealthJson(peerDiscovered = false, visionModel = null))
        assertFalse(json.getBoolean("peerDiscovered"))
        assertTrue(json.isNull("visionModel"))
    }

    @Test
    fun `describeCauseChain walks up to 4 causes`() {
        val e4 = IllegalStateException("root")
        val e3 = RuntimeException("three", e4)
        val e2 = RuntimeException("two", e3)
        val e1 = RuntimeException("one", e2)
        assertEquals("one <- two <- three <- root", e1.describeCauseChain())
    }

    @Test
    fun `describeCauseChain falls back to class name when message is null`() {
        val cause = RuntimeException()
        assertEquals("RuntimeException", cause.describeCauseChain())
    }
}
