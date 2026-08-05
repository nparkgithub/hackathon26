package com.mpquic.server

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.mpquic.core.EngineController
import com.mpquic.core.FileLogger
import com.mpquic.core.NetUtils
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var engine: EngineController
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var statsView: TextView
    private var fileLogger: FileLogger? = null
    private val logBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        statsView = findViewById(R.id.stats)

        val mpAlgo = findViewById<Spinner>(R.id.mpAlgo)
        val ccAlgo = findViewById<Spinner>(R.id.ccAlgo)
        val logLevel = findViewById<Spinner>(R.id.logLevel)
        setSpinner(mpAlgo, listOf("minrtt", "redundant", "roundrobin"), 0)
        setSpinner(ccAlgo, listOf("bbr", "cubic", "bbr3", "copa"), 0)
        setSpinner(logLevel, listOf("off", "error", "warn", "info", "debug", "trace"), 3)

        findViewById<TextView>(R.id.deviceIps).text =
            "Device IPs: " + NetUtils.deviceAddresses()
                .joinToString(", ") { (nif, ip) -> "$nif=$ip" }
                .ifEmpty { "none" }

        fileLogger = FileLogger(this, "server")
        findViewById<TextView>(R.id.logLabel).text = "Log — file: ${fileLogger?.file}"
        appendLog("I: log file: ${fileLogger?.file}")

        engine = EngineController(onLog = ::appendLog, onEvent = ::handleEvent)

        val startBtn = findViewById<Button>(R.id.startBtn)
        val stopBtn = findViewById<Button>(R.id.stopBtn)

        startBtn.setOnClickListener {
            val certPath = NetUtils.assetToFile(this, "server.crt")
            val keyPath = NetUtils.assetToFile(this, "server.key")
            val cfg = JSONObject().apply {
                put("role", "server")
                put("listen", findViewById<EditText>(R.id.listenAddr).text.toString().trim())
                put(
                    "enable_multipath",
                    findViewById<SwitchMaterial>(R.id.multipathSwitch).isChecked
                )
                put("multipath_algorithm", mpAlgo.selectedItem.toString())
                put("congestion_control", ccAlgo.selectedItem.toString())
                put("log_level", logLevel.selectedItem.toString())
                put("echo", findViewById<SwitchMaterial>(R.id.echoSwitch).isChecked)
                put("cert_file", certPath)
                put("key_file", keyPath)
            }
            appendLog("I: starting server: $cfg")
            if (engine.start(cfg.toString())) {
                startBtn.isEnabled = false
                stopBtn.isEnabled = true
            }
        }

        stopBtn.setOnClickListener {
            engine.stop()
            statsView.text = "Not running"
            startBtn.isEnabled = true
            stopBtn.isEnabled = false
            appendLog("I: stopped")
        }
    }

    private fun setSpinner(spinner: Spinner, items: List<String>, default: Int) {
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, items
        )
        spinner.setSelection(default)
    }

    private fun handleEvent(ev: JSONObject) {
        when (ev.optString("type")) {
            "listening" -> appendLog("I: listening on ${ev.optString("addr")}")
            "connected" -> appendLog("I: client connected (multipath=${ev.optBoolean("multipath")})")
            "data" -> appendLog(
                "I: recv ${ev.optInt("bytes")} B on stream ${ev.optLong("stream")}" +
                    " \"${ev.optString("preview")}\""
            )
            "send_complete" -> renderSendSummary(ev)
            "stats" -> renderStats(ev)
            "error" -> appendLog("E: ${ev.optString("message")}")
            "disconnected" -> appendLog("I: client disconnected")
            "stopped" -> appendLog("I: engine stopped")
            else -> appendLog("D: event $ev")
        }
    }

    /** Per-path summary of the echo the server just finished sending back. */
    private fun renderSendSummary(ev: JSONObject) {
        appendLog("I: == echo complete: ${ev.optLong("bytes_queued")} B payload ==")
        val paths = ev.optJSONArray("paths") ?: JSONArray()
        for (i in 0 until paths.length()) {
            val p = paths.getJSONObject(i)
            appendLog(
                "I:   path ${p.optString("local")} -> ${p.optString("remote")}: " +
                    "${p.optLong("bytes_sent")} B / ${p.optLong("pkts_sent")} pkts this send" +
                    " (total ${p.optLong("total_sent_bytes")} B)"
            )
        }
        appendLog("I:   ${NetUtils.ifaceTxSummary()}")
    }

    private fun renderStats(ev: JSONObject) {
        val sb = StringBuilder()
        sb.append("conn#${ev.optLong("conn_index")} multipath=${ev.optBoolean("multipath")}\n")
        sb.append("tx=${ev.optLong("sent_bytes")}B/${ev.optLong("sent_pkts")}p  ")
        sb.append("rx=${ev.optLong("recv_bytes")}B/${ev.optLong("recv_pkts")}p  ")
        sb.append("lost=${ev.optLong("lost_pkts")}\n")
        val paths = ev.optJSONArray("paths") ?: JSONArray()
        for (i in 0 until paths.length()) {
            val p = paths.getJSONObject(i)
            sb.append(
                "path ${p.optString("local")} <- ${p.optString("remote")}\n" +
                    "  srtt=${p.optLong("srtt_us") / 1000.0}ms" +
                    " cwnd=${p.optLong("cwnd")}" +
                    " tx=${p.optLong("sent_bytes")}B" +
                    " rx=${p.optLong("recv_bytes")}B" +
                    " lost=${p.optLong("lost_pkts")}\n"
            )
        }
        statsView.text = sb.toString()
    }

    private fun appendLog(line: String) {
        fileLogger?.write(line)
        logBuffer.append(line).append('\n')
        if (logBuffer.length > 60_000) {
            logBuffer.delete(0, logBuffer.length - 50_000)
        }
        // Stick to the bottom only if the user is already there, so manual
        // scrolling through history isn't yanked back down on every line.
        val stick = !logScroll.canScrollVertically(1)
        logView.text = logBuffer
        if (stick) {
            logScroll.post { logScroll.scrollTo(0, logView.bottom) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.stop()
        fileLogger?.close()
    }
}
