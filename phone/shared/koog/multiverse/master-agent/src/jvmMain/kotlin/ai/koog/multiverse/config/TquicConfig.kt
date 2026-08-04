package ai.koog.multiverse.config

import kotlinx.serialization.Serializable

/** Congestion-control algorithms accepted by tquic (`CongestionControlAlgorithm` FromStr). */
@Serializable
enum class CongestionControl { cubic, bbr, bbr3, copa, dummy, lia, olia }

/** Multipath schedulers accepted by tquic (`MultipathAlgorithm` FromStr). Default = minrtt. */
@Serializable
enum class MultipathAlgorithm { minrtt, redundant, roundrobin, ecf, erf, thle, thlev2 }

/** Log verbosity levels for the transport. */
@Serializable
enum class LogLevel { off, error, warn, info, debug, trace }

/**
 * Full TQUIC transport configuration (grillme_version2 Sec 4.2). Defaults here mirror the library
 * (`Config::new` / `RecoveryConfig::default` / `TransportParams::default`) semantics, NOT the
 * tquic_client CLI defaults, and are the values passed to the Rust `lib` setters via the JNI bridge.
 *
 * The source of truth for actual default values is `tquic_config.xml`; [ai.koog.multiverse.config.TquicConfigLoader]
 * loads that file into this object, applies overrides, and can serialize it back. The values below are
 * only the fallback used when a key is absent from the XML.
 *
 * Units are explicit per field (see KDoc) because several tquic params differ in unit between the CLI
 * (microseconds) and the library setters (milliseconds); this object always uses the library unit.
 */
@Serializable
data class TquicConfig(
    // --- Connection / timeouts ---
    /** ms; 0 = disabled. */
    val maxIdleTimeoutMs: Long = 30_000,
    /** ms. */
    val maxHandshakeTimeoutMs: Long = 30_000,
    /** MUST be >= 2. */
    val activeConnectionIdLimit: Long = 2,
    /** <= 20. */
    val cidLen: Int = 8,
    val maxConcurrentConns: Long = 1_000_000,
    /** clamped to >= 3. */
    val antiAmplificationFactor: Int = 3,
    val sendBatchSize: Int = 64,
    val maxUndecryptablePackets: Int = 10,
    /** packets. */
    val zerorttBufferSize: Int = 1000,
    val enableRetry: Boolean = false,

    // --- MTU / UDP payload ---
    val recvUdpPayloadSize: Int = 65527,
    val sendUdpPayloadSize: Int = 1200,
    val enableDplpmtud: Boolean = true,

    // --- Flow control ---
    val initialMaxData: Long = 10_485_760,
    val initialMaxStreamDataBidiLocal: Long = 5_242_880,
    val initialMaxStreamDataBidiRemote: Long = 2_097_152,
    val initialMaxStreamDataUni: Long = 1_048_576,
    val initialMaxStreamsBidi: Long = 200,
    val initialMaxStreamsUni: Long = 100,
    val maxConnectionWindow: Long = 15_728_640,
    val maxStreamWindow: Long = 6_291_456,

    // --- Congestion control / recovery / pacing ---
    val congestionControl: CongestionControl = CongestionControl.bbr,
    /** packets. */
    val initialCongestionWindow: Long = 10,
    /** packets. */
    val minCongestionWindow: Long = 2,
    /** ms; must be > 0. */
    val initialRttMs: Long = 333,
    val enablePacing: Boolean = true,
    /** ms. */
    val pacingGranularityMs: Long = 1,
    val ptoLinearFactor: Long = 0,
    /** ms; 0 means unset (library default is effectively unbounded). */
    val maxPtoMs: Long = 0,
    /** ms. */
    val maxAckDelayMs: Long = 25,
    val ackDelayExponent: Long = 3,

    // --- BBR-specific ---
    val bbrProbeRttDurationMs: Long = 200,
    val bbrProbeRttBasedOnBdp: Boolean = false,
    val bbrProbeRttCwndGain: Double = 0.75,
    val bbrRtpropFilterLenMs: Long = 10_000,
    val bbrProbeBwCwndGain: Double = 2.0,

    // --- COPA-specific ---
    val copaSlowStartDelta: Double = 0.04,
    val copaSteadyDelta: Double = 0.04,
    val copaUseStandingRtt: Boolean = true,

    // --- Multipath ---
    val enableMultipath: Boolean = false,
    val multipathAlgorithm: MultipathAlgorithm = MultipathAlgorithm.minrtt,
    /** bytes; used by Thle/ThleV2 schedulers. 0 = unset. */
    val fileSizeMpScheduler: Long = 0,
    /** Local interface addresses to add as paths (path_0, path_1, ...). */
    val localAddresses: List<String> = emptyList(),

    // --- PATFB (Path Arrival Time Feedback) ---
    val enablePathArrivalTimeFeedback: Boolean = false,

    // --- TLS / security ---
    val alpn: String = "h3",
    val enableEarlyData: Boolean = false,
    val disableEncryption: Boolean = false,
    val enableStatelessReset: Boolean = true,

    // --- Logging / debug ---
    val logLevel: LogLevel = LogLevel.info,
    val logFile: String = "",
    val keylogFile: String = "",
    val qlogDir: String = "",
    val dumpDir: String = "",
) {
    /** Validate ranges/enums; throws [IllegalArgumentException] on the first violation. */
    fun validate() {
        require(cidLen in 0..20) { "cidLen must be <= 20 (was $cidLen)" }
        require(activeConnectionIdLimit >= 2) { "activeConnectionIdLimit must be >= 2 (was $activeConnectionIdLimit)" }
        require(antiAmplificationFactor >= 3) { "antiAmplificationFactor must be >= 3 (was $antiAmplificationFactor)" }
        require(initialRttMs > 0) { "initialRttMs must be > 0 (was $initialRttMs)" }
        require(maxIdleTimeoutMs >= 0) { "maxIdleTimeoutMs must be >= 0 (was $maxIdleTimeoutMs)" }
        require(minCongestionWindow >= 2) { "minCongestionWindow must be >= 2 (was $minCongestionWindow)" }
        require(initialCongestionWindow >= minCongestionWindow) {
            "initialCongestionWindow ($initialCongestionWindow) must be >= minCongestionWindow ($minCongestionWindow)"
        }
        require(bbrProbeRttCwndGain > 0.0) { "bbrProbeRttCwndGain must be > 0 (was $bbrProbeRttCwndGain)" }
    }
}
