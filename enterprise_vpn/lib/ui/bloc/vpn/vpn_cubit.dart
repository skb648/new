import 'dart:async';

import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../models/vpn_config.dart';
import '../../../models/vpn_state.dart';
import '../../../services/connectivity_service.dart';
import '../../../services/vpn_service.dart';

// ============================================
// STATES
// ============================================

abstract class VpnState extends Equatable {
  const VpnState();

  @override
  List<Object?> get props => [];
}

class VpnInitial extends VpnState {
  const VpnInitial();
}

class VpnLoading extends VpnState {
  const VpnLoading();
}

class VpnReady extends VpnState {
  const VpnReady({
    required this.status,
    this.config,
  });

  final VpnStatus status;
  final VpnConfig? config;

  VpnReady copyWith({
    VpnStatus? status,
    VpnConfig? config,
  }) {
    return VpnReady(
      status: status ?? this.status,
      config: config ?? this.config,
    );
  }

  @override
  List<Object?> get props => [status, config];
}

class VpnError extends VpnState {
  const VpnError({
    required this.message,
    this.code,
  });

  final String message;
  final String? code;

  @override
  List<Object?> get props => [message, code];
}

// ============================================
// CUBIT
// ============================================

class VpnCubit extends Cubit<VpnState> {
  VpnCubit({
    required VpnService vpnService,
    required ConnectivityService connectivityService,
  })  : _vpnService = vpnService,
        _connectivityService = connectivityService,
        super(const VpnInitial()) {
    _init();
  }

  final VpnService _vpnService;
  final ConnectivityService _connectivityService;

  StreamSubscription? _statusSubscription;
  StreamSubscription? _trafficSubscription;
  StreamSubscription? _eventSubscription;
  StreamSubscription? _networkSubscription;

  void _init() {
    // Listen to VPN status updates
    _statusSubscription = _vpnService.statusStream.listen(_onStatusUpdate);

    // Listen to traffic updates
    _trafficSubscription = _vpnService.trafficStream.listen(_onTrafficUpdate);

    // Listen to VPN events
    _eventSubscription = _vpnService.eventsStream.listen(_onVpnEvent);

    // Listen to network changes
    _networkSubscription = _connectivityService.addListener(_onNetworkChange);
  }

  void _onStatusUpdate(VpnStatus status) {
    final currentState = state;
    if (currentState is VpnReady) {
      emit(currentState.copyWith(status: status));
    } else {
      emit(VpnReady(status: status));
    }
  }

  void _onTrafficUpdate(TrafficStats stats) {
    // Traffic updates are handled through status updates
    debugPrint('Traffic: ↓${stats.speedDown} ↑${stats.speedUp}');
  }

  void _onVpnEvent(VpnEvent event) {
    debugPrint('VPN Event: ${event.type} - ${event.message}');

    switch (event.type) {
      case VpnEventType.error:
        final currentState = state;
        if (currentState is VpnReady) {
          emit(currentState.copyWith(
            status: currentState.status.copyWith(
              state: VpnConnectionState.error,
              errorMessage: event.message,
            ),
          ));
        }
        break;
      case VpnEventType.permissionRequired:
        // Handle permission requirement
        debugPrint('VPN permission required');
        break;
      default:
        break;
    }
  }

  void _onNetworkChange() {
    final networkStatus = _connectivityService.status;
    debugPrint('Network changed: $networkStatus');

    // If network is offline and VPN was connected, update state
    if (networkStatus == NetworkStatus.offline) {
      final currentState = state;
      if (currentState is VpnReady && 
          currentState.status.state == VpnConnectionState.connected) {
        emit(currentState.copyWith(
          status: currentState.status.copyWith(
            state: VpnConnectionState.noNetwork,
          ),
        ));
      }
    }
  }

  // ============================================
  // PUBLIC METHODS
  // ============================================

  /// Initialize VPN service
  Future<void> initialize() async {
    emit(const VpnLoading());

    try {
      await _vpnService.initialize();
      final status = await _vpnService.getStatus();

      emit(VpnReady(status: status));
    } catch (e) {
      emit(VpnError(message: 'Failed to initialize: $e'));
    }
  }

  /// Request VPN permission
  Future<bool> requestPermission() async {
    try {
      return await _vpnService.requestVpnPermission();
    } catch (e) {
      emit(VpnError(message: 'Permission request failed: $e'));
      return false;
    }
  }

  /// Check if VPN permission is granted
  Future<bool> hasPermission() async {
    return await _vpnService.hasVpnPermission();
  }

  /// Connect to VPN
  Future<void> connect(VpnConfig config) async {
    final currentState = state;
    if (currentState is! VpnReady) return;

    // Check permission first
    final hasPermission = await _vpnService.hasVpnPermission();
    if (!hasPermission) {
      final granted = await _vpnService.requestVpnPermission();
      if (!granted) {
        emit(const VpnError(
          message: 'VPN permission denied',
          code: 'PERMISSION_DENIED',
        ));
        return;
      }
    }

    // Check network connectivity
    if (!_connectivityService.isOnline) {
      emit(VpnReady(
        status: currentState.status.copyWith(
          state: VpnConnectionState.noNetwork,
          errorMessage: 'No network connection available',
        ),
        config: config,
      ));
      return;
    }

    // Update to connecting state
    emit(VpnReady(
      status: currentState.status.copyWith(
        state: VpnConnectionState.connecting,
        serverName: config.server?.name,
        serverIp: config.server?.serverIp,
        serverPort: config.server?.port,
        protocol: config.server?.protocol,
      ),
      config: config,
    ));

    try {
      final success = await _vpnService.connect(config);
      if (!success) {
        emit(VpnError(
          message: 'Failed to connect to VPN',
          code: 'CONNECTION_FAILED',
        ));
      }
    } catch (e) {
      emit(VpnError(message: 'Connection error: $e'));
    }
  }

  /// Disconnect from VPN
  Future<void> disconnect() async {
    final currentState = state;
    if (currentState is! VpnReady) return;

    emit(VpnReady(
      status: currentState.status.copyWith(
        state: VpnConnectionState.disconnecting,
      ),
      config: currentState.config,
    ));

    try {
      await _vpnService.disconnect();
    } catch (e) {
      emit(VpnError(message: 'Disconnection error: $e'));
    }
  }

  /// Toggle VPN connection
  Future<void> toggle(VpnConfig config) async {
    final currentState = state;
    if (currentState is! VpnReady) return;

    if (currentState.status.state == VpnConnectionState.connected) {
      await disconnect();
    } else {
      await connect(config);
    }
  }

  /// Update HTTP headers
  Future<bool> updateHttpHeaders(List<HttpHeader> headers) async {
    return await _vpnService.setHttpHeaders(headers);
  }

  /// Update SNI configuration
  Future<bool> updateSni(SniConfig sniConfig) async {
    return await _vpnService.setSni(sniConfig);
  }

  /// App lifecycle callbacks
  void onAppResumed() {
    _vpnService.getStatus();
  }

  void onAppPaused() {
    // VPN continues in background
  }

  void onAppDetached() {
    // Cleanup if needed
  }

  @override
  Future<void> close() {
    _statusSubscription?.cancel();
    _trafficSubscription?.cancel();
    _eventSubscription?.cancel();
    _networkSubscription?.cancel();
    return super.close();
  }
}
