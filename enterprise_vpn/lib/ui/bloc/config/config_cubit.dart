import 'dart:convert';

import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:equatable/equatable.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter/foundation.dart';

import '../../../core/constants/app_constants.dart';
import '../../../models/vpn_config.dart';

// ============================================
// STATES
// ============================================

abstract class ConfigState extends Equatable {
  const ConfigState();

  @override
  List<Object?> get props => [];
}

class ConfigInitial extends ConfigState {
  const ConfigInitial();
}

class ConfigLoading extends ConfigState {
  const ConfigLoading();
}

class ConfigLoaded extends ConfigState {
  const ConfigLoaded({
    required this.config,
    this.savedServers = const [],
  });

  final VpnConfig config;
  final List<VpnServerConfig> savedServers;

  ConfigLoaded copyWith({
    VpnConfig? config,
    List<VpnServerConfig>? savedServers,
  }) {
    return ConfigLoaded(
      config: config ?? this.config,
      savedServers: savedServers ?? this.savedServers,
    );
  }

  @override
  List<Object?> get props => [config, savedServers];
}

class ConfigError extends ConfigState {
  const ConfigError({
    required this.message,
  });

  final String message;

  @override
  List<Object?> get props => [message];
}

// ============================================
// CUBIT
// ============================================

class ConfigCubit extends Cubit<ConfigState> {
  ConfigCubit({
    required FlutterSecureStorage secureStorage,
    required SharedPreferences preferences,
  })  : _secureStorage = secureStorage,
        _preferences = preferences,
        // ✅ FIX: Start with ConfigLoaded (empty config) instead of ConfigInitial
        // This ensures the UI is interactive immediately
        super(ConfigLoaded(config: VpnConfig.empty())) {
    // Load saved config in background
    _loadConfiguration();
  }

  final FlutterSecureStorage _secureStorage;
  final SharedPreferences _preferences;

  Future<void> _loadConfiguration() async {
    try {
      // Load VPN configuration
      final configJson = await _secureStorage.read(key: AppConstants.keyVpnConfig);
      VpnConfig config = VpnConfig.empty();

      if (configJson != null) {
        config = VpnConfig.fromJson(configJson);
      }

      // Load saved servers
      final serversJson = _preferences.getString(AppConstants.keyServerList);
      List<VpnServerConfig> savedServers = [];

      if (serversJson != null) {
        final List<dynamic> serversList = jsonDecode(serversJson);
        savedServers = serversList
            .map((s) => VpnServerConfig.fromMap(s as Map<String, dynamic>))
            .toList();
      }

      // Only emit if we have real data, otherwise keep empty config
      if (configJson != null || serversJson != null) {
        emit(ConfigLoaded(
          config: config,
          savedServers: savedServers,
        ));
      }
    } catch (e) {
      debugPrint('Failed to load configuration: $e');
      // Keep the empty config state - don't emit error
    }
  }

  /// Reload configuration from storage
  Future<void> loadConfiguration() async {
    await _loadConfiguration();
  }

  /// Save complete configuration
  Future<void> saveConfiguration(VpnConfig config) async {
    final currentState = state;
    ConfigLoaded currentLoadedState;
    
    if (currentState is ConfigLoaded) {
      currentLoadedState = currentState;
    } else {
      currentLoadedState = ConfigLoaded(config: VpnConfig.empty());
    }

    // Debug logging - BEFORE save
    debugPrint('========================================');
    debugPrint('ConfigCubit.saveConfiguration() - BEFORE SAVE');
    debugPrint('config.isValid: ${config.isValid}');
    debugPrint('config.server: ${config.server}');
    debugPrint('config.server?.serverIp: ${config.server?.serverIp}');
    debugPrint('config.server?.port: ${config.server?.port}');
    debugPrint('currentState type: ${currentState.runtimeType}');
    debugPrint('========================================');

    try {
      final configJson = config.toJson();
      debugPrint('Saving config JSON: $configJson');
      
      await _secureStorage.write(
        key: AppConstants.keyVpnConfig,
        value: configJson,
      );
      
      // Verify the save worked
      final savedJson = await _secureStorage.read(key: AppConstants.keyVpnConfig);
      debugPrint('Verified saved JSON: $savedJson');

      final newState = currentLoadedState.copyWith(config: config);
      debugPrint('Emitting new state with config: serverIp=${newState.config.server?.serverIp}, port=${newState.config.server?.port}');
      emit(newState);
      
      // Debug logging - AFTER emit
      debugPrint('========================================');
      debugPrint('ConfigCubit.saveConfiguration() - AFTER EMIT');
      debugPrint('New state emitted successfully');
      debugPrint('state is ConfigLoaded: ${state is ConfigLoaded}');
      if (state is ConfigLoaded) {
        final loadedState = state as ConfigLoaded;
        debugPrint('state.config.server?.serverIp: ${loadedState.config.server?.serverIp}');
        debugPrint('state.config.server?.port: ${loadedState.config.server?.port}');
      }
      debugPrint('========================================');
      
    } catch (e) {
      debugPrint('Failed to save configuration: $e');
      // Still try to emit the state even if storage failed
      emit(currentLoadedState.copyWith(config: config));
    }
  }

  /// Save last connected server with authentication
  Future<void> saveLastServer(VpnServerConfig server) async {
    try {
      await _secureStorage.write(
        key: AppConstants.keyLastServer,
        value: server.toJson(),
      );
      
      await _preferences.setString(
        AppConstants.keyServerList,
        jsonEncode([server.toMap()]),
      );
    } catch (e) {
      debugPrint('Failed to save server: $e');
    }
  }

  /// Get last connected server with authentication
  Future<VpnServerConfig?> getLastServer() async {
    try {
      final serverJson = await _secureStorage.read(key: AppConstants.keyLastServer);
      if (serverJson != null) {
        return VpnServerConfig.fromJson(serverJson);
      }
    } catch (e) {
      debugPrint('Failed to get last server: $e');
    }
    return null;
  }

  /// Update server configuration with authentication
  Future<void> updateServer(VpnServerConfig server) async {
    final currentState = state;
    if (currentState is! ConfigLoaded) return;

    final newConfig = currentState.config.copyWith(server: server);
    await saveConfiguration(newConfig);
    await saveLastServer(server);
  }

  /// Add HTTP header
  Future<void> addHttpHeader(HttpHeader header) async {
    final currentState = state;
    if (currentState is! ConfigLoaded) return;

    final headers = List<HttpHeader>.from(currentState.config.httpHeaders);
    headers.add(header);

    final newConfig = currentState.config.copyWith(httpHeaders: headers);
    await saveConfiguration(newConfig);
  }

  /// Update HTTP header at index
  Future<void> updateHttpHeader(int index, HttpHeader header) async {
    final currentState = state;
    if (currentState is! ConfigLoaded) return;

    final headers = List<HttpHeader>.from(currentState.config.httpHeaders);
    if (index >= 0 && index < headers.length) {
      headers[index] = header;
    }

    final newConfig = currentState.config.copyWith(httpHeaders: headers);
    await saveConfiguration(newConfig);
  }

  /// Remove HTTP header
  Future<void> removeHttpHeader(int index) async {
    final currentState = state;
    if (currentState is! ConfigLoaded) return;

    final headers = List<HttpHeader>.from(currentState.config.httpHeaders);
    if (index >= 0 && index < headers.length) {
      headers.removeAt(index);
    }

    final newConfig = currentState.config.copyWith(httpHeaders: headers);
    await saveConfiguration(newConfig);
  }

  /// Update SNI configuration
  Future<void> updateSniConfig(SniConfig sniConfig) async {
    final currentState = state;
    if (currentState is! ConfigLoaded) return;

    final newConfig = currentState.config.copyWith(sniConfig: sniConfig);
    await saveConfiguration(newConfig);
  }

  /// Save server to favorites
  Future<void> saveServer(VpnServerConfig server) async {
    final currentState = state;
    if (currentState is! ConfigLoaded) return;

    final servers = List<VpnServerConfig>.from(currentState.savedServers);
    final existingIndex = servers.indexWhere((s) => s.id == server.id);

    if (existingIndex >= 0) {
      servers[existingIndex] = server;
    } else {
      servers.add(server);
    }

    await _preferences.setString(
      AppConstants.keyServerList,
      jsonEncode(servers.map((s) => s.toMap()).toList()),
    );

    emit(currentState.copyWith(savedServers: servers));
  }

  /// Remove server from favorites
  Future<void> removeServer(String serverId) async {
    final currentState = state;
    if (currentState is! ConfigLoaded) return;

    final servers = List<VpnServerConfig>.from(currentState.savedServers);
    servers.removeWhere((s) => s.id == serverId);

    await _preferences.setString(
      AppConstants.keyServerList,
      jsonEncode(servers.map((s) => s.toMap()).toList()),
    );

    emit(currentState.copyWith(savedServers: servers));
  }

  /// Toggle auto-connect
  Future<void> toggleAutoConnect() async {
    final currentState = state;
    if (currentState is! ConfigLoaded) return;

    final newConfig = currentState.config.copyWith(
      autoConnect: !currentState.config.autoConnect,
    );

    await _preferences.setBool(
      AppConstants.keyAutoConnect,
      newConfig.autoConnect,
    );

    await saveConfiguration(newConfig);
  }

  /// Toggle kill switch
  Future<void> toggleKillSwitch() async {
    final currentState = state;
    if (currentState is! ConfigLoaded) return;

    final newConfig = currentState.config.copyWith(
      killSwitch: !currentState.config.killSwitch,
    );

    await _preferences.setBool(
      AppConstants.keyKillSwitch,
      newConfig.killSwitch,
    );

    await saveConfiguration(newConfig);
  }

  /// Toggle DNS leak protection
  Future<void> toggleDnsLeakProtection() async {
    final currentState = state;
    if (currentState is! ConfigLoaded) return;

    final newConfig = currentState.config.copyWith(
      dnsLeakProtection: !currentState.config.dnsLeakProtection,
    );

    await saveConfiguration(newConfig);
  }

  /// Validate server configuration
  bool validateServerConfig({
    required String serverIp,
    required String port,
  }) {
    // Validate IP or domain
    final isIp = AppConstants.ipv4Pattern.hasMatch(serverIp) ||
        AppConstants.ipv6Pattern.hasMatch(serverIp);
    final isDomain = AppConstants.domainPattern.hasMatch(serverIp);

    if (!isIp && !isDomain) {
      return false;
    }

    // Validate port
    final portNum = int.tryParse(port);
    if (portNum == null || portNum < AppConstants.minPort || portNum > AppConstants.maxPort) {
      return false;
    }

    return true;
  }

  /// Validate HTTP header
  bool validateHttpHeader({
    required String name,
    required String value,
  }) {
    if (name.isEmpty || value.isEmpty) return false;
    if (!AppConstants.headerNamePattern.hasMatch(name)) return false;
    if (name.length > AppConstants.maxHeaderNameLength) return false;
    if (value.length > AppConstants.maxHeaderValueLength) return false;
    return true;
  }

  /// Validate SNI
  bool validateSni(String sni) {
    if (sni.isEmpty) return false;
    if (sni.length > AppConstants.maxSniLength) return false;
    return AppConstants.sniPattern.hasMatch(sni);
  }
}
