package com.example.devmon

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

data class ParsedHttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

data class MultipartPart(
    val name: String,
    val filename: String?,
    val contentType: String?,
    val content: ByteArray,
)

data class HttpResponse(val status: Int, val contentType: String, val body: String)

private val STATUS_TEXT = mapOf(
    200 to "OK",
    400 to "Bad Request",
    502 to "Bad Gateway",
    503 to "Service Unavailable",
)

/**
 * Reads one HTTP/1.1 request off [input]: request line, headers, then exactly
 * `Content-Length` body bytes (no size cap - the contract expects ~4 MB image uploads).
 * Reads byte-by-byte for the header section since a buffering reader would risk
 * over-consuming into the binary multipart body.
 */
fun readHttpRequest(input: InputStream): ParsedHttpRequest {
    val requestLine = readHeaderLine(input)
    val requestParts = requestLine.split(" ")
    if (requestParts.size < 2) throw IOException("Malformed request line: $requestLine")
    val method = requestParts[0]
    val path = requestParts[1].substringBefore('?')

    val headers = mutableMapOf<String, String>()
    while (true) {
        val line = readHeaderLine(input)
        if (line.isEmpty()) break
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
    }

    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
    val body = if (contentLength > 0) readExactly(input, contentLength) else ByteArray(0)
    return ParsedHttpRequest(method, path, headers, body)
}

private fun readHeaderLine(input: InputStream): String {
    val line = ByteArrayOutputStream()
    while (true) {
        val b = input.read()
        if (b == -1 || b == '\n'.code) break
        line.write(b)
    }
    val bytes = line.toByteArray()
    val trimmed = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
        bytes.copyOf(bytes.size - 1)
    } else {
        bytes
    }
    return String(trimmed, Charsets.ISO_8859_1)
}

private fun readExactly(input: InputStream, length: Int): ByteArray {
    val buf = ByteArray(length)
    var read = 0
    while (read < length) {
        val n = input.read(buf, read, length - read)
        if (n == -1) break
        read += n
    }
    return if (read == length) buf else buf.copyOf(read)
}

/** Extracts the `boundary=` parameter from a `Content-Type: multipart/form-data; boundary=...` header value. */
fun extractBoundary(contentTypeHeader: String): String? {
    val param = contentTypeHeader.split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("boundary=", ignoreCase = true) }
        ?: return null
    var value = param.substringAfter('=').trim()
    if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
        value = value.substring(1, value.length - 1)
    }
    return value.takeIf { it.isNotBlank() }
}

/** Splits a multipart/form-data body on `boundary` into its file/form parts. */
fun parseMultipart(body: ByteArray, boundary: String): List<MultipartPart> {
    val delimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)
    val parts = mutableListOf<MultipartPart>()

    for (section in splitOn(body, delimiter)) {
        if (section.isEmpty()) continue
        // The closing boundary is "--<boundary>--"; the section left after it starts with "--".
        if (section.size >= 2 && section[0] == HYPHEN && section[1] == HYPHEN) continue

        var content = section
        if (content.size >= 2 && content[0] == CR && content[1] == LF) {
            content = content.copyOfRange(2, content.size)
        }

        val headerEnd = indexOfBytes(content, DOUBLE_CRLF)
        if (headerEnd == -1) continue
        val headerText = String(content, 0, headerEnd, Charsets.ISO_8859_1)
        var partBody = content.copyOfRange(headerEnd + DOUBLE_CRLF.size, content.size)
        if (partBody.size >= 2 && partBody[partBody.size - 2] == CR && partBody[partBody.size - 1] == LF) {
            partBody = partBody.copyOfRange(0, partBody.size - 2)
        }

        var name: String? = null
        var filename: String? = null
        var contentType: String? = null
        for (headerLine in headerText.split("\r\n")) {
            when {
                headerLine.startsWith("Content-Disposition", ignoreCase = true) -> {
                    name = extractDispositionParam(headerLine, "name")
                    filename = extractDispositionParam(headerLine, "filename")
                }
                headerLine.startsWith("Content-Type", ignoreCase = true) -> {
                    contentType = headerLine.substringAfter(':').trim()
                }
            }
        }
        if (name != null) {
            parts += MultipartPart(name, filename, contentType, partBody)
        }
    }
    return parts
}

private val DISPOSITION_PARAM_REGEX = Regex("""(\w+)="([^"]*)"""")

private fun extractDispositionParam(headerLine: String, key: String): String? =
    DISPOSITION_PARAM_REGEX.findAll(headerLine).firstOrNull { it.groupValues[1] == key }?.groupValues?.get(2)

private const val CR = '\r'.code.toByte()
private const val LF = '\n'.code.toByte()
private const val HYPHEN = '-'.code.toByte()
private val DOUBLE_CRLF = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
    if (needle.isEmpty()) return from
    outer@ for (i in from..haystack.size - needle.size) {
        for (j in needle.indices) {
            if (haystack[i + j] != needle[j]) continue@outer
        }
        return i
    }
    return -1
}

private fun splitOn(data: ByteArray, delimiter: ByteArray): List<ByteArray> {
    val sections = mutableListOf<ByteArray>()
    var start = 0
    while (true) {
        val idx = indexOfBytes(data, delimiter, start)
        if (idx == -1) {
            sections += data.copyOfRange(start, data.size)
            break
        }
        sections += data.copyOfRange(start, idx)
        start = idx + delimiter.size
    }
    return sections
}

fun writeHttpResponse(response: HttpResponse): ByteArray {
    val bodyBytes = response.body.toByteArray(Charsets.UTF_8)
    val statusText = STATUS_TEXT[response.status] ?: "Unknown"
    val head = buildString {
        append("HTTP/1.1 ${response.status} $statusText\r\n")
        append("Content-Type: ${response.contentType}\r\n")
        append("Content-Length: ${bodyBytes.size}\r\n")
        append("Connection: close\r\n")
        append("\r\n")
    }.toByteArray(Charsets.ISO_8859_1)
    return head + bodyBytes
}
