package com.enterprise.vpn

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.enterprise.vpn.service.EnterpriseVpnService
import com.enterprise.vpn.service.VpnServiceManager
import com.enterprise.vpn.util.CrashHandler
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * Main Activity
 * 
 * Entry point for the Flutter application.
 * Handles VPN permission requests and MethodChannel communication.
 */
class MainActivity : FlutterActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        private const val VPN_CHANNEL = "com.enterprise.vpn/service"
        private const val VPN_PERMISSION_REQUEST = 1001
    }
    
    private var vpnServiceManager: VpnServiceManager? = null
    private var pendingMethodResult: MethodChannel.Result? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize crash handler
        CrashHandler.init(this)
        
        Log.i(TAG, "MainActivity created")
    }
    
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        Log.i(TAG, "Configuring Flutter engine")
        
        // Initialize VPN service manager
        vpnServiceManager = VpnServiceManager
        vpnServiceManager?.init(this, this, MethodChannel(flutterEngine.dartExecutor.binaryMessenger, VPN_CHANNEL))
        
        Log.i(TAG, "VPN service manager initialized")
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        Log.i(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")
        
        when (requestCode) {
            VPN_PERMISSION_REQUEST -> {
                val granted = resultCode == Activity.RESULT_OK
                Log.i(TAG, "VPN permission result: granted=$granted")
                
                pendingMethodResult?.let { result ->
                    if (granted) {
                        result.success(true)
                    } else {
                        result.error("PERMISSION_DENIED", "VPN permission denied by user", null)
                    }
                    pendingMethodResult = null
                }
            }
        }
    }
    
    override fun onDestroy() {
        Log.i(TAG, "MainActivity destroying")
        vpnServiceManager?.cleanup()
        vpnServiceManager = null
        super.onDestroy()
    }
    
    /**
     * Request VPN permission with result callback
     */
    fun requestVpnPermission(result: MethodChannel.Result) {
        pendingMethodResult = result
        
        try {
            val intent = android.net.VpnService.prepare(this)
            if (intent != null) {
                Log.i(TAG, "Requesting VPN permission from user")
                startActivityForResult(intent, VPN_PERMISSION_REQUEST)
            } else {
                Log.i(TAG, "VPN permission already granted")
                result.success(true)
                pendingMethodResult = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting VPN permission", e)
            result.error("PERMISSION_ERROR", "Failed to request VPN permission: ${e.message}", null)
            pendingMethodResult = null
        }
    }
}
