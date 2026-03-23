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
import 'package:keevo/features/sync_indicator/presentation/widget/sync_required_modal.dart';
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

// ── Helpers ───────────────────────────────────────────────────────────────────

Widget _wrapIndicator({
  required SyncGateState gateState,
  int days = 0,
  required SharedPreferences prefs,
}) {
  return ProviderScope(
    overrides: [
      syncGateStateProvider.overrideWithValue(gateState),
      daysSinceLastSyncProvider.overrideWithValue(days),
      daysOfflineProvider.overrideWithValue(0),
      syncStatusProvider.overrideWith((ref) => Stream.value(SyncStatus.online)),
      connectivityStreamProvider
          .overrideWith((ref) => Stream.value([ConnectivityResult.wifi])),
      syncTriggerNotifierProvider
          .overrideWith(() => _FakeSyncTriggerNotifier()),
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
  });
}
