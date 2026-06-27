package com.torchain.android.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.torchain.android.R
import com.torchain.android.ui.MainActivity
import com.torchain.android.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.IOException

class TorVpnService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Logger.i("vpn", "TorVpnService start")
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        try {
            Logger.i("vpn", "Building VpnService configuration...")
            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .addAddress(VPN_ADDRESS, 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(VPN_DNS)
                .setMtu(1500)
                .setBlocking(false)
                .allowFamily(android.system.OsConstants.AF_INET)
            Logger.i("vpn", "VpnService.Builder configured: addr=$VPN_ADDRESS/30 dns=$VPN_DNS mtu=1500 route=0.0.0.0/0")

            try {
                builder.addDisallowedApplication(packageName)
                Logger.i("vpn", "Excluded $packageName from VPN to prevent routing loop")
            } catch (e: Exception) {
                Logger.w("vpn", "Failed to exclude self application", e)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            val pi = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.setConfigureIntent(pi)

            Logger.i("vpn", "Calling VpnService.Builder.establish()...")
            val pfd = builder.establish()
            if (pfd == null) {
                Logger.e("vpn", "VpnService.establish() returned null — user may have revoked VPN permission or service not declared in manifest")
                stopSelf()
                return
            }
            tunFd = pfd
            running = true
            Logger.i("vpn", "TUN established fd=${pfd.fd}, MTU 1500, routes 0.0.0.0/0 via $VPN_ADDRESS")

            // Write tproxy.conf configuration
            val file = java.io.File(cacheDir, "tproxy.conf")
            val conf = """
                misc:
                  task-stack-size: 20480
                tunnel:
                  mtu: 1500
                socks5:
                  port: 9050
                  address: '127.0.0.1'
                  udp: true
                mapdns:
                  address: '$VPN_DNS'
                  port: 53
                  network: '240.0.0.0'
                  netmask: '240.0.0.0'
                  cache-size: 10000
            """.trimIndent()
            file.writeText(conf)
            Logger.i("vpn", "tproxy.conf written to ${file.absolutePath}")

            Logger.i("vpn", "Starting TProxy native library background thread...")
            Thread({
                try {
                    Logger.i("vpn-tproxy", "Calling TProxyStartService...")
                    hev.sockstun.TProxyService.TProxyStartService(file.absolutePath, pfd.fd)
                    Logger.i("vpn-tproxy", "TProxyStartService returned normally")
                } catch (t: Throwable) {
                    Logger.e("vpn-tproxy", "TProxyStartService failed (native error): ${t.message}", 
                        if (t is Exception) t else null)
                }
            }, "tproxy-worker").apply {
                isDaemon = true
                start()
            }
            Logger.i("vpn", "TProxy worker thread dispatched")

        } catch (e: java.lang.SecurityException) {
            Logger.e("vpn", "VPN permission denied: ${e.message}", e)
            stopSelf()
        } catch (e: java.lang.IllegalStateException) {
            Logger.e("vpn", "VPN service not properly declared in manifest: ${e.message}", e)
            stopSelf()
        } catch (e: Exception) {
            Logger.e("vpn", "startVpn failed: ${e.message}", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        try {
            hev.sockstun.TProxyService.TProxyStopService()
            Logger.i("vpn", "TProxyService stopped")
        } catch (t: Throwable) {
            Logger.e("vpn", "TProxyStopService failed", 
                if (t is Exception) t else Exception(t.message))
        }
        try { tunFd?.close() } catch (_: Exception) {}
        tunFd = null
        scope.cancel()
        Logger.i("vpn", "TorVpnService destroyed")
    }

    override fun onRevoke() {
        Logger.w("vpn", "VPN revoked")
        running = false
        try {
            hev.sockstun.TProxyService.TProxyStopService()
        } catch (t: Throwable) {
            Logger.e("vpn", "TProxyStopService failed on revoke", 
                if (t is Exception) t else Exception(t.message))
        }
        try { tunFd?.close() } catch (_: Exception) {}
        stopSelf()
    }

    companion object {
        private const val VPN_ADDRESS = "10.211.211.2"
        private const val VPN_DNS = "10.211.211.1"
    }
}
