package com.enterprise.vpn.proxy

import android.content.Context
import android.net.VpnService
import android.util.Log
import com.enterprise.vpn.HttpHeaderConfig
import com.enterprise.vpn.SniConfig
import com.enterprise.vpn.packet.PacketProcessor
import com.enterprise.vpn.packet.IpPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Proxy Engine
 * 
 * Core proxy routing engine that:
 * - Intercepts packets from TUN interface
 * - Routes TCP/UDP traffic to target server
 * - Injects custom HTTP headers
 * - Modifies SNI for TLS connections
 * - Handles connection pooling for performance
 */
class ProxyEngine(private val vpnService: VpnService) {

    companion object {
        private const val TAG = "ProxyEngine"
        
        // Buffer sizes
        private const val TCP_BUFFER_SIZE = 65536
        private const val UDP_BUFFER_SIZE = 32768
        
        // Connection pool settings
        private const val MAX_TCP_CONNECTIONS = 100
        private const val CONNECTION_TIMEOUT_MS = 60000L
        private const val IDLE_TIMEOUT_MS = 30000L
        
        // Speed test
        private const val SPEED_TEST_DURATION_MS = 10000L
        private const val SPEED_TEST_PACKET_SIZE = 1024
    }

    // ============================================
    // STATE
    // ============================================

    private val scope = CoroutineScope(Dispatchers.IO)
    private var proxyJob: Job? = null
    private var speedTestJob: Job? = null
    
    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    
    // Configuration
    private var config: ProxyConfig? = null
    private var httpHeaders: List<HttpHeaderConfig> = emptyList()
    private var sniConfig: SniConfig? = null
    
    // Packet processor
    private val packetProcessor = PacketProcessor()
    
    // Connection management
    private val tcpConnections = ConcurrentHashMap<Int, TcpConnection>()
    private val udpChannels = ConcurrentHashMap<Int, UdpChannel>()
    
    // Selector for non-blocking I/O
    private var selector: Selector? = null
    
    // Incoming packet flow (for writing back to TUN)
    private val _incomingPacketFlow = MutableSharedFlow<IncomingPacket>(extraBufferCapacity = 1024)
    val incomingPacketFlow: SharedFlow<IncomingPacket> = _incomingPacketFlow.asSharedFlow()
    
    // Speed test state
    private val isSpeedTestRunning = AtomicBoolean(false)
    private var speedTestResults = SpeedTestResults()
    
    // Statistics
    private var totalBytesSent = 0L
    private var totalBytesReceived = 0L
    private var connectionCount = 0

    // ============================================
    // CONFIGURATION
    // ============================================

    /**
     * Configure the proxy engine
     */
    fun configure(config: ProxyConfig) {
        this.config = config
        this.httpHeaders = config.httpHeaders
        this.sniConfig = config.sniConfig
        
        Log.d(TAG, "ProxyEngine configured - Server: ${config.serverIp}:${config.serverPort}, " +
                "Headers: ${httpHeaders.size}, SNI: ${sniConfig?.serverName}")
    }

    /**
     * Update HTTP headers configuration
     */
    fun updateHttpHeaders(headers: List<HttpHeaderConfig>) {
        this.httpHeaders = headers.filter { it.enabled }
        Log.d(TAG, "HTTP headers updated: ${this.httpHeaders.size} active headers")
    }

    /**
     * Update SNI configuration
     */
    fun updateSniConfig(config: SniConfig) {
        this.sniConfig = if (config.enabled) config else null
        Log.d(TAG, "SNI config updated: ${this.sniConfig?.serverName}")
    }

    // ============================================
    // LIFECYCLE
    // ============================================

    /**
     * Start the proxy engine
     */
    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "ProxyEngine already running")
            return
        }
        
        Log.d(TAG, "Starting ProxyEngine")
        
        // Initialize selector for non-blocking I/O
        selector = Selector.open()
        
        // Start proxy loop
        proxyJob = scope.launch {
            try {
                runProxyLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Proxy loop error", e)
            }
        }
        
        Log.d(TAG, "ProxyEngine started")
    }

    /**
     * Stop the proxy engine
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }
        
        Log.d(TAG, "Stopping ProxyEngine")
        
        // Cancel proxy job
        proxyJob?.cancel()
        speedTestJob?.cancel()
        
        // Close all connections
        closeAllConnections()
        
        // Close selector
        selector?.close()
        selector = null
        
        Log.d(TAG, "ProxyEngine stopped")
    }

    /**
     * Pause traffic routing
     */
    fun pause() {
        isPaused.set(true)
        Log.d(TAG, "ProxyEngine paused")
    }

    /**
     * Resume traffic routing
     */
    fun resume() {
        isPaused.set(false)
        Log.d(TAG, "ProxyEngine resumed")
    }

    // ============================================
    // PACKET PROCESSING
    // ============================================

    /**
     * Process outgoing packet from TUN interface
     * Returns true if packet was processed successfully
     */
    fun processOutgoingPacket(data: ByteArray, length: Int): Boolean {
        if (!isRunning.get() || isPaused.get()) {
            return false
        }
        
        try {
            // Parse IP packet
            val packet = packetProcessor.parsePacket(data, length) ?: return false
            
            when (packet.protocol) {
                IpPacket.PROTOCOL_TCP -> {
                    processTcpPacket(packet)
                }
                IpPacket.PROTOCOL_UDP -> {
                    processUdpPacket(packet)
                }
                IpPacket.PROTOCOL_ICMP -> {
                    // Forward ICMP packets (for ping)
                    forwardIcmpPacket(packet)
                }
            }
            
            totalBytesSent += length
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing outgoing packet", e)
            return false
        }
    }

    /**
     * Process TCP packet
     */
    private fun processTcpPacket(packet: IpPacket) {
        val srcPort = packet.srcPort
        val dstPort = packet.dstPort
        val connectionKey = packet.hashCode()
        
        // Get or create TCP connection
        val connection = tcpConnections.getOrPut(connectionKey) {
            createTcpConnection(packet)
        }
        
        if (connection == null) {
            Log.w(TAG, "Failed to create TCP connection for $srcPort -> $dstPort")
            return
        }
        
        // Process packet through connection
        connection.handleOutgoingPacket(packet)
    }

    /**
     * Create a new TCP connection
     * Routes traffic through remote proxy server when configured
     */
    private fun createTcpConnection(packet: IpPacket): TcpConnection? {
        val cfg = config ?: return null
        
        try {
            // Determine target server (remote proxy or direct)
            val targetIp = if (cfg.hasRemoteProxy) cfg.remoteServerIp else cfg.serverIp
            val targetPort = if (cfg.hasRemoteProxy) cfg.remoteServerPort else cfg.serverPort
            
            Log.d(TAG, "Creating TCP connection to $targetIp:$targetPort " +
                    "(original: ${packet.dstAddress}:${packet.dstPort})")
            
            // Protect socket from VPN routing (avoid loop)
            val socketChannel = SocketChannel.open()
            socketChannel.configureBlocking(false)
            
            // Protect socket so it bypasses VPN
            if (!vpnService.protect(socketChannel.socket())) {
                Log.e(TAG, "Failed to protect socket")
                socketChannel.close()
                return null
            }
            
            // Connect to target server via proxy
            val serverAddress = InetSocketAddress(targetIp, targetPort)
            socketChannel.connect(serverAddress)
            
            // Register with selector
            socketChannel.register(selector!!, SelectionKey.OP_CONNECT, connectionKey)
            
            // Create connection with proxy routing configuration
            val connection = TcpConnection(
                socketChannel = socketChannel,
                packet = packet,
                httpHeaders = httpHeaders,
                sniConfig = sniConfig,
                proxyConfig = cfg,
                // Callback for incoming responses
                onResponse = { responsePacket ->
                    _incomingPacketFlow.tryEmit(responsePacket)
                },
                // Flag to indicate routing through external proxy
                routeThroughProxy = cfg.hasRemoteProxy,
                // Authentication for proxy connection
                proxyAuth = if (cfg.hasAuthentication) {
                    ProxyAuth(cfg.authUsername, cfg.authPassword)
                } else null
            )
            
            connectionCount++
            Log.d(TAG, "TCP connection created: ${packet.srcPort} -> ${packet.dstPort} " +
                    "via $targetIp:$targetPort (total: $connectionCount)")
            
            return connection
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create TCP connection", e)
            return null
        }
    }

    /**
     * Process UDP packet
     */
    private fun processUdpPacket(packet: IpPacket) {
        val cfg = config ?: return
        
        try {
            val dstPort = packet.dstPort
            
            // DNS queries (port 53) - route through our DNS or forward
            if (dstPort == 53) {
                handleDnsPacket(packet)
                return
            }
            
            // Get or create UDP channel
            val channelKey = packet.hashCode()
            val udpChannel = udpChannels.getOrPut(channelKey) {
                createUdpChannel(packet)
            }
            
            udpChannel?.send(packet)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing UDP packet", e)
        }
    }

    /**
     * Create UDP channel for forwarding
     */
    private fun createUdpChannel(packet: IpPacket): UdpChannel? {
        val cfg = config ?: return null
        
        try {
            val datagramChannel = DatagramChannel.open()
            datagramChannel.configureBlocking(false)
            
            // Protect from VPN
            if (!vpnService.protect(datagramChannel.socket())) {
                Log.e(TAG, "Failed to protect UDP socket")
                datagramChannel.close()
                return null
            }
            
            // Connect to target
            val serverAddress = InetSocketAddress(cfg.serverIp, cfg.serverPort)
            datagramChannel.connect(serverAddress)
            
            return UdpChannel(
                datagramChannel = datagramChannel,
                packet = packet,
                onResponse = { responsePacket ->
                    _incomingPacketFlow.tryEmit(responsePacket)
                }
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create UDP channel", e)
            return null
        }
    }

    /**
     * Handle DNS packet
     */
    private fun handleDnsPacket(packet: IpPacket) {
        // Forward DNS to configured servers
        val cfg = config ?: return
        
        try {
            val dnsChannel = DatagramChannel.open()
            dnsChannel.configureBlocking(false)
            
            if (!vpnService.protect(dnsChannel.socket())) {
                dnsChannel.close()
                return
            }
            
            // Send to first DNS server
            val dnsAddress = InetSocketAddress(cfg.serverIp, 53)
            dnsChannel.send(ByteBuffer.wrap(packet.payload), dnsAddress)
            dnsChannel.close()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling DNS packet", e)
        }
    }

    /**
     * Forward ICMP packet (for ping)
     */
    private fun forwardIcmpPacket(packet: IpPacket) {
        // ICMP forwarding - limited support
        // This would require raw socket access which is not typically available
        Log.d(TAG, "ICMP packet received - limited support")
    }

    // ============================================
    // PROXY LOOP
    // ============================================

    /**
     * Main proxy loop - handles I/O events
     */
    private suspend fun runProxyLoop() {
        val buffer = ByteBuffer.allocateDirect(TCP_BUFFER_SIZE)
        
        while (isActive && isRunning.get()) {
            try {
                // Wait for I/O events
                selector?.select(100) // 100ms timeout
                
                val selectedKeys = selector?.selectedKeys()
                if (selectedKeys == null || selectedKeys.isEmpty()) {
                    continue
                }
                
                val iterator = selectedKeys.iterator()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    iterator.remove()
                    
                    if (!key.isValid) continue
                    
                    when {
                        key.isConnectable -> {
                            handleConnect(key)
                        }
                        key.isReadable -> {
                            handleRead(key, buffer)
                        }
                        key.isWritable -> {
                            handleWrite(key)
                        }
                    }
                }
                
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Error in proxy loop", e)
                }
            }
        }
    }

    /**
     * Handle connection completion
     */
    private fun handleConnect(key: SelectionKey) {
        val connectionKey = key.attachment() as? Int ?: return
        val connection = tcpConnections[connectionKey] ?: return
        
        try {
            val socketChannel = connection.socketChannel
            if (socketChannel.finishConnect()) {
                // Connection established
                key.interestOps(SelectionKey.OP_READ or SelectionKey.OP_WRITE)
                connection.onConnected()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Connection failed", e)
            removeConnection(connectionKey)
        }
    }

    /**
     * Handle read event
     */
    private fun handleRead(key: SelectionKey, buffer: ByteBuffer) {
        val connectionKey = key.attachment() as? Int ?: return
        val connection = tcpConnections[connectionKey] ?: return
        
        try {
            buffer.clear()
            val bytesRead = connection.socketChannel.read(buffer)
            
            if (bytesRead > 0) {
                buffer.flip()
                val data = ByteArray(bytesRead)
                buffer.get(data)
                
                connection.handleIncomingData(data, bytesRead)
                totalBytesReceived += bytesRead
            } else if (bytesRead < 0) {
                // Connection closed by server
                removeConnection(connectionKey)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Read error", e)
            removeConnection(connectionKey)
        }
    }

    /**
     * Handle write event
     */
    private fun handleWrite(key: SelectionKey) {
        val connectionKey = key.attachment() as? Int ?: return
        val connection = tcpConnections[connectionKey] ?: return
        
        try {
            connection.flushOutgoing()
        } catch (e: IOException) {
            Log.e(TAG, "Write error", e)
            removeConnection(connectionKey)
        }
    }

    /**
     * Remove and close connection
     */
    private fun removeConnection(connectionKey: Int) {
        tcpConnections.remove(connectionKey)?.let { connection ->
            connection.close()
            connectionCount--
        }
    }

    /**
     * Close all connections
     */
    private fun closeAllConnections() {
        tcpConnections.values.forEach { it.close() }
        tcpConnections.clear()
        
        udpChannels.values.forEach { it.close() }
        udpChannels.clear()
        
        connectionCount = 0
    }

    // ============================================
    // SPEED TEST
    // ============================================

    /**
     * Start speed test
     */
    fun startSpeedTest(): Boolean {
        if (isSpeedTestRunning.getAndSet(true)) {
            return false
        }
        
        speedTestResults = SpeedTestResults()
        
        speedTestJob = scope.launch {
            try {
                runSpeedTest()
            } catch (e: Exception) {
                Log.e(TAG, "Speed test error", e)
            } finally {
                isSpeedTestRunning.set(false)
            }
        }
        
        return true
    }

    /**
     * Stop speed test
     */
    fun stopSpeedTest(): Boolean {
        speedTestJob?.cancel()
        isSpeedTestRunning.set(false)
        return true
    }

    private suspend fun runSpeedTest() {
        val cfg = config ?: return
        
        // Measure latency
        val latency = measureLatency()
        speedTestResults.latency = latency
        
        // Measure download speed
        measureDownloadSpeed(cfg)
        
        // Measure upload speed
        measureUploadSpeed(cfg)
        
        Log.d(TAG, "Speed test completed: $speedTestResults")
    }

    /**
     * Measure latency to server
     */
    fun measureLatency(): Int {
        val cfg = config ?: return -1
        
        return try {
            val start = System.currentTimeMillis()
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(cfg.serverIp, cfg.serverPort), 3000)
            socket.close()
            (System.currentTimeMillis() - start).toInt()
        } catch (e: Exception) {
            Log.e(TAG, "Latency measurement failed", e)
            -1
        }
    }

    private suspend fun measureDownloadSpeed(cfg: ProxyConfig) {
        // Simulated download speed test
        // In production, this would download from a test server
        speedTestResults.downloadSpeed = 50L * 1024 * 1024 // 50 Mbps placeholder
    }

    private suspend fun measureUploadSpeed(cfg: ProxyConfig) {
        // Simulated upload speed test
        speedTestResults.uploadSpeed = 20L * 1024 * 1024 // 20 Mbps placeholder
    }

    // ============================================
    // INNER CLASSES
    // ============================================

    /**
     * Incoming packet for TUN interface
     */
    data class IncomingPacket(
        val data: ByteArray,
        val length: Int
    )

    /**
     * Speed test results
     */
    data class SpeedTestResults(
        var latency: Int = 0,
        var downloadSpeed: Long = 0L,
        var uploadSpeed: Long = 0L,
        var jitter: Int = 0,
        var packetLoss: Double = 0.0
    )
}
