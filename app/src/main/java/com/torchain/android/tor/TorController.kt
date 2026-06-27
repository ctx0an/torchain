package com.torchain.android.tor

import android.content.Context
import com.torchain.android.data.CircuitHop
import com.torchain.android.data.CircuitInfo
import com.torchain.android.data.TorState
import com.torchain.android.data.TorStatus
import com.torchain.android.data.TorchainConfig
import com.torchain.android.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

class TorController(private val context: Context) {

    private val _status = MutableStateFlow(TorStatus())
    val status: StateFlow<TorStatus> = _status.asStateFlow()

    private var process: Process? = null
    private var control: ControlPortClient? = null
    private var dataDir: File = context.filesDir.resolve("tor")
    private var cookieFile: File = dataDir.resolve("control_auth_cookie")

    @Volatile private var torRunning = false

    fun locateTorBinary(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val f = File(nativeDir, "libtor.so")
        if (f.exists() && f.canExecute()) return f
        val extracted = File(context.filesDir, "libtor.so")
        if (extracted.exists() && extracted.canExecute()) return extracted
        return null
    }

    fun locateTransportBinary(): File? = null

    fun locateGeoip(): File? {
        val f = File(context.filesDir, "geoip")
        if (f.exists()) return f
        try {
            context.assets.open("geoip").use { input ->
                f.parentFile?.mkdirs()
                f.outputStream().use { input.copyTo(it) }
            }
            return f
        } catch (e: Exception) { return null }
    }

    fun locateGeoip6(): File? {
        val f = File(context.filesDir, "geoip6")
        if (f.exists()) return f
        try {
            context.assets.open("geoip6").use { input ->
                f.parentFile?.mkdirs()
                f.outputStream().use { input.copyTo(it) }
            }
            return f
        } catch (e: Exception) { return null }
    }

    suspend fun start(config: TorchainConfig): Boolean = withContext(Dispatchers.IO) {
        if (_status.value.state is TorState.Running ||
            _status.value.state is TorState.Starting) {
            return@withContext true
        }
        try {
            val binary = locateTorBinary()
            if (binary == null) {
                val msg = "Tor binary not bundled in APK. Run scripts/download_tor.sh " +
                          "and rebuild. (nativeLibraryDir=${context.applicationInfo.nativeLibraryDir})"
                Logger.e("tor", msg)
                _status.value = _status.value.copy(
                    state = TorState.Error(msg), message = msg)
                return@withContext false
            }
            Logger.i("tor", "Using tor binary: ${binary.absolutePath}")

            dataDir.mkdirs()
            val cookie = File(dataDir, "control_auth_cookie")
            if (cookie.exists()) cookie.delete()

            val torrc = TorConfig.write(
                dataDir = dataDir,
                config = config,
                transports = locateTransportBinary(),
                geoipFile = locateGeoip(),
                geoip6File = locateGeoip6()
            )

            _status.value = _status.value.copy(
                state = TorState.Starting,
                message = "Launching tor...")

            val socksPort = 9050
            val controlPort = 9051
            val dnsPort = 5400

            _status.value = _status.value.copy(
                socksPort = socksPort,
                controlPort = controlPort,
                dnsPort = dnsPort
            )

            val cmd = listOf(
                binary.absolutePath,
                "-f", torrc.absolutePath,
                "--RunAsDaemon", "0",
                "--ignore-missing-torrc"
            )
            Logger.i("tor", "exec: ${cmd.joinToString(" ")}")
            val pb = ProcessBuilder(cmd).apply {
                redirectErrorStream(true)
                directory(context.filesDir)
            }
            process = pb.start()

            Thread({
                try {
                    process?.inputStream?.bufferedReader()?.forEachLine { line ->
                        Logger.i("tor-stdout", line)
                    }
                } catch (e: Exception) {
                    Logger.w("tor-stdout", "stdout pump died", e)
                }
            }, "tor-stdout").start()

            if (!waitForControlPort(controlPort, 20000)) {
                val msg = "Tor control port did not come up within 20s"
                throw IOException(msg)
            }

            if (!waitForCookie(cookie, 5000)) {
                Logger.w("tor", "control_auth_cookie not present after 5s; auth may fail")
            }

            control = ControlPortClient("127.0.0.1", controlPort, cookie).also {
                it.setEventListener(::onEvent)
                it.connect()
                it.setEvents("STATUS_CLIENT", "BW", "CIRC", "NOTICE", "WARN", "ERR")
            }

            _status.value = _status.value.copy(
                state = TorState.Bootstrapping(0, "starting"),
                message = "Bootstrapping...")
            true
        } catch (e: Exception) {
            Logger.e("tor", "start failed", e)
            _status.value = _status.value.copy(
                state = TorState.Error(e.message ?: "unknown error"),
                message = e.message ?: "unknown error"
            )
            stopInternal()
            false
        }
    }

    private fun waitForCookie(cookie: File, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cookie.exists() && cookie.length() > 0L) return true
            try { Thread.sleep(100) } catch (_: Exception) {}
        }
        return cookie.exists() && cookie.length() > 0L
    }

    private fun waitForControlPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 300)
                    return true
                }
            } catch (_: Exception) { }
            try { Thread.sleep(150) } catch (_: Exception) {}
        }
        return false
    }

    private fun onEvent(ev: ControlPortClient.Event) {
        when (ev) {
            is ControlPortClient.Event.Bootstrap -> {
                _status.value = _status.value.copy(
                    state = TorState.Bootstrapping(ev.progress, ev.tag),
                    message = "Bootstrap ${ev.progress}% - ${ev.tag}")
                if (ev.progress >= 100) {
                    _status.value = _status.value.copy(
                        state = TorState.Running,
                        message = "Bootstrapped 100%")
                    queryExitIpAsync()
                }
            }
            is ControlPortClient.Event.Status -> {
                Logger.i("tor-status", "${ev.severity} ${ev.action} ${ev.args}")
            }
            is ControlPortClient.Event.Bandwidth -> { }
            is ControlPortClient.Event.Circuit -> { }
            is ControlPortClient.Event.Log -> {
                Logger.i("tor-notice", "${ev.severity} ${ev.msg}")
            }
        }
    }

    private fun queryExitIpAsync() {
        Thread({
            try {
                val ctl = control ?: return@Thread
                val info = runBlocking { ctl.getInfo("address") }
                val ip = info["address"] ?: ""
                _status.value = _status.value.copy(exitIp = ip)
            } catch (e: Exception) {
                Logger.w("tor", "exit ip query failed", e)
            }
        }, "tor-exitip").start()
    }

    suspend fun rotateIdentity(): Boolean = withContext(Dispatchers.IO) {
        try {
            control?.signal("NEWNYM")
            _status.value = _status.value.copy(message = "New identity requested")
            true
        } catch (e: Exception) {
            Logger.e("tor", "rotate failed", e)
            false
        }
    }

    suspend fun refreshCircuits(): List<CircuitInfo> = withContext(Dispatchers.IO) {
        try {
            val info = control?.getInfo("circuit-status") ?: return@withContext emptyList()
            val raw = info["circuit-status"] ?: return@withContext emptyList()
            raw.split('\n').mapNotNull { line ->
                val parts = line.split(' ')
                if (parts.size < 3) return@mapNotNull null
                val id = parts[0]; val status = parts[1]; val path = parts[2]
                val hops = path.split(',').map { fp ->
                    CircuitHop(
                        nickname = fp.substringAfter('~', fp).substringAfter('$', fp),
                        fingerprint = fp.removePrefix("$").substringBefore('~'),
                        ipv4 = "", countryCode = "")
                }
                CircuitInfo(id, status, "general", hops)
            }.also { _status.value = _status.value.copy(circuits = it) }
        } catch (e: Exception) {
            Logger.w("tor", "refresh circuits failed", e)
            emptyList()
        }
    }

    suspend fun stop(): Boolean = withContext(Dispatchers.IO) { stopInternal(); true }

    private fun stopInternal() {
        _status.value = _status.value.copy(state = TorState.Stopping, message = "Stopping...")
        try { control?.let { runBlocking { it.close() } } }
        catch (e: Exception) { Logger.w("tor", "control close failed", e) }
        control = null
        torRunning = false
        _status.value = TorStatus()
    }

    suspend fun panic() {
        stopInternal()
        _status.value = _status.value.copy(
            state = TorState.Stopped, message = "Panic - all traffic dropped")
    }
}
