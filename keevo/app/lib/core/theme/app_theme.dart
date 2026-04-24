import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

/// Keevo Material 3 — Indigo Sky palette (UX Design Spec)
///
/// colorPrimary:    #3B5BDB  (indigo royal)    — boutons, nav active
/// primaryGradient: #3B5BDB → #4DABF7          — AppBar, headers
/// colorSecondary:  #FF6B6B  (corail chaud)    — accent CTA, prix
/// colorSuccess:    #51CF66  (vert lime)        — confirmations, sync OK
/// colorWarning:    #FCC419  (ambre doré)       — stock bas, brouillons
/// colorError:      #FA5252  (rouge rubis)      — ruptures, erreurs
/// darkSurface:     #0D1B2A  (navy nuit)
class AppTheme {
  AppTheme._();

  // ── Core palette ──────────────────────────────────────────────────────
  static const Color primary = Color(0xFF3B5BDB);
  static const Color primaryGradientEnd = Color(0xFF4DABF7);
  static const Color secondary = Color(0xFFFF6B6B);
  static const Color success = Color(0xFF51CF66);
  /// Couleur de texte/icône accessible sur fond [success] (vert lime).
  static const Color onSuccess = Color(0xFF1A4731);
  static const Color warning = Color(0xFFFCC419);
  /// Couleur de texte/icône accessible sur fond [warning] (ambre).
  static const Color onWarning = Color(0xFF4A2C00);
  static const Color errorColor = Color(0xFFFA5252);
  /// Couleur de texte/icône accessible sur fond [errorColor] (rouge rubis).
  static const Color onError = Color(0xFFFFFFFF);
  static const Color darkSurface = Color(0xFF0D1B2A);

  // ── Neutral greys (semantic) ──────────────────────────────────────────
  static const Color grey600 = Color(0xFF868E96);
  static const Color grey500 = Color(0xFFADB5BD);
  static const Color grey200 = Color(0xFFE9ECEF);
  static const Color grey100 = Color(0xFFF1F3F5);
  static const Color grey50 = Color(0xFFF8F9FA);

  // ── Scaffold backgrounds ──────────────────────────────────────────────
  static const Color scaffoldLight = Color(0xFFF8F9FA);
  // dark mode uses colorScheme.surface (darkSurface)

  // ── Gradient builders ─────────────────────────────────────────────────
  static LinearGradient get primaryGradient => const LinearGradient(
        colors: [primary, primaryGradientEnd],
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
      );

  static LinearGradient get primaryGradientDark => const LinearGradient(
        colors: [Color(0xFF1A365D), Color(0xFF0D1B2A)],
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
      );

  static LinearGradient get successGradient => const LinearGradient(
        colors: [success, Color(0xFF37B24D)],
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
      );

  // ── Settings icon color pairs (light mode) ────────────────────────────
  // Grouped by hue family so the settings page looks intentional.
  static const Color iconBlueBg = Color(0xFFD0EBFF);
  static const Color iconBlue = Color(0xFF339AF0);
  static const Color iconTealBg = Color(0xFFD3F9D8);
  static const Color iconTeal = Color(0xFF0CA678);
  static const Color iconAmberBg = Color(0xFFFFF9DB);
  static const Color iconAmber = Color(0xFFF59F00);
  static const Color iconPurpleBg = Color(0xFFF3E5F5);
  static const Color iconPurple = Color(0xFF9B59B6);
  static const Color iconIndigoBg = Color(0xFFF0E6FF);
  static const Color iconIndigo = Color(0xFF6F42C1);
  static const Color iconOrangeBg = Color(0xFFFFF7ED);
  static const Color iconOrange = Color(0xFFEA580C);
  static const Color iconGreyBg = Color(0xFFF1F3F5);
  static const Color iconGrey = Color(0xFF868E96);
  static const Color iconCyanBg = Color(0xFFE0F2FE);
  static const Color iconCyan = Color(0xFF0EA5E9);
  static const Color iconRedBg = Color(0xFFFFE3E3);

  // ── Shared radii ──────────────────────────────────────────────────────
  static const double radiusS = 8;
  static const double radiusM = 12;
  static const double radiusL = 16;
  static const double radiusXL = 24;

  // ── Helpers ───────────────────────────────────────────────────────────

  /// Semantic status color based on label.
  static Color statusColor(String status) => switch (status) {
        'SUCCESS' || 'OK' => success,
        'FAILED' => errorColor,
        'WARN' => warning,
        _ => grey600,
      };

  static TextTheme _interTextTheme(TextTheme base) =>
      GoogleFonts.interTextTheme(base);

  // ── Component themes (shared) ─────────────────────────────────────────

  static InputDecorationTheme _inputDecoration(ColorScheme cs) =>
      InputDecorationTheme(
        filled: true,
        fillColor: cs.surfaceContainerHighest.withAlpha(80),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(radiusM),
          borderSide: BorderSide.none,
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(radiusM),
          borderSide: BorderSide(color: cs.outlineVariant.withAlpha(100)),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(radiusM),
          borderSide: BorderSide(color: cs.primary, width: 1.5),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(radiusM),
          borderSide: BorderSide(color: cs.error),
        ),
        focusedErrorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(radiusM),
          borderSide: BorderSide(color: cs.error, width: 1.5),
        ),
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        hintStyle: TextStyle(color: cs.onSurface.withAlpha(120)),
      );

  static CardThemeData _cardTheme(ColorScheme cs) => CardThemeData(
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radiusL),
        ),
        color: cs.surface,
        surfaceTintColor: Colors.transparent,
      );

  static AppBarTheme _appBarTheme(ColorScheme cs) => AppBarTheme(
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
        backgroundColor: Colors.transparent,
        foregroundColor: cs.onSurface,
        titleTextStyle: TextStyle(
          fontSize: 18,
          fontWeight: FontWeight.w600,
          color: cs.onSurface,
        ),
      );

  static NavigationBarThemeData _navBarTheme(ColorScheme cs) =>
      NavigationBarThemeData(
        elevation: 2,
        indicatorColor: cs.primaryContainer,
        backgroundColor: cs.surface,
        labelTextStyle: WidgetStateProperty.resolveWith((states) {
          final selected = states.contains(WidgetState.selected);
          return TextStyle(
            fontSize: 11,
            fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
            color: selected ? cs.primary : cs.onSurfaceVariant,
          );
        }),
      );

  static DialogThemeData _dialogTheme(ColorScheme cs) => DialogThemeData(
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radiusXL),
        ),
        backgroundColor: cs.surface,
      );

  static BottomSheetThemeData _bottomSheetTheme(ColorScheme cs) =>
      BottomSheetThemeData(
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
        ),
        backgroundColor: cs.surface,
        showDragHandle: true,
      );

  static SnackBarThemeData _snackBarTheme(ColorScheme cs) => SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radiusM),
        ),
      );

  // ── Light theme ───────────────────────────────────────────────────────

  static ThemeData light() {
    final colorScheme = ColorScheme.fromSeed(
      seedColor: primary,
      brightness: Brightness.light,
    ).copyWith(
      primary: primary,
      secondary: secondary,
      onSecondary: Colors.white,
      error: errorColor,
    );
    final base = ThemeData(
      useMaterial3: true,
      colorScheme: colorScheme,
      scaffoldBackgroundColor: scaffoldLight,
    );
    return base.copyWith(
      textTheme: _interTextTheme(base.textTheme),
      primaryTextTheme: _interTextTheme(base.primaryTextTheme),
      inputDecorationTheme: _inputDecoration(colorScheme),
      cardTheme: _cardTheme(colorScheme),
      appBarTheme: _appBarTheme(colorScheme),
      navigationBarTheme: _navBarTheme(colorScheme),
      dialogTheme: _dialogTheme(colorScheme),
      bottomSheetTheme: _bottomSheetTheme(colorScheme),
      snackBarTheme: _snackBarTheme(colorScheme),
      dividerTheme: DividerThemeData(
        color: colorScheme.outlineVariant.withAlpha(80),
        thickness: 0.5,
        space: 1,
      ),
    );
  }

  // ── Dark theme ────────────────────────────────────────────────────────

  static ThemeData dark() {
    final colorScheme = ColorScheme.fromSeed(
      seedColor: primary,
      brightness: Brightness.dark,
    ).copyWith(
      primary: const Color(0xFF4DABF7), // bleu ciel en dark mode (UX spec)
      secondary: const Color(0xFFFF8787), // corail clair dark mode (UX spec)
      onSecondary: Colors.black,
      error: errorColor,
      surface: darkSurface,
    );
    final base = ThemeData(
      useMaterial3: true,
      colorScheme: colorScheme,
    );
    return base.copyWith(
      textTheme: _interTextTheme(base.textTheme),
      primaryTextTheme: _interTextTheme(base.primaryTextTheme),
      inputDecorationTheme: _inputDecoration(colorScheme),
      cardTheme: _cardTheme(colorScheme),
      appBarTheme: _appBarTheme(colorScheme),
      navigationBarTheme: _navBarTheme(colorScheme),
      dialogTheme: _dialogTheme(colorScheme),
      bottomSheetTheme: _bottomSheetTheme(colorScheme),
      snackBarTheme: _snackBarTheme(colorScheme),
      dividerTheme: DividerThemeData(
        color: colorScheme.outlineVariant.withAlpha(80),
        thickness: 0.5,
        space: 1,
      ),
    );
  }
}

/// Extension to access AppTheme semantic colors relative to the current
/// brightness without importing AppTheme constants everywhere.
extension KeevoColors on ColorScheme {
  bool get _isDark => brightness == Brightness.dark;

  Color get success => _isDark ? const Color(0xFF69DB7C) : AppTheme.success;
  Color get warning => _isDark ? const Color(0xFFFFD43B) : AppTheme.warning;
  Color get successContainer =>
      _isDark ? const Color(0xFF1B4332) : const Color(0xFFD3F9D8);
  Color get warningContainer =>
      _isDark ? const Color(0xFF462F00) : const Color(0xFFFFF9DB);
  Color get errorContainer_ =>
      _isDark ? const Color(0xFF5C1A1A) : const Color(0xFFFFE3E3);

  /// Light scaffold‐grey that adapts for dark mode.
  Color get scaffoldBg => _isDark ? surface : AppTheme.scaffoldLight;

  /// Card/container background that adapts cleanly.
  Color get cardBg =>
      _isDark ? surfaceContainerHigh : Colors.white;

  /// Muted foreground (secondary text, icons).
  Color get muted => onSurface.withAlpha(150);

  /// Very muted foreground (tertiary labels, hints).
  Color get dimmed => onSurface.withAlpha(100);
}
