package com.enterprise.vpn.proxy

import com.enterprise.vpn.HttpHeaderConfig
import com.enterprise.vpn.SniConfig

/**
 * Proxy Configuration
 * 
 * Configuration data class for the proxy engine containing all
 * server settings, HTTP headers, SNI configuration, and authentication.
 */
data class ProxyConfig(
    val serverIp: String,
    val serverPort: Int = 443,
    val protocol: String = "TCP",
    val httpHeaders: List<HttpHeaderConfig> = emptyList(),
    val sniConfig: SniConfig? = null,
    val mtu: Int = 1500,
    val connectionTimeout: Long = 30000L,
    val readTimeout: Long = 60000L,
    val writeTimeout: Long = 30000L,
    val enableCompression: Boolean = false,
    val enableKeepAlive: Boolean = true,
    // Remote server authentication
    val authUsername: String = "",
    val authPassword: String = "",
    // Custom payload for advanced routing
    val customPayload: String = "",
    // Remote proxy server to connect through
    val remoteServerIp: String = "",
    val remoteServerPort: Int = 443
) {
    /**
     * Check if configuration is valid
     */
    val isValid: Boolean
        get() = serverIp.isNotEmpty() && serverPort > 0 && serverPort <= 65535

    /**
     * Check if HTTP headers are configured
     */
    val hasHttpHeaders: Boolean
        get() = httpHeaders.isNotEmpty()

    /**
     * Check if SNI is configured
     */
    val hasSniConfig: Boolean
        get() = sniConfig != null && sniConfig.enabled && sniConfig.serverName.isNotEmpty()
    
    /**
     * Check if authentication is configured
     */
    val hasAuthentication: Boolean
        get() = authUsername.isNotEmpty() && authPassword.isNotEmpty()
    
    /**
     * Check if remote proxy is configured
     */
    val hasRemoteProxy: Boolean
        get() = remoteServerIp.isNotEmpty() && remoteServerPort > 0

    /**
     * Get enabled HTTP headers
     */
    val enabledHttpHeaders: List<HttpHeaderConfig>
        get() = httpHeaders.filter { it.enabled }
}
