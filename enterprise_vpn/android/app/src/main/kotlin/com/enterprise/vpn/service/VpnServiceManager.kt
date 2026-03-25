package com.enterprise.vpn.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import com.enterprise.vpn.model.*
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * VPN Service Manager
 * 
 * Manages communication between Flutter and the native VPN service.
 */
object VpnServiceManager : MethodChannel.MethodCallHandler {

    private const val TAG = "VpnServiceManager"
    private const val VPN_PERMISSION_REQUEST = 1001
    
    private var channel: MethodChannel? = null
    private var context: Context? = null
    private var activity: Activity? = null
    
    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pendingResult: MethodChannel.Result? = null

    fun init(context: Context, activity: Activity, channel: MethodChannel) {
        this.context = context
        this.activity = activity
        this.channel = channel
        
        channel.setMethodCallHandler(this)
        startObservingState()
        
        Log.i(TAG, "VPN Service Manager initialized")
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
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

    @Suppress("UNCHECKED_CAST")
    private fun handleConnect(call: MethodCall, result: MethodChannel.Result) {
        try {
            Log.d(TAG, "======== handleConnect called ========")
            
            val configMap = call.arguments as? Map<String, Any?>
            if (configMap == null) {
                Log.e(TAG, "CONFIG_INVALID: configMap is null, arguments=${call.arguments}")
                result.error("CONFIG_INVALID", "Invalid configuration format - configMap is null", null)
                return
            }
            
            Log.d(TAG, "Received configMap keys: ${configMap.keys}")
            
            val serverMap = configMap["server"] as? Map<String, Any?>
            if (serverMap != null) {
                Log.d(TAG, "Server map: id=${serverMap["id"]}, name=${serverMap["name"]}, serverIp=${serverMap["serverIp"]}, port=${serverMap["port"]}")
            } else {
                Log.e(TAG, "Server map is null!")
                result.error("CONFIG_INVALID", "Invalid configuration: server map is missing", null)
                return
            }
            
            val config = VpnConfig.fromMap(configMap)
            
            Log.d(TAG, "Parsed config: server=${config.server}, serverIp=${config.server?.serverIp}, port=${config.server?.port}")
            Log.d(TAG, "Config isValid: ${config.isValid()}")
            
            if (!config.isValid()) {
                val errorMsg = buildString {
                    append("Invalid VPN configuration: ")
                    if (config.server == null) {
                        append("server is null")
                    } else {
                        if (config.server!!.serverIp.isEmpty()) {
                            append("server address is missing")
                        }
                        if (config.server!!.port <= 0) {
                            if (isNotEmpty()) append(", ")
                            append("port is invalid (${config.server!!.port})")
                        }
                    }
                }
                Log.e(TAG, "CONFIG_INVALID: $errorMsg")
                result.error("CONFIG_INVALID", errorMsg, null)
                return
            }
            
            val ctx = context
            if (ctx == null) {
                result.error("NO_CONTEXT", "Context not available", null)
                return
            }
            
            val intent = Intent(ctx, EnterpriseVpnService::class.java).apply {
                action = EnterpriseVpnService.ACTION_CONNECT
                putExtra("config", config.toJson())
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
            
            // Return success result as map for Flutter to parse
            result.success(mapOf(
                "success" to true,
                "error" to null,
                "errorCode" to null
            ))
            Log.i(TAG, "VPN service started with config")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            result.error("CONNECTION_ERROR", "Failed to start VPN: ${e.message}", null)
        }
    }

    private fun handleDisconnect(result: MethodChannel.Result) {
        try {
            val ctx = context
            if (ctx == null) {
                result.error("NO_CONTEXT", "Context not available", null)
                return
            }
            
            val intent = Intent(ctx, EnterpriseVpnService::class.java).apply {
                action = EnterpriseVpnService.ACTION_DISCONNECT
            }
            ctx.startService(intent)
            result.success(true)
            Log.i(TAG, "VPN disconnect requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect VPN", e)
            result.error("DISCONNECT_ERROR", "Failed to disconnect: ${e.message}", null)
        }
    }

    private fun handleGetStatus(result: MethodChannel.Result) {
        val status = EnterpriseVpnService.statusFlow.value
        result.success(status.toMap())
    }

    private fun handleGetStats(result: MethodChannel.Result) {
        val stats = EnterpriseVpnService.trafficFlow.value
        result.success(stats.toMap())
    }

    private fun handleHasPermission(result: MethodChannel.Result) {
        try {
            val ctx = context
            if (ctx == null) {
                result.success(false)
                return
            }
            val hasPermission = VpnService.prepare(ctx) == null
            result.success(hasPermission)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking VPN permission", e)
            result.success(false)
        }
    }

    private fun handleRequestPermission(result: MethodChannel.Result) {
        try {
            val act = activity
            if (act == null) {
                result.error("NO_ACTIVITY", "No activity available", null)
                return
            }
            
            val intent = VpnService.prepare(act)
            if (intent != null) {
                pendingResult = result
                act.startActivityForResult(intent, VPN_PERMISSION_REQUEST)
            } else {
                result.success(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting VPN permission", e)
            result.error("PERMISSION_ERROR", "Failed to request permission: ${e.message}", null)
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == VPN_PERMISSION_REQUEST) {
            pendingResult?.let { result ->
                result.success(resultCode == Activity.RESULT_OK)
                pendingResult = null
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleSetHttpHeaders(call: MethodCall, result: MethodChannel.Result) {
        try {
            result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting HTTP headers", e)
            result.error("HEADERS_ERROR", "Failed to set headers: ${e.message}", null)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleSetSni(call: MethodCall, result: MethodChannel.Result) {
        try {
            result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting SNI", e)
            result.error("SNI_ERROR", "Failed to set SNI: ${e.message}", null)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleConfigure(call: MethodCall, result: MethodChannel.Result) {
        try {
            val configMap = call.arguments as? Map<String, Any?>
            if (configMap == null) {
                result.error("CONFIG_INVALID", "Invalid configuration", null)
                return
            }
            
            val config = VpnConfig.fromMap(configMap)
            
            val ctx = context
            if (ctx == null) {
                result.error("NO_CONTEXT", "Context not available", null)
                return
            }
            
            val intent = Intent(ctx, EnterpriseVpnService::class.java).apply {
                action = EnterpriseVpnService.ACTION_CONNECT
                putExtra("config", config.toJson())
            }
            ctx.startService(intent)
            
            result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring VPN", e)
            result.error("CONFIG_ERROR", "Failed to configure: ${e.message}", null)
        }
    }

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

    fun cleanup() {
        channel?.setMethodCallHandler(null)
        pendingResult = null
    }
}
