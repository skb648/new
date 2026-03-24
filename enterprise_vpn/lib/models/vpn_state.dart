import 'package:equatable/equatable.dart';

/// VPN Connection State
/// Represents the current state of the VPN connection
enum VpnConnectionState {
  disconnected,
  connecting,
  connected,
  disconnecting,
  reconnecting,
  authenticating,
  error,
  noNetwork,
}

/// Extension for VpnConnectionState helper methods
extension VpnConnectionStateExtension on VpnConnectionState {
  /// Check if VPN is in an active state
  bool get isActive => this == VpnConnectionState.connected || 
      this == VpnConnectionState.connecting || 
      this == VpnConnectionState.reconnecting ||
      this == VpnConnectionState.authenticating;

  /// Check if VPN is in transition
  bool get isTransitioning => this == VpnConnectionState.connecting || 
      this == VpnConnectionState.disconnecting || 
      this == VpnConnectionState.reconnecting ||
      this == VpnConnectionState.authenticating;

  /// Get display name for the state
  String get displayName {
    switch (this) {
      case VpnConnectionState.disconnected:
        return 'Disconnected';
      case VpnConnectionState.connecting:
        return 'Connecting';
      case VpnConnectionState.connected:
        return 'Connected';
      case VpnConnectionState.disconnecting:
        return 'Disconnecting';
      case VpnConnectionState.reconnecting:
        return 'Reconnecting';
      case VpnConnectionState.authenticating:
        return 'Authenticating';
      case VpnConnectionState.error:
        return 'Error';
      case VpnConnectionState.noNetwork:
        return 'No Network';
    }
  }
}

/// VPN Status Model
/// Contains all information about current VPN status
class VpnStatus extends Equatable {
  const VpnStatus({
    required this.state,
    this.serverName,
    this.serverIp,
    this.serverPort,
    this.protocol,
    this.connectedAt,
    this.duration = Duration.zero,
    this.bytesIn = 0,
    this.bytesOut = 0,
    this.currentSpeedDown = 0,
    this.currentSpeedUp = 0,
    this.latency,
    this.errorMessage,
    this.localIp,
    this.remoteIp,
  });

  /// Current connection state
  final VpnConnectionState state;

  /// Server display name
  final String? serverName;

  /// Server IP address
  final String? serverIp;

  /// Server port
  final int? serverPort;

  /// Connection protocol
  final String? protocol;

  /// Connection timestamp
  final DateTime? connectedAt;

  /// Connection duration
  final Duration duration;

  /// Total bytes received
  final int bytesIn;

  /// Total bytes sent
  final int bytesOut;

  /// Current download speed in bytes per second
  final int currentSpeedDown;

  /// Current upload speed in bytes per second
  final int currentSpeedUp;

  /// Current latency in milliseconds
  final int? latency;

  /// Error message if any
  final String? errorMessage;

  /// Local VPN interface IP
  final String? localIp;

  /// Remote public IP
  final String? remoteIp;

  /// Create a copy with updated values
  VpnStatus copyWith({
    VpnConnectionState? state,
    String? serverName,
    String? serverIp,
    int? serverPort,
    String? protocol,
    DateTime? connectedAt,
    Duration? duration,
    int? bytesIn,
    int? bytesOut,
    int? currentSpeedDown,
    int? currentSpeedUp,
    int? latency,
    String? errorMessage,
    String? localIp,
    String? remoteIp,
  }) {
    return VpnStatus(
      state: state ?? this.state,
      serverName: serverName ?? this.serverName,
      serverIp: serverIp ?? this.serverIp,
      serverPort: serverPort ?? this.serverPort,
      protocol: protocol ?? this.protocol,
      connectedAt: connectedAt ?? this.connectedAt,
      duration: duration ?? this.duration,
      bytesIn: bytesIn ?? this.bytesIn,
      bytesOut: bytesOut ?? this.bytesOut,
      currentSpeedDown: currentSpeedDown ?? this.currentSpeedDown,
      currentSpeedUp: currentSpeedUp ?? this.currentSpeedUp,
      latency: latency ?? this.latency,
      errorMessage: errorMessage ?? this.errorMessage,
      localIp: localIp ?? this.localIp,
      remoteIp: remoteIp ?? this.remoteIp,
    );
  }

  /// Check if connected
  bool get isConnected => state == VpnConnectionState.connected;

  /// Check if connecting
  bool get isConnecting => state == VpnConnectionState.connecting;

  /// Check if has error
  bool get hasError => state == VpnConnectionState.error;

  /// Get total traffic in bytes
  int get totalTraffic => bytesIn + bytesOut;

  /// Format bytes to human readable string
  String get formattedBytesIn => _formatBytes(bytesIn);

  /// Format bytes to human readable string
  String get formattedBytesOut => _formatBytes(bytesOut);

  /// Format download speed
  String get formattedSpeedDown => '${_formatBytes(currentSpeedDown)}/s';

  /// Format upload speed
  String get formattedSpeedUp => '${_formatBytes(currentSpeedUp)}/s';

  /// Format duration to readable string
  String get formattedDuration {
    if (duration.inHours > 0) {
      return '${duration.inHours}h ${duration.inMinutes.remainder(60)}m ${duration.inSeconds.remainder(60)}s';
    } else if (duration.inMinutes > 0) {
      return '${duration.inMinutes}m ${duration.inSeconds.remainder(60)}s';
    } else {
      return '${duration.inSeconds}s';
    }
  }

  /// Format latency
  String get formattedLatency => latency != null ? '${latency}ms' : '--';

  static String _formatBytes(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    if (bytes < 1024 * 1024 * 1024) {
      return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
    }
    return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(2)} GB';
  }

  /// Initial state
  factory VpnStatus.initial() => const VpnStatus(
        state: VpnConnectionState.disconnected,
      );

  @override
  List<Object?> get props => [
        state,
        serverName,
        serverIp,
        serverPort,
        protocol,
        connectedAt,
        duration,
        bytesIn,
        bytesOut,
        currentSpeedDown,
        currentSpeedUp,
        latency,
        errorMessage,
        localIp,
        remoteIp,
      ];
}

/// Traffic Statistics
class TrafficStats extends Equatable {
  const TrafficStats({
    required this.timestamp,
    required this.bytesIn,
    required this.bytesOut,
    required this.speedDown,
    required this.speedUp,
  });

  final DateTime timestamp;
  final int bytesIn;
  final int bytesOut;
  final int speedDown;
  final int speedUp;

  @override
  List<Object?> get props => [timestamp, bytesIn, bytesOut, speedDown, speedUp];
}
