package com.enterprise.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * MainActivity - Flutter Activity with Native Bridge
 * 
 * Handles all MethodChannel communication between Flutter and Native Android.
 * Supports:
 * - VPN permission requests
 * - Connection management
 * - HTTP header injection configuration
 * - SNI configuration
 * - Real-time status updates
 */
class MainActivity : FlutterActivity() {

    companion object {
        private const val TAG = "MainActivity"
        
        // Channel names
        private const val METHOD_CHANNEL = "com.enterprise.vpn/service"
        private const val STATE_EVENT_CHANNEL = "com.enterprise.vpn/state"
        private const val TRAFFIC_EVENT_CHANNEL = "com.enterprise.vpn/traffic"
        private const val EVENTS_EVENT_CHANNEL = "com.enterprise.vpn/events"
        
        // Request codes
        private const val VPN_PERMISSION_REQUEST_CODE = 1001
        
        // Method names
        private const val METHOD_CONNECT = "connect"
        private const val METHOD_DISCONNECT = "disconnect"
        private const val METHOD_GET_STATUS = "getStatus"
        private const val METHOD_GET_STATS = "getStats"
        private const val METHOD_CONFIGURE = "configure"
        private const val METHOD_SET_HEADERS = "setHttpHeaders"
        private const val METHOD_SET_SNI = "setSni"
        private const val METHOD_REQUEST_PERMISSION = "requestVpnPermission"
        private const val METHOD_HAS_PERMISSION = "hasVpnPermission"
        private const val METHOD_PREPARE_VPN = "prepareVpn"
        private const val METHOD_IS_VPN_PREPARED = "isVpnPrepared"
        private const val METHOD_GET_CONNECTION_INFO = "getConnectionInfo"
        private const val METHOD_SET_KILL_SWITCH = "setKillSwitch"
        private const val METHOD_SET_DNS_SERVERS = "setDnsServers"
        private const val METHOD_SET_MTU = "setMtu"
        private const val METHOD_START_SPEED_TEST = "startSpeedTest"
        private const val METHOD_STOP_SPEED_TEST = "stopSpeedTest"
    }

    // Channels
    private lateinit var methodChannel: MethodChannel
    private var stateEventSink: EventChannel.EventSink? = null
    private var trafficEventSink: EventChannel.EventSink? = null
    private var eventsEventSink: EventChannel.EventSink? = null

    // Coroutine scope for async operations
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // VPN Service Manager
    private lateinit var vpnManager: VpnServiceManager
    
    // Pending configuration for after permission grant
    private var pendingConfig: JSONObject? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        Log.d(TAG, "Configuring Flutter Engine with native bridge")
        
        // Initialize VPN Manager
        vpnManager = VpnServiceManager.getInstance(this)
        
        // Setup Method Channel
        setupMethodChannel(flutterEngine)
        
        // Setup Event Channels
        setupEventChannels(flutterEngine)
        
        // Observe VPN state changes
        observeVpnState()
    }

    // ============================================
    // METHOD CHANNEL SETUP
    // ============================================

    private fun setupMethodChannel(flutterEngine: FlutterEngine) {
        methodChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            METHOD_CHANNEL
        )
        
        methodChannel.setMethodCallHandler { call, result ->
            Log.d(TAG, "Method call received: ${call.method}")
            
            when (call.method) {
                // Permission methods
                METHOD_HAS_PERMISSION -> handleHasPermission(result)
                METHOD_REQUEST_PERMISSION -> handleRequestPermission(result)
                METHOD_PREPARE_VPN -> handlePrepareVpn(result)
                METHOD_IS_VPN_PREPARED -> handleIsVpnPrepared(result)
                
                // Connection methods
                METHOD_CONNECT -> handleConnect(call.arguments, result)
                METHOD_DISCONNECT -> handleDisconnect(result)
                METHOD_GET_STATUS -> handleGetStatus(result)
                METHOD_GET_STATS -> handleGetStats(result)
                METHOD_GET_CONNECTION_INFO -> handleGetConnectionInfo(result)
                
                // Configuration methods
                METHOD_CONFIGURE -> handleConfigure(call.arguments, result)
                METHOD_SET_HEADERS -> handleSetHeaders(call.arguments, result)
                METHOD_SET_SNI -> handleSetSni(call.arguments, result)
                METHOD_SET_KILL_SWITCH -> handleSetKillSwitch(call.arguments, result)
                METHOD_SET_DNS_SERVERS -> handleSetDnsServers(call.arguments, result)
                METHOD_SET_MTU -> handleSetMtu(call.arguments, result)
                
                // Speed test methods
                METHOD_START_SPEED_TEST -> handleStartSpeedTest(result)
                METHOD_STOP_SPEED_TEST -> handleStopSpeedTest(result)
                
                else -> {
                    Log.w(TAG, "Unknown method: ${call.method}")
                    result.notImplemented()
                }
            }
        }
    }

    // ============================================
    // EVENT CHANNELS SETUP
    // ============================================

    private fun setupEventChannels(flutterEngine: FlutterEngine) {
        // State Event Channel
        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            STATE_EVENT_CHANNEL
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                stateEventSink = events
                Log.d(TAG, "State event channel listener attached")
            }
            
            override fun onCancel(arguments: Any?) {
                stateEventSink = null
                Log.d(TAG, "State event channel listener detached")
            }
        })
        
        // Traffic Event Channel
        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            TRAFFIC_EVENT_CHANNEL
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                trafficEventSink = events
                Log.d(TAG, "Traffic event channel listener attached")
            }
            
            override fun onCancel(arguments: Any?) {
                trafficEventSink = null
                Log.d(TAG, "Traffic event channel listener detached")
            }
        })
        
        // Events Event Channel
        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            EVENTS_EVENT_CHANNEL
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                eventsEventSink = events
                Log.d(TAG, "Events event channel listener attached")
            }
            
            override fun onCancel(arguments: Any?) {
                eventsEventSink = null
                Log.d(TAG, "Events event channel listener detached")
            }
        })
    }

    // ============================================
    // VPN STATE OBSERVATION
    // ============================================

    private fun observeVpnState() {
        mainScope.launch {
            vpnManager.stateFlow.collectLatest { state ->
                stateEventSink?.success(state.toJson())
            }
        }
        
        mainScope.launch {
            vpnManager.trafficFlow.collectLatest { traffic ->
                trafficEventSink?.success(traffic.toJson())
            }
        }
        
        mainScope.launch {
            vpnManager.eventsFlow.collectLatest { event ->
                eventsEventSink?.success(event.toJson())
            }
        }
    }

    // ============================================
    // PERMISSION HANDLERS
    // ============================================

    private fun handleHasPermission(result: MethodChannel.Result) {
        try {
            val hasPermission = vpnManager.hasVpnPermission()
            Log.d(TAG, "hasVpnPermission: $hasPermission")
            result.success(hasPermission)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking VPN permission", e)
            result.error("PERMISSION_ERROR", e.message, null)
        }
    }

    private fun handleRequestPermission(result: MethodChannel.Result) {
        try {
            if (vpnManager.hasVpnPermission()) {
                Log.d(TAG, "VPN permission already granted")
                result.success(true)
                return
            }
            
            val intent = VpnService.prepare(this)
            if (intent != null) {
                // Need to show permission dialog
                startActivityForResult(intent, VPN_PERMISSION_REQUEST_CODE)
                // Result will be sent in onActivityResult
                pendingResult = result
            } else {
                // Permission already granted
                result.success(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting VPN permission", e)
            result.error("PERMISSION_ERROR", e.message, null)
        }
    }

    private var pendingResult: MethodChannel.Result? = null

    private fun handlePrepareVpn(result: MethodChannel.Result) {
        try {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                startActivityForResult(intent, VPN_PERMISSION_REQUEST_CODE)
                pendingResult = result
            } else {
                result.success(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing VPN", e)
            result.error("VPN_PREPARE_ERROR", e.message, null)
        }
    }

    private fun handleIsVpnPrepared(result: MethodChannel.Result) {
        try {
            val isPrepared = VpnService.prepare(this) == null
            result.success(isPrepared)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking VPN prepared", e)
            result.error("VPN_CHECK_ERROR", e.message, null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == VPN_PERMISSION_REQUEST_CODE) {
            val granted = resultCode == Activity.RESULT_OK
            Log.d(TAG, "VPN permission result: $granted")
            
            pendingResult?.success(granted)
            pendingResult = null
            
            // If permission granted and we have pending config, start connection
            if (granted && pendingConfig != null) {
                vpnManager.connect(pendingConfig!!)
                pendingConfig = null
            }
            
            // Notify via events channel
            sendEvent(
                type = if (granted) "permissionGranted" else "permissionDenied",
                message = if (granted) "VPN permission granted" else "VPN permission denied"
            )
        }
    }

    // ============================================
    // CONNECTION HANDLERS
    // ============================================

    private fun handleConnect(arguments: Any?, result: MethodChannel.Result) {
        try {
            val configJson = arguments as? Map<*, *>
                ?: throw IllegalArgumentException("Invalid configuration format")
            
            val config = JSONObject(configJson)
            Log.d(TAG, "Connect request with config: ${config.toString(2)}")
            
            // Check if VPN permission is granted
            if (!vpnManager.hasVpnPermission()) {
                Log.d(TAG, "VPN permission not granted, requesting...")
                pendingConfig = config
                val intent = VpnService.prepare(this)
                if (intent != null) {
                    startActivityForResult(intent, VPN_PERMISSION_REQUEST_CODE)
                    pendingResult = result
                    return
                }
            }
            
            // Start connection
            val connectResult = vpnManager.connect(config)
            
            result.success(mapOf(
                "success" to connectResult.success,
                "error" to connectResult.error,
                "errorCode" to connectResult.errorCode
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting VPN", e)
            result.error("CONNECT_ERROR", e.message, null)
        }
    }

    private fun handleDisconnect(result: MethodChannel.Result) {
        try {
            Log.d(TAG, "Disconnect request")
            val success = vpnManager.disconnect()
            result.success(success)
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting VPN", e)
            result.error("DISCONNECT_ERROR", e.message, null)
        }
    }

    private fun handleGetStatus(result: MethodChannel.Result) {
        try {
            val status = vpnManager.getStatus()
            result.success(status.toJson())
        } catch (e: Exception) {
            Log.e(TAG, "Error getting status", e)
            result.error("STATUS_ERROR", e.message, null)
        }
    }

    private fun handleGetStats(result: MethodChannel.Result) {
        try {
            val stats = vpnManager.getTrafficStats()
            result.success(stats.toJson())
        } catch (e: Exception) {
            Log.e(TAG, "Error getting stats", e)
            result.error("STATS_ERROR", e.message, null)
        }
    }

    private fun handleGetConnectionInfo(result: MethodChannel.Result) {
        try {
            val info = vpnManager.getConnectionInfo()
            result.success(info.toJson())
        } catch (e: Exception) {
            Log.e(TAG, "Error getting connection info", e)
            result.error("CONNECTION_INFO_ERROR", e.message, null)
        }
    }

    // ============================================
    // CONFIGURATION HANDLERS
    // ============================================

    private fun handleConfigure(arguments: Any?, result: MethodChannel.Result) {
        try {
            val configMap = arguments as? Map<*, *>
                ?: throw IllegalArgumentException("Invalid configuration format")
            
            val config = JSONObject(configMap)
            Log.d(TAG, "Configure request: ${config.toString(2)}")
            
            val success = vpnManager.configure(config)
            result.success(success)
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring VPN", e)
            result.error("CONFIG_ERROR", e.message, null)
        }
    }

    private fun handleSetHeaders(arguments: Any?, result: MethodChannel.Result) {
        try {
            val argsMap = arguments as? Map<*, *>
            val headersList = argsMap?.get("headers") as? List<*>
            
            val headersJson = JSONObject()
            val headersArray = org.json.JSONArray()
            
            headersList?.forEach { header ->
                val headerMap = header as? Map<*, *>
                if (headerMap != null) {
                    val headerObj = JSONObject()
                    headerObj.put("name", headerMap["name"] as? String ?: "")
                    headerObj.put("value", headerMap["value"] as? String ?: "")
                    headerObj.put("enabled", headerMap["enabled"] as? Boolean ?: true)
                    headersArray.put(headerObj)
                }
            }
            
            headersJson.put("headers", headersArray)
            Log.d(TAG, "Setting HTTP headers: ${headersJson.toString(2)}")
            
            val success = vpnManager.setHttpHeaders(headersJson)
            result.success(success)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting HTTP headers", e)
            result.error("HEADERS_ERROR", e.message, null)
        }
    }

    private fun handleSetSni(arguments: Any?, result: MethodChannel.Result) {
        try {
            val argsMap = arguments as? Map<*, *>
            
            val sniConfig = JSONObject()
            sniConfig.put("serverName", argsMap?.get("serverName") as? String ?: "")
            sniConfig.put("enabled", argsMap?.get("enabled") as? Boolean ?: true)
            sniConfig.put("allowOverride", argsMap?.get("allowOverride") as? Boolean ?: false)
            
            Log.d(TAG, "Setting SNI config: ${sniConfig.toString(2)}")
            
            val success = vpnManager.setSniConfig(sniConfig)
            result.success(success)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting SNI config", e)
            result.error("SNI_ERROR", e.message, null)
        }
    }

    private fun handleSetKillSwitch(arguments: Any?, result: MethodChannel.Result) {
        try {
            val argsMap = arguments as? Map<*, *>
            val enabled = argsMap?.get("enabled") as? Boolean ?: false
            
            Log.d(TAG, "Setting kill switch: $enabled")
            
            val success = vpnManager.setKillSwitch(enabled)
            result.success(success)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting kill switch", e)
            result.error("KILL_SWITCH_ERROR", e.message, null)
        }
    }

    private fun handleSetDnsServers(arguments: Any?, result: MethodChannel.Result) {
        try {
            val argsMap = arguments as? Map<*, *>
            val servers = (argsMap?.get("servers") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            
            Log.d(TAG, "Setting DNS servers: $servers")
            
            val success = vpnManager.setDnsServers(servers)
            result.success(success)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting DNS servers", e)
            result.error("DNS_ERROR", e.message, null)
        }
    }

    private fun handleSetMtu(arguments: Any?, result: MethodChannel.Result) {
        try {
            val argsMap = arguments as? Map<*, *>
            val mtu = (argsMap?.get("mtu") as? Number)?.toInt() ?: 1500
            
            Log.d(TAG, "Setting MTU: $mtu")
            
            val success = vpnManager.setMtu(mtu)
            result.success(success)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting MTU", e)
            result.error("MTU_ERROR", e.message, null)
        }
    }

    // ============================================
    // SPEED TEST HANDLERS
    // ============================================

    private fun handleStartSpeedTest(result: MethodChannel.Result) {
        try {
            Log.d(TAG, "Starting speed test")
            val success = vpnManager.startSpeedTest()
            result.success(success)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speed test", e)
            result.error("SPEED_TEST_ERROR", e.message, null)
        }
    }

    private fun handleStopSpeedTest(result: MethodChannel.Result) {
        try {
            Log.d(TAG, "Stopping speed test")
            val success = vpnManager.stopSpeedTest()
            result.success(success)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping speed test", e)
            result.error("SPEED_TEST_ERROR", e.message, null)
        }
    }

    // ============================================
    // HELPER METHODS
    // ============================================

    private fun sendEvent(type: String, message: String, data: Map<String, Any>? = null) {
        try {
            val eventJson = JSONObject()
            eventJson.put("type", type)
            eventJson.put("message", message)
            eventJson.put("timestamp", System.currentTimeMillis())
            
            if (data != null) {
                val dataJson = JSONObject(data)
                eventJson.put("data", dataJson)
            }
            
            eventsEventSink?.success(eventJson.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending event", e)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")
        mainScope.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save any pending configuration
        pendingConfig?.let {
            outState.putString("pendingConfig", it.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restore pending configuration if any
        savedInstanceState?.getString("pendingConfig")?.let {
            pendingConfig = JSONObject(it)
        }
    }
}
