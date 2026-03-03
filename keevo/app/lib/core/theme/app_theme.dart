import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

/// Keevo Material 3 — Indigo Sky palette
///
/// colorPrimary:  #3B5BDB
/// colorSuccess:  #51CF66
/// colorWarning:  #FCC419
/// colorError:    #FA5252
/// darkSurface:   #0D1B2A
class AppTheme {
  AppTheme._();

  static const Color primary = Color(0xFF3B5BDB);
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
      primary: primary,
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
