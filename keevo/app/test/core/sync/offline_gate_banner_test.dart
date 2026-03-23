import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/scaffold/offline_gate_banner.dart';
import 'package:keevo/core/sync/sync_gate_provider.dart';
import 'package:keevo/core/sync/sync_gate_state.dart';
import 'package:shared_preferences/shared_preferences.dart';

Widget _wrap(SyncGateState gate, int days, SharedPreferences prefs) {
  return ProviderScope(
    overrides: [
      syncGateStateProvider.overrideWithValue(gate),
      daysSinceLastSyncProvider.overrideWithValue(days),
      sharedPreferencesProvider.overrideWithValue(prefs),
    ],
    child: const MaterialApp(
      home: Scaffold(body: OfflineGateBanner()),
    ),
  );
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
      expect(decoration, const Color(0xFFFFF3BF),
          reason: 'Warning banner background must be light amber #FFF3BF');
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
  });
}
