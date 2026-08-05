package com.mpquic.client

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
import com.mpquic.core.NetUtils
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var engine: EngineController
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var statsView: TextView
    private val logBuffer = StringBuilder()
    private var connected = false

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
            "Device IPs: " + NetUtils.deviceIpv4Addresses()
                .joinToString(", ") { (nif, ip) -> "$nif=$ip" }
                .ifEmpty { "none" }

        engine = EngineController(onLog = ::appendLog, onEvent = ::handleEvent)

        val connectBtn = findViewById<Button>(R.id.connectBtn)
        val disconnectBtn = findViewById<Button>(R.id.disconnectBtn)
        val sendBtn = findViewById<Button>(R.id.sendBtn)
        val sendBulkBtn = findViewById<Button>(R.id.sendBulkBtn)

        connectBtn.setOnClickListener {
            val cfg = JSONObject().apply {
                put("role", "client")
                put("connect_to", findViewById<EditText>(R.id.serverAddr).text.toString().trim())
                put(
                    "local_addresses",
                    JSONArray(
                        findViewById<EditText>(R.id.localAddrs).text.toString()
                            .split(',')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                    )
                )
                put(
                    "enable_multipath",
                    findViewById<SwitchMaterial>(R.id.multipathSwitch).isChecked
                )
                put("multipath_algorithm", mpAlgo.selectedItem.toString())
                put("congestion_control", ccAlgo.selectedItem.toString())
                put("log_level", logLevel.selectedItem.toString())
            }
            appendLog("I: starting client: $cfg")
            if (engine.start(cfg.toString())) {
                connectBtn.isEnabled = false
                disconnectBtn.isEnabled = true
                sendBtn.isEnabled = true
                sendBulkBtn.isEnabled = true
            }
        }

        disconnectBtn.setOnClickListener {
            engine.stop()
            connected = false
            statsView.text = "Not connected"
            connectBtn.isEnabled = true
            disconnectBtn.isEnabled = false
            sendBtn.isEnabled = false
            sendBulkBtn.isEnabled = false
            appendLog("I: stopped")
        }

        sendBtn.setOnClickListener {
            val text = findViewById<EditText>(R.id.message).text.toString()
            if (text.isNotEmpty()) {
                engine.send(text.toByteArray())
                appendLog("I: sent ${text.toByteArray().size} bytes")
            }
        }

        sendBulkBtn.setOnClickListener {
            val payload = ByteArray(1024 * 1024) { (it % 251).toByte() }
            engine.send(payload)
            appendLog("I: sent 1 MiB test payload")
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
            "connected" -> {
                connected = true
                appendLog("I: connected (multipath=${ev.optBoolean("multipath")})")
            }
            "path_added" -> appendLog("I: path added ${ev.optString("local")} -> ${ev.optString("remote")}")
            "path_failed" -> appendLog("W: path failed ${ev.optString("local")}: ${ev.optString("error")}")
            "data" -> appendLog(
                "I: recv ${ev.optInt("bytes")} B on stream ${ev.optLong("stream")}" +
                    " \"${ev.optString("preview")}\""
            )
            "stats" -> renderStats(ev)
            "error" -> appendLog("E: ${ev.optString("message")}")
            "disconnected" -> {
                connected = false
                appendLog("I: disconnected")
            }
            "stopped" -> appendLog("I: engine stopped")
            else -> appendLog("D: event $ev")
        }
    }

    private fun renderStats(ev: JSONObject) {
        val sb = StringBuilder()
        sb.append("multipath=${ev.optBoolean("multipath")}  ")
        sb.append("tx=${ev.optLong("sent_bytes")}B/${ev.optLong("sent_pkts")}p  ")
        sb.append("rx=${ev.optLong("recv_bytes")}B/${ev.optLong("recv_pkts")}p  ")
        sb.append("lost=${ev.optLong("lost_pkts")}\n")
        val paths = ev.optJSONArray("paths") ?: JSONArray()
        for (i in 0 until paths.length()) {
            val p = paths.getJSONObject(i)
            sb.append(
                "path ${p.optString("local")} -> ${p.optString("remote")}\n" +
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
        logBuffer.append(line).append('\n')
        if (logBuffer.length > 60_000) {
            logBuffer.delete(0, logBuffer.length - 50_000)
        }
        logView.text = logBuffer
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.stop()
    }
}
