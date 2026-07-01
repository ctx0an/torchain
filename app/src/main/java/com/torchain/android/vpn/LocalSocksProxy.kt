package com.torchain.android.vpn

import com.torchain.android.util.Logger
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Minimal SOCKS5 proxy that fronts Tor's own SOCKS port (9050) and Tor's DNS
 * port (5400). It exists so the native hev-socks5-tunnel (which reads the TUN
 * fd) has a single local SOCKS5 endpoint to forward both TCP and UDP DNS to.
 *
 * HARDENING (fix for the 15%-bootstrap crash):
 *  - All inbound TCP connections are serviced by a *bounded* ThreadPoolExecutor
 *    with a capped work queue. Previously every connection spawned 3 unbounded
 *    `thread {}` calls (acceptor + 2 pipes), so when the VPN came up mid-bootstrap
 *    and every app on the phone retried simultaneously, the process created
 *    thousands of threads and died with OOM. Now excess connections are rejected
 *    cleanly (socket closed) instead of crashing the process.
 *  - The per-connection pipe threads are still needed (blocking I/O), but their
 *    count is bounded by the same semaphore-style pool sizing.
 */
class LocalSocksProxy(
    private val localPort: Int = 9053,
    private val torSocksPort: Int = 9050,
    private val torDnsPort: Int = 5400
) {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var udpSocket: DatagramSocket? = null
    @Volatile private var running = false

    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

    // Bounded worker pool for SOCKS client handling. Core/max = MAX_CONCURRENT;
    // queue is small so back-pressure rejects floods fast instead of piling up.
    private val workerPool: ThreadPoolExecutor = ThreadPoolExecutor(
        /* corePoolSize    = */ MAX_CONCURRENT,
        /* maximumPoolSize = */ MAX_CONCURRENT,
        /* keepAliveTime   = */ 30L,
        /* unit            = */ TimeUnit.SECONDS,
        /* workQueue       = */ LinkedBlockingQueue(MAX_QUEUE),
        /* threadFactory   = */ { r ->
            Thread(r, "socks-worker-${workerCounter.incrementAndGet()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        },
        /* handler         = */ ThreadPoolExecutor.AbortPolicy()
    )

    private val pipePool: ThreadPoolExecutor = ThreadPoolExecutor(
        /* corePoolSize    = */ MAX_CONCURRENT * 2,
        /* maximumPoolSize = */ MAX_CONCURRENT * 2,
        /* keepAliveTime   = */ 30L,
        /* unit            = */ TimeUnit.SECONDS,
        /* workQueue       = */ SynchronousQueue<Runnable>(),
        /* threadFactory   = */ { r ->
            Thread(r, "socks-pipe-${pipeCounter.incrementAndGet()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        },
        /* handler         = */ ThreadPoolExecutor.AbortPolicy()
    )

    private val udpPool: ThreadPoolExecutor = ThreadPoolExecutor(
        /* corePoolSize    = */ 16,
        /* maximumPoolSize = */ 32,
        /* keepAliveTime   = */ 30L,
        /* unit            = */ TimeUnit.SECONDS,
        /* workQueue       = */ LinkedBlockingQueue(100),
        /* threadFactory   = */ { r ->
            Thread(r, "socks-udp-${udpCounter.incrementAndGet()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        },
        /* handler         = */ ThreadPoolExecutor.AbortPolicy()
    )

    private val workerCounter = AtomicInteger(0)
    private val pipeCounter = AtomicInteger(0)
    private val udpCounter = AtomicInteger(0)
    private val activeConnections = AtomicInteger(0)
    private val rejectedConnections = AtomicLong(0)

    fun start() {
        running = true
        try {
            serverSocket = ServerSocket(localPort, 50, InetAddress.getByName("127.0.0.1"))
            udpSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            Logger.i("LocalSocksProxy", "Local SOCKS proxy started on port $localPort, UDP on ${udpSocket?.localPort} (maxConcurrent=$MAX_CONCURRENT)")

            thread(name = "socks-tcp-accept") {
                acceptTcpConnections()
            }

            thread(name = "socks-udp-relay") {
                runUdpRelay()
            }
        } catch (e: Exception) {
            Logger.e("LocalSocksProxy", "Failed to start local SOCKS proxy", e)
        }
    }

    private fun closeSocket(socket: Socket?) {
        if (socket != null) {
            activeSockets.remove(socket)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        udpSocket = null

        val socketsToClose = activeSockets.toList()
        activeSockets.clear()
        for (s in socketsToClose) {
            try { s.close() } catch (_: Exception) {}
        }

        try { workerPool.shutdownNow() } catch (_: Exception) {}
        try { pipePool.shutdownNow() } catch (_: Exception) {}
        try { udpPool.shutdownNow() } catch (_: Exception) {}
        Logger.i("LocalSocksProxy", "Local SOCKS proxy stopped (served=${activeConnections.get()}, rejected=${rejectedConnections.get()})")
    }

    private fun acceptTcpConnections() {
        while (running) {
            try {
                val client = serverSocket?.accept() ?: break
                client.tcpNoDelay = true
                activeSockets.add(client)

                val currentActive = activeConnections.incrementAndGet()
                if (currentActive > MAX_CONCURRENT) {
                    rejectedConnections.incrementAndGet()
                    activeConnections.decrementAndGet()
                    closeSocket(client)
                    continue
                }

                try {
                    workerPool.execute {
                        var handedOver = false
                        try {
                            handedOver = handleTcpClient(client)
                        } catch (e: Throwable) {
                            Logger.w("LocalSocksProxy", "Worker threw: ${e.message}")
                        } finally {
                            if (!handedOver) {
                                closeSocket(client)
                                activeConnections.decrementAndGet()
                            }
                        }
                    }
                } catch (rejected: java.util.concurrent.RejectedExecutionException) {
                    rejectedConnections.incrementAndGet()
                    activeConnections.decrementAndGet()
                    closeSocket(client)
                }
            } catch (e: Exception) {
                if (running) {
                    Logger.w("LocalSocksProxy", "Error accepting TCP connection: ${e.message}")
                }
            }
        }
    }

    private fun handleTcpClient(client: Socket): Boolean {
        try {
            client.soTimeout = 30000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // 1. Handshake
            val version = input.read()
            if (version != 5) {
                return false
            }
            val nMethods = input.read()
            if (nMethods <= 0) {
                return false
            }
            val methods = ByteArray(nMethods)
            input.readFully(methods)

            // Respond with no auth (0x00)
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // 2. Request
            val reqVersion = input.read()
            val cmd = input.read()
            input.read() // RSV
            val atyp = input.read()

            if (reqVersion != 5) {
                sendErrorResponse(output, 0x01) // general failure
                return false
            }

            // Parse destination address — keep the RAW address bytes (not just the
            // String form) so we can replay them verbatim to Tor's own SOCKS5
            // server. Tor:9050 is a SOCKS5 server, so it needs its OWN SOCKS5
            // handshake (greeting + CONNECT) before any app data. The previous
            // code piped raw client bytes straight to :9050, which Tor could not
            // parse — so it closed every connection and no website could complete
            // its TCP/TLS handshake even though the VPN was "up".
            val addrBytes: ByteArray
            val destAddrLog: String
            when (atyp) {
                0x01 -> { // IPv4
                    addrBytes = ByteArray(4)
                    input.readFully(addrBytes)
                    destAddrLog = InetAddress.getByAddress(addrBytes).hostAddress ?: ""
                }
                0x03 -> { // Domain name
                    val len = input.read()
                    if (len <= 0) {
                        sendErrorResponse(output, 0x01)
                        return false
                    }
                    val domainBytes = ByteArray(len)
                    input.readFully(domainBytes)
                    // Store as [len][domain] so we can write ATYP=0x03 + this blob
                    // directly into Tor's CONNECT request.
                    addrBytes = ByteArray(1 + len)
                    addrBytes[0] = len.toByte()
                    System.arraycopy(domainBytes, 0, addrBytes, 1, len)
                    destAddrLog = String(domainBytes)
                }
                0x04 -> { // IPv6
                    addrBytes = ByteArray(16)
                    input.readFully(addrBytes)
                    destAddrLog = InetAddress.getByAddress(addrBytes).hostAddress ?: ""
                }
                else -> {
                    sendErrorResponse(output, 0x08) // Address type not supported
                    return false
                }
            }

            val portBytes = ByteArray(2)
            input.readFully(portBytes)
            val destPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            if (cmd == 0x01) { // CONNECT
                var torSocket: Socket? = null
                try {
                    torSocket = Socket()
                    torSocket.tcpNoDelay = true
                    activeSockets.add(torSocket)
                    torSocket.connect(InetSocketAddress("127.0.0.1", torSocksPort), 5000)
                } catch (e: Exception) {
                    Logger.w("LocalSocksProxy", "Tor SOCKS connect failed: ${e.message}")
                    sendErrorResponse(output, 0x03) // Network unreachable
                    closeSocket(torSocket)
                    return false
                }

                val tOut = torSocket.getOutputStream()
                val tIn = torSocket.getInputStream()
                try {
                    // --- SOCKS5 client handshake to Tor ---
                    // Greeting: ver=5, 1 method offered, no-auth (0x00).
                    tOut.write(byteArrayOf(0x05, 0x01, 0x00)); tOut.flush()
                    val g1 = tIn.read()
                    val g2 = tIn.read()
                    if (g1 != 5 || g2 == 0xFF || g2 == -1) {
                        sendErrorResponse(output, 0x01) // general SOCKS server failure
                        closeSocket(torSocket)
                        return false
                    }
                    // CONNECT request: replay ATYP + addr + port to Tor verbatim.
                    val req = java.io.ByteArrayOutputStream()
                    req.write(0x05); req.write(0x01); req.write(0x00); req.write(atyp)
                    req.write(addrBytes)
                    req.write(portBytes)
                    tOut.write(req.toByteArray()); tOut.flush()
                    // Read Tor's reply: VER REP RSV ATYP BND.ADDR BND.PORT
                    tIn.read() // VER
                    val repCode = tIn.read() // REP
                    tIn.read() // RSV
                    val batyp = tIn.read() // BND.ATYP
                    when (batyp) {
                        0x01 -> { val b = ByteArray(4); tIn.readFully(b) }
                        0x03 -> { val l = tIn.read(); if (l > 0) { val b = ByteArray(l); tIn.readFully(b) } }
                        0x04 -> { val b = ByteArray(16); tIn.readFully(b) }
                        else -> { /* skip best-effort */ }
                    }
                    tIn.readFully(ByteArray(2)) // BND.PORT
                    if (repCode != 0x00) {
                        Logger.w("LocalSocksProxy", "Tor rejected CONNECT $destAddrLog:$destPort rep=$repCode")
                        sendErrorResponse(output, repCode.toByte())
                        closeSocket(torSocket)
                        return false
                    }
                } catch (e: Exception) {
                    Logger.w("LocalSocksProxy", "Tor SOCKS5 handshake failed: ${e.message}")
                    sendErrorResponse(output, 0x01)
                    closeSocket(torSocket)
                    return false
                }

                // Tor accepted the CONNECT — now tell the client (TProxy) success
                // and pipe app data both ways. BND.ADDR/PORT use 0.0.0.0:0
                // (clients ignore them).
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                client.soTimeout = 0 // clear the 30s handshake timeout for long-lived streams

                // Pipe bi-directionally on the bounded pipe pool.
                pipeSockets(client, torSocket)
                return true

            } else if (cmd == 0x03) { // UDP ASSOCIATE
                val localUdpPort = udpSocket?.localPort ?: 0
                val response = ByteBuffer.allocate(10)
                response.put(0x05.toByte()) // VER
                response.put(0x00.toByte()) // REP (Success)
                response.put(0x00.toByte()) // RSV
                response.put(0x01.toByte()) // ATYP (IPv4)
                response.put(byteArrayOf(127, 0, 0, 1)) // BND.ADDR
                response.putShort(localUdpPort.toShort()) // BND.PORT

                output.write(response.array())
                output.flush()

                // Keep the connection open until client closes it
                try {
                    val buf = ByteArray(1024)
                    while (running) {
                        val read = input.read(buf)
                        if (read < 0) break
                    }
                } catch (_: Exception) {}
                return false
            } else {
                sendErrorResponse(output, 0x07) // Command not supported
                return false
            }
        } catch (e: Exception) {
            Logger.w("LocalSocksProxy", "Error handling TCP client: ${e.message}")
            return false
        }
    }

    private fun sendErrorResponse(output: OutputStream, errCode: Byte) {
        try {
            output.write(byteArrayOf(0x05, errCode, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
        } catch (_: Exception) {}
    }

    private fun InputStream.readFully(b: ByteArray) {
        var offset = 0
        while (offset < b.size) {
            val read = read(b, offset, b.size - offset)
            if (read < 0) throw java.io.EOFException()
            offset += read
        }
    }

    private fun pipeSockets(s1: Socket, s2: Socket) {
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val pipesFinished = AtomicInteger(2)

        fun closeAll() {
            if (closed.compareAndSet(false, true)) {
                closeSocket(s1)
                closeSocket(s2)
            }
        }

        fun onPipeDone() {
            if (pipesFinished.decrementAndGet() == 0) {
                activeConnections.decrementAndGet()
            }
        }

        try {
            pipePool.execute {
                try {
                    val in1 = s1.getInputStream()
                    val out2 = s2.getOutputStream()
                    val buf = ByteArray(8192)
                    var len: Int
                    while (in1.read(buf).also { len = it } >= 0) {
                        if (len > 0) { out2.write(buf, 0, len); out2.flush() }
                    }
                } catch (_: Exception) {}
                finally {
                    try { s2.shutdownOutput() } catch (_: Exception) {}
                    closeAll()
                    onPipeDone()
                }
            }
        } catch (rejected: Exception) {
            closeAll()
            onPipeDone()
            onPipeDone()
            return
        }

        try {
            pipePool.execute {
                try {
                    val in2 = s2.getInputStream()
                    val out1 = s1.getOutputStream()
                    val buf = ByteArray(8192)
                    var len: Int
                    while (in2.read(buf).also { len = it } >= 0) {
                        if (len > 0) { out1.write(buf, 0, len); out1.flush() }
                    }
                } catch (_: Exception) {}
                finally {
                    try { s1.shutdownOutput() } catch (_: Exception) {}
                    closeAll()
                    onPipeDone()
                }
            }
        } catch (rejected: Exception) {
            closeAll()
            onPipeDone()
        }
    }

    private fun runUdpRelay() {
        val socket = udpSocket ?: return
        val buffer = ByteArray(65535)
        while (running) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)

                val packetData = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val clientAddress = packet.address
                val clientPort = packet.port

                try {
                    udpPool.execute {
                        handleUdpPacket(packetData, clientAddress, clientPort)
                    }
                } catch (rejected: java.util.concurrent.RejectedExecutionException) {
                    Logger.w("LocalSocksProxy", "UDP packet dropped due to pool saturation")
                }
            } catch (e: Exception) {
                if (running) {
                    Logger.w("LocalSocksProxy", "Error receiving UDP packet: ${e.message}")
                }
            }
        }
    }

    private fun handleUdpPacket(data: ByteArray, clientAddress: InetAddress, clientPort: Int) {
        try {
            if (data.size < 4) return
            // Parse SOCKS5 UDP header
            val frag = data[2]
            val atyp = data[3]
            if (frag != 0.toByte()) {
                return // We don't support fragmentation
            }

            var headerLen = 4
            when (atyp.toInt()) {
                0x01 -> { // IPv4
                    if (data.size < headerLen + 4) return
                    headerLen += 4
                }
                0x03 -> { // Domain
                    if (data.size < headerLen + 1) return
                    val len = data[headerLen].toInt() and 0xFF
                    if (data.size < headerLen + 1 + len) return
                    headerLen += 1 + len
                }
                0x04 -> { // IPv6
                    if (data.size < headerLen + 16) return
                    headerLen += 16
                }
                else -> return
            }

            if (data.size < headerLen + 2) return
            val destPort = ((data[headerLen].toInt() and 0xFF) shl 8) or (data[headerLen + 1].toInt() and 0xFF)
            headerLen += 2

            val payload = ByteArray(data.size - headerLen)
            System.arraycopy(data, headerLen, payload, 0, payload.size)

            // If it is a DNS query (port 53), resolve via Tor DNSPort (UDP)
            if (destPort == 53) {
                val dnsResponse = queryTorDns(payload) ?: return

                val respHeader = ByteArray(headerLen)
                System.arraycopy(data, 0, respHeader, 0, headerLen)

                val respPacketData = ByteArray(respHeader.size + dnsResponse.size)
                System.arraycopy(respHeader, 0, respPacketData, 0, respHeader.size)
                System.arraycopy(dnsResponse, 0, respPacketData, respHeader.size, dnsResponse.size)

                val respPacket = DatagramPacket(respPacketData, respPacketData.size, clientAddress, clientPort)
                udpSocket?.send(respPacket)
            }
        } catch (e: Exception) {
            Logger.w("LocalSocksProxy", "Error handling UDP packet: ${e.message}")
        }
    }

    private fun queryTorDns(query: ByteArray): ByteArray? {
        return try {
            DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { socket ->
                socket.soTimeout = 2500
                val torAddr = InetAddress.getByName("127.0.0.1")
                val packet = DatagramPacket(query, query.size, torAddr, torDnsPort)
                socket.send(packet)

                val responseBuf = ByteArray(4096)
                val responsePacket = DatagramPacket(responseBuf, responseBuf.size)
                socket.receive(responsePacket)

                responsePacket.data.copyOfRange(responsePacket.offset, responsePacket.offset + responsePacket.length)
            }
        } catch (e: Exception) {
            Logger.w("LocalSocksProxy", "Tor DNS query failed", e)
            null
        }
    }

    companion object {
        // Hard caps that prevent thread/fd explosion when many apps retry at once.
        // 64 concurrent TCP relays × 2 pipe threads = 128 threads max, well within
        // Android per-app thread limits, while still handling normal phone traffic.
        private const val MAX_CONCURRENT = 64
        private const val MAX_QUEUE = 128
    }
}
