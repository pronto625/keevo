import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/sync/sync_gate_provider.dart';
import 'package:keevo/core/sync/sync_gate_state.dart';
import 'package:keevo/core/sync/sync_status_provider.dart';
import 'package:keevo/core/sync/sync_trigger_notifier.dart';
import 'package:keevo/features/sync_indicator/presentation/widget/sync_required_modal.dart';
import 'package:shared_preferences/shared_preferences.dart';

// ── Fake SyncTriggerNotifier ──────────────────────────────────────────────────

class _FakeSyncTriggerNotifier extends SyncTriggerNotifier {
  int syncCallCount = 0;
  bool shouldFail = false;
  Completer<void>? syncCompleter;

  @override
  SyncTriggerState build() => const SyncTriggerState.idle();

  @override
  Future<void> triggerSync() async {
    syncCallCount++;
    if (shouldFail) throw Exception('Sync failed');
    if (syncCompleter != null) await syncCompleter!.future;
  }

  @override
  Future<void> triggerPush() async {}
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/// Builds a widget that can open SyncRequiredModal on button tap.
Widget _wrapButton({
  List<ConnectivityResult> connectivity = const [ConnectivityResult.wifi],
  SyncGateState gateState = SyncGateState.blocked,
  _FakeSyncTriggerNotifier? notifier,
  required SharedPreferences prefs,
}) {
  final fake = notifier ?? _FakeSyncTriggerNotifier();
  return ProviderScope(
    overrides: [
      syncGateStateProvider.overrideWithValue(gateState),
      connectivityStreamProvider.overrideWith(
          (ref) => Stream.value(connectivity)),
      syncTriggerNotifierProvider.overrideWith(() => fake),
      sharedPreferencesProvider.overrideWithValue(prefs),
    ],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (ctx) => ElevatedButton(
            onPressed: () => SyncRequiredModal.show(ctx),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  );
}

Future<void> _openModal(WidgetTester tester) async {
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
}

// ── Tests ─────────────────────────────────────────────────────────────────────

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  group('SyncRequiredModal', () {
    testWidgets('modal_offline_primaryButtonDisabled', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(_wrapButton(
        connectivity: [ConnectivityResult.none],
        prefs: prefs,
      ));
      await _openModal(tester);

      final btn = tester.widget<FilledButton>(find.byType(FilledButton));
      expect(btn.onPressed, isNull,
          reason: 'Primary button must be disabled when offline');
    });

    testWidgets('modal_online_primaryButtonEnabled', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(_wrapButton(
        connectivity: [ConnectivityResult.wifi],
        prefs: prefs,
      ));
      await _openModal(tester);

      final btn = tester.widget<FilledButton>(find.byType(FilledButton));
      expect(btn.onPressed, isNotNull,
          reason: 'Primary button must be enabled when online');
    });

    testWidgets('modal_tapContinuerLectureSeule_dismissesModal', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(_wrapButton(
        connectivity: [ConnectivityResult.wifi],
        prefs: prefs,
      ));
      await _openModal(tester);

      expect(find.text('Continuer en lecture seule'), findsOneWidget);

      await tester.tap(find.text('Continuer en lecture seule'));
      await tester.pumpAndSettle();

      expect(find.text('Continuer en lecture seule'), findsNothing,
          reason: 'Modal must be dismissed after tapping secondary button');
    });

    testWidgets('modal_syncing_showsProgressIndicator', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();
      // Fake notifier with a Completer that blocks triggerSync indefinitely
      final completer = Completer<void>();
      final fake = _FakeSyncTriggerNotifier()..syncCompleter = completer;

      await tester.pumpWidget(_wrapButton(
        connectivity: [ConnectivityResult.wifi],
        prefs: prefs,
        notifier: fake,
      ));
      await _openModal(tester);

      // Tap sync button
      await tester.tap(find.text('Synchroniser maintenant'));
      // One frame only — triggerSync is blocked on the Completer, _isSyncing stays true
      await tester.pump();

      expect(find.byType(CircularProgressIndicator), findsOneWidget,
          reason: 'Progress indicator must be shown while syncing');

      // Unblock so the test cleans up properly
      completer.complete();
      await tester.pumpAndSettle();
    });

    testWidgets('modal_syncError_showsRetryButton', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();
      final fake = _FakeSyncTriggerNotifier()..shouldFail = true;

      await tester.pumpWidget(_wrapButton(
        connectivity: [ConnectivityResult.wifi],
        prefs: prefs,
        notifier: fake,
      ));
      await _openModal(tester);

      await tester.tap(find.text('Synchroniser maintenant'));
      await tester.pumpAndSettle();

      // After error, button shows 'R\u00e9essayer'
      expect(find.text('R\u00e9essayer'), findsOneWidget,
          reason: 'Primary button must show R\u00e9essayer after sync failure');
      // Error message shown
      expect(find.textContaining('La synchronisation a \u00e9chou\u00e9'), findsOneWidget);
    });

    testWidgets('modal_syncSuccess_dismissesAndShowsSnackBar', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();

      // Use a dynamic gate provider so we can trigger the open transition
      final gateController =
          StateProvider<SyncGateState>((ref) => SyncGateState.blocked);

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            syncGateStateProvider
                .overrideWith((ref) => ref.watch(gateController)),
            connectivityStreamProvider
                .overrideWith((ref) => Stream.value([ConnectivityResult.wifi])),
            syncTriggerNotifierProvider
                .overrideWith(() => _FakeSyncTriggerNotifier()),
            sharedPreferencesProvider.overrideWithValue(prefs),
          ],
          child: MaterialApp(
            home: Scaffold(
              body: Builder(
                builder: (ctx) => ElevatedButton(
                  onPressed: () => SyncRequiredModal.show(ctx),
                  child: const Text('open'),
                ),
              ),
            ),
          ),
        ),
      );

      await _openModal(tester);
      expect(find.text('Synchroniser maintenant'), findsOneWidget);

      // Simulate successful sync: gate transitions to open
      // Use ElevatedButton element (inside ProviderScope) to get the container
      final container = ProviderScope.containerOf(
          tester.element(find.byType(ElevatedButton)));
      container.read(gateController.notifier).state = SyncGateState.open;
      await tester.pumpAndSettle();

      // Modal dismissed
      expect(find.text('Synchroniser maintenant'), findsNothing,
          reason: 'Modal must auto-dismiss when gate reopens');
      // SnackBar shown
      expect(
          find.text('\u2705 Synchronisation r\u00e9ussie \u2014 acc\u00e8s complet restaur\u00e9'),
          findsOneWidget,
          reason: 'Success SnackBar must appear after gate lifts');
    });

    testWidgets('modal_tapSynchroniserMaintenant_callsTriggerSync',
        (tester) async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();
      final fake = _FakeSyncTriggerNotifier();

      await tester.pumpWidget(_wrapButton(
        connectivity: [ConnectivityResult.wifi],
        prefs: prefs,
        notifier: fake,
      ));
      await _openModal(tester);

      await tester.tap(find.text('Synchroniser maintenant'));
      await tester.pumpAndSettle();

      expect(fake.syncCallCount, 1,
          reason: 'triggerSync() must be called once on button tap');
    });
  });
}
