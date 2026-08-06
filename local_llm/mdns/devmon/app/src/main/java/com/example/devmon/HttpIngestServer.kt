package com.example.devmon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Minimal HTTP server with two endpoints:
 *
 * - `POST /analyze` — accepts a multipart/form-data JPEG image + text query, runs it through the
 *   same vision-LLM lookup and OpenAI-compatible call path as the GUI's "Analyze reported LLM"
 *   button, and returns the answer as JSON in the same request.
 * - `GET|POST /health` — no body; reports whether a peer has been discovered and which vision
 *   model (if any) an /analyze call would currently use.
 *
 * Hand-rolled instead of an embedded HTTP server library, matching AdvertiserService's own
 * plain-ServerSocket + coroutine-accept-loop style; there is very little routing to support.
 */
class HttpIngestServer(private val advertiser: AdvertiserService) {

    companion object {
        const val PORT = 8080
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024
    }

    sealed interface State {
        object Idle : State
        data class Listening(val port: Int) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null

    fun start() {
        val sock = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
        } catch (e: IOException) {
            _state.value = State.Failed(e.message ?: "unknown error")
            advertiser.log("HTTP ingest bind failed: ${e.message}")
            return
        }
        server = sock
        _state.value = State.Listening(PORT)
        advertiser.log("HTTP ingest listening on :$PORT (POST /analyze, GET|POST /health)")
        scope.launch { acceptLoop(sock) }
    }

    fun shutdown() {
        runCatching { server?.close() }
        server = null
        _state.value = State.Idle
        scope.cancel()
    }

    private suspend fun acceptLoop(sock: ServerSocket) {
        while (!sock.isClosed) {
            val client = try {
                sock.accept()
            } catch (e: IOException) {
                if (!sock.isClosed) advertiser.log("HTTP ingest accept error: ${e.message}")
                return  // socket closed by shutdown()
            }
            scope.launch { handleClient(client) }
        }
    }

    private suspend fun handleClient(client: Socket) {
        val who = client.inetAddress?.hostAddress ?: "unknown"
        try {
            client.getInputStream().use { input ->
                client.getOutputStream().use { output ->
                    handleRequest(who, input, output)
                }
            }
        } catch (e: IOException) {
            advertiser.log("[ingest] $who dropped: ${e.message}")
        } catch (e: Exception) {
            advertiser.log("[ingest] $who error: ${e.describeCauseChain()}")
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun handleRequest(who: String, input: InputStream, output: OutputStream) {
        val head = readHeadBlock(input)
        if (head == null) {
            respond(output, 400, errorJson("malformed request"))
            advertiser.log("[ingest] $who -> 400 malformed request")
            return
        }
        val (requestLine, headers) = head

        val requestParts = requestLine.split(' ')
        val method = requestParts.getOrNull(0)
        val path = requestParts.getOrNull(1)?.substringBefore('?')

        // Health is answered before the POST /analyze gate: it takes no body, and GET is what
        // most probes (curl, k8s-style checkers, adb one-liners) will reach for by default.
        if (path == "/health" && (method == "POST" || method == "GET")) {
            respondHealth(who, output)
            return
        }

        if (method != "POST" || path != "/analyze") {
            respond(output, 404, errorJson("not found"))
            advertiser.log("[ingest] $who -> 404 ${method ?: "?"} ${path ?: "?"}")
            return
        }

        val contentType = headers["content-type"]
        val boundary = contentType?.let { extractBoundary(it) }
        if (contentType == null || !contentType.startsWith("multipart/form-data") || boundary == null) {
            respond(output, 400, errorJson("expected multipart/form-data with a boundary"))
            advertiser.log("[ingest] $who -> 400 bad content-type")
            return
        }

        val contentLength = headers["content-length"]?.toIntOrNull()
        if (contentLength == null || contentLength <= 0) {
            respond(output, 400, errorJson("missing or invalid Content-Length"))
            advertiser.log("[ingest] $who -> 400 missing content-length")
            return
        }
        if (contentLength > MAX_BODY_BYTES) {
            respond(output, 413, errorJson("body too large (max ${MAX_BODY_BYTES / (1024 * 1024)} MiB)"))
            advertiser.log("[ingest] $who -> 413 body too large")
            return
        }

        val body = readExact(input, contentLength)
        if (body == null) {
            respond(output, 400, errorJson("body shorter than Content-Length"))
            advertiser.log("[ingest] $who -> 400 truncated body")
            return
        }

        val multiparts = MultipartParser.parse(body, boundary)
        val imagePart = multiparts.firstOrNull { it.name == "image" && it.filename != null }
        val queryPart = multiparts.firstOrNull { it.name == "query" }
        val query = queryPart?.let { String(it.bytes, StandardCharsets.UTF_8).trim() }

        if (imagePart == null || imagePart.bytes.isEmpty()) {
            respond(output, 400, errorJson("missing 'image' file part"))
            advertiser.log("[ingest] $who -> 400 missing image part")
            return
        }
        if (query.isNullOrBlank()) {
            respond(output, 400, errorJson("missing or blank 'query' field"))
            advertiser.log("[ingest] $who -> 400 missing query field")
            return
        }
        advertiser.log("[ingest] $who query: \"${truncateForLog(query)}\"")

        val target = visionTarget()
        if (target == null) {
            respond(output, 503, errorJson("no peer reported both an OpenAI endpoint and a vision model"))
            advertiser.log("[ingest] $who -> 503 no vision peer")
            return
        }
        val (endpoint, model) = target

        val outcome = runCatching {
            OpenAiAnalysisClient.analyze(
                endpoint,
                model,
                imagePart.bytes,
                imagePart.contentType ?: "image/jpeg",
                query,
            )
        }
        outcome.fold(
            onSuccess = { answer ->
                val json = JSONObject()
                    .put("answer", answer)
                    .put("model", model.name)
                    .put("endpoint", endpoint)
                respond(output, 200, json.toString())
                advertiser.log("[ingest] $who -> 200 (${model.name})")
            },
            onFailure = { e ->
                respond(output, 500, errorJson(e.describeCauseChain()))
                advertiser.log("[ingest] $who -> 500 ${e.describeCauseChain()}")
            },
        )
    }

    /**
     * Endpoint + vision model taken from a *single* peer frame — the same lookup
     * MainActivity.analyzeSelectedImage() does. Null when no peer can serve /analyze right now.
     */
    private fun visionTarget(): Pair<String, Telemetry.Llm>? =
        advertiser.peers.value.values.firstNotNullOfOrNull { telemetry ->
            val endpoint = telemetry.openAiEndpoint ?: return@firstNotNullOfOrNull null
            val model = telemetry.llms.firstOrNull { it.vision } ?: return@firstNotNullOfOrNull null
            endpoint to model
        }

    /**
     * Readiness, signalled by silence.
     *
     * Answers **only** when a peer has been discovered *and* reported both an OpenAI endpoint and
     * a vision-capable model — i.e. exactly when /analyze could be served. With no such peer this
     * writes nothing and returns; handleClient's `finally` then closes the socket, so the probe
     * sees an empty reply (curl exit 52) instead of a body it would have to inspect.
     *
     * Because of that gate, every response this emits carries `peerDiscovered: true` and a
     * non-null `visionModel` — the not-ready variants are unreachable by construction. Note the
     * cost of the design: a silent close is indistinguishable from a crashed app or a dead
     * network, so this endpoint reports readiness but cannot report liveness.
     */
    private fun respondHealth(who: String, output: OutputStream) {
        val target = visionTarget()
        if (target == null) {
            advertiser.log("[ingest] $who -> (silent close) /health: no peer endpoint")
            return
        }
        val json = JSONObject()
            .put("status", "ok")
            .put("peerDiscovered", true)
            .put("visionModel", target.second.name)
        respond(output, 200, json.toString())
        advertiser.log("[ingest] $who -> 200 /health (vision: ${target.second.name})")
    }

    private fun errorJson(message: String) = JSONObject().put("error", message).toString()

    private fun truncateForLog(text: String, maxLen: Int = 200): String =
        if (text.length <= maxLen) text else text.take(maxLen) + "…"

    private fun respond(output: OutputStream, status: Int, jsonBody: String) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            503 -> "Service Unavailable"
            else -> "Internal Server Error"
        }
        val bodyBytes = jsonBody.toByteArray(StandardCharsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.write(bodyBytes)
        output.flush()
    }

    /** Reads the raw request line + headers up to the blank line; the body is read separately by Content-Length. */
    private fun readHeadBlock(input: InputStream): Pair<String, Map<String, String>>? {
        val buf = ByteArrayOutputStream()
        val last4 = ByteArray(4)
        while (true) {
            val b = input.read()
            if (b == -1) return null  // connection closed before headers completed
            buf.write(b)
            last4[0] = last4[1]; last4[1] = last4[2]; last4[2] = last4[3]; last4[3] = b.toByte()
            if (last4[0] == '\r'.code.toByte() && last4[1] == '\n'.code.toByte() &&
                last4[2] == '\r'.code.toByte() && last4[3] == '\n'.code.toByte()
            ) break
            if (buf.size() > 16 * 1024) return null  // header block implausibly large; bail
        }
        val text = String(buf.toByteArray(), StandardCharsets.ISO_8859_1)
        val lines = text.split("\r\n").dropLast(2)  // drop the trailing blank-line pair
        val requestLine = lines.firstOrNull() ?: return null
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        return requestLine to headers
    }

    private fun readExact(input: InputStream, count: Int): ByteArray? {
        val out = ByteArray(count)
        var off = 0
        while (off < count) {
            val n = input.read(out, off, count - off)
            if (n == -1) return null
            off += n
        }
        return out
    }

    private fun extractBoundary(contentType: String): String? {
        val marker = "boundary="
        val idx = contentType.indexOf(marker)
        if (idx == -1) return null
        var b = contentType.substring(idx + marker.length)
        val semi = b.indexOf(';')
        if (semi != -1) b = b.substring(0, semi)
        b = b.trim().trim('"')
        return b.ifBlank { null }
    }
}

/** Hand-rolled multipart/form-data body parser — no library dependency for one endpoint. */
private object MultipartParser {
    data class Part(val name: String, val filename: String?, val contentType: String?, val bytes: ByteArray)

    fun parse(body: ByteArray, boundary: String): List<Part> {
        val delimiter = "--$boundary".toByteArray(StandardCharsets.US_ASCII)
        val parts = mutableListOf<Part>()
        var start = indexOf(body, delimiter, 0)
        if (start == -1) return parts
        start += delimiter.size
        while (true) {
            // "--" right after the delimiter marks the closing boundary — no more parts.
            if (start + 1 >= body.size || (body[start] == '-'.code.toByte() && body[start + 1] == '-'.code.toByte())) break
            var partStart = start
            if (partStart + 1 < body.size && body[partStart] == '\r'.code.toByte() && body[partStart + 1] == '\n'.code.toByte()) {
                partStart += 2
            }
            val next = indexOf(body, delimiter, partStart)
            if (next == -1) break
            var partEnd = next
            if (partEnd >= 2 && body[partEnd - 2] == '\r'.code.toByte() && body[partEnd - 1] == '\n'.code.toByte()) {
                partEnd -= 2
            }
            parsePart(body, partStart, partEnd)?.let { parts.add(it) }
            start = next + delimiter.size
        }
        return parts
    }

    private fun parsePart(body: ByteArray, from: Int, to: Int): Part? {
        val headerEnd = indexOf(body, "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII), from)
        if (headerEnd == -1 || headerEnd > to) return null
        val headerText = String(body, from, headerEnd - from, StandardCharsets.ISO_8859_1)
        val contentStart = headerEnd + 4
        if (contentStart > to) return null
        val bytes = body.copyOfRange(contentStart, to)

        var name: String? = null
        var filename: String? = null
        var contentType: String? = null
        for (line in headerText.split("\r\n")) {
            val lower = line.lowercase()
            when {
                lower.startsWith("content-disposition") -> {
                    name = extractQuoted(line, "name")
                    filename = extractQuoted(line, "filename")
                }
                lower.startsWith("content-type") -> {
                    contentType = line.substringAfter(':').trim()
                }
            }
        }
        val partName = name ?: return null
        return Part(partName, filename, contentType, bytes)
    }

    /** Finds `key="value"`, skipping false matches like "name" inside "filename" by requiring
     *  the match not be preceded by a letter/digit (i.e. it starts a token, not a suffix). */
    private fun extractQuoted(header: String, key: String): String? {
        val marker = "$key=\""
        var searchFrom = 0
        while (true) {
            val idx = header.indexOf(marker, searchFrom)
            if (idx == -1) return null
            if (idx == 0 || !header[idx - 1].isLetterOrDigit()) {
                val start = idx + marker.length
                val end = header.indexOf('"', start)
                return if (end == -1) null else header.substring(start, end)
            }
            searchFrom = idx + 1
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return from
        val last = haystack.size - needle.size
        outer@ for (i in from.coerceAtLeast(0)..last) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
