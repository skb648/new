/// Application-wide constants
/// Centralized configuration values for the VPN application
class AppConstants {
  AppConstants._();

  // ============================================
  // APP INFO
  // ============================================

  static const String appName = 'Enterprise VPN';
  static const String appVersion = '1.0.0';
  static const String appBuildNumber = '1';

  // ============================================
  // VPN CONFIGURATION
  // ============================================

  /// Default VPN server configuration
  static const String defaultServerIp = '';
  static const int defaultServerPort = 443;
  static const String defaultProtocol = 'TCP';

  /// Connection timeout in seconds
  static const int connectionTimeoutSeconds = 30;

  /// Reconnection delay in seconds
  static const int reconnectionDelaySeconds = 5;

  /// Maximum reconnection attempts
  static const int maxReconnectionAttempts = 5;

  /// Ping interval in seconds
  static const int pingIntervalSeconds = 10;

  /// Speed test interval in seconds
  static const int speedTestIntervalSeconds = 5;

  /// Traffic stats update interval in milliseconds
  static const int trafficStatsIntervalMs = 1000;

  // ============================================
  // NETWORK CONFIGURATION
  // ============================================

  /// DNS servers
  static const List<String> defaultDnsServers = [
    '8.8.8.8',
    '8.8.4.4',
    '1.1.1.1',
    '1.0.0.1',
  ];

  /// MTU size for VPN interface
  static const int defaultMtu = 1500;

  /// Default routes to exclude from VPN
  static const List<String> defaultExcludedApps = [];

  // ============================================
  // UI ANIMATION DURATIONS
  // ============================================

  /// Standard animation duration (120Hz optimized)
  static const Duration animationDurationFast = Duration(milliseconds: 100);

  /// Medium animation duration
  static const Duration animationDurationMedium = Duration(milliseconds: 200);

  /// Slow animation duration
  static const Duration animationDurationSlow = Duration(milliseconds: 400);

  /// Extra slow animation duration
  static const Duration animationDurationExtraSlow = Duration(milliseconds: 600);

  /// Toggle animation duration
  static const Duration toggleAnimationDuration = Duration(milliseconds: 300);

  /// Bottom sheet animation duration
  static const Duration bottomSheetAnimationDuration = Duration(milliseconds: 350);

  /// Card animation duration
  static const Duration cardAnimationDuration = Duration(milliseconds: 250);

  // ============================================
  // STORAGE KEYS
  // ============================================

  static const String keyVpnConfig = 'vpn_configuration';
  static const String keyServerList = 'server_list';
  static const String keyLastServer = 'last_connected_server';
  static const String keyLastConnectionTime = 'last_connection_time';
  static const String keyTotalTraffic = 'total_traffic_bytes';
  static const String keyAutoConnect = 'auto_connect_enabled';
  static const String keyKillSwitch = 'kill_switch_enabled';
  static const String keyDnsLeak = 'dns_leak_protection';
  static const String keyIPv6 = 'ipv6_enabled';
  static const String keySplitTunnel = 'split_tunnel_enabled';
  static const String keyCustomHeaders = 'custom_http_headers';
  static const String keySniConfig = 'sni_configuration';
  static const String keyTheme = 'app_theme';
  static const String keyLanguage = 'app_language';
  static const String keyFirstLaunch = 'first_launch';

  // ============================================
  // METHOD CHANNEL NAMES
  // ============================================

  static const String channelVpnService = 'com.enterprise.vpn/service';
  static const String methodConnect = 'connect';
  static const String methodDisconnect = 'disconnect';
  static const String methodGetStatus = 'getStatus';
  static const String methodGetStats = 'getStats';
  static const String methodConfigure = 'configure';
  static const String methodSetHeaders = 'setHttpHeaders';
  static const String methodSetSni = 'setSni';
  static const String methodRequestPermission = 'requestVpnPermission';
  static const String methodHasPermission = 'hasVpnPermission';

  // ============================================
  // EVENT CHANNEL NAMES
  // ============================================

  static const String channelVpnState = 'com.enterprise.vpn/state';
  static const String channelTrafficStats = 'com.enterprise.vpn/traffic';
  static const String channelConnectionEvents = 'com.enterprise.vpn/events';

  // ============================================
  // NOTIFICATION
  // ============================================

  static const int notificationId = 1001;
  static const String notificationChannelId = 'enterprise_vpn_channel';
  static const String notificationChannelName = 'VPN Status';
  static const String notificationChannelDescription = 'Shows VPN connection status and important alerts';

  // ============================================
  // VALIDATION
  // ============================================

  /// Minimum password length
  static const int minPasswordLength = 8;

  /// Maximum header name length
  static const int maxHeaderNameLength = 256;

  /// Maximum header value length
  static const int maxHeaderValueLength = 4096;

  /// Maximum SNI length
  static const int maxSniLength = 253;

  /// Valid port range
  static const int minPort = 1;
  static const int maxPort = 65535;

  // ============================================
  // REGEX PATTERNS
  // ============================================

  /// IPv4 address pattern
  static final RegExp ipv4Pattern = RegExp(
    r'^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$',
  );

  /// IPv6 address pattern (simplified)
  static final RegExp ipv6Pattern = RegExp(
    r'^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$',
  );

  /// Domain name pattern
  static final RegExp domainPattern = RegExp(
    r'^([a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}$',
  );

  /// SNI pattern
  static final RegExp sniPattern = RegExp(
    r'^([a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?\.)*[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?$',
  );

  /// HTTP header name pattern
  static final RegExp headerNamePattern = RegExp(
    r'^[A-Za-z][A-Za-z0-9\-]*$',
  );

  // ============================================
  // ERROR CODES
  // ============================================

  static const String errorPermissionDenied = 'PERMISSION_DENIED';
  static const String errorConnectionFailed = 'CONNECTION_FAILED';
  static const String errorAuthFailed = 'AUTH_FAILED';
  static const String errorServerUnreachable = 'SERVER_UNREACHABLE';
  static const String errorConfigInvalid = 'CONFIG_INVALID';
  static const String errorCertificateInvalid = 'CERTIFICATE_INVALID';
  static const String errorTimeout = 'TIMEOUT';
  static const String errorNetworkUnavailable = 'NETWORK_UNAVAILABLE';
  static const String errorVpnServiceUnavailable = 'VPN_SERVICE_UNAVAILABLE';
  static const String errorUnknown = 'UNKNOWN_ERROR';
}
