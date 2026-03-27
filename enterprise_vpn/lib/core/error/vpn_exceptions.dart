/// VPN Error Handling System
/// Centralized error handling with recovery strategies

/// Base VPN Exception
sealed class VpnException implements Exception {
  const VpnException({
    required this.code,
    required this.message,
    this.originalError,
    this.stackTrace,
  });

  final String code;
  final String message;
  final Object? originalError;
  final StackTrace? stackTrace;

  @override
  String toString() => 'VpnException($code): $message';
}

/// Permission Errors
class VpnPermissionException extends VpnException {
  const VpnPermissionException({
    super.message = 'VPN permission is required',
    super.originalError,
    super.stackTrace,
  }) : super(code: 'PERMISSION_DENIED');
}

/// Connection Errors
class VpnConnectionException extends VpnException {
  const VpnConnectionException({
    super.message = 'Failed to establish VPN connection',
    super.originalError,
    super.stackTrace,
    this.isRecoverable = true,
    this.retryAfter,
  }) : super(code: 'CONNECTION_FAILED');

  final bool isRecoverable;
  final Duration? retryAfter;
}

/// Authentication Errors
class VpnAuthException extends VpnException {
  const VpnAuthException({
    super.message = 'Authentication failed',
    super.originalError,
    super.stackTrace,
  }) : super(code: 'AUTH_FAILED');
}

/// Configuration Errors
class VpnConfigException extends VpnException {
  const VpnConfigException({
    super.message = 'Invalid VPN configuration',
    super.originalError,
    super.stackTrace,
    this.field,
  }) : super(code: 'CONFIG_INVALID');

  final String? field;
}

/// Network Errors
class VpnNetworkException extends VpnException {
  const VpnNetworkException({
    super.message = 'Network unavailable',
    super.originalError,
    super.stackTrace,
    this.networkType,
  }) : super(code: 'NETWORK_UNAVAILABLE');

  final String? networkType;
}

/// Server Errors
class VpnServerException extends VpnException {
  const VpnServerException({
    super.message = 'Server unreachable',
    super.originalError,
    super.stackTrace,
    this.serverAddress,
  }) : super(code: 'SERVER_UNREACHABLE');

  final String? serverAddress;
}

/// Timeout Errors
class VpnTimeoutException extends VpnException {
  const VpnTimeoutException({
    super.message = 'Connection timeout',
    super.originalError,
    super.stackTrace,
    this.timeoutDuration,
  }) : super(code: 'TIMEOUT');

  final Duration? timeoutDuration;
}

/// Certificate Errors
class VpnCertificateException extends VpnException {
  const VpnCertificateException({
    super.message = 'Certificate validation failed',
    super.originalError,
    super.stackTrace,
  }) : super(code: 'CERTIFICATE_INVALID');
}

/// Service Errors
class VpnServiceException extends VpnException {
  const VpnServiceException({
    super.message = 'VPN service unavailable',
    super.originalError,
    super.stackTrace,
  }) : super(code: 'SERVICE_UNAVAILABLE');
}

/// Protocol Errors
class VpnProtocolException extends VpnException {
  const VpnProtocolException({
    super.message = 'Protocol error',
    super.originalError,
    super.stackTrace,
  }) : super(code: 'PROTOCOL_ERROR');
}

/// Error Recovery Strategy
enum RecoveryStrategy {
  none,
  retry,
  reconnect,
  reconfigure,
  restart,
  askUser,
}

/// Error Handler
class VpnErrorHandler {
  VpnErrorHandler._();

  /// Parse error from native code
  static VpnException parseError(String code, String message, {Object? originalError}) {
    return switch (code.toUpperCase()) {
      'PERMISSION_DENIED' => VpnPermissionException(
          message: message,
          originalError: originalError,
        ),
      'CONNECTION_FAILED' => VpnConnectionException(
          message: message,
          originalError: originalError,
        ),
      'AUTH_FAILED' => VpnAuthException(
          message: message,
          originalError: originalError,
        ),
      'CONFIG_INVALID' => VpnConfigException(
          message: message,
          originalError: originalError,
        ),
      'NETWORK_UNAVAILABLE' => VpnNetworkException(
          message: message,
          originalError: originalError,
        ),
      'SERVER_UNREACHABLE' => VpnServerException(
          message: message,
          originalError: originalError,
        ),
      'TIMEOUT' => VpnTimeoutException(
          message: message,
          originalError: originalError,
        ),
      'CERTIFICATE_INVALID' => VpnCertificateException(
          message: message,
          originalError: originalError,
        ),
      'SERVICE_UNAVAILABLE' => VpnServiceException(
          message: message,
          originalError: originalError,
        ),
      _ => VpnException(
          code: code,
          message: message,
          originalError: originalError,
        ),
    };
  }

  /// Get recovery strategy for error
  static RecoveryStrategy getRecoveryStrategy(VpnException error) {
    return switch (error) {
      VpnPermissionException() => RecoveryStrategy.askUser,
      VpnConnectionException e => e.isRecoverable
          ? RecoveryStrategy.reconnect
          : RecoveryStrategy.askUser,
      VpnAuthException() => RecoveryStrategy.askUser,
      VpnConfigException() => RecoveryStrategy.reconfigure,
      VpnNetworkException() => RecoveryStrategy.retry,
      VpnServerException() => RecoveryStrategy.reconnect,
      VpnTimeoutException() => RecoveryStrategy.retry,
      VpnCertificateException() => RecoveryStrategy.askUser,
      VpnServiceException() => RecoveryStrategy.restart,
      VpnProtocolException() => RecoveryStrategy.reconnect,
      _ => RecoveryStrategy.none,
    };
  }

  /// Get user-friendly error message
  static String getUserMessage(VpnException error) {
    return switch (error) {
      VpnPermissionException() =>
        'VPN permission is required. Please grant permission to continue.',
      VpnConnectionException() =>
        'Failed to connect to VPN server. Please check your connection.',
      VpnAuthException() =>
        'Authentication failed. Please check your credentials.',
      VpnConfigException e =>
        'Invalid configuration${e.field != null ? ': ${e.field}' : ''}. Please verify settings.',
      VpnNetworkException() =>
        'No network connection available. Please check your internet.',
      VpnServerException() =>
        'VPN server is not responding. Please try another server.',
      VpnTimeoutException() =>
        'Connection timed out. Server may be slow or unreachable.',
      VpnCertificateException() =>
        'Security warning: Certificate verification failed.',
      VpnServiceException() =>
        'VPN service is unavailable. Please restart the app.',
      VpnProtocolException() =>
        'A protocol error occurred. Please try again.',
      _ => 'An unexpected error occurred: ${error.message}',
    };
  }

  /// Check if error is recoverable
  static bool isRecoverable(VpnException error) {
    return switch (getRecoveryStrategy(error)) {
      RecoveryStrategy.none => false,
      RecoveryStrategy.askUser => false,
      _ => true,
    };
  }

  /// Get retry delay for error
  static Duration getRetryDelay(VpnException error, int attemptCount) {
    final baseDelay = switch (error) {
      VpnTimeoutException() => const Duration(seconds: 5),
      VpnNetworkException() => const Duration(seconds: 3),
      VpnServerException() => const Duration(seconds: 10),
      VpnConnectionException() => const Duration(seconds: 5),
      _ => const Duration(seconds: 5),
    };

    // Exponential backoff with jitter
    final multiplier = 1 << (attemptCount.clamp(0, 5));
    final delay = baseDelay * multiplier;
    final jitter = Duration(milliseconds: DateTime.now().millisecond % 1000);
    
    return Duration(
      milliseconds: (delay.inMilliseconds + jitter.inMilliseconds).clamp(
        0,
        Duration.minutes(5).inMilliseconds,
      ),
    );
  }
}
