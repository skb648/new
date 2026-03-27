import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Samsung One UI 6 Style Theme Configuration
/// Premium, clean design with rounded corners and smooth animations
class AppTheme {
  AppTheme._();

  // ============================================
  // COLOR PALETTE - Samsung One UI 6 Inspired
  // ============================================

  // Primary Colors
  static const Color primaryColor = Color(0xFF1A73E8);
  static const Color primaryDark = Color(0xFF1557B0);
  static const Color primaryLight = Color(0xFF4285F4);
  static const Color primaryVariant = Color(0xFF0D47A1);

  // Secondary Colors
  static const Color secondaryColor = Color(0xFF00BFA5);
  static const Color secondaryDark = Color(0xFF00897B);
  static const Color secondaryLight = Color(0xFF64FFDA);
  static const Color secondaryVariant = Color(0xFF00695C);

  // Status Colors
  static const Color statusConnected = Color(0xFF34A853);
  static const Color statusConnecting = Color(0xFFFBBC04);
  static const Color statusDisconnected = Color(0xFF9AA0A6);
  static const Color statusError = Color(0xFFEA4335);
  static const Color statusWarning = Color(0xFFFBBC04);

  // Semantic Colors
  static const Color success = Color(0xFF34A853);
  static const Color warning = Color(0xFFFBBC04);
  static const Color error = Color(0xFFEA4335);
  static const Color info = Color(0xFF4285F4);

  // ============================================
  // LIGHT THEME
  // ============================================

  static ThemeData get light {
    final base = ThemeData.light(useMaterial3: true);

    return base.copyWith(
      // Brand Colors
      primaryColor: primaryColor,
      primaryColorLight: primaryLight,
      primaryColorDark: primaryDark,
      scaffoldBackgroundColor: const Color(0xFFF7F7F7),

      // Color Scheme
      colorScheme: const ColorScheme.light(
        primary: primaryColor,
        onPrimary: Colors.white,
        primaryContainer: Color(0xFFD3E3FD),
        onPrimaryContainer: Color(0xFF041E49),
        secondary: secondaryColor,
        onSecondary: Colors.white,
        secondaryContainer: Color(0xFFCEFAF8),
        onSecondaryContainer: Color(0xFF00201C),
        tertiary: Color(0xFF00BFA5),
        error: error,
        onError: Colors.white,
        errorContainer: Color(0xFFFFDAD6),
        onErrorContainer: Color(0xFF410002),
        surface: Colors.white,
        onSurface: Color(0xFF1F1F1F),
        surfaceContainerHighest: Color(0xFFE3E3E3),
        onSurfaceVariant: Color(0xFF5F6368),
        outline: Color(0xFFDADCE0),
        outlineVariant: Color(0xFFE8EAED),
      ),

      // AppBar Theme
      appBarTheme: const AppBarTheme(
        elevation: 0,
        scrolledUnderElevation: 0,
        backgroundColor: Colors.transparent,
        foregroundColor: Color(0xFF1F1F1F),
        centerTitle: false,
        titleSpacing: 20,
        titleTextStyle: TextStyle(
          fontSize: 22,
          fontWeight: FontWeight.w600,
          color: Color(0xFF1F1F1F),
        ),
        systemOverlayStyle: SystemUiOverlayStyle(
          statusBarColor: Colors.transparent,
          statusBarIconBrightness: Brightness.dark,
          statusBarBrightness: Brightness.light,
        ),
      ),

      // Card Theme
      cardTheme: CardTheme(
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(24),
        ),
        color: Colors.white,
        surfaceTintColor: Colors.white,
      ),

      // Elevated Button Theme
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: primaryColor,
          foregroundColor: Colors.white,
          elevation: 0,
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
          textStyle: const TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w500,
          ),
        ),
      ),

      // Text Button Theme
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          foregroundColor: primaryColor,
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
          textStyle: const TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w500,
          ),
        ),
      ),

      // Input Decoration Theme
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: const Color(0xFFF5F5F5),
        contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 18),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide.none,
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide.none,
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: const BorderSide(color: primaryColor, width: 2),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: const BorderSide(color: error, width: 2),
        ),
        focusedErrorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: const BorderSide(color: error, width: 2),
        ),
        hintStyle: const TextStyle(
          fontSize: 16,
          color: Color(0xFF9AA0A6),
        ),
        labelStyle: const TextStyle(
          fontSize: 16,
          color: Color(0xFF5F6368),
        ),
        floatingLabelStyle: const TextStyle(
          fontSize: 14,
          color: primaryColor,
        ),
      ),

      // Switch Theme
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return primaryColor;
          }
          return const Color(0xFF9AA0A6);
        }),
        trackColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return primaryColor.withAlpha(128);
          }
          return const Color(0xFFE8EAED);
        }),
      ),

      // Floating Action Button Theme
      floatingActionButtonTheme: const FloatingActionButtonThemeData(
        backgroundColor: primaryColor,
        foregroundColor: Colors.white,
        elevation: 4,
        shape: CircleBorder(),
      ),

      // Bottom Sheet Theme
      bottomSheetTheme: const BottomSheetThemeData(
        backgroundColor: Colors.white,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
        ),
        showDragHandle: true,
        dragHandleColor: Color(0xFFDADCE0),
        dragHandleSize: Size(40, 4),
      ),

      // Divider Theme
      dividerTheme: const DividerThemeData(
        color: Color(0xFFE8EAED),
        thickness: 1,
        space: 1,
      ),

      // Icon Theme
      iconTheme: const IconThemeData(
        color: Color(0xFF5F6368),
        size: 24,
      ),

      // Text Theme
      textTheme: _buildTextTheme(),
    );
  }

  // ============================================
  // DARK THEME
  // ============================================

  static ThemeData get dark {
    final base = ThemeData.dark(useMaterial3: true);

    return base.copyWith(
      // Brand Colors
      primaryColor: primaryLight,
      primaryColorLight: primaryColor,
      primaryColorDark: primaryLight,
      scaffoldBackgroundColor: const Color(0xFF1A1A2E),

      // Color Scheme
      colorScheme: const ColorScheme.dark(
        primary: primaryLight,
        onPrimary: Color(0xFF1A1A2E),
        primaryContainer: Color(0xFF0D47A1),
        onPrimaryContainer: Color(0xFFD3E3FD),
        secondary: secondaryColor,
        onSecondary: Color(0xFF1A1A2E),
        secondaryContainer: Color(0xFF004D40),
        onSecondaryContainer: Color(0xFFCEFAF8),
        tertiary: Color(0xFF64FFDA),
        error: Color(0xFFFF8A80),
        onError: Color(0xFF410002),
        errorContainer: Color(0xFF410002),
        onErrorContainer: Color(0xFFFFDAD6),
        surface: Color(0xFF1E1E1E),
        onSurface: Color(0xFFE8EAED),
        surfaceContainerHighest: Color(0xFF2D2D2D),
        onSurfaceVariant: Color(0xFF9AA0A6),
        outline: Color(0xFF333333),
        outlineVariant: Color(0xFF424242),
      ),

      // AppBar Theme
      appBarTheme: const AppBarTheme(
        elevation: 0,
        scrolledUnderElevation: 0,
        backgroundColor: Colors.transparent,
        foregroundColor: Color(0xFFE8EAED),
        centerTitle: false,
        titleSpacing: 20,
        titleTextStyle: TextStyle(
          fontSize: 22,
          fontWeight: FontWeight.w600,
          color: Color(0xFFE8EAED),
        ),
        systemOverlayStyle: SystemUiOverlayStyle(
          statusBarColor: Colors.transparent,
          statusBarIconBrightness: Brightness.light,
          statusBarBrightness: Brightness.dark,
        ),
      ),

      // Card Theme
      cardTheme: CardTheme(
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(24),
        ),
        color: const Color(0xFF1E1E1E),
        surfaceTintColor: const Color(0xFF1E1E1E),
      ),

      // Bottom Sheet Theme
      bottomSheetTheme: const BottomSheetThemeData(
        backgroundColor: Color(0xFF1E1E1E),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
        ),
        showDragHandle: true,
        dragHandleColor: Color(0xFF424242),
        dragHandleSize: Size(40, 4),
      ),

      // Text Theme
      textTheme: _buildTextTheme(isDark: true),
    );
  }

  // ============================================
  // TEXT THEME
  // ============================================

  static TextTheme _buildTextTheme({bool isDark = false}) {
    final Color textColor = isDark ? const Color(0xFFE8EAED) : const Color(0xFF1F1F1F);
    final Color textColorSecondary = isDark ? const Color(0xFF9AA0A6) : const Color(0xFF5F6368);
    final Color textColorTertiary = isDark ? const Color(0xFF6B7280) : const Color(0xFF9AA0A6);

    return TextTheme(
      // Display Styles
      displayLarge: TextStyle(
        fontSize: 57,
        fontWeight: FontWeight.w700,
        color: textColor,
        letterSpacing: -0.25,
      ),
      displayMedium: TextStyle(
        fontSize: 45,
        fontWeight: FontWeight.w700,
        color: textColor,
      ),
      displaySmall: TextStyle(
        fontSize: 36,
        fontWeight: FontWeight.w700,
        color: textColor,
      ),

      // Headline Styles
      headlineLarge: TextStyle(
        fontSize: 32,
        fontWeight: FontWeight.w600,
        color: textColor,
      ),
      headlineMedium: TextStyle(
        fontSize: 28,
        fontWeight: FontWeight.w600,
        color: textColor,
      ),
      headlineSmall: TextStyle(
        fontSize: 24,
        fontWeight: FontWeight.w600,
        color: textColor,
      ),

      // Title Styles
      titleLarge: TextStyle(
        fontSize: 22,
        fontWeight: FontWeight.w600,
        color: textColor,
      ),
      titleMedium: TextStyle(
        fontSize: 16,
        fontWeight: FontWeight.w600,
        color: textColor,
        letterSpacing: 0.15,
      ),
      titleSmall: TextStyle(
        fontSize: 14,
        fontWeight: FontWeight.w600,
        color: textColor,
        letterSpacing: 0.1,
      ),

      // Body Styles
      bodyLarge: TextStyle(
        fontSize: 16,
        fontWeight: FontWeight.w400,
        color: textColor,
        letterSpacing: 0.5,
      ),
      bodyMedium: TextStyle(
        fontSize: 14,
        fontWeight: FontWeight.w400,
        color: textColor,
        letterSpacing: 0.25,
      ),
      bodySmall: TextStyle(
        fontSize: 12,
        fontWeight: FontWeight.w400,
        color: textColorSecondary,
        letterSpacing: 0.4,
      ),

      // Label Styles
      labelLarge: TextStyle(
        fontSize: 14,
        fontWeight: FontWeight.w500,
        color: textColor,
        letterSpacing: 0.1,
      ),
      labelMedium: TextStyle(
        fontSize: 12,
        fontWeight: FontWeight.w500,
        color: textColorSecondary,
        letterSpacing: 0.5,
      ),
      labelSmall: TextStyle(
        fontSize: 11,
        fontWeight: FontWeight.w500,
        color: textColorTertiary,
        letterSpacing: 0.5,
      ),
    );
  }

  // ============================================
  // CUSTOM DECORATIONS
  // ============================================

  /// Premium card decoration
  static BoxDecoration cardDecoration({
    Color? color,
    bool isDark = false,
  }) {
    return BoxDecoration(
      color: color ?? (isDark ? const Color(0xFF1E1E1E) : Colors.white),
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
    );
  }

  /// Toggle button decoration
  static BoxDecoration toggleDecoration({
    required bool isActive,
    bool isDark = false,
  }) {
    return BoxDecoration(
      gradient: isActive
          ? const LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [primaryColor, secondaryColor],
            )
          : null,
      color: isActive ? null : (isDark ? const Color(0xFF2D2D2D) : const Color(0xFFE8EAED)),
      shape: BoxShape.circle,
      boxShadow: isActive
          ? [
              BoxShadow(
                color: primaryColor.withAlpha(100),
                blurRadius: 30,
                offset: const Offset(0, 10),
              ),
            ]
          : [
              BoxShadow(
                color: Colors.black.withAlpha(isDark ? 80 : 25),
                blurRadius: 20,
                offset: const Offset(0, 4),
              ),
            ],
    );
  }

  /// Status indicator decoration
  static BoxDecoration statusIndicatorDecoration({
    required bool isConnected,
  }) {
    return BoxDecoration(
      color: isConnected ? statusConnected : statusDisconnected,
      shape: BoxShape.circle,
      boxShadow: [
        BoxShadow(
          color: (isConnected ? statusConnected : statusDisconnected).withAlpha(128),
          blurRadius: 8,
          spreadRadius: 2,
        ),
      ],
    );
  }
}
