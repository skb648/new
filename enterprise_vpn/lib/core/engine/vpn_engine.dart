import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../../models/vpn_config.dart';
import '../../models/vpn_state.dart';

/// VPN Engine - Flutter-Native Communication Bridge
/// Handles all MethodChannel and EventChannel communication with native Android
class VpnEngine with ChangeNotifier {
  VpnEngine._();

  static final VpnEngine _instance = VpnEngine._();
  static VpnEngine get instance => _instance;

  // ============================================
  // CHANNEL CONSTANTS
  // ============================================

  static const String _methodChannelName = 'com.enterprise.vpn/service';
  static const String _stateChannelName = 'com.enterprise.vpn/state';
  static const String _trafficChannelName = 'com.enterprise.vpn/traffic';
  static const String _eventsChannelName = 'com.enterprise.vpn/events';

  // Method names
  static const String methodConnect = 'connect';
  static const String methodDisconnect = 'disconnect';
  static const String methodGetStatus = 'getStatus';
  static const String methodGetStats = 'getStats';
  static const String methodConfigure = 'configure';
  static const String methodSetHeaders = 'setHttpHeaders';
  static const String methodSetSni = 'setSni';
  static const String methodRequestPermission = 'requestVpnPermission';
  static const String methodHasPermission = 'hasVpnPermission';
  static const String methodPrepareVpn = 'prepareVpn';
  static const String methodIsVpnPrepared = 'isVpnPrepared';
  static const String methodGetConnectionInfo = 'getConnectionInfo';
  static const String methodSetKillSwitch = 'setKillSwitch';
  static const String methodSetDnsServers = 'setDnsServers';
  static const String methodSetMtu = 'setMtu';
  static const String methodStartSpeedTest = 'startSpeedTest';
  static const String methodStopSpeedTest = 'stopSpeedTest';

  // ============================================
  // CHANNELS
  // ============================================

  late final MethodChannel _methodChannel;
  late final EventChannel _stateChannel;
  late final EventChannel _trafficChannel;
  late final EventChannel _eventsChannel;

  // ============================================
  // STREAM CONTROLLERS
  // ============================================

  final StreamController<VpnStatus> _statusController = 
      StreamController<VpnStatus>.broadcast();
  final StreamController<TrafficStats> _trafficController = 
      StreamController<TrafficStats>.broadcast();
  final StreamController<VpnEvent> _eventsController = 
      StreamController<VpnEvent>.broadcast();
  final StreamController<ConnectionInfo> _connectionInfoController = 
      StreamController<ConnectionInfo>.broadcast();

  // ============================================
  // STATE
  // ============================================

  VpnStatus _status = VpnStatus.initial();
  bool _isInitialized = false;
  List<HttpHeader> _currentHeaders = [];
  SniConfig? _currentSniConfig;
  VpnConfig? _currentConfig;

  // Stream subscriptions
  StreamSubscription? _stateSubscription;
  StreamSubscription? _trafficSubscription;
  StreamSubscription? _eventsSubscription;

  // ============================================
  // GETTERS
  // ============================================

  VpnStatus get status => _status;
  bool get isInitialized => _isInitialized;
  bool get isConnected => _status.isConnected;
  bool get isConnecting => _status.isConnecting;
  List<HttpHeader> get currentHeaders => List.unmodifiable(_currentHeaders);
  SniConfig? get currentSniConfig => _currentSniConfig;
  VpnConfig? get currentConfig => _currentConfig;

  // Streams
  Stream<VpnStatus> get statusStream => _statusController.stream;
  Stream<TrafficStats> get trafficStream => _trafficController.stream;
  Stream<VpnEvent> get eventsStream => _eventsController.stream;
  Stream<ConnectionInfo> get connectionInfoStream => _connectionInfoController.stream;

  // ============================================
  // INITIALIZATION
  // ============================================

  /// Initialize the VPN engine
  Future<void> initialize() async {
    if (_isInitialized) return;

    try {
      // Initialize method channel
      _methodChannel = const MethodChannel(_methodChannelName);
      _methodChannel.setMethodCallHandler(_handleMethodCall);

      // Initialize event channels
      _stateChannel = const EventChannel(_stateChannelName);
      _trafficChannel = const EventChannel(_trafficChannelName);
      _eventsChannel = const EventChannel(_eventsChannelName);

      // Listen to event channels
      _stateSubscription = _stateChannel.receiveBroadcastStream().listen(
        _handleStateUpdate,
        onError: (error) => _handleError('State channel error: $error'),
      );

      _trafficSubscription = _trafficChannel.receiveBroadcastStream().listen(
        _handleTrafficUpdate,
        onError: (error) => _handleError('Traffic channel error: $error'),
      );

      _eventsSubscription = _eventsChannel.receiveBroadcastStream().listen(
        _handleConnectionEvent,
        onError: (error) => _handleError('Events channel error: $error'),
      );

      _isInitialized = true;
      debugPrint('[VpnEngine] Initialized successfully');
    } catch (e) {
      debugPrint('[VpnEngine] Initialization failed: $e');
      rethrow;
    }
  }

  // ============================================
  // METHOD CALL HANDLER (Native -> Flutter)
  // ============================================

  Future<dynamic> _handleMethodCall(MethodCall call) async {
    debugPrint('[VpnEngine] Method call from native: ${call.method}');

    switch (call.method) {
      case 'onStatusChanged':
        _handleStateUpdate(call.arguments);
        return null;

      case 'onTrafficUpdate':
        _handleTrafficUpdate(call.arguments);
        return null;

      case 'onConnectionEvent':
        _handleConnectionEvent(call.arguments);
        return null;

      case 'onError':
        final message = call.arguments as String? ?? 'Unknown error';
        _handleError(message);
        return null;

      case 'onSpeedTestResult':
        _handleSpeedTestResult(call.arguments);
        return null;

      case 'onPermissionResult':
        final granted = call.arguments as bool? ?? false;
        _eventsController.add(VpnEvent(
          type: granted ? VpnEventType.permissionRequired : VpnEventType.error,
          message: granted ? 'Permission granted' : 'Permission denied',
          timestamp: DateTime.now(),
        ));
        return null;

      default:
        debugPrint('[VpnEngine] Unknown method call: ${call.method}');
        return null;
    }
  }

  // ============================================
  // EVENT HANDLERS
  // ============================================

  void _handleStateUpdate(dynamic data) {
    try {
      final Map<String, dynamic> stateData = data is String
          ? jsonDecode(data) as Map<String, dynamic>
          : data as Map<String, dynamic>;

      final stateString = stateData['state'] as String? ?? 'disconnected';
      final state = _parseConnectionState(stateString);

      _status = VpnStatus(
        state: state,
        serverName: stateData['serverName'] as String?,
        serverIp: stateData['serverIp'] as String?,
        serverPort: stateData['serverPort'] as int?,
        protocol: stateData['protocol'] as String?,
        connectedAt: stateData['connectedAt'] != null
            ? DateTime.parse(stateData['connectedAt'] as String)
            : null,
        duration: stateData['duration'] != null
            ? Duration(seconds: stateData['duration'] as int)
            : Duration.zero,
        bytesIn: stateData['bytesIn'] as int? ?? _status.bytesIn,
        bytesOut: stateData['bytesOut'] as int? ?? _status.bytesOut,
        currentSpeedDown: stateData['speedDown'] as int? ?? _status.currentSpeedDown,
        currentSpeedUp: stateData['speedUp'] as int? ?? _status.currentSpeedUp,
        latency: stateData['latency'] as int?,
        errorMessage: stateData['errorMessage'] as String?,
        localIp: stateData['localIp'] as String?,
        remoteIp: stateData['remoteIp'] as String?,
      );

      _statusController.add(_status);
      notifyListeners();

      debugPrint('[VpnEngine] State updated: $stateString');
    } catch (e) {
      debugPrint('[VpnEngine] Error parsing state update: $e');
    }
  }

  void _handleTrafficUpdate(dynamic data) {
    try {
      final Map<String, dynamic> trafficData = data is String
          ? jsonDecode(data) as Map<String, dynamic>
          : data as Map<String, dynamic>;

      final stats = TrafficStats(
        timestamp: DateTime.now(),
        bytesIn: trafficData['bytesIn'] as int? ?? 0,
        bytesOut: trafficData['bytesOut'] as int? ?? 0,
        speedDown: trafficData['speedDown'] as int? ?? 0,
        speedUp: trafficData['speedUp'] as int? ?? 0,
      );

      _status = _status.copyWith(
        bytesIn: stats.bytesIn,
        bytesOut: stats.bytesOut,
        currentSpeedDown: stats.speedDown,
        currentSpeedUp: stats.speedUp,
      );

      _trafficController.add(stats);
      notifyListeners();
    } catch (e) {
      debugPrint('[VpnEngine] Error parsing traffic update: $e');
    }
  }

  void _handleConnectionEvent(dynamic data) {
    try {
      final Map<String, dynamic> eventData = data is String
          ? jsonDecode(data) as Map<String, dynamic>
          : data as Map<String, dynamic>;

      final eventTypeStr = eventData['type'] as String? ?? 'unknown';
      final eventType = VpnEventType.values.firstWhere(
        (t) => t.name.toLowerCase() == eventTypeStr.toLowerCase(),
        orElse: () => VpnEventType.unknown,
      );

      final event = VpnEvent(
        type: eventType,
        message: eventData['message'] as String? ?? '',
        timestamp: eventData['timestamp'] != null
            ? DateTime.parse(eventData['timestamp'] as String)
            : DateTime.now(),
        data: eventData['data'] as Map<String, dynamic>?,
      );

      _eventsController.add(event);
      debugPrint('[VpnEngine] Event: $eventType - ${event.message}');
    } catch (e) {
      debugPrint('[VpnEngine] Error parsing connection event: $e');
    }
  }

  void _handleSpeedTestResult(dynamic data) {
    try {
      final Map<String, dynamic> resultData = data is String
          ? jsonDecode(data) as Map<String, dynamic>
          : data as Map<String, dynamic>;

      final info = ConnectionInfo(
        downloadSpeed: resultData['downloadSpeed'] as int? ?? 0,
        uploadSpeed: resultData['uploadSpeed'] as int? ?? 0,
        latency: resultData['latency'] as int? ?? 0,
        jitter: resultData['jitter'] as int?,
        packetLoss: resultData['packetLoss'] as double?,
      );

      _connectionInfoController.add(info);
    } catch (e) {
      debugPrint('[VpnEngine] Error parsing speed test result: $e');
    }
  }

  void _handleError(String error) {
    debugPrint('[VpnEngine] Error: $error');

    _status = _status.copyWith(
      state: VpnConnectionState.error,
      errorMessage: error,
    );

    _statusController.add(_status);

    _eventsController.add(VpnEvent(
      type: VpnEventType.error,
      message: error,
      timestamp: DateTime.now(),
    ));

    notifyListeners();
  }

  VpnConnectionState _parseConnectionState(String state) {
    switch (state.toLowerCase()) {
      case 'connected':
        return VpnConnectionState.connected;
      case 'connecting':
        return VpnConnectionState.connecting;
      case 'disconnecting':
        return VpnConnectionState.disconnecting;
      case 'reconnecting':
        return VpnConnectionState.reconnecting;
      case 'authenticating':
        return VpnConnectionState.authenticating;
      case 'error':
        return VpnConnectionState.error;
      case 'no_network':
        return VpnConnectionState.noNetwork;
      case 'disconnected':
      default:
        return VpnConnectionState.disconnected;
    }
  }

  // ============================================
  // PUBLIC API METHODS
  // ============================================

  /// Check if VPN permission is granted
  Future<bool> hasVpnPermission() async {
    try {
      final result = await _methodChannel.invokeMethod<bool>(methodHasPermission);
      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('[VpnEngine] hasVpnPermission error: ${e.message}');
      return false;
    }
  }

  /// Request VPN permission (prepares VPN service)
  Future<bool> requestVpnPermission() async {
    try {
      final result = await _methodChannel.invokeMethod<bool>(methodRequestPermission);
      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('[VpnEngine] requestVpnPermission error: ${e.message}');
      return false;
    }
  }

  /// Check if VPN is prepared
  Future<bool> isVpnPrepared() async {
    try {
      final result = await _methodChannel.invokeMethod<bool>(methodIsVpnPrepared);
      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('[VpnEngine] isVpnPrepared error: ${e.message}');
      return false;
    }
  }

  /// Prepare VPN (shows system VPN permission dialog)
  Future<bool> prepareVpn() async {
    try {
      final result = await _methodChannel.invokeMethod<bool>(methodPrepareVpn);
      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('[VpnEngine] prepareVpn error: ${e.message}');
      return false;
    }
  }

  /// Connect to VPN with full configuration
  Future<ConnectionResult> connect(VpnConfig config) async {
    if (!config.isValid) {
      return ConnectionResult(
        success: false,
        error: 'Invalid VPN configuration',
        errorCode: 'CONFIG_INVALID',
      );
    }

    try {
      _currentConfig = config;
      _currentHeaders = List.from(config.httpHeaders);
      _currentSniConfig = config.sniConfig;

      // Update status to connecting
      _status = _status.copyWith(
        state: VpnConnectionState.connecting,
        serverName: config.server?.name,
        serverIp: config.server?.serverIp,
        serverPort: config.server?.port,
        protocol: config.server?.protocol,
      );
      _statusController.add(_status);
      notifyListeners();

      // Prepare config map for native
      final configMap = _buildConfigMap(config);

      final result = await _methodChannel.invokeMethod<Map<dynamic, dynamic>>(
        methodConnect,
        configMap,
      );

      if (result != null) {
        final success = result['success'] as bool? ?? false;
        return ConnectionResult(
          success: success,
          error: result['error'] as String?,
          errorCode: result['errorCode'] as String?,
        );
      }

      return const ConnectionResult(success: true);
    } on PlatformException catch (e) {
      debugPrint('[VpnEngine] connect error: ${e.message}');
      return ConnectionResult(
        success: false,
        error: e.message ?? 'Connection failed',
        errorCode: e.code,
      );
    }
  }

  /// Disconnect from VPN
  Future<bool> disconnect() async {
    try {
      _status = _status.copyWith(state: VpnConnectionState.disconnecting);
      _statusController.add(_status);
      notifyListeners();

      final result = await _methodChannel.invokeMethod<bool>(methodDisconnect);
      
      if (result == true) {
        _currentConfig = null;
      }

      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('[VpnEngine] disconnect error: ${e.message}');
      return false;
    }
  }

  /// Get current VPN status
  Future<VpnStatus> getStatus() async {
    try {
      final result = await _methodChannel.invokeMethod<Map<dynamic, dynamic>>(
        methodGetStatus,
      );

      if (result != null) {
        _handleStateUpdate(result);
      }

      return _status;
    } on PlatformException catch (e) {
      debugPrint('[VpnEngine] getStatus error: ${e.message}');
      return _status;
    }
  }

  /// Get current traffic statistics
  Future<TrafficStats?> getStats() async {
    try {
      final result = await _methodChannel.invokeMethod<Map<dynamic, dynamic>>(
        methodGetStats,
      );

      if (result != null) {
        return TrafficStats(
          timestamp: DateTime.now(),
          bytesIn: result['bytesIn'] as int? ?? 0,
          bytesOut: result['bytesOut'] as int? ?? 0,
          speedDown: result['speedDown'] as int? ?? 0,
          speedUp: result['speedUp'] as int? ?? 0,
        );
      }
      return null;
    } on PlatformException catch (e) {
      debugPrint('[VpnEngine] getStats error: ${e.message}');
      return null;
    }
  }

  /// Set custom HTTP headers for traffic injection
  Future<bool> setHttpHeaders(List<HttpHeader> headers) async {
    try {
      final headersMap = {
        'headers': headers.map((h) => {
          'name': h.name,
          'value': h.value,
          'enabled': h.enabled,
        }).toList(),
      };

      final result = await _methodChannel.invokeMethod<bool>(
        methodSetHeaders,
        headersMap,
      );

      if (result == true) {
        _currentHeaders = List.from(headers);
      }

      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('[VpnEngine] setHttpHeaders error: ${e.message}');
      return false;
    }
  }

  /// Set SNI configuration for TLS connections
  Future<bool> setSni(SniConfig sniConfig) async {
    try {
      final result = await _methodChannel.invokeMethod<bool>(
        methodSetSni,
        {
          'serverName': sniConfig.serverName,
          'enabled': sniConfig.enabled,
          'allowOverride': sniConfig.allowOverride,
        },
      );

      if (result == true) {
        _currentSniConfig = sniConfig;
      }

      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('[VpnEngine] setSni error: ${e.message}');
      return false;
    }
  }

  /// Configure VPN with full settings
  Future<bool> configure(VpnConfig config) async {
    try {
      final configMap = _buildConfigMap(config);
      final result = await _methodChannel.invokeMethod<bool>(
        methodConfigure,
        configMap,
      );

      if (result == true) {
        _currentConfig = config;
      }

      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('[VpnEngine] configure error: ${e.message}');
      return false;
    }
  }

  /// Set kill switch mode
  Future<bool> setKillSwitch(bool enabled) async {
    try {
      final result = await _methodChannel.invokeMethod<bool>(
        methodSetKillSwitch,
        {'enabled': enabled},
      );
      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('[VpnEngine] setKillSwitch error: ${e.message}');
      return false;
    }
  }

  /// Set custom DNS servers
  Future<bool> setDnsServers(List<String> servers) async {
    try {
      final result = await _methodChannel.invokeMethod<bool>(
        methodSetDnsServers,
        {'servers': servers},
      );
      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('[VpnEngine] setDnsServers error: ${e.message}');
      return false;
    }
  }

  /// Set MTU size
  Future<bool> setMtu(int mtu) async {
    try {
      final result = await _methodChannel.invokeMethod<bool>(
        methodSetMtu,
        {'mtu': mtu},
      );
      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('[VpnEngine] setMtu error: ${e.message}');
      return false;
    }
  }

  /// Start speed test
  Future<bool> startSpeedTest() async {
    try {
      final result = await _methodChannel.invokeMethod<bool>(methodStartSpeedTest);
      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('[VpnEngine] startSpeedTest error: ${e.message}');
      return false;
    }
  }

  /// Stop speed test
  Future<bool> stopSpeedTest() async {
    try {
      final result = await _methodChannel.invokeMethod<bool>(methodStopSpeedTest);
      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('[VpnEngine] stopSpeedTest error: ${e.message}');
      return false;
    }
  }

  /// Get detailed connection info
  Future<ConnectionInfo?> getConnectionInfo() async {
    try {
      final result = await _methodChannel.invokeMethod<Map<dynamic, dynamic>>(
        methodGetConnectionInfo,
      );

      if (result != null) {
        return ConnectionInfo(
          downloadSpeed: result['downloadSpeed'] as int? ?? 0,
          uploadSpeed: result['uploadSpeed'] as int? ?? 0,
          latency: result['latency'] as int? ?? 0,
          jitter: result['jitter'] as int?,
          packetLoss: result['packetLoss'] as double?,
        );
      }
      return null;
    } on PlatformException catch (e) {
      debugPrint('[VpnEngine] getConnectionInfo error: ${e.message}');
      return null;
    }
  }

  // ============================================
  // HELPER METHODS
  // ============================================

  Map<String, dynamic> _buildConfigMap(VpnConfig config) {
    return {
      'server': {
        'id': config.server?.id ?? '',
        'name': config.server?.name ?? '',
        'serverIp': config.server?.serverIp ?? '',
        'port': config.server?.port ?? 443,
        'protocol': config.server?.protocol ?? 'TCP',
        'username': config.server?.username ?? '',
        'password': config.server?.password ?? '',
      },
      'httpHeaders': config.httpHeaders
          .where((h) => h.enabled)
          .map((h) => {
                'name': h.name,
                'value': h.value,
                'enabled': h.enabled,
              })
          .toList(),
      'sniConfig': config.sniConfig != null
          ? {
              'serverName': config.sniConfig!.serverName,
              'enabled': config.sniConfig!.enabled,
              'allowOverride': config.sniConfig!.allowOverride,
            }
          : null,
      'autoConnect': config.autoConnect,
      'killSwitch': config.killSwitch,
      'dnsLeakProtection': config.dnsLeakProtection,
      'ipv6Enabled': config.ipv6Enabled,
      'splitTunnelEnabled': config.splitTunnelEnabled,
      'excludedApps': config.excludedApps,
      'customDns': config.customDns,
      'mtu': config.mtu,
      // Additional routing configuration
      'customPayload': '',
      'connectionTimeout': 30000,
      'readTimeout': 60000,
    };
  }

  // ============================================
  // CLEANUP
  // ============================================

  /// Dispose all resources
  void dispose() {
    _stateSubscription?.cancel();
    _trafficSubscription?.cancel();
    _eventsSubscription?.cancel();
    _statusController.close();
    _trafficController.close();
    _eventsController.close();
    _connectionInfoController.close();
    _isInitialized = false;
    super.dispose();
  }
}

/// Connection Result Model
class ConnectionResult {
  const ConnectionResult({
    required this.success,
    this.error,
    this.errorCode,
  });

  final bool success;
  final String? error;
  final String? errorCode;

  bool get hasError => error != null;
}

/// Connection Info Model
class ConnectionInfo {
  const ConnectionInfo({
    required this.downloadSpeed,
    required this.uploadSpeed,
    required this.latency,
    this.jitter,
    this.packetLoss,
  });

  final int downloadSpeed; // bytes per second
  final int uploadSpeed; // bytes per second
  final int latency; // milliseconds
  final int? jitter; // milliseconds
  final double? packetLoss; // percentage

  String get formattedDownloadSpeed => _formatSpeed(downloadSpeed);
  String get formattedUploadSpeed => _formatSpeed(uploadSpeed);
  String get formattedLatency => '${latency}ms';

  static String _formatSpeed(int bytesPerSec) {
    if (bytesPerSec < 1024) {
      return '$bytesPerSec B/s';
    } else if (bytesPerSec < 1024 * 1024) {
      return '${(bytesPerSec / 1024).toStringAsFixed(1)} KB/s';
    } else if (bytesPerSec < 1024 * 1024 * 1024) {
      return '${(bytesPerSec / (1024 * 1024)).toStringAsFixed(1)} MB/s';
    } else {
      return '${(bytesPerSec / (1024 * 1024 * 1024)).toStringAsFixed(2)} GB/s';
    }
  }
}
