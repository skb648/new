import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_animate/flutter_animate.dart';

import '../../core/theme/app_theme.dart';
import '../../models/vpn_config.dart';
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
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: const Text('Opening Advanced Configuration...'),
        backgroundColor: AppTheme.primaryColor,
        behavior: SnackBarBehavior.floating,
        duration: const Duration(seconds: 1),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(16),
        ),
      ),
    );
    
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
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: const Text('Power button pressed!'),
        backgroundColor: AppTheme.primaryColor,
        behavior: SnackBarBehavior.floating,
        duration: const Duration(milliseconds: 800),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(16),
        ),
      ),
    );
    
    HapticFeedback.mediumImpact();
    
    // Get the VPN cubit and config cubit
    final vpnCubit = context.read<VpnCubit>();
    final configCubit = context.read<ConfigCubit>();
    
    // 🔥 CRITICAL DEBUG: Log what we're reading from state
    debugPrint('========================================');
    debugPrint('🔥 DashboardScreen._toggleVpn() CALLED');
    debugPrint('🔥 configCubit.state type: ${configCubit.state.runtimeType}');
    
    // Get current VPN status
    final currentState = vpnCubit.state;
    VpnStatus currentStatus;
    if (currentState is VpnReady) {
      currentStatus = currentState.status;
    } else {
      currentStatus = VpnStatus.initial();
    }

    // Get config - read fresh from the cubit
    VpnConfig config;
    final configState = configCubit.state;
    
    debugPrint('🔥 configState is ConfigLoaded: ${configState is ConfigLoaded}');
    
    if (configState is ConfigLoaded) {
      config = configState.config;
      // 🔥 CRITICAL DEBUG: Log the config we got from state
      debugPrint('========================================');
      debugPrint('🔥 Config from ConfigCubit.state:');
      debugPrint('🔥 config.isValid: ${config.isValid}');
      debugPrint('🔥 config.server: ${config.server}');
      debugPrint('🔥 config.server?.serverIp: "${config.server?.serverIp}"');
      debugPrint('🔥 config.server?.port: ${config.server?.port}');
      debugPrint('🔥 config.server?.id: ${config.server?.id}');
      debugPrint('========================================');
    } else {
      config = VpnConfig.empty();
      debugPrint('========================================');
      debugPrint('🔥 Config NOT Loaded - using empty config!');
      debugPrint('🔥 configCubit.state: ${configCubit.state}');
      debugPrint('========================================');
    }

    // Toggle based on current state
    if (currentStatus.state == VpnConnectionState.connected) {
      vpnCubit.disconnect();
    } else if (currentStatus.state == VpnConnectionState.disconnected ||
               currentStatus.state == VpnConnectionState.error ||
               currentStatus.state == VpnConnectionState.noNetwork) {
      // Check if config is valid
      if (config.isValid && config.server != null) {
        debugPrint('========================================');
        debugPrint('🔥 CALLING vpnCubit.connect() with config:');
        debugPrint('🔥 IP: "${config.server?.serverIp}"');
        debugPrint('🔥 Port: ${config.server?.port}');
        debugPrint('🔥 Protocol: ${config.server?.protocol}');
        debugPrint('========================================');
        vpnCubit.connect(config);
      } else {
        // Show config sheet if no valid config
        debugPrint('========================================');
        debugPrint('🔥 CONFIG INVALID - showing config sheet');
        debugPrint('🔥 config.isValid: ${config.isValid}');
        debugPrint('🔥 config.server: ${config.server}');
        debugPrint('🔥 config.server?.serverIp: "${config.server?.serverIp}"');
        debugPrint('🔥 config.server?.port: ${config.server?.port}');
        debugPrint('========================================');
        _showAdvancedConfig();
      }
    } else if (currentStatus.state == VpnConnectionState.connecting) {
      // Allow cancel by disconnecting
      vpnCubit.disconnect();
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
              _showErrorDialog(context, state.message, state.code);
            } else if (state is VpnReady && 
                       state.status.state == VpnConnectionState.error &&
                       state.status.errorMessage != null) {
              _showErrorDialog(context, state.status.errorMessage!, 'CONNECTION_ERROR');
            }
          },
          builder: (context, vpnState) {
            VpnStatus status;
            VpnConfig? config;
            
            if (vpnState is VpnReady) {
              status = vpnState.status;
              config = vpnState.config;
            } else if (vpnState is VpnError) {
              status = VpnStatus.initial().copyWith(
                state: VpnConnectionState.error,
                errorMessage: vpnState.message,
              );
            } else {
              status = VpnStatus.initial();
            }

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
                          _buildToggleSection(context, status, isDark),

                          const SizedBox(height: 40),

                          // Status Card
                          StatusCard(
                            status: status,
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
                            bytesIn: status.bytesIn,
                            bytesOut: status.bytesOut,
                            speedDown: status.currentSpeedDown,
                            speedUp: status.currentSpeedUp,
                          ).animate().fadeIn(
                            duration: 400.ms,
                            delay: 100.ms,
                          ).slideY(
                            begin: 0.1,
                            end: 0,
                            duration: 400.ms,
                            curve: Curves.easeOutCubic,
                          ),

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
              ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(
                  content: const Text('Settings button pressed!'),
                  backgroundColor: AppTheme.primaryColor,
                  behavior: SnackBarBehavior.floating,
                  duration: const Duration(milliseconds: 800),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                ),
              );
              HapticFeedback.lightImpact();
            },
          ),
        ],
      ),
    );
  }

  Widget _buildToggleSection(
    BuildContext context,
    VpnStatus status,
    bool isDark,
  ) {
    final isConnected = status.state == VpnConnectionState.connected;
    final isConnecting = status.state == VpnConnectionState.connecting ||
        status.state == VpnConnectionState.reconnecting;

    return Column(
      children: [
        // Connection Status Text
        AnimatedSwitcher(
          duration: const Duration(milliseconds: 300),
          child: Text(
            _getStatusText(status.state),
            key: ValueKey(status.state),
            style: Theme.of(context).textTheme.titleMedium?.copyWith(
                  color: _getStatusColor(status.state),
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
            String serverInfo = 'No server configured';
            if (configState is ConfigLoaded && configState.config.server != null) {
              serverInfo = configState.config.server?.name ?? 
                           configState.config.server?.serverIp ?? 
                           'Custom Server';
            } else if (status.serverName != null) {
              serverInfo = status.serverName!;
            } else if (status.serverIp != null) {
              serverInfo = status.serverIp!;
            }

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
                    serverInfo,
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
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: _showAdvancedConfig,
        borderRadius: BorderRadius.circular(24),
        child: Ink(
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: isDark 
                ? const Color(0xFF1E1E1E) 
                : Colors.white,
            borderRadius: BorderRadius.circular(24),
            boxShadow: [
              BoxShadow(
                color: isDark
                    ? Colors.black.withAlpha(80)
                    : Colors.black.withAlpha(13),
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
                  color: AppTheme.primaryColor.withAlpha(25),
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
        ),
      ),
    ).animate(target: _showConfigSheet ? 1 : 0).scale(
      begin: const Offset(1, 1),
      end: const Offset(0.95, 0.95),
      duration: 200.ms,
    );
  }

  Widget _buildIconButton({
    required IconData icon,
    required bool isDark,
    required VoidCallback onTap,
  }) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(14),
        child: Ink(
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

  void _showErrorDialog(BuildContext context, String message, String? code) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: Theme.of(context).brightness == Brightness.dark
            ? const Color(0xFF1E1E1E)
            : Colors.white,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(20),
        ),
        title: Row(
          children: [
            Icon(
              Icons.error_outline,
              color: AppTheme.error,
              size: 28,
            ),
            const SizedBox(width: 12),
            const Text(
              'Connection Error',
              style: TextStyle(
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
        ),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (code != null) ...[
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(
                    color: AppTheme.error.withAlpha(25),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    'Error Code: $code',
                    style: TextStyle(
                      color: AppTheme.error,
                      fontWeight: FontWeight.w500,
                      fontSize: 12,
                    ),
                  ),
                ),
                const SizedBox(height: 16),
              ],
              Text(
                'Detailed Error:',
                style: TextStyle(
                  fontWeight: FontWeight.w500,
                  color: Theme.of(context).brightness == Brightness.dark
                      ? const Color(0xFF9AA0A6)
                      : const Color(0xFF5F6368),
                  fontSize: 12,
                ),
              ),
              const SizedBox(height: 8),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Theme.of(context).brightness == Brightness.dark
                      ? const Color(0xFF2D2D2D)
                      : const Color(0xFFF5F5F5),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: SelectableText(
                  message,
                  style: const TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 12,
                  ),
                ),
              ),
              const SizedBox(height: 16),
              Text(
                'Tips:',
                style: TextStyle(
                  fontWeight: FontWeight.w500,
                  color: Theme.of(context).brightness == Brightness.dark
                      ? const Color(0xFF9AA0A6)
                      : const Color(0xFF5F6368),
                  fontSize: 12,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                _getTroubleshootingTips(message),
                style: TextStyle(
                  fontSize: 12,
                  color: Theme.of(context).brightness == Brightness.dark
                      ? const Color(0xFFE8EAED)
                      : const Color(0xFF1F1F1F),
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () {
              Navigator.of(context).pop();
              context.read<VpnCubit>().reset();
            },
            child: Text(
              'Dismiss',
              style: TextStyle(
                color: AppTheme.primaryColor,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          TextButton(
            onPressed: () {
              Navigator.of(context).pop();
              _showAdvancedConfig();
            },
            child: Text(
              'Edit Config',
              style: TextStyle(
                color: AppTheme.primaryColor,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ],
      ),
    );
  }

  String _getTroubleshootingTips(String errorMessage) {
    final lowerMessage = errorMessage.toLowerCase();
    
    if (lowerMessage.contains('server') && lowerMessage.contains('null')) {
      return '• Server configuration is missing\n• Open Advanced Config and save your settings\n• Make sure IP and Port are entered correctly';
    } else if (lowerMessage.contains('connection_refused')) {
      return '• Check if server is running\n• Verify the port number is correct\n• Check firewall settings on server';
    } else if (lowerMessage.contains('timeout')) {
      return '• Server may be offline\n• Check network connectivity\n• Verify IP address is correct\n• Check firewall settings';
    } else if (lowerMessage.contains('dns') || lowerMessage.contains('unresolved')) {
      return '• Check if the server IP/hostname is correct\n• Try using IP address instead of hostname\n• Check your DNS settings';
    } else if (lowerMessage.contains('no_route')) {
      return '• Network is unreachable\n• Check WiFi/mobile data connection\n• Server IP may be invalid';
    } else if (lowerMessage.contains('permission')) {
      return '• Grant VPN permission when prompted\n• Reinstall the app if permission issues persist';
    } else if (lowerMessage.contains('protect_failed')) {
      return '• VPN socket protection failed\n• Try restarting the app\n• Check Android VPN settings';
    } else {
      return '• Check server IP and port\n• Verify network connectivity\n• Try a different server';
    }
  }
}
