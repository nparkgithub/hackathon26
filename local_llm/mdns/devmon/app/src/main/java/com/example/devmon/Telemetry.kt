package com.example.devmon

import org.json.JSONObject
import java.net.URI

/** One telemetry snapshot pushed by a desktop peer. */
data class Telemetry(
    val host: String,
    val os: String,
    val ip: String,
    /** LAN OpenAI-compatible base URL reported by discover_and_report.py; null if unavailable/unsafe. */
    val openAiEndpoint: String?,
    val iface: String,
    val cpuPercent: Double,
    val cpuCount: Int,
    val memPercent: Double,
    val interfaces: List<Iface>,
    /** Every model the peer reports; empty if it sent none. */
    val llms: List<Llm>,
) {
    data class Iface(val name: String, val ipv4: String, val speedMbps: Int?, val up: Boolean)
    data class Llm(
        val name: String,
        val parameters: String,
        val quantization: String,
        val contextLength: Int,
        val family: String,
        val vision: Boolean,
    )

    companion object {
        fun from(json: String): Telemetry {
            val o = JSONObject(json)
            val ifaces = mutableListOf<Iface>()
            o.optJSONArray("interfaces")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val x = arr.getJSONObject(i)
                    ifaces += Iface(
                        name = x.optString("name", "?"),
                        ipv4 = x.optString("ipv4", "?"),
                        speedMbps = if (x.isNull("speed_mbps")) null else x.optInt("speed_mbps"),
                        up = x.optBoolean("up", false),
                    )
                }
            }
            // Peers send every model under `llms`. Older ones send a single
            // object under `llm`; newer ones repeat the first record there for
            // exactly that reason, so only read it when `llms` is absent.
            val llms = mutableListOf<Llm>()
            val llmArr = o.optJSONArray("llms")
            if (llmArr != null) {
                for (i in 0 until llmArr.length()) {
                    llmArr.optJSONObject(i)?.let { llms += llmFrom(it) }
                }
            } else {
                o.optJSONObject("llm")?.let { llms += llmFrom(it) }
            }
            return Telemetry(
                host = o.optString("host", "?"),
                os = o.optString("os", "?"),
                ip = o.optString("ip", "?"),
                openAiEndpoint = endpointFrom(o.optString("openai_endpoint", "")),
                iface = o.optString("interface", "?"),
                cpuPercent = o.optDouble("cpu_percent", 0.0),
                cpuCount = o.optInt("cpu_count", 0),
                memPercent = o.optDouble("mem_percent", 0.0),
                interfaces = ifaces,
                llms = llms,
            )
        }

        private fun llmFrom(o: JSONObject) = Llm(
            name = o.optString("name", "?"),
            parameters = o.optString("parameters", "?"),
            quantization = o.optString("quantization", "?"),
            contextLength = o.optInt("context_length", 0),
            family = o.optString("family", "?"),
            vision = o.optBoolean("vision", false),
        )

        /**
         * Android only accepts an explicit IPv4 LAN URL emitted by the reporter.
         * Do not use localhost, DNS names, Android's own address, or telemetry.ip
         * as substitutes when this field is absent.
         */
        private fun endpointFrom(raw: String): String? {
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            val host = uri.host ?: return null
            if (uri.scheme != "http" || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
            if (uri.path.isNotEmpty() && uri.path != "/") return null
            val octets = host.split('.')
            if (octets.size != 4 || octets.any { it.toIntOrNull()?.let { n -> n !in 0..255 } != false }) return null
            if (octets[0] == "127" || host == "0.0.0.0") return null
            val port = uri.port
            if (port !in 1..65535) return null
            return "http://$host:$port"
        }
    }
}
