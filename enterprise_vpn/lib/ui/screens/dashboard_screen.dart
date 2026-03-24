import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_animate/flutter_animate.dart';

import '../../core/theme/app_theme.dart';
import '../../models/vpn_state.dart';
import '../bloc/vpn/vpn_cubit.dart';
import '../bloc/config/config_cubit.dart';
import '../widgets/toggle/vpn_toggle_button.dart';
import '../widgets/cards/status_card.dart';
import '../widgets/cards/traffic_stats_card.dart';
import '../widgets/bottom_sheet/advanced_config_sheet.dart';

/// Dashboard Screen
/// Main VPN control screen with Samsung One UI 6 design
class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen>
    with TickerProviderStateMixin {
  late AnimationController _pulseController;
  bool _showConfigSheet = false;

  @override
  void initState() {
    super.initState();
    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1500),
    );
  }

  @override
  void dispose() {
    _pulseController.dispose();
    super.dispose();
  }

  void _showAdvancedConfig() {
    HapticFeedback.mediumImpact();
    setState(() => _showConfigSheet = true);
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: Colors.transparent,
      builder: (context) => const AdvancedConfigSheet(),
    ).whenComplete(() {
      setState(() => _showConfigSheet = false);
    });
  }

  void _toggleVpn() {
    final vpnState = context.read<VpnCubit>().state;
    final configState = context.read<ConfigCubit>().state;

    if (vpnState is VpnReady && configState is ConfigLoaded) {
      if (vpnState.status.state == VpnConnectionState.connected) {
        context.read<VpnCubit>().disconnect();
      } else {
        if (configState.config.isValid) {
          context.read<VpnCubit>().connect(configState.config);
        } else {
          _showAdvancedConfig();
        }
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: isDark ? SystemUiOverlayStyle.light : SystemUiOverlayStyle.dark,
      child: Scaffold(
        backgroundColor: isDark 
            ? const Color(0xFF1A1A2E) 
            : const Color(0xFFF7F7F7),
        body: BlocConsumer<VpnCubit, VpnState>(
          listener: (context, state) {
            if (state is VpnError) {
              ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(
                  content: Text(state.message),
                  backgroundColor: AppTheme.error,
                  behavior: SnackBarBehavior.floating,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                ),
              );
            }
          },
          builder: (context, vpnState) {
            return SafeArea(
              child: CustomScrollView(
                physics: const BouncingScrollPhysics(),
                slivers: [
                  // App Bar
                  SliverToBoxAdapter(
                    child: _buildAppBar(context, isDark),
                  ),

                  // Main Content
                  SliverToBoxAdapter(
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 24),
                      child: Column(
                        children: [
                          const SizedBox(height: 20),

                          // VPN Toggle Button
                          _buildToggleSection(context, vpnState, isDark),

                          const SizedBox(height: 40),

                          // Status Card
                          if (vpnState is VpnReady) ...[
                            StatusCard(
                              status: vpnState.status,
                            ).animate().fadeIn(
                              duration: 400.ms,
                            ).slideY(
                              begin: 0.1,
                              end: 0,
                              duration: 400.ms,
                              curve: Curves.easeOutCubic,
                            ),

                            const SizedBox(height: 20),

                            // Traffic Stats Card
                            TrafficStatsCard(
                              bytesIn: vpnState.status.bytesIn,
                              bytesOut: vpnState.status.bytesOut,
                              speedDown: vpnState.status.currentSpeedDown,
                              speedUp: vpnState.status.currentSpeedUp,
                            ).animate().fadeIn(
                              duration: 400.ms,
                              delay: 100.ms,
                            ).slideY(
                              begin: 0.1,
                              end: 0,
                              duration: 400.ms,
                              curve: Curves.easeOutCubic,
                            ),
                          ],

                          const SizedBox(height: 30),

                          // Advanced Config Button
                          _buildConfigButton(context, isDark),

                          const SizedBox(height: 30),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _buildAppBar(BuildContext context, bool isDark) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(24, 16, 24, 8),
      child: Row(
        children: [
          // Title
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Enterprise VPN',
                  style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                        fontWeight: FontWeight.w700,
                        color: isDark 
                            ? const Color(0xFFE8EAED) 
                            : const Color(0xFF1F1F1F),
                      ),
                ),
                const SizedBox(height: 4),
                Text(
                  'Secure Network Routing',
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: isDark 
                            ? const Color(0xFF9AA0A6) 
                            : const Color(0xFF5F6368),
                      ),
                ),
              ],
            ),
          ),

          // Settings Button
          _buildIconButton(
            icon: Icons.settings_outlined,
            isDark: isDark,
            onTap: () {
              HapticFeedback.lightImpact();
              // Navigate to settings
            },
          ),
        ],
      ),
    );
  }

  Widget _buildToggleSection(
    BuildContext context,
    VpnState vpnState,
    bool isDark,
  ) {
    final status = vpnState is VpnReady ? vpnState.status.state : VpnConnectionState.disconnected;
    final isConnected = status == VpnConnectionState.connected;
    final isConnecting = status == VpnConnectionState.connecting ||
        status == VpnConnectionState.reconnecting;

    return Column(
      children: [
        // Connection Status Text
        AnimatedSwitcher(
          duration: const Duration(milliseconds: 300),
          child: Text(
            _getStatusText(status),
            key: ValueKey(status),
            style: Theme.of(context).textTheme.titleMedium?.copyWith(
                  color: _getStatusColor(status),
                  fontWeight: FontWeight.w600,
                ),
          ),
        ),

        const SizedBox(height: 32),

        // VPN Toggle Button
        VpnToggleButton(
          isActive: isConnected,
          isAnimating: isConnecting,
          size: 180,
          onPressed: _toggleVpn,
        ),

        const SizedBox(height: 32),

        // Server Info
        BlocBuilder<ConfigCubit, ConfigState>(
          builder: (context, configState) {
            final server = configState is ConfigLoaded 
                ? configState.config.server 
                : null;

            return AnimatedContainer(
              duration: const Duration(milliseconds: 300),
              padding: const EdgeInsets.symmetric(
                horizontal: 20,
                vertical: 12,
              ),
              decoration: BoxDecoration(
                color: isDark
                    ? const Color(0xFF2D2D2D)
                    : const Color(0xFFF5F5F5),
                borderRadius: BorderRadius.circular(16),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    Icons.dns_outlined,
                    size: 18,
                    color: isDark
                        ? const Color(0xFF9AA0A6)
                        : const Color(0xFF5F6368),
                  ),
                  const SizedBox(width: 10),
                  Text(
                    server?.name ?? server?.serverIp ?? 'No server configured',
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                          color: isDark
                              ? const Color(0xFFE8EAED)
                              : const Color(0xFF1F1F1F),
                        ),
                  ),
                ],
              ),
            );
          },
        ),
      ],
    );
  }

  Widget _buildConfigButton(BuildContext context, bool isDark) {
    return GestureDetector(
      onTap: _showAdvancedConfig,
      child: Container(
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          color: isDark 
              ? const Color(0xFF1E1E1E) 
              : Colors.white,
          borderRadius: BorderRadius.circular(24),
          boxShadow: [
            BoxShadow(
              color: isDark
                  ? Colors.black.withValues(alpha: 0.3)
                  : Colors.black.withValues(alpha: 0.05),
              blurRadius: 20,
              offset: const Offset(0, 4),
            ),
          ],
        ),
        child: Row(
          children: [
            Container(
              width: 48,
              height: 48,
              decoration: BoxDecoration(
                color: AppTheme.primaryColor.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(14),
              ),
              child: Icon(
                Icons.tune_outlined,
                color: AppTheme.primaryColor,
                size: 24,
              ),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Advanced Routing Config',
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w600,
                          color: isDark 
                              ? const Color(0xFFE8EAED) 
                              : const Color(0xFF1F1F1F),
                        ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    'Server IP, Port, Headers, SNI',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: isDark 
                              ? const Color(0xFF9AA0A6) 
                              : const Color(0xFF5F6368),
                        ),
                  ),
                ],
              ),
            ),
            Icon(
              Icons.keyboard_arrow_up,
              color: isDark 
                  ? const Color(0xFF9AA0A6) 
                  : const Color(0xFF5F6368),
            ),
          ],
        ),
      ).animate(target: _showConfigSheet ? 1 : 0).scale(
        begin: const Offset(1, 1),
        end: const Offset(0.95, 0.95),
        duration: 200.ms,
      ),
    );
  }

  Widget _buildIconButton({
    required IconData icon,
    required bool isDark,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 48,
        height: 48,
        decoration: BoxDecoration(
          color: isDark 
              ? const Color(0xFF2D2D2D) 
              : const Color(0xFFF5F5F5),
          borderRadius: BorderRadius.circular(14),
        ),
        child: Icon(
          icon,
          color: isDark 
              ? const Color(0xFFE8EAED) 
              : const Color(0xFF5F6368),
        ),
      ),
    );
  }

  String _getStatusText(VpnConnectionState state) {
    switch (state) {
      case VpnConnectionState.connected:
        return 'Connected';
      case VpnConnectionState.connecting:
        return 'Connecting...';
      case VpnConnectionState.disconnecting:
        return 'Disconnecting...';
      case VpnConnectionState.reconnecting:
        return 'Reconnecting...';
      case VpnConnectionState.authenticating:
        return 'Authenticating...';
      case VpnConnectionState.error:
        return 'Connection Error';
      case VpnConnectionState.noNetwork:
        return 'No Network';
      case VpnConnectionState.disconnected:
      default:
        return 'Disconnected';
    }
  }

  Color _getStatusColor(VpnConnectionState state) {
    switch (state) {
      case VpnConnectionState.connected:
        return AppTheme.statusConnected;
      case VpnConnectionState.connecting:
      case VpnConnectionState.reconnecting:
      case VpnConnectionState.authenticating:
        return AppTheme.statusConnecting;
      case VpnConnectionState.error:
        return AppTheme.statusError;
      case VpnConnectionState.noNetwork:
        return AppTheme.statusError;
      case VpnConnectionState.disconnecting:
      case VpnConnectionState.disconnected:
      default:
        return AppTheme.statusDisconnected;
    }
  }
}
