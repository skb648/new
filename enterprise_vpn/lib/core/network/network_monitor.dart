import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/foundation.dart';

/// Network Connectivity State
enum NetworkState {
  connected,
  disconnected,
  wifi,
  mobile,
  ethernet,
  vpn,
  unknown,
}

/// Network Quality
enum NetworkQuality {
  excellent,
  good,
  fair,
  poor,
  unknown,
}

/// Network Change Event
class NetworkChangeEvent {
  const NetworkChangeEvent({
    required this.previousState,
    required this.currentState,
    required this.timestamp,
    this.quality,
  });

  final NetworkState previousState;
  final NetworkState currentState;
  final DateTime timestamp;
  final NetworkQuality? quality;

  bool get becameOnline =>
      previousState == NetworkState.disconnected && currentState != NetworkState.disconnected;

  bool get becameOffline =>
      previousState != NetworkState.disconnected && currentState == NetworkState.disconnected;

  bool get switchedToWifi =>
      currentState == NetworkState.wifi && previousState != NetworkState.wifi;

  bool get switchedToMobile =>
      currentState == NetworkState.mobile && previousState != NetworkState.mobile;
}

/// Network Connectivity Monitor
/// Monitors network state changes and provides connectivity information
class NetworkConnectivityMonitor with ChangeNotifier {
  NetworkConnectivityMonitor() {
    _init();
  }

  final Connectivity _connectivity = Connectivity();

  StreamSubscription<List<ConnectivityResult>>? _subscription;
  Timer? _qualityCheckTimer;

  NetworkState _currentState = NetworkState.unknown;
  NetworkQuality _currentQuality = NetworkQuality.unknown;
  String _wifiName = '';
  String _wifiIP = '';

  // ============================================
  // GETTERS
  // ============================================

  NetworkState get state => _currentState;
  NetworkQuality get quality => _currentQuality;
  String get wifiName => _wifiName;
  String get wifiIP => _wifiIP;

  bool get isConnected => _currentState != NetworkState.disconnected &&
      _currentState != NetworkState.unknown;
  bool get isWifi => _currentState == NetworkState.wifi;
  bool get isMobile => _currentState == NetworkState.mobile;
  bool get isVpn => _currentState == NetworkState.vpn;
  bool get isOffline => _currentState == NetworkState.disconnected;

  // Stream controller for network events
  final StreamController<NetworkChangeEvent> _eventController =
      StreamController<NetworkChangeEvent>.broadcast();
  Stream<NetworkChangeEvent> get events => _eventController.stream;

  // ============================================
  // INITIALIZATION
  // ============================================

  void _init() {
    _checkConnectivity();
    _subscription = _connectivity.onConnectivityChanged.listen(
      _handleConnectivityChange,
      onError: (error) => debugPrint('Connectivity error: $error'),
    );

    // Start quality monitoring
    _startQualityMonitoring();
  }

  Future<void> _checkConnectivity() async {
    try {
      final results = await _connectivity.checkConnectivity();
      _updateState(results);
    } catch (e) {
      debugPrint('Failed to check connectivity: $e');
    }
  }

  void _handleConnectivityChange(List<ConnectivityResult> results) {
    _updateState(results);
  }

  Future<void> _updateState(List<ConnectivityResult> results) async {
    final previousState = _currentState;

    if (results.isEmpty || results.contains(ConnectivityResult.none)) {
      _currentState = NetworkState.disconnected;
    } else if (results.contains(ConnectivityResult.vpn)) {
      _currentState = NetworkState.vpn;
    } else if (results.contains(ConnectivityResult.wifi)) {
      _currentState = NetworkState.wifi;
      await _loadWifiInfo();
    } else if (results.contains(ConnectivityResult.mobile)) {
      _currentState = NetworkState.mobile;
    } else if (results.contains(ConnectivityResult.ethernet)) {
      _currentState = NetworkState.ethernet;
    } else {
      _currentState = NetworkState.unknown;
    }

    // Emit event on state change
    if (previousState != _currentState) {
      final event = NetworkChangeEvent(
        previousState: previousState,
        currentState: _currentState,
        timestamp: DateTime.now(),
        quality: _currentQuality,
      );
      _eventController.add(event);
      notifyListeners();
      debugPrint('Network state changed: $previousState -> $_currentState');
    }
  }

  Future<void> _loadWifiInfo() async {
    // WiFi info loading would go here
    // Placeholder for actual implementation
  }

  // ============================================
  // QUALITY MONITORING
  // ============================================

  void _startQualityMonitoring() {
    _qualityCheckTimer = Timer.periodic(
      const Duration(seconds: 30),
      (_) => _checkNetworkQuality(),
    );
  }

  Future<void> _checkNetworkQuality() async {
    if (!isConnected) {
      _currentQuality = NetworkQuality.unknown;
      return;
    }

    try {
      // Simple quality check based on connection type
      _currentQuality = switch (_currentState) {
        NetworkState.ethernet => NetworkQuality.excellent,
        NetworkState.wifi => NetworkQuality.good,
        NetworkState.mobile => NetworkQuality.fair,
        _ => NetworkQuality.unknown,
      };

      notifyListeners();
    } catch (e) {
      debugPrint('Quality check failed: $e');
    }
  }

  /// Force a connectivity check
  Future<void> refresh() async {
    await _checkConnectivity();
    await _checkNetworkQuality();
  }

  // ============================================
  // CLEANUP
  // ============================================

  @override
  void dispose() {
    _subscription?.cancel();
    _qualityCheckTimer?.cancel();
    _eventController.close();
    super.dispose();
  }
}

/// Connection Recovery Manager
/// Handles automatic reconnection and recovery logic
class ConnectionRecoveryManager {
  ConnectionRecoveryManager({
    required this.onReconnect,
    this.maxRetryAttempts = 5,
    this.baseRetryDelay = const Duration(seconds: 5),
    this.maxRetryDelay = const Duration(minutes: 5),
  });

  final Future<bool> Function() onReconnect;
  final int maxRetryAttempts;
  final Duration baseRetryDelay;
  final Duration maxRetryDelay;

  int _retryCount = 0;
  Timer? _retryTimer;
  bool _isRecovering = false;
  DateTime? _lastAttempt;

  // State stream
  final StreamController<RecoveryState> _stateController =
      StreamController<RecoveryState>.broadcast();
  Stream<RecoveryState> get stateStream => _stateController.stream;

  RecoveryState _state = RecoveryState.idle;
  RecoveryState get state => _state;

  int get retryCount => _retryCount;
  bool get isRecovering => _isRecovering;
  bool get canRetry => _retryCount < maxRetryAttempts;

  /// Start recovery process
  Future<void> startRecovery() async {
    if (_isRecovering) return;

    _isRecovering = true;
    _state = RecoveryState.recovering;
    _stateController.add(_state);

    await _attemptReconnection();
  }

  Future<void> _attemptReconnection() async {
    if (_retryCount >= maxRetryAttempts) {
      _state = RecoveryState.failed;
      _stateController.add(_state);
      _isRecovering = false;
      return;
    }

    _retryCount++;
    _lastAttempt = DateTime.now();
    _state = RecoveryState.retrying;
    _stateController.add(_state);

    debugPrint('Reconnection attempt $_retryCount/$maxRetryAttempts');

    try {
      final success = await onReconnect();

      if (success) {
        _state = RecoveryState.recovered;
        _stateController.add(_state);
        reset();
      } else {
        _scheduleRetry();
      }
    } catch (e) {
      debugPrint('Reconnection attempt failed: $e');
      _scheduleRetry();
    }
  }

  void _scheduleRetry() {
    final delay = _calculateRetryDelay();
    debugPrint('Scheduling retry in ${delay.inSeconds} seconds');

    _retryTimer?.cancel();
    _retryTimer = Timer(delay, () async {
      await _attemptReconnection();
    });
  }

  Duration _calculateRetryDelay() {
    // Exponential backoff with jitter
    final multiplier = 1 << (_retryCount - 1).clamp(0, 10);
    final delay = baseRetryDelay * multiplier;
    final jitter = Duration(
      milliseconds: DateTime.now().millisecond % 1000,
    );

    return Duration(
      milliseconds: (delay.inMilliseconds + jitter.inMilliseconds).clamp(
        baseRetryDelay.inMilliseconds,
        maxRetryDelay.inMilliseconds,
      ),
    );
  }

  /// Reset recovery state
  void reset() {
    _retryTimer?.cancel();
    _retryCount = 0;
    _isRecovering = false;
    _lastAttempt = null;
    _state = RecoveryState.idle;
    _stateController.add(_state);
  }

  /// Cancel ongoing recovery
  void cancel() {
    _retryTimer?.cancel();
    _isRecovering = false;
    _state = RecoveryState.cancelled;
    _stateController.add(_state);
  }

  void dispose() {
    _retryTimer?.cancel();
    _stateController.close();
  }
}

/// Recovery State
enum RecoveryState {
  idle,
  recovering,
  retrying,
  recovered,
  failed,
  cancelled,
}
