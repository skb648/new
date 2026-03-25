import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_animate/flutter_animate.dart';

import '../../../core/theme/app_theme.dart';
import '../../../core/constants/app_constants.dart';
import '../../../models/vpn_config.dart';
import '../../bloc/config/config_cubit.dart';
import '../../bloc/vpn/vpn_cubit.dart';

/// Advanced Configuration Bottom Sheet
/// Swipe-up sheet for configuring Server IP, Port, HTTP Headers, and SNI
class AdvancedConfigSheet extends StatefulWidget {
  const AdvancedConfigSheet({super.key});

  @override
  State<AdvancedConfigSheet> createState() => _AdvancedConfigSheetState();
}

class _AdvancedConfigSheetState extends State<AdvancedConfigSheet>
    with SingleTickerProviderStateMixin {
  late TabController _tabController;
  late TextEditingController _serverIpController;
  late TextEditingController _portController;
  late TextEditingController _sniController;
  late TextEditingController _usernameController;
  late TextEditingController _passwordController;
  late ScrollController _scrollController;

  final List<HeaderInputController> _headerControllers = [];
  bool _sniEnabled = true;
  String _selectedProtocol = 'TCP';
  bool _obscurePassword = true;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 3, vsync: this);
    _scrollController = ScrollController();
    _serverIpController = TextEditingController();
    _portController = TextEditingController(text: '443');
    _sniController = TextEditingController();
    _usernameController = TextEditingController();
    _passwordController = TextEditingController();

    _loadSavedConfig();
  }

  void _loadSavedConfig() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final configState = context.read<ConfigCubit>().state;
      if (configState is ConfigLoaded) {
        final config = configState.config;
        if (config.server != null) {
          _serverIpController.text = config.server!.serverIp;
          _portController.text = config.server!.port.toString();
          _selectedProtocol = config.server!.protocol;
          _usernameController.text = config.server!.username;
          _passwordController.text = config.server!.password;
        }
        if (config.sniConfig != null) {
          _sniController.text = config.sniConfig!.serverName;
          _sniEnabled = config.sniConfig!.enabled;
        }
        for (final header in config.httpHeaders) {
          _headerControllers.add(HeaderInputController(
            nameController: TextEditingController(text: header.name),
            valueController: TextEditingController(text: header.value),
            enabled: header.enabled,
          ));
        }
        setState(() {});
      }
    });
  }

  @override
  void dispose() {
    _tabController.dispose();
    _scrollController.dispose();
    _serverIpController.dispose();
    _portController.dispose();
    _sniController.dispose();
    _usernameController.dispose();
    _passwordController.dispose();
    for (final controller in _headerControllers) {
      controller.dispose();
    }
    super.dispose();
  }

  void _saveConfiguration() {
    HapticFeedback.mediumImpact();

    // Validate inputs
    if (_serverIpController.text.isEmpty) {
      _showError('Please enter a server IP address');
      return;
    }

    final port = int.tryParse(_portController.text);
    if (port == null || port < 1 || port > 65535) {
      _showError('Please enter a valid port number (1-65535)');
      return;
    }

    // Validate authentication if provided
    final username = _usernameController.text.trim();
    final password = _passwordController.text.trim();
    
    if (username.isNotEmpty && password.isEmpty) {
      _showError('Please enter a password for the username');
      return;
    }

    // Create server config with authentication
    final serverConfig = VpnServerConfig(
      id: 'custom_${DateTime.now().millisecondsSinceEpoch}',
      name: 'Custom Server',
      serverIp: _serverIpController.text.trim(),
      port: port,
      protocol: _selectedProtocol,
      username: username,
      password: password,
    );

    // Debug logging
    debugPrint('========================================');
    debugPrint('AdvancedConfigSheet._saveConfiguration()');
    debugPrint('serverConfig.id: ${serverConfig.id}');
    debugPrint('serverConfig.serverIp: ${serverConfig.serverIp}');
    debugPrint('serverConfig.port: ${serverConfig.port}');
    debugPrint('serverConfig.protocol: ${serverConfig.protocol}');
    debugPrint('serverConfig.isValid: ${serverConfig.serverIp.isNotEmpty && serverConfig.port > 0}');
    debugPrint('========================================');

    // Create SNI config
    SniConfig? sniConfig;
    if (_sniController.text.isNotEmpty) {
      if (!AppConstants.sniPattern.hasMatch(_sniController.text)) {
        _showError('Invalid SNI format');
        return;
      }
      sniConfig = SniConfig(
        serverName: _sniController.text.trim(),
        enabled: _sniEnabled,
      );
    }

    // Create HTTP headers
    final headers = _headerControllers
        .where((c) => c.nameController.text.isNotEmpty)
        .map((c) => HttpHeader(
              name: c.nameController.text.trim(),
              value: c.valueController.text.trim(),
              enabled: c.enabled,
            ))
        .toList();

    // Create complete config
    final config = VpnConfig(
      server: serverConfig,
      sniConfig: sniConfig,
      httpHeaders: headers,
    );

    // Debug logging for final config
    debugPrint('========================================');
    debugPrint('Final VpnConfig created:');
    debugPrint('config.isValid: ${config.isValid}');
    debugPrint('config.server: ${config.server}');
    debugPrint('========================================');

    // Save configuration
    context.read<ConfigCubit>().saveConfiguration(config);

    // Show success message
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: const Text('Configuration saved successfully'),
        backgroundColor: AppTheme.success,
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(16),
        ),
        action: SnackBarAction(
          label: 'Connect',
          textColor: Colors.white,
          onPressed: () {
            Navigator.of(context).pop();
            context.read<VpnCubit>().connect(config);
          },
        ),
      ),
    );
  }

  void _showError(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: AppTheme.error,
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(16),
        ),
      ),
    );
  }

  void _addHeader() {
    setState(() {
      _headerControllers.add(HeaderInputController(
        nameController: TextEditingController(),
        valueController: TextEditingController(),
        enabled: true,
      ));
    });
    HapticFeedback.lightImpact();
  }

  void _removeHeader(int index) {
    setState(() {
      _headerControllers[index].dispose();
      _headerControllers.removeAt(index);
    });
    HapticFeedback.lightImpact();
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final bottomPadding = MediaQuery.of(context).viewPadding.bottom;

    return DraggableScrollableSheet(
      initialChildSize: 0.85,
      minChildSize: 0.5,
      maxChildSize: 0.95,
      builder: (context, scrollController) {
        return Container(
          decoration: BoxDecoration(
            color: isDark ? const Color(0xFF1E1E1E) : Colors.white,
            borderRadius: const BorderRadius.vertical(
              top: Radius.circular(28),
            ),
          ),
          child: Column(
            children: [
              // Drag Handle
              Container(
                margin: const EdgeInsets.only(top: 12),
                width: 40,
                height: 4,
                decoration: BoxDecoration(
                  color: isDark
                      ? const Color(0xFF424242)
                      : const Color(0xFFDADCE0),
                  borderRadius: BorderRadius.circular(2),
                ),
              ),

              // Header
              Padding(
                padding: const EdgeInsets.all(24),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      'Advanced Routing Config',
                      style: Theme.of(context).textTheme.titleLarge?.copyWith(
                            fontWeight: FontWeight.w600,
                            color: isDark
                                ? const Color(0xFFE8EAED)
                                : const Color(0xFF1F1F1F),
                          ),
                    ),
                    IconButton(
                      onPressed: () => Navigator.of(context).pop(),
                      icon: Icon(
                        Icons.close,
                        color: isDark
                            ? const Color(0xFF9AA0A6)
                            : const Color(0xFF5F6368),
                      ),
                    ),
                  ],
                ),
              ),

              // Tab Bar
              Container(
                margin: const EdgeInsets.symmetric(horizontal: 24),
                decoration: BoxDecoration(
                  color: isDark
                      ? const Color(0xFF2D2D2D)
                      : const Color(0xFFF5F5F5),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: TabBar(
                  controller: _tabController,
                  indicator: BoxDecoration(
                    color: AppTheme.primaryColor,
                    borderRadius: BorderRadius.circular(10),
                  ),
                  indicatorPadding: const EdgeInsets.all(4),
                  indicatorSize: TabBarIndicatorSize.tab,
                  dividerColor: Colors.transparent,
                  labelColor: Colors.white,
                  unselectedLabelColor: isDark
                      ? const Color(0xFF9AA0A6)
                      : const Color(0xFF5F6368),
                  labelStyle: const TextStyle(
                    fontWeight: FontWeight.w600,
                    fontSize: 13,
                  ),
                  tabs: const [
                    Tab(text: 'Server'),
                    Tab(text: 'Headers'),
                    Tab(text: 'SNI'),
                  ],
                ),
              ),

              // Tab Content
              Expanded(
                child: TabBarView(
                  controller: _tabController,
                  children: [
                    _buildServerTab(context, isDark, scrollController),
                    _buildHeadersTab(context, isDark, scrollController),
                    _buildSniTab(context, isDark, scrollController),
                  ],
                ),
              ),

              // Save Button
              Container(
                padding: EdgeInsets.fromLTRB(24, 16, 24, 24 + bottomPadding),
                child: SizedBox(
                  width: double.infinity,
                  height: 56,
                  child: ElevatedButton(
                    onPressed: _saveConfiguration,
                    style: ElevatedButton.styleFrom(
                      backgroundColor: AppTheme.primaryColor,
                      foregroundColor: Colors.white,
                      elevation: 0,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(16),
                      ),
                    ),
                    child: const Text(
                      'Save Configuration',
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ).animate().fadeIn(duration: 200.ms).slideY(
              begin: 0.1,
              end: 0,
              duration: 300.ms,
              curve: Curves.easeOutCubic,
            );
      },
    );
  }

  Widget _buildServerTab(
    BuildContext context,
    bool isDark,
    ScrollController scrollController,
  ) {
    return SingleChildScrollView(
      controller: scrollController,
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Server IP Input
          _buildInputField(
            context: context,
            label: 'Server IP Address',
            hint: 'Enter server IP or domain',
            controller: _serverIpController,
            isDark: isDark,
            keyboardType: TextInputType.text,
            prefixIcon: Icons.dns_outlined,
          ),

          const SizedBox(height: 20),

          // Port Input
          _buildInputField(
            context: context,
            label: 'Port',
            hint: '443',
            controller: _portController,
            isDark: isDark,
            keyboardType: TextInputType.number,
            prefixIcon: Icons.settings_ethernet,
            inputFormatters: [
              FilteringTextInputFormatter.digitsOnly,
              LengthLimitingTextInputFormatter(5),
            ],
          ),

          const SizedBox(height: 20),

          // Protocol Selection
          Text(
            'Protocol',
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  fontWeight: FontWeight.w500,
                  color: isDark
                      ? const Color(0xFFE8EAED)
                      : const Color(0xFF1F1F1F),
                ),
          ),
          const SizedBox(height: 12),
          Row(
            children: ['TCP', 'UDP'].map((protocol) {
              final isSelected = _selectedProtocol == protocol;
              return Padding(
                padding: const EdgeInsets.only(right: 12),
                child: GestureDetector(
                  onTap: () {
                    setState(() => _selectedProtocol = protocol);
                    HapticFeedback.selectionClick();
                  },
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 24,
                      vertical: 12,
                    ),
                    decoration: BoxDecoration(
                      color: isSelected
                          ? AppTheme.primaryColor
                          : (isDark
                              ? const Color(0xFF2D2D2D)
                              : const Color(0xFFF5F5F5)),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Text(
                      protocol,
                      style: TextStyle(
                        color: isSelected
                            ? Colors.white
                            : (isDark
                                ? const Color(0xFFE8EAED)
                                : const Color(0xFF5F6368)),
                        fontWeight:
                            isSelected ? FontWeight.w600 : FontWeight.w400,
                      ),
                    ),
                  ),
                ),
              );
            }).toList(),
          ),

          const SizedBox(height: 24),

          // Authentication Section
          Container(
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
                    Icon(
                      Icons.lock_outline,
                      color: AppTheme.primaryColor,
                      size: 20,
                    ),
                    const SizedBox(width: 8),
                    Text(
                      'Server Authentication',
                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                            fontWeight: FontWeight.w600,
                            color: isDark
                                ? const Color(0xFFE8EAED)
                                : const Color(0xFF1F1F1F),
                          ),
                    ),
                  ],
                ),
                const SizedBox(height: 16),
                
                // Username Input
                _buildInputField(
                  context: context,
                  label: 'Username',
                  hint: 'Enter username (optional)',
                  controller: _usernameController,
                  isDark: isDark,
                  keyboardType: TextInputType.text,
                  prefixIcon: Icons.person_outline,
                ),
                
                const SizedBox(height: 16),
                
                // Password Input
                Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Password',
                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                            fontWeight: FontWeight.w500,
                            color: isDark
                                ? const Color(0xFFE8EAED)
                                : const Color(0xFF1F1F1F),
                          ),
                    ),
                    const SizedBox(height: 8),
                    TextField(
                      controller: _passwordController,
                      obscureText: _obscurePassword,
                      style: TextStyle(
                        color: isDark
                            ? const Color(0xFFE8EAED)
                            : const Color(0xFF1F1F1F),
                      ),
                      decoration: InputDecoration(
                        hintText: 'Enter password (optional)',
                        hintStyle: TextStyle(
                          color: isDark
                              ? const Color(0xFF6B7280)
                              : const Color(0xFF9AA0A6),
                        ),
                        filled: true,
                        fillColor: isDark
                            ? const Color(0xFF1E1E1E)
                            : Colors.white,
                        border: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(12),
                          borderSide: BorderSide.none,
                        ),
                        focusedBorder: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(12),
                          borderSide: const BorderSide(
                            color: AppTheme.primaryColor,
                            width: 2,
                          ),
                        ),
                        contentPadding: const EdgeInsets.symmetric(
                          horizontal: 16,
                          vertical: 16,
                        ),
                        prefixIcon: Icon(
                          Icons.key_outlined,
                          color: isDark
                              ? const Color(0xFF9AA0A6)
                              : const Color(0xFF5F6368),
                          size: 20,
                        ),
                        suffixIcon: IconButton(
                          icon: Icon(
                            _obscurePassword
                                ? Icons.visibility_outlined
                                : Icons.visibility_off_outlined,
                            color: isDark
                                ? const Color(0xFF9AA0A6)
                                : const Color(0xFF5F6368),
                            size: 20,
                          ),
                          onPressed: () {
                            setState(() {
                              _obscurePassword = !_obscurePassword;
                            });
                          },
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),

          const SizedBox(height: 24),

          // Quick Server Templates
          Text(
            'Quick Templates',
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  fontWeight: FontWeight.w500,
                  color: isDark
                      ? const Color(0xFFE8EAED)
                      : const Color(0xFF1F1F1F),
                ),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _buildTemplateChip(
                context,
                label: 'AWS',
                isDark: isDark,
                onTap: () => _applyTemplate('ec2.amazonaws.com', 443),
              ),
              _buildTemplateChip(
                context,
                label: 'Azure',
                isDark: isDark,
                onTap: () => _applyTemplate('azure.microsoft.com', 443),
              ),
              _buildTemplateChip(
                context,
                label: 'GCP',
                isDark: isDark,
                onTap: () => _applyTemplate('cloud.google.com', 443),
              ),
              _buildTemplateChip(
                context,
                label: 'Custom',
                isDark: isDark,
                onTap: () {},
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildHeadersTab(
    BuildContext context,
    bool isDark,
    ScrollController scrollController,
  ) {
    return SingleChildScrollView(
      controller: scrollController,
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Header Info
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: AppTheme.info.withAlpha(25),
              borderRadius: BorderRadius.circular(16),
            ),
            child: Row(
              children: [
                Icon(
                  Icons.info_outline,
                  color: AppTheme.info,
                  size: 20,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    'Custom HTTP headers will be injected into all tunneled requests.',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: AppTheme.info,
                        ),
                  ),
                ),
              ],
            ),
          ),

          const SizedBox(height: 24),

          // Header List
          ...List.generate(_headerControllers.length, (index) {
            return _buildHeaderInput(context, index, isDark);
          }),

          // Add Header Button
          GestureDetector(
            onTap: _addHeader,
            child: Container(
              width: double.infinity,
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                border: Border.all(
                  color: isDark
                      ? const Color(0xFF424242)
                      : const Color(0xFFDADCE0),
                  width: 1,
                  style: BorderStyle.solid,
                ),
                borderRadius: BorderRadius.circular(16),
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(
                    Icons.add,
                    color: AppTheme.primaryColor,
                  ),
                  const SizedBox(width: 8),
                  Text(
                    'Add Custom Header',
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                          color: AppTheme.primaryColor,
                          fontWeight: FontWeight.w500,
                        ),
                  ),
                ],
              ),
            ),
          ),

          const SizedBox(height: 24),

          // Common Headers
          Text(
            'Common Headers',
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  fontWeight: FontWeight.w500,
                  color: isDark
                      ? const Color(0xFFE8EAED)
                      : const Color(0xFF1F1F1F),
                ),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _buildTemplateChip(
                context,
                label: 'X-Forwarded-For',
                isDark: isDark,
                onTap: () => _addPresetHeader('X-Forwarded-For', ''),
              ),
              _buildTemplateChip(
                context,
                label: 'X-Real-IP',
                isDark: isDark,
                onTap: () => _addPresetHeader('X-Real-IP', ''),
              ),
              _buildTemplateChip(
                context,
                label: 'Authorization',
                isDark: isDark,
                onTap: () => _addPresetHeader('Authorization', 'Bearer '),
              ),
              _buildTemplateChip(
                context,
                label: 'User-Agent',
                isDark: isDark,
                onTap: () => _addPresetHeader('User-Agent', 'EnterpriseVPN/1.0'),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildHeaderInput(
    BuildContext context,
    int index,
    bool isDark,
  ) {
    final controller = _headerControllers[index];

    return Container(
      margin: const EdgeInsets.only(bottom: 16),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: isDark ? const Color(0xFF2D2D2D) : const Color(0xFFF5F5F5),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Header Row
          Row(
            children: [
              Expanded(
                child: Text(
                  'Header #${index + 1}',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: isDark
                            ? const Color(0xFF9AA0A6)
                            : const Color(0xFF5F6368),
                      ),
                ),
              ),
              // Enable/Disable Toggle
              Switch(
                value: controller.enabled,
                onChanged: (value) {
                  setState(() => controller.enabled = value);
                },
                activeColor: AppTheme.primaryColor,
              ),
              // Delete Button
              GestureDetector(
                onTap: () => _removeHeader(index),
                child: Icon(
                  Icons.delete_outline,
                  color: AppTheme.error,
                  size: 20,
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),

          // Name Input
          TextField(
            controller: controller.nameController,
            style: TextStyle(
              color: isDark ? const Color(0xFFE8EAED) : const Color(0xFF1F1F1F),
            ),
            decoration: InputDecoration(
              hintText: 'Header Name',
              hintStyle: TextStyle(
                color: isDark
                    ? const Color(0xFF6B7280)
                    : const Color(0xFF9AA0A6),
              ),
              filled: true,
              fillColor: isDark
                  ? const Color(0xFF1E1E1E)
                  : Colors.white,
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
                borderSide: BorderSide.none,
              ),
              contentPadding: const EdgeInsets.symmetric(
                horizontal: 16,
                vertical: 14,
              ),
            ),
          ),
          const SizedBox(height: 12),

          // Value Input
          TextField(
            controller: controller.valueController,
            style: TextStyle(
              color: isDark ? const Color(0xFFE8EAED) : const Color(0xFF1F1F1F),
            ),
            decoration: InputDecoration(
              hintText: 'Header Value',
              hintStyle: TextStyle(
                color: isDark
                    ? const Color(0xFF6B7280)
                    : const Color(0xFF9AA0A6),
              ),
              filled: true,
              fillColor: isDark
                  ? const Color(0xFF1E1E1E)
                  : Colors.white,
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
                borderSide: BorderSide.none,
              ),
              contentPadding: const EdgeInsets.symmetric(
                horizontal: 16,
                vertical: 14,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSniTab(
    BuildContext context,
    bool isDark,
    ScrollController scrollController,
  ) {
    return SingleChildScrollView(
      controller: scrollController,
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // SNI Info
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: AppTheme.info.withAlpha(25),
              borderRadius: BorderRadius.circular(16),
            ),
            child: Row(
              children: [
                Icon(
                  Icons.info_outline,
                  color: AppTheme.info,
                  size: 20,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    'Server Name Indication (SNI) is used during TLS handshake to specify the hostname.',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: AppTheme.info,
                        ),
                  ),
                ),
              ],
            ),
          ),

          const SizedBox(height: 24),

          // SNI Enable Toggle
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: isDark
                  ? const Color(0xFF2D2D2D)
                  : const Color(0xFFF5F5F5),
              borderRadius: BorderRadius.circular(16),
            ),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Enable Custom SNI',
                        style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                              fontWeight: FontWeight.w500,
                              color: isDark
                                  ? const Color(0xFFE8EAED)
                                  : const Color(0xFF1F1F1F),
                            ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        'Override the default SNI value',
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                              color: isDark
                                  ? const Color(0xFF9AA0A6)
                                  : const Color(0xFF5F6368),
                            ),
                      ),
                    ],
                  ),
                ),
                Switch(
                  value: _sniEnabled,
                  onChanged: (value) {
                    setState(() => _sniEnabled = value);
                    HapticFeedback.selectionClick();
                  },
                  activeColor: AppTheme.primaryColor,
                ),
              ],
            ),
          ),

          const SizedBox(height: 24),

          // SNI Input
          _buildInputField(
            context: context,
            label: 'Server Name (SNI)',
            hint: 'e.g., www.example.com',
            controller: _sniController,
            isDark: isDark,
            keyboardType: TextInputType.text,
            prefixIcon: Icons.verified_user_outlined,
            enabled: _sniEnabled,
          ),

          const SizedBox(height: 24),

          // SNI Presets
          Text(
            'Common SNI Presets',
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  fontWeight: FontWeight.w500,
                  color: isDark
                      ? const Color(0xFFE8EAED)
                      : const Color(0xFF1F1F1F),
                ),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _buildTemplateChip(
                context,
                label: 'www.google.com',
                isDark: isDark,
                onTap: () => _applySniPreset('www.google.com'),
              ),
              _buildTemplateChip(
                context,
                label: 'www.cloudflare.com',
                isDark: isDark,
                onTap: () => _applySniPreset('www.cloudflare.com'),
              ),
              _buildTemplateChip(
                context,
                label: 'www.microsoft.com',
                isDark: isDark,
                onTap: () => _applySniPreset('www.microsoft.com'),
              ),
              _buildTemplateChip(
                context,
                label: 'www.apple.com',
                isDark: isDark,
                onTap: () => _applySniPreset('www.apple.com'),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildInputField({
    required BuildContext context,
    required String label,
    required String hint,
    required TextEditingController controller,
    required bool isDark,
    required IconData prefixIcon,
    TextInputType keyboardType = TextInputType.text,
    List<TextInputFormatter>? inputFormatters,
    bool enabled = true,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                fontWeight: FontWeight.w500,
                color: isDark
                    ? const Color(0xFFE8EAED)
                    : const Color(0xFF1F1F1F),
              ),
        ),
        const SizedBox(height: 8),
        TextField(
          controller: controller,
          enabled: enabled,
          keyboardType: keyboardType,
          inputFormatters: inputFormatters,
          style: TextStyle(
            color: enabled
                ? (isDark ? const Color(0xFFE8EAED) : const Color(0xFF1F1F1F))
                : (isDark ? const Color(0xFF6B7280) : const Color(0xFF9AA0A6)),
          ),
          decoration: InputDecoration(
            hintText: hint,
            hintStyle: TextStyle(
              color: isDark
                  ? const Color(0xFF6B7280)
                  : const Color(0xFF9AA0A6),
            ),
            filled: true,
            fillColor: isDark
                ? const Color(0xFF2D2D2D)
                : const Color(0xFFF5F5F5),
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(16),
              borderSide: BorderSide.none,
            ),
            focusedBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(16),
              borderSide: const BorderSide(
                color: AppTheme.primaryColor,
                width: 2,
              ),
            ),
            contentPadding: const EdgeInsets.symmetric(
              horizontal: 16,
              vertical: 16,
            ),
            prefixIcon: Icon(
              prefixIcon,
              color: isDark
                  ? const Color(0xFF9AA0A6)
                  : const Color(0xFF5F6368),
              size: 20,
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildTemplateChip(
    BuildContext context, {
    required String label,
    required bool isDark,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        decoration: BoxDecoration(
          color: isDark
              ? const Color(0xFF2D2D2D)
              : const Color(0xFFF5F5F5),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: isDark
                ? const Color(0xFF424242)
                : const Color(0xFFE8EAED),
          ),
        ),
        child: Text(
          label,
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: isDark
                    ? const Color(0xFFE8EAED)
                    : const Color(0xFF5F6368),
                fontWeight: FontWeight.w500,
              ),
        ),
      ),
    );
  }

  void _applyTemplate(String server, int port) {
    _serverIpController.text = server;
    _portController.text = port.toString();
    HapticFeedback.lightImpact();
  }

  void _applySniPreset(String sni) {
    _sniController.text = sni;
    HapticFeedback.lightImpact();
  }

  void _addPresetHeader(String name, String value) {
    _headerControllers.add(HeaderInputController(
      nameController: TextEditingController(text: name),
      valueController: TextEditingController(text: value),
      enabled: true,
    ));
    setState(() {});
    HapticFeedback.lightImpact();
  }
}

/// Helper class to manage header input controllers
class HeaderInputController {
  HeaderInputController({
    required this.nameController,
    required this.valueController,
    this.enabled = true,
  });

  final TextEditingController nameController;
  final TextEditingController valueController;
  bool enabled;

  void dispose() {
    nameController.dispose();
    valueController.dispose();
  }
}
