package com.enterprise.vpn.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.enterprise.vpn.service.EnterpriseVpnService
import com.enterprise.vpn.util.PreferenceManager

/**
 * Boot Completed Receiver
 * 
 * Handles device boot events and auto-starts VPN if configured.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Received broadcast: ${intent.action}")
        
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                handleBootCompleted(context)
            }
        }
    }
    
    private fun handleBootCompleted(context: Context) {
        Log.i(TAG, "Boot completed, checking auto-connect setting")
        
        val autoConnect = PreferenceManager.getBoolean(context, PreferenceManager.KEY_AUTO_CONNECT, false)
        
        if (autoConnect) {
            Log.i(TAG, "Auto-connect enabled, starting VPN service")
            
            // Start VPN service
            val serviceIntent = Intent(context, EnterpriseVpnService::class.java).apply {
                action = EnterpriseVpnService.ACTION_CONNECT
            }
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } else {
            Log.i(TAG, "Auto-connect disabled, not starting VPN")
        }
    }
}
