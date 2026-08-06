package com.example.devmon

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyzeRequestHandlingTest {

    private fun telemetry(endpoint: String?, vision: Boolean): Telemetry = Telemetry(
        host = "h", os = "os", ip = "1.2.3.4",
        openAiEndpoint = endpoint,
        iface = "wlan0", cpuPercent = 0.0, cpuCount = 1, memPercent = 0.0,
        interfaces = emptyList(),
        llms = listOf(Telemetry.Llm("llava:7b", "7b", "q4", 4096, "llava", vision)),
    )

    private fun multipartRequest(fields: Map<String, ByteArray>, imageFileName: String = "photo.jpg", imageContentType: String = "image/jpeg"): ParsedHttpRequest {
        val boundary = "TESTBOUNDARY"
        val out = java.io.ByteArrayOutputStream()
        fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        for ((name, value) in fields) {
            writeAscii("--$boundary\r\n")
            if (name == "image") {
                writeAscii("Content-Disposition: form-data; name=\"image\"; filename=\"$imageFileName\"\r\n")
                writeAscii("Content-Type: $imageContentType\r\n\r\n")
            } else {
                writeAscii("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            }
            out.write(value)
            writeAscii("\r\n")
        }
        writeAscii("--$boundary--\r\n")
        val body = out.toByteArray()
        return ParsedHttpRequest(
            method = "POST",
            path = "/analyze",
            headers = mapOf("content-type" to "multipart/form-data; boundary=$boundary"),
            body = body,
        )
    }

    @Test
    fun `health reports no peer discovered when peers map is empty`() = runTest {
        val response = routeRequest(
            ParsedHttpRequest("GET", "/health", emptyMap(), ByteArray(0)),
            peersProvider = { emptyMap() },
            analyze = { _, _, _, _, _ -> "" },
        )
        assertEquals(200, response.status)
        val json = JSONObject(response.body)
        assertEquals("ok", json.getString("status"))
        assertEquals(false, json.getBoolean("peerDiscovered"))
        assertEquals(true, json.isNull("visionModel"))
    }

    @Test
    fun `health reports vision model when a vision peer is present`() = runTest {
        val response = routeRequest(
            ParsedHttpRequest("GET", "/health", emptyMap(), ByteArray(0)),
            peersProvider = { mapOf("a" to telemetry("http://192.168.1.5:8000", vision = true)) },
            analyze = { _, _, _, _, _ -> "" },
        )
        val json = JSONObject(response.body)
        assertEquals(true, json.getBoolean("peerDiscovered"))
        assertEquals("llava:7b", json.getString("visionModel"))
    }

    @Test
    fun `analyze succeeds and returns answer json`() = runTest {
        val request = multipartRequest(mapOf("image" to byteArrayOf(1, 2, 3), "query" to "safe?".toByteArray()))
        val response = routeRequest(
            request,
            peersProvider = { mapOf("a" to telemetry("http://192.168.1.5:8000", vision = true)) },
            analyze = { endpoint, model, _, _, query -> "Answer for $query via ${model.name} at $endpoint" },
        )
        assertEquals(200, response.status)
        val json = JSONObject(response.body)
        assertEquals("Answer for safe? via llava:7b at http://192.168.1.5:8000", json.getString("answer"))
        assertEquals("llava:7b", json.getString("model"))
        assertEquals("http://192.168.1.5:8000", json.getString("endpoint"))
    }

    @Test
    fun `analyze returns no_peer when no endpoint discovered`() = runTest {
        val request = multipartRequest(mapOf("image" to byteArrayOf(1), "query" to "safe?".toByteArray()))
        val response = routeRequest(
            request,
            peersProvider = { emptyMap() },
            analyze = { _, _, _, _, _ -> "" },
        )
        assertEquals(503, response.status)
        assertEquals("no_peer", JSONObject(response.body).getString("error"))
    }

    @Test
    fun `analyze returns no_vision_model when peer lacks vision`() = runTest {
        val request = multipartRequest(mapOf("image" to byteArrayOf(1), "query" to "safe?".toByteArray()))
        val response = routeRequest(
            request,
            peersProvider = { mapOf("a" to telemetry("http://192.168.1.5:8000", vision = false)) },
            analyze = { _, _, _, _, _ -> "" },
        )
        assertEquals(503, response.status)
        assertEquals("no_vision_model", JSONObject(response.body).getString("error"))
    }

    @Test
    fun `analyze returns upstream_failed with 502 when analyze throws`() = runTest {
        val request = multipartRequest(mapOf("image" to byteArrayOf(1), "query" to "safe?".toByteArray()))
        val response = routeRequest(
            request,
            peersProvider = { mapOf("a" to telemetry("http://192.168.1.5:8000", vision = true)) },
            analyze = { _, _, _, _, _ -> throw RuntimeException("connect timed out") },
        )
        assertEquals(502, response.status)
        val json = JSONObject(response.body)
        assertEquals("upstream_failed", json.getString("error"))
        assertEquals("connect timed out", json.getString("message"))
    }

    @Test
    fun `analyze returns bad_request when image part missing`() = runTest {
        val request = multipartRequest(mapOf("query" to "safe?".toByteArray()))
        val response = routeRequest(
            request,
            peersProvider = { mapOf("a" to telemetry("http://192.168.1.5:8000", vision = true)) },
            analyze = { _, _, _, _, _ -> "" },
        )
        assertEquals(400, response.status)
        assertEquals("bad_request", JSONObject(response.body).getString("error"))
    }

    @Test
    fun `analyze returns bad_request when query is blank`() = runTest {
        val request = multipartRequest(mapOf("image" to byteArrayOf(1), "query" to "   ".toByteArray()))
        val response = routeRequest(
            request,
            peersProvider = { mapOf("a" to telemetry("http://192.168.1.5:8000", vision = true)) },
            analyze = { _, _, _, _, _ -> "" },
        )
        assertEquals(400, response.status)
        assertEquals("bad_request", JSONObject(response.body).getString("error"))
    }

    @Test
    fun `analyze returns bad_request when content-type is not multipart`() = runTest {
        val request = ParsedHttpRequest("POST", "/analyze", mapOf("content-type" to "application/json"), ByteArray(0))
        val response = routeRequest(
            request,
            peersProvider = { emptyMap() },
            analyze = { _, _, _, _, _ -> "" },
        )
        assertEquals(400, response.status)
        assertEquals("bad_request", JSONObject(response.body).getString("error"))
    }

    @Test
    fun `unknown route returns 404 bad_request`() = runTest {
        val response = routeRequest(
            ParsedHttpRequest("GET", "/unknown", emptyMap(), ByteArray(0)),
            peersProvider = { emptyMap() },
            analyze = { _, _, _, _, _ -> "" },
        )
        assertEquals(404, response.status)
        assertEquals("bad_request", JSONObject(response.body).getString("error"))
    }
}
