package com.enterprise.vpn.proxy

import android.util.Log
import com.enterprise.vpn.packet.IpPacket
import com.enterprise.vpn.packet.PacketProcessor
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

/**
 * UDP Channel Handler
 * 
 * Manages UDP packet forwarding through the proxy.
 * Handles DNS queries and other UDP traffic.
 */
class UdpChannel(
    private val datagramChannel: DatagramChannel,
    private val packet: IpPacket,
    private val onResponse: (ProxyEngine.IncomingPacket) -> Unit
) {
    companion object {
        private const val TAG = "UdpChannel"
        private const val BUFFER_SIZE = 32768
    }

    private val buffer = ByteBuffer.allocate(BUFFER_SIZE)
    private val packetProcessor = PacketProcessor()
    
    private val clientPort = packet.srcPort
    private val serverPort = packet.dstPort
    private val clientIp = packet.srcIp
    private val serverIp = packet.dstIp

    /**
     * Send UDP packet through channel
     */
    fun send(packet: IpPacket) {
        try {
            val payload = packet.payload
            if (payload.isEmpty()) return
            
            buffer.clear()
            buffer.put(payload)
            buffer.flip()
            
            datagramChannel.write(buffer)
            
            Log.d(TAG, "UDP packet sent: $clientPort -> $serverPort (${payload.size} bytes)")
            
            // Start listening for response
            receiveResponse()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending UDP packet", e)
        }
    }

    /**
     * Receive UDP response
     */
    private fun receiveResponse() {
        try {
            buffer.clear()
            val sourceAddress = datagramChannel.receive(buffer) as? InetSocketAddress
            
            if (sourceAddress != null && buffer.position() > 0) {
                buffer.flip()
                
                val responseLength = buffer.remaining()
                val responseData = ByteArray(responseLength)
                buffer.get(responseData)
                
                // Reconstruct IP packet
                val responsePacket = packetProcessor.buildUdpPacket(
                    srcIp = serverIp,
                    dstIp = clientIp,
                    srcPort = serverPort,
                    dstPort = clientPort,
                    payload = responseData
                )
                
                onResponse(ProxyEngine.IncomingPacket(
                    data = responsePacket,
                    length = responsePacket.size
                ))
                
                Log.d(TAG, "UDP response received: $serverPort -> $clientPort ($responseLength bytes)")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving UDP response", e)
        }
    }

    /**
     * Close the channel
     */
    fun close() {
        try {
            datagramChannel.close()
            Log.d(TAG, "UDP channel closed: $clientPort -> $serverPort")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing UDP channel", e)
        }
    }
}
