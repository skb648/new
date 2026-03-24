import 'dart:async';

import 'package:flutter/foundation.dart';

import '../engine/vpn_engine.dart';
import '../error/vpn_exceptions.dart';
import '../network/network_monitor.dart';
import '../../models/vpn_config.dart';
import '../../models/vpn_state.dart';

/// VPN Connection Manager
/// 
/// High-level manager that coordinates VPN connection lifecycle,
/// error handling, recovery, and state management.
class VpnConnectionManager with ChangeNotifier {
  VpnConnectionManager({
    required this.networkMonitor,
  }) {
    _init();
  }

  final NetworkConnectivityMonitor networkMonitor;

  // Engine instance
  final VpnEngine _engine = VpnEngine.instance;

  // Connection state
  VpnStatus _status = VpnStatus.initial();
  VpnConfig? _config;
  
  // Recovery manager
  late final ConnectionRecoveryManager _recoveryManager;
  
  // Subscriptions
  StreamSubscription? _statusSubscription;
  StreamSubscription? _eventSubscription;
  StreamSubscription? _networkSubscription;

  // State
  bool _isInitialized = false;
  bool _autoReconnect = true;
  bool _isReconnecting = false;

  // ============================================
  // GETTERS
  // ============================================

  VpnStatus get status => _status;
  VpnConfig? get config => _config;
  bool get isInitialized => _isInitialized;
  bool get isConnected => _status.isConnected;
  bool get isConnecting => _status.isConnecting;
  bool get autoReconnect => _autoReconnect;

  // ============================================
  // INITIALIZATION
  // ============================================

  void _init() {
    // Initialize recovery manager
    _recoveryManager = ConnectionRecoveryManager(
      onReconnect: _performReconnection,
      maxRetryAttempts: 5,
    );

    // Listen to recovery state
    _recoveryManager.stateStream.listen(_handleRecoveryState);

    // Listen to network changes
    _networkSubscription = networkMonitor.events.listen(_handleNetworkChange);

    // Listen to engine events
    _statusSubscription = _engine.statusStream.listen(_handleStatusUpdate);
    _eventSubscription = _engine.eventsStream.listen(_handleVpnEvent);

    _isInitialized = true;
  }

  // ============================================
  // PUBLIC API
  // ============================================

  /// Initialize the VPN engine
  Future<void> initialize() async {
    if (!_isInitialized) {
      await _engine.initialize();
      _isInitialized = true;
    }
  }

  /// Connect to VPN with configuration
  Future<ConnectionResult> connect(VpnConfig config) async {
    if (_status.isConnecting || _status.state == VpnConnectionState.connecting) {
      return const ConnectionResult(
        success: false,
        error: 'Connection already in progress',
        errorCode: 'ALREADY_CONNECTING',
      );
    }

    _config = config;

    // Check network availability
    if (!networkMonitor.isConnected) {
      _updateStatus(_status.copyWith(
        state: VpnConnectionState.noNetwork,
        errorMessage: 'No network connection available',
      ));
      return const ConnectionResult(
        success: false,
        error: 'No network connection',
        errorCode: 'NO_NETWORK',
      );
    }

    // Check VPN permission
    final hasPermission = await _engine.hasVpnPermission();
    if (!hasPermission) {
      final granted = await _engine.requestVpnPermission();
      if (!granted) {
        _updateStatus(_status.copyWith(
          state: VpnConnectionState.error,
          errorMessage: 'VPN permission denied',
        ));
        return const ConnectionResult(
          success: false,
          error: 'VPN permission denied',
          errorCode: 'PERMISSION_DENIED',
        );
      }
    }

    // Reset recovery state
    _recoveryManager.reset();

    // Perform connection
    try {
      final result = await _engine.connect(config);
      
      if (result.success) {
        _isReconnecting = false;
      }
      
      return result;
    } catch (e) {
      _handleConnectionError(e);
      return ConnectionResult(
        success: false,
        error: e.toString(),
        errorCode: 'CONNECTION_ERROR',
      );
    }
  }

  /// Disconnect from VPN
  Future<void> disconnect() async {
    _recoveryManager.cancel();
    _isReconnecting = false;
    
    await _engine.disconnect();
  }

  /// Reconnect with current configuration
  Future<void> reconnect() async {
    if (_config == null) {
      debugPrint('No configuration available for reconnection');
      return;
    }

    await disconnect();
    await Future.delayed(const Duration(milliseconds: 500));
    await connect(_config!);
  }

  /// Toggle auto-reconnect
  void setAutoReconnect(bool enabled) {
    _autoReconnect = enabled;
    notifyListeners();
  }

  /// Update HTTP headers
  Future<bool> updateHttpHeaders(List<HttpHeader> headers) async {
    return await _engine.setHttpHeaders(headers);
  }

  /// Update SNI configuration
  Future<bool> updateSni(SniConfig sniConfig) async {
    return await _engine.setSni(sniConfig);
  }

  // ============================================
  // STATE HANDLING
  // ============================================

  void _updateStatus(VpnStatus newStatus) {
    _status = newStatus;
    notifyListeners();
  }

  void _handleStatusUpdate(VpnStatus status) {
    final previousState = _status.state;
    _status = status;

    // Handle state transitions
    if (previousState != status.state) {
      _handleStateTransition(previousState, status.state);
    }

    notifyListeners();
  }

  void _handleStateTransition(VpnConnectionState from, VpnConnectionState to) {
    debugPrint('VPN state transition: $from -> $to');

    switch (to) {
      case VpnConnectionState.connected:
        _isReconnecting = false;
        _recoveryManager.reset();
        break;

      case VpnConnectionState.error:
        if (_autoReconnect && !_isReconnecting) {
          _startRecovery();
        }
        break;

      case VpnConnectionState.noNetwork:
        // Will auto-reconnect when network returns
        break;

      default:
        break;
    }
  }

  void _handleVpnEvent(VpnEvent event) {
    debugPrint('VPN Event: ${event.type} - ${event.message}');

    switch (event.type) {
      case VpnEventType.error:
        _handleConnectionError(event.message);
        break;

      case VpnEventType.permissionRequired:
        // Handle permission requirement
        break;

      default:
        break;
    }
  }

  void _handleConnectionError(dynamic error) {
    debugPrint('Connection error: $error');

    final vpnError = error is VpnException
        ? error
        : VpnException(
            code: 'UNKNOWN',
            message: error.toString(),
          );

    _updateStatus(_status.copyWith(
      state: VpnConnectionState.error,
      errorMessage: vpnError.message,
    ));

    if (_autoReconnect && VpnErrorHandler.isRecoverable(vpnError)) {
      _startRecovery();
    }
  }

  // ============================================
  // RECOVERY
  // ============================================

  void _startRecovery() {
    if (_isReconnecting || !networkMonitor.isConnected) {
      return;
    }

    _isReconnecting = true;
    _recoveryManager.startRecovery();
  }

  Future<bool> _performReconnection() async {
    if (_config == null) return false;

    try {
      // Disconnect first
      await _engine.disconnect();
      await Future.delayed(const Duration(seconds: 1));

      // Reconnect
      final result = await _engine.connect(_config!);
      return result.success;
    } catch (e) {
      debugPrint('Reconnection attempt failed: $e');
      return false;
    }
  }

  void _handleRecoveryState(RecoveryState state) {
    debugPrint('Recovery state: $state');

    switch (state) {
      case RecoveryState.recovering:
      case RecoveryState.retrying:
        _updateStatus(_status.copyWith(
          state: VpnConnectionState.reconnecting,
        ));
        break;

      case RecoveryState.recovered:
        _isReconnecting = false;
        break;

      case RecoveryState.failed:
        _isReconnecting = false;
        _updateStatus(_status.copyWith(
          state: VpnConnectionState.error,
          errorMessage: 'Reconnection failed after maximum attempts',
        ));
        break;

      case RecoveryState.cancelled:
        _isReconnecting = false;
        break;

      default:
        break;
    }
  }

  // ============================================
  // NETWORK HANDLING
  // ============================================

  void _handleNetworkChange(NetworkChangeEvent event) {
    debugPrint('Network change: ${event.previousState} -> ${event.currentState}');

    if (event.becameOffline && isConnected) {
      // Network lost while connected
      _updateStatus(_status.copyWith(
        state: VpnConnectionState.noNetwork,
        errorMessage: 'Network connection lost',
      ));
    } else if (event.becameOnline && !isConnected && _config != null && _autoReconnect) {
      // Network restored, attempt reconnection
      _startRecovery();
    }
  }

  // ============================================
  // CLEANUP
  // ============================================

  @override
  void dispose() {
    _statusSubscription?.cancel();
    _eventSubscription?.cancel();
    _networkSubscription?.cancel();
    _recoveryManager.dispose();
    super.dispose();
  }
}

/// Connection Manager State
enum ConnectionManagerState {
  idle,
  connecting,
  connected,
  disconnecting,
  recovering,
  error,
}
