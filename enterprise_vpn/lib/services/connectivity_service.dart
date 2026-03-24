import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/foundation.dart';
import 'package:network_info_plus/network_info_plus.dart';

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
class ConnectivityService with ChangeNotifier {
  ConnectivityService() {
    _init();
  }

  final Connectivity _connectivity = Connectivity();
  final NetworkInfo _networkInfo = NetworkInfo();

  StreamSubscription<List<ConnectivityResult>>? _connectivitySubscription;

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

  void _init() {
    _checkConnectivity();
    _connectivitySubscription = _connectivity.onConnectivityChanged.listen(
      _updateConnectionStatus,
    );
  }

  Future<void> _checkConnectivity() async {
    try {
      final results = await _connectivity.checkConnectivity();
      await _updateConnectionStatus(results);
    } catch (e) {
      debugPrint('Connectivity check error: $e');
    }
  }

  Future<void> _updateConnectionStatus(List<ConnectivityResult> results) async {
    final previousStatus = _status;

    if (results.isEmpty || results.contains(ConnectivityResult.none)) {
      _status = NetworkStatus.offline;
    } else if (results.contains(ConnectivityResult.vpn)) {
      _status = NetworkStatus.vpn;
    } else if (results.contains(ConnectivityResult.wifi)) {
      _status = NetworkStatus.wifi;
      await _loadWifiInfo();
    } else if (results.contains(ConnectivityResult.mobile)) {
      _status = NetworkStatus.mobile;
    } else if (results.contains(ConnectivityResult.ethernet)) {
      _status = NetworkStatus.ethernet;
    } else {
      _status = NetworkStatus.unknown;
    }

    if (previousStatus != _status) {
      notifyListeners();
      debugPrint('Network status changed: $previousStatus -> $_status');
    }
  }

  Future<void> _loadWifiInfo() async {
    try {
      _wifiName = await _networkInfo.getWifiName() ?? '';
      _wifiIP = await _networkInfo.getWifiIP() ?? '';
      _gatewayIP = await _networkInfo.getWifiGatewayIP() ?? '';
      notifyListeners();
    } catch (e) {
      debugPrint('Failed to load WiFi info: $e');
    }
  }

  void dispose() {
    _connectivitySubscription?.cancel();
    super.dispose();
  }
}
