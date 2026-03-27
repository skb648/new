import 'package:flutter/material.dart';

import '../../../core/theme/app_theme.dart';

/// Traffic Stats Card Widget
/// Displays real-time traffic statistics (simplified without fl_chart)
class TrafficStatsCard extends StatelessWidget {
  const TrafficStatsCard({
    super.key,
    required this.bytesIn,
    required this.bytesOut,
    required this.speedDown,
    required this.speedUp,
  });

  final int bytesIn;
  final int bytesOut;
  final int speedDown;
  final int speedUp;

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
          // Header
          Text(
            'Traffic Statistics',
            style: Theme.of(context).textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.w600,
                  color: isDark
                      ? const Color(0xFFE8EAED)
                      : const Color(0xFF1F1F1F),
                ),
          ),

          const SizedBox(height: 20),

          // Speed Stats Row
          Row(
            children: [
              Expanded(
                child: _buildSpeedTile(
                  context,
                  icon: Icons.arrow_downward,
                  label: 'Download',
                  speed: speedDown,
                  total: bytesIn,
                  color: AppTheme.statusConnected,
                  isDark: isDark,
                ),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: _buildSpeedTile(
                  context,
                  icon: Icons.arrow_upward,
                  label: 'Upload',
                  speed: speedUp,
                  total: bytesOut,
                  color: AppTheme.primaryColor,
                  isDark: isDark,
                ),
              ),
            ],
          ),

          const SizedBox(height: 24),

          // Total Traffic
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: isDark
                  ? const Color(0xFF2D2D2D)
                  : const Color(0xFFF5F5F5),
              borderRadius: BorderRadius.circular(16),
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceAround,
              children: [
                _buildTotalItem(
                  context,
                  label: 'Total Download',
                  value: _formatBytes(bytesIn),
                  icon: Icons.download,
                  color: AppTheme.statusConnected,
                  isDark: isDark,
                ),
                Container(
                  width: 1,
                  height: 40,
                  color: isDark
                      ? const Color(0xFF424242)
                      : const Color(0xFFE8EAED),
                ),
                _buildTotalItem(
                  context,
                  label: 'Total Upload',
                  value: _formatBytes(bytesOut),
                  icon: Icons.upload,
                  color: AppTheme.primaryColor,
                  isDark: isDark,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSpeedTile(
    BuildContext context, {
    required IconData icon,
    required String label,
    required int speed,
    required int total,
    required Color color,
    required bool isDark,
  }) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: isDark
            ? const Color(0xFF2D2D2D)
            : const Color(0xFFF5F5F5),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 32,
                height: 32,
                decoration: BoxDecoration(
                  color: color.withAlpha(40),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(
                  icon,
                  color: color,
                  size: 18,
                ),
              ),
              const SizedBox(width: 10),
              Text(
                label,
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: isDark
                          ? const Color(0xFF9AA0A6)
                          : const Color(0xFF5F6368),
                    ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            _formatSpeed(speed),
            style: Theme.of(context).textTheme.titleLarge?.copyWith(
                  fontWeight: FontWeight.w700,
                  color: isDark
                      ? const Color(0xFFE8EAED)
                      : const Color(0xFF1F1F1F),
                ),
          ),
        ],
      ),
    );
  }

  Widget _buildTotalItem(
    BuildContext context, {
    required String label,
    required String value,
    required IconData icon,
    required Color color,
    required bool isDark,
  }) {
    return Column(
      children: [
        Icon(
          icon,
          color: color,
          size: 20,
        ),
        const SizedBox(height: 8),
        Text(
          value,
          style: Theme.of(context).textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.w600,
                color: isDark
                    ? const Color(0xFFE8EAED)
                    : const Color(0xFF1F1F1F),
              ),
        ),
        const SizedBox(height: 4),
        Text(
          label,
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: isDark
                    ? const Color(0xFF9AA0A6)
                    : const Color(0xFF5F6368),
              ),
        ),
      ],
    );
  }

  String _formatSpeed(int bytesPerSec) {
    if (bytesPerSec < 1024) {
      return '$bytesPerSec B/s';
    } else if (bytesPerSec < 1024 * 1024) {
      return '${(bytesPerSec / 1024).toStringAsFixed(1)} KB/s';
    } else if (bytesPerSec < 1024 * 1024 * 1024) {
      return '${(bytesPerSec / (1024 * 1024)).toStringAsFixed(1)} MB/s';
    } else {
      return '${(bytesPerSec / (1024 * 1024 * 1024)).toStringAsFixed(2)} GB/s';
    }
  }

  String _formatBytes(int bytes) {
    if (bytes < 1024) {
      return '$bytes B';
    } else if (bytes < 1024 * 1024) {
      return '${(bytes / 1024).toStringAsFixed(1)} KB';
    } else if (bytes < 1024 * 1024 * 1024) {
      return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
    } else {
      return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(2)} GB';
    }
  }
}
