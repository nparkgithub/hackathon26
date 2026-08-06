package com.example.devmon

import android.content.Context

/**
 * Owns the mDNS advertiser + HTTP ingest server independent of any Activity/Service instance,
 * so they survive the app leaving the foreground. Started from DevMonForegroundService.onCreate()
 * (and also from MainActivity.onCreate() as a same-process fast path), stopped only via the
 * foreground notification's Stop action.
 */
object DevMonRuntime {
    lateinit var advertiser: AdvertiserService
        private set
    lateinit var httpIngest: HttpIngestServer
        private set
    private var started = false

    @Synchronized
    fun ensureStarted(context: Context) {
        if (started) return
        started = true
        advertiser = AdvertiserService(context.applicationContext)
        httpIngest = HttpIngestServer(advertiser)
        httpIngest.start()
    }

    @Synchronized
    fun shutdown() {
        if (!started) return
        started = false
        httpIngest.shutdown()
        advertiser.shutdown()
    }
}
