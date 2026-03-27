package com.enterprise.vpn.proxy

import android.util.Log
import com.enterprise.vpn.model.*
import com.enterprise.vpn.service.EnterpriseVpnService
import kotlinx.coroutines.*
import java.io.IOException
import java.net.*
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Proxy Engine
 * 
 * Handles traffic forwarding between the TUN interface and the external server.
 * 
 * CRITICAL: All outbound sockets MUST be protected using vpnService.protect()
 * before connecting to prevent routing loops.
 */
class ProxyEngine(
    private val vpnService: EnterpriseVpnService,
    private val config: VpnConfig
) {
    companion object {
        private const val TAG = "ProxyEngine"
        private const val TUN_BUFFER_SIZE = 65536
        private const val SOCKET_BUFFER_SIZE = 65536
        private const val CONNECTION_TIMEOUT_MS = 30000
        private const val KEEP_ALIVE_INTERVAL_MS = 30000L
    }

    private val tcpConnections = ConcurrentHashMap<Int, SocketChannel>()
    private val udpChannels = ConcurrentHashMap<Int, DatagramChannel>()
    
    private val bytesIn = AtomicLong(0)
    private val bytesOut = AtomicLong(0)
    
    private val isRunning = AtomicBoolean(false)
    private val isStopping = AtomicBoolean(false)
    
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var keepAliveJob: Job? = null

    private var mainTcpChannel: SocketChannel? = null
    private var mainUdpChannel: DatagramChannel? = null

    fun start(): Boolean {
        if (isRunning.get()) {
            Log.w(TAG, "Proxy engine already running")
            return true
        }

        val server = config.server
        if (server == null) {
            Log.e(TAG, "No server configuration")
            return false
        }

        Log.i(TAG, "Starting proxy engine for ${server.serverIp}:${server.port}")
        
        try {
            // Create main TCP connection to server (PROTECTED!)
            if (server.protocol.equals("TCP", ignoreCase = true)) {
                mainTcpChannel = createProtectedTcpConnection(server.serverIp, server.port)
                if (mainTcpChannel == null) {
                    Log.e(TAG, "Failed to create protected TCP connection")
                    return false
                }
                Log.i(TAG, "Main TCP connection established")
            }

            // Create main UDP channel to server (PROTECTED!)
            mainUdpChannel = createProtectedUdpChannel(server.serverIp, server.port)
            if (mainUdpChannel != null) {
                Log.i(TAG, "Main UDP channel established")
            }

            isRunning.set(true)
            isStopping.set(false)

            readJob = engineScope.launch { readFromTun() }
            writeJob = engineScope.launch { writeToTun() }
            keepAliveJob = engineScope.launch { sendKeepAlive() }

            Log.i(TAG, "Proxy engine started successfully")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy engine", e)
            stop()
            return false
        }
    }

    fun stop() {
        if (!isRunning.get()) return

        Log.i(TAG, "Stopping proxy engine")
        isStopping.set(true)
        isRunning.set(false)

        readJob?.cancel()
        writeJob?.cancel()
        keepAliveJob?.cancel()

        try { mainTcpChannel?.close() } catch (e: Exception) { Log.w(TAG, "Error closing TCP", e) }
        try { mainUdpChannel?.close() } catch (e: Exception) { Log.w(TAG, "Error closing UDP", e) }

        tcpConnections.values.forEach { channel ->
            try { channel.close() } catch (e: Exception) { }
        }
        tcpConnections.clear()

        udpChannels.values.forEach { channel ->
            try { channel.close() } catch (e: Exception) { }
        }
        udpChannels.clear()

        engineScope.cancel()
        Log.i(TAG, "Proxy engine stopped")
    }

    /**
     * Create a PROTECTED TCP connection to external server
     * 
     * CRITICAL: We MUST call vpnService.protect() before connecting.
     */
    private fun createProtectedTcpConnection(serverIp: String, port: Int): SocketChannel? {
        return try {
            Log.d(TAG, "Creating protected TCP connection to $serverIp:$port")

            val channel = SocketChannel.open()
            channel.configureBlocking(true)

            // CRITICAL: PROTECT THE SOCKET BEFORE CONNECTING
            // This is the key fix for the routing loop issue
            val socket = channel.socket()
            val protected = vpnService.protect(socket)
            if (!protected) {
                val errorMsg = "CRITICAL: Failed to protect TCP socket - VPN protect() returned false"
                Log.e(TAG, errorMsg)
                sendErrorToFlutter(errorMsg, "PROTECT_FAILED")
                channel.close()
                return null
            }
            Log.i(TAG, "TCP socket protected successfully - exempted from VPN routing")

            // Set socket options
            socket.soTimeout = CONNECTION_TIMEOUT_MS
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.receiveBufferSize = SOCKET_BUFFER_SIZE
            socket.sendBufferSize = SOCKET_BUFFER_SIZE

            // Connect to server
            Log.d(TAG, "Connecting protected TCP socket to $serverIp:$port")
            val address = InetSocketAddress(serverIp, port)
            channel.connect(address)

            if (channel.isConnected) {
                Log.i(TAG, "Protected TCP connection established to $serverIp:$port")
                
                // Perform handshake
                if (!performHandshake(channel)) {
                    val errorMsg = "Handshake failed - server did not respond correctly"
                    Log.e(TAG, errorMsg)
                    sendErrorToFlutter(errorMsg, "HANDSHAKE_FAILED")
                    channel.close()
                    return null
                }
                
                return channel
            } else {
                val errorMsg = "TCP connection not established after connect() call"
                Log.e(TAG, errorMsg)
                sendErrorToFlutter(errorMsg, "CONNECTION_FAILED")
                channel.close()
                return null
            }

        } catch (e: UnknownHostException) {
            val errorMsg = "DNS resolution failed for $serverIp: ${e.message}"
            Log.e(TAG, errorMsg, e)
            sendErrorToFlutter(errorMsg, "DNS_ERROR")
            null
        } catch (e: ConnectException) {
            val errorMsg = "Connection REFUSED by $serverIp:$port - Server not listening or firewall blocking: ${e.message}"
            Log.e(TAG, errorMsg, e)
            sendErrorToFlutter(errorMsg, "CONNECTION_REFUSED")
            null
        } catch (e: SocketTimeoutException) {
            val errorMsg = "Connection TIMEOUT to $serverIp:$port - Server not responding after ${CONNECTION_TIMEOUT_MS}ms: ${e.message}"
            Log.e(TAG, errorMsg, e)
            sendErrorToFlutter(errorMsg, "CONNECTION_TIMEOUT")
            null
        } catch (e: java.net.NoRouteToHostException) {
            val errorMsg = "No route to host $serverIp - Network unreachable: ${e.message}"
            Log.e(TAG, errorMsg, e)
            sendErrorToFlutter(errorMsg, "NO_ROUTE")
            null
        } catch (e: java.nio.channels.UnresolvedAddressException) {
            val errorMsg = "Unresolved address: $serverIp - Invalid IP/hostname: ${e.message}"
            Log.e(TAG, errorMsg, e)
            sendErrorToFlutter(errorMsg, "UNRESOLVED_ADDRESS")
            null
        } catch (e: SecurityException) {
            val errorMsg = "Security exception - Permission denied: ${e.message}"
            Log.e(TAG, errorMsg, e)
            sendErrorToFlutter(errorMsg, "PERMISSION_DENIED")
            null
        } catch (e: IOException) {
            val errorMsg = "IO error connecting to $serverIp:$port: ${e.message}"
            Log.e(TAG, errorMsg, e)
            sendErrorToFlutter(errorMsg, "IO_ERROR")
            null
        } catch (e: Exception) {
            val errorMsg = "Unexpected error connecting to $serverIp:$port: ${e.javaClass.simpleName} - ${e.message}"
            Log.e(TAG, errorMsg, e)
            sendErrorToFlutter(errorMsg, "UNKNOWN_ERROR")
            null
        }
    }

    /**
     * Send detailed error to Flutter via VpnService
     */
    private fun sendErrorToFlutter(message: String, code: String) {
        try {
            // Create error event
            val event = VpnEvent(
                type = VpnEventType.ERROR,
                message = "[$code] $message",
                data = mapOf("errorCode" to code, "originalMessage" to message)
            )
            
            // Emit through the companion object's StateFlow
            // This will be picked up by VpnServiceManager and sent to Flutter
            EnterpriseVpnService.updateEvent(event)
            
            Log.e(TAG, "🔥 ERROR SENT TO FLUTTER: [$code] $message")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send error to Flutter: ${e.message}")
        }
    }

    /**
     * Create a PROTECTED UDP channel to external server
     */
    private fun createProtectedUdpChannel(serverIp: String, port: Int): DatagramChannel? {
        return try {
            Log.d(TAG, "Creating protected UDP channel to $serverIp:$port")

            val channel = DatagramChannel.open()
            channel.configureBlocking(false)

            // CRITICAL: PROTECT THE CHANNEL BEFORE CONNECTING
            val socket = channel.socket()
            val protected = vpnService.protect(socket)
            if (!protected) {
                Log.e(TAG, "CRITICAL: Failed to protect UDP socket - aborting")
                channel.close()
                return null
            }
            Log.i(TAG, "UDP socket protected successfully - exempted from VPN routing")

            val address = InetSocketAddress(serverIp, port)
            channel.connect(address)

            Log.i(TAG, "Protected UDP channel created to $serverIp:$port")
            return channel

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create UDP channel: ${e.message}", e)
            null
        }
    }

    private fun performHandshake(channel: SocketChannel): Boolean {
        try {
            val server = config.server ?: return false
            
            // Build handshake packet
            val handshake = buildHandshakePacket()
            
            // Send handshake
            val buffer = ByteBuffer.wrap(handshake)
            channel.write(buffer)
            
            // Wait for response
            channel.socket().soTimeout = 10000
            val responseBuffer = ByteBuffer.allocate(1024)
            val bytesRead = channel.read(responseBuffer)
            
            if (bytesRead > 0) {
                Log.i(TAG, "Handshake successful, received $bytesRead bytes")
                return true
            }
            
            return false

        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Handshake timeout - server did not respond")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Handshake failed: ${e.message}", e)
            return false
        }
    }

    private fun buildHandshakePacket(): ByteArray {
        val server = config.server ?: return ByteArray(0)
        
        val builder = StringBuilder()
        builder.append("ENTERPRISE_VPN/1.0\r\n")
        builder.append("Host: ${server.serverIp}:${server.port}\r\n")
        
        if (server.username.isNotEmpty() && server.password.isNotEmpty()) {
            val credentials = "${server.username}:${server.password}"
            val encoded = android.util.Base64.encodeToString(
                credentials.toByteArray(),
                android.util.Base64.NO_WRAP
            )
            builder.append("Authorization: Basic $encoded\r\n")
        }
        
        config.enabledHeaders.forEach { header ->
            builder.append("${header.name}: ${header.value}\r\n")
        }
        
        config.sniConfig?.let { sni ->
            if (sni.enabled && sni.serverName.isNotEmpty()) {
                builder.append("SNI: ${sni.serverName}\r\n")
            }
        }
        
        builder.append("\r\n")
        
        return builder.toString().toByteArray()
    }

    private suspend fun readFromTun() {
        Log.i(TAG, "Starting TUN read loop")
        
        val inputStream = vpnService.getVpnInputStream()
        if (inputStream == null) {
            Log.e(TAG, "VPN input stream is null")
            return
        }
        
        val buffer = ByteBuffer.allocate(TUN_BUFFER_SIZE)
        
        while (isRunning.get() && !isStopping.get()) {
            try {
                val bytesRead = inputStream.read(buffer.array())
                if (bytesRead > 0) {
                    processOutgoingPacket(buffer, bytesRead)
                    bytesOut.addAndGet(bytesRead.toLong())
                }
            } catch (e: IOException) {
                if (isStopping.get()) break
                Log.e(TAG, "Error reading from TUN", e)
                delay(100)
            } catch (e: Exception) {
                if (isStopping.get()) break
                Log.e(TAG, "Error in read loop", e)
                delay(100)
            }
        }
        
        Log.i(TAG, "TUN read loop ended")
    }

    private fun processOutgoingPacket(buffer: ByteBuffer, length: Int) {
        try {
            val firstByte = buffer.array()[0]
            val protocol = (firstByte.toInt() shr 4) and 0x0F
            
            if (protocol == 4) {
                processIPv4Packet(buffer, length)
            } else if (protocol == 6) {
                processIPv6Packet(buffer, length)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing outgoing packet", e)
        }
    }

    private fun processIPv4Packet(buffer: ByteBuffer, length: Int) {
        try {
            val protocol = buffer.array()[9].toInt() and 0xFF
            
            when (protocol) {
                6 -> forwardTcpPacket(buffer, length) // TCP
                17 -> forwardUdpPacket(buffer, length) // UDP
                1 -> forwardIcmpPacket(buffer, length) // ICMP
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing IPv4 packet", e)
        }
    }

    private fun processIPv6Packet(buffer: ByteBuffer, length: Int) {
        try {
            val protocol = buffer.array()[6].toInt() and 0xFF
            
            when (protocol) {
                6 -> forwardTcpPacket(buffer, length)
                17 -> forwardUdpPacket(buffer, length)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing IPv6 packet", e)
        }
    }

    private fun forwardTcpPacket(buffer: ByteBuffer, length: Int) {
        try {
            mainTcpChannel?.let { channel ->
                if (channel.isConnected) {
                    val writeBuffer = ByteBuffer.wrap(buffer.array(), 0, length)
                    channel.write(writeBuffer)
                }
            }
        } catch (e: Exception) {
            if (!isStopping.get()) {
                Log.e(TAG, "Error forwarding TCP packet", e)
            }
        }
    }

    private fun forwardUdpPacket(buffer: ByteBuffer, length: Int) {
        try {
            mainUdpChannel?.let { channel ->
                if (channel.isOpen) {
                    val writeBuffer = ByteBuffer.wrap(buffer.array(), 0, length)
                    channel.write(writeBuffer)
                }
            }
        } catch (e: Exception) {
            if (!isStopping.get()) {
                Log.e(TAG, "Error forwarding UDP packet", e)
            }
        }
    }

    private fun forwardIcmpPacket(buffer: ByteBuffer, length: Int) {
        forwardUdpPacket(buffer, length)
    }

    private suspend fun writeToTun() {
        Log.i(TAG, "Starting TUN write loop")
        
        val outputStream = vpnService.getVpnOutputStream()
        if (outputStream == null) {
            Log.e(TAG, "VPN output stream is null")
            return
        }
        
        val buffer = ByteBuffer.allocate(TUN_BUFFER_SIZE)
        
        while (isRunning.get() && !isStopping.get()) {
            try {
                // Read from main channel
                mainTcpChannel?.let { channel ->
                    if (channel.isConnected) {
                        buffer.clear()
                        val bytesRead = channel.read(buffer)
                        if (bytesRead > 0) {
                            outputStream.write(buffer.array(), 0, bytesRead)
                            bytesIn.addAndGet(bytesRead.toLong())
                        }
                    }
                }
                
                mainUdpChannel?.let { channel ->
                    if (channel.isOpen) {
                        buffer.clear()
                        val bytesRead = channel.read(buffer)
                        if (bytesRead > 0) {
                            outputStream.write(buffer.array(), 0, bytesRead)
                            bytesIn.addAndGet(bytesRead.toLong())
                        }
                    }
                }
                
                delay(1)
                
            } catch (e: IOException) {
                if (isStopping.get()) break
                Log.e(TAG, "Error writing to TUN", e)
                delay(100)
            } catch (e: Exception) {
                if (isStopping.get()) break
                Log.e(TAG, "Error in write loop", e)
                delay(100)
            }
        }
        
        Log.i(TAG, "TUN write loop ended")
    }

    private suspend fun sendKeepAlive() {
        while (isRunning.get() && !isStopping.get()) {
            delay(KEEP_ALIVE_INTERVAL_MS)
            
            if (!isRunning.get() || isStopping.get()) break
            
            try {
                mainTcpChannel?.let { channel ->
                    if (channel.isConnected) {
                        val keepAliveData = byteArrayOf(0x00, 0x00, 0x00, 0x00)
                        val buffer = ByteBuffer.wrap(keepAliveData)
                        channel.write(buffer)
                        Log.d(TAG, "Sent TCP keep-alive")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send TCP keep-alive", e)
            }
        }
    }

    fun getBytesIn(): Long = bytesIn.get()
    fun getBytesOut(): Long = bytesOut.get()
    fun isRunning(): Boolean = isRunning.get()

    private suspend fun delay(ms: Long) {
        kotlinx.coroutines.delay(ms)
    }
}
