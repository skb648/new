import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';

import '../../../core/theme/app_theme.dart';
import '../../../models/vpn_state.dart';

/// Status Card Widget
/// Displays current VPN connection status with real-time updates
class StatusCard extends StatelessWidget {
  const StatusCard({
    super.key,
    required this.status,
  });

  final VpnStatus status;

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: isDark ? const Color(0xFF1E1E1E) : Colors.white,
        borderRadius: BorderRadius.circular(24),
        boxShadow: [
          BoxShadow(
            color: isDark
                ? Colors.black.withAlpha(80)
                : Colors.black.withAlpha(15),
            blurRadius: 20,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Header Row
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                'Connection Status',
                style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w600,
                      color: isDark
                          ? const Color(0xFFE8EAED)
                          : const Color(0xFF1F1F1F),
                    ),
              ),
              _buildStatusBadge(context, status.state, isDark),
            ],
          ),

          const SizedBox(height: 24),

          // Connection Info Grid
          Row(
            children: [
              Expanded(
                child: _buildInfoTile(
                  context,
                  icon: Icons.timer_outlined,
                  label: 'Duration',
                  value: status.formattedDuration,
                  isDark: isDark,
                ),
              ),
              Container(
                width: 1,
                height: 48,
                color: isDark
                    ? const Color(0xFF333333)
                    : const Color(0xFFE8EAED),
              ),
              Expanded(
                child: _buildInfoTile(
                  context,
                  icon: Icons.network_ping_outlined,
                  label: 'Latency',
                  value: status.formattedLatency,
                  isDark: isDark,
                ),
              ),
            ],
          ),

          const SizedBox(height: 16),

          // Error Message Display
          if (status.state == VpnConnectionState.error && status.errorMessage != null)
            _buildErrorMessage(context, isDark),

          // Server Info
          if (status.serverName != null || status.serverIp != null)
            _buildServerInfo(context, isDark),
        ],
      ),
    );
  }

  /// Build error message display with detailed error info
  Widget _buildErrorMessage(BuildContext context, bool isDark) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      margin: const EdgeInsets.only(bottom: 16),
      decoration: BoxDecoration(
        color: AppTheme.error.withAlpha(25),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: AppTheme.error.withAlpha(50),
          width: 1,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                Icons.error_outline,
                color: AppTheme.error,
                size: 20,
              ),
              const SizedBox(width: 8),
              Text(
                'Connection Failed',
                style: Theme.of(context).textTheme.titleSmall?.copyWith(
                      color: AppTheme.error,
                      fontWeight: FontWeight.w600,
                    ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            status.errorMessage ?? 'Unknown error',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: isDark
                      ? const Color(0xFFE8EAED)
                      : const Color(0xFF1F1F1F),
                  fontFamily: 'monospace',
                  fontSize: 11,
                ),
          ),
        ],
      ),
    );
  }

  Widget _buildStatusBadge(
    BuildContext context,
    VpnConnectionState state,
    bool isDark,
  ) {
    final badgeInfo = _getStatusBadgeInfo(state);
    final color = badgeInfo['color'] as Color;
    final text = badgeInfo['text'] as String;

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      decoration: BoxDecoration(
        color: color.withAlpha(40),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 8,
            height: 8,
            decoration: BoxDecoration(
              color: color,
              shape: BoxShape.circle,
              boxShadow: [
                BoxShadow(
                  color: color.withAlpha(128),
                  blurRadius: 4,
                  spreadRadius: 1,
                ),
              ],
            ),
          )
              .animate(
                onPlay: state == VpnConnectionState.connecting ||
                        state == VpnConnectionState.reconnecting
                    ? (controller) => controller.repeat()
                    : null,
              )
              .scale(
                begin: const Offset(1, 1),
                end: const Offset(1.2, 1.2),
                duration: 800.ms,
              )
              .then()
              .scale(
                begin: const Offset(1.2, 1.2),
                end: const Offset(1, 1),
                duration: 800.ms,
              ),
          const SizedBox(width: 8),
          Text(
            text,
            style: Theme.of(context).textTheme.labelMedium?.copyWith(
                  color: color,
                  fontWeight: FontWeight.w600,
                ),
          ),
        ],
      ),
    );
  }

  Widget _buildInfoTile(
    BuildContext context, {
    required IconData icon,
    required String label,
    required String value,
    required bool isDark,
  }) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8),
      child: Row(
        children: [
          Icon(
            icon,
            size: 20,
            color: AppTheme.primaryColor,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  label,
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: isDark
                            ? const Color(0xFF9AA0A6)
                            : const Color(0xFF5F6368),
                      ),
                ),
                const SizedBox(height: 4),
                Text(
                  value,
                  style: Theme.of(context).textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.w600,
                        color: isDark
                            ? const Color(0xFFE8EAED)
                            : const Color(0xFF1F1F1F),
                      ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildServerInfo(BuildContext context, bool isDark) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: isDark
            ? const Color(0xFF2D2D2D)
            : const Color(0xFFF5F5F5),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Row(
        children: [
          Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              color: AppTheme.primaryColor.withAlpha(25),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Icon(
              Icons.dns_outlined,
              color: AppTheme.primaryColor,
              size: 20,
            ),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  status.serverName ?? 'VPN Server',
                  style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                        fontWeight: FontWeight.w500,
                        color: isDark
                            ? const Color(0xFFE8EAED)
                            : const Color(0xFF1F1F1F),
                      ),
                ),
                if (status.serverIp != null) ...[
                  const SizedBox(height: 4),
                  Text(
                    '${status.serverIp}:${status.serverPort ?? 443}',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: isDark
                              ? const Color(0xFF9AA0A6)
                              : const Color(0xFF5F6368),
                        ),
                  ),
                ],
              ],
            ),
          ),
          if (status.protocol != null)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              decoration: BoxDecoration(
                color: AppTheme.secondaryColor.withAlpha(25),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text(
                status.protocol!,
                style: Theme.of(context).textTheme.labelSmall?.copyWith(
                      color: AppTheme.secondaryColor,
                      fontWeight: FontWeight.w600,
                    ),
              ),
            ),
        ],
      ),
    );
  }

  Map<String, dynamic> _getStatusBadgeInfo(VpnConnectionState state) {
    switch (state) {
      case VpnConnectionState.connected:
        return {'color': AppTheme.statusConnected, 'text': 'Connected'};
      case VpnConnectionState.connecting:
        return {'color': AppTheme.statusConnecting, 'text': 'Connecting'};
      case VpnConnectionState.disconnecting:
        return {'color': AppTheme.statusConnecting, 'text': 'Disconnecting'};
      case VpnConnectionState.reconnecting:
        return {'color': AppTheme.statusConnecting, 'text': 'Reconnecting'};
      case VpnConnectionState.authenticating:
        return {'color': AppTheme.statusConnecting, 'text': 'Auth'};
      case VpnConnectionState.error:
        return {'color': AppTheme.statusError, 'text': 'Error'};
      case VpnConnectionState.noNetwork:
        return {'color': AppTheme.statusError, 'text': 'No Network'};
      case VpnConnectionState.disconnected:
      default:
        return {'color': AppTheme.statusDisconnected, 'text': 'Disconnected'};
    }
  }
}
