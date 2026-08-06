package com.example.devmon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerSelectionTest {

    private fun telemetry(endpoint: String?, vision: Boolean?): Telemetry = Telemetry(
        host = "h", os = "os", ip = "1.2.3.4",
        openAiEndpoint = endpoint,
        iface = "wlan0", cpuPercent = 0.0, cpuCount = 1, memPercent = 0.0,
        interfaces = emptyList(),
        llms = if (vision == null) emptyList() else listOf(
            Telemetry.Llm("m", "7b", "q4", 4096, "family", vision)
        ),
    )

    @Test
    fun `no peers means NoPeer`() {
        assertEquals(AnalysisTarget.NoPeer, selectAnalysisTarget(emptyMap()))
    }

    @Test
    fun `peer with no endpoint means NoPeer`() {
        val peers = mapOf("a" to telemetry(endpoint = null, vision = true))
        assertEquals(AnalysisTarget.NoPeer, selectAnalysisTarget(peers))
    }

    @Test
    fun `peer with endpoint but no vision model means NoVisionModel`() {
        val peers = mapOf("a" to telemetry(endpoint = "http://192.168.1.5:8000", vision = false))
        assertEquals(AnalysisTarget.NoVisionModel, selectAnalysisTarget(peers))
    }

    @Test
    fun `peer with endpoint and vision model is Found`() {
        val peers = mapOf("a" to telemetry(endpoint = "http://192.168.1.5:8000", vision = true))
        val target = selectAnalysisTarget(peers)
        assertTrue(target is AnalysisTarget.Found)
        target as AnalysisTarget.Found
        assertEquals("http://192.168.1.5:8000", target.endpoint)
        assertEquals("m", target.model.name)
    }

    @Test
    fun `skips non-vision peer to find a vision peer`() {
        val peers = mapOf(
            "a" to telemetry(endpoint = "http://192.168.1.5:8000", vision = false),
            "b" to telemetry(endpoint = "http://192.168.1.6:8000", vision = true),
        )
        val target = selectAnalysisTarget(peers)
        assertTrue(target is AnalysisTarget.Found)
        assertEquals("http://192.168.1.6:8000", (target as AnalysisTarget.Found).endpoint)
    }
}
