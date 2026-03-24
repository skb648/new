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
        
        // Buffer sizes
        private const val TUN_BUFFER_SIZE = 65536
        private const val SOCKET_BUFFER_SIZE = 65536
        
        // Timeouts
        private const val CONNECTION_TIMEOUT_MS = 30000
        private const val READ_TIMEOUT_MS = 60000
        
        // Keep-alive settings
        private const val KEEP_ALIVE_INTERVAL_MS = 30000L
        private const val KEEP_ALIVE_DATA = byteArrayOf(
            0x00, 0x00, 0x00, 0x00
        )
    }

    // Active connections
    private val tcpConnections = ConcurrentHashMap<Int, TcpConnection>()
    private val udpChannels = ConcurrentHashMap<Int, UdpChannel>()
    
    // Statistics
    private val bytesIn = AtomicLong(0)
    private val bytesOut = AtomicLong(0)
    
    // Control flags
    private val isRunning = AtomicBoolean(false)
    private val isStopping = AtomicBoolean(false)
    
    // Coroutines
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var keepAliveJob: Job? = null

    // Server connection (protected from VPN routing)
    private var mainTcpChannel: SocketChannel? = null
    private var mainUdpChannel: DatagramChannel? = null

    /**
     * Start the proxy engine
     * 
     * This method creates protected connections to the external server
     * and begins forwarding traffic between the TUN interface and the server.
     */
    fun start(): Boolean {
        if (isRunning.get()) {
            Log.w(TAG, "Proxy engine already running")
            return true
        }

        val server = config.server ?: run {
            Log.e(TAG, "No server configuration")
            return false
        }

        Log.i(TAG, "Starting proxy engine for ${server.serverIp}:${server.port}")
        
        try {
            // Create main TCP connection to server (PROTECTED!)
            if (server.protocol.equals("TCP", ignoreCase true)) {
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

            // Start processing threads
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
            false
        }
    }

    /**
     * Stop the proxy engine
     */
    fun stop() {
        if (!isRunning.get()) return

        Log.i(TAG, "Stopping proxy engine")
        isStopping.set(true)
        isRunning.set(false)

        // Cancel jobs
        readJob?.cancel()
        writeJob?.cancel()
        keepAliveJob?.cancel()

        // Close connections
        mainTcpChannel?.close()
        mainUdpChannel?.close()

        // Close all TCP connections
        tcpConnections.values.forEach { it.close() }
        tcpConnections.clear()

        // Close all UDP channels
        udpChannels.values.forEach { it.close() }
        udpChannels.clear()

        engineScope.cancel()

        Log.i(TAG, "Proxy engine stopped")
    }

    /**
     * Create a PROTECTED TCP connection to external server
     * 
     * CRITICAL: We MUST call vpnService.protect() before connecting.
     * This exempts the socket from VPN routing,     */
    private fun createProtectedTcpConnection(serverIp: String, port: Int): SocketChannel? {
        return try {
            Log.d(TAG, "Creating protected TCP connection to $serverIp:$port")

            // Create socket channel
            val channel = SocketChannel.open()
            channel.configureBlocking(true)

            // CRITICAL: PROTECT THE SOCKET BEFORE CONNECTING
            // This is the key fix for the routing loop issue
            val socket = channel.socket()
            if (!vpnService.protect(socket)) {
                Log.e(TAG, "CRITICAL: Failed to protect TCP socket - aborting connection")
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
                
                // Perform handshake if needed
                if (!performHandshake(channel)) {
                    Log.e(TAG, "Handshake failed")
                    channel.close()
                    return null
                }
                
                return channel
            } else {
                Log.e(TAG, "TCP connection not established")
                channel.close()
                return null
            }

        } catch (e: UnknownHostException) {
            Log.e(TAG, "DNS resolution failed for $serverIp", e)
            vpnService.sendError("DNS resolution failed: $serverIp - ${e.message}")
            null
        } catch (e: ConnectException) {
            Log.e(TAG, "Connection refused by $serverIp:$port", e)
            vpnService.sendError("Connection refused by server: ${server.serverIp}:${server.port}")
            null
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Connection timeout to $serverIp:$port", e)
            vpnService.sendError("Connection timeout: ${server.serverIp}:${server.port}")
            null
        } catch (e: IOException) {
            Log.e(TAG, "IO error connecting to $serverIp:$port", e)
            vpnService.sendError("Network error: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create TCP connection: ${e.message}", e)
            vpnService.sendError("Connection failed: ${e.message}")
            null
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
            if (!vpnService.protect(socket)) {
                Log.e(TAG, "CRITICAL: Failed to protect UDP socket - aborting")
                channel.close()
                return null
            }
            Log.i(TAG, "UDP socket protected successfully - exempted from VPN routing")

            // Connect
            val address = InetSocketAddress(serverIp, port)
            channel.connect(address)

            Log.i(TAG, "Protected UDP channel created to $serverIp:$port")
            return channel

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create UDP channel: ${e.message}", e)
            null
        }
    }

    /**
     * Perform handshake with server
     */
    private fun performHandshake(channel: SocketChannel): Boolean {
        try {
            val server = config.server ?: return false
            
            // Build handshake packet
            val handshake = buildHandshakePacket()
            
            // Send handshake
            val buffer = ByteBuffer.wrap(handshake)
            channel.write(buffer)
            
            // Wait for response
            channel.socket().soTimeout = 10000 // 10 second timeout
            val responseBuffer = ByteBuffer.allocate(1024)
            val bytesRead = channel.read(responseBuffer)
            
            if (bytesRead > 0) {
                Log.i(TAG, "Handshake successful, received $bytesRead bytes")
                return true
            }
            
            return false

        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Handshake timeout - server did not respond")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Handshake failed: ${e.message}", e)
            false
        }
    }

    /**
     * Build handshake packet
     */
    private fun buildHandshakePacket(): ByteArray {
        val server = config.server ?: return ByteArray(0)
        
        // Build handshake with authentication
        val builder = StringBuilder()
        builder.append("ENTERPRISE_VPN/1.0\r\n")
        builder.append("Host: ${server.serverIp}:${server.port}\r\n")
        
        // Add authentication if provided
        if (server.username.isNotEmpty() && server.password.isNotEmpty()) {
            val credentials = "${server.username}:${server.password}"
            val encoded = android.util.Base64.encodeToString(
                credentials.toByteArray(),
                android.util.Base64.NO_WRAP
            )
            builder.append("Authorization: Basic $encoded\r\n")
        }
        
        // Add custom HTTP headers
        config.enabledHeaders.forEach { header ->
            builder.append("${header.name}: ${header.value}\r\n")
        }
        
        // Add SNI if configured
        config.sniConfig?.let { sni ->
            if (sni.enabled && sni.serverName.isNotEmpty()) {
                builder.append("SNI: ${sni.serverName}\r\n")
            }
        }
        
        builder.append("\r\n")
        
        return builder.toString().toByteArray()
    }

    /**
     * Read packets from TUN interface
     */
    private suspend fun readFromTun() {
        Log.i(TAG, "Starting TUN read loop")
        
        val inputStream = vpnService.getVpnInputStream() ?: return
        val buffer = ByteBuffer.allocate(TUN_BUFFER_SIZE)
        
        while (isRunning.get() && !isStopping.get()) {
            try {
                // Read packet from TUN
                val bytesRead = inputStream.read(buffer.array())
                if (bytesRead > 0) {
                    // Process and forward packet
                    processOutgoingPacket(buffer, bytesRead)
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

    /**
     * Process outgoing packet (from TUN to external server)
     */
    private fun processOutgoingPacket(buffer: ByteBuffer, length: Int) {
        try {
            // Get packet info (IP header)
            val firstByte = buffer.array()[0]
            val protocol = firstByte.toInt() shr 4
            
            // IPv4 = 4, IPv6 = 6
            if (protocol == 4) {
                processIPv4Packet(buffer, length)
            } else if (protocol == 6) {
                processIPv6Packet(buffer, length)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing outgoing packet", e)
        }
    }

    /**
     * Process IPv4 packet
     */
    private fun processIPv4Packet(buffer: ByteBuffer, length: Int) {
        try {
            // Extract IP header
            val ipHeader = buffer.array()[0]
            val protocol = buffer.array()[9].toInt() and 0xFF
            val srcPort = ((buffer.array()[20].toInt() and 0xFF) shl 8) or (buffer.array()[21].toInt() and 0xFF)
            val dstPort = ((buffer.array()[22].toInt() and 0xFF) shl 8) or (buffer.array()[23].toInt() and 0xFF)
            
            // Forward based on protocol
            when (protocol) {
                6 -> forwardTcpPacket(buffer, length, srcPort, dstPort) // TCP
                17 -> forwardUdpPacket(buffer, length, srcPort, dstPort) // UDP
                1 -> forwardIcmpPacket(buffer, length) // ICMP
            }
            
            bytesOut.addAndGetAndAdd(length.toLong())
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing IPv4 packet", e)
        }
    }

    /**
     * Process IPv6 packet
     */
    private fun processIPv6Packet(buffer: ByteBuffer, length: Int) {
        try {
            // IPv6 next header field is at offset 6
            val protocol = buffer.array()[6].toInt() and 0xFF
            
            when (protocol) {
                6 -> forwardTcpPacket(buffer, length, 0, 0)
                17 -> forwardUdpPacket(buffer, length, 0, 0)
            }
            
            bytesOut.addAndGetAndAdd(length.toLong())
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing IPv6 packet", e)
        }
    }

    /**
     * Forward TCP packet to server
     */
    private fun forwardTcpPacket(buffer: ByteBuffer, length: Int, srcPort: Int, dstPort: Int) {
        try {
            // Use main TCP channel if available
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

    /**
     * Forward UDP packet to server
     */
    private fun forwardUdpPacket(buffer: ByteBuffer, length: Int, srcPort: Int, dstPort: Int) {
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

    /**
     * Forward ICMP packet
     */
    private fun forwardIcmpPacket(buffer: ByteBuffer, length: Int) {
        // ICMP is typically forwarded via UDP channel
        forwardUdpPacket(buffer, length, 0, 0)
    }

    /**
     * Write packets to TUN interface
     */
    private suspend fun writeToTun() {
        Log.i(TAG, "Starting TUN write loop")
        
        val outputStream = vpnService.getVpnOutputStream() ?: return
        val buffer = ByteBuffer.allocate(TUN_BUFFER_SIZE)
        
        while (isRunning.get() && !isStopping.get()) {
            try {
                // Read from main channel
                mainTcpChannel?.let { channel ->
                    if (channel.isConnected) {
                        buffer.clear()
                        val bytesRead = channel.read(buffer)
                        if (bytesRead > 0) {
                            // Write to TUN
                            outputStream.write(buffer.array(), 0, bytesRead)
                            bytesIn.addAndGetAndAdd(bytesRead.toLong())
                        }
                    }
                }
                
                // Also check UDP channel
                mainUdpChannel?.let { channel ->
                    if (channel.isOpen) {
                        buffer.clear()
                        val bytesRead = channel.read(buffer)
                        if (bytesRead > 0) {
                            outputStream.write(buffer.array(), 0, bytesRead)
                            bytesIn.addAndGetAndAdd(bytesRead.toLong())
                        }
                    }
                }
                
                delay(1) // Small delay to prevent busy loop
                
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

    /**
     * Send keep-alive packets
     */
    private suspend fun sendKeepAlive() {
        while (isRunning.get() && !isStopping.get()) {
                delay(KEEP_ALIVE_INTERVAL_MS)
                
                if (!isRunning.get() || isStopping.get()) break
                
                try {
                    // Send TCP keep-alive
                    mainTcpChannel?.let { channel ->
                        if (channel.isConnected) {
                            val buffer = ByteBuffer.wrap(KEEP_ALIVE_DATA)
                            channel.write(buffer)
                            Log.d(TAG, "Sent TCP keep-alive")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send TCP keep-alive", e)
                }
            }
        }
    }

    /**
     * Get bytes in statistics
     */
    fun getBytesIn(): Long = bytesIn.get()

    /**
     * Get bytes out statistics
     */
    fun getBytesOut(): Long = bytesOut.get()

    /**
     * Check if engine is running
     */
    fun isRunning(): Boolean = isRunning.get()

    /**
     * Delay helper
     */
    private suspend fun delay(ms: Long) {
        kotlinx.coroutines.delay(ms)
    }

    /**
     * TCP Connection helper class
     */
    inner class TcpConnection(
        val channel: SocketChannel,
    ) {
        fun close() {
            try {
                channel.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing TCP connection", e)
            }
        }
    }

    /**
     * UDP Channel helper class
     */
    inner class UdpChannel(
        val channel: DatagramChannel
    ) {
        fun close() {
            try {
                channel.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing UDP channel", e)
            }
        }
    }
}
