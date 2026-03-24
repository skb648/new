package com.enterprise.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import com.enterprise.vpn.MainActivity
import com.enterprise.vpn.R
import com.enterprise.vpn.model.*
import com.enterprise.vpn.proxy.ProxyEngine
import com.enterprise.vpn.util.CrashHandler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel
import java.nio.channels.spi.AbstractSelectableChannel
import java.util.concurrent.atomic.AtomicReference

/**
 * Enterprise VPN Service
 * 
 * CRITICAL: This service implements socket protection to avoid routing loops.
 * All outbound sockets MUST be protected using protect() before connecting.
 * 
 * The protect() method exempts a socket from being routed through the VPN tunnel,
 * preventing the classic Android VPN routing loop where the VPN's own traffic
 * gets captured by the tun interface.
 */
class EnterpriseVpnService : VpnService() {

    companion object {
        private const val TAG = "EnterpriseVpnService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "enterprise_vpn_channel"
        private const val CHANNEL_NAME = "VPN Status"

        // Intent actions
        const val ACTION_CONNECT = "com.enterprise.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.enterprise.vpn.DISCONNECT"
        const val ACTION_GET_STATUS = "com.enterprise.vpn.GET_STATUS"

        // Singleton instance for accessing from other components
        private val _instance = AtomicReference<EnterpriseVpnService?>(null)
        val instance: EnterpriseVpnService? get() = _instance.get()

        // State flows for observation
        private val _statusFlow = MutableStateFlow(VpnStatus())
        val statusFlow: StateFlow<VpnStatus> = _statusFlow.asStateFlow()

        private val _eventFlow = MutableStateFlow<VpnEvent?>(null)
        val eventFlow: StateFlow<VpnEvent?> = _eventFlow.asStateFlow()

        private val _trafficFlow = MutableStateFlow(TrafficStats())
        val trafficFlow: StateFlow<TrafficStats> = _trafficFlow.asStateFlow()
    }

    // VPN Interface
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnInputStream: FileInputStream? = null
    private var vpnOutputStream: FileOutputStream? = null

    // Proxy Engine for traffic forwarding
    private var proxyEngine: ProxyEngine? = null

    // Configuration
    private var currentConfig: VpnConfig? = null

    // Coroutine scope for async operations
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // State tracking
    private var isRunning = false
    private var connectJob: Job? = null

    // Traffic statistics
    private var totalBytesIn: Long = 0
    private var totalBytesOut: Long = 0
    private var lastTrafficUpdate: Long = 0
    private var lastBytesIn: Long = 0
    private var lastBytesOut: Long = 0

    override fun onCreate() {
        super.onCreate()
        _instance.set(this)
        CrashHandler.init(this)
        createNotificationChannel()
        Log.i(TAG, "VPN Service created")
    }

    override fun onDestroy() {
        Log.i(TAG, "VPN Service destroying")
        cleanup()
        _instance.set(null)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_CONNECT -> {
                val configJson = intent.getStringExtra("config")
                if (configJson != null) {
                    val config = VpnConfig.fromJson(configJson)
                    connect(config)
                } else {
                    sendError("No configuration provided")
                }
            }
            ACTION_DISCONNECT -> {
                disconnect()
            }
            ACTION_GET_STATUS -> {
                // Status is available via flow
            }
        }

        return START_STICKY
    }

    /**
     * Connect to VPN with the given configuration
     * 
     * This method handles all connection errors and sends detailed error
     * messages back to Flutter via the event flow.
     */
    fun connect(config: VpnConfig) {
        if (!config.isValid) {
            sendError("Invalid configuration: server address or port missing")
            return
        }

        currentConfig = config
        connectJob?.cancel()
        connectJob = serviceScope.launch {
            try {
                performConnection(config)
            } catch (e: CancellationException) {
                Log.i(TAG, "Connection cancelled")
                updateState(VpnConnectionState.DISCONNECTED)
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                handleConnectionError(e)
            }
        }
    }

    /**
     * Perform the actual VPN connection
     */
    private suspend fun performConnection(config: VpnConfig) {
        updateState(VpnConnectionState.CONNECTING)
        sendEvent(VpnEventType.CONNECTING, "Connecting to ${config.server?.serverIp}:${config.server?.port}")

        // Check network availability
        if (!isNetworkAvailable()) {
            throw VpnConnectionException("No network connection available", "NO_NETWORK")
        }

        // Test connection to server BEFORE establishing VPN
        // This is critical to ensure we can reach the server
        Log.i(TAG, "Testing server connection...")
        val serverReachable = testServerConnection(config)
        if (!serverReachable) {
            throw VpnConnectionException(
                "Cannot reach server at ${config.server?.serverIp}:${config.server?.port}. " +
                "Please check the server address and port.",
                "SERVER_UNREACHABLE"
            )
        }
        Log.i(TAG, "Server connection test passed")

        // Establish VPN interface
        Log.i(TAG, "Establishing VPN interface...")
        vpnInterface = establishVpn(config)
        
        if (vpnInterface == null) {
            throw VpnConnectionException("Failed to create VPN interface", "VPN_INTERFACE_ERROR")
        }

        vpnInputStream = FileInputStream(vpnInterface!!.fileDescriptor)
        vpnOutputStream = FileOutputStream(vpnInterface!!.fileDescriptor)

        // Start proxy engine
        Log.i(TAG, "Starting proxy engine...")
        proxyEngine = ProxyEngine(this, config)
        proxyEngine?.start()

        isRunning = true
        updateState(VpnConnectionState.CONNECTED)
        sendEvent(VpnEventType.CONNECTED, "Connected to ${config.server?.name ?: config.server?.serverIp}")

        // Start foreground service
        startForeground(NOTIFICATION_ID, createNotification("Connected to ${config.server?.name ?: "VPN"}"))

        // Start traffic monitoring
        startTrafficMonitoring()
    }

    /**
     * Test server connection with a PROTECTED socket
     * 
     * CRITICAL: We protect the socket before connecting to ensure
     * the test traffic doesn't get routed through the VPN.
     */
    private suspend fun testServerConnection(config: VpnConfig): Boolean = withContext(Dispatchers.IO) {
        val server = config.server ?: return@withContext false
        
        // Create socket based on protocol
        when (server.protocol.uppercase()) {
            "TCP" -> testTcpConnection(server.serverIp, server.port)
            "UDP" -> testUdpConnection(server.serverIp, server.port)
            else -> testTcpConnection(server.serverIp, server.port)
        }
    }

    /**
     * Test TCP connection with protected socket
     * 
     * @return true if connection successful, false otherwise
     */
    private fun testTcpConnection(serverIp: String, port: Int): Boolean {
        var socket: Socket? = null
        try {
            // Create socket
            socket = Socket()
            
            // CRITICAL: Protect the socket BEFORE connecting
            // This exempts the socket from VPN routing
            if (!protect(socket)) {
                Log.e(TAG, "Failed to protect TCP test socket")
                return false
            }
            Log.d(TAG, "TCP test socket protected successfully")

            // Set socket options
            socket.soTimeout = 10000 // 10 second timeout
            socket.tcpNoDelay = true
            socket.keepAlive = true

            // Connect to server
            val address = InetSocketAddress(serverIp, port)
            Log.d(TAG, "Attempting TCP connection to $serverIp:$port")
            socket.connect(address, 10000)

            Log.i(TAG, "TCP connection test successful: $serverIp:$port")
            return true

        } catch (e: UnknownHostException) {
            Log.e(TAG, "TCP test failed - Unknown host: $serverIp", e)
            return false
        } catch (e: ConnectException) {
            Log.e(TAG, "TCP test failed - Connection refused: $serverIp:$port", e)
            return false
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "TCP test failed - Connection timeout: $serverIp:$port", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "TCP test failed - ${e.javaClass.simpleName}: ${e.message}", e)
            return false
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing test socket", e)
            }
        }
    }

    /**
     * Test UDP connection with protected socket
     */
    private fun testUdpConnection(serverIp: String, port: Int): Boolean {
        var channel: DatagramChannel? = null
        try {
            channel = DatagramChannel.open()
            
            // CRITICAL: Protect the channel BEFORE connecting
            if (!protect(channel.socket())) {
                Log.e(TAG, "Failed to protect UDP test socket")
                return false
            }
            Log.d(TAG, "UDP test socket protected successfully")

            // Connect to server
            val address = InetSocketAddress(serverIp, port)
            channel.connect(address)

            // Send a small probe packet
            val buffer = ByteBuffer.wrap(byteArrayOf(0))
            channel.send(buffer, address)

            Log.i(TAG, "UDP connection test successful: $serverIp:$port")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "UDP test failed - ${e.javaClass.simpleName}: ${e.message}", e)
            return false
        } finally {
            try {
                channel?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing UDP test channel", e)
            }
        }
    }

    /**
     * Create a protected TCP socket channel for the proxy engine
     * 
     * This method creates and protects a TCP socket before connecting.
     * Call this from ProxyEngine to create outbound connections.
     * 
     * @param serverIp The server IP address
     * @param port The server port
     * @param timeoutMs Connection timeout in milliseconds
     * @return Connected SocketChannel or null if failed
     */
    fun createProtectedTcpChannel(
        serverIp: String, 
        port: Int, 
        timeoutMs: Int = 30000
    ): SocketChannel? {
        return try {
            val channel = SocketChannel.open()
            channel.configureBlocking(true)
            
            // CRITICAL: Protect BEFORE connecting
            if (!protect(channel.socket())) {
                Log.e(TAG, "Failed to protect TCP channel for $serverIp:$port")
                channel.close()
                return null
            }
            Log.d(TAG, "TCP channel protected: $serverIp:$port")

            // Set socket options
            channel.socket().soTimeout = timeoutMs
            channel.socket().tcpNoDelay = true
            channel.socket().keepAlive = true

            // Connect
            val address = InetSocketAddress(serverIp, port)
            channel.socket().connect(address, timeoutMs)

            if (channel.isConnected) {
                Log.i(TAG, "Protected TCP channel connected: $serverIp:$port")
                channel
            } else {
                Log.e(TAG, "Protected TCP channel not connected: $serverIp:$port")
                channel.close()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create protected TCP channel: ${e.message}", e)
            null
        }
    }

    /**
     * Create a protected TCP socket for the proxy engine
     * 
     * @param serverIp The server IP address
     * @param port The server port
     * @param timeoutMs Connection timeout in milliseconds
     * @return Connected Socket or null if failed
     */
    fun createProtectedTcpSocket(
        serverIp: String,
        port: Int,
        timeoutMs: Int = 30000
    ): Socket? {
        return try {
            val socket = Socket()
            
            // CRITICAL: Protect BEFORE connecting
            if (!protect(socket)) {
                Log.e(TAG, "Failed to protect TCP socket for $serverIp:$port")
                socket.close()
                return null
            }
            Log.d(TAG, "TCP socket protected: $serverIp:$port")

            // Set socket options
            socket.soTimeout = timeoutMs
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.receiveBufferSize = 65536
            socket.sendBufferSize = 65536

            // Connect
            val address = InetSocketAddress(serverIp, port)
            socket.connect(address, timeoutMs)

            if (socket.isConnected) {
                Log.i(TAG, "Protected TCP socket connected: $serverIp:$port")
                socket
            } else {
                Log.e(TAG, "Protected TCP socket not connected: $serverIp:$port")
                socket.close()
                null
            }
        } catch (e: UnknownHostException) {
            Log.e(TAG, "Unknown host: $serverIp", e)
            null
        } catch (e: ConnectException) {
            Log.e(TAG, "Connection refused: $serverIp:$port", e)
            null
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Connection timeout: $serverIp:$port", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create protected TCP socket: ${e.message}", e)
            null
        }
    }

    /**
     * Create a protected UDP channel for the proxy engine
     */
    fun createProtectedUdpChannel(serverIp: String, port: Int): DatagramChannel? {
        return try {
            val channel = DatagramChannel.open()
            
            // CRITICAL: Protect BEFORE connecting
            if (!protect(channel.socket())) {
                Log.e(TAG, "Failed to protect UDP channel for $serverIp:$port")
                channel.close()
                return null
            }
            Log.d(TAG, "UDP channel protected: $serverIp:$port")

            // Connect
            val address = InetSocketAddress(serverIp, port)
            channel.connect(address)

            Log.i(TAG, "Protected UDP channel created: $serverIp:$port")
            channel
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create protected UDP channel: ${e.message}", e)
            null
        }
    }

    /**
     * Establish the VPN interface
     */
    private fun establishVpn(config: VpnConfig): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("Enterprise VPN")
                .setMtu(config.mtu)

            // Add addresses
            builder.addAddress("10.0.0.2", 32)
            builder.addAddress("fd00::2", 128)

            // Add routes (route all traffic through VPN)
            builder.addRoute("0.0.0.0", 0)
            if (config.ipv6Enabled) {
                builder.addRoute("::", 0)
            }

            // Add DNS servers
            if (config.customDns.isNotEmpty()) {
                config.customDns.forEach { dns ->
                    try {
                        builder.addDnsServer(dns)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to add DNS server: $dns", e)
                    }
                }
            } else {
                builder.addDnsServer("8.8.8.8")
                builder.addDnsServer("8.8.4.4")
                builder.addDnsServer("1.1.1.1")
            }

            // Handle split tunneling
            if (config.splitTunnelEnabled && config.excludedApps.isNotEmpty()) {
                config.excludedApps.forEach { packageName ->
                    try {
                        builder.addAllowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to exclude app: $packageName", e)
                    }
                }
            }

            // Establish the interface
            val interfaceFd = builder.establish()
            Log.i(TAG, "VPN interface established: mtu=${config.mtu}")
            interfaceFd

        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN interface", e)
            null
        }
    }

    /**
     * Disconnect from VPN
     */
    fun disconnect() {
        Log.i(TAG, "Disconnecting VPN")
        
        connectJob?.cancel()
        connectJob = null

        cleanup()
        
        updateState(VpnConnectionState.DISCONNECTED)
        sendEvent(VpnEventType.DISCONNECTED, "Disconnected")
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Cleanup resources
     */
    private fun cleanup() {
        isRunning = false

        // Stop proxy engine
        proxyEngine?.stop()
        proxyEngine = null

        // Close VPN interface
        try {
            vpnInputStream?.close()
            vpnInputStream = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing VPN input stream", e)
        }

        try {
            vpnOutputStream?.close()
            vpnOutputStream = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing VPN output stream", e)
        }

        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing VPN interface", e)
        }

        // Reset traffic stats
        totalBytesIn = 0
        totalBytesOut = 0
    }

    /**
     * Handle connection errors with detailed error messages
     */
    private fun handleConnectionError(e: Throwable) {
        val errorMessage = when (e) {
            is VpnConnectionException -> e.message ?: "Connection failed"
            is UnknownHostException -> "Cannot resolve server address: ${e.message}"
            is ConnectException -> "Connection refused by server: ${e.message}"
            is SocketTimeoutException -> "Connection timed out: ${e.message}"
            is SecurityException -> "VPN permission denied: ${e.message}"
            else -> "Connection error: ${e.javaClass.simpleName} - ${e.message}"
        }

        Log.e(TAG, "Connection error: $errorMessage", e)
        sendError(errorMessage)
        updateState(VpnConnectionState.ERROR, errorMessage)
    }

    /**
     * Update VPN state
     */
    private fun updateState(state: VpnConnectionState, errorMessage: String? = null) {
        val currentStatus = _statusFlow.value
        val newStatus = currentStatus.copy(
            state = state,
            errorMessage = errorMessage,
            serverName = currentConfig?.server?.name,
            serverIp = currentConfig?.server?.serverIp,
            serverPort = currentConfig?.server?.port,
            protocol = currentConfig?.server?.protocol,
            connectedAt = if (state == VpnConnectionState.CONNECTED && currentStatus.connectedAt == null) {
                System.currentTimeMillis()
            } else currentStatus.connectedAt
        )
        _statusFlow.value = newStatus

        // Broadcast to Flutter
        broadcastStatus(newStatus)
    }

    /**
     * Send event to Flutter
     */
    private fun sendEvent(type: VpnEventType, message: String, data: Map<String, Any?>? = null) {
        val event = VpnEvent(type, message, System.currentTimeMillis(), data)
        _eventFlow.value = event

        // Broadcast to Flutter via intent
        val intent = Intent("com.enterprise.vpn.EVENT")
        intent.putExtra("type", type.name)
        intent.putExtra("message", message)
        sendBroadcast(intent)
    }

    /**
     * Send error event
     */
    private fun sendError(message: String) {
        sendEvent(VpnEventType.ERROR, message)
    }

    /**
     * Broadcast status to Flutter
     */
    private fun broadcastStatus(status: VpnStatus) {
        val intent = Intent("com.enterprise.vpn.STATUS")
        intent.putExtra("status", status.toJson())
        sendBroadcast(intent)
    }

    /**
     * Update traffic statistics
     */
    fun updateTraffic(bytesIn: Long, bytesOut: Long) {
        totalBytesIn += bytesIn
        totalBytesOut += bytesOut

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastTrafficUpdate >= 1000) {
            val timeDiff = (currentTime - lastTrafficUpdate) / 1000.0
            val speedDown = ((totalBytesIn - lastBytesIn) / timeDiff).toLong()
            val speedUp = ((totalBytesOut - lastBytesOut) / timeDiff).toLong()

            val stats = TrafficStats(
                timestamp = currentTime,
                bytesIn = totalBytesIn,
                bytesOut = totalBytesOut,
                speedDown = speedDown,
                speedUp = speedUp
            )
            _trafficFlow.value = stats

            // Update status with traffic info
            val currentStatus = _statusFlow.value
            _statusFlow.value = currentStatus.copy(
                bytesIn = totalBytesIn,
                bytesOut = totalBytesOut,
                currentSpeedDown = speedDown,
                currentSpeedUp = speedUp
            )

            lastBytesIn = totalBytesIn
            lastBytesOut = totalBytesOut
            lastTrafficUpdate = currentTime
        }
    }

    /**
     * Start traffic monitoring
     */
    private fun startTrafficMonitoring() {
        lastTrafficUpdate = System.currentTimeMillis()
        lastBytesIn = 0
        lastBytesOut = 0
    }

    /**
     * Check if network is available
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Create notification channel
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN connection status"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Create notification
     */
    private fun createNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Enterprise VPN")
                .setContentText(contentText)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Enterprise VPN")
                .setContentText(contentText)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    /**
     * Get VPN input stream for reading packets
     */
    fun getVpnInputStream(): FileInputStream? = vpnInputStream

    /**
     * Get VPN output stream for writing packets
     */
    fun getVpnOutputStream(): FileOutputStream? = vpnOutputStream

    /**
     * Check if VPN is running
     */
    fun isVpnRunning(): Boolean = isRunning

    /**
     * Get current configuration
     */
    fun getCurrentConfig(): VpnConfig? = currentConfig
}

/**
 * Custom exception for VPN connection errors
 */
class VpnConnectionException(
    message: String,
    val errorCode: String? = null
) : Exception(message)
