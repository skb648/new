import 'dart:convert';

import 'package:equatable/equatable.dart';

/// HTTP Header Model
/// Represents a custom HTTP header for injection
class HttpHeader extends Equatable {
  const HttpHeader({
    required this.name,
    required this.value,
    this.enabled = true,
  });

  final String name;
  final String value;
  final bool enabled;

  HttpHeader copyWith({
    String? name,
    String? value,
    bool? enabled,
  }) {
    return HttpHeader(
      name: name ?? this.name,
      value: value ?? this.value,
      enabled: enabled ?? this.enabled,
    );
  }

  Map<String, dynamic> toMap() => {
        'name': name,
        'value': value,
        'enabled': enabled,
      };

  factory HttpHeader.fromMap(Map<String, dynamic> map) => HttpHeader(
        name: map['name'] as String,
        value: map['value'] as String,
        enabled: map['enabled'] as bool? ?? true,
      );

  String toJson() => jsonEncode(toMap());

  factory HttpHeader.fromJson(String source) =>
      HttpHeader.fromMap(jsonDecode(source) as Map<String, dynamic>);

  @override
  List<Object?> get props => [name, value, enabled];
}

/// SNI Configuration
/// Server Name Indication settings for TLS connections
class SniConfig extends Equatable {
  const SniConfig({
    required this.serverName,
    this.enabled = true,
    this.allowOverride = false,
  });

  final String serverName;
  final bool enabled;
  final bool allowOverride;

  SniConfig copyWith({
    String? serverName,
    bool? enabled,
    bool? allowOverride,
  }) {
    return SniConfig(
      serverName: serverName ?? this.serverName,
      enabled: enabled ?? this.enabled,
      allowOverride: allowOverride ?? this.allowOverride,
    );
  }

  Map<String, dynamic> toMap() => {
        'serverName': serverName,
        'enabled': enabled,
        'allowOverride': allowOverride,
      };

  factory SniConfig.fromMap(Map<String, dynamic> map) => SniConfig(
        serverName: map['serverName'] as String,
        enabled: map['enabled'] as bool? ?? true,
        allowOverride: map['allowOverride'] as bool? ?? false,
      );

  String toJson() => jsonEncode(toMap());

  factory SniConfig.fromJson(String source) =>
      SniConfig.fromMap(jsonDecode(source) as Map<String, dynamic>);

  @override
  List<Object?> get props => [serverName, enabled, allowOverride];
}

/// VPN Server Configuration
/// Complete server settings for VPN connection including authentication
class VpnServerConfig extends Equatable {
  const VpnServerConfig({
    required this.id,
    required this.name,
    required this.serverIp,
    required this.port,
    this.protocol = 'TCP',
    this.country = '',
    this.countryCode = '',
    this.city = '',
    this.load = 0,
    this.isPremium = false,
    this.isFavorite = false,
    this.username = '',
    this.password = '',
  });

  final String id;
  final String name;
  final String serverIp;
  final int port;
  final String protocol;
  final String country;
  final String countryCode;
  final String city;
  final int load;
  final bool isPremium;
  final bool isFavorite;
  
  /// Authentication credentials for remote server
  final String username;
  final String password;

  VpnServerConfig copyWith({
    String? id,
    String? name,
    String? serverIp,
    int? port,
    String? protocol,
    String? country,
    String? countryCode,
    String? city,
    int? load,
    bool? isPremium,
    bool? isFavorite,
    String? username,
    String? password,
  }) {
    return VpnServerConfig(
      id: id ?? this.id,
      name: name ?? this.name,
      serverIp: serverIp ?? this.serverIp,
      port: port ?? this.port,
      protocol: protocol ?? this.protocol,
      country: country ?? this.country,
      countryCode: countryCode ?? this.countryCode,
      city: city ?? this.city,
      load: load ?? this.load,
      isPremium: isPremium ?? this.isPremium,
      isFavorite: isFavorite ?? this.isFavorite,
      username: username ?? this.username,
      password: password ?? this.password,
    );
  }

  Map<String, dynamic> toMap() => {
        'id': id,
        'name': name,
        'serverIp': serverIp,
        'port': port,
        'protocol': protocol,
        'country': country,
        'countryCode': countryCode,
        'city': city,
        'load': load,
        'isPremium': isPremium,
        'isFavorite': isFavorite,
        'username': username,
        'password': password,
      };

  factory VpnServerConfig.fromMap(Map<String, dynamic> map) => VpnServerConfig(
        id: map['id'] as String,
        name: map['name'] as String,
        serverIp: map['serverIp'] as String,
        port: map['port'] as int,
        protocol: map['protocol'] as String? ?? 'TCP',
        country: map['country'] as String? ?? '',
        countryCode: map['countryCode'] as String? ?? '',
        city: map['city'] as String? ?? '',
        load: map['load'] as int? ?? 0,
        isPremium: map['isPremium'] as bool? ?? false,
        isFavorite: map['isFavorite'] as bool? ?? false,
        username: map['username'] as String? ?? '',
        password: map['password'] as String? ?? '',
      );

  String toJson() => jsonEncode(toMap());

  factory VpnServerConfig.fromJson(String source) =>
      VpnServerConfig.fromMap(jsonDecode(source) as Map<String, dynamic>);

  @override
  List<Object?> get props => [
        id,
        name,
        serverIp,
        port,
        protocol,
        country,
        countryCode,
        city,
        load,
        isPremium,
        isFavorite,
        username,
        password,
      ];
}

/// Complete VPN Configuration
/// All settings for the VPN connection
class VpnConfig extends Equatable {
  const VpnConfig({
    this.server,
    this.httpHeaders = const [],
    this.sniConfig,
    this.autoConnect = false,
    this.killSwitch = false,
    this.dnsLeakProtection = true,
    this.ipv6Enabled = false,
    this.splitTunnelEnabled = false,
    this.excludedApps = const [],
    this.customDns = const [],
    this.mtu = 1500,
  });

  final VpnServerConfig? server;
  final List<HttpHeader> httpHeaders;
  final SniConfig? sniConfig;
  final bool autoConnect;
  final bool killSwitch;
  final bool dnsLeakProtection;
  final bool ipv6Enabled;
  final bool splitTunnelEnabled;
  final List<String> excludedApps;
  final List<String> customDns;
  final int mtu;

  /// Check if configuration is valid for connection
  bool get isValid => server != null && server!.serverIp.isNotEmpty && server!.port > 0;

  /// Get enabled HTTP headers
  List<HttpHeader> get enabledHeaders => httpHeaders.where((h) => h.enabled).toList();

  VpnConfig copyWith({
    VpnServerConfig? server,
    List<HttpHeader>? httpHeaders,
    SniConfig? sniConfig,
    bool? autoConnect,
    bool? killSwitch,
    bool? dnsLeakProtection,
    bool? ipv6Enabled,
    bool? splitTunnelEnabled,
    List<String>? excludedApps,
    List<String>? customDns,
    int? mtu,
  }) {
    return VpnConfig(
      server: server ?? this.server,
      httpHeaders: httpHeaders ?? this.httpHeaders,
      sniConfig: sniConfig ?? this.sniConfig,
      autoConnect: autoConnect ?? this.autoConnect,
      killSwitch: killSwitch ?? this.killSwitch,
      dnsLeakProtection: dnsLeakProtection ?? this.dnsLeakProtection,
      ipv6Enabled: ipv6Enabled ?? this.ipv6Enabled,
      splitTunnelEnabled: splitTunnelEnabled ?? this.splitTunnelEnabled,
      excludedApps: excludedApps ?? this.excludedApps,
      customDns: customDns ?? this.customDns,
      mtu: mtu ?? this.mtu,
    );
  }

  Map<String, dynamic> toMap() => {
        'server': server?.toMap(),
        'httpHeaders': httpHeaders.map((h) => h.toMap()).toList(),
        'sniConfig': sniConfig?.toMap(),
        'autoConnect': autoConnect,
        'killSwitch': killSwitch,
        'dnsLeakProtection': dnsLeakProtection,
        'ipv6Enabled': ipv6Enabled,
        'splitTunnelEnabled': splitTunnelEnabled,
        'excludedApps': excludedApps,
        'customDns': customDns,
        'mtu': mtu,
      };

  factory VpnConfig.fromMap(Map<String, dynamic> map) => VpnConfig(
        server: map['server'] != null
            ? VpnServerConfig.fromMap(map['server'] as Map<String, dynamic>)
            : null,
        httpHeaders: (map['httpHeaders'] as List<dynamic>?)
                ?.map((h) => HttpHeader.fromMap(h as Map<String, dynamic>))
                .toList() ??
            [],
        sniConfig: map['sniConfig'] != null
            ? SniConfig.fromMap(map['sniConfig'] as Map<String, dynamic>)
            : null,
        autoConnect: map['autoConnect'] as bool? ?? false,
        killSwitch: map['killSwitch'] as bool? ?? false,
        dnsLeakProtection: map['dnsLeakProtection'] as bool? ?? true,
        ipv6Enabled: map['ipv6Enabled'] as bool? ?? false,
        splitTunnelEnabled: map['splitTunnelEnabled'] as bool? ?? false,
        excludedApps: (map['excludedApps'] as List<dynamic>?)?.cast<String>() ?? [],
        customDns: (map['customDns'] as List<dynamic>?)?.cast<String>() ?? [],
        mtu: map['mtu'] as int? ?? 1500,
      );

  String toJson() => jsonEncode(toMap());

  factory VpnConfig.fromJson(String source) =>
      VpnConfig.fromMap(jsonDecode(source) as Map<String, dynamic>);

  /// Empty configuration
  factory VpnConfig.empty() => const VpnConfig();

  @override
  List<Object?> get props => [
        server,
        httpHeaders,
        sniConfig,
        autoConnect,
        killSwitch,
        dnsLeakProtection,
        ipv6Enabled,
        splitTunnelEnabled,
        excludedApps,
        customDns,
        mtu,
      ];
}
