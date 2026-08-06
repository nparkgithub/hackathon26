package com.example.devmon

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpParsingTest {

    private fun crlfJoin(vararg lines: String): String = lines.joinToString("\r\n")

    @Test
    fun `readHttpRequest parses method, path, headers and body`() {
        val body = "hello"
        val raw = crlfJoin(
            "POST /analyze?x=1 HTTP/1.1",
            "Host: 127.0.0.1",
            "Content-Type: text/plain",
            "Content-Length: ${body.length}",
            "",
            "",
        ) + body
        val request = readHttpRequest(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)))

        assertEquals("POST", request.method)
        assertEquals("/analyze", request.path)
        assertEquals("text/plain", request.headers["content-type"])
        assertEquals(body, String(request.body, Charsets.ISO_8859_1))
    }

    @Test
    fun `readHttpRequest with no content-length has empty body`() {
        val raw = crlfJoin("GET /health HTTP/1.1", "Host: 127.0.0.1", "", "")
        val request = readHttpRequest(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)))
        assertEquals("GET", request.method)
        assertEquals("/health", request.path)
        assertEquals(0, request.body.size)
    }

    @Test
    fun `extractBoundary reads unquoted value`() {
        assertEquals("abc123", extractBoundary("multipart/form-data; boundary=abc123"))
    }

    @Test
    fun `extractBoundary reads quoted value`() {
        assertEquals("abc 123", extractBoundary("multipart/form-data; boundary=\"abc 123\""))
    }

    @Test
    fun `extractBoundary returns null when absent`() {
        assertNull(extractBoundary("application/json"))
    }

    @Test
    fun `parseMultipart extracts named parts with content`() {
        val boundary = "XYZ"
        val imageBytes = byteArrayOf(1, 2, 3, 4)
        val body = buildMultipartBody(
            boundary,
            listOf(
                MultipartField("query", "is this safe?"),
                MultipartFileField("image", "photo.jpg", "image/jpeg", imageBytes),
            ),
        )
        val parts = parseMultipart(body, boundary)

        assertEquals(2, parts.size)
        val query = parts.first { it.name == "query" }
        assertEquals("is this safe?", String(query.content, Charsets.UTF_8))
        assertNull(query.filename)

        val image = parts.first { it.name == "image" }
        assertEquals("photo.jpg", image.filename)
        assertEquals("image/jpeg", image.contentType)
        assertArrayEquals(imageBytes, image.content)
    }

    @Test
    fun `parseMultipart returns empty list for garbage body`() {
        val parts = parseMultipart("not a multipart body".toByteArray(), "XYZ")
        assertTrue(parts.isEmpty())
    }

    @Test
    fun `writeHttpResponse serializes status line headers and body`() {
        val bytes = writeHttpResponse(HttpResponse(200, "application/json", "{\"a\":1}"))
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Type: application/json\r\n"))
        assertTrue(text.contains("Content-Length: 7\r\n"))
        assertTrue(text.contains("Connection: close\r\n"))
        assertTrue(text.endsWith("{\"a\":1}"))
    }

    @Test
    fun `writeHttpResponse maps known status codes to reason phrases`() {
        fun statusLine(status: Int) = String(writeHttpResponse(HttpResponse(status, "application/json", "")), Charsets.ISO_8859_1).lineSequence().first()
        assertEquals("HTTP/1.1 400 Bad Request\r", statusLine(400))
        assertEquals("HTTP/1.1 502 Bad Gateway\r", statusLine(502))
        assertEquals("HTTP/1.1 503 Service Unavailable\r", statusLine(503))
    }

    // --- multipart fixture builder ---

    private sealed interface MultipartEntry
    private class MultipartField(val name: String, val value: String) : MultipartEntry
    private class MultipartFileField(val name: String, val filename: String, val contentType: String, val bytes: ByteArray) : MultipartEntry

    private fun buildMultipartBody(boundary: String, entries: List<MultipartEntry>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        for (entry in entries) {
            writeAscii("--$boundary\r\n")
            when (entry) {
                is MultipartField -> {
                    writeAscii("Content-Disposition: form-data; name=\"${entry.name}\"\r\n\r\n")
                    writeAscii(entry.value)
                    writeAscii("\r\n")
                }
                is MultipartFileField -> {
                    writeAscii("Content-Disposition: form-data; name=\"${entry.name}\"; filename=\"${entry.filename}\"\r\n")
                    writeAscii("Content-Type: ${entry.contentType}\r\n\r\n")
                    out.write(entry.bytes)
                    writeAscii("\r\n")
                }
            }
        }
        writeAscii("--$boundary--\r\n")
        return out.toByteArray()
    }
}
