import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/sync/sync_gate_provider.dart';
import 'package:keevo/core/sync/sync_gate_state.dart';
import 'package:keevo/core/sync/sync_status.dart';
import 'package:keevo/core/sync/sync_status_provider.dart';
import 'package:keevo/core/sync/sync_trigger_notifier.dart';
import 'package:keevo/features/sync_indicator/presentation/widget/sync_indicator.dart';
import 'package:shared_preferences/shared_preferences.dart';

// ── Fake SyncTriggerNotifier ──────────────────────────────────────────────────

class _FakeSyncTriggerNotifier extends SyncTriggerNotifier {
  @override
  SyncTriggerState build() => const SyncTriggerState.idle();

  @override
  Future<void> triggerSync() async {}

  @override
  Future<void> triggerPush() async {}
}

class _FakeSyncingTriggerNotifier extends SyncTriggerNotifier {
  @override
  SyncTriggerState build() => const SyncTriggerState.syncing();

  @override
  Future<void> triggerSync() async {}

  @override
  Future<void> triggerPush() async {}
}

// ── Helpers ───────────────────────────────────────────────────────────────────

Widget _wrapIndicator({
  required SyncGateState gateState,
  int days = 0,
  required SharedPreferences prefs,
  bool isSyncing = false,
}) {
  return ProviderScope(
    overrides: [
      syncGateStateProvider.overrideWithValue(gateState),
      daysSinceLastSyncProvider.overrideWithValue(days),
      daysOfflineProvider.overrideWithValue(0),
      syncStatusProvider.overrideWith((ref) => Stream.value(SyncStatus.online)),
      connectivityStreamProvider
          .overrideWith((ref) => Stream.value([ConnectivityResult.wifi])),
      syncTriggerNotifierProvider.overrideWith(() => isSyncing
          ? _FakeSyncingTriggerNotifier()
          : _FakeSyncTriggerNotifier()),
      sharedPreferencesProvider.overrideWithValue(prefs),
    ],
    child: MaterialApp(
      home: Scaffold(
        appBar: PreferredSize(
          preferredSize: const Size.fromHeight(56),
          child: AppBar(actions: const [SyncIndicator()]),
        ),
        body: const SizedBox.shrink(),
      ),
    ),
  );
}

// ── Tests ─────────────────────────────────────────────────────────────────────

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  group('SyncIndicator gate state', () {
    testWidgets('indicator_blocked_showsAccesLimite', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(
          _wrapIndicator(gateState: SyncGateState.blocked, days: 7, prefs: prefs));
      await tester.pumpAndSettle();

      // Blocked state label — use textContaining to be emoji-agnostic
      expect(find.textContaining('Accès limité — Sync requise'),
          findsOneWidget,
          reason: 'Blocked indicator must show access-limited label');
    });

    testWidgets('indicator_blocked_dot_isRed', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(
          _wrapIndicator(gateState: SyncGateState.blocked, days: 7, prefs: prefs));
      await tester.pumpAndSettle();

      final dot = tester.widget<Container>(find.byKey(const Key('sync_dot')));
      expect((dot.decoration as BoxDecoration).color, const Color(0xFFFA5252),
          reason: 'Blocked state dot must be red #FA5252');
    });

    testWidgets('indicator_blocked_tapOpensModal', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(
          _wrapIndicator(gateState: SyncGateState.blocked, days: 7, prefs: prefs));
      await tester.pumpAndSettle();

      // Tap the blocked indicator using textContaining
      await tester.tap(find.textContaining('Accès limité — Sync requise'));
      await tester.pumpAndSettle();

      // SyncRequiredModal should have opened (dialog with sync button visible)
      expect(find.text('Synchroniser maintenant'), findsOneWidget,
          reason: 'Tapping blocked indicator must open SyncRequiredModal');
    });

    testWidgets('indicator_open_showsNormalStatus', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(
          _wrapIndicator(gateState: SyncGateState.open, days: 0, prefs: prefs));
      await tester.pumpAndSettle();

      // Normal label — NOT the blocked label
      expect(find.textContaining('Accès limité — Sync requise'),
          findsNothing,
          reason: 'Open gate must not show blocked label');
      expect(find.text('En ligne'), findsOneWidget,
          reason: 'Open gate with online status must show En ligne');
    });

    // ── Story 16.5: online-stale indicator ────────────────────────────

    testWidgets('shouldShowWarningWhenOnlineAndStaleDay5', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(
          _wrapIndicator(gateState: SyncGateState.warning, days: 5, prefs: prefs));
      await tester.pumpAndSettle();

      // Label must show "En ligne (sync J-5)"
      expect(find.text('En ligne (sync J-5)'), findsOneWidget,
          reason: 'Online+warning at day 5 must show "En ligne (sync J-5)"');

      // Dot must be amber #FCC419
      final dot = tester.widget<Container>(find.byKey(const Key('sync_dot')));
      expect((dot.decoration as BoxDecoration).color, const Color(0xFFFCC419),
          reason: 'Online+warning dot must be amber #FCC419');
    });

    testWidgets('shouldShowCriticalDay6', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(
          _wrapIndicator(gateState: SyncGateState.critical, days: 6, prefs: prefs));
      await tester.pumpAndSettle();

      // Label must contain "Sync urgente"
      expect(find.textContaining('Sync urgente'), findsOneWidget,
          reason: 'Online+critical at day 6 must show "⚠ Sync urgente"');

      // Dot must be red #FA5252
      final dot = tester.widget<Container>(find.byKey(const Key('sync_dot')));
      expect((dot.decoration as BoxDecoration).color, const Color(0xFFFA5252),
          reason: 'Online+critical dot must be red #FA5252');
    });

    testWidgets('shouldShowSyncingSpinnerEvenWhenGateIsCritical',
        (tester) async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(_wrapIndicator(
          gateState: SyncGateState.critical,
          days: 6,
          prefs: prefs,
          isSyncing: true));
      await tester.pump(const Duration(milliseconds: 100));

      // A manual sync in-flight must win over the online-stale critical label.
      expect(find.text('Synchronisation...'), findsOneWidget,
          reason:
              'SyncTriggerSyncing must show the syncing label regardless of gate state');
      expect(find.textContaining('Sync urgente'), findsNothing,
          reason:
              'SyncTriggerSyncing must override the online-stale critical label');
      expect(find.byType(CircularProgressIndicator), findsOneWidget,
          reason: 'Manual sync in-flight must show the spinner');
    });
  });
}
