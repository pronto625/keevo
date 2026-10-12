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

    // v1s-16-3 — UX spec token table, dark column.
    test('dark theme carries the UX-spec dark tokens', () {
      final cs = AppTheme.dark().colorScheme;
      expect(cs.primary, const Color(0xFF4DABF7));
      expect(cs.onPrimary, const Color(0xFF0D1B2A));
      expect(cs.primaryContainer, const Color(0xFF1B3A5C));
      expect(cs.onSurface, const Color(0xFFF0F4F8));
      expect(cs.onSurfaceVariant, const Color(0xFFADB5BD));
      expect(cs.surfaceContainerHighest, const Color(0xFF1B2838));
      expect(cs.onError, AppTheme.onError);
    });

    test('light theme onPrimary is white and onError explicit', () {
      final cs = AppTheme.light().colorScheme;
      expect(cs.onPrimary, const Color(0xFFFFFFFF));
      expect(cs.onError, AppTheme.onError);
    });

    // v1s-16-3 — KeevoColors pairs: brightness branch + WCAG AA (4.5:1).
    group('KeevoColors tokens', () {
      double contrast(Color a, Color b) {
        final la = a.computeLuminance();
        final lb = b.computeLuminance();
        return ((la > lb ? la : lb) + 0.05) / ((la > lb ? lb : la) + 0.05);
      }

      final light = AppTheme.light().colorScheme;
      final dark = AppTheme.dark().colorScheme;

      test('strong tokens follow brightness', () {
        expect(light.successStrong, AppTheme.successStrong);
        expect(light.warningStrong, AppTheme.warningStrong);
        expect(dark.successStrong, dark.success);
        expect(dark.warningStrong, dark.warning);
      });

      for (final cs in [light, dark]) {
        test('banner on-container pairs are AA (${cs.brightness})', () {
          expect(contrast(cs.onWarningContainer, cs.warningContainer),
              greaterThanOrEqualTo(4.5));
          expect(contrast(cs.onErrorContainer_, cs.errorContainer_),
              greaterThanOrEqualTo(4.5));
        });

        test('strong tokens are AA on the surface (${cs.brightness})', () {
          expect(contrast(cs.successStrong, cs.surface),
              greaterThanOrEqualTo(4.5));
          expect(contrast(cs.warningStrong, cs.surface),
              greaterThanOrEqualTo(4.5));
        });

        test('avatar initials are AA on their 15% tint (${cs.brightness})',
            () {
          for (final accent in AppTheme.avatarPalette) {
            final tint =
                Color.alphaBlend(accent.withValues(alpha: 0.15), cs.surface);
            final fg = AppTheme.avatarInitials(accent, cs.brightness);
            expect(contrast(fg, tint), greaterThanOrEqualTo(4.5),
                reason: '$accent (${cs.brightness})');
          }
        });
      }

      test('onBrand is AA on the successStrong fill', () {
        expect(contrast(AppTheme.onBrand, AppTheme.successStrong),
            greaterThanOrEqualTo(4.5));
      });
    });

    test('softTint follows brightness', () {
      expect(AppTheme.softTint(Brightness.light), AppTheme.primarySoftTint);
      expect(AppTheme.softTint(Brightness.dark), AppTheme.primarySoftTintDark);
    });
  });
}
