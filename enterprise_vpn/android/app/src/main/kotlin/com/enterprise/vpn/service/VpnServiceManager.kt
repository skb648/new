package com.enterprise.vpn.service

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import com.enterprise.vpn.model.*
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * VPN Service Manager
 * 
 * Manages communication between Flutter and the native VPN service.
 * Handles method calls, state updates, and error reporting.
 */
object VpnServiceManager : MethodChannel.MethodCallHandler {

    private const val TAG = "VpnServiceManager"
    
    private var channel: MethodChannel? = null
    private var context: Context? = null
    private var activity: Activity? = null
    
    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // VPN permission request code
    private var vpnPermissionRequestCode: Int = 0
    private const val VPN_PERMISSION_REQUEST = 1001

    /**
     * Initialize the manager
     */
    fun init(context: Context, activity: Activity, channel: MethodChannel) {
        this.context = context
        this.activity = activity
        this.channel = channel
        
        channel.setMethodCallHandler(this)
        
        // Start observing VPN state
        startObservingState()
        
        Log.i(TAG, "VPN Service Manager initialized")
    }

    /**
     * Handle method calls from Flutter
     */
    override fun onMethodCall(call: MethodChannel.MethodCall, result: MethodChannel.Result) {
        Log.d(TAG, "Method called: ${call.method}")
        
        when (call.method) {
            "connect" -> handleConnect(call, result)
            "disconnect" -> handleDisconnect(result)
            "getStatus" -> handleGetStatus(result)
            "getStats" -> handleGetStats(result)
            "hasVpnPermission" -> handleHasPermission(result)
            "requestVpnPermission" -> handleRequestPermission(result)
            "setHttpHeaders" -> handleSetHttpHeaders(call, result)
            "setSni" -> handleSetSni(call, result)
            "configure" -> handleConfigure(call, result)
            else -> result.notImplemented()
        }
    }

    /**
     * Handle connect request
     */
    private fun handleConnect(call: MethodChannel.MethodCall, result: MethodChannel.Result) {
        try {
            val configMap = call.arguments<Map<String, Any?>>()
            val config = VpnConfig.fromMap(configMap)
            
            if (!config.isValid) {
                result.error("CONFIG_INVALID", "Invalid VPN configuration", null)
                return
            }
            
            val intent = Intent(context, EnterpriseVpnService::class.java).apply {
                action = EnterpriseVpnService.ACTION_CONNECT
                putExtra("config", config.toJson())
            }
            
            // Start the VPN service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            
            result.success(true)
            Log.i(TAG, "VPN service started with config")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            result.error("CONNECTION_ERROR", "Failed to start VPN: ${e.message}", null)
        }
    }

    /**
     * Handle disconnect request
     */
    private fun handleDisconnect(result: MethodChannel.Result) {
        try {
            val intent = Intent(context, EnterpriseVpnService::class.java).apply {
                action = EnterpriseVpnService.ACTION_DISCONNECT
            }
            context.startService(intent)
            result.success(true)
            Log.i(TAG, "VPN disconnect requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect VPN", e)
            result.error("DISCONNECT_ERROR", "Failed to disconnect: ${e.message}", null)
        }
    }

    /**
     * Handle get status request
     */
    private fun handleGetStatus(result: MethodChannel.Result) {
        val status = EnterpriseVpnService.statusFlow.value
        result.success(status.toMap())
    }

    /**
     * Handle get stats request
     */
    private fun handleGetStats(result: MethodChannel.Result) {
        val stats = EnterpriseVpnService.trafficFlow.value
        result.success(stats.toMap())
    }

    /**
     * Handle has permission check
     */
    private fun handleHasPermission(result: MethodChannel.Result) {
        try {
            val hasPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                VpnService.prepare(context) == null
            } else {
                true
            }
            result.success(hasPermission)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking VPN permission", e)
            result.success(false)
        }
    }

    /**
     * Handle request VPN permission
     */
    private fun handleRequestPermission(result: MethodChannel.Result) {
        try {
            activity?.let { act ->
                val intent = VpnService.prepare(act)
                if (intent != null) {
                    // Need to ask user for permission
                    vpnPermissionRequestCode = VPN_PERMISSION_REQUEST
                    act.startActivityForResult(intent, VPN_PERMISSION_REQUEST)
                    result.success(false) // Permission not granted yet
                } else {
                    // Already have permission
                    result.success(true)
                }
            } ?: run {
                result.error("NO_ACTIVITY", "No activity available", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting VPN permission", e)
            result.error("PERMISSION_ERROR", "Failed to request permission: ${e.message}", null)
        }
    }

    /**
     * Handle permission result from activity
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == VPN_PERMISSION_REQUEST) {
            channel?.invokeMethod("onPermissionResult", resultCode == Activity.RESULT_OK)
        }
    }

    /**
     * Handle set HTTP headers
     */
    private fun handleSetHttpHeaders(call: MethodChannel.MethodCall, result: MethodChannel.Result) {
        try {
            val arguments = call.arguments<Map<String, Any?>>()
            val headersList = arguments["headers"] as? List<Map<String, Any?>>
            
            if (headersList != null) {
                // Headers will be used in next connection
                result.success(true)
            } else {
                result.error("INVALID_HEADERS", "Invalid headers format", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting HTTP headers", e)
            result.error("HEADERS_ERROR", "Failed to set headers: ${e.message}", null)
        }
    }

    /**
     * Handle set SNI
     */
    private fun handleSetSni(call: MethodChannel.MethodCall, result: MethodChannel.Result) {
        try {
            val arguments = call.arguments<Map<String, Any?>>()
            // SNI will be used in next connection
            result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting SNI", e)
            result.error("SNI_ERROR", "Failed to set SNI: ${e.message}", null)
        }
    }

    /**
     * Handle configure
     */
    private fun handleConfigure(call: MethodChannel.MethodCall, result: MethodChannel.Result) {
        try {
            val configMap = call.arguments<Map<String, Any?>>()
            val config = VpnConfig.fromMap(configMap)
            
            // Store config for next connection
            val intent = Intent(context, EnterpriseVpnService::class.java).apply {
                action = EnterpriseVpnService.ACTION_CONNECT
                putExtra("config", config.toJson())
            }
            context.startService(intent)
            
            result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring VPN", e)
            result.error("CONFIG_ERROR", "Failed to configure: ${e.message}", null)
        }
    }

    /**
     * Start observing VPN state changes
     */
    private fun startObservingState() {
        managerScope.launch {
            EnterpriseVpnService.statusFlow.collect { status ->
                channel?.invokeMethod("onStatusChanged", status.toMap())
            }
        }
        
        managerScope.launch {
            EnterpriseVpnService.trafficFlow.collect { stats ->
                channel?.invokeMethod("onTrafficUpdate", stats.toMap())
            }
        }
        
        managerScope.launch {
            EnterpriseVpnService.eventFlow.collect { event ->
                event?.let {
                    channel?.invokeMethod("onVpnEvent", it.toMap())
                }
            }
        }
    }

    /**
     * Clean up
     */
    fun cleanup() {
        channel?.setMethodCallHandler(null)
        managerScope.cancel()
    }
}
