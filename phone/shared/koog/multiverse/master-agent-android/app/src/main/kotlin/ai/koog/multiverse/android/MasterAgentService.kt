package ai.koog.multiverse.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import ai.koog.multiverse.api.KoogHttpServer
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground Service that hosts the Koog Master Agent's embedded Ktor HTTP server on-device
 * (127.0.0.1:8080). The VideoShowCase phone app POSTs to it over loopback (grillme_version2 topology).
 */
class MasterAgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: EmbeddedServer<*, *>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        if (server == null) {
            scope.launch {
                val agent = AndroidConfig.buildAgent(applicationContext)
                server = KoogHttpServer(agent, host = "0.0.0.0", port = AndroidConfig.PORT).start(wait = false)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop(500, 1000)
        server = null
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Master Agent", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Koog Master Agent")
            .setContentText("Running on 127.0.0.1:${AndroidConfig.PORT}")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "master_agent"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, MasterAgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MasterAgentService::class.java))
        }
    }
}
