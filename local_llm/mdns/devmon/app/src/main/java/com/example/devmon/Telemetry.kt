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
    val llm: Llm?,
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
            val llm = o.optJSONObject("llm")?.let {
                Llm(
                    name = it.optString("name", "?"),
                    parameters = it.optString("parameters", "?"),
                    quantization = it.optString("quantization", "?"),
                    contextLength = it.optInt("context_length", 0),
                    family = it.optString("family", "?"),
                )
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
                llm = llm,
            )
        }
    }
}
