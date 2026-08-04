package ai.koog.multiverse

import ai.koog.multiverse.config.CongestionControl
import ai.koog.multiverse.config.MultipathAlgorithm
import ai.koog.multiverse.config.TquicConfig
import ai.koog.multiverse.config.TquicConfigLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TquicConfigLoaderTest {

    @Test
    fun loadsDefaultResource() {
        val cfg = TquicConfigLoader.loadDefault()
        assertEquals(CongestionControl.bbr, cfg.congestionControl)
        assertEquals(MultipathAlgorithm.minrtt, cfg.multipathAlgorithm)
        assertEquals(8, cfg.cidLen)
        assertEquals("h3", cfg.alpn)
        assertEquals(30_000, cfg.maxIdleTimeoutMs)
    }

    @Test
    fun overridesFromValueAttributeWinOverDefault() {
        val xml = """
            <tquicConfig>
              <category name="multipath">
                <param key="enable_multipath" default="false" value="true"/>
                <param key="multipath_algorithm" default="minrtt" value="thlev2"/>
              </category>
            </tquicConfig>
        """.trimIndent()
        val cfg = TquicConfigLoader.load(xml.byteInputStream())
        assertTrue(cfg.enableMultipath)
        assertEquals(MultipathAlgorithm.thlev2, cfg.multipathAlgorithm)
    }

    @Test
    fun roundTripsThroughXml() {
        val original = TquicConfig(enableMultipath = true, cidLen = 12, initialRttMs = 500)
        val xml = TquicConfigLoader.toXml(original)
        val reparsed = TquicConfigLoader.load(xml.byteInputStream())
        assertEquals(true, reparsed.enableMultipath)
        assertEquals(12, reparsed.cidLen)
        assertEquals(500, reparsed.initialRttMs)
    }

    @Test
    fun validationRejectsCidLenTooLarge() {
        assertFailsWith<IllegalArgumentException> { TquicConfig(cidLen = 21).validate() }
    }

    @Test
    fun validationRejectsActiveConnIdLimitBelowTwo() {
        assertFailsWith<IllegalArgumentException> { TquicConfig(activeConnectionIdLimit = 1).validate() }
    }
}
