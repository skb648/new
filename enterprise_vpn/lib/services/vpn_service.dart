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
class VpnService with ChangeNotifier {
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
  late final EventChannel _stateChannel;
  late final EventChannel _trafficChannel;
  late final EventChannel _eventsChannel;

  // ============================================
  // STREAM CONTROLLERS
  // ============================================

  final StreamController<VpnStatus> _statusController = StreamController<VpnStatus>.broadcast();
  final StreamController<TrafficStats> _trafficController = StreamController<TrafficStats>.broadcast();
  final StreamController<VpnEvent> _eventsController = StreamController<VpnEvent>.broadcast();

  // ============================================
  // STATE
  // ============================================

  VpnStatus _status = VpnStatus.initial();
  bool _isInitialized = false;

  // ============================================
  // GETTERS
  // ============================================

  VpnStatus get status => _status;
  Stream<VpnStatus> get statusStream => _statusController.stream;
  Stream<TrafficStats> get trafficStream => _trafficController.stream;
  Stream<VpnEvent> get eventsStream => _eventsController.stream;
  bool get isInitialized => _isInitialized;

  // ============================================
  // INITIALIZATION
  // ============================================

  void _initMethodChannel() {
    _methodChannel = const MethodChannel(AppConstants.channelVpnService);
    _methodChannel.setMethodCallHandler(_handleMethodCall);
  }

  void _initEventChannels() {
    _stateChannel = const EventChannel(AppConstants.channelVpnState);
    _stateChannel.receiveBroadcastStream().listen(
      _handleStateUpdate,
      onError: (error) => _handleError('State channel error: $error'),
    );

    _trafficChannel = const EventChannel(AppConstants.channelTrafficStats);
    _trafficChannel.receiveBroadcastStream().listen(
      _handleTrafficUpdate,
      onError: (error) => _handleError('Traffic channel error: $error'),
    );

    _eventsChannel = const EventChannel(AppConstants.channelConnectionEvents);
    _eventsChannel.receiveBroadcastStream().listen(
      _handleConnectionEvent,
      onError: (error) => _handleError('Events channel error: $error'),
    );
  }

  // ============================================
  // METHOD CALL HANDLERS
  // ============================================

  Future<dynamic> _handleMethodCall(MethodCall call) async {
    switch (call.method) {
      case 'onStatusChanged':
        _handleStateUpdate(call.arguments);
        break;
      case 'onTrafficUpdate':
        _handleTrafficUpdate(call.arguments);
        break;
      case 'onError':
        _handleError(call.arguments as String);
        break;
      default:
        debugPrint('Unknown method call: ${call.method}');
    }
  }

  void _handleStateUpdate(dynamic data) {
    try {
      final Map<String, dynamic> stateData = data is String 
          ? jsonDecode(data) as Map<String, dynamic> 
          : data as Map<String, dynamic>;

      final stateString = stateData['state'] as String? ?? 'disconnected';
      final state = _parseConnectionState(stateString);

      _status = _status.copyWith(
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
        latency: stateData['latency'] as int?,
        errorMessage: stateData['errorMessage'] as String?,
        localIp: stateData['localIp'] as String?,
        remoteIp: stateData['remoteIp'] as String?,
      );

      _statusController.add(_status);
      notifyListeners();
    } catch (e) {
      debugPrint('Error parsing state update: $e');
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
      debugPrint('Error parsing traffic update: $e');
    }
  }

  void _handleConnectionEvent(dynamic data) {
    try {
      final Map<String, dynamic> eventData = data is String 
          ? jsonDecode(data) as Map<String, dynamic> 
          : data as Map<String, dynamic>;

      final event = VpnEvent(
        type: VpnEventType.values.firstWhere(
          (t) => t.name == eventData['type'],
          orElse: () => VpnEventType.unknown,
        ),
        message: eventData['message'] as String? ?? '',
        timestamp: DateTime.now(),
        data: eventData['data'] as Map<String, dynamic>?,
      );

      _eventsController.add(event);
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
  // PUBLIC METHODS
  // ============================================

  /// Initialize the VPN service
  Future<void> initialize() async {
    if (_isInitialized) return;

    try {
      final hasPermission = await hasVpnPermission();
      if (!hasPermission) {
        debugPrint('VPN permission not granted');
      }

      _isInitialized = true;
      debugPrint('VPN Service initialized');
    } catch (e) {
      debugPrint('Failed to initialize VPN service: $e');
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
      // Update state to connecting
      _status = _status.copyWith(
        state: VpnConnectionState.connecting,
        serverName: config.server?.name,
        serverIp: config.server?.serverIp,
        serverPort: config.server?.port,
        protocol: config.server?.protocol,
      );
      _statusController.add(_status);
      notifyListeners();

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
      _statusController.add(_status);
      notifyListeners();

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
        _handleStateUpdate(result);
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
    _statusController.close();
    _trafficController.close();
    _eventsController.close();
    super.dispose();
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
