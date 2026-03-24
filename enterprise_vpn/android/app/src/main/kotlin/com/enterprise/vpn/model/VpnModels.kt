package com.enterprise.vpn.model

import android.os.Parcel
import android.os.Parcelable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.InetAddress

/**
 * VPN Server Configuration
 * Contains all details needed to connect to external VPN server
 */
@Serializable
data class VpnServerConfig(
    val id: String = "",
    val name: String = "",
    val serverIp: String = "",
    val port: Int = 443,
    val protocol: String = "TCP",
    val country: String = "",
    val countryCode: String = "",
    val city: String = "",
    val load: Int = 0,
    val isPremium: Boolean = false,
    val isFavorite: Boolean = false,
    val username: String = "",
    val password: String = ""
) : Parcelable {
    
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readInt(),
        parcel.readString() ?: "TCP",
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readInt(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readString() ?: "",
        parcel.readString() ?: ""
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeString(name)
        parcel.writeString(serverIp)
        parcel.writeInt(port)
        parcel.writeString(protocol)
        parcel.writeString(country)
        parcel.writeString(countryCode)
        parcel.writeString(city)
        parcel.writeInt(load)
        parcel.writeByte(if (isPremium) 1 else 0)
        parcel.writeByte(if (isFavorite) 1 else 0)
        parcel.writeString(username)
        parcel.writeString(password)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<VpnServerConfig> {
        override fun createFromParcel(parcel: Parcel): VpnServerConfig = VpnServerConfig(parcel)
        override fun newArray(size: Int): Array<VpnServerConfig?> = arrayOfNulls(size)
        
        fun fromJson(json: String): VpnServerConfig {
            return try {
                Json { ignoreUnknownKeys = true }.decodeFromString(json)
            } catch (e: Exception) {
                VpnServerConfig()
            }
        }
    }

    fun toJson(): String = Json.encodeToString(this)

    fun getInetAddress(): InetAddress? {
        return try {
            InetAddress.getByName(serverIp)
        } catch (e: Exception) {
            null
        }
    }

    fun isValid(): Boolean {
        return serverIp.isNotEmpty() && port in 1..65535
    }
}

/**
 * HTTP Header for custom header injection
 */
@Serializable
data class HttpHeader(
    val name: String,
    val value: String,
    val enabled: Boolean = true
) : Parcelable {
    
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(name)
        parcel.writeString(value)
        parcel.writeByte(if (enabled) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<HttpHeader> {
        override fun createFromParcel(parcel: Parcel): HttpHeader = HttpHeader(parcel)
        override fun newArray(size: Int): Array<HttpHeader?> = arrayOfNulls(size)
    }
}

/**
 * SNI Configuration for TLS connections
 */
@Serializable
data class SniConfig(
    val serverName: String = "",
    val enabled: Boolean = true,
    val allowOverride: Boolean = false
) : Parcelable {
    
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(serverName)
        parcel.writeByte(if (enabled) 1 else 0)
        parcel.writeByte(if (allowOverride) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<SniConfig> {
        override fun createFromParcel(parcel: Parcel): SniConfig = SniConfig(parcel)
        override fun newArray(size: Int): Array<SniConfig?> = arrayOfNulls(size)
    }
}

/**
 * Complete VPN Configuration
 */
@Serializable
data class VpnConfig(
    val server: VpnServerConfig? = null,
    val httpHeaders: List<HttpHeader> = emptyList(),
    val sniConfig: SniConfig? = null,
    val autoConnect: Boolean = false,
    val killSwitch: Boolean = false,
    val dnsLeakProtection: Boolean = true,
    val ipv6Enabled: Boolean = false,
    val splitTunnelEnabled: Boolean = false,
    val excludedApps: List<String> = emptyList(),
    val customDns: List<String> = emptyList(),
    val mtu: Int = 1500
) : Parcelable {
    
    constructor(parcel: Parcel) : this(
        parcel.readParcelable(VpnServerConfig::class.java.classLoader),
        parcel.createTypedArrayList(HttpHeader.CREATOR) ?: emptyList(),
        parcel.readParcelable(SniConfig::class.java.classLoader),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.createStringArrayList() ?: emptyList(),
        parcel.createStringArrayList() ?: emptyList(),
        parcel.readInt()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(server, flags)
        parcel.writeTypedList(httpHeaders)
        parcel.writeParcelable(sniConfig, flags)
        parcel.writeByte(if (autoConnect) 1 else 0)
        parcel.writeByte(if (killSwitch) 1 else 0)
        parcel.writeByte(if (dnsLeakProtection) 1 else 0)
        parcel.writeByte(if (ipv6Enabled) 1 else 0)
        parcel.writeByte(if (splitTunnelEnabled) 1 else 0)
        parcel.writeStringList(excludedApps)
        parcel.writeStringList(customDns)
        parcel.writeInt(mtu)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<VpnConfig> {
        override fun createFromParcel(parcel: Parcel): VpnConfig = VpnConfig(parcel)
        override fun newArray(size: Int): Array<VpnConfig?> = arrayOfNulls(size)
        
        fun fromMap(map: Map<String, Any?>): VpnConfig {
            val serverMap = map["server"] as? Map<String, Any?>
            val server = if (serverMap != null) {
                VpnServerConfig(
                    id = serverMap["id"] as? String ?: "",
                    name = serverMap["name"] as? String ?: "",
                    serverIp = serverMap["serverIp"] as? String ?: "",
                    port = (serverMap["port"] as? Number)?.toInt() ?: 443,
                    protocol = serverMap["protocol"] as? String ?: "TCP",
                    username = serverMap["username"] as? String ?: "",
                    password = serverMap["password"] as? String ?: ""
                )
            } else null

            val headersList = (map["httpHeaders"] as? List<Map<String, Any?>>)?.map {
                HttpHeader(
                    name = it["name"] as? String ?: "",
                    value = it["value"] as? String ?: "",
                    enabled = it["enabled"] as? Boolean ?: true
                )
            } ?: emptyList()

            val sniMap = map["sniConfig"] as? Map<String, Any?>
            val sniConfig = if (sniMap != null) {
                SniConfig(
                    serverName = sniMap["serverName"] as? String ?: "",
                    enabled = sniMap["enabled"] as? Boolean ?: true,
                    allowOverride = sniMap["allowOverride"] as? Boolean ?: false
                )
            } else null

            return VpnConfig(
                server = server,
                httpHeaders = headersList,
                sniConfig = sniConfig,
                autoConnect = map["autoConnect"] as? Boolean ?: false,
                killSwitch = map["killSwitch"] as? Boolean ?: false,
                dnsLeakProtection = map["dnsLeakProtection"] as? Boolean ?: true,
                ipv6Enabled = map["ipv6Enabled"] as? Boolean ?: false,
                splitTunnelEnabled = map["splitTunnelEnabled"] as? Boolean ?: false,
                excludedApps = (map["excludedApps"] as? List<String>) ?: emptyList(),
                customDns = (map["customDns"] as? List<String>) ?: emptyList(),
                mtu = (map["mtu"] as? Number)?.toInt() ?: 1500
            )
        }

        fun fromJson(json: String): VpnConfig {
            return try {
                Json { ignoreUnknownKeys = true }.decodeFromString(json)
            } catch (e: Exception) {
                VpnConfig()
            }
        }
    }

    fun toJson(): String = Json.encodeToString(this)
    fun isValid(): Boolean = server != null && server!!.isValid()
    val enabledHeaders: List<HttpHeader> get() = httpHeaders.filter { it.enabled }
}

/**
 * VPN Connection State
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

/**
 * VPN Status for real-time updates
 */
data class VpnStatus(
    val state: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val serverName: String? = null,
    val serverIp: String? = null,
    val serverPort: Int? = null,
    val protocol: String? = null,
    val connectedAt: Long? = null,
    val duration: Long = 0,
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val currentSpeedDown: Long = 0,
    val currentSpeedUp: Long = 0,
    val latency: Int? = null,
    val errorMessage: String? = null,
    val localIp: String? = null,
    val remoteIp: String? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "state" to state.name.lowercase(),
        "serverName" to serverName,
        "serverIp" to serverIp,
        "serverPort" to serverPort,
        "protocol" to protocol,
        "connectedAt" to connectedAt,
        "duration" to duration,
        "bytesIn" to bytesIn,
        "bytesOut" to bytesOut,
        "speedDown" to currentSpeedDown,
        "speedUp" to currentSpeedUp,
        "latency" to latency,
        "errorMessage" to errorMessage,
        "localIp" to localIp,
        "remoteIp" to remoteIp
    )

    fun toJson(): String = Json.encodeToString(serializer(), this)
}

/**
 * Traffic Statistics
 */
data class TrafficStats(
    val timestamp: Long = System.currentTimeMillis(),
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val speedDown: Long = 0,
    val speedUp: Long = 0
) {
    fun toMap(): Map<String, Any> = mapOf(
        "timestamp" to timestamp,
        "bytesIn" to bytesIn,
        "bytesOut" to bytesOut,
        "speedDown" to speedDown,
        "speedUp" to speedUp
    )
}

/**
 * VPN Event Type
 */
enum class VpnEventType {
    UNKNOWN,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    DISCONNECTED,
    RECONNECTING,
    ERROR,
    PERMISSION_REQUIRED,
    CONFIGURATION_CHANGED,
    SERVER_CHANGED
}

/**
 * VPN Event for notifying Flutter
 */
data class VpnEvent(
    val type: VpnEventType,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val data: Map<String, Any?>? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "type" to type.name.lowercase(),
        "message" to message,
        "timestamp" to timestamp,
        "data" to data
    )
}

/**
 * Connection Result
 */
data class ConnectionResult(
    val success: Boolean,
    val error: String? = null,
    val errorCode: String? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "success" to success,
        "error" to error,
        "errorCode" to errorCode
    )
}
