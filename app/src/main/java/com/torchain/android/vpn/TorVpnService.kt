package com.torchain.android.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.torchain.android.R
import com.torchain.android.TorchainApp
import com.torchain.android.data.TorState
import com.torchain.android.ui.MainActivity
import com.torchain.android.util.Logger
import com.torchain.android.util.TorStatusBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class TorVpnService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var localProxy: LocalSocksProxy? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Logger.i("vpn", "TorVpnService starting...")
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        scope.launch(Dispatchers.IO) {
            try {
                // 1. Check if TProxyService library is loaded and available
                if (!hev.sockstun.TProxyService.isAvailable()) {
                    val msg = "Native TProxy library not available. Cannot start VPN."
                    Logger.e("vpn", msg)
                    updateTorStateError(msg)
                    stopSelf()
                    return@launch
                }

                // 2. Wait and verify Tor SOCKS port is accepting connections
                Logger.i("vpn", "Waiting for Tor SOCKS proxy on port $TOR_SOCKS_PORT...")
                var socksReady = false
                for (i in 1..15) {
                    if (isPortOpen("127.0.0.1", TOR_SOCKS_PORT)) {
                        socksReady = true
                        break
                    }
                    delay(1000)
                }

                if (!socksReady) {
                    val msg = "Tor SOCKS proxy not responding on port $TOR_SOCKS_PORT. Aborting VPN start."
                    Logger.e("vpn", msg)
                    updateTorStateError(msg)
                    stopSelf()
                    return@launch
                }
                Logger.i("vpn", "Tor SOCKS proxy is ready. Proceeding to establish VPN.")

                // 3. Start local SOCKS proxy to intercept DNS and forward TCP
                Logger.i("vpn", "Starting LocalSocksProxy on port $LOCAL_PROXY_PORT...")
                localProxy = LocalSocksProxy(LOCAL_PROXY_PORT, TOR_SOCKS_PORT, TOR_DNS_PORT)
                localProxy?.start()

                // 4. Configure VpnService.Builder
                Logger.i("vpn", "Building VpnService configuration...")
                val builder = Builder()
                    .setSession(getString(R.string.app_name))
                    .addAddress(VPN_ADDRESS, 30)
                    .addRoute("0.0.0.0", 0)
                    // IPv6 support: route all IPv6 traffic into the tunnel to prevent leaks
                    .addAddress(VPN_ADDRESS_V6, 128)
                    .addRoute("::", 0)
                    .addDnsServer(VPN_DNS)
                    .addDnsServer(VPN_DNS_V6)
                    .setMtu(VPN_MTU)
                    .setBlocking(false)
                
                Logger.i("vpn", "VpnService.Builder configured: IPv4=$VPN_ADDRESS/30 IPv6=$VPN_ADDRESS_V6/128 DNS=$VPN_DNS,$VPN_DNS_V6 MTU=$VPN_MTU")

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
                    this@TorVpnService, 0,
                    Intent(this@TorVpnService, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
                builder.setConfigureIntent(pi)

                Logger.i("vpn", "Calling VpnService.Builder.establish()...")
                val pfd = builder.establish()
                if (pfd == null) {
                    val msg = "VpnService.establish() returned null — user may have revoked VPN permission"
                    Logger.e("vpn", msg)
                    updateTorStateError(msg)
                    stopSelf()
                    return@launch
                }
                tunFd = pfd
                running = true
                Logger.i("vpn", "TUN established fd=${pfd.fd}, MTU $VPN_MTU")

                // 5. Write tproxy.conf configuration matching exact bundled library schema
                val file = File(cacheDir, "tproxy.conf")
                val conf = """
                    tunnel:
                      mtu: $VPN_MTU
                    socks5:
                      port: $LOCAL_PROXY_PORT
                      address: '$SOCKS_ADDRESS'
                      udp: '$SOCKS_UDP_MODE'
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
                        Logger.e("vpn-tproxy", "TProxyStartService crashed: ${t.message}", t)
                    }
                }, "tproxy-worker").apply {
                    isDaemon = true
                    start()
                }
                Logger.i("vpn", "TProxy worker thread dispatched")

            } catch (e: java.lang.SecurityException) {
                Logger.e("vpn", "VPN permission denied: ${e.message}", e)
                updateTorStateError("VPN permission denied: ${e.message}")
                stopSelf()
            } catch (e: Exception) {
                Logger.e("vpn", "startVpn failed: ${e.message}", e)
                updateTorStateError("VPN start failed: ${e.message}")
                stopSelf()
            }
        }
    }

    private fun isPortOpen(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 500)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun updateTorStateError(message: String) {
        val intent = Intent(com.torchain.android.service.TorService.ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(com.torchain.android.service.TorService.EXTRA_STATE, TorState.Error::class.java.simpleName)
            putExtra(com.torchain.android.service.TorService.EXTRA_MESSAGE, message)
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }



    override fun onDestroy() {
        super.onDestroy()
        teardown()
    }

    override fun onRevoke() {
        Logger.w("vpn", "VPN revoked")
        teardown()
        stopSelf()
    }

    private fun teardown() {
        if (!running) return
        running = false
        Logger.i("vpn", "Tearing down VPN service...")
        
        try {
            if (hev.sockstun.TProxyService.isAvailable()) {
                hev.sockstun.TProxyService.TProxyStopService()
                Logger.i("vpn", "TProxyService stopped")
            }
        } catch (t: Throwable) {
            Logger.e("vpn", "TProxyStopService failed during teardown", t)
        }

        try {
            localProxy?.stop()
            localProxy = null
        } catch (e: Exception) {
            Logger.e("vpn", "Failed to stop LocalSocksProxy", e)
        }

        try {
            tunFd?.close()
        } catch (e: Exception) {
            Logger.w("vpn", "Failed to close TUN file descriptor", e)
        }
        tunFd = null
        scope.cancel()
        Logger.i("vpn", "VPN service teardown complete")
    }

    companion object {
        private const val NOTIF_ID = 2
        
        // VPN Network Configuration Constants
        private const val VPN_ADDRESS = "10.211.211.2"
        private const val VPN_ADDRESS_V6 = "fd00:1:2:3::2"
        private const val VPN_DNS = "10.211.211.1"
        private const val VPN_DNS_V6 = "fd00:1:2:3::1"
        private const val VPN_MTU = 1500

        // TProxy Configuration Constants
        private const val SOCKS_ADDRESS = "127.0.0.1"
        private const val SOCKS_UDP_MODE = "udp"
        private const val TPROXY_STACK_SIZE = 20480

        // Local Ports
        private const val TOR_SOCKS_PORT = 9050
        private const val TOR_DNS_PORT = 5400
        private const val LOCAL_PROXY_PORT = 9053
    }
}
