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

  static const Color primary = Color(0xFF3B5BDB);
  static const Color primaryGradientEnd = Color(0xFF4DABF7);
  static const Color secondary = Color(0xFFFF6B6B);
  static const Color success = Color(0xFF51CF66);
  static const Color warning = Color(0xFFFCC419);
  static const Color errorColor = Color(0xFFFA5252);
  static const Color darkSurface = Color(0xFF0D1B2A);

  static TextTheme _interTextTheme(TextTheme base) =>
      GoogleFonts.interTextTheme(base);

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
    );
    return base.copyWith(
      textTheme: _interTextTheme(base.textTheme),
      primaryTextTheme: _interTextTheme(base.primaryTextTheme),
    );
  }

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
    );
  }
}
