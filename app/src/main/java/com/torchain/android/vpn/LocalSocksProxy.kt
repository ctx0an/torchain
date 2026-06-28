package com.torchain.android.vpn

import com.torchain.android.util.Logger
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class LocalSocksProxy(
    private val localPort: Int = 9053,
    private val torSocksPort: Int = 9050,
    private val torDnsPort: Int = 5400
) {
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    @Volatile private var running = false

    fun start() {
        running = true
        try {
            serverSocket = ServerSocket(localPort, 50, InetAddress.getByName("127.0.0.1"))
            udpSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            Logger.i("LocalSocksProxy", "Local SOCKS proxy started on port $localPort, UDP on ${udpSocket?.localPort}")
            
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

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        udpSocket = null
        Logger.i("LocalSocksProxy", "Local SOCKS proxy stopped")
    }

    private fun acceptTcpConnections() {
        while (running) {
            try {
                val client = serverSocket?.accept() ?: break
                thread(name = "socks-tcp-client") {
                    handleTcpClient(client)
                }
            } catch (e: Exception) {
                if (running) {
                    Logger.w("LocalSocksProxy", "Error accepting TCP connection: ${e.message}")
                }
            }
        }
    }

    private fun handleTcpClient(client: Socket) {
        try {
            client.soTimeout = 30000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // 1. Handshake
            val version = input.read()
            if (version != 5) {
                client.close()
                return
            }
            val nMethods = input.read()
            if (nMethods <= 0) {
                client.close()
                return
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
                client.close()
                return
            }

            // Parse address
            val destAddr: String
            when (atyp) {
                0x01 -> { // IPv4
                    val addrBytes = ByteArray(4)
                    input.readFully(addrBytes)
                    destAddr = InetAddress.getByAddress(addrBytes).hostAddress ?: ""
                }
                0x03 -> { // Domain name
                    val len = input.read()
                    val domainBytes = ByteArray(len)
                    input.readFully(domainBytes)
                    destAddr = String(domainBytes)
                }
                0x04 -> { // IPv6
                    val addrBytes = ByteArray(16)
                    input.readFully(addrBytes)
                    destAddr = InetAddress.getByAddress(addrBytes).hostAddress ?: ""
                }
                else -> {
                    sendErrorResponse(output, 0x08) // Address type not supported
                    client.close()
                    return
                }
            }

            val portBytes = ByteArray(2)
            input.readFully(portBytes)
            val destPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            if (cmd == 0x01) { // CONNECT
                // Connect to Tor SOCKS port
                val torSocket = try {
                    Socket("127.0.0.1", torSocksPort)
                } catch (e: Exception) {
                    Logger.e("LocalSocksProxy", "Failed to connect to Tor SOCKS port $torSocksPort", e)
                    sendErrorResponse(output, 0x03) // Network unreachable
                    client.close()
                    return
                }

                // Send success response: SOCKS5 success, atyp=1, addr=0.0.0.0, port=0
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()

                // Pipe bi-directionally
                pipeSockets(client, torSocket)

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
                client.close()
            } else {
                sendErrorResponse(output, 0x07) // Command not supported
                client.close()
            }
        } catch (e: Exception) {
            Logger.w("LocalSocksProxy", "Error handling TCP client: ${e.message}")
            try { client.close() } catch (_: Exception) {}
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
        thread(name = "socks-pipe-1") {
            try {
                val in1 = s1.getInputStream()
                val out2 = s2.getOutputStream()
                val buf = ByteArray(8192)
                var len: Int
                while (in1.read(buf).also { len = it } >= 0) {
                    out2.write(buf, 0, len)
                    out2.flush()
                }
            } catch (_: Exception) {}
            try { s2.close() } catch (_: Exception) {}
            try { s1.close() } catch (_: Exception) {}
        }
        thread(name = "socks-pipe-2") {
            try {
                val in2 = s2.getInputStream()
                val out1 = s1.getOutputStream()
                val buf = ByteArray(8192)
                var len: Int
                while (in2.read(buf).also { len = it } >= 0) {
                    out1.write(buf, 0, len)
                    out1.flush()
                }
            } catch (_: Exception) {}
            try { s1.close() } catch (_: Exception) {}
            try { s2.close() } catch (_: Exception) {}
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
                
                thread(name = "socks-udp-packet") {
                    handleUdpPacket(packetData, clientAddress, clientPort)
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
            if (data.size < 10) return
            // Parse SOCKS5 UDP header
            val frag = data[2]
            val atyp = data[3]
            if (frag != 0.toByte()) {
                return // We don't support fragmentation
            }

            var headerLen = 4
            when (atyp.toInt()) {
                0x01 -> { // IPv4
                    headerLen += 4
                }
                0x03 -> { // Domain
                    val len = data[headerLen].toInt() and 0xFF
                    headerLen += 1 + len
                }
                0x04 -> { // IPv6
                    headerLen += 16
                }
                else -> return
            }

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
            DatagramSocket().use { socket ->
                socket.soTimeout = 8000
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
}
