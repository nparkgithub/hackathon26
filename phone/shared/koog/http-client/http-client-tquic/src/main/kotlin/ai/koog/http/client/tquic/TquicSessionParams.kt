package ai.koog.http.client.tquic

/**
 * Transport parameters the [TquicKoogHttpClient] passes to [TquicNative.openSession]. Kept as a plain
 * holder in this module (no dependency on the master-agent `TquicConfig`) to avoid a module cycle; the
 * master-agent layer maps its `TquicConfig` onto this.
 */
data class TquicSessionParams(
    val serverName: String,
    val alpn: String = "h3",
    val primaryLocalAddr: String = "0.0.0.0:0",
    val enableMultipath: Boolean = false,
    val multipathAlgorithm: String = "minrtt",
    val enablePatfb: Boolean = false,
    val fileSizeMpScheduler: Long = 0,
    val congestionControl: String = "bbr",
    val idleTimeoutMs: Long = 30_000,
    val initialRttMs: Long = 333,
    /** Extra local addresses to add as additional paths (path_1, path_2, ...). */
    val additionalLocalAddresses: List<String> = emptyList(),
)
