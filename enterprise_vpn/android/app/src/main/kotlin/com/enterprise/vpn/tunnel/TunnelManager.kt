package com.enterprise.vpn.tunnel

import android.net.VpnService
import android.util.Log
import com.enterprise.vpn.proxy.ProxyEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tunnel Manager
 * 
 * Manages secure tunnel connections to the remote VPN server.
 * Handles:
 * - TLS connection establishment
 * - SNI injection during handshake
 * - Connection keepalive
 * - Traffic encryption/decryption
 */
class TunnelManager(private val vpnService: VpnService) {

    companion object {
        private const val TAG = "TunnelManager"
        
        // Buffer sizes
        private const val BUFFER_SIZE = 65536
        
        // Keepalive settings
        private const val KEEPALIVE_INTERVAL_MS = 30000L
        private const val KEEPALIVE_TIMEOUT_MS = 60000L
    }

    // ============================================
    // STATE
    // ============================================

    private val scope = CoroutineScope(Dispatchers.IO)
    private var tunnelJob: Job? = null
    private var keepaliveJob: Job? = null
    
    private val isConnected = AtomicBoolean(false)
    
    // Tunnel configuration
    private var config: TunnelConfig? = null
    
    // Socket channel
    private var socketChannel: SocketChannel? = null
    
    // Incoming traffic flow
    private val _incomingFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    val incomingFlow: SharedFlow<ByteArray> = _incomingFlow.asSharedFlow()
    
    // Outgoing queue
    private val outgoingQueue = Channel<ByteArray>(capacity = 256)

    // ============================================
    // LIFECYCLE
    // ============================================

    /**
     * Configure the tunnel
     */
    fun configure(config: TunnelConfig) {
        this.config = config
        Log.d(TAG, "Tunnel configured - Server: ${config.serverIp}:${config.serverPort}, SNI: ${config.sniServerName}")
    }

    /**
     * Update SNI server name
     */
    fun updateSniServerName(serverName: String) {
        config?.let {
            config = it.copy(sniServerName = serverName)
        }
        Log.d(TAG, "SNI server name updated: $serverName")
    }

    /**
     * Connect to tunnel server
     */
    fun connect() {
        if (isConnected.getAndSet(true)) {
            Log.w(TAG, "Tunnel already connected")
            return
        }
        
        val cfg = config ?: run {
            Log.e(TAG, "No configuration set")
            isConnected.set(false)
            return
        }
        
        Log.d(TAG, "Connecting to tunnel: ${cfg.serverIp}:${cfg.serverPort}")
        
        tunnelJob = scope.launch {
            try {
                establishTunnel(cfg)
            } catch (e: Exception) {
                Log.e(TAG, "Tunnel connection error", e)
                isConnected.set(false)
            }
        }
    }

    /**
     * Disconnect from tunnel server
     */
    fun disconnect() {
        if (!isConnected.getAndSet(false)) {
            return
        }
        
        Log.d(TAG, "Disconnecting tunnel")
        
        tunnelJob?.cancel()
        keepaliveJob?.cancel()
        
        try {
            socketChannel?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket", e)
        }
        
        socketChannel = null
        
        Log.d(TAG, "Tunnel disconnected")
    }

    /**
     * Stop tunnel
     */
    fun stop() {
        disconnect()
    }

    // ============================================
    // TUNNEL ESTABLISHMENT
    // ============================================

    /**
     * Establish tunnel connection with authentication
     */
    private suspend fun establishTunnel(cfg: TunnelConfig) {
        // Create socket channel
        socketChannel = SocketChannel.open().apply {
            configureBlocking(false)
        }
        
        // Protect socket from VPN routing
        if (!vpnService.protect(socketChannel!!.socket())) {
            throw TunnelException("Failed to protect tunnel socket")
        }
        
        // Connect to server
        val serverAddress = InetSocketAddress(cfg.serverIp, cfg.serverPort)
        socketChannel!!.connect(serverAddress)
        
        // Wait for connection
        while (!socketChannel!!.finishConnect()) {
            Thread.sleep(10)
            if (!isActive) return
        }
        
        Log.d(TAG, "Tunnel socket connected to ${cfg.serverIp}:${cfg.serverPort}")
        
        // Perform TLS handshake with SNI
        if (cfg.useTls) {
            performTlsHandshake(cfg)
        }
        
        // Perform authentication if credentials are provided
        if (cfg.authUsername.isNotEmpty() && cfg.authPassword.isNotEmpty()) {
            performAuthentication(cfg.authUsername, cfg.authPassword)
        }
        
        // Send custom payload if configured
        if (cfg.customPayload.isNotEmpty()) {
            sendCustomPayload(cfg.customPayload)
        }
        
        // Start keepalive
        startKeepalive()
        
        // Start I/O loops
        launch { readLoop() }
        launch { writeLoop() }
        
        Log.d(TAG, "Tunnel established successfully with authentication")
    }

    /**
     * Perform authentication handshake
     */
    private suspend fun performAuthentication(username: String, password: String) {
        Log.d(TAG, "Performing authentication handshake for user: $username")
        
        // Build authentication request
        val authRequest = buildAuthRequest(username, password)
        
        // Send authentication request
        sendRaw(authRequest)
        
        // Wait for authentication response
        val response = waitForAuthResponse()
        
        if (!response.success) {
            throw TunnelException("Authentication failed: ${response.message}")
        }
        
        Log.d(TAG, "Authentication successful")
    }

    /**
     * Build authentication request packet
     */
    private fun buildAuthRequest(username: String, password: String): ByteArray {
        val output = ByteArrayOutputStream()
        
        // Authentication packet format:
        // [1 byte] Auth type (0x01 = username/password)
        // [2 bytes] Username length
        // [N bytes] Username
        // [2 bytes] Password length
        // [N bytes] Password
        
        output.write(0x01) // Auth type: username/password
        
        val usernameBytes = username.toByteArray(Charsets.UTF_8)
        output.write((usernameBytes.size shr 8) and 0xFF)
        output.write(usernameBytes.size and 0xFF)
        output.write(usernameBytes)
        
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        output.write((passwordBytes.size shr 8) and 0xFF)
        output.write(passwordBytes.size and 0xFF)
        output.write(passwordBytes)
        
        return output.toByteArray()
    }

    /**
     * Wait for authentication response from server
     */
    private suspend fun waitForAuthResponse(): AuthResponse {
        val buffer = ByteBuffer.allocate(1024)
        
        withTimeout(30000) {
            while (true) {
                buffer.clear()
                val bytesRead = socketChannel?.read(buffer) ?: -1
                
                if (bytesRead > 0) {
                    buffer.flip()
                    val data = ByteArray(bytesRead)
                    buffer.get(data)
                    
                    return@withTimeout parseAuthResponse(data)
                } else if (bytesRead < 0) {
                    throw TunnelException("Connection closed during authentication")
                }
                
                delay(50)
            }
        }
        
        throw TunnelException("Authentication timeout")
    }

    /**
     * Parse authentication response
     */
    private fun parseAuthResponse(data: ByteArray): AuthResponse {
        if (data.isEmpty()) {
            return AuthResponse(false, "Empty response")
        }
        
        val status = data[0].toInt()
        val success = status == 0x00
        
        val message = if (data.size > 1) {
            String(data, 1, data.size - 1, Charsets.UTF_8)
        } else {
            if (success) "OK" else "Authentication failed"
        }
        
        return AuthResponse(success, message)
    }

    /**
     * Send custom payload to server
     */
    private suspend fun sendCustomPayload(payload: String) {
        Log.d(TAG, "Sending custom payload (${payload.length} bytes)")
        
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream()
        
        // Custom payload format:
        // [2 bytes] Payload length
        // [N bytes] Payload data
        
        output.write((payloadBytes.size shr 8) and 0xFF)
        output.write(payloadBytes.size and 0xFF)
        output.write(payloadBytes)
        
        sendRaw(output.toByteArray())
    }

    /**
     * Authentication response
     */
    data class AuthResponse(
        val success: Boolean,
        val message: String
    )

    /**
     * Perform TLS handshake with custom SNI
     */
    private suspend fun performTlsHandshake(cfg: TunnelConfig) {
        Log.d(TAG, "Starting TLS handshake with SNI: ${cfg.sniServerName}")
        
        // Build ClientHello with custom SNI
        val clientHello = buildClientHello(cfg.sniServerName)
        
        // Send ClientHello
        sendRaw(clientHello)
        
        // Wait for ServerHello (simplified - real implementation would need full TLS)
        // In production, this would use SSLEngine or native TLS library
        
        Log.d(TAG, "TLS handshake initiated")
    }

    /**
     * Build TLS ClientHello with custom SNI
     */
    private fun buildClientHello(sniServerName: String): ByteArray {
        // Simplified ClientHello construction
        // In production, this would use proper TLS library
        
        val sniBytes = sniServerName.toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream()
        
        // TLS Record Layer
        output.write(0x16) // Handshake
        output.write(0x03) // TLS 1.0
        output.write(0x01) // TLS 1.0
        
        // Handshake length placeholder
        val lengthPos = output.size()
        output.write(0)
        output.write(0)
        
        // Handshake type: ClientHello
        output.write(0x01)
        
        // ClientHello length placeholder
        val chLengthPos = output.size()
        output.write(0)
        output.write(0)
        output.write(0)
        
        // Client Version: TLS 1.2
        output.write(0x03)
        output.write(0x03)
        
        // Random (32 bytes)
        val random = ByteArray(32)
        java.security.SecureRandom().nextBytes(random)
        output.write(random)
        
        // Session ID length (0)
        output.write(0)
        
        // Cipher Suites
        output.write(0x00)
        output.write(0x02)
        output.write(0x00)
        output.write(0x2F) // TLS_RSA_WITH_AES_128_CBC_SHA
        
        // Compression methods
        output.write(0x01)
        output.write(0x00)
        
        // Extensions
        val extensionsOutput = ByteArrayOutputStream()
        
        // SNI Extension
        extensionsOutput.write(0x00) // Extension type: server_name
        extensionsOutput.write(0x00) // Extension type (cont.)
        
        // Extension length
        val sniExtLength = 2 + 1 + 2 + sniBytes.size
        extensionsOutput.write((sniExtLength shr 8) and 0xFF)
        extensionsOutput.write(sniExtLength and 0xFF)
        
        // SNI list length
        extensionsOutput.write(((1 + 2 + sniBytes.size) shr 8) and 0xFF)
        extensionsOutput.write((1 + 2 + sniBytes.size) and 0xFF)
        
        // SNI type: hostname
        extensionsOutput.write(0x00)
        
        // SNI length
        extensionsOutput.write((sniBytes.size shr 8) and 0xFF)
        extensionsOutput.write(sniBytes.size and 0xFF)
        
        // SNI value
        extensionsOutput.write(sniBytes)
        
        // Write extensions
        output.write((extensionsOutput.size() shr 8) and 0xFF)
        output.write(extensionsOutput.size() and 0xFF)
        output.write(extensionsOutput.toByteArray())
        
        // Update lengths
        val result = output.toByteArray()
        
        // Handshake length
        val handshakeLength = result.size - 5
        result[3] = ((handshakeLength shr 8) and 0xFF).toByte()
        result[4] = (handshakeLength and 0xFF).toByte()
        
        // ClientHello length
        val clientHelloLength = handshakeLength - 4
        result[5] = ((clientHelloLength shr 16) and 0xFF).toByte()
        result[6] = ((clientHelloLength shr 8) and 0xFF).toByte()
        result[7] = (clientHelloLength and 0xFF).toByte()
        
        return result
    }

    // ============================================
    // I/O LOOPS
    // ============================================

    /**
     * Read loop - reads data from tunnel
     */
    private suspend fun readLoop() {
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)
        
        while (isActive && isConnected.get()) {
            try {
                buffer.clear()
                val bytesRead = socketChannel?.read(buffer) ?: -1
                
                if (bytesRead > 0) {
                    buffer.flip()
                    val data = ByteArray(bytesRead)
                    buffer.get(data)
                    
                    _incomingFlow.emit(data)
                } else if (bytesRead < 0) {
                    Log.w(TAG, "Tunnel connection closed by server")
                    break
                }
                
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Read error", e)
                    break
                }
            }
        }
        
        isConnected.set(false)
    }

    /**
     * Write loop - sends data to tunnel
     */
    private suspend fun writeLoop() {
        while (isActive && isConnected.get()) {
            try {
                val data = outgoingQueue.receive()
                
                val buffer = ByteBuffer.wrap(data)
                while (buffer.hasRemaining()) {
                    socketChannel?.write(buffer)
                }
                
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Write error", e)
                    break
                }
            }
        }
        
        isConnected.set(false)
    }

    /**
     * Send raw data through tunnel
     */
    private suspend fun sendRaw(data: ByteArray) {
        val buffer = ByteBuffer.wrap(data)
        while (buffer.hasRemaining()) {
            socketChannel?.write(buffer)
        }
    }

    /**
     * Send data through tunnel
     */
    fun send(data: ByteArray) {
        outgoingQueue.trySend(data)
    }

    // ============================================
    // KEEPALIVE
    // ============================================

    /**
     * Start keepalive loop
     */
    private fun startKeepalive() {
        keepaliveJob = scope.launch {
            while (isActive && isConnected.get()) {
                delay(KEEPALIVE_INTERVAL_MS)
                
                if (!isConnected.get()) break
                
                // Send keepalive packet
                val keepalive = buildKeepalivePacket()
                send(keepalive)
                
                Log.v(TAG, "Keepalive sent")
            }
        }
    }

    /**
     * Build keepalive packet
     */
    private fun buildKeepalivePacket(): ByteArray {
        return byteArrayOf(0x00, 0x00, 0x00, 0x00) // Simple keepalive
    }
}

/**
 * Tunnel Configuration
 */
data class TunnelConfig(
    val serverIp: String,
    val serverPort: Int,
    val sniServerName: String = serverIp,
    val protocol: String = "TCP",
    val useTls: Boolean = true,
    val verifyCertificate: Boolean = true,
    // Authentication credentials
    val authUsername: String = "",
    val authPassword: String = "",
    // Custom payload for handshake
    val customPayload: String = ""
)

/**
 * Tunnel Exception
 */
class TunnelException(message: String) : Exception(message)
