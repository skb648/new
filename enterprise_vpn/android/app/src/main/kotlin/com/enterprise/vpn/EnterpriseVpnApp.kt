package com.enterprise.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.enterprise.vpn.receiver.BootCompletedReceiver
import com.enterprise.vpn.service.VpnServiceManager
import com.enterprise.vpn.util.CrashHandler
import com.enterprise.vpn.util.PreferenceManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Enterprise VPN Application
 * 
 * Main application class that initializes:
 * - Hilt dependency injection
 * - WorkManager for background tasks
 * - Notification channels
 * - Crash handling
 * - VPN service manager
 */
@HiltAndroidApp
class EnterpriseVpnApp : Application(), Configuration.Provider {

    companion object {
        private const val TAG = "EnterpriseVpnApp"
        
        // Notification channel IDs
        const val CHANNEL_VPN_STATUS = "vpn_status"
        const val CHANNEL_VPN_ALERTS = "vpn_alerts"
        const val CHANNEL_BACKGROUND = "vpn_background"
        
        @Volatile
        private var instance: EnterpriseVpnApp? = null
        
        fun getInstance(): EnterpriseVpnApp {
            return instance ?: throw IllegalStateException("Application not initialized")
        }
    }

    // Hilt worker factory
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // Application scope
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Preferences
    private lateinit var preferenceManager: PreferenceManager

    override fun onCreate() {
        super.onCreate()
        
        instance = this
        
        Log.d(TAG, "Enterprise VPN Application starting...")
        
        // Initialize crash handler first
        CrashHandler.initialize(this)
        
        // Initialize preferences
        preferenceManager = PreferenceManager(this)
        
        // Create notification channels
        createNotificationChannels()
        
        // Initialize WorkManager
        initializeWorkManager()
        
        // Initialize VPN service manager
        initializeVpnManager()
        
        // Check for auto-start on boot
        checkAutoStart()
        
        Log.d(TAG, "Enterprise VPN Application initialized successfully")
    }

    /**
     * Create notification channels for Android O+
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            
            // VPN Status Channel - Low importance (ongoing connection)
            val statusChannel = NotificationChannel(
                CHANNEL_VPN_STATUS,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
            }
            
            // VPN Alerts Channel - High importance (errors, warnings)
            val alertsChannel = NotificationChannel(
                CHANNEL_VPN_ALERTS,
                "VPN Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Important VPN alerts and notifications"
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                enableLights(true)
                lightColor = getColor(R.color.primaryColor)
            }
            
            // Background Tasks Channel - Min importance
            val backgroundChannel = NotificationChannel(
                CHANNEL_BACKGROUND,
                "Background Tasks",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Background VPN maintenance tasks"
                setShowBadge(false)
                setSound(null, null)
            }
            
            notificationManager.createNotificationChannels(
                listOf(statusChannel, alertsChannel, backgroundChannel)
            )
            
            Log.d(TAG, "Notification channels created")
        }
    }

    /**
     * Initialize WorkManager for background tasks
     */
    private fun initializeWorkManager() {
        try {
            WorkManager.initialize(
                this,
                Configuration.Builder()
                    .setWorkerFactory(workerFactory)
                    .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
                    .build()
            )
            Log.d(TAG, "WorkManager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WorkManager", e)
        }
    }

    /**
     * Initialize VPN service manager
     */
    private fun initializeVpnManager() {
        applicationScope.launch {
            try {
                val manager = VpnServiceManager.getInstance(this@EnterpriseVpnApp)
                Log.d(TAG, "VPN Service Manager initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize VPN Service Manager", e)
            }
        }
    }

    /**
     * Check if auto-start on boot is enabled
     */
    private fun checkAutoStart() {
        val autoStart = preferenceManager.getBoolean("auto_start_on_boot", false)
        val autoConnect = preferenceManager.getBoolean("auto_connect_on_boot", false)
        
        Log.d(TAG, "Auto-start on boot: $autoStart, Auto-connect: $autoConnect")
        
        if (autoStart && autoConnect) {
            // Schedule auto-connect after boot
            BootCompletedReceiver.scheduleAutoConnect(this)
        }
    }

    /**
     * Get preference manager
     */
    fun getPreferenceManager(): PreferenceManager = preferenceManager

    override fun getWorkManagerConfiguration(): Configuration {
        return Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    }

    override fun onTerminate() {
        super.onTerminate()
        applicationScope.cancel()
        Log.d(TAG, "Application terminated")
    }
}
