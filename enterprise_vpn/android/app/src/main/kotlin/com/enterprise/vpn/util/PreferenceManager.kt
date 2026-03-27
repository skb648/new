package com.enterprise.vpn.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Preference Manager
 * 
 * Manages VPN preferences and settings.
 */
object PreferenceManager {
    
    private const val TAG = "PreferenceManager"
    private const val PREF_NAME = "enterprise_vpn_prefs"
    
    // Keys
    const val KEY_LAST_SERVER = "last_server"
    const val KEY_AUTO_CONNECT = "auto_connect"
    const val KEY_KILL_SWITCH = "kill_switch"
    const val KEY_DNS_LEAK = "dns_leak_protection"
    const val KEY_IPV6 = "ipv6_enabled"
    const val KEY_SPLIT_TUNNEL = "split_tunnel_enabled"
    const val KEY_MTU = "mtu"
    const val KEY_CUSTOM_HEADERS = "custom_headers"
    const val KEY_SNI_CONFIG = "sni_config"
    const val KEY_TOTAL_TRAFFIC = "total_traffic_bytes"
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
    
    fun saveString(context: Context, key: String, value: String) {
        try {
            getPrefs(context).edit().putString(key, value).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save string preference", e)
        }
    }
    
    fun getString(context: Context, key: String, defaultValue: String = ""): String {
        return try {
            getPrefs(context).getString(key, defaultValue) ?: defaultValue
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get string preference", e)
            defaultValue
        }
    }
    
    fun saveBoolean(context: Context, key: String, value: Boolean) {
        try {
            getPrefs(context).edit().putBoolean(key, value).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save boolean preference", e)
        }
    }
    
    fun getBoolean(context: Context, key: String, defaultValue: Boolean = false): Boolean {
        return try {
            getPrefs(context).getBoolean(key, defaultValue)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get boolean preference", e)
            defaultValue
        }
    }
    
    fun saveInt(context: Context, key: String, value: Int) {
        try {
            getPrefs(context).edit().putInt(key, value).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save int preference", e)
        }
    }
    
    fun getInt(context: Context, key: String, defaultValue: Int = 0): Int {
        return try {
            getPrefs(context).getInt(key, defaultValue)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get int preference", e)
            defaultValue
        }
    }
    
    fun saveLong(context: Context, key: String, value: Long) {
        try {
            getPrefs(context).edit().putLong(key, value).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save long preference", e)
        }
    }
    
    fun getLong(context: Context, key: String, defaultValue: Long = 0L): Long {
        return try {
            getPrefs(context).getLong(key, defaultValue)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get long preference", e)
            defaultValue
        }
    }
    
    fun clearAll(context: Context) {
        try {
            getPrefs(context).edit().clear().apply()
            Log.i(TAG, "All preferences cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear preferences", e)
        }
    }
}
