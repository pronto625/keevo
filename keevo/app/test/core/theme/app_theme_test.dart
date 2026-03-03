import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/theme/app_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  GoogleFonts.config.allowRuntimeFetching = false;

  group('AppTheme', () {
    test('light theme has correct primary color', () {
      final theme = AppTheme.light();
      expect(
        theme.colorScheme.primary,
        const Color(0xFF3B5BDB),
      );
    });

    test('dark theme has correct surface color', () {
      final theme = AppTheme.dark();
      expect(
        theme.colorScheme.surface,
        const Color(0xFF0D1B2A),
      );
    });

    test('light theme uses Material 3', () {
      final theme = AppTheme.light();
      expect(theme.useMaterial3, isTrue);
    });

    test('dark theme uses Material 3', () {
      final theme = AppTheme.dark();
      expect(theme.useMaterial3, isTrue);
    });

    test('error color is correct in light theme', () {
      final theme = AppTheme.light();
      expect(theme.colorScheme.error, const Color(0xFFFA5252));
    });
  });
}
