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
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicReference

/**
 * Enterprise VPN Service
 *
 * IMPORTANT: This app is excluded from VPN routing via addDisallowedApplication(),
 * so all sockets created by this app automatically bypass the VPN tunnel.
 * No manual protect() calls are needed.
 *
 * IMPORTANT: All network operations run on background threads (Dispatchers.IO)
 * to prevent ANR (Application Not Responding) errors.
 */
class EnterpriseVpnService : VpnService() {

    companion object {
        private const val TAG = "EnterpriseVpnService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "enterprise_vpn_channel"

        const val ACTION_CONNECT = "com.enterprise.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.enterprise.vpn.DISCONNECT"
        const val ACTION_GET_STATUS = "com.enterprise.vpn.GET_STATUS"

        private val _instance = AtomicReference<EnterpriseVpnService?>(null)
        val instance: EnterpriseVpnService? get() = _instance.get()

        private val _statusFlow = MutableStateFlow(VpnStatus())
        val statusFlow: StateFlow<VpnStatus> = _statusFlow.asStateFlow()

        private val _eventFlow = MutableStateFlow<VpnEvent?>(null)
        val eventFlow: StateFlow<VpnEvent?> = _eventFlow.asStateFlow()

        private val _trafficFlow = MutableStateFlow(TrafficStats())
        val trafficFlow: StateFlow<TrafficStats> = _trafficFlow.asStateFlow()

        /**
         * Update event from external classes (like ProxyEngine)
         * This allows error events to be sent to Flutter
         */
        fun updateEvent(event: VpnEvent) {
            _eventFlow.value = event
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnInputStream: FileInputStream? = null
    private var vpnOutputStream: FileOutputStream? = null
    private var proxyEngine: ProxyEngine? = null
    private var currentConfig: VpnConfig? = null

    // Use Dispatchers.IO for all network operations to prevent ANR
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var connectJob: Job? = null

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
                Log.d(TAG, "Received config JSON from intent: $configJson")
                
                if (configJson != null) {
                    val config = VpnConfig.fromJson(configJson)
                    Log.d(TAG, "Parsed config from JSON:")
                    Log.d(TAG, "  server=${config.server}")
                    Log.d(TAG, "  server.serverIp=${config.server?.serverIp}")
                    Log.d(TAG, "  server.port=${config.server?.port}")
                    Log.d(TAG, "  server.protocol=${config.server?.protocol}")
                    Log.d(TAG, "  config.isValid=${config.isValid()}")
                    connect(config)
                } else {
                    Log.e(TAG, "No configuration provided in intent!")
                    sendError("No configuration provided")
                }
            }
            ACTION_DISCONNECT -> {
                disconnect()
            }
        }

        return START_STICKY
    }

    fun connect(config: VpnConfig) {
        Log.d(TAG, "======== EnterpriseVpnService.connect() ========")
        Log.d(TAG, "config.server: ${config.server}")
        Log.d(TAG, "config.server?.serverIp: ${config.server?.serverIp}")
        Log.d(TAG, "config.server?.port: ${config.server?.port}")
        Log.d(TAG, "config.isValid(): ${config.isValid()}")
        
        if (!config.isValid()) {
            val errorMsg = "Invalid configuration: server=${config.server}, serverIp=${config.server?.serverIp}, port=${config.server?.port}"
            Log.e(TAG, errorMsg)
            sendError(errorMsg)
            return
        }

        currentConfig = config
        connectJob?.cancel()
        
        // CRITICAL: Launch connection in background thread (Dispatchers.IO) to prevent ANR
        connectJob = serviceScope.launch {
            try {
                performConnection(config)
            } catch (e: CancellationException) {
                Log.i(TAG, "Connection cancelled")
                withContext(Dispatchers.Main) {
                    updateState(VpnConnectionState.DISCONNECTED)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                withContext(Dispatchers.Main) {
                    handleConnectionError(e)
                }
            }
        }
    }

    /**
     * Perform the VPN connection on a background thread.
     * This method runs on Dispatchers.IO to prevent ANR.
     */
    private suspend fun performConnection(config: VpnConfig) {
        // Update state to CONNECTING on main thread
        withContext(Dispatchers.Main) {
            updateState(VpnConnectionState.CONNECTING)
            sendEvent(VpnEventType.CONNECTING, "Connecting to ${config.server?.serverIp}:${config.server?.port}")
        }

        // Check network availability (runs on IO dispatcher)
        if (!isNetworkAvailable()) {
            throw VpnConnectionException("No network connection available", "NO_NETWORK")
        }

        // Test connection to server BEFORE establishing VPN (runs on IO dispatcher)
        Log.i(TAG, "Testing server connection...")
        val serverReachable = testServerConnection(config)
        if (!serverReachable) {
            throw VpnConnectionException(
                "Cannot reach server at ${config.server?.serverIp}:${config.server?.port}",
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
        
        // Update state to CONNECTED on main thread
        withContext(Dispatchers.Main) {
            updateState(VpnConnectionState.CONNECTED)
            sendEvent(VpnEventType.CONNECTED, "Connected to ${config.server?.name ?: config.server?.serverIp}")
        }

        startForeground(NOTIFICATION_ID, createNotification("Connected to ${config.server?.name ?: "VPN"}"))
        startTrafficMonitoring()
    }

    /**
     * Test server connection - runs entirely on background thread
     */
    private suspend fun testServerConnection(config: VpnConfig): Boolean {
        val server = config.server ?: return false
        
        // Already running on Dispatchers.IO from serviceScope, no need for withContext
        return when {
            server.protocol.equals("TCP", ignoreCase = true) -> testTcpConnection(server.serverIp, server.port)
            server.protocol.equals("UDP", ignoreCase = true) -> testUdpConnection(server.serverIp, server.port)
            else -> testTcpConnection(server.serverIp, server.port)
        }
    }

    /**
     * Test TCP connection.
     * Note: App is already excluded from VPN via addDisallowedApplication(),
     * so sockets automatically bypass the VPN tunnel.
     */
    private fun testTcpConnection(serverIp: String, port: Int): Boolean {
        val socket = Socket()
        try {
            Log.d(TAG, "======== testTcpConnection START ========")
            Log.d(TAG, "Target: $serverIp:$port")

            // Set socket options
            Log.d(TAG, "Setting socket options...")
            socket.soTimeout = 10000  // 10 second read timeout
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.receiveBufferSize = 65536
            socket.sendBufferSize = 65536

            // Connect with timeout
            Log.d(TAG, "Connecting to $serverIp:$port with 10s timeout...")
            val address = InetSocketAddress(serverIp, port)
            socket.connect(address, 10000)  // 10 second connection timeout

            Log.i(TAG, "TCP connection established to $serverIp:$port")
            Log.i(TAG, "======== testTcpConnection SUCCESS ========")
            return true

        } catch (e: UnknownHostException) {
            val errorMsg = "[DNS_ERROR] Cannot resolve host '$serverIp' - ${e.message}"
            Log.e(TAG, errorMsg, e)
            sendDetailedError(errorMsg, "DNS_ERROR")
            return false
        } catch (e: ConnectException) {
            val errorMsg = "[CONNECTION_REFUSED] Server at $serverIp:$port refused connection - ${e.message}. " +
                "Is the server running and listening on this port?"
            Log.e(TAG, errorMsg, e)
            sendDetailedError(errorMsg, "CONNECTION_REFUSED")
            return false
        } catch (e: SocketTimeoutException) {
            val errorMsg = "[CONNECTION_TIMEOUT] Connection to $serverIp:$port timed out after 10 seconds - ${e.message}. " +
                "Server may be offline or firewall blocking."
            Log.e(TAG, errorMsg, e)
            sendDetailedError(errorMsg, "CONNECTION_TIMEOUT")
            return false
        } catch (e: java.net.NoRouteToHostException) {
            val errorMsg = "[NO_ROUTE] No route to host $serverIp - Network unreachable: ${e.message}"
            Log.e(TAG, errorMsg, e)
            sendDetailedError(errorMsg, "NO_ROUTE")
            return false
        } catch (e: java.nio.channels.UnresolvedAddressException) {
            val errorMsg = "[UNRESOLVED_ADDRESS] Cannot resolve address '$serverIp' - ${e.message}"
            Log.e(TAG, errorMsg, e)
            sendDetailedError(errorMsg, "UNRESOLVED_ADDRESS")
            return false
        } catch (e: SecurityException) {
            val errorMsg = "[PERMISSION_DENIED] Network permission denied - ${e.message}"
            Log.e(TAG, errorMsg, e)
            sendDetailedError(errorMsg, "PERMISSION_DENIED")
            return false
        } catch (e: IOException) {
            val errorMsg = "[IO_ERROR] Network error connecting to $serverIp:$port - ${e.message}"
            Log.e(TAG, errorMsg, e)
            sendDetailedError(errorMsg, "IO_ERROR")
            return false
        } catch (e: Exception) {
            val errorMsg = "[UNKNOWN_ERROR] ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, errorMsg, e)
            sendDetailedError(errorMsg, "UNKNOWN_ERROR")
            return false
        } finally {
            try {
                socket.close()
                Log.d(TAG, "Test socket closed")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing test socket: ${e.message}")
            }
        }
    }

    /**
     * Send detailed error to Flutter with full exception info.
     * This method ensures the error is sent on the main thread.
     */
    private fun sendDetailedError(message: String, code: String) {
        try {
            val event = VpnEvent(
                type = VpnEventType.ERROR,
                message = "[$code] $message",
                data = mapOf(
                    "errorCode" to code,
                    "fullMessage" to message,
                    "timestamp" to System.currentTimeMillis()
                )
            )
            _eventFlow.value = event
            Log.e(TAG, "🔥 ERROR SENT TO FLUTTER: [$code] $message")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send error to Flutter: ${e.message}")
        }
    }

    /**
     * Test UDP connection.
     * Note: App is already excluded from VPN via addDisallowedApplication(),
     * so sockets automatically bypass the VPN tunnel.
     */
    private fun testUdpConnection(serverIp: String, port: Int): Boolean {
        var channel: DatagramChannel? = null
        try {
            Log.d(TAG, "======== testUdpConnection START ========")
            Log.d(TAG, "Target: $serverIp:$port")

            channel = DatagramChannel.open()

            val address = InetSocketAddress(serverIp, port)
            channel.connect(address)

            // Send a test packet
            val buffer = ByteBuffer.wrap(byteArrayOf(0))
            channel.send(buffer, address)

            Log.i(TAG, "UDP connection test successful: $serverIp:$port")
            Log.i(TAG, "======== testUdpConnection SUCCESS ========")
            return true

        } catch (e: UnknownHostException) {
            val errorMsg = "[DNS_ERROR] Cannot resolve host '$serverIp' - ${e.message}"
            Log.e(TAG, errorMsg, e)
            sendDetailedError(errorMsg, "DNS_ERROR")
            return false
        } catch (e: java.net.NoRouteToHostException) {
            val errorMsg = "[NO_ROUTE] No route to host $serverIp - ${e.message}"
            Log.e(TAG, errorMsg, e)
            sendDetailedError(errorMsg, "NO_ROUTE")
            return false
        } catch (e: java.nio.channels.UnresolvedAddressException) {
            val errorMsg = "[UNRESOLVED_ADDRESS] Cannot resolve address '$serverIp' - ${e.message}"
            Log.e(TAG, errorMsg, e)
            sendDetailedError(errorMsg, "UNRESOLVED_ADDRESS")
            return false
        } catch (e: SecurityException) {
            val errorMsg = "[PERMISSION_DENIED] Network permission denied - ${e.message}"
            Log.e(TAG, errorMsg, e)
            sendDetailedError(errorMsg, "PERMISSION_DENIED")
            return false
        } catch (e: IOException) {
            val errorMsg = "[IO_ERROR] Network error connecting to $serverIp:$port - ${e.message}"
            Log.e(TAG, errorMsg, e)
            sendDetailedError(errorMsg, "IO_ERROR")
            return false
        } catch (e: Exception) {
            val errorMsg = "[UNKNOWN_ERROR] ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, errorMsg, e)
            sendDetailedError(errorMsg, "UNKNOWN_ERROR")
            return false
        } finally {
            try { channel?.close() } catch (e: Exception) { }
        }
    }

    /**
     * Create a TCP channel for the proxy engine.
     * Note: App is already excluded from VPN via addDisallowedApplication(),
     * so sockets automatically bypass the VPN tunnel.
     */
    fun createProtectedTcpChannel(serverIp: String, port: Int, timeoutMs: Int = 30000): SocketChannel? {
        return try {
            Log.d(TAG, "======== createProtectedTcpChannel START ========")
            Log.d(TAG, "Target: $serverIp:$port, timeout: ${timeoutMs}ms")

            val channel = SocketChannel.open()
            channel.configureBlocking(true)

            // Set socket options
            Log.d(TAG, "Setting socket options...")
            val socket = channel.socket()
            socket.soTimeout = timeoutMs
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.receiveBufferSize = 65536
            socket.sendBufferSize = 65536

            // Connect with timeout
            Log.d(TAG, "Connecting to $serverIp:$port with ${timeoutMs}ms timeout...")
            val address = InetSocketAddress(serverIp, port)
            channel.socket().connect(address, timeoutMs)

            if (channel.isConnected) {
                Log.i(TAG, "TCP channel connected: $serverIp:$port")
                Log.i(TAG, "======== createProtectedTcpChannel SUCCESS ========")
                channel
            } else {
                val errorMsg = "[CONNECTION_FAILED] TCP channel not connected after connect() call"
                Log.e(TAG, errorMsg)
                sendDetailedError(errorMsg, "CONNECTION_FAILED")
                channel.close()
                null
            }
        } catch (e: Exception) {
            val errorMsg = "[${getErrorCode(e)}] Failed to create TCP channel: ${e.message}"
            Log.e(TAG, errorMsg, e)
            sendDetailedError(errorMsg, getErrorCode(e))
            null
        }
    }

    /**
     * Create a UDP channel for the proxy engine.
     * Note: App is already excluded from VPN via addDisallowedApplication(),
     * so sockets automatically bypass the VPN tunnel.
     */
    fun createProtectedUdpChannel(serverIp: String, port: Int): DatagramChannel? {
        return try {
            Log.d(TAG, "======== createProtectedUdpChannel START ========")
            Log.d(TAG, "Target: $serverIp:$port")

            val channel = DatagramChannel.open()

            val address = InetSocketAddress(serverIp, port)
            channel.connect(address)

            Log.i(TAG, "UDP channel created: $serverIp:$port")
            Log.i(TAG, "======== createProtectedUdpChannel SUCCESS ========")
            channel
        } catch (e: Exception) {
            val errorMsg = "[${getErrorCode(e)}] Failed to create UDP channel: ${e.message}"
            Log.e(TAG, errorMsg, e)
            sendDetailedError(errorMsg, getErrorCode(e))
            null
        }
    }

    /**
     * Get error code from exception type
     */
    private fun getErrorCode(e: Exception): String {
        return when (e) {
            is UnknownHostException -> "DNS_ERROR"
            is ConnectException -> "CONNECTION_REFUSED"
            is SocketTimeoutException -> "CONNECTION_TIMEOUT"
            is java.net.NoRouteToHostException -> "NO_ROUTE"
            is java.nio.channels.UnresolvedAddressException -> "UNRESOLVED_ADDRESS"
            is SecurityException -> "PERMISSION_DENIED"
            is IOException -> "IO_ERROR"
            else -> "UNKNOWN_ERROR"
        }
    }

    private fun establishVpn(config: VpnConfig): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("Enterprise VPN")
                .setMtu(config.mtu)

            builder.addAddress("10.0.0.2", 32)
            builder.addAddress("fd00::2", 128)

            builder.addRoute("0.0.0.0", 0)
            if (config.ipv6Enabled) {
                builder.addRoute("::", 0)
            }

            if (config.customDns.isNotEmpty()) {
                config.customDns.forEach { dns ->
                    try { builder.addDnsServer(dns) } catch (e: Exception) { }
                }
            } else {
                builder.addDnsServer("8.8.8.8")
                builder.addDnsServer("8.8.4.4")
                builder.addDnsServer("1.1.1.1")
            }

            // =====================================================
            // VPN ROUTING LOOP PREVENTION
            // =====================================================
            // By disallowing our own app from the VPN, all sockets created by
            // this app will bypass the tun0 interface and use the original network.
            // This eliminates the need for manual protect() calls.
            // =====================================================
            try {
                builder.addDisallowedApplication(this.packageName)
                Log.i(TAG, "Added self to disallowed applications - sockets bypass VPN tunnel automatically")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add disallowed application (non-critical on some devices)", e)
            }

            // Split tunnel: allow only specific apps through VPN
            if (config.splitTunnelEnabled && config.excludedApps.isNotEmpty()) {
                Log.i(TAG, "Split tunnel enabled with ${config.excludedApps.size} excluded apps")
            }

            val interfaceFd = builder.establish()
            Log.i(TAG, "VPN interface established: mtu=${config.mtu}")
            interfaceFd

        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN interface", e)
            null
        }
    }

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

    private fun cleanup() {
        isRunning = false

        proxyEngine?.stop()
        proxyEngine = null

        try { vpnInputStream?.close() } catch (e: Exception) { }
        vpnInputStream = null

        try { vpnOutputStream?.close() } catch (e: Exception) { }
        vpnOutputStream = null

        try { vpnInterface?.close() } catch (e: Exception) { }
        vpnInterface = null

        totalBytesIn = 0
        totalBytesOut = 0
    }

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
        broadcastStatus(newStatus)
    }

    private fun sendEvent(type: VpnEventType, message: String, data: Map<String, Any?>? = null) {
        val event = VpnEvent(type, message, System.currentTimeMillis(), data)
        _eventFlow.value = event

        val intent = Intent("com.enterprise.vpn.EVENT")
        intent.putExtra("type", type.name)
        intent.putExtra("message", message)
        sendBroadcast(intent)
    }

    private fun sendError(message: String) {
        sendEvent(VpnEventType.ERROR, message)
    }

    private fun broadcastStatus(status: VpnStatus) {
        val intent = Intent("com.enterprise.vpn.STATUS")
        intent.putExtra("status", status.toMap().toString())
        sendBroadcast(intent)
    }

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

    private fun startTrafficMonitoring() {
        lastTrafficUpdate = System.currentTimeMillis()
        lastBytesIn = 0
        lastBytesOut = 0
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Status",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
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

    fun getVpnInputStream(): FileInputStream? = vpnInputStream
    fun getVpnOutputStream(): FileOutputStream? = vpnOutputStream
    fun isVpnRunning(): Boolean = isRunning
    fun getCurrentConfig(): VpnConfig? = currentConfig
}
