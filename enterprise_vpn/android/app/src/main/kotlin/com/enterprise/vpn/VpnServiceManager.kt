package com.enterprise.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.enterprise.vpn.model.ConnectionInfo
import com.enterprise.vpn.model.TrafficStats
import com.enterprise.vpn.model.VpnEvent
import com.enterprise.vpn.model.VpnState
import com.enterprise.vpn.service.EnterpriseVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * VPN Service Manager
 * 
 * Central manager for VPN operations that bridges the UI layer with the VPN service.
 * Handles state management, configuration, and communication with the native VPN engine.
 */
class VpnServiceManager private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "VpnServiceManager"
        
        @Volatile
        private var instance: VpnServiceManager? = null
        
        fun getInstance(context: Context): VpnServiceManager {
            return instance ?: synchronized(this) {
                instance ?: VpnServiceManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    // ============================================
    // STATE FLOWS
    // ============================================

    private val _stateFlow = MutableStateFlow(VpnState())
    val stateFlow: StateFlow<VpnState> = _stateFlow.asStateFlow()

    private val _trafficFlow = MutableSharedFlow<TrafficStats>(replay = 1)
    val trafficFlow: SharedFlow<TrafficStats> = _trafficFlow.asSharedFlow()

    private val _eventsFlow = MutableSharedFlow<VpnEvent>()
    val eventsFlow: SharedFlow<VpnEvent> = _eventsFlow.asSharedFlow()

    // ============================================
    // COROUTINE SCOPE
    // ============================================

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ============================================
    // CONFIGURATION STATE
    // ============================================

    private val currentConfig = AtomicReference<JSONObject?>(null)
    private val httpHeaders = AtomicReference<List<HttpHeaderConfig>>(emptyList())
    private val sniConfig = AtomicReference<SniConfig?>(null)
    private var killSwitchEnabled = false
    private var customDnsServers = listOf<String>()
    private var mtuSize = 1500

    // ============================================
    // SERVICE BINDER REFERENCE
    // ============================================

    private var vpnServiceBinder: EnterpriseVpnService.VpnBinder? = null

    // ============================================
    // PUBLIC API
    // ============================================

    /**
     * Check if VPN permission is granted
     */
    fun hasVpnPermission(): Boolean {
        return VpnService.prepare(context) == null
    }

    /**
     * Connect to VPN with the given configuration
     */
    fun connect(config: JSONObject): ConnectionResult {
        return try {
            Log.d(TAG, "Connecting with config: ${config.toString(2)}")
            
            // Store configuration
            currentConfig.set(config)
            
            // Parse server configuration
            val serverConfig = config.optJSONObject("server")
            val serverIp = serverConfig?.optString("serverIp") ?: ""
            val serverPort = serverConfig?.optInt("port") ?: 443
            val serverName = serverConfig?.optString("name") ?: "VPN Server"
            val protocol = serverConfig?.optString("protocol") ?: "TCP"
            
            // Parse HTTP headers
            val headersArray = config.optJSONArray("httpHeaders")
            val headers = mutableListOf<HttpHeaderConfig>()
            for (i in 0 until (headersArray?.length() ?: 0)) {
                val headerObj = headersArray?.getJSONObject(i)
                if (headerObj != null) {
                    headers.add(HttpHeaderConfig(
                        name = headerObj.optString("name"),
                        value = headerObj.optString("value"),
                        enabled = headerObj.optBoolean("enabled", true)
                    ))
                }
            }
            httpHeaders.set(headers)
            
            // Parse SNI configuration
            val sniObj = config.optJSONObject("sniConfig")
            if (sniObj != null) {
                sniConfig.set(SniConfig(
                    serverName = sniObj.optString("serverName"),
                    enabled = sniObj.optBoolean("enabled", true),
                    allowOverride = sniObj.optBoolean("allowOverride", false)
                ))
            }
            
            // Parse other options
            killSwitchEnabled = config.optBoolean("killSwitch", false)
            mtuSize = config.optInt("mtu", 1500)
            
            val dnsArray = config.optJSONArray("customDns")
            if (dnsArray != null && dnsArray.length() > 0) {
                customDnsServers = (0 until dnsArray.length()).map { dnsArray.getString(it) }
            }
            
            // Update state to connecting
            updateState(
                state = VpnConnectionState.CONNECTING,
                serverName = serverName,
                serverIp = serverIp,
                serverPort = serverPort,
                protocol = protocol
            )
            
            // Start VPN service
            val serviceIntent = Intent(context, EnterpriseVpnService::class.java).apply {
                action = EnterpriseVpnService.ACTION_CONNECT
                putExtra(EnterpriseVpnService.EXTRA_CONFIG, config.toString())
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            ConnectionResult(success = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            updateState(
                state = VpnConnectionState.ERROR,
                errorMessage = e.message ?: "Connection failed"
            )
            ConnectionResult(
                success = false,
                error = e.message ?: "Connection failed",
                errorCode = "CONNECT_ERROR"
            )
        }
    }

    /**
     * Disconnect from VPN
     */
    fun disconnect(): Boolean {
        return try {
            Log.d(TAG, "Disconnecting...")
            
            updateState(state = VpnConnectionState.DISCONNECTING)
            
            val serviceIntent = Intent(context, EnterpriseVpnService::class.java).apply {
                action = EnterpriseVpnService.ACTION_DISCONNECT
            }
            context.startService(serviceIntent)
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect", e)
            updateState(
                state = VpnConnectionState.ERROR,
                errorMessage = e.message ?: "Disconnection failed"
            )
            false
        }
    }

    /**
     * Get current VPN status
     */
    fun getStatus(): VpnState {
        return _stateFlow.value
    }

    /**
     * Get traffic statistics
     */
    fun getTrafficStats(): TrafficStats {
        return TrafficStats(
            bytesIn = _stateFlow.value.bytesIn,
            bytesOut = _stateFlow.value.bytesOut,
            speedDown = _stateFlow.value.speedDown,
            speedUp = _stateFlow.value.speedUp
        )
    }

    /**
     * Get connection info
     */
    fun getConnectionInfo(): ConnectionInfo {
        return ConnectionInfo(
            downloadSpeed = _stateFlow.value.speedDown,
            uploadSpeed = _stateFlow.value.speedUp,
            latency = _stateFlow.value.latency,
            jitter = null,
            packetLoss = null
        )
    }

    /**
     * Configure VPN
     */
    fun configure(config: JSONObject): Boolean {
        return try {
            currentConfig.set(config)
            
            // Update configuration in running service
            vpnServiceBinder?.updateConfiguration(config)
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure", e)
            false
        }
    }

    /**
     * Set HTTP headers for injection
     */
    fun setHttpHeaders(headersJson: JSONObject): Boolean {
        return try {
            val headersArray = headersJson.optJSONArray("headers")
            val headers = mutableListOf<HttpHeaderConfig>()
            
            for (i in 0 until (headersArray?.length() ?: 0)) {
                val headerObj = headersArray?.getJSONObject(i)
                if (headerObj != null) {
                    headers.add(HttpHeaderConfig(
                        name = headerObj.optString("name"),
                        value = headerObj.optString("value"),
                        enabled = headerObj.optBoolean("enabled", true)
                    ))
                }
            }
            
            httpHeaders.set(headers)
            
            // Update headers in running service
            vpnServiceBinder?.updateHttpHeaders(headers)
            
            Log.d(TAG, "HTTP headers updated: ${headers.size} headers")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set HTTP headers", e)
            false
        }
    }

    /**
     * Set SNI configuration
     */
    fun setSniConfig(config: JSONObject): Boolean {
        return try {
            val sniConfigObj = SniConfig(
                serverName = config.optString("serverName"),
                enabled = config.optBoolean("enabled", true),
                allowOverride = config.optBoolean("allowOverride", false)
            )
            
            sniConfig.set(sniConfigObj)
            
            // Update SNI in running service
            vpnServiceBinder?.updateSniConfig(sniConfigObj)
            
            Log.d(TAG, "SNI config updated: ${sniConfigObj.serverName}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set SNI config", e)
            false
        }
    }

    /**
     * Set kill switch mode
     */
    fun setKillSwitch(enabled: Boolean): Boolean {
        return try {
            killSwitchEnabled = enabled
            vpnServiceBinder?.updateKillSwitch(enabled)
            Log.d(TAG, "Kill switch updated: $enabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set kill switch", e)
            false
        }
    }

    /**
     * Set custom DNS servers
     */
    fun setDnsServers(servers: List<String>): Boolean {
        return try {
            customDnsServers = servers
            vpnServiceBinder?.updateDnsServers(servers)
            Log.d(TAG, "DNS servers updated: $servers")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set DNS servers", e)
            false
        }
    }

    /**
     * Set MTU size
     */
    fun setMtu(mtu: Int): Boolean {
        return try {
            mtuSize = mtu
            vpnServiceBinder?.updateMtu(mtu)
            Log.d(TAG, "MTU updated: $mtu")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set MTU", e)
            false
        }
    }

    /**
     * Start speed test
     */
    fun startSpeedTest(): Boolean {
        return try {
            vpnServiceBinder?.startSpeedTest() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speed test", e)
            false
        }
    }

    /**
     * Stop speed test
     */
    fun stopSpeedTest(): Boolean {
        return try {
            vpnServiceBinder?.stopSpeedTest() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop speed test", e)
            false
        }
    }

    // ============================================
    // STATE MANAGEMENT
    // ============================================

    /**
     * Update VPN state
     */
    fun updateState(
        state: VpnConnectionState? = null,
        serverName: String? = null,
        serverIp: String? = null,
        serverPort: Int? = null,
        protocol: String? = null,
        connectedAt: Long? = null,
        duration: Long? = null,
        bytesIn: Long? = null,
        bytesOut: Long? = null,
        speedDown: Long? = null,
        speedUp: Long? = null,
        latency: Int? = null,
        errorMessage: String? = null,
        localIp: String? = null,
        remoteIp: String? = null
    ) {
        _stateFlow.update { currentState ->
            VpnState(
                state = state ?: currentState.state,
                serverName = serverName ?: currentState.serverName,
                serverIp = serverIp ?: currentState.serverIp,
                serverPort = serverPort ?: currentState.serverPort,
                protocol = protocol ?: currentState.protocol,
                connectedAt = connectedAt ?: currentState.connectedAt,
                duration = duration ?: currentState.duration,
                bytesIn = bytesIn ?: currentState.bytesIn,
                bytesOut = bytesOut ?: currentState.bytesOut,
                speedDown = speedDown ?: currentState.speedDown,
                speedUp = speedUp ?: currentState.speedUp,
                latency = latency ?: currentState.latency,
                errorMessage = errorMessage ?: currentState.errorMessage,
                localIp = localIp ?: currentState.localIp,
                remoteIp = remoteIp ?: currentState.remoteIp
            )
        }
    }

    /**
     * Update traffic statistics
     */
    fun updateTraffic(
        bytesIn: Long,
        bytesOut: Long,
        speedDown: Long,
        speedUp: Long
    ) {
        _trafficFlow.tryEmit(TrafficStats(
            bytesIn = bytesIn,
            bytesOut = bytesOut,
            speedDown = speedDown,
            speedUp = speedUp
        ))
    }

    /**
     * Send event
     */
    fun sendEvent(type: String, message: String, data: Map<String, Any>? = null) {
        _eventsFlow.tryEmit(VpnEvent(
            type = type,
            message = message,
            timestamp = System.currentTimeMillis(),
            data = data
        ))
    }

    /**
     * Register VPN service binder
     */
    fun registerBinder(binder: EnterpriseVpnService.VpnBinder) {
        vpnServiceBinder = binder
        Log.d(TAG, "VPN service binder registered")
    }

    /**
     * Unregister VPN service binder
     */
    fun unregisterBinder() {
        vpnServiceBinder = null
        Log.d(TAG, "VPN service binder unregistered")
    }

    /**
     * Cleanup
     */
    fun cleanup() {
        scope.cancel()
        vpnServiceBinder = null
    }
}

// ============================================
// DATA CLASSES
// ============================================

/**
 * Connection result
 */
data class ConnectionResult(
    val success: Boolean,
    val error: String? = null,
    val errorCode: String? = null
)

/**
 * HTTP header configuration
 */
data class HttpHeaderConfig(
    val name: String,
    val value: String,
    val enabled: Boolean = true
)

/**
 * SNI configuration
 */
data class SniConfig(
    val serverName: String,
    val enabled: Boolean = true,
    val allowOverride: Boolean = false
)

/**
 * VPN Connection State
 */
enum class VpnConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    RECONNECTING,
    AUTHENTICATING,
    ERROR,
    NO_NETWORK
}
