import 'dart:async';

import 'package:flutter/foundation.dart';
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
        // ✅ FIX: Start with VpnReady (disconnected) instead of VpnInitial
        // This ensures the UI is interactive immediately
        super(VpnReady(status: VpnStatus.initial())) {
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
    _networkSubscription = _connectivityService.onStatusChange.listen(_onNetworkChange);
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
    debugPrint('Traffic: ↓${stats.speedDown} ↑${stats.speedUp}');
  }

  void _onVpnEvent(VpnEvent event) {
    debugPrint('VPN Event: ${event.type} - ${event.message}');

    switch (event.type) {
      case VpnEventType.error:
        // Extract error code from event data if available
        String? errorCode;
        if (event.data != null && event.data!.containsKey('errorCode')) {
          errorCode = event.data!['errorCode'] as String?;
        }
        
        final currentState = state;
        if (currentState is VpnReady) {
          emit(currentState.copyWith(
            status: currentState.status.copyWith(
              state: VpnConnectionState.error,
              errorMessage: event.message,
            ),
          ));
        } else {
          // If not in VpnReady, emit VpnError with code
          emit(VpnError(
            message: event.message,
            code: errorCode,
          ));
        }
        break;
      case VpnEventType.permissionRequired:
        debugPrint('VPN permission required');
        break;
      default:
        break;
    }
  }

  void _onNetworkChange(NetworkStatus networkStatus) {
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

  /// Initialize VPN service - UI already works
  Future<void> initialize() async {
    // Don't emit loading - keep UI interactive
    try {
      await _vpnService.initialize();
      final status = await _vpnService.getStatus();
      
      // Only update if we got valid status
      if (status.state != VpnConnectionState.disconnected || 
          status.serverIp != null) {
        emit(VpnReady(status: status));
      }
    } catch (e) {
      debugPrint('Initialize error (non-critical): $e');
      // Stay in ready state with disconnected status
    }
  }

  /// Request VPN permission
  Future<bool> requestPermission() async {
    try {
      return await _vpnService.requestVpnPermission();
    } catch (e) {
      debugPrint('Permission request failed: $e');
      return false;
    }
  }

  /// Check if VPN permission is granted
  Future<bool> hasPermission() async {
    try {
      return await _vpnService.hasVpnPermission();
    } catch (e) {
      debugPrint('Permission check failed: $e');
      return false;
    }
  }

  /// Connect to VPN
  Future<void> connect(VpnConfig config) async {
    // Get or create current state
    VpnReady currentState;
    if (state is VpnReady) {
      currentState = state as VpnReady;
    } else {
      currentState = VpnReady(status: VpnStatus.initial());
    }

    // Check permission first
    try {
      final hasPermission = await _vpnService.hasVpnPermission();
      if (!hasPermission) {
        final granted = await _vpnService.requestVpnPermission();
        if (!granted) {
          emit(const VpnError(
            message: 'VPN permission denied. Please grant VPN permission.',
            code: 'PERMISSION_DENIED',
          ));
          // Reset to ready after error
          await Future.delayed(const Duration(seconds: 2));
          emit(VpnReady(status: VpnStatus.initial()));
          return;
        }
      }
    } catch (e) {
      debugPrint('Permission check error: $e');
      // Continue anyway - permission might be granted
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
      debugPrint('========================================');
      debugPrint('VpnCubit.connect() - Calling _vpnService.connect()');
      debugPrint('config.isValid: ${config.isValid}');
      debugPrint('config.server: ${config.server}');
      debugPrint('config.server?.serverIp: ${config.server?.serverIp}');
      debugPrint('config.server?.port: ${config.server?.port}');
      debugPrint('========================================');
      
      final result = await _vpnService.connect(config);
      
      debugPrint('VpnCubit.connect() - Result: success=${result.success}, error=${result.error}');
      
      if (!result.success) {
        final errorMessage = result.error ?? 'Failed to connect to VPN server';
        debugPrint('VpnCubit.connect() - Emitting error: $errorMessage');
        emit(VpnReady(
          status: currentState.status.copyWith(
            state: VpnConnectionState.error,
            errorMessage: errorMessage,
          ),
          config: config,
        ));
        // Also emit VpnError for BlocListener to catch
        emit(VpnError(
          message: errorMessage,
          code: result.errorCode,
        ));
      }
    } catch (e) {
      debugPrint('VpnCubit.connect() - Exception: $e');
      emit(VpnReady(
        status: currentState.status.copyWith(
          state: VpnConnectionState.error,
          errorMessage: 'Connection error: $e',
        ),
        config: config,
      ));
      emit(VpnError(
        message: 'Connection error: $e',
      ));
    }
  }

  /// Disconnect from VPN
  Future<void> disconnect() async {
    VpnReady currentState;
    if (state is VpnReady) {
      currentState = state as VpnReady;
    } else {
      currentState = VpnReady(status: VpnStatus.initial());
    }

    emit(VpnReady(
      status: currentState.status.copyWith(
        state: VpnConnectionState.disconnecting,
      ),
      config: currentState.config,
    ));

    try {
      await _vpnService.disconnect();
    } catch (e) {
      debugPrint('Disconnect error: $e');
      // Reset to disconnected state
      emit(VpnReady(status: VpnStatus.initial()));
    }
  }

  /// Toggle VPN connection
  Future<void> toggle(VpnConfig config) async {
    VpnReady currentState;
    if (state is VpnReady) {
      currentState = state as VpnReady;
    } else {
      currentState = VpnReady(status: VpnStatus.initial());
    }

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

  /// Reset to initial state
  void reset() {
    emit(VpnReady(status: VpnStatus.initial()));
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
