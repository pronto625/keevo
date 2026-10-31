import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/scaffold/offline_gate_banner.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:keevo_core/sync/sync_gate_provider.dart';
import 'package:keevo_core/sync/sync_gate_state.dart';
import 'package:keevo_core/theme/app_theme.dart';
import 'package:shared_preferences/shared_preferences.dart';

Widget _wrap(SyncGateState gate, int days, SharedPreferences prefs,
    {ThemeData? theme}) {
  return ProviderScope(
    retry: noProviderRetry,
    overrides: [
      syncGateStateProvider.overrideWithValue(gate),
      daysSinceLastSyncProvider.overrideWithValue(days),
      sharedPreferencesProvider.overrideWithValue(prefs),
    ],
    child: MaterialApp(
      theme: theme,
      home: const Scaffold(body: OfflineGateBanner()),
    ),
  );
}

/// WCAG 2.x contrast ratio between two opaque colors.
double _contrast(Color a, Color b) {
  final la = a.computeLuminance();
  final lb = b.computeLuminance();
  final hi = la > lb ? la : lb;
  final lo = la > lb ? lb : la;
  return (hi + 0.05) / (lo + 0.05);
}

Future<void> _expectReadable(
  WidgetTester tester,
  ThemeData theme,
  SyncGateState gate,
  int days,
  String text,
) async {
  SharedPreferences.setMockInitialValues({});
  final prefs = await SharedPreferences.getInstance();
  await tester.pumpWidget(_wrap(gate, days, prefs, theme: theme));
  await tester.pumpAndSettle();

  final bg = tester
      .widget<Container>(find
          .ancestor(of: find.textContaining(text), matching: find.byType(Container))
          .first)
      .color!;
  final textColor =
      tester.widget<Text>(find.textContaining(text)).style!.color!;
  final iconColor = tester
      .widget<Icon>(find.byIcon(gate == SyncGateState.critical
          ? Icons.error_outline_rounded
          : Icons.warning_amber_rounded))
      .color!;

  expect(_contrast(textColor, bg), greaterThanOrEqualTo(4.5),
      reason: 'text $textColor on $bg (${theme.brightness}, $gate)');
  expect(_contrast(iconColor, bg), greaterThanOrEqualTo(4.5),
      reason: 'icon $iconColor on $bg (${theme.brightness}, $gate)');
}

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  group('OfflineGateBanner', () {
    testWidgets('banner_open_notShown', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(_wrap(SyncGateState.open, 0, prefs));
      await tester.pumpAndSettle();

      expect(find.textContaining('Synchronisation'), findsNothing);
    });

    testWidgets('banner_warning_amberBannerShown', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(_wrap(SyncGateState.warning, 5, prefs));
      await tester.pumpAndSettle();

      expect(find.textContaining('Synchronisation requise'), findsOneWidget);
      // Dismiss button present for warning state
      expect(find.byIcon(Icons.close), findsOneWidget);
      // Background should be light amber (not aggressive yellow)
      final container = tester.widget<Container>(
        find.ancestor(
          of: find.textContaining('Synchronisation requise'),
          matching: find.byType(Container),
        ).first,
      );
      final decoration = container.color;
      // v1s-16-3: design-system token `warningContainer` (≈ spec "#FCC419 at
      // 15%"); the former #FFF3BF matched neither the spec nor a token.
      expect(decoration, const Color(0xFFFFF9DB),
          reason: 'Warning banner background must be warningContainer #FFF9DB');
    });

    testWidgets('banner_critical_redBannerShown_notDismissable', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(_wrap(SyncGateState.critical, 6, prefs));
      await tester.pumpAndSettle();

      expect(find.textContaining('Derni\u00e8re chance'), findsOneWidget);
      // NO dismiss button for critical
      expect(find.byIcon(Icons.close), findsNothing);
      // Background should be light red
      final container = tester.widget<Container>(
        find.ancestor(
          of: find.textContaining('Derni\u00e8re chance'),
          matching: find.byType(Container),
        ).first,
      );
      expect(container.color, const Color(0xFFFFE3E3),
          reason: 'Critical banner background must be light red #FFE3E3');
    });

    testWidgets('banner_blocked_noBannerShown', (tester) async {
      // blocked state: modal takes over, banner is hidden
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(_wrap(SyncGateState.blocked, 7, prefs));
      await tester.pumpAndSettle();

      expect(find.textContaining('Synchronisation'), findsNothing);
      expect(find.textContaining('Derni\u00e8re chance'), findsNothing);
    });

    testWidgets('banner_warning_dismiss_hiddenForToday', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(_wrap(SyncGateState.warning, 5, prefs));
      await tester.pumpAndSettle();

      // Banner is visible initially
      expect(find.textContaining('Synchronisation requise'), findsOneWidget);

      // Tap dismiss
      await tester.tap(find.byIcon(Icons.close));
      await tester.pumpAndSettle();

      // Banner is now hidden
      expect(find.textContaining('Synchronisation requise'), findsNothing);

      // SharedPreferences now has today's date
      final todayKey = 'offline_gate_banner_dismissed_date';
      expect(prefs.getString(todayKey), isNotNull);
    });

    // v1s-16-13: the dismissed state is read from prefs at start.
    String day(DateTime d) =>
        '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

    testWidgets('banner_warning_dismissedToday_staysHidden', (tester) async {
      SharedPreferences.setMockInitialValues(
          {'offline_gate_banner_dismissed_date': day(DateTime.now())});
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(_wrap(SyncGateState.warning, 5, prefs));
      await tester.pumpAndSettle();

      expect(find.textContaining('Synchronisation requise'), findsNothing);
    });

    testWidgets('banner_warning_dismissedYesterday_showsAgain', (tester) async {
      SharedPreferences.setMockInitialValues({
        'offline_gate_banner_dismissed_date':
            day(DateTime.now().subtract(const Duration(days: 1))),
      });
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(_wrap(SyncGateState.warning, 5, prefs));
      await tester.pumpAndSettle();

      expect(find.textContaining('Synchronisation requise'), findsOneWidget);
    });

    // One fresh widget tree per scenario (no ProviderScope reuse).
    testWidgets('shouldRenderReadableOfflineBannerInLightMode_warning',
        (tester) async {
      await _expectReadable(tester, AppTheme.light(), SyncGateState.warning, 5,
          'Synchronisation requise');
    });

    testWidgets('shouldRenderReadableOfflineBannerInLightMode_critical',
        (tester) async {
      await _expectReadable(tester, AppTheme.light(), SyncGateState.critical, 6,
          'Derni\u00e8re chance');
    });

    testWidgets('shouldRenderReadableOfflineBannerInDarkMode_warning',
        (tester) async {
      await _expectReadable(tester, AppTheme.dark(), SyncGateState.warning, 5,
          'Synchronisation requise');
    });

    testWidgets('shouldRenderReadableOfflineBannerInDarkMode_critical',
        (tester) async {
      await _expectReadable(tester, AppTheme.dark(), SyncGateState.critical, 6,
          'Derni\u00e8re chance');
    });
  });
}
