package ai.koog.multiverse.android

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Minimal status UI for the Koog Master Agent Android app: start/stop the hosting Service, probe
 * `/v1/health`, and inspect the live registry + routing policy (diagnostics menus). Built with plain
 * Views (no Compose) to keep the APK build simple on this toolchain.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class DeviceInfo(
        val deviceId: String,
        val role: String,
        val host: String,
        val port: Int,
        val multipath: String,
        val models: List<String>,
        val reachable: Boolean,
        val gpuLoad: Double,
    )

    @Serializable
    private data class DevicesBody(val devices: List<DeviceInfo>)

    @Serializable
    private data class RankedCandidateBody(val deviceId: String, val gpuLoad: Double, val selected: Boolean)

    @Serializable
    private data class PolicyTierBody(val tier: Int, val name: String, val candidates: List<RankedCandidateBody>)

    @Serializable
    private data class RoutingPolicyBody(
        val requestedModel: String,
        val wifiUp: Boolean,
        val g5Up: Boolean,
        val tiers: List<PolicyTierBody>,
        val decisionTarget: String?,
        val decisionDeviceId: String?,
        val useMultipath: Boolean,
        val error: String?,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }
        val title = TextView(this).apply {
            text = "Koog Master Agent"
            textSize = 22f
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Stopped. Server will listen on 127.0.0.1:${AndroidConfig.PORT}"
            textSize = 14f
            setPadding(0, 48, 0, 48)
        }
        val startBtn = Button(this).apply {
            text = "Start agent"
            setOnClickListener {
                MasterAgentService.start(this@MainActivity)
                status.text = "Started. Hosting on 127.0.0.1:${AndroidConfig.PORT}"
            }
        }
        val stopBtn = Button(this).apply {
            text = "Stop agent"
            setOnClickListener {
                MasterAgentService.stop(this@MainActivity)
                status.text = "Stopped."
            }
        }
        val healthBtn = Button(this).apply {
            text = "Check /v1/health"
            setOnClickListener { checkHealth() }
        }
        val devicesBtn = Button(this).apply {
            text = "View devices"
            setOnClickListener { showDevices() }
        }
        val routingBtn = Button(this).apply {
            text = "View routing policy"
            setOnClickListener { showRoutingPolicy() }
        }

        root.addView(title)
        root.addView(status)
        root.addView(startBtn)
        root.addView(stopBtn)
        root.addView(healthBtn)
        root.addView(devicesBtn)
        root.addView(routingBtn)
        setContentView(root)
    }

    private fun checkHealth() {
        status.text = "Checking..."
        thread {
            val result = runCatching {
                val conn = URL("http://127.0.0.1:${AndroidConfig.PORT}/v1/health").openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.inputStream.bufferedReader().use { it.readText() }
            }.getOrElse { "error: ${it.message}" }
            runOnUiThread { status.text = result }
        }
    }

    private fun showDevices() {
        thread {
            val text = runCatching {
                val raw = get("/v1/registry/devices")
                formatDevices(json.decodeFromString(DevicesBody.serializer(), raw))
            }.getOrElse { "error: ${it.message}" }
            runOnUiThread { showScrollableDialog("Registered devices", text) }
        }
    }

    private fun showRoutingPolicy() {
        thread {
            val text = runCatching {
                val raw = get("/v1/routing/policy")
                formatRoutingPolicy(json.decodeFromString(RoutingPolicyBody.serializer(), raw))
            }.getOrElse { "error: ${it.message}" }
            runOnUiThread { showScrollableDialog("Routing policy", text) }
        }
    }

    private fun get(path: String): String {
        val conn = URL("http://127.0.0.1:${AndroidConfig.PORT}$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun formatDevices(body: DevicesBody): String {
        if (body.devices.isEmpty()) return "(no devices registered)"
        return body.devices.joinToString("\n\n") { d ->
            val mode = if (d.role == "remote_agent") "remote" else "local"
            "${d.deviceId}  [$mode]\n" +
                "  ${d.host}:${d.port}\n" +
                "  multipath: ${d.multipath}\n" +
                "  models: ${d.models.joinToString(", ")}\n" +
                "  gpuLoad: ${d.gpuLoad}   reachable: ${d.reachable}"
        }
    }

    private fun formatRoutingPolicy(body: RoutingPolicyBody): String {
        val header = "Requested model: ${body.requestedModel}   wifi:${if (body.wifiUp) "up" else "down"}" +
            "  5g:${if (body.g5Up) "up" else "down"}"

        val tierLines = body.tiers.joinToString("\n\n") { t ->
            val title = "Tier ${t.tier} - ${t.name}"
            if (t.candidates.isEmpty()) {
                "$title\n  (no candidates)"
            } else {
                title + "\n" + t.candidates.joinToString("\n") { c ->
                    val marker = if (c.selected) "-> " else "   "
                    val tag = if (c.selected) "  [SELECTED]" else ""
                    "$marker${c.deviceId}  gpuLoad=${c.gpuLoad}$tag"
                }
            }
        }

        val decision = if (body.error != null) {
            "Decision: FAILED - ${body.error}"
        } else {
            "Decision: ${body.decisionTarget} -> ${body.decisionDeviceId}   multipath=${body.useMultipath}"
        }

        return "$header\n\n$tierLines\n\n$decision"
    }

    /**
     * A custom (non-AlertDialog) dialog: AlertDialog's content panel wraps to its content height
     * regardless of window resizing, which left large dead space below short content and clipped long
     * content. This lays out title / scrollable body / close button directly so the body can be given
     * `weight = 1` and genuinely fill (and scroll within) the resized window.
     */
    private fun showScrollableDialog(title: String, message: String) {
        val titleView = TextView(this).apply {
            text = title
            textSize = 20f
            setPadding(56, 40, 56, 24)
        }
        val bodyView = TextView(this).apply {
            text = message
            textSize = 16f
            setPadding(56, 8, 56, 24)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            addView(bodyView)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val closeBtn = Button(this).apply {
            text = "Close"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.END; setMargins(0, 16, 32, 24) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(scroll)
            addView(closeBtn)
        }

        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(root)
            setCancelable(true)
        }
        closeBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()

        val displayMetrics = resources.displayMetrics
        dialog.window?.setLayout(
            (displayMetrics.widthPixels * 0.94).toInt(),
            (displayMetrics.heightPixels * 0.85).toInt(),
        )
    }
}
