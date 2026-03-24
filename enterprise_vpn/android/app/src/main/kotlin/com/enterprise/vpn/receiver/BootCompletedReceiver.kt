package com.enterprise.vpn.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.enterprise.vpn.service.EnterpriseVpnService
import com.enterprise.vpn.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Boot Completed Receiver
 * 
 * Handles device boot events and optionally starts VPN connection.
 * Supports Android 14+ background restrictions.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val AUTO_CONNECT_DELAY_MS = 10000L // 10 seconds delay after boot
        
        /**
         * Schedule auto-connect after boot
         */
        fun scheduleAutoConnect(context: Context) {
            // This could use WorkManager for more reliable scheduling
            Log.d(TAG, "Auto-connect scheduled after boot")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Boot event received: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                handleBootCompleted(context)
            }
        }
    }

    private fun handleBootCompleted(context: Context) {
        val prefs = PreferenceManager(context)

        // Check if auto-start on boot is enabled
        val autoStart = prefs.getBoolean(PreferenceManager.KEY_AUTO_START_ON_BOOT, false)
        val autoConnect = prefs.getBoolean(PreferenceManager.KEY_AUTO_CONNECT_ON_BOOT, false)

        Log.d(TAG, "Auto-start on boot: $autoStart, Auto-connect: $autoConnect")

        if (autoStart && autoConnect) {
            // Use coroutine for delayed connection
            CoroutineScope(Dispatchers.Default).launch {
                delay(AUTO_CONNECT_DELAY_MS)
                startVpnConnection(context)
            }
        }
    }

    private fun startVpnConnection(context: Context) {
        Log.d(TAG, "Starting VPN connection after boot")

        try {
            // Get last used server configuration
            val prefs = PreferenceManager(context)
            val serverIp = prefs.getLastServerIp()
            val serverPort = prefs.getLastServerPort()

            if (serverIp.isEmpty()) {
                Log.w(TAG, "No last server found, skipping auto-connect")
                return
            }

            // Build server configuration JSON
            val configJson = buildConfigJson(prefs, serverIp, serverPort)

            // Start VPN service
            val serviceIntent = Intent(context, EnterpriseVpnService::class.java).apply {
                action = EnterpriseVpnService.ACTION_CONNECT
                putExtra(EnterpriseVpnService.EXTRA_CONFIG, configJson)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            Log.d(TAG, "VPN service started with auto-connect")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN connection after boot", e)
        }
    }

    private fun buildConfigJson(prefs: PreferenceManager, serverIp: String, serverPort: Int): String {
        val serverName = prefs.getString(PreferenceManager.KEY_LAST_SERVER_NAME, "VPN Server")
        val protocol = prefs.getString(PreferenceManager.KEY_LAST_SERVER_PROTOCOL, "TCP")
        val mtu = prefs.getInt(PreferenceManager.KEY_MTU_SIZE, 1500)
        val killSwitch = prefs.getBoolean(PreferenceManager.KEY_KILL_SWITCH, false)
        val ipv6Enabled = prefs.getBoolean(PreferenceManager.KEY_IPV6_ENABLED, false)

        return """
            {
                "server": {
                    "id": "last_server",
                    "name": "$serverName",
                    "serverIp": "$serverIp",
                    "port": $serverPort,
                    "protocol": "$protocol"
                },
                "mtu": $mtu,
                "killSwitch": $killSwitch,
                "ipv6Enabled": $ipv6Enabled,
                "autoConnect": true
            }
        """.trimIndent()
    }
}
