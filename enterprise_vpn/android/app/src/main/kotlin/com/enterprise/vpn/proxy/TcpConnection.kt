package com.enterprise.vpn.proxy

import android.util.Log
import com.enterprise.vpn.HttpHeaderConfig
import com.enterprise.vpn.SniConfig
import com.enterprise.vpn.packet.IpPacket
import com.enterprise.vpn.packet.PacketProcessor
import com.enterprise.vpn.packet.TcpHeader
import com.enterprise.vpn.packet.IpHeader
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP Connection Handler
 * 
 * Manages a single TCP connection from client through the proxy.
 * Handles:
 * - Connection state management
 * - HTTP header injection
 * - TLS SNI modification
 * - Proxy authentication
 * - Packet reconstruction
 * - Flow control
 */
class TcpConnection(
    private val socketChannel: SocketChannel,
    private val packet: IpPacket,
    private val httpHeaders: List<HttpHeaderConfig>,
    private val sniConfig: SniConfig?,
    private val proxyConfig: ProxyConfig,
    private val onResponse: (ProxyEngine.IncomingPacket) -> Unit,
    // New parameters for external proxy routing
    private val routeThroughProxy: Boolean = false,
    private val proxyAuth: ProxyAuth? = null
) {
    companion object {
        private const val TAG = "TcpConnection"
        
        // Buffer sizes
        private const val READ_BUFFER_SIZE = 65536
        private const val WRITE_BUFFER_SIZE = 65536
        
        // Connection states
        const val STATE_CONNECTING = 0
        const val STATE_CONNECTED = 1
        const val STATE_CLOSING = 2
        const val STATE_CLOSED = 3
    }

    // Connection state
    private var state = STATE_CONNECTING
    private val isConnected = AtomicBoolean(false)
    
    // Buffers
    private val readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE)
    private val writeQueue = Channel<ByteArray>(capacity = 256)
    private val pendingWrites = ByteArrayOutputStream()
    
    // Packet tracking
    private var clientSeq = 0L
    private var serverSeq = 0L
    private var clientAck = 0L
    private var serverAck = 0L
    
    // Port tracking for response packet construction
    private val clientPort = packet.srcPort
    private val serverPort = packet.dstPort
    private val clientIp = packet.srcIp
    private val serverIp = packet.dstIp
    
    // HTTP state tracking
    private var isHttpRequestDetected = false
    private var httpState = HttpState()
    
    // TLS state tracking
    private var isTlsConnection = false
    private var tlsState = TlsState()
    
    // Packet processor for reconstruction
    private val packetProcessor = PacketProcessor()

    /**
     * Called when connection is established
     */
    fun onConnected() {
        state = STATE_CONNECTED
        isConnected.set(true)
        
        // Perform proxy handshake if routing through external proxy
        if (routeThroughProxy) {
            performProxyHandshake()
        }
        
        Log.d(TAG, "TCP connection established: $clientPort -> $serverPort")
    }

    /**
     * Perform proxy handshake for external proxy routing
     */
    private fun performProxyHandshake() {
        try {
            // Build proxy handshake request
            val handshakeRequest = buildProxyHandshake()
            
            // Queue handshake for sending
            pendingWrites.write(handshakeRequest)
            pendingWrites.flush()
            
            Log.d(TAG, "Proxy handshake initiated for $clientIp:$clientPort -> $serverIp:$serverPort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to perform proxy handshake", e)
            close()
        }
    }

    /**
     * Build proxy handshake request
     * Supports HTTP CONNECT and SOCKS5 protocols
     */
    private fun buildProxyHandshake(): ByteArray {
        val output = ByteArrayOutputStream()
        
        when (proxyConfig.proxyProtocol) {
            "HTTP" -> {
                // HTTP CONNECT request
                val connectRequest = StringBuilder().apply {
                    append("CONNECT $serverIp:$serverPort HTTP/1.1\r\n")
                    append("Host: $serverIp:$serverPort\r\n")
                    
                    // Add authentication if provided
                    if (proxyAuth != null) {
                        val credentials = "${proxyAuth.username}:${proxyAuth.password}"
                        val encoded = Base64.encodeToString(
                            credentials.toByteArray(),
                            Base64.NO_WRAP
                        )
                        append("Proxy-Authorization: Basic $encoded\r\n")
                    }
                    
                    // Add custom HTTP headers
                    httpHeaders.forEach { header ->
                        if (header.enabled) {
                            append("${header.name}: ${header.value}\r\n")
                        }
                    }
                    
                    append("\r\n")
                }.toString()
                
                output.write(connectRequest.toByteArray(Charsets.UTF_8))
                Log.d(TAG, "HTTP CONNECT request built for $serverIp:$serverPort")
            }
            
            "SOCKS5" -> {
                // SOCKS5 handshake
                // Version 5, 1 method, no auth or username/password
                if (proxyAuth != null) {
                    // Method: username/password (0x02)
                    output.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
                } else {
                    // Method: no authentication (0x00)
                    output.write(byteArrayOf(0x05, 0x01, 0x00))
                }
                Log.d(TAG, "SOCKS5 handshake initiated")
            }
            
            else -> {
                Log.w(TAG, "Unknown proxy protocol: ${proxyConfig.proxyProtocol}")
            }
        }
        
        return output.toByteArray()
    }

    /**
     * Handle proxy response
     */
    fun handleProxyResponse(data: ByteArray): Boolean {
        when (proxyConfig.proxyProtocol) {
            "HTTP" -> {
                val response = String(data, Charsets.UTF_8)
                if (response.contains("HTTP/1.1 200") || response.contains("HTTP/1.0 200")) {
                    Log.d(TAG, "HTTP proxy connection established")
                    return true
                } else if (response.contains("407")) {
                    Log.e(TAG, "HTTP proxy authentication failed")
                    return false
                }
            }
            "SOCKS5" -> {
                // Handle SOCKS5 response
                if (data.size >= 2 && data[0] == 0x05.toByte()) {
                    if (data[1] == 0x00.toByte()) {
                        Log.d(TAG, "SOCKS5 connection established")
                        return true
                    } else if (data[1] == 0x02.toByte() && proxyAuth != null) {
                        // Need to send username/password
                        sendSocks5Auth()
                        return false // Wait for auth response
                    } else {
                        Log.e(TAG, "SOCKS5 connection failed: ${data[1].toInt() and 0xFF}")
                        return false
                    }
                }
            }
        }
        return false
    }

    /**
     * Send SOCKS5 username/password authentication
     */
    private fun sendSocks5Auth() {
        if (proxyAuth == null) return
        
        val output = ByteArrayOutputStream()
        
        // Version 1
        output.write(0x01)
        
        // Username
        val usernameBytes = proxyAuth.username.toByteArray(Charsets.UTF_8)
        output.write(usernameBytes.size)
        output.write(usernameBytes)
        
        // Password
        val passwordBytes = proxyAuth.password.toByteArray(Charsets.UTF_8)
        output.write(passwordBytes.size)
        output.write(passwordBytes)
        
        pendingWrites.write(output.toByteArray())
        Log.d(TAG, "SOCKS5 authentication sent")
    }

    /**
     * Handle outgoing packet from client
     */
    fun handleOutgoingPacket(packet: IpPacket) {
        if (!isConnected.get()) {
            return
        }
        
        try {
            val payload = packet.payload
            if (payload.isEmpty()) return
            
            // Detect protocol type
            detectProtocol(payload)
            
            // Process based on protocol
            val processedPayload = when {
                isTlsConnection -> processTlsPayload(payload)
                isHttpRequestDetected -> processHttpPayload(payload)
                else -> payload
            }
            
            // Queue for writing
            pendingWrites.write(processedPayload)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling outgoing packet", e)
        }
    }

    /**
     * Handle incoming data from server
     */
    fun handleIncomingData(data: ByteArray, length: Int) {
        if (length == 0) return
        
        try {
            // Reconstruct IP packet for TUN interface
            val responsePacket = reconstructPacket(data, length)
            
            onResponse(responsePacket)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming data", e)
        }
    }

    /**
     * Flush outgoing data to server
     */
    fun flushOutgoing() {
        try {
            val data = pendingWrites.toByteArray()
            if (data.isEmpty()) return
            
            pendingWrites.reset()
            
            val buffer = ByteBuffer.wrap(data)
            while (buffer.hasRemaining()) {
                socketChannel.write(buffer)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing outgoing data", e)
        }
    }

    /**
     * Close the connection
     */
    fun close() {
        try {
            state = STATE_CLOSING
            socketChannel.close()
            state = STATE_CLOSED
            Log.d(TAG, "TCP connection closed: $clientPort -> $serverPort")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing connection", e)
        }
    }

    // ============================================
    // PROTOCOL DETECTION
    // ============================================

    /**
     * Detect if connection is HTTP or TLS
     */
    private fun detectProtocol(payload: ByteArray) {
        if (payload.size < 3) return
        
        // Check for TLS handshake
        if (payload[0] == 0x16.toByte() && // Handshake
            payload[1] == 0x03.toByte()) { // TLS version
            isTlsConnection = true
            Log.d(TAG, "TLS connection detected on port $serverPort")
            return
        }
        
        // Check for HTTP methods
        val headerStart = String(payload, 0, minOf(payload.size, 10))
        isHttpRequestDetected = when {
            headerStart.startsWith("GET ") -> true
            headerStart.startsWith("POST ") -> true
            headerStart.startsWith("PUT ") -> true
            headerStart.startsWith("DELETE ") -> true
            headerStart.startsWith("HEAD ") -> true
            headerStart.startsWith("OPTIONS ") -> true
            headerStart.startsWith("PATCH ") -> true
            headerStart.startsWith("CONNECT ") -> true
            else -> false
        }
        
        if (isHttpRequestDetected) {
            Log.d(TAG, "HTTP connection detected on port $serverPort")
        }
    }

    // ============================================
    // HTTP HEADER INJECTION
    // ============================================

    /**
     * Process HTTP payload and inject custom headers
     */
    private fun processHttpPayload(payload: ByteArray): ByteArray {
        if (httpHeaders.isEmpty()) return payload
        
        try {
            // Parse HTTP request
            val request = parseHttpRequest(payload)
            
            if (request != null) {
                // Inject custom headers
                val modifiedRequest = injectHttpHeaders(request)
                
                httpState.requestCount++
                Log.d(TAG, "HTTP request #${httpState.requestCount} processed with ${httpHeaders.size} custom headers")
                
                return modifiedRequest.toByteArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing HTTP payload", e)
        }
        
        return payload
    }

    /**
     * Parse HTTP request from payload
     */
    private fun parseHttpRequest(payload: ByteArray): HttpRequest? {
        try {
            val requestStr = String(payload, Charsets.UTF_8)
            val lines = requestStr.split("\r\n")
            
            if (lines.isEmpty()) return null
            
            // Parse request line
            val requestLine = lines[0].split(" ")
            if (requestLine.size < 3) return null
            
            val method = requestLine[0]
            val path = requestLine[1]
            val version = requestLine[2]
            
            // Parse headers
            val headers = mutableMapOf<String, String>()
            var bodyStartIndex = 0
            
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) {
                    bodyStartIndex = requestStr.indexOf("\r\n\r\n") + 4
                    break
                }
                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    val name = line.substring(0, colonIndex).trim()
                    val value = line.substring(colonIndex + 1).trim()
                    headers[name] = value
                }
            }
            
            // Extract body
            val body = if (bodyStartIndex > 0 && bodyStartIndex < payload.size) {
                payload.copyOfRange(bodyStartIndex, payload.size)
            } else {
                ByteArray(0)
            }
            
            return HttpRequest(
                method = method,
                path = path,
                version = version,
                headers = headers,
                body = body
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing HTTP request", e)
            return null
        }
    }

    /**
     * Inject custom HTTP headers into request
     */
    private fun injectHttpHeaders(request: HttpRequest): HttpRequest {
        val modifiedHeaders = request.headers.toMutableMap()
        
        // Apply each custom header
        httpHeaders.forEach { header ->
            val name = header.name.trim()
            val value = header.value.trim()
            
            if (name.isNotEmpty() && value.isNotEmpty()) {
                modifiedHeaders[name] = value
                Log.d(TAG, "Injected HTTP header: $name: ${value.take(30)}...")
            }
        }
        
        // Update Content-Length if body exists
        if (request.body.isNotEmpty()) {
            modifiedHeaders["Content-Length"] = request.body.size.toString()
        }
        
        return request.copy(headers = modifiedHeaders)
    }

    /**
     * Convert HttpRequest back to bytes
     */
    private fun HttpRequest.toByteArray(): ByteArray {
        val sb = StringBuilder()
        
        // Request line
        sb.append("$method $path $version\r\n")
        
        // Headers
        headers.forEach { (name, value) ->
            sb.append("$name: $value\r\n")
        }
        
        // Empty line
        sb.append("\r\n")
        
        // Combine headers and body
        val headerBytes = sb.toString().toByteArray(Charsets.UTF_8)
        
        return if (body.isNotEmpty()) {
            headerBytes + body
        } else {
            headerBytes
        }
    }

    // ============================================
    // TLS SNI MODIFICATION
    // ============================================

    /**
     * Process TLS payload and modify SNI if configured
     */
    private fun processTlsPayload(payload: ByteArray): ByteArray {
        if (sniConfig == null || !sniConfig.enabled) return payload
        
        try {
            // Check if this is a ClientHello
            if (payload.size >= 6 && 
                payload[0] == 0x16.toByte() && 
                payload[1] == 0x03.toByte()) {
                
                // Parse TLS ClientHello
                val clientHello = parseClientHello(payload)
                
                if (clientHello != null && clientHello.sni.isNotEmpty()) {
                    Log.d(TAG, "TLS ClientHello detected - Original SNI: ${clientHello.sni}")
                    
                    // Check if we should modify SNI
                    if (shouldModifySni(clientHello.sni)) {
                        val modifiedPayload = modifySniInClientHello(payload, clientHello)
                        
                        tlsState.sniModified = true
                        tlsState.originalSni = clientHello.sni
                        tlsState.modifiedSni = sniConfig.serverName
                        
                        Log.d(TAG, "SNI modified: ${clientHello.sni} -> ${sniConfig.serverName}")
                        
                        return modifiedPayload
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing TLS payload", e)
        }
        
        return payload
    }

    /**
     * Parse ClientHello from TLS handshake
     */
    private fun parseClientHello(payload: ByteArray): ClientHello? {
        try {
            // TLS record structure:
            // [0] Content type (0x16 = Handshake)
            // [1-2] Version
            // [3-4] Length
            // [5] Handshake type (0x01 = ClientHello)
            // ...
            
            if (payload.size < 43) return null
            
            // Check for ClientHello
            if (payload[5] != 0x01.toByte()) return null
            
            // Find SNI extension
            // This is a simplified parser - production would need full extension parsing
            
            // Skip to extensions
            var offset = 43 // After session ID
            
            // Skip cipher suites
            val cipherSuitesLength = ((payload[offset].toInt() and 0xFF) shl 8) or 
                                     (payload[offset + 1].toInt() and 0xFF)
            offset += 2 + cipherSuitesLength
            
            // Skip compression methods
            val compressionLength = payload[offset].toInt() and 0xFF
            offset += 1 + compressionLength
            
            // Extensions
            if (offset + 2 < payload.size) {
                val extensionsLength = ((payload[offset].toInt() and 0xFF) shl 8) or 
                                       (payload[offset + 1].toInt() and 0xFF)
                offset += 2
                
                // Search for SNI extension (type 0x0000)
                val extensionsEnd = offset + extensionsLength
                while (offset + 4 < extensionsEnd) {
                    val extensionType = ((payload[offset].toInt() and 0xFF) shl 8) or 
                                        (payload[offset + 1].toInt() and 0xFF)
                    val extensionLength = ((payload[offset + 2].toInt() and 0xFF) shl 8) or 
                                          (payload[offset + 3].toInt() and 0xFF)
                    
                    if (extensionType == 0x0000) { // SNI extension
                        // Parse SNI
                        val sniListOffset = offset + 4 + 2
                        val sniType = payload[offset + 4 + 2].toInt() and 0xFF
                        if (sniType == 0) { // hostname type
                            val sniLength = ((payload[offset + 4 + 3].toInt() and 0xFF) shl 8) or 
                                           (payload[offset + 4 + 4].toInt() and 0xFF)
                            
                            val sniBytes = payload.copyOfRange(
                                offset + 4 + 5,
                                offset + 4 + 5 + sniLength
                            )
                            val sni = String(sniBytes, Charsets.UTF_8)
                            
                            return ClientHello(
                                sni = sni,
                                sniOffset = offset + 4 + 5,
                                sniLength = sniLength
                            )
                        }
                    }
                    
                    offset += 4 + extensionLength
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ClientHello", e)
        }
        
        return null
    }

    /**
     * Check if SNI should be modified
     */
    private fun shouldModifySni(originalSni: String): Boolean {
        if (sniConfig == null || !sniConfig.enabled) return false
        if (sniConfig.serverName.isEmpty()) return false
        
        return if (sniConfig.allowOverride) {
            true // Always override
        } else {
            originalSni != sniConfig.serverName // Only override if different
        }
    }

    /**
     * Modify SNI in ClientHello
     */
    private fun modifySniInClientHello(payload: ByteArray, clientHello: ClientHello): ByteArray {
        val newSni = sniConfig?.serverName ?: return payload
        val newSniBytes = newSni.toByteArray(Charsets.UTF_8)
        
        // Calculate size difference
        val oldLength = clientHello.sniLength
        val newLength = newSniBytes.size
        val lengthDiff = newLength - oldLength
        
        // Create new payload with modified SNI
        val output = ByteArrayOutputStream()
        
        // Copy everything before SNI
        output.write(payload, 0, clientHello.sniOffset)
        
        // Write new SNI
        output.write(newSniBytes)
        
        // Copy everything after SNI
        val afterSniOffset = clientHello.sniOffset + oldLength
        if (afterSniOffset < payload.size) {
            output.write(payload, afterSniOffset, payload.size - afterSniOffset)
        }
        
        val result = output.toByteArray()
        
        // Update length fields if SNI length changed
        if (lengthDiff != 0) {
            updateLengthFields(result, lengthDiff, clientHello.sniOffset)
        }
        
        return result
    }

    /**
     * Update TLS length fields after SNI modification
     */
    private fun updateLengthFields(payload: ByteArray, diff: Int, sniOffset: Int) {
        try {
            // Update record length (bytes 3-4)
            val recordLen = ((payload[3].toInt() and 0xFF) shl 8) or 
                           (payload[4].toInt() and 0xFF)
            val newRecordLen = recordLen + diff
            payload[3] = ((newRecordLen shr 8) and 0xFF).toByte()
            payload[4] = (newRecordLen and 0xFF).toByte()
            
            // Update handshake length (bytes 6-8, 3 bytes)
            // This is complex - would need proper implementation
        } catch (e: Exception) {
            Log.e(TAG, "Error updating length fields", e)
        }
    }

    // ============================================
    // PACKET RECONSTRUCTION
    // ============================================

    /**
     * Reconstruct IP packet for TUN interface
     */
    private fun reconstructPacket(data: ByteArray, length: Int): ProxyEngine.IncomingPacket {
        // Build IP packet with reversed addresses
        val packet = packetProcessor.buildTcpPacket(
            srcIp = serverIp,
            dstIp = clientIp,
            srcPort = serverPort,
            dstPort = clientPort,
            payload = data.copyOf(length),
            seq = serverSeq,
            ack = clientSeq
        )
        
        serverSeq += length
        
        return ProxyEngine.IncomingPacket(
            data = packet,
            length = packet.size
        )
    }

    // ============================================
    // DATA CLASSES
    // ============================================

    data class HttpRequest(
        val method: String,
        val path: String,
        val version: String,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    data class ClientHello(
        val sni: String,
        val sniOffset: Int,
        val sniLength: Int
    )

    data class HttpState(
        var requestCount: Int = 0,
        var responseCount: Int = 0
    )

    data class TlsState(
        var sniModified: Boolean = false,
        var originalSni: String = "",
        var modifiedSni: String = ""
    )
}

/**
 * Proxy Authentication Configuration
 */
data class ProxyAuth(
    val username: String,
    val password: String
)
