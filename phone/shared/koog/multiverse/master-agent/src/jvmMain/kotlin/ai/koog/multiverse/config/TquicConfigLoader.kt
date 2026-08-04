package ai.koog.multiverse.config

import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Loads [TquicConfig] from a `tquic_config.xml` document whose `<param key="..." default="..."/>`
 * entries are the **source of truth for defaults** (grillme_version2 Sec 4.3, Decision 18).
 *
 * The GUI (separate module) and env overrides edit on top of the loaded defaults. Parsing uses the
 * JDK DOM parser (no extra dependency). Unknown keys are ignored (forward-compatible); missing keys
 * fall back to the [TquicConfig] constructor defaults.
 */
object TquicConfigLoader {

    /** Parse an XML stream into a validated [TquicConfig]. */
    fun load(input: InputStream): TquicConfig {
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(input)
        val params = mutableMapOf<String, String>()
        val nodes = doc.getElementsByTagName("param")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val key = el.getAttribute("key").takeIf { it.isNotBlank() } ?: continue
            // A param carries its default in the "default" attribute; a GUI-saved file may also carry
            // a "value" attribute that overrides it.
            val value = el.getAttribute("value").ifBlank { el.getAttribute("default") }
            params[key] = value
        }
        return fromMap(params).also { it.validate() }
    }

    /** Load defaults from the packaged `tquic_config.xml` resource. */
    fun loadDefault(): TquicConfig {
        val stream = TquicConfigLoader::class.java.classLoader
            .getResourceAsStream("tquic_config.xml")
            ?: error("tquic_config.xml not found on classpath")
        return stream.use { load(it) }
    }

    /** Build a [TquicConfig] from a flat key -> string map, coercing types. */
    fun fromMap(p: Map<String, String>): TquicConfig {
        fun long(k: String, d: Long) = p[k]?.trim()?.toLongOrNull() ?: d
        fun int(k: String, d: Int) = p[k]?.trim()?.toIntOrNull() ?: d
        fun dbl(k: String, d: Double) = p[k]?.trim()?.toDoubleOrNull() ?: d
        fun bool(k: String, d: Boolean) = p[k]?.trim()?.toBooleanStrictOrNull() ?: d
        fun str(k: String, d: String) = p[k]?.trim()?.ifEmpty { d } ?: d
        fun list(k: String): List<String> =
            p[k]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        val d = TquicConfig()
        return TquicConfig(
            maxIdleTimeoutMs = long("max_idle_timeout", d.maxIdleTimeoutMs),
            maxHandshakeTimeoutMs = long("max_handshake_timeout", d.maxHandshakeTimeoutMs),
            activeConnectionIdLimit = long("active_connection_id_limit", d.activeConnectionIdLimit),
            cidLen = int("cid_len", d.cidLen),
            maxConcurrentConns = long("max_concurrent_conns", d.maxConcurrentConns),
            antiAmplificationFactor = int("anti_amplification_factor", d.antiAmplificationFactor),
            sendBatchSize = int("send_batch_size", d.sendBatchSize),
            maxUndecryptablePackets = int("max_undecryptable_packets", d.maxUndecryptablePackets),
            zerorttBufferSize = int("zerortt_buffer_size", d.zerorttBufferSize),
            enableRetry = bool("enable_retry", d.enableRetry),
            recvUdpPayloadSize = int("recv_udp_payload_size", d.recvUdpPayloadSize),
            sendUdpPayloadSize = int("send_udp_payload_size", d.sendUdpPayloadSize),
            enableDplpmtud = bool("enable_dplpmtud", d.enableDplpmtud),
            initialMaxData = long("initial_max_data", d.initialMaxData),
            initialMaxStreamDataBidiLocal = long("initial_max_stream_data_bidi_local", d.initialMaxStreamDataBidiLocal),
            initialMaxStreamDataBidiRemote = long("initial_max_stream_data_bidi_remote", d.initialMaxStreamDataBidiRemote),
            initialMaxStreamDataUni = long("initial_max_stream_data_uni", d.initialMaxStreamDataUni),
            initialMaxStreamsBidi = long("initial_max_streams_bidi", d.initialMaxStreamsBidi),
            initialMaxStreamsUni = long("initial_max_streams_uni", d.initialMaxStreamsUni),
            maxConnectionWindow = long("max_connection_window", d.maxConnectionWindow),
            maxStreamWindow = long("max_stream_window", d.maxStreamWindow),
            congestionControl = enumOf(p["congestion_control_algorithm"], d.congestionControl),
            initialCongestionWindow = long("initial_congestion_window", d.initialCongestionWindow),
            minCongestionWindow = long("min_congestion_window", d.minCongestionWindow),
            initialRttMs = long("initial_rtt", d.initialRttMs),
            enablePacing = bool("enable_pacing", d.enablePacing),
            pacingGranularityMs = long("pacing_granularity", d.pacingGranularityMs),
            ptoLinearFactor = long("pto_linear_factor", d.ptoLinearFactor),
            maxPtoMs = long("max_pto", d.maxPtoMs),
            maxAckDelayMs = long("max_ack_delay", d.maxAckDelayMs),
            ackDelayExponent = long("ack_delay_exponent", d.ackDelayExponent),
            bbrProbeRttDurationMs = long("bbr_probe_rtt_duration", d.bbrProbeRttDurationMs),
            bbrProbeRttBasedOnBdp = bool("bbr_probe_rtt_based_on_bdp", d.bbrProbeRttBasedOnBdp),
            bbrProbeRttCwndGain = dbl("bbr_probe_rtt_cwnd_gain", d.bbrProbeRttCwndGain),
            bbrRtpropFilterLenMs = long("bbr_rtprop_filter_len", d.bbrRtpropFilterLenMs),
            bbrProbeBwCwndGain = dbl("bbr_probe_bw_cwnd_gain", d.bbrProbeBwCwndGain),
            copaSlowStartDelta = dbl("copa_slow_start_delta", d.copaSlowStartDelta),
            copaSteadyDelta = dbl("copa_steady_delta", d.copaSteadyDelta),
            copaUseStandingRtt = bool("copa_use_standing_rtt", d.copaUseStandingRtt),
            enableMultipath = bool("enable_multipath", d.enableMultipath),
            multipathAlgorithm = enumOf(p["multipath_algorithm"], d.multipathAlgorithm),
            fileSizeMpScheduler = long("file_size_mp_scheduler", d.fileSizeMpScheduler),
            localAddresses = list("local_addresses"),
            enablePathArrivalTimeFeedback = bool("enable_path_arrival_time_feedback", d.enablePathArrivalTimeFeedback),
            alpn = str("alpn", d.alpn),
            enableEarlyData = bool("enable_early_data", d.enableEarlyData),
            disableEncryption = bool("disable_encryption", d.disableEncryption),
            enableStatelessReset = bool("enable_stateless_reset", d.enableStatelessReset),
            logLevel = enumOf(p["log_level"], d.logLevel),
            logFile = str("log_file", d.logFile),
            keylogFile = str("keylog_file", d.keylogFile),
            qlogDir = str("qlog_dir", d.qlogDir),
            dumpDir = str("dump_dir", d.dumpDir),
        )
    }

    /** Serialize a config back to the `<param key value/>` XML form (for GUI "Save"). */
    fun toXml(config: TquicConfig): String {
        fun row(key: String, value: Any?) = "    <param key=\"$key\" value=\"${value ?: ""}\"/>"
        val rows = listOf(
            row("max_idle_timeout", config.maxIdleTimeoutMs),
            row("max_handshake_timeout", config.maxHandshakeTimeoutMs),
            row("active_connection_id_limit", config.activeConnectionIdLimit),
            row("cid_len", config.cidLen),
            row("max_concurrent_conns", config.maxConcurrentConns),
            row("anti_amplification_factor", config.antiAmplificationFactor),
            row("send_batch_size", config.sendBatchSize),
            row("max_undecryptable_packets", config.maxUndecryptablePackets),
            row("zerortt_buffer_size", config.zerorttBufferSize),
            row("enable_retry", config.enableRetry),
            row("recv_udp_payload_size", config.recvUdpPayloadSize),
            row("send_udp_payload_size", config.sendUdpPayloadSize),
            row("enable_dplpmtud", config.enableDplpmtud),
            row("initial_max_data", config.initialMaxData),
            row("initial_max_stream_data_bidi_local", config.initialMaxStreamDataBidiLocal),
            row("initial_max_stream_data_bidi_remote", config.initialMaxStreamDataBidiRemote),
            row("initial_max_stream_data_uni", config.initialMaxStreamDataUni),
            row("initial_max_streams_bidi", config.initialMaxStreamsBidi),
            row("initial_max_streams_uni", config.initialMaxStreamsUni),
            row("max_connection_window", config.maxConnectionWindow),
            row("max_stream_window", config.maxStreamWindow),
            row("congestion_control_algorithm", config.congestionControl),
            row("initial_congestion_window", config.initialCongestionWindow),
            row("min_congestion_window", config.minCongestionWindow),
            row("initial_rtt", config.initialRttMs),
            row("enable_pacing", config.enablePacing),
            row("pacing_granularity", config.pacingGranularityMs),
            row("pto_linear_factor", config.ptoLinearFactor),
            row("max_pto", config.maxPtoMs),
            row("max_ack_delay", config.maxAckDelayMs),
            row("ack_delay_exponent", config.ackDelayExponent),
            row("bbr_probe_rtt_duration", config.bbrProbeRttDurationMs),
            row("bbr_probe_rtt_based_on_bdp", config.bbrProbeRttBasedOnBdp),
            row("bbr_probe_rtt_cwnd_gain", config.bbrProbeRttCwndGain),
            row("bbr_rtprop_filter_len", config.bbrRtpropFilterLenMs),
            row("bbr_probe_bw_cwnd_gain", config.bbrProbeBwCwndGain),
            row("copa_slow_start_delta", config.copaSlowStartDelta),
            row("copa_steady_delta", config.copaSteadyDelta),
            row("copa_use_standing_rtt", config.copaUseStandingRtt),
            row("enable_multipath", config.enableMultipath),
            row("multipath_algorithm", config.multipathAlgorithm),
            row("file_size_mp_scheduler", config.fileSizeMpScheduler),
            row("local_addresses", config.localAddresses.joinToString(",")),
            row("enable_path_arrival_time_feedback", config.enablePathArrivalTimeFeedback),
            row("alpn", config.alpn),
            row("enable_early_data", config.enableEarlyData),
            row("disable_encryption", config.disableEncryption),
            row("enable_stateless_reset", config.enableStatelessReset),
            row("log_level", config.logLevel),
            row("log_file", config.logFile),
            row("keylog_file", config.keylogFile),
            row("qlog_dir", config.qlogDir),
            row("dump_dir", config.dumpDir),
        ).joinToString("\n")
        return "<tquicConfig version=\"1\">\n$rows\n</tquicConfig>\n"
    }

    private inline fun <reified E : Enum<E>> enumOf(value: String?, default: E): E {
        val v = value?.trim()?.lowercase() ?: return default
        return enumValues<E>().firstOrNull { it.name.lowercase() == v } ?: default
    }
}
