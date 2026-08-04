package com.example.devmon

import org.json.JSONObject

/** One telemetry snapshot pushed by a desktop peer. */
data class Telemetry(
    val host: String,
    val os: String,
    val ip: String,
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
        )
    }
}
