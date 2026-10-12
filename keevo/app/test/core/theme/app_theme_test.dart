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
      // v1s-16-17 — UX-spec dark error with a dark foreground.
      expect(cs.error, const Color(0xFFFF6B6B));
      expect(cs.onError, AppTheme.onErrorDark);
      expect(cs.onError, const Color(0xFF0D1B2A));
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

    // v1s-16-17 — feedback fills: every fill/foreground pair is AA in
    // light and dark (text ≥ 4.5:1, icons ≥ 3:1).
    group('feedback fill pairs (v1s-16-17)', () {
      double contrast(Color a, Color b) {
        final la = a.computeLuminance();
        final lb = b.computeLuminance();
        return ((la > lb ? la : lb) + 0.05) / ((la > lb ? lb : la) + 0.05);
      }

      const aa = 4.5;

      test('fixed tone fills are AA under onBrand', () {
        for (final fill in [
          AppTheme.successStrong,
          AppTheme.errorStrong,
          AppTheme.warningStrong,
          AppTheme.primary,
          AppTheme.violetDeep,
          AppTheme.snackBarNeutral,
          AppTheme.snackBarNeutralDark,
        ]) {
          expect(contrast(AppTheme.onBrand, fill), greaterThanOrEqualTo(aa),
              reason: '$fill');
        }
        expect(AppTheme.errorStrong, const Color(0xFFC92A2A));
      });

      test('onWarning is AA on the warning fill', () {
        expect(contrast(AppTheme.onWarning, AppTheme.warning),
            greaterThanOrEqualTo(aa));
      });

      test('dark error pair is AA', () {
        final cs = AppTheme.dark().colorScheme;
        expect(contrast(cs.onError, cs.error), greaterThanOrEqualTo(aa));
      });

      test('light error token unchanged (indicator role)', () {
        final cs = AppTheme.light().colorScheme;
        expect(cs.error, const Color(0xFFFA5252));
        expect(cs.onError, AppTheme.onError);
      });

      for (final theme in [AppTheme.light(), AppTheme.dark()]) {
        final cs = theme.colorScheme;
        final b = cs.brightness;

        test('snackBarTheme: fixed neutral fill + onBrand content ($b)', () {
          final sb = theme.snackBarTheme;
          expect(
              sb.backgroundColor,
              b == Brightness.dark
                  ? AppTheme.snackBarNeutralDark
                  : AppTheme.snackBarNeutral);
          expect(sb.contentTextStyle?.color, AppTheme.onBrand);
          expect(sb.actionTextColor, AppTheme.onBrand);
          expect(sb.closeIconColor, AppTheme.onBrand);
          for (final fg in [
            sb.contentTextStyle!.color!,
            sb.actionTextColor!,
            sb.closeIconColor!,
          ]) {
            expect(contrast(fg, sb.backgroundColor!), greaterThanOrEqualTo(aa));
          }
          // Built from the Inter text theme, not a bare TextStyle.
          expect(sb.contentTextStyle?.fontFamily,
              theme.textTheme.bodyMedium?.fontFamily);
          expect(sb.contentTextStyle?.fontFamily, contains('Inter'));
          expect(sb.contentTextStyle?.fontSize,
              theme.textTheme.bodyMedium?.fontSize);
        });

        test('speed-dial closed pair is AA ($b)', () {
          expect(contrast(cs.surface, cs.onSurfaceVariant),
              greaterThanOrEqualTo(aa));
        });

        test('disabled CTA pair is AA ($b)', () {
          expect(contrast(cs.onSurfaceVariant, cs.surfaceContainerHighest),
              greaterThanOrEqualTo(aa));
        });
      }
    });

    test('softTint follows brightness', () {
      expect(AppTheme.softTint(Brightness.light), AppTheme.primarySoftTint);
      expect(AppTheme.softTint(Brightness.dark), AppTheme.primarySoftTintDark);
    });
  });
}
