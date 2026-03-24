import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_animate/flutter_animate.dart';

import '../../../core/theme/app_theme.dart';

/// Premium VPN Toggle Button
/// Large, Samsung One UI 6 style toggle with smooth animations
class VpnToggleButton extends StatefulWidget {
  const VpnToggleButton({
    super.key,
    required this.isActive,
    required this.onPressed,
    this.isAnimating = false,
    this.size = 180,
  });

  final bool isActive;
  final bool isAnimating;
  final double size;
  final VoidCallback onPressed;

  @override
  State<VpnToggleButton> createState() => _VpnToggleButtonState();
}

class _VpnToggleButtonState extends State<VpnToggleButton>
    with SingleTickerProviderStateMixin {
  late AnimationController _controller;
  late Animation<double> _scaleAnimation;
  late Animation<double> _glowAnimation;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 300),
    );

    _scaleAnimation = Tween<double>(begin: 1.0, end: 0.95).animate(
      CurvedAnimation(
        parent: _controller,
        curve: Curves.easeInOut,
      ),
    );

    _glowAnimation = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: Curves.easeInOut,
      ),
    );
  }

  @override
  void didUpdateWidget(VpnToggleButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.isAnimating && !oldWidget.isAnimating) {
      // Start pulsing animation when connecting
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _handleTapDown(TapDownDetails details) {
    _controller.forward();
    HapticFeedback.lightImpact();
  }

  void _handleTapUp(TapUpDetails details) {
    _controller.reverse();
    widget.onPressed();
  }

  void _handleTapCancel() {
    _controller.reverse();
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return GestureDetector(
      onTapDown: _handleTapDown,
      onTapUp: _handleTapUp,
      onTapCancel: _handleTapCancel,
      child: AnimatedBuilder(
        animation: _controller,
        builder: (context, child) {
          return Transform.scale(
            scale: _scaleAnimation.value,
            child: child,
          );
        },
        child: Stack(
          alignment: Alignment.center,
          children: [
            // Glow Effect (when active)
            if (widget.isActive)
              Container(
                width: widget.size + 40,
                height: widget.size + 40,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  boxShadow: [
                    BoxShadow(
                      color: AppTheme.primaryColor.withValues(alpha: 0.3),
                      blurRadius: 40,
                      spreadRadius: 10,
                    ),
                  ],
                ),
              ).animate().fadeIn(duration: 400.ms).scale(
                    begin: const Offset(0.8, 0.8),
                    end: const Offset(1, 1),
                    duration: 400.ms,
                  ),

            // Pulse rings when connecting
            if (widget.isAnimating) ..._buildPulseRings(isDark),

            // Main button
            Container(
              width: widget.size,
              height: widget.size,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: widget.isActive
                    ? const LinearGradient(
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                        colors: [
                          AppTheme.primaryColor,
                          AppTheme.secondaryColor,
                        ],
                      )
                    : null,
                color: widget.isActive
                    ? null
                    : (isDark ? const Color(0xFF2D2D2D) : const Color(0xFFE8EAED)),
                boxShadow: [
                  BoxShadow(
                    color: widget.isActive
                        ? AppTheme.primaryColor.withValues(alpha: 0.4)
                        : Colors.black.withValues(alpha: isDark ? 0.3 : 0.1),
                    blurRadius: 30,
                    offset: const Offset(0, 10),
                  ),
                ],
              ),
              child: Stack(
                alignment: Alignment.center,
                children: [
                  // Icon
                  AnimatedSwitcher(
                    duration: const Duration(milliseconds: 300),
                    child: widget.isAnimating
                        ? _buildLoadingIndicator(isDark)
                        : Icon(
                            widget.isActive ? Icons.vpn_lock : Icons.power_settings_new,
                            key: ValueKey(widget.isActive),
                            size: widget.size * 0.4,
                            color: widget.isActive
                                ? Colors.white
                                : (isDark
                                    ? const Color(0xFF9AA0A6)
                                    : const Color(0xFF5F6368)),
                          ),
                  ),

                  // Progress ring when animating
                  if (widget.isAnimating)
                    SizedBox(
                      width: widget.size - 16,
                      height: widget.size - 16,
                      child: CircularProgressIndicator(
                        strokeWidth: 3,
                        valueColor: const AlwaysStoppedAnimation<Color>(Colors.white),
                        strokeCap: StrokeCap.round,
                        backgroundColor: Colors.white.withValues(alpha: 0.2),
                      ),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  List<Widget> _buildPulseRings(bool isDark) {
    return [
      for (int i = 0; i < 3; i++)
        Container(
          width: widget.size + 20.0 + (i * 30),
          height: widget.size + 20.0 + (i * 30),
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            border: Border.all(
              color: AppTheme.primaryColor.withValues(alpha: 0.3 - (i * 0.1)),
              width: 2,
            ),
          ),
        )
            .animate(onPlay: (controller) => controller.repeat())
            .scale(
              begin: const Offset(0.8, 0.8),
              end: const Offset(1.2, 1.2),
              duration: 1500.ms,
              delay: Duration(milliseconds: i * 300),
            )
            .fadeOut(
              duration: 1500.ms,
              delay: Duration(milliseconds: i * 300),
            ),
    ];
  }

  Widget _buildLoadingIndicator(bool isDark) {
    return SizedBox(
      key: const ValueKey('loading'),
      width: widget.size * 0.4,
      height: widget.size * 0.4,
      child: CircularProgressIndicator(
        strokeWidth: 3,
        valueColor: AlwaysStoppedAnimation<Color>(
          widget.isActive
              ? Colors.white
              : (isDark
                  ? const Color(0xFF9AA0A6)
                  : const Color(0xFF5F6368)),
        ),
        strokeCap: StrokeCap.round,
      ),
    );
  }
}
