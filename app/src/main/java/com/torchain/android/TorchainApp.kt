package com.torchain.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.torchain.android.util.Logger

class TorchainApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Logger.init(this)
        Logger.i("TorchainApp", "Torchain starting (v${BuildConfig.VERSION_NAME})")
        createNotificationChannels()
        preloadNativeLibraries()
    }

    private fun preloadNativeLibraries() {
        try {
            System.loadLibrary("hev-socks5-tunnel")
            Logger.i("TorchainApp", "hev-socks5-tunnel native library loaded")
        } catch (t: Throwable) {
            Logger.w("TorchainApp", "hev-socks5-tunnel native library not available: ${t.message}")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        NotificationChannel(CHANNEL_TOR, getString(R.string.notif_channel_tor),
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "Persistent notification while Tor is running"
            setShowBadge(false)
            nm.createNotificationChannel(this)
        }
        NotificationChannel(CHANNEL_WATCHDOG, getString(R.string.notif_channel_watchdog),
            NotificationManager.IMPORTANCE_MIN).apply {
            description = "Watchdog self-healing notifications"
            setShowBadge(false)
            nm.createNotificationChannel(this)
        }
    }

    companion object {
        const val CHANNEL_TOR = "tor_service"
        const val CHANNEL_WATCHDOG = "watchdog"
        @Volatile lateinit var instance: TorchainApp
            private set
    }
}
