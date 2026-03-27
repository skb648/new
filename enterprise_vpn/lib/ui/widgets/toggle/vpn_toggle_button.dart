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
  bool _isPressed = false;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 150),
    );

    _scaleAnimation = Tween<double>(begin: 1.0, end: 0.92).animate(
      CurvedAnimation(
        parent: _controller,
        curve: Curves.easeInOut,
      ),
    );
  }

  @override
  void didUpdateWidget(VpnToggleButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    // Reset animation when state changes externally
    if (widget.isActive != oldWidget.isActive || 
        widget.isAnimating != oldWidget.isAnimating) {
      if (!widget.isAnimating) {
        _controller.reverse();
      }
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _handleTap() {
    // ✅ FIX: Always call onPressed - let parent handle state checks
    HapticFeedback.mediumImpact();
    widget.onPressed();
  }

  void _handleTapDown(TapDownDetails details) {
    setState(() => _isPressed = true);
    _controller.forward();
    HapticFeedback.lightImpact();
  }

  void _handleTapUp(TapUpDetails details) {
    setState(() => _isPressed = false);
    _controller.reverse();
    // Call onPressed after animation starts
    widget.onPressed();
  }

  void _handleTapCancel() {
    setState(() => _isPressed = false);
    _controller.reverse();
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return GestureDetector(
      // ✅ FIX: Add onTap as fallback - this is the most reliable
      onTap: _handleTap,
      onTapDown: _handleTapDown,
      onTapUp: _handleTapUp,
      onTapCancel: _handleTapCancel,
      behavior: HitTestBehavior.opaque,
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
                width: widget.size + 60,
                height: widget.size + 60,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  boxShadow: [
                    BoxShadow(
                      color: AppTheme.primaryColor.withAlpha(80),
                      blurRadius: 50,
                      spreadRadius: 15,
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
            AnimatedContainer(
              duration: const Duration(milliseconds: 300),
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
                        ? AppTheme.primaryColor.withAlpha(100)
                        : (isDark ? Colors.black.withAlpha(80) : Colors.black.withAlpha(25)),
                    blurRadius: 30,
                    offset: const Offset(0, 10),
                  ),
                  // Add inner shadow effect
                  BoxShadow(
                    color: widget.isActive
                        ? AppTheme.primaryColor.withAlpha(50)
                        : Colors.transparent,
                    blurRadius: 60,
                    spreadRadius: 10,
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
                            widget.isActive ? Icons.vpn_lock : Icons.power_settings_new_rounded,
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
                      width: widget.size - 20,
                      height: widget.size - 20,
                      child: CircularProgressIndicator(
                        strokeWidth: 4,
                        valueColor: const AlwaysStoppedAnimation<Color>(Colors.white),
                        strokeCap: StrokeCap.round,
                        backgroundColor: Colors.white.withAlpha(50),
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
          width: widget.size + 20.0 + (i * 40),
          height: widget.size + 20.0 + (i * 40),
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            border: Border.all(
              color: AppTheme.primaryColor.withAlpha((80 - (i * 25)).clamp(0, 255)),
              width: 2,
            ),
          ),
        )
            .animate(onPlay: (controller) => controller.repeat())
            .scale(
              begin: const Offset(0.8, 0.8),
              end: const Offset(1.3, 1.3),
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
      width: widget.size * 0.35,
      height: widget.size * 0.35,
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
