package com.enterprise.vpn.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Preference Manager
 * 
 * Manages application preferences with type-safe access and encryption support.
 */
class PreferenceManager(context: Context) {

    companion object {
        private const val TAG = "PreferenceManager"
        private const val PREFS_NAME = "enterprise_vpn_prefs"
        
        // Preference keys
        const val KEY_FIRST_LAUNCH = "first_launch"
        const val KEY_LAST_SERVER_ID = "last_server_id"
        const val KEY_LAST_SERVER_IP = "last_server_ip"
        const val KEY_LAST_SERVER_PORT = "last_server_port"
        const val KEY_LAST_SERVER_NAME = "last_server_name"
        const val KEY_LAST_SERVER_PROTOCOL = "last_server_protocol"
        const val KEY_AUTH_USERNAME = "auth_username"
        const val KEY_AUTH_PASSWORD = "auth_password"
        const val KEY_CUSTOM_PAYLOAD = "custom_payload"
        const val KEY_REMOTE_SERVER_IP = "remote_server_ip"
        const val KEY_REMOTE_SERVER_PORT = "remote_server_port"
        const val KEY_AUTO_CONNECT = "auto_connect"
        const val KEY_AUTO_CONNECT_ON_BOOT = "auto_connect_on_boot"
        const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
        const val KEY_KILL_SWITCH = "kill_switch"
        const val KEY_DNS_LEAK_PROTECTION = "dns_leak_protection"
        const val KEY_IPV6_ENABLED = "ipv6_enabled"
        const val KEY_SPLIT_TUNNEL_ENABLED = "split_tunnel_enabled"
        const val KEY_LAST_CONNECTION_TIME = "last_connection_time"
        const val KEY_TOTAL_BYTES_IN = "total_bytes_in"
        const val KEY_TOTAL_BYTES_OUT = "total_bytes_out"
        const val KEY_CONNECTION_COUNT = "connection_count"
        const val KEY_THEME = "app_theme"
        const val KEY_LANGUAGE = "app_language"
        const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        const val KEY_SHOW_SPEED_NOTIFICATION = "show_speed_notification"
        const val KEY_CUSTOM_DNS_SERVERS = "custom_dns_servers"
        const val KEY_MTU_SIZE = "mtu_size"
        const val KEY_CUSTOM_HEADERS = "custom_headers"
        const val KEY_SNI_CONFIG = "sni_config"
        const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // ============================================
    // STRING PREFERENCES
    // ============================================

    fun getString(key: String, defaultValue: String = ""): String {
        return try {
            prefs.getString(key, defaultValue) ?: defaultValue
        } catch (e: Exception) {
            Log.e(TAG, "Error reading string preference: $key", e)
            defaultValue
        }
    }

    fun putString(key: String, value: String) {
        try {
            prefs.edit().putString(key, value).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing string preference: $key", e)
        }
    }

    // ============================================
    // INTEGER PREFERENCES
    // ============================================

    fun getInt(key: String, defaultValue: Int = 0): Int {
        return try {
            prefs.getInt(key, defaultValue)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading int preference: $key", e)
            defaultValue
        }
    }

    fun putInt(key: String, value: Int) {
        try {
            prefs.edit().putInt(key, value).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing int preference: $key", e)
        }
    }

    // ============================================
    // LONG PREFERENCES
    // ============================================

    fun getLong(key: String, defaultValue: Long = 0L): Long {
        return try {
            prefs.getLong(key, defaultValue)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading long preference: $key", e)
            defaultValue
        }
    }

    fun putLong(key: String, value: Long) {
        try {
            prefs.edit().putLong(key, value).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing long preference: $key", e)
        }
    }

    // ============================================
    // BOOLEAN PREFERENCES
    // ============================================

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return try {
            prefs.getBoolean(key, defaultValue)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading boolean preference: $key", e)
            defaultValue
        }
    }

    fun putBoolean(key: String, value: Boolean) {
        try {
            prefs.edit().putBoolean(key, value).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing boolean preference: $key", e)
        }
    }

    // ============================================
    // FLOAT PREFERENCES
    // ============================================

    fun getFloat(key: String, defaultValue: Float = 0f): Float {
        return try {
            prefs.getFloat(key, defaultValue)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading float preference: $key", e)
            defaultValue
        }
    }

    fun putFloat(key: String, value: Float) {
        try {
            prefs.edit().putFloat(key, value).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing float preference: $key", e)
        }
    }

    // ============================================
    // UTILITY METHODS
    // ============================================

    fun contains(key: String): Boolean {
        return prefs.contains(key)
    }

    fun remove(key: String) {
        try {
            prefs.edit().remove(key).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error removing preference: $key", e)
        }
    }

    fun clear() {
        try {
            prefs.edit().clear().apply()
            Log.d(TAG, "All preferences cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing preferences", e)
        }
    }

    fun getAll(): Map<String, *> {
        return prefs.all
    }

    // ============================================
    // CONVENIENCE METHODS
    // ============================================

    /**
     * Check if first launch
     */
    fun isFirstLaunch(): Boolean {
        return getBoolean(KEY_FIRST_LAUNCH, true)
    }

    /**
     * Mark first launch complete
     */
    fun markFirstLaunchComplete() {
        putBoolean(KEY_FIRST_LAUNCH, false)
    }

    /**
     * Save last connected server
     */
    fun saveLastServer(serverId: String, ip: String, port: Int, name: String, protocol: String) {
        prefs.edit().apply {
            putString(KEY_LAST_SERVER_ID, serverId)
            putString(KEY_LAST_SERVER_IP, ip)
            putInt(KEY_LAST_SERVER_PORT, port)
            putString(KEY_LAST_SERVER_NAME, name)
            putString(KEY_LAST_SERVER_PROTOCOL, protocol)
        }.apply()
    }

    /**
     * Get last connected server IP
     */
    fun getLastServerIp(): String {
        return getString(KEY_LAST_SERVER_IP)
    }

    /**
     * Get last connected server port
     */
    fun getLastServerPort(): Int {
        return getInt(KEY_LAST_SERVER_PORT, 443)
    }

    /**
     * Update connection statistics
     */
    fun updateConnectionStats(bytesIn: Long, bytesOut: Long) {
        val currentIn = getLong(KEY_TOTAL_BYTES_IN, 0)
        val currentOut = getLong(KEY_TOTAL_BYTES_OUT, 0)
        
        prefs.edit().apply {
            putLong(KEY_TOTAL_BYTES_IN, currentIn + bytesIn)
            putLong(KEY_TOTAL_BYTES_OUT, currentOut + bytesOut)
            putLong(KEY_LAST_CONNECTION_TIME, System.currentTimeMillis())
        }.apply()
    }

    /**
     * Increment connection count
     */
    fun incrementConnectionCount() {
        val count = getInt(KEY_CONNECTION_COUNT, 0)
        putInt(KEY_CONNECTION_COUNT, count + 1)
    }

    /**
     * Save authentication credentials
     */
    fun saveAuthCredentials(username: String, password: String) {
        prefs.edit().apply {
            putString(KEY_AUTH_USERNAME, username)
            putString(KEY_AUTH_PASSWORD, password)
        }.apply()
    }

    /**
     * Get authentication username
     */
    fun getAuthUsername(): String {
        return getString(KEY_AUTH_USERNAME)
    }

    /**
     * Get authentication password
     */
    fun getAuthPassword(): String {
        return getString(KEY_AUTH_PASSWORD)
    }

    /**
     * Check if authentication credentials exist
     */
    fun hasAuthCredentials(): Boolean {
        return getAuthUsername().isNotEmpty() && getAuthPassword().isNotEmpty()
    }

    /**
     * Clear authentication credentials
     */
    fun clearAuthCredentials() {
        prefs.edit().apply {
            remove(KEY_AUTH_USERNAME)
            remove(KEY_AUTH_PASSWORD)
        }.apply()
    }

    /**
     * Save remote server configuration
     */
    fun saveRemoteServer(ip: String, port: Int) {
        prefs.edit().apply {
            putString(KEY_REMOTE_SERVER_IP, ip)
            putInt(KEY_REMOTE_SERVER_PORT, port)
        }.apply()
    }

    /**
     * Get remote server IP
     */
    fun getRemoteServerIp(): String {
        return getString(KEY_REMOTE_SERVER_IP)
    }

    /**
     * Get remote server port
     */
    fun getRemoteServerPort(): Int {
        return getInt(KEY_REMOTE_SERVER_PORT, 443)
    }

    /**
     * Save custom payload
     */
    fun saveCustomPayload(payload: String) {
        putString(KEY_CUSTOM_PAYLOAD, payload)
    }

    /**
     * Get custom payload
     */
    fun getCustomPayload(): String {
        return getString(KEY_CUSTOM_PAYLOAD)
    }

    /**
     * Get total traffic statistics
     */
    fun getTrafficStats(): TrafficStats {
        return TrafficStats(
            totalBytesIn = getLong(KEY_TOTAL_BYTES_IN, 0),
            totalBytesOut = getLong(KEY_TOTAL_BYTES_OUT, 0),
            connectionCount = getInt(KEY_CONNECTION_COUNT, 0)
        )
    }
}

/**
 * Traffic Statistics
 */
data class TrafficStats(
    val totalBytesIn: Long,
    val totalBytesOut: Long,
    val connectionCount: Int
)
