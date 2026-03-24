import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../core/constants/app_constants.dart';
import '../models/vpn_config.dart';
import '../models/vpn_state.dart';

/// VPN Service
/// Handles all communication between Flutter and Native Android VPN service
class VpnService {
  VpnService({
    required FlutterSecureStorage secureStorage,
    required SharedPreferences preferences,
  })  : _secureStorage = secureStorage,
        _preferences = preferences {
    _initMethodChannel();
    _initEventChannels();
  }

  final FlutterSecureStorage _secureStorage;
  final SharedPreferences _preferences;

  // ============================================
  // METHOD CHANNELS
  // ============================================

  late final MethodChannel _methodChannel;
  late final EventChannel _statusEventChannel;
  late final EventChannel _trafficEventChannel;
  late final EventChannel _eventsEventChannel;

  // ============================================
  // STREAM CONTROLLERS
  // ============================================

  final StreamController<VpnStatus> _statusStreamController = 
      StreamController<VpnStatus>.broadcast();
  final StreamController<TrafficStats> _trafficStreamController = 
      StreamController<TrafficStats>.broadcast();
  final StreamController<VpnEvent> _eventsStreamController = 
      StreamController<VpnEvent>.broadcast();

  // ============================================
  // STATE
  // ============================================

  VpnStatus _status = VpnStatus.initial();
  bool _isInitialized = false;

  // ============================================
  // GETTERS
  // ============================================

  VpnStatus get status => _status;
  bool get isInitialized => _isInitialized;
  
  /// Stream of VPN status updates
  Stream<VpnStatus> get statusStream => _statusStreamController.stream;
  
  /// Stream of traffic statistics
  Stream<TrafficStats> get trafficStream => _trafficStreamController.stream;
  
  /// Stream of VPN events
  Stream<VpnEvent> get eventsStream => _eventsStreamController.stream;

  // ============================================
  // INITIALIZATION
  // ============================================

  void _initMethodChannel() {
    _methodChannel = const MethodChannel(AppConstants.channelVpnService);
    _methodChannel.setMethodCallHandler(_handleMethodCall);
  }

  void _initEventChannels() {
    // Status event channel
    _statusEventChannel = const EventChannel(AppConstants.channelVpnState);
    _statusEventChannel.receiveBroadcastStream().listen(
      (data) => _handleStatusUpdate(data),
      onError: (error) => _handleError('Status channel error: $error'),
    );

    // Traffic event channel
    _trafficEventChannel = const EventChannel(AppConstants.channelTrafficStats);
    _trafficEventChannel.receiveBroadcastStream().listen(
      (data) => _handleTrafficUpdate(data),
      onError: (error) => debugPrint('Traffic channel error: $error'),
    );

    // Events event channel
    _eventsEventChannel = const EventChannel(AppConstants.channelConnectionEvents);
    _eventsEventChannel.receiveBroadcastStream().listen(
      (data) => _handleConnectionEvent(data),
      onError: (error) => debugPrint('Events channel error: $error'),
    );
  }

  // ============================================
  // METHOD CALL HANDLERS
  // ============================================

  Future<dynamic> _handleMethodCall(MethodCall call) async {
    switch (call.method) {
      case 'onStatusChanged':
        _handleStatusUpdate(call.arguments);
        break;
      case 'onTrafficUpdate':
        _handleTrafficUpdate(call.arguments);
        break;
      case 'onVpnEvent':
        _handleConnectionEvent(call.arguments);
        break;
      case 'onError':
        _handleError(call.arguments?.toString() ?? 'Unknown error');
        break;
      default:
        debugPrint('Unknown method call: ${call.method}');
    }
  }

  void _handleStatusUpdate(dynamic data) {
    try {
      final Map<String, dynamic> statusData = data is String 
          ? jsonDecode(data) as Map<String, dynamic> 
          : Map<String, dynamic>.from(data as Map);

      final stateString = statusData['state'] as String? ?? 'disconnected';
      final state = _parseConnectionState(stateString);

      _status = _status.copyWith(
        state: state,
        serverName: statusData['serverName'] as String?,
        serverIp: statusData['serverIp'] as String?,
        serverPort: statusData['serverPort'] as int?,
        protocol: statusData['protocol'] as String?,
        connectedAt: statusData['connectedAt'] != null
            ? DateTime.tryParse(statusData['connectedAt'] as String)
            : null,
        duration: statusData['duration'] != null
            ? Duration(seconds: (statusData['duration'] as num).toInt())
            : null,
        latency: statusData['latency'] as int?,
        errorMessage: statusData['errorMessage'] as String?,
        localIp: statusData['localIp'] as String?,
        remoteIp: statusData['remoteIp'] as String?,
      );

      _statusStreamController.add(_status);
    } catch (e) {
      debugPrint('Error parsing status update: $e');
    }
  }

  void _handleTrafficUpdate(dynamic data) {
    try {
      final Map<String, dynamic> trafficData = data is String 
          ? jsonDecode(data) as Map<String, dynamic> 
          : Map<String, dynamic>.from(data as Map);

      final stats = TrafficStats(
        timestamp: DateTime.now(),
        bytesIn: (trafficData['bytesIn'] as num?)?.toInt() ?? 0,
        bytesOut: (trafficData['bytesOut'] as num?)?.toInt() ?? 0,
        speedDown: (trafficData['speedDown'] as num?)?.toInt() ?? 0,
        speedUp: (trafficData['speedUp'] as num?)?.toInt() ?? 0,
      );

      _status = _status.copyWith(
        bytesIn: stats.bytesIn,
        bytesOut: stats.bytesOut,
        currentSpeedDown: stats.speedDown,
        currentSpeedUp: stats.speedUp,
      );

      _trafficStreamController.add(stats);
      _statusStreamController.add(_status);
    } catch (e) {
      debugPrint('Error parsing traffic update: $e');
    }
  }

  void _handleConnectionEvent(dynamic data) {
    try {
      final Map<String, dynamic> eventData = data is String 
          ? jsonDecode(data) as Map<String, dynamic> 
          : Map<String, dynamic>.from(data as Map);

      final typeString = eventData['type'] as String? ?? 'unknown';
      final event = VpnEvent(
        type: _parseEventType(typeString),
        message: eventData['message'] as String? ?? '',
        timestamp: DateTime.now(),
        data: eventData['data'] != null 
            ? Map<String, dynamic>.from(eventData['data'] as Map)
            : null,
      );

      _eventsStreamController.add(event);
    } catch (e) {
      debugPrint('Error parsing connection event: $e');
    }
  }

  void _handleError(String error) {
    debugPrint('VPN Error: $error');
    
    _status = _status.copyWith(
      state: VpnConnectionState.error,
      errorMessage: error,
    );

    _statusStreamController.add(_status);
    
    _eventsStreamController.add(VpnEvent(
      type: VpnEventType.error,
      message: error,
      timestamp: DateTime.now(),
    ));
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

  VpnEventType _parseEventType(String type) {
    return VpnEventType.values.firstWhere(
      (t) => t.name.toLowerCase() == type.toLowerCase(),
      orElse: () => VpnEventType.unknown,
    );
  }

  // ============================================
  // PUBLIC METHODS
  // ============================================

  /// Initialize the VPN service
  Future<void> initialize() async {
    if (_isInitialized) return;

    try {
      // Check permission status
      final hasPermission = await hasVpnPermission();
      debugPrint('VPN permission status: $hasPermission');

      _isInitialized = true;
      debugPrint('VPN Service initialized');
    } catch (e) {
      debugPrint('Failed to initialize VPN service: $e');
      // Still mark as initialized - user can try to connect
      _isInitialized = true;
    }
  }

  /// Check if VPN permission is granted
  Future<bool> hasVpnPermission() async {
    try {
      final result = await _methodChannel.invokeMethod<bool>(
        AppConstants.methodHasPermission,
      );
      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('Failed to check VPN permission: ${e.message}');
      return false;
    }
  }

  /// Request VPN permission
  Future<bool> requestVpnPermission() async {
    try {
      final result = await _methodChannel.invokeMethod<bool>(
        AppConstants.methodRequestPermission,
      );
      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('Failed to request VPN permission: ${e.message}');
      return false;
    }
  }

  /// Connect to VPN with the given configuration
  Future<bool> connect(VpnConfig config) async {
    if (!config.isValid) {
      _handleError('Invalid VPN configuration');
      return false;
    }

    try {
      // Update to connecting state immediately
      _status = _status.copyWith(
        state: VpnConnectionState.connecting,
        serverName: config.server?.name,
        serverIp: config.server?.serverIp,
        serverPort: config.server?.port,
        protocol: config.server?.protocol,
      );
      _statusStreamController.add(_status);

      final result = await _methodChannel.invokeMethod<bool>(
        AppConstants.methodConnect,
        config.toMap(),
      );

      if (result == true) {
        // Save last used server
        await _preferences.setString(
          AppConstants.keyLastServer,
          jsonEncode(config.server?.toMap()),
        );
      }

      return result ?? false;
    } on PlatformException catch (e) {
      _handleError('Failed to connect: ${e.message}');
      return false;
    }
  }

  /// Disconnect from VPN
  Future<bool> disconnect() async {
    try {
      _status = _status.copyWith(state: VpnConnectionState.disconnecting);
      _statusStreamController.add(_status);

      final result = await _methodChannel.invokeMethod<bool>(
        AppConstants.methodDisconnect,
      );

      return result ?? false;
    } on PlatformException catch (e) {
      _handleError('Failed to disconnect: ${e.message}');
      return false;
    }
  }

  /// Get current VPN status
  Future<VpnStatus> getStatus() async {
    try {
      final result = await _methodChannel.invokeMethod<Map<dynamic, dynamic>>(
        AppConstants.methodGetStatus,
      );

      if (result != null) {
        _handleStatusUpdate(result);
      }

      return _status;
    } on PlatformException catch (e) {
      debugPrint('Failed to get status: ${e.message}');
      return _status;
    }
  }

  /// Set custom HTTP headers
  Future<bool> setHttpHeaders(List<HttpHeader> headers) async {
    try {
      final result = await _methodChannel.invokeMethod<bool>(
        AppConstants.methodSetHeaders,
        {
          'headers': headers.map((h) => h.toMap()).toList(),
        },
      );

      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('Failed to set HTTP headers: ${e.message}');
      return false;
    }
  }

  /// Set SNI configuration
  Future<bool> setSni(SniConfig sniConfig) async {
    try {
      final result = await _methodChannel.invokeMethod<bool>(
        AppConstants.methodSetSni,
        sniConfig.toMap(),
      );

      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('Failed to set SNI: ${e.message}');
      return false;
    }
  }

  /// Configure VPN with full configuration
  Future<bool> configure(VpnConfig config) async {
    try {
      final result = await _methodChannel.invokeMethod<bool>(
        AppConstants.methodConfigure,
        config.toMap(),
      );

      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('Failed to configure VPN: ${e.message}');
      return false;
    }
  }

  // ============================================
  // CLEANUP
  // ============================================

  void dispose() {
    _statusStreamController.close();
    _trafficStreamController.close();
    _eventsStreamController.close();
  }
}

/// VPN Event Types
enum VpnEventType {
  unknown,
  connecting,
  connected,
  disconnecting,
  disconnected,
  reconnecting,
  error,
  permissionRequired,
  configurationChanged,
  serverChanged,
}

/// VPN Event Model
class VpnEvent {
  const VpnEvent({
    required this.type,
    required this.message,
    required this.timestamp,
    this.data,
  });

  final VpnEventType type;
  final String message;
  final DateTime timestamp;
  final Map<String, dynamic>? data;
}
