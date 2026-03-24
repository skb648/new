import 'package:flutter/material.dart';

/// Notification Handler Widget
/// Wrapper widget for handling global notifications and overlays
class NotificationHandler extends StatelessWidget {
  const NotificationHandler({
    super.key,
    required this.child,
  });

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        child,
        // Add global overlay widgets here if needed
      ],
    );
  }
}
