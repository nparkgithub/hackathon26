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
import com.mpquic.core.FileLogger
import com.mpquic.core.IfaceAddrs
import com.mpquic.core.NetUtils
import com.mpquic.core.NetworkMonitor
import com.mpquic.core.PathGraphView
import com.mpquic.core.UdpIngest
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var engine: EngineController
    private lateinit var monitor: NetworkMonitor
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var statsView: TextView
    private lateinit var localAddrs: EditText
    private lateinit var autoAddrSwitch: SwitchMaterial
    private lateinit var ifaceStatus: TextView
    private var fileLogger: FileLogger? = null
    private lateinit var pathGraph: PathGraphView
    private val prevPathBytes = mutableMapOf<String, Long>()
    private val ifaceByIp = mutableMapOf<String, String>()
    private val logBuffer = StringBuilder()
    private var udpIngest: UdpIngest? = null
    private var h3Running = false

    // Read from the UDP receive thread, written on the UI thread.
    @Volatile
    private var connected = false

    /** Interface owning the local end of a path ("10.73.51.51:39696" -> "wlan0"). */
    private fun ifaceFor(localHostPort: String): String =
        ifaceByIp.getOrPut(localHostPort) { NetUtils.ifaceLabelFor(localHostPort) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        statsView = findViewById(R.id.stats)
        pathGraph = findViewById(R.id.pathGraph)

        val mpAlgo = findViewById<Spinner>(R.id.mpAlgo)
        val ccAlgo = findViewById<Spinner>(R.id.ccAlgo)
        val logLevel = findViewById<Spinner>(R.id.logLevel)
        val bulkSize = findViewById<Spinner>(R.id.bulkSize)
        setSpinner(mpAlgo, listOf("minrtt", "redundant", "roundrobin"), 0)
        setSpinner(ccAlgo, listOf("bbr", "cubic", "bbr3", "copa"), 0)
        setSpinner(logLevel, listOf("off", "error", "warn", "info", "debug", "trace"), 3)
        setSpinner(
            bulkSize,
            listOf("1 MB", "2 MB", "5 MB", "10 MB", "25 MB", "30 MB", "40 MB", "50 MB", "100 MB"),
            0
        )

        findViewById<TextView>(R.id.deviceIps).text =
            "Device IPs: " + NetUtils.deviceAddresses()
                .joinToString(", ") { (nif, ip) -> "$nif=$ip" }
                .ifEmpty { "none" }

        localAddrs = findViewById(R.id.localAddrs)
        autoAddrSwitch = findViewById(R.id.autoAddrSwitch)
        ifaceStatus = findViewById(R.id.ifaceStatus)

        fileLogger = FileLogger(this, "client")
        findViewById<TextView>(R.id.logLabel).text = "Log — file: ${fileLogger?.file}"
        appendLog("I: log file: ${fileLogger?.file}")

        // Auto-fill the multipath local addresses from wlan*/rmnet_data* and
        // keep them fresh as networks come and go. The field itself stays
        // editable; the switch only controls whether auto updates overwrite it.
        monitor = NetworkMonitor(this, ::onIfacesChanged)
        autoAddrSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) monitor.refresh()
        }
        monitor.start()

        engine = EngineController(onLog = ::appendLog, onEvent = ::handleEvent)

        val connectBtn = findViewById<Button>(R.id.connectBtn)
        val disconnectBtn = findViewById<Button>(R.id.disconnectBtn)
        val sendBtn = findViewById<Button>(R.id.sendBtn)
        val sendBulkBtn = findViewById<Button>(R.id.sendBulkBtn)

        connectBtn.setOnClickListener {
            val cfg = JSONObject().apply {
                put("role", "client")
                put("connect_to", findViewById<EditText>(R.id.serverAddr).text.toString().trim())
                // Second remote, opposite address family -- lets a local
                // path whose only real address is the other family (e.g.
                // rmnet, often IPv6-only) join the same connection instead
                // of being dropped for address-family mismatch. Optional --
                // omitted entirely when blank, so single-remote behavior is
                // unchanged unless this field is actually filled in.
                findViewById<EditText>(R.id.serverAddrAlt).text.toString().trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { put("connect_to_alt", it) }
                put(
                    "local_addresses",
                    JSONArray(
                        localAddrs.text.toString()
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
                findViewById<Button>(R.id.h3Btn).isEnabled = true
            }
        }

        disconnectBtn.setOnClickListener {
            engine.stop()
            connected = false
            prevPathBytes.clear()
            statsView.text = "Not connected"
            connectBtn.isEnabled = true
            disconnectBtn.isEnabled = false
            sendBtn.isEnabled = false
            sendBulkBtn.isEnabled = false
            resetH3Controls()
            appendLog("I: stopped")
        }

        sendBtn.setOnClickListener {
            val text = findViewById<EditText>(R.id.message).text.toString()
            if (text.isNotEmpty()) {
                engine.send(text.toByteArray())
                appendLog("I: sent ${text.toByteArray().size} bytes")
            }
        }

        // Plain-UDP payload intake: datagrams arriving on this local port are
        // forwarded into the QUIC connection as opaque payload.
        val udpBtn = findViewById<Button>(R.id.udpBtn)
        val udpPort = findViewById<EditText>(R.id.udpPort)
        udpBtn.setOnClickListener {
            val running = udpIngest
            if (running == null) {
                val port = udpPort.text.toString().trim().toIntOrNull()
                if (port == null || port !in 1..65535) {
                    appendLog("E: invalid UDP port '${udpPort.text}'")
                    return@setOnClickListener
                }
                val ingest = UdpIngest(
                    onData = { data, from ->
                        // Receive-thread context: nativeSend is thread-safe.
                        if (connected && engine.send(data)) {
                            runOnUiThread {
                                appendLog("I: udp-in ${data.size} B from $from -> QUIC")
                            }
                        } else {
                            runOnUiThread {
                                appendLog("W: udp-in ${data.size} B from $from dropped (not connected)")
                            }
                        }
                    },
                    onLog = { line -> runOnUiThread { appendLog(line) } },
                )
                if (ingest.start(port)) {
                    udpIngest = ingest
                    udpPort.isEnabled = false
                    udpBtn.text = "Stop UDP RX"
                    appendLog("I: UDP ingest listening on 0.0.0.0:$port -> QUIC")
                }
            } else {
                running.stop()
                udpIngest = null
                udpPort.isEnabled = true
                udpBtn.text = "Start UDP RX"
                appendLog("I: UDP ingest stopped")
            }
        }

        // HTTP/3 intake: a real local HTTP/3 server whose requests (e.g.
        // large JPEG POSTs) are tunneled over MPQUIC; the peer's response is
        // returned to the same HTTP/3 client.
        val h3Btn = findViewById<Button>(R.id.h3Btn)
        val h3Port = findViewById<EditText>(R.id.h3Port)
        h3Btn.setOnClickListener {
            if (!h3Running) {
                val port = h3Port.text.toString().trim().toIntOrNull()
                if (port == null || port !in 1..65535) {
                    appendLog("E: invalid HTTP/3 port '${h3Port.text}'")
                    return@setOnClickListener
                }
                val cert = NetUtils.assetToFile(this, "server.crt")
                val key = NetUtils.assetToFile(this, "server.key")
                if (engine.h3Listen(port, cert, key)) {
                    h3Running = true
                    h3Port.isEnabled = false
                    h3Btn.text = "Stop HTTP/3 RX"
                }
            } else {
                engine.h3Stop()
                h3Running = false
                h3Port.isEnabled = true
                h3Btn.text = "Start HTTP/3 RX"
            }
        }

        sendBulkBtn.setOnClickListener {
            val sizeMb = bulkSize.selectedItem.toString().substringBefore(' ').toInt()
            val unit = ByteArray(1024 * 1024) { (it % 251).toByte() }
            val payload = ByteArray(sizeMb * unit.size)
            for (i in 0 until sizeMb) {
                unit.copyInto(payload, i * unit.size)
            }
            engine.send(payload)
            appendLog("I: sent $sizeMb MB test payload")
        }
    }

    /** The h3 listener lives inside the engine, so it dies with the tunnel. */
    private fun resetH3Controls() {
        h3Running = false
        findViewById<EditText>(R.id.h3Port).isEnabled = true
        findViewById<Button>(R.id.h3Btn).apply {
            text = "Start HTTP/3 RX"
            isEnabled = false
        }
    }

    private fun onIfacesChanged(ifaces: List<IfaceAddrs>) {
        ifaceStatus.text = if (ifaces.isEmpty()) {
            "Path interfaces: none up (wlan*/rmnet_data*)"
        } else {
            "Path interfaces:\n" + ifaces.joinToString("\n") { i ->
                "  ${i.name}: ${i.all.joinToString(", ").ifEmpty { "no usable address" }}"
            }
        }
        if (autoAddrSwitch.isChecked) {
            val text = NetUtils.defaultLocalAddresses(ifaces).joinToString(", ")
            if (localAddrs.text.toString() != text) {
                localAddrs.setText(text)
            }
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
            "h3_listening" -> appendLog("I: HTTP/3 listener on 0.0.0.0:${ev.optInt("port")} -> tunnel")
            "h3_stopped" -> appendLog("I: HTTP/3 listener stopped")
            "h3_request" -> appendLog(
                "I: h3 ${ev.optString("method")} ${ev.optString("path")}" +
                    " (${ev.optString("content_type")}) ${ev.optInt("bytes")} B" +
                    " -> tunnel stream ${ev.optLong("tunnel_stream")}"
            )
            "h3_response" -> appendLog(
                "I: h3 response ${ev.optString("status")} ${ev.optInt("bytes")} B" +
                    " <- tunnel stream ${ev.optLong("tunnel_stream")}"
            )
            "h3_error" -> appendLog("E: h3: ${ev.optString("message")}")
            "send_complete" -> renderSendSummary(ev)
            "stats" -> renderStats(ev)
            "error" -> appendLog("E: ${ev.optString("message")}")
            "disconnected" -> {
                connected = false
                appendLog("I: disconnected")
                // A client with no connection is done — stop the engine so
                // Connect works again immediately.
                engine.stop()
                prevPathBytes.clear()
                statsView.text = "Not connected"
                findViewById<Button>(R.id.connectBtn).isEnabled = true
                findViewById<Button>(R.id.disconnectBtn).isEnabled = false
                findViewById<Button>(R.id.sendBtn).isEnabled = false
                findViewById<Button>(R.id.sendBulkBtn).isEnabled = false
                resetH3Controls()
            }
            "stopped" -> appendLog("I: engine stopped")
            else -> appendLog("D: event $ev")
        }
    }

    /**
     * Post-transfer summary: payload size, bytes each QUIC path carried
     * (QUIC packets incl. protocol overhead), and interface-level TX
     * counters as reported by `ifconfig`.
     */
    private fun renderSendSummary(ev: JSONObject) {
        appendLog("I: == send complete: ${ev.optLong("bytes_queued")} B payload ==")
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
        sb.append("multipath=${ev.optBoolean("multipath")}  ")
        sb.append("tx=${ev.optLong("sent_bytes")}B/${ev.optLong("sent_pkts")}p  ")
        sb.append("rx=${ev.optLong("recv_bytes")}B/${ev.optLong("recv_pkts")}p  ")
        sb.append("lost=${ev.optLong("lost_pkts")}\n")
        val paths = ev.optJSONArray("paths") ?: JSONArray()
        val samples = mutableMapOf<String, Float>()
        for (i in 0 until paths.length()) {
            val p = paths.getJSONObject(i)
            val local = p.optString("local")
            val key = "${ifaceFor(local)} ${local} -> ${p.optString("remote")}"
            sb.append(
                "path $key\n" +
                    "  srtt=${p.optLong("srtt_us") / 1000.0}ms" +
                    " cwnd=${p.optLong("cwnd")}" +
                    " tx=${p.optLong("sent_bytes")}B" +
                    " rx=${p.optLong("recv_bytes")}B" +
                    " lost=${p.optLong("lost_pkts")}\n"
            )
            // Bytes sent since the previous stats tick -> graph sample.
            val sentBytes = p.optLong("sent_bytes")
            val prev = prevPathBytes[key]
            if (prev != null && sentBytes >= prev) {
                samples[key] = (sentBytes - prev).toFloat()
            }
            prevPathBytes[key] = sentBytes
        }
        if (samples.isNotEmpty()) pathGraph.addSamples(samples)
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
        udpIngest?.stop()
        udpIngest = null
        monitor.stop()
        engine.stop()
        fileLogger?.close()
    }
}
