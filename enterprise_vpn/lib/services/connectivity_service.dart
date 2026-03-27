import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/foundation.dart';

/// Network connectivity status
enum NetworkStatus {
  online,
  offline,
  wifi,
  mobile,
  ethernet,
  vpn,
  unknown,
}

/// Connectivity Service
/// Monitors network state and provides connectivity information
class ConnectivityService {
  ConnectivityService() {
    _init();
  }

  final Connectivity _connectivity = Connectivity();

  StreamSubscription<ConnectivityResult>? _connectivitySubscription;
  final StreamController<NetworkStatus> _statusController = 
      StreamController<NetworkStatus>.broadcast();

  NetworkStatus _status = NetworkStatus.unknown;
  String _wifiName = '';
  String _wifiIP = '';
  String _gatewayIP = '';

  NetworkStatus get status => _status;
  String get wifiName => _wifiName;
  String get wifiIP => _wifiIP;
  String get gatewayIP => _gatewayIP;
  bool get isOnline => _status != NetworkStatus.offline && _status != NetworkStatus.unknown;
  bool get isWifi => _status == NetworkStatus.wifi;
  bool get isMobile => _status == NetworkStatus.mobile;
  
  /// Stream of network status changes
  Stream<NetworkStatus> get onStatusChange => _statusController.stream;

  void _init() {
    _checkConnectivity();
    _connectivitySubscription = _connectivity.onConnectivityChanged.listen(
      _updateConnectionStatus,
    );
  }

  Future<void> _checkConnectivity() async {
    try {
      final result = await _connectivity.checkConnectivity();
      await _updateConnectionStatus(result);
    } catch (e) {
      debugPrint('Connectivity check error: $e');
      // Default to online so VPN can attempt connection
      _status = NetworkStatus.online;
      _statusController.add(_status);
    }
  }

  Future<void> _updateConnectionStatus(ConnectivityResult result) async {
    final previousStatus = _status;

    if (result == ConnectivityResult.none) {
      _status = NetworkStatus.offline;
    } else if (result == ConnectivityResult.vpn) {
      _status = NetworkStatus.vpn;
    } else if (result == ConnectivityResult.wifi) {
      _status = NetworkStatus.wifi;
    } else if (result == ConnectivityResult.mobile) {
      _status = NetworkStatus.mobile;
    } else if (result == ConnectivityResult.ethernet) {
      _status = NetworkStatus.ethernet;
    } else {
      _status = NetworkStatus.unknown;
    }

    if (previousStatus != _status) {
      debugPrint('Network status changed: $previousStatus -> $_status');
      _statusController.add(_status);
    }
  }

  void dispose() {
    _connectivitySubscription?.cancel();
    _statusController.close();
  }
}
