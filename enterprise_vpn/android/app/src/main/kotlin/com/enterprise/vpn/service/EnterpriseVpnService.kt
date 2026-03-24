package com.enterprise.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import com.enterprise.vpn.HttpHeaderConfig
import com.enterprise.vpn.R
import com.enterprise.vpn.SniConfig
import com.enterprise.vpn.VpnConnectionState
import com.enterprise.vpn.VpnServiceManager
import com.enterprise.vpn.proxy.ProxyEngine
import com.enterprise.vpn.proxy.ProxyConfig
import com.enterprise.vpn.tunnel.TunnelManager
import com.enterprise.vpn.tunnel.TunnelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect

/**
 * Enterprise VPN Service
 * 
 * Core VPN service implementing VpnService for Android 14+.
 * Creates and manages the TUN interface (tun0) with proper foreground service handling.
 * 
 * Features:
 * - VpnService.Builder for TUN interface creation
 * - Android 14+ Foreground Service compliance (FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
 * - HTTP Header injection via ProxyEngine
 * - SNI configuration for TLS connections
 * - Traffic statistics tracking
 * - Kill switch functionality
 * - Split tunneling support
 */
class EnterpriseVpnService : VpnService() {

    companion object {
        private const val TAG = "EnterpriseVpnService"
        
        // Intent Actions
        const val ACTION_CONNECT = "com.enterprise.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.enterprise.vpn.DISCONNECT"
        const val ACTION_RECONNECT = "com.enterprise.vpn.RECONNECT"
        const val ACTION_PAUSE = "com.enterprise.vpn.PAUSE"
        const val ACTION_RESUME = "com.enterprise.vpn.RESUME"
        
        // Intent Extras
        const val EXTRA_CONFIG = "config"
        const val EXTRA_SERVER_IP = "server_ip"
        const val EXTRA_SERVER_PORT = "server_port"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_SNI = "sni"
        const val EXTRA_CUSTOM_PAYLOAD = "custom_payload"
        
        // Notification
        private const val NOTIFICATION_CHANNEL_ID = "enterprise_vpn_channel"
        private const val NOTIFICATION_CHANNEL_NAME = "VPN Status"
        private const val NOTIFICATION_ID = 1001
        
        // VPN Configuration Defaults
        private const val DEFAULT_MTU = 1500
        private const val DEFAULT_ADDRESS = "10.8.0.2"
        private const val DEFAULT_ADDRESS_PREFIX = 24
        private const val DEFAULT_DNS_1 = "8.8.8.8"
        private const val DEFAULT_DNS_2 = "8.8.4.4"
        private const val DEFAULT_DNS_3 = "1.1.1.1"
        
        // Traffic Stats Update Interval
        private const val STATS_UPDATE_INTERVAL_MS = 1000L
        private const val PING_INTERVAL_MS = 10000L
        
        // Buffer sizes
        private const val TUN_READ_BUFFER_SIZE = 32767
        private const val SOCKET_BUFFER_SIZE = 65536
    }

    // ============================================
    // STATE
    // ============================================

    // VPN Interface
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnInputStream: FileInputStream? = null
    private var vpnOutputStream: FileOutputStream? = null
    
    // Proxy Engine
    private var proxyEngine: ProxyEngine? = null
    private var tunnelManager: TunnelManager? = null
    
    // Coroutine jobs
    private var connectionJob: Job? = null
    private var statsJob: Job? = null
    private var pingJob: Job? = null
    private var readJob: Job? = null
    private var writeJob: Job? = null
    
    // Coroutine scope
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    
    // Connection state
    private val _connectionState = MutableStateFlow(VpnConnectionState.DISCONNECTED)
    val connectionState: StateFlow<VpnConnectionState> = _connectionState.asStateFlow()
    
    // Current configuration
    private var serverIp: String = ""
    private var serverPort: Int = 443
    private var serverName: String = ""
    private var protocol: String = "TCP"
    private var mtu: Int = DEFAULT_MTU
    private var customDnsServers: List<String> = emptyList()
    private var httpHeaders: List<HttpHeaderConfig> = emptyList()
    private var sniConfig: SniConfig? = null
    private var killSwitchEnabled: Boolean = false
    private var splitTunnelApps: List<String> = emptyList()
    private var excludedApps: List<String> = emptyList()
    private var ipv6Enabled: Boolean = false
    
    // Authentication credentials for remote server
    private var authUsername: String = ""
    private var authPassword: String = ""
    private var customPayload: String = ""
    
    // Traffic Statistics
    private var bytesIn: Long = 0L
    private var bytesOut: Long = 0L
    private var lastBytesIn: Long = 0L
    private var lastBytesOut: Long = 0L
    private var speedDown: Long = 0L
    private var speedUp: Long = 0L
    private var connectedAt: Long = 0L
    private var latency: Int = 0
    
    // Buffer for TUN reading
    private val tunBuffer = ByteBuffer.allocateDirect(TUN_READ_BUFFER_SIZE)
    
    // Binder for service connection
    private val binder = VpnBinder()
    
    // Is service running
    @Volatile
    private var isRunning = false

    // ============================================
    // SERVICE LIFECYCLE
    // ============================================

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VPN Service onCreate")
        
        // Create notification channel
        createNotificationChannel()
        
        // Register with manager
        VpnServiceManager.getInstance(this).registerBinder(binder)
        
        // Initialize proxy engine
        proxyEngine = ProxyEngine(this)
        tunnelManager = TunnelManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "VPN Service onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_CONNECT -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG)
                if (configJson != null) {
                    val config = JSONObject(configJson)
                    connect(config)
                }
            }
            ACTION_DISCONNECT -> {
                disconnect()
            }
            ACTION_RECONNECT -> {
                reconnect()
            }
            ACTION_PAUSE -> {
                pause()
            }
            ACTION_RESUME -> {
                resume()
            }
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        Log.d(TAG, "VPN Service onDestroy")
        
        disconnect()
        VpnServiceManager.getInstance(this).unregisterBinder()
        serviceScope.cancel()
        proxyEngine?.stop()
        tunnelManager?.stop()
        
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.d(TAG, "VPN Service onRevoke - Permission revoked")
        
        // VPN permission was revoked by user
        disconnect()
        
        // Update state
        val manager = VpnServiceManager.getInstance(this)
        manager.updateState(
            state = VpnConnectionState.DISCONNECTED,
            errorMessage = "VPN permission revoked by system"
        )
        manager.sendEvent(
            type = "permissionRevoked",
            message = "VPN permission was revoked by the system"
        )
    }

    // ============================================
    // CONNECTION MANAGEMENT
    // ============================================

    /**
     * Connect to VPN server with given configuration
     */
    private fun connect(config: JSONObject) {
        if (isRunning) {
            Log.w(TAG, "VPN already running, disconnecting first")
            disconnect()
        }
        
        Log.d(TAG, "Connecting with configuration")
        
        // Parse configuration
        parseConfiguration(config)
        
        // Update state to connecting
        _connectionState.value = VpnConnectionState.CONNECTING
        val manager = VpnServiceManager.getInstance(this)
        manager.updateState(
            state = VpnConnectionState.CONNECTING,
            serverName = serverName,
            serverIp = serverIp,
            serverPort = serverPort,
            protocol = protocol
        )
        manager.sendEvent(
            type = "connecting",
            message = "Connecting to $serverIp:$serverPort"
        )
        
        // Start foreground service with proper type for Android 14+
        startVpnForegroundService()
        
        // Start connection process
        connectionJob = serviceScope.launch {
            try {
                establishConnection()
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                handleConnectionError(e.message ?: "Connection failed")
            }
        }
    }

    /**
     * Parse VPN configuration from JSON
     */
    private fun parseConfiguration(config: JSONObject) {
        // Server configuration
        val serverConfig = config.optJSONObject("server")
        serverIp = serverConfig?.optString("serverIp") ?: ""
        serverPort = serverConfig?.optInt("port") ?: 443
        serverName = serverConfig?.optString("name") ?: "VPN Server"
        protocol = serverConfig?.optString("protocol") ?: "TCP"
        
        // Authentication credentials
        authUsername = serverConfig?.optString("username") ?: ""
        authPassword = serverConfig?.optString("password") ?: ""
        
        // VPN settings
        mtu = config.optInt("mtu", DEFAULT_MTU)
        killSwitchEnabled = config.optBoolean("killSwitch", false)
        ipv6Enabled = config.optBoolean("ipv6Enabled", false)
        
        // Custom payload for advanced routing
        customPayload = config.optString("customPayload", "")
        
        // DNS servers
        val dnsArray = config.optJSONArray("customDns")
        customDnsServers = if (dnsArray != null && dnsArray.length() > 0) {
            (0 until dnsArray.length()).map { dnsArray.getString(it) }
        } else {
            listOf(DEFAULT_DNS_1, DEFAULT_DNS_2, DEFAULT_DNS_3)
        }
        
        // HTTP headers
        val headersArray = config.optJSONArray("httpHeaders")
        httpHeaders = (0 until (headersArray?.length() ?: 0)).mapNotNull { i ->
            headersArray?.getJSONObject(i)?.let { headerObj ->
                HttpHeaderConfig(
                    name = headerObj.optString("name"),
                    value = headerObj.optString("value"),
                    enabled = headerObj.optBoolean("enabled", true)
                )
            }
        }.filter { it.enabled }
        
        // SNI configuration
        val sniObj = config.optJSONObject("sniConfig")
        if (sniObj != null && sniObj.optBoolean("enabled", true)) {
            sniConfig = SniConfig(
                serverName = sniObj.optString("serverName"),
                enabled = sniObj.optBoolean("enabled", true),
                allowOverride = sniObj.optBoolean("allowOverride", false)
            )
        }
        
        // Split tunneling
        val excludedAppsArray = config.optJSONArray("excludedApps")
        excludedApps = if (excludedAppsArray != null) {
            (0 until excludedAppsArray.length()).map { excludedAppsArray.getString(it) }
        } else {
            emptyList()
        }
        
        Log.d(TAG, "Configuration parsed - Server: $serverIp:$serverPort, " +
                "Headers: ${httpHeaders.size}, SNI: ${sniConfig?.serverName}, " +
                "Auth: ${if (authUsername.isNotEmpty()) "enabled" else "disabled"}")
    }

    /**
     * Start foreground service with Android 14+ compliance
     */
    private fun startVpnForegroundService() {
        val notification = createNotification(
            title = getString(R.string.app_name),
            content = getString(R.string.vpn_notification_connecting)
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ (API 34) - Must specify foreground service type
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ (API 29)
            startForeground(NOTIFICATION_ID, notification)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Establish VPN connection
     */
    private suspend fun establishConnection() {
        Log.d(TAG, "Establishing VPN connection to $serverIp:$serverPort")
        
        // Update state
        _connectionState.value = VpnConnectionState.AUTHENTICATING
        VpnServiceManager.getInstance(this).updateState(
            state = VpnConnectionState.AUTHENTICATING
        )
        
        // Build VPN interface
        val vpnBuilder = buildVpnInterface()
        
        // Establish the VPN interface (creates tun0)
        vpnInterface = vpnBuilder.establish()
        
        if (vpnInterface == null) {
            throw VpnException("Failed to establish VPN interface - permission may have been revoked")
        }
        
        Log.d(TAG, "TUN interface (tun0) established successfully")
        
        // Get input/output streams for TUN interface
        vpnInputStream = FileInputStream(vpnInterface!!.fileDescriptor)
        vpnOutputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
        
        // Configure and start proxy engine
        configureProxyEngine()
        
        // Start traffic monitoring
        startTrafficMonitoring()
        
        // Start latency monitoring
        startPingMonitoring()
        
        // Start packet processing
        startPacketProcessing()
        
        // Connection established
        connectedAt = System.currentTimeMillis()
        isRunning = true
        _connectionState.value = VpnConnectionState.CONNECTED
        
        VpnServiceManager.getInstance(this).updateState(
            state = VpnConnectionState.CONNECTED,
            connectedAt = connectedAt,
            localIp = DEFAULT_ADDRESS
        )
        
        VpnServiceManager.getInstance(this).sendEvent(
            type = "connected",
            message = "Connected to $serverName"
        )
        
        // Update notification
        updateNotification(
            content = getString(R.string.vpn_notification_content, serverName, serverIp)
        )
        
        Log.d(TAG, "VPN connection established successfully - tun0 active")
    }

    /**
     * Build VPN interface using VpnService.Builder
     */
    private fun buildVpnInterface(): Builder {
        val builder = Builder()
        
        // Set session name
        builder.setSession("Enterprise VPN - $serverName")
        
        // Set MTU
        builder.setMtu(mtu)
        
        // Add local address (our VPN interface IP)
        builder.addAddress(DEFAULT_ADDRESS, DEFAULT_ADDRESS_PREFIX)
        
        // Route all traffic through VPN
        builder.addRoute("0.0.0.0", 0)  // All IPv4 traffic
        
        // Add IPv6 routing if enabled
        if (ipv6Enabled) {
            builder.addAddress("fd00::1", 64)
            builder.addRoute("::", 0)  // All IPv6 traffic
        }
        
        // Add DNS servers
        customDnsServers.forEach { dns ->
            try {
                builder.addDnsServer(dns)
                Log.d(TAG, "Added DNS server: $dns")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add DNS server: $dns", e)
            }
        }
        
        // Add search domain
        builder.addSearchDomain("enterprise.vpn.local")
        
        // Configure split tunneling (exclude apps)
        if (excludedApps.isNotEmpty()) {
            excludedApps.forEach { packageName ->
                try {
                    builder.addDisallowedApplication(packageName)
                    Log.d(TAG, "Excluded app from VPN: $packageName")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to exclude app: $packageName", e)
                }
            }
        }
        
        // Allow bypass if kill switch is disabled
        if (!killSwitchEnabled) {
            builder.allowBypass()
        }
        
        // Set underlying network for better performance
        // builder.setUnderlyingNetworks(...)
        
        // Configure HTTP proxy if needed
        // Note: For custom HTTP header injection, we handle this in ProxyEngine
        
        return builder
    }

    /**
     * Configure and start proxy engine
     */
    private fun configureProxyEngine() {
        val proxyConfig = ProxyConfig(
            serverIp = serverIp,
            serverPort = serverPort,
            protocol = protocol,
            httpHeaders = httpHeaders,
            sniConfig = sniConfig,
            mtu = mtu
        )
        
        proxyEngine?.configure(proxyConfig)
        proxyEngine?.start()
        
        // Initialize tunnel manager for secure connection
        val tunnelConfig = TunnelConfig(
            serverIp = serverIp,
            serverPort = serverPort,
            sniServerName = sniConfig?.serverName ?: serverIp,
            protocol = protocol
        )
        
        tunnelManager?.configure(tunnelConfig)
        tunnelManager?.connect()
        
        Log.d(TAG, "Proxy engine and tunnel configured")
    }

    /**
     * Start packet processing loop
     * Reads packets from TUN interface and forwards to proxy engine
     */
    private fun startPacketProcessing() {
        // Read from TUN and process
        readJob = serviceScope.launch {
            Log.d(TAG, "Starting TUN read loop")
            
            while (isActive && vpnInterface != null) {
                try {
                    // Read packet from TUN interface
                    tunBuffer.clear()
                    val length = vpnInputStream?.read(tunBuffer.array()) ?: -1
                    
                    if (length > 0) {
                        // Extract packet data
                        val packet = ByteArray(length)
                        System.arraycopy(tunBuffer.array(), 0, packet, 0, length)
                        
                        // Process packet through proxy engine
                        val processed = proxyEngine?.processOutgoingPacket(packet, length) ?: false
                        
                        if (processed) {
                            // Update statistics
                            bytesOut += length
                        }
                    } else if (length < 0) {
                        Log.w(TAG, "TUN read returned -1, interface may be closed")
                        break
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error reading from TUN", e)
                        // Brief pause before retry
                        delay(10)
                    }
                }
            }
            
            Log.d(TAG, "TUN read loop ended")
        }
        
        // Write to TUN (incoming packets from proxy)
        writeJob = serviceScope.launch {
            Log.d(TAG, "Starting TUN write handler")
            
            proxyEngine?.incomingPacketFlow?.collect { packet ->
                try {
                    vpnOutputStream?.write(packet.data, 0, packet.length)
                    bytesIn += packet.length
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error writing to TUN", e)
                    }
                }
            }
        }
    }

    /**
     * Disconnect from VPN
     */
    private fun disconnect() {
        if (!isRunning && vpnInterface == null) {
            Log.d(TAG, "VPN not running, nothing to disconnect")
            return
        }
        
        Log.d(TAG, "Disconnecting...")
        
        isRunning = false
        _connectionState.value = VpnConnectionState.DISCONNECTING
        
        VpnServiceManager.getInstance(this).updateState(
            state = VpnConnectionState.DISCONNECTING
        )
        
        VpnServiceManager.getInstance(this).sendEvent(
            type = "disconnecting",
            message = "Disconnecting from VPN"
        )
        
        // Cancel all jobs
        connectionJob?.cancel()
        statsJob?.cancel()
        pingJob?.cancel()
        readJob?.cancel()
        writeJob?.cancel()
        
        // Stop proxy engine
        proxyEngine?.stop()
        tunnelManager?.disconnect()
        
        // Close TUN interface
        try {
            vpnInputStream?.close()
            vpnOutputStream?.close()
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TUN interface", e)
        }
        
        vpnInputStream = null
        vpnOutputStream = null
        vpnInterface = null
        
        // Reset statistics
        bytesIn = 0L
        bytesOut = 0L
        speedDown = 0L
        speedUp = 0L
        connectedAt = 0L
        
        // Update state
        _connectionState.value = VpnConnectionState.DISCONNECTED
        
        VpnServiceManager.getInstance(this).updateState(
            state = VpnConnectionState.DISCONNECTED,
            bytesIn = 0L,
            bytesOut = 0L,
            speedDown = 0L,
            speedUp = 0L
        )
        
        VpnServiceManager.getInstance(this).sendEvent(
            type = "disconnected",
            message = "VPN disconnected"
        )
        
        // Stop foreground service
        stopForeground(STOP_FOREGROUND_REMOVE)
        
        Log.d(TAG, "VPN disconnected successfully")
    }

    /**
     * Reconnect to VPN
     */
    private fun reconnect() {
        Log.d(TAG, "Reconnecting...")
        
        _connectionState.value = VpnConnectionState.RECONNECTING
        VpnServiceManager.getInstance(this).updateState(
            state = VpnConnectionState.RECONNECTING
        )
        
        VpnServiceManager.getInstance(this).sendEvent(
            type = "reconnecting",
            message = "Reconnecting to VPN"
        )
        
        disconnect()
        
        // Reconnect with existing config
        serviceScope.launch {
            delay(1000)
            if (serverIp.isNotEmpty()) {
                val config = JSONObject().apply {
                    put("server", JSONObject().apply {
                        put("serverIp", serverIp)
                        put("port", serverPort)
                        put("name", serverName)
                        put("protocol", protocol)
                    })
                    put("mtu", mtu)
                    put("killSwitch", killSwitchEnabled)
                    put("customDns", org.json.JSONArray(customDnsServers))
                }
                connect(config)
            }
        }
    }

    /**
     * Pause VPN (keep connection but stop routing)
     */
    private fun pause() {
        Log.d(TAG, "Pausing VPN")
        proxyEngine?.pause()
        updateNotification(content = "VPN Paused")
    }

    /**
     * Resume VPN routing
     */
    private fun resume() {
        Log.d(TAG, "Resuming VPN")
        proxyEngine?.resume()
        updateNotification(content = getString(R.string.vpn_notification_content, serverName, serverIp))
    }

    /**
     * Handle connection error
     */
    private fun handleConnectionError(message: String) {
        Log.e(TAG, "Connection error: $message")
        
        isRunning = false
        _connectionState.value = VpnConnectionState.ERROR
        
        VpnServiceManager.getInstance(this).updateState(
            state = VpnConnectionState.ERROR,
            errorMessage = message
        )
        
        VpnServiceManager.getInstance(this).sendEvent(
            type = "error",
            message = message
        )
        
        updateNotification(content = "Connection Error: $message")
        
        // Stop foreground after delay
        serviceScope.launch {
            delay(5000)
            if (_connectionState.value == VpnConnectionState.ERROR) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    // ============================================
    // TRAFFIC MONITORING
    // ============================================

    private fun startTrafficMonitoring() {
        statsJob = serviceScope.launch {
            while (isActive && isRunning) {
                delay(STATS_UPDATE_INTERVAL_MS)
                
                if (!isRunning) break
                
                val currentTime = System.currentTimeMillis()
                val duration = if (connectedAt > 0) {
                    (currentTime - connectedAt) / 1000L
                } else {
                    0L
                }
                
                // Calculate speeds (bytes per second)
                speedDown = bytesIn - lastBytesIn
                speedUp = bytesOut - lastBytesOut
                lastBytesIn = bytesIn
                lastBytesOut = bytesOut
                
                // Update manager
                VpnServiceManager.getInstance(this@EnterpriseVpnService).updateState(
                    bytesIn = bytesIn,
                    bytesOut = bytesOut,
                    speedDown = speedDown,
                    speedUp = speedUp,
                    duration = duration
                )
                
                VpnServiceManager.getInstance(this@EnterpriseVpnService).updateTraffic(
                    bytesIn = bytesIn,
                    bytesOut = bytesOut,
                    speedDown = speedDown,
                    speedUp = speedUp
                )
            }
        }
    }

    private fun startPingMonitoring() {
        pingJob = serviceScope.launch {
            while (isActive && isRunning) {
                delay(PING_INTERVAL_MS)
                
                if (!isRunning) break
                
                // Measure latency to server
                latency = proxyEngine?.measureLatency() ?: measureDirectLatency()
                
                VpnServiceManager.getInstance(this@EnterpriseVpnService).updateState(
                    latency = latency
                )
            }
        }
    }

    private fun measureDirectLatency(): Int {
        // Basic ICMP-like latency measurement
        return try {
            val start = System.currentTimeMillis()
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(serverIp, serverPort), 3000)
            socket.close()
            (System.currentTimeMillis() - start).toInt()
        } catch (e: Exception) {
            Log.d(TAG, "Latency measurement failed: ${e.message}")
            -1
        }
    }

    // ============================================
    // NOTIFICATION MANAGEMENT
    // ============================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN connection status and statistics"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
            }
            
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        // Intent to open app when notification is tapped
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Disconnect action
        val disconnectIntent = Intent(this, EnterpriseVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(getColor(R.color.notification_color))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.btn_disconnect),
                disconnectPendingIntent
            )
            .build()
    }

    private fun updateNotification(content: String) {
        updateNotification(getString(R.string.app_name), content)
    }

    private fun updateNotification(title: String, content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(title, content))
    }

    // ============================================
    // BINDER
    // ============================================

    inner class VpnBinder : Binder() {
        val service: EnterpriseVpnService
            get() = this@EnterpriseVpnService
        
        val isConnected: Boolean
            get() = this@EnterpriseVpnService.isRunning
        
        val currentState: VpnConnectionState
            get() = this@EnterpriseVpnService._connectionState.value
        
        fun updateConfiguration(config: JSONObject) {
            this@EnterpriseVpnService.connect(config)
        }
        
        fun updateHttpHeaders(headers: List<HttpHeaderConfig>) {
            this@EnterpriseVpnService.httpHeaders = headers
            this@EnterpriseVpnService.proxyEngine?.updateHttpHeaders(headers)
            Log.d(TAG, "HTTP headers updated: ${headers.size} headers")
        }
        
        fun updateSniConfig(config: SniConfig) {
            this@EnterpriseVpnService.sniConfig = config
            this@EnterpriseVpnService.proxyEngine?.updateSniConfig(config)
            this@EnterpriseVpnService.tunnelManager?.updateSniServerName(config.serverName)
            Log.d(TAG, "SNI config updated: ${config.serverName}")
        }
        
        fun updateKillSwitch(enabled: Boolean) {
            this@EnterpriseVpnService.killSwitchEnabled = enabled
            Log.d(TAG, "Kill switch updated: $enabled")
        }
        
        fun updateDnsServers(servers: List<String>) {
            this@EnterpriseVpnService.customDnsServers = servers
            Log.d(TAG, "DNS servers updated: $servers")
        }
        
        fun updateMtu(mtu: Int) {
            this@EnterpriseVpnService.mtu = mtu
            Log.d(TAG, "MTU updated: $mtu")
        }
        
        fun excludeApps(packageNames: List<String>) {
            this@EnterpriseVpnService.excludedApps = packageNames
            Log.d(TAG, "Excluded apps updated: $packageNames")
        }
        
        fun startSpeedTest(): Boolean {
            return this@EnterpriseVpnService.proxyEngine?.startSpeedTest() ?: false
        }
        
        fun stopSpeedTest(): Boolean {
            return this@EnterpriseVpnService.proxyEngine?.stopSpeedTest() ?: false
        }
        
        fun getTrafficStats(): TrafficStats {
            return TrafficStats(
                bytesIn = this@EnterpriseVpnService.bytesIn,
                bytesOut = this@EnterpriseVpnService.bytesOut,
                speedDown = this@EnterpriseVpnService.speedDown,
                speedUp = this@EnterpriseVpnService.speedUp
            )
        }
    }
}

/**
 * VPN Exception
 */
class VpnException(message: String) : Exception(message)

// Missing imports - add StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect

/**
 * Traffic Statistics
 */
data class TrafficStats(
    val bytesIn: Long,
    val bytesOut: Long,
    val speedDown: Long,
    val speedUp: Long
)
