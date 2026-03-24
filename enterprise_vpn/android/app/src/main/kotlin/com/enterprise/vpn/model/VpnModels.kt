package com.enterprise.vpn.model

import org.json.JSONObject

/**
 * VPN State Model
 * 
 * Represents the complete state of the VPN connection including
 * server info, traffic stats, and connection details.
 */
data class VpnState(
    val state: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val serverName: String = "",
    val serverIp: String = "",
    val serverPort: Int = 0,
    val protocol: String = "",
    val connectedAt: Long = 0L,
    val duration: Long = 0L,
    val bytesIn: Long = 0L,
    val bytesOut: Long = 0L,
    val speedDown: Long = 0L,
    val speedUp: Long = 0L,
    val latency: Int = 0,
    val errorMessage: String = "",
    val localIp: String = "",
    val remoteIp: String = ""
) {
    /**
     * Convert to JSON for transmission to Flutter
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("state", state.name.lowercase())
            put("serverName", serverName)
            put("serverIp", serverIp)
            put("serverPort", serverPort)
            put("protocol", protocol)
            put("connectedAt", if (connectedAt > 0) connectedAt else null)
            put("duration", duration)
            put("bytesIn", bytesIn)
            put("bytesOut", bytesOut)
            put("speedDown", speedDown)
            put("speedUp", speedUp)
            put("latency", if (latency > 0) latency else null)
            put("errorMessage", errorMessage.ifEmpty { null })
            put("localIp", localIp.ifEmpty { null })
            put("remoteIp", remoteIp.ifEmpty { null })
        }
    }

    /**
     * Check if VPN is connected
     */
    val isConnected: Boolean
        get() = state == VpnConnectionState.CONNECTED

    /**
     * Check if VPN is connecting
     */
    val isConnecting: Boolean
        get() = state == VpnConnectionState.CONNECTING ||
                state == VpnConnectionState.RECONNECTING ||
                state == VpnConnectionState.AUTHENTICATING
}

/**
 * Traffic Statistics Model
 */
data class TrafficStats(
    val bytesIn: Long = 0L,
    val bytesOut: Long = 0L,
    val speedDown: Long = 0L,
    val speedUp: Long = 0L
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("bytesIn", bytesIn)
            put("bytesOut", bytesOut)
            put("speedDown", speedDown)
            put("speedUp", speedUp)
        }
    }
}

/**
 * VPN Event Model
 */
data class VpnEvent(
    val type: String,
    val message: String,
    val timestamp: Long,
    val data: Map<String, Any>? = null
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("type", type)
            put("message", message)
            put("timestamp", timestamp)
            data?.let { put("data", JSONObject(it)) }
        }
    }
}

/**
 * Connection Info Model
 */
data class ConnectionInfo(
    val downloadSpeed: Long = 0L,
    val uploadSpeed: Long = 0L,
    val latency: Int = 0,
    val jitter: Int? = null,
    val packetLoss: Double? = null
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("downloadSpeed", downloadSpeed)
            put("uploadSpeed", uploadSpeed)
            put("latency", latency)
            put("jitter", jitter)
            put("packetLoss", packetLoss)
        }
    }
}

/**
 * VPN Connection State Enum
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
