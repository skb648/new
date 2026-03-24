package com.enterprise.vpn.packet

import android.util.Log
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Packet Processor
 * 
 * Handles parsing and reconstruction of IP packets for the VPN tunnel.
 * Supports IPv4, TCP, UDP, and ICMP protocols.
 * 
 * IP Packet Structure (IPv4):
 * - Version (4 bits) + IHL (4 bits)
 * - Type of Service (8 bits)
 * - Total Length (16 bits)
 * - Identification (16 bits)
 * - Flags (3 bits) + Fragment Offset (13 bits)
 * - TTL (8 bits)
 * - Protocol (8 bits)
 * - Header Checksum (16 bits)
 * - Source IP (32 bits)
 * - Destination IP (32 bits)
 * - Options (variable)
 * - Payload
 */
class PacketProcessor {

    companion object {
        private const val TAG = "PacketProcessor"
        
        // IP header field offsets
        private const val IP_VERSION_OFFSET = 0
        private const val IP_TOS_OFFSET = 1
        private const val IP_TOTAL_LENGTH_OFFSET = 2
        private const val IP_ID_OFFSET = 4
        private const val IP_FLAGS_OFFSET = 6
        private const val IP_TTL_OFFSET = 8
        private const val IP_PROTOCOL_OFFSET = 9
        private const val IP_CHECKSUM_OFFSET = 10
        private const val IP_SRC_ADDR_OFFSET = 12
        private const val IP_DST_ADDR_OFFSET = 16
        
        // TCP header field offsets (from start of TCP header)
        private const val TCP_SRC_PORT_OFFSET = 0
        private const val TCP_DST_PORT_OFFSET = 2
        private const val TCP_SEQ_OFFSET = 4
        private const val TCP_ACK_OFFSET = 8
        private const val TCP_DATA_OFFSET = 12
        private const val TCP_FLAGS_OFFSET = 13
        private const val TCP_WINDOW_OFFSET = 14
        private const val TCP_CHECKSUM_OFFSET = 16
        private const val TCP_URGENT_OFFSET = 18
        
        // UDP header field offsets
        private const val UDP_SRC_PORT_OFFSET = 0
        private const val UDP_DST_PORT_OFFSET = 2
        private const val UDP_LENGTH_OFFSET = 4
        private const val UDP_CHECKSUM_OFFSET = 6
        private const val UDP_HEADER_SIZE = 8
        
        // Protocol numbers
        const val PROTOCOL_ICMP = 1
        const val PROTOCOL_TCP = 6
        const val PROTOCOL_UDP = 17
    }

    // Identification counter for outgoing packets
    private var ipIdentification = 0

    /**
     * Parse an IP packet from raw bytes
     */
    fun parsePacket(data: ByteArray, length: Int): IpPacket? {
        if (length < 20) {
            Log.w(TAG, "Packet too short: $length bytes")
            return null
        }
        
        try {
            val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
            
            // Parse IP version and header length
            val versionAndIhl = buffer.get(IP_VERSION_OFFSET).toInt() and 0xFF
            val version = (versionAndIhl shr 4) and 0x0F
            val ihl = versionAndIhl and 0x0F
            
            if (version != 4) {
                Log.d(TAG, "Non-IPv4 packet: version $version")
                return null
            }
            
            val headerLength = ihl * 4
            if (headerLength < 20 || headerLength > length) {
                Log.w(TAG, "Invalid header length: $headerLength")
                return null
            }
            
            // Parse IP header fields
            val totalLength = buffer.short.toInt() and 0xFFFF
            val identification = buffer.short.toInt() and 0xFFFF
            val flagsAndOffset = buffer.short.toInt() and 0xFFFF
            val flags = (flagsAndOffset shr 13) and 0x07
            val fragmentOffset = flagsAndOffset and 0x1FFF
            val ttl = buffer.get().toInt() and 0xFF
            val protocol = buffer.get().toInt() and 0xFF
            
            // Skip checksum
            buffer.short
            
            // Parse source and destination IPs
            val srcIpBytes = ByteArray(4)
            val dstIpBytes = ByteArray(4)
            buffer.get(srcIpBytes)
            buffer.get(dstIpBytes)
            
            val srcIp = bytesToIp(srcIpBytes)
            val dstIp = bytesToIp(dstIpBytes)
            
            // Extract payload
            val payloadLength = totalLength - headerLength
            val payload = if (payloadLength > 0) {
                val payloadBytes = ByteArray(payloadLength)
                System.arraycopy(data, headerLength, payloadBytes, 0, payloadLength)
                payloadBytes
            } else {
                ByteArray(0)
            }
            
            // Parse protocol-specific headers
            val (srcPort, dstPort, protocolPayload) = when (protocol) {
                PROTOCOL_TCP -> parseTcpHeader(payload)
                PROTOCOL_UDP -> parseUdpHeader(payload)
                else -> Triple(0, 0, payload)
            }
            
            return IpPacket(
                version = version,
                headerLength = headerLength,
                totalLength = totalLength,
                identification = identification,
                flags = flags,
                fragmentOffset = fragmentOffset,
                ttl = ttl,
                protocol = protocol,
                srcIp = srcIp,
                dstIp = dstIp,
                srcPort = srcPort,
                dstPort = dstPort,
                payload = payload,
                rawData = data.copyOf(length)
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing packet", e)
            return null
        }
    }

    /**
     * Parse TCP header from payload
     */
    private fun parseTcpHeader(payload: ByteArray): Triple<Int, Int, ByteArray> {
        if (payload.size < 20) return Triple(0, 0, payload)
        
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        
        val srcPort = buffer.short.toInt() and 0xFFFF
        val dstPort = buffer.short.toInt() and 0xFFFF
        
        // Skip sequence and acknowledgment numbers
        buffer.int
        buffer.int
        
        // Data offset (header length)
        val dataOffsetByte = buffer.get().toInt() and 0xFF
        val dataOffset = (dataOffsetByte shr 4) * 4
        
        // Extract TCP payload
        val tcpPayload = if (dataOffset < payload.size) {
            payload.copyOfRange(dataOffset, payload.size)
        } else {
            ByteArray(0)
        }
        
        return Triple(srcPort, dstPort, tcpPayload)
    }

    /**
     * Parse UDP header from payload
     */
    private fun parseUdpHeader(payload: ByteArray): Triple<Int, Int, ByteArray> {
        if (payload.size < 8) return Triple(0, 0, payload)
        
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        
        val srcPort = buffer.short.toInt() and 0xFFFF
        val dstPort = buffer.short.toInt() and 0xFFFF
        val length = buffer.short.toInt() and 0xFFFF
        
        // Extract UDP payload
        val udpPayload = if (length > 8 && length <= payload.size) {
            payload.copyOfRange(8, length)
        } else {
            ByteArray(0)
        }
        
        return Triple(srcPort, dstPort, udpPayload)
    }

    // ============================================
    // PACKET BUILDING
    // ============================================

    /**
     * Build a complete TCP/IP packet
     */
    fun buildTcpPacket(
        srcIp: String,
        dstIp: String,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        seq: Long = 0,
        ack: Long = 0,
        flags: Int = TcpHeader.FLAG_PSH or TcpHeader.FLAG_ACK,
        window: Int = 65535
    ): ByteArray {
        val tcpHeader = buildTcpHeader(
            srcPort = srcPort,
            dstPort = dstPort,
            seq = seq,
            ack = ack,
            flags = flags,
            window = window,
            payload = payload,
            srcIp = srcIp,
            dstIp = dstIp
        )
        
        return buildIpPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            protocol = PROTOCOL_TCP,
            payload = tcpHeader + payload
        )
    }

    /**
     * Build a complete UDP/IP packet
     */
    fun buildUdpPacket(
        srcIp: String,
        dstIp: String,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpHeader = buildUdpHeader(
            srcPort = srcPort,
            dstPort = dstPort,
            payload = payload
        )
        
        return buildIpPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            protocol = PROTOCOL_UDP,
            payload = udpHeader + payload
        )
    }

    /**
     * Build IP packet header
     */
    private fun buildIpPacket(
        srcIp: String,
        dstIp: String,
        protocol: Int,
        payload: ByteArray
    ): ByteArray {
        val srcIpBytes = ipToBytes(srcIp)
        val dstIpBytes = ipToBytes(dstIp)
        
        val totalLength = 20 + payload.size
        ipIdentification = (ipIdentification + 1) and 0xFFFF
        
        val buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.BIG_ENDIAN)
        
        // Version (4) + IHL (5 = 20 bytes / 4)
        buffer.put((0x45).toByte())
        
        // Type of Service
        buffer.put(0)
        
        // Total Length
        buffer.putShort(totalLength.toShort())
        
        // Identification
        buffer.putShort(ipIdentification.toShort())
        
        // Flags + Fragment Offset (Don't fragment)
        buffer.putShort((0x4000).toShort())
        
        // TTL
        buffer.put(64.toByte())
        
        // Protocol
        buffer.put(protocol.toByte())
        
        // Checksum placeholder (will be calculated)
        buffer.putShort(0)
        
        // Source IP
        buffer.put(srcIpBytes)
        
        // Destination IP
        buffer.put(dstIpBytes)
        
        // Payload
        buffer.put(payload)
        
        // Calculate and set checksum
        buffer.position(10)
        val checksum = calculateChecksum(buffer.array(), 0, 20)
        buffer.putShort(checksum)
        
        return buffer.array()
    }

    /**
     * Build TCP header
     */
    private fun buildTcpHeader(
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        payload: ByteArray,
        srcIp: String,
        dstIp: String
    ): ByteArray {
        val headerSize = 20 // Standard TCP header, no options
        val buffer = ByteBuffer.allocate(headerSize).order(ByteOrder.BIG_ENDIAN)
        
        // Source port
        buffer.putShort(srcPort.toShort())
        
        // Destination port
        buffer.putShort(dstPort.toShort())
        
        // Sequence number
        buffer.putInt(seq.toInt())
        
        // Acknowledgment number
        buffer.putInt(ack.toInt())
        
        // Data offset (5 = 20 bytes / 4) + Reserved + Flags
        buffer.put((0x50).toByte()) // Data offset = 5
        buffer.put(flags.toByte())
        
        // Window
        buffer.putShort(window.toShort())
        
        // Checksum placeholder
        buffer.putShort(0)
        
        // Urgent pointer
        buffer.putShort(0)
        
        // Calculate TCP checksum with pseudo-header
        val checksum = calculateTcpChecksum(
            buffer.array(),
            srcIp = srcIp,
            dstIp = dstIp,
            payload = payload
        )
        buffer.putShort(10, checksum)
        
        return buffer.array()
    }

    /**
     * Build UDP header
     */
    private fun buildUdpHeader(
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val buffer = ByteBuffer.allocate(UDP_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        
        // Source port
        buffer.putShort(srcPort.toShort())
        
        // Destination port
        buffer.putShort(dstPort.toShort())
        
        // Length (header + payload)
        buffer.putShort((UDP_HEADER_SIZE + payload.size).toShort())
        
        // Checksum (optional for UDP over IPv4, set to 0)
        buffer.putShort(0)
        
        return buffer.array()
    }

    // ============================================
    // CHECKSUM CALCULATIONS
    // ============================================

    /**
     * Calculate IP checksum
     */
    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Short {
        var sum = 0L
        
        for (i in offset until offset + length step 2) {
            val word = if (i + 1 < offset + length) {
                ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            } else {
                (data[i].toInt() and 0xFF) shl 8
            }
            sum += word.toLong()
        }
        
        // Fold 32-bit sum to 16 bits
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        
        return ((~sum).toInt() and 0xFFFF).toShort()
    }

    /**
     * Calculate TCP checksum with pseudo-header
     */
    private fun calculateTcpChecksum(
        tcpHeader: ByteArray,
        srcIp: String,
        dstIp: String,
        payload: ByteArray
    ): Short {
        var sum = 0L
        
        // Pseudo-header
        val srcIpBytes = ipToBytes(srcIp)
        val dstIpBytes = ipToBytes(dstIp)
        
        // Source IP
        sum += ((srcIpBytes[0].toInt() and 0xFF) shl 8) or (srcIpBytes[1].toInt() and 0xFF)
        sum += ((srcIpBytes[2].toInt() and 0xFF) shl 8) or (srcIpBytes[3].toInt() and 0xFF)
        
        // Destination IP
        sum += ((dstIpBytes[0].toInt() and 0xFF) shl 8) or (dstIpBytes[1].toInt() and 0xFF)
        sum += ((dstIpBytes[2].toInt() and 0xFF) shl 8) or (dstIpBytes[3].toInt() and 0xFF)
        
        // Zero + Protocol
        sum += PROTOCOL_TCP.toLong()
        
        // TCP length
        val tcpLength = tcpHeader.size + payload.size
        sum += tcpLength.toLong()
        
        // TCP header
        for (i in tcpHeader.indices step 2) {
            val word = if (i + 1 < tcpHeader.size) {
                ((tcpHeader[i].toInt() and 0xFF) shl 8) or (tcpHeader[i + 1].toInt() and 0xFF)
            } else {
                (tcpHeader[i].toInt() and 0xFF) shl 8
            }
            sum += word.toLong()
        }
        
        // TCP payload
        for (i in payload.indices step 2) {
            val word = if (i + 1 < payload.size) {
                ((payload[i].toInt() and 0xFF) shl 8) or (payload[i + 1].toInt() and 0xFF)
            } else {
                (payload[i].toInt() and 0xFF) shl 8
            }
            sum += word.toLong()
        }
        
        // Fold to 16 bits
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        
        return ((~sum).toInt() and 0xFFFF).toShort()
    }

    // ============================================
    // UTILITY METHODS
    // ============================================

    /**
     * Convert IP string to byte array
     */
    private fun ipToBytes(ip: String): ByteArray {
        val parts = ip.split(".")
        if (parts.size != 4) {
            return byteArrayOf(0, 0, 0, 0)
        }
        
        return ByteArray(4) { i ->
            parts[i].toIntOrNull()?.toByte() ?: 0
        }
    }

    /**
     * Convert byte array to IP string
     */
    private fun bytesToIp(bytes: ByteArray): String {
        if (bytes.size != 4) return "0.0.0.0"
        
        return "${bytes[0].toInt() and 0xFF}." +
               "${bytes[1].toInt() and 0xFF}." +
               "${bytes[2].toInt() and 0xFF}." +
               "${bytes[3].toInt() and 0xFF}"
    }
}
