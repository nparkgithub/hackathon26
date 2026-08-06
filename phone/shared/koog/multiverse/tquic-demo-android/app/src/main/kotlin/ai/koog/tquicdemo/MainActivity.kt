package ai.koog.tquicdemo

import ai.koog.http.client.tquic.TquicNative
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "tquic_demo"

/** The loopback port both the phase-3 client check and the phase-4 server check use. */
private const val LOOPBACK_PORT = 19500

/**
 * Scaffold verification tool, not the real app UI (that lands in a later
 * phase). Four independent, increasingly real checks:
 *
 *  1. Does libtquic_jni.so load and respond to a JNI call at all.
 *  2. Does the bundled throwaway cert/key actually satisfy tquic's TLS
 *     setup (a real serverStart()/serverStop() round trip on a scratch port).
 *  3+4. A same-device loopback round trip: start the server (4a), start the
 *     client pointed at 127.0.0.1:LOOPBACK_PORT (3a), and confirm messages
 *     sent by 3a actually show up in 4's received-message log with a real
 *     "OK" status on the client side -- not the timeout/closed behavior
 *     phase 3 exercised on its own against nothing listening. This is
 *     deliberately NOT the final two-device test: it proves the client and
 *     server logic (and the fact both can share one process/reactor) work
 *     together before involving real network hardware between two devices.
 *
 * A fifth section (VLM inference) is the real target: sending a TLV-framed
 * image+prompt to a real `tquic-vlm-server-interface` instance instead of
 * this app's own loopback peer -- see `TquicDemoController.sendVlmInference`.
 *
 * All checks run off the main thread (Dispatchers.IO) -- every TquicNative
 * call can legitimately block briefly on a reactor round trip.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var loadStatus: TextView
    private lateinit var certStatus: TextView
    private lateinit var messageInput: EditText
    private lateinit var clientStatus: TextView
    private lateinit var serverStatus: TextView
    private lateinit var receivedLog: TextView

    private lateinit var vlmHostInput: EditText
    private lateinit var vlmPortInput: EditText
    private lateinit var vlmPromptInput: EditText
    private lateinit var vlmUseBundledCheckbox: CheckBox
    private lateinit var vlmSendBtn: Button
    private lateinit var vlmStatus: TextView

    private val controller by lazy { TquicDemoController(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }
        fun sectionTitle(text: String) = TextView(this).apply {
            this.text = text
            textSize = 18f
            setPadding(0, 32, 0, 8)
        }
        fun monoStatus() = TextView(this).apply {
            text = "Not checked yet."
            textSize = 14f
            setPadding(0, 8, 0, 24)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        val title = TextView(this).apply {
            text = "TQUIC Demo -- scaffold checks"
            textSize = 22f
            gravity = Gravity.CENTER
        }

        loadStatus = monoStatus()
        val loadBtn = Button(this).apply {
            text = "1. Check libtquic_jni.so loads"
            setOnClickListener { runLoadCheck() }
        }

        certStatus = monoStatus()
        val certBtn = Button(this).apply {
            text = "2. Check cert + serverStart()"
            setOnClickListener { runCertCheck() }
        }

        val serverStartBtn = Button(this).apply {
            text = "4a. Start server (loopback :$LOOPBACK_PORT)"
            setOnClickListener { startServerCheck() }
        }
        val serverStopBtn = Button(this).apply {
            text = "4b. Stop server"
            setOnClickListener { stopServerCheck() }
        }
        serverStatus = monoStatus()
        receivedLog = monoStatus().apply { text = "(no messages received yet)" }

        messageInput = EditText(this).apply {
            hint = "message to send"
            setText("hello from the demo app")
        }
        clientStatus = monoStatus()
        val clientStartBtn = Button(this).apply {
            text = "3a. Start client loop -> 127.0.0.1:$LOOPBACK_PORT"
            setOnClickListener { startClientCheck() }
        }
        val clientStopBtn = Button(this).apply {
            text = "3b. Stop client loop"
            setOnClickListener { stopClientCheck() }
        }

        // -- VLM inference section --
        vlmHostInput = EditText(this).apply {
            hint = "tquic-vlm-server-interface host"
            setText("54.190.37.190")
        }
        vlmPortInput = EditText(this).apply {
            hint = "port"
            setText("10000")
        }
        vlmPromptInput = EditText(this).apply {
            hint = "prompt for the VLM"
        }
        vlmUseBundledCheckbox = CheckBox(this).apply {
            text = "Use bundled sample image"
            isChecked = true
        }
        vlmStatus = monoStatus()
        vlmSendBtn = Button(this).apply {
            text = "Send inference request"
            setOnClickListener { sendVlmInferenceCheck() }
        }
        vlmUseBundledCheckbox.setOnCheckedChangeListener { _, _ -> updateVlmSendEnabled() }
        updateVlmSendEnabled()

        root.addView(title)
        root.addView(sectionTitle("Native load"))
        root.addView(loadStatus)
        root.addView(loadBtn)
        root.addView(sectionTitle("TLS cert provisioning"))
        root.addView(certStatus)
        root.addView(certBtn)
        root.addView(sectionTitle("Server (start this first for a real loopback round trip)"))
        root.addView(serverStatus)
        root.addView(serverStartBtn)
        root.addView(serverStopBtn)
        root.addView(sectionTitle("Received messages"))
        root.addView(receivedLog)
        root.addView(sectionTitle("Client send loop"))
        root.addView(messageInput)
        root.addView(clientStatus)
        root.addView(clientStartBtn)
        root.addView(clientStopBtn)
        root.addView(sectionTitle("VLM inference (tquic-vlm-server-interface)"))
        root.addView(vlmHostInput)
        root.addView(vlmPortInput)
        root.addView(vlmUseBundledCheckbox)
        root.addView(vlmPromptInput)
        root.addView(vlmStatus)
        root.addView(vlmSendBtn)
        // Four sections' worth of content overflows the screen height on most
        // devices -- a plain LinearLayout silently clips anything past the
        // bottom with no way to reach it by touch (a real bug that only
        // surfaced once phase 4 added enough content to overflow; phases 1-3
        // happened to fit). Wrap in a ScrollView rather than assume content
        // height will always fit.
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        runLoadCheck()

        lifecycleScope.launch {
            controller.sendStatus.collect { status ->
                clientStatus.text = when (status) {
                    is TquicDemoController.SendStatus.Idle -> "Idle."
                    is TquicDemoController.SendStatus.Sending -> "Sending..."
                    is TquicDemoController.SendStatus.Ok -> "Last send: ${status.at} -- OK"
                    is TquicDemoController.SendStatus.Failed -> "Last send: FAILED -- ${status.reason}"
                }
            }
        }
        lifecycleScope.launch {
            controller.serverRunning.collect { running ->
                if (serverStatus.text == "Not checked yet.") {
                    serverStatus.text = if (running) "Running on 0.0.0.0:$LOOPBACK_PORT" else "Stopped."
                }
            }
        }
        lifecycleScope.launch {
            controller.receivedLog.collect { messages ->
                receivedLog.text = if (messages.isEmpty()) {
                    "(no messages received yet)"
                } else {
                    messages.joinToString("\n\n") { m -> "[${m.at}] ${m.method} ${m.path}\n${m.text}" }
                }
            }
        }
        lifecycleScope.launch {
            controller.vlmStatus.collect { status ->
                vlmStatus.text = when (status) {
                    is TquicDemoController.VlmStatus.Idle -> "Idle."
                    is TquicDemoController.VlmStatus.Sending -> "Sending..."
                    is TquicDemoController.VlmStatus.Ok -> status.answer
                    is TquicDemoController.VlmStatus.Failed -> "FAILED -- ${status.reason}"
                }
            }
        }
    }

    private fun runLoadCheck() {
        loadStatus.text = "Checking..."
        val result = runCatching {
            check(TquicNative.tryLoad()) {
                "tryLoad() returned false: ${TquicNative.lastLoadError}"
            }
            val abiVersion = TquicNative.abiVersion()
            val abiInfo = TquicNative.abiInfo()
            val version = TquicNative.version()
            "loaded=true\n" +
                "abiVersion=$abiVersion (expected ${TquicNative.EXPECTED_ABI_VERSION})\n" +
                "abiInfo=$abiInfo\n" +
                "version()=$version"
        }
        val text = result.getOrElse { e ->
            Log.e(TAG, "native load check failed", e)
            "FAILED: ${e::class.simpleName}: ${e.message}\n\nlastLoadError=${TquicNative.lastLoadError}"
        }
        Log.i(TAG, "native load check: $text")
        loadStatus.text = text
    }

    private fun runCertCheck() {
        certStatus.text = "Checking..."
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    TquicNative.ensureLoaded()
                    val provisioned = CertProvisioner.provision(applicationContext)
                    check(java.io.File(provisioned.certPath).isFile) { "cert missing after provision" }
                    check(java.io.File(provisioned.keyPath).isFile) { "key missing after provision" }

                    // Scratch port distinct from LOOPBACK_PORT -- this check always
                    // stops itself immediately, so it never conflicts with 4a's server.
                    val handle = TquicNative.serverStart(
                        bindAddr = "0.0.0.0:19443",
                        certPath = provisioned.certPath,
                        keyPath = provisioned.keyPath,
                        alpn = "h3",
                        caCertsPath = null,
                        requireClientCert = false,
                        idleTimeoutMs = 30_000,
                        congestionControl = "bbr",
                    )
                    check(handle != 0L) { "serverStart returned 0" }
                    TquicNative.serverStop(handle, false)

                    "cert=${provisioned.certPath}\n" +
                        "key=${provisioned.keyPath}\n" +
                        "serverStart()=OK (handle=$handle, stopped cleanly)"
                }.getOrElse { e ->
                    Log.e(TAG, "cert check failed", e)
                    "FAILED: ${e::class.simpleName}: ${e.message}"
                }
            }
            Log.i(TAG, "cert check: $text")
            certStatus.text = text
        }
    }

    private fun startServerCheck() {
        serverStatus.text = "Starting..."
        lifecycleScope.launch {
            runCatching {
                TquicNative.ensureLoaded()
                controller.startServer(LOOPBACK_PORT)
            }.onSuccess {
                serverStatus.text = "Running on 0.0.0.0:$LOOPBACK_PORT"
            }.onFailure { e ->
                Log.e(TAG, "startServer failed", e)
                serverStatus.text = "FAILED to start: ${e::class.simpleName}: ${e.message}"
            }
        }
    }

    private fun stopServerCheck() {
        lifecycleScope.launch {
            controller.stopServer()
            serverStatus.text = "Stopped."
        }
    }

    private fun startClientCheck() {
        lifecycleScope.launch {
            runCatching {
                TquicNative.ensureLoaded()
                controller.startClient("127.0.0.1", LOOPBACK_PORT) { messageInput.text.toString() }
            }.onFailure { e ->
                Log.e(TAG, "startClient failed", e)
                clientStatus.text = "FAILED to start: ${e::class.simpleName}: ${e.message}"
            }
        }
    }

    private fun stopClientCheck() {
        lifecycleScope.launch { controller.stopClient() }
    }

    /**
     * Unchecking "use bundled sample image" disables the send button rather
     * than silently sending nothing or a placeholder -- there is no other
     * image source wired in yet (camera/gallery capture is separately
     * scoped, see the repo-root Integration.txt), so this keeps the option
     * to *not* send the bundled image honest about what actually happens.
     */
    private fun updateVlmSendEnabled() {
        val useBundled = vlmUseBundledCheckbox.isChecked
        vlmSendBtn.isEnabled = useBundled
        if (!useBundled) {
            vlmStatus.text = "no image source selected"
        }
    }

    private fun sendVlmInferenceCheck() {
        vlmStatus.text = "Sending..."
        val host = vlmHostInput.text.toString()
        val port = vlmPortInput.text.toString().toIntOrNull()
        if (port == null) {
            vlmStatus.text = "FAILED -- invalid port"
            return
        }
        val prompt = vlmPromptInput.text.toString()
        lifecycleScope.launch {
            runCatching {
                TquicNative.ensureLoaded()
                val jpeg = withContext(Dispatchers.IO) {
                    assets.open("vlm-demo/sample_image.jpg").use { it.readBytes() }
                }
                controller.sendVlmInference(host, port, jpeg, prompt)
            }.onFailure { e ->
                Log.e(TAG, "sendVlmInference failed", e)
                vlmStatus.text = "FAILED: ${e::class.simpleName}: ${e.message}"
            }
        }
    }
}
