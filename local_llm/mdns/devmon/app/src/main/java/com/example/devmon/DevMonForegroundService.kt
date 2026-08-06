package com.example.devmon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Thin foreground service: its only job is to hold the process at foreground priority (via the
 * persistent notification below) so DevMonRuntime's sockets survive the app leaving the
 * foreground. It does not own the sockets itself and does not support binding.
 */
class DevMonForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "devmon_running"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.example.devmon.action.STOP"
    }

    override fun onCreate() {
        super.onCreate()
        DevMonRuntime.ensureStarted(this)
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            DevMonRuntime.shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "koog_agent background service", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, DevMonForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("koog_agent running")
            .setContentText("Advertising + HTTP ingest on :${HttpIngestServer.PORT}")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }
}
