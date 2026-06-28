package com.torchain.android.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.torchain.android.R
import com.torchain.android.TorchainApp
import com.torchain.android.data.Config
import com.torchain.android.data.TorState
import com.torchain.android.data.TorStatus
import com.torchain.android.tor.TorController
import com.torchain.android.ui.MainActivity
import com.torchain.android.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TorService : LifecycleService() {

    private lateinit var tor: TorController
    private var statusJob: Job? = null
    @Volatile private var proxyMode: String = "vpn"

    override fun onCreate() {
        super.onCreate()
        tor = TorController(this)
        startForeground(NOTIF_ID, buildNotification("Torchain starting..."))
        statusJob = lifecycleScope.launch {
            tor.status.collectLatest { s ->
                updateNotification(s)
                broadcastState(s)
                if (s.state is TorState.Error) {
                    if (proxyMode == "vpn") {
                        try {
                            stopService(Intent(this@TorService, com.torchain.android.vpn.TorVpnService::class.java))
                            Logger.i("TorService", "Tor error detected, stopped TorVpnService")
                        } catch (e: Exception) {
                            Logger.w("TorService", "Failed to stop VPN on Tor error", e)
                        }
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent); return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startTor()
            ACTION_STOP  -> stopTor()
            ACTION_ROTATE -> lifecycleScope.launch { tor.rotateIdentity() }
            ACTION_PANIC -> lifecycleScope.launch { tor.panic() }
        }
        return START_STICKY
    }

    private fun startTor() {
        lifecycleScope.launch {
            val config = Config.flow(this@TorService).first()
            proxyMode = config.proxyMode
            Logger.i("TorService", "Starting Tor with config: exitCountry=${config.exitCountry} blockIpv6=${config.blockIpv6} bridges=${config.bridgesEnabled} proxyMode=${config.proxyMode}")
            val ok = tor.start(config)
            if (ok) {
                if (config.proxyMode == "vpn") {
                    Logger.i("TorService", "Tor process launched, starting VPN service...")
                    val vpnIntent = Intent(this@TorService, com.torchain.android.vpn.TorVpnService::class.java)
                    try {
                        startService(vpnIntent)
                        Logger.i("TorService", "TorVpnService startService() called")
                    } catch (e: Exception) {
                        Logger.e("TorService", "Failed to start TorVpnService: ${e.message}", e)
                    }
                } else {
                    Logger.i("TorService", "SOCKS5 mode — VPN service skipped, SOCKS5 proxy on port 9050")
                }
            } else {
                Logger.e("TorService", "Tor.start() returned false — service will not start")
            }
        }
    }

    private fun stopTor() {
        lifecycleScope.launch {
            val config = Config.flow(this@TorService).first()
            Logger.i("TorService", "Stopping Tor and VPN...")
            if (config.proxyMode == "vpn") {
                try {
                    stopService(Intent(this@TorService, com.torchain.android.vpn.TorVpnService::class.java))
                    Logger.i("TorService", "VPN service stop requested")
                } catch (e: Exception) { Logger.w("TorService", "stop vpn failed", e) }
            } else {
                Logger.i("TorService", "SOCKS5 mode — no VPN service to stop")
            }
            tor.stop()
            Logger.i("TorService", "Tor stopped")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateNotification(s: TorStatus) {
        val modeTag = if (proxyMode == "socks5") " [SOCKS5]" else ""
        val msg = when (val st = s.state) {
            is TorState.Stopped -> "Stopped$modeTag"
            is TorState.Starting -> "Starting...$modeTag"
            is TorState.Bootstrapping -> "Bootstrap ${st.progress}% - ${st.tag}$modeTag"
            is TorState.Running -> "Running - exit ${s.exitIp.ifEmpty { "..." }}$modeTag"
            is TorState.Stopping -> "Stopping...$modeTag"
            is TorState.Error -> "Error: ${st.message.take(80)}$modeTag"
        }
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(msg))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, TorchainApp.CHANNEL_TOR)
            .setSmallIcon(R.drawable.ic_torchain)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun broadcastState(s: TorStatus) {
        val intent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, s.state::class.java.simpleName)
            putExtra(EXTRA_PID, s.pid)
            putExtra(EXTRA_SOCKS, s.socksPort)
            putExtra(EXTRA_CONTROL, s.controlPort)
            putExtra(EXTRA_EXIT_IP, s.exitIp)
            putExtra(EXTRA_MESSAGE, s.message)
            val st = s.state
            if (st is TorState.Bootstrapping) {
                putExtra(EXTRA_PROGRESS, st.progress)
                putExtra(EXTRA_TAG, st.tag)
            }
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager
            .getInstance(this).sendBroadcast(intent)
    }

    override fun onDestroy() {
        statusJob?.cancel()
        lifecycleScope.launch { tor.stop() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.torchain.android.START"
        const val ACTION_STOP = "com.torchain.android.STOP"
        const val ACTION_ROTATE = "com.torchain.android.ROTATE"
        const val ACTION_PANIC = "com.torchain.android.PANIC"
        const val ACTION_STATUS = "com.torchain.android.STATUS"
        const val EXTRA_STATE = "state"
        const val EXTRA_PID = "pid"
        const val EXTRA_SOCKS = "socks"
        const val EXTRA_CONTROL = "control"
        const val EXTRA_EXIT_IP = "exit_ip"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_TAG = "tag"
        const val NOTIF_ID = 1

        fun start(ctx: Context) {
            val i = Intent(ctx, TorService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, TorService::class.java).setAction(ACTION_STOP))
        }
        fun rotate(ctx: Context) {
            ctx.startService(Intent(ctx, TorService::class.java).setAction(ACTION_ROTATE))
        }
        fun panic(ctx: Context) {
            ctx.startService(Intent(ctx, TorService::class.java).setAction(ACTION_PANIC))
        }
    }
}
