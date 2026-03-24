package com.enterprise.vpn.packet

/**
 * IP Packet Model
 * 
 * Represents a parsed IP packet with all relevant fields.
 * Used for packet processing and routing decisions.
 */
data class IpPacket(
    // IP header fields
    val version: Int,
    val headerLength: Int,
    val totalLength: Int,
    val identification: Int,
    val flags: Int,
    val fragmentOffset: Int,
    val ttl: Int,
    val protocol: Int,
    
    // Addresses
    val srcIp: String,
    val dstIp: String,
    
    // Transport layer ports
    val srcPort: Int = 0,
    val dstPort: Int = 0,
    
    // Payload
    val payload: ByteArray,
    
    // Raw data for forwarding
    val rawData: ByteArray = ByteArray(0)
) {
    companion object {
        // Protocol constants
        const val PROTOCOL_ICMP = 1
        const val PROTOCOL_TCP = 6
        const val PROTOCOL_UDP = 17
        const val PROTOCOL_GRE = 47
        const val PROTOCOL_ESP = 50
    }

    /**
     * Check if this is a TCP packet
     */
    val isTcp: Boolean
        get() = protocol == PROTOCOL_TCP

    /**
     * Check if this is a UDP packet
     */
    val isUdp: Boolean
        get() = protocol == PROTOCOL_UDP

    /**
     * Check if this is an ICMP packet
     */
    val isIcmp: Boolean
        get() = protocol == PROTOCOL_ICMP

    /**
     * Get the connection key for tracking
     */
    fun getConnectionKey(): String {
        return "$srcIp:$srcPort->$dstIp:$dstPort:$protocol"
    }

    /**
     * Get reverse connection key
     */
    fun getReverseConnectionKey(): String {
        return "$dstIp:$dstPort->$srcIp:$srcPort:$protocol"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IpPacket

        if (version != other.version) return false
        if (headerLength != other.headerLength) return false
        if (totalLength != other.totalLength) return false
        if (identification != other.identification) return false
        if (flags != other.flags) return false
        if (fragmentOffset != other.fragmentOffset) return false
        if (ttl != other.ttl) return false
        if (protocol != other.protocol) return false
        if (srcIp != other.srcIp) return false
        if (dstIp != other.dstIp) return false
        if (srcPort != other.srcPort) return false
        if (dstPort != other.dstPort) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + headerLength
        result = 31 * result + totalLength
        result = 31 * result + identification
        result = 31 * result + flags
        result = 31 * result + fragmentOffset
        result = 31 * result + ttl
        result = 31 * result + protocol
        result = 31 * result + srcIp.hashCode()
        result = 31 * result + dstIp.hashCode()
        result = 31 * result + srcPort
        result = 31 * result + dstPort
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * TCP Header Constants and Flags
 */
object TcpHeader {
    // TCP Flags
    const val FLAG_FIN = 0x01
    const val FLAG_SYN = 0x02
    const val FLAG_RST = 0x04
    const val FLAG_PSH = 0x08
    const val FLAG_ACK = 0x10
    const val FLAG_URG = 0x20
    const val FLAG_ECE = 0x40
    const val FLAG_CWR = 0x80

    // TCP States
    const val STATE_CLOSED = 0
    const val STATE_LISTEN = 1
    const val STATE_SYN_SENT = 2
    const val STATE_SYN_RECEIVED = 3
    const val STATE_ESTABLISHED = 4
    const val STATE_FIN_WAIT_1 = 5
    const val STATE_FIN_WAIT_2 = 6
    const val STATE_CLOSE_WAIT = 7
    const val STATE_CLOSING = 8
    const val STATE_LAST_ACK = 9
    const val STATE_TIME_WAIT = 10
}

/**
 * UDP Header Constants
 */
object UdpHeader {
    const val HEADER_SIZE = 8
}

/**
 * IP Header Constants
 */
object IpHeader {
    const val MIN_HEADER_SIZE = 20
    const val MAX_HEADER_SIZE = 60
}
