package com.example.devmon

import android.Manifest
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.devmon.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding
    private lateinit var advertiser: AdvertiserService
    private lateinit var analyzeServer: AnalyzeHttpServer
    private var selectedImage: ByteArray? = null
    private var selectedImageMimeType: String = "image/jpeg"

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            ui.txtAnalysis.text = "Could not read the selected image."
        } else if (bytes.size > MAX_IMAGE_BYTES) {
            ui.txtAnalysis.text = "Image is too large (max 8 MiB). Choose a smaller image."
        } else {
            selectedImage = bytes
            selectedImageMimeType = contentResolver.getType(uri) ?: "image/jpeg"
            ui.txtSelectedImage.text = "Selected image: ${bytes.size / 1024} KiB"
            ui.btnAnalyze.isEnabled = true
        }
    }

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { advertiser.start() }   // start regardless; advertising works without it on most builds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)

        advertiser = AdvertiserService(this)
        analyzeServer = AnalyzeHttpServer { advertiser.peers.value }
        analyzeServer.start()

        ui.btnToggle.setOnClickListener {
            if (advertiser.state.value is AdvertiserService.State.Advertising) {
                advertiser.stop()
            } else {
                ensurePermissionsThenStart()
            }
        }
        ui.btnPickImage.setOnClickListener { pickImage.launch("image/*") }
        ui.btnAnalyze.setOnClickListener { analyzeSelectedImage() }
        ui.btnAnalyze.isEnabled = false

        ui.txtSelf.text = describeSelf()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(advertiser.state, advertiser.peers, advertiser.log) { s, p, l ->
                    Triple(s, p, l)
                }.collect { (state, peers, log) -> render(state, peers, log) }
            }
        }
    }

    private fun ensurePermissionsThenStart() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPerms.launch(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES))
        } else {
            advertiser.start()
        }
    }

    private fun render(
        state: AdvertiserService.State,
        peers: Map<String, Telemetry>,
        log: List<String>,
    ) {
        ui.txtStatus.text = when (state) {
            is AdvertiserService.State.Idle -> "Idle - not advertising"
            is AdvertiserService.State.Registering -> "Registering..."
            is AdvertiserService.State.Advertising ->
                "Advertising '${state.serviceName}'\n${AdvertiserService.SERVICE_TYPE} on port ${state.port}"
            is AdvertiserService.State.Failed -> "Failed: ${state.reason}"
        }
        ui.btnToggle.text =
            if (state is AdvertiserService.State.Advertising) "Stop advertising" else "Start advertising"

        ui.txtPeers.text = if (peers.isEmpty()) {
            "No desktop peers connected.\n\nRun on Windows:\n  python discover_and_report.py"
        } else {
            peers.entries.joinToString("\n\n") { (addr, t) ->
                buildString {
                    appendLine("${t.host}  ($addr)")
                    appendLine("  OS         ${t.os}")
                    appendLine("  IP         ${t.ip}")
                    appendLine("  OpenAI     ${t.openAiEndpoint ?: "not reported"}")
                    appendLine("  Interface  ${t.iface}")
                    appendLine("  CPU        ${"%.1f".format(t.cpuPercent)}%  (${t.cpuCount} cores)")
                    appendLine("  Memory     ${"%.1f".format(t.memPercent)}%")
                    if (t.interfaces.isNotEmpty()) {
                        appendLine("  All interfaces:")
                        t.interfaces.forEach { i ->
                            val speed = i.speedMbps?.let { "${it} Mbps" } ?: "unknown speed"
                            appendLine("    - ${i.name}: ${i.ipv4} ($speed${if (i.up) "" else ", down"})")
                        }
                    }
                    if (t.llms.isNotEmpty()) {
                        appendLine("  Local LLMs (${t.llms.size}):")
                        t.llms.forEach { llm ->
                            appendLine("    - ${llm.name}  ${llm.parameters} params  ${llm.quantization}")
                            appendLine("      ctx ${llm.contextLength}  family ${llm.family}  vision ${llm.vision}")
                        }
                    }
                }
            }
        }

        ui.txtLog.text = log.takeLast(15).joinToString("\n")
    }

    private fun analyzeSelectedImage() {
        val image = selectedImage ?: run {
            ui.txtAnalysis.text = "Select an image first."
            return
        }
        val target = selectAnalysisTarget(advertiser.peers.value)
        if (target !is AnalysisTarget.Found) {
            ui.txtAnalysis.text = when (target) {
                is AnalysisTarget.NoPeer -> "No peer reported an OpenAI-compatible endpoint yet."
                is AnalysisTarget.NoVisionModel -> "A peer is connected, but none reported a vision model."
                is AnalysisTarget.Found -> return  // unreachable
            }
            return
        }

        ui.btnAnalyze.isEnabled = false
        ui.txtAnalysis.text = "Analyzing with ${target.model.name} at ${target.endpoint}…"
        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    OpenAiAnalysisClient.analyze(target.endpoint, target.model, image, selectedImageMimeType)
                }
            }
            ui.txtAnalysis.text = outcome.fold(
                onSuccess = { "Allergy information (informational only):\n$it" },
                onFailure = { "Analysis failed: ${it.describeCauseChain()}" },
            )
            ui.btnAnalyze.isEnabled = selectedImage != null
        }
    }

    /** This device's own WiFi address, so you can sanity-check both ends are on one subnet. */
    private fun describeSelf(): String {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ssid = if (Build.VERSION.SDK_INT < 33) {
            @Suppress("DEPRECATION") wifi.connectionInfo.ssid ?: "?"
        } else "(SSID hidden on API 33+)"

        val addrs = buildList {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
                if (!nif.isUp || nif.isLoopback) return@forEach
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>().forEach { a ->
                    add("${nif.name}: ${a.hostAddress}")
                }
            }
        }
        return "This device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})\n" +
                "WiFi: $ssid\n" + addrs.joinToString("\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        advertiser.shutdown()
        analyzeServer.shutdown()
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    }
}
