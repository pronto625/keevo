import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/sync/sync_monitoring_providers.dart';
import 'package:keevo/core/sync/sync_trigger_notifier.dart';
import 'package:keevo/features/sync_indicator/presentation/widget/sync_indicator.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:keevo_core/sync/sync_event_logger.dart';
import 'package:keevo_core/sync/sync_gate_provider.dart';
import 'package:keevo_core/sync/sync_service.dart';
import 'package:keevo_core/sync/sync_status.dart';
import 'package:keevo_core/sync/sync_status_provider.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _MockSyncService extends Mock implements SyncService {}

class _MockEventLogger extends Mock implements SyncEventLogger {}

/// Story 15.4b — Riverpod 3 pauses the subscriptions of a widget its route
/// hides (flutter_riverpod 3.3 pauses `watch`, not `listen`, but that is an
/// implementation detail): two syncs while the indicator is hidden must both
/// reach the user — the sync appends its notices, never overwrites them.
void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);
  setUp(SyncIndicator.resetSurfacedRejections);

  testWidgets('two syncs while the indicator is hidden: both notices shown',
      (tester) async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final sync = _MockSyncService();
    final logger = _MockEventLogger();
    var pushes = 0;
    when(() => sync.push()).thenAnswer((_) async => [
          if (pushes++ == 0)
            {
              'status': 'REJECTED',
              'operationId': 'op-1',
              'operation': 'CREATE_PRODUCT',
              'reason': 'PLAN_LIMIT_EXCEEDED',
              'rolledBack': false,
            }
          else
            {
              'status': 'REJECTED',
              'operationId': 'op-2',
              'operation': 'CREATE_SALE',
              'reason': 'INSUFFICIENT_STOCK',
              'rolledBack': false,
            },
        ]);

    final container = ProviderContainer(retry: noProviderRetry, overrides: [
      sharedPreferencesProvider.overrideWithValue(prefs),
      syncServiceProvider.overrideWithValue(sync),
      syncEventLoggerProvider.overrideWithValue(logger),
      connectivityStreamProvider
          .overrideWith((ref) => Stream.value([ConnectivityResult.wifi])),
      syncStatusProvider.overrideWith((ref) => Stream.value(SyncStatus.online)),
      daysOfflineProvider.overrideWithValue(0),
      daysSinceLastSyncProvider.overrideWithValue(0),
      currentUserRoleProvider.overrideWithValue('EMPLOYEE'),
      quarantinedSyncCountProvider.overrideWith((ref) async => 0),
    ]);
    addTearDown(container.dispose);

    final visible = ValueNotifier(false);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        home: Scaffold(
          appBar: PreferredSize(
            preferredSize: const Size.fromHeight(56),
            child: AppBar(actions: [
              // Hidden like a page under a full-screen route.
              ValueListenableBuilder<bool>(
                valueListenable: visible,
                builder: (_, enabled, child) =>
                    TickerMode(enabled: enabled, child: child!),
                child: const SyncIndicator(),
              ),
            ]),
          ),
          body: const SizedBox.shrink(),
        ),
      ),
    ));
    await tester.pump();

    final trigger = container.read(syncTriggerNotifierProvider.notifier);
    await tester.runAsync(() async {
      await trigger.triggerPush();
      await trigger.triggerPush();
    });
    await tester.pump();

    visible.value = true;
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    const first = 'Non synchronisé (Création de produit) : Limite de votre '
        'forfait atteinte — passez à un forfait supérieur';
    const second =
        'Non synchronisé (Enregistrement de vente) : Stock insuffisant';
    expect(find.text(first), findsOneWidget);

    // SnackBars are queued: the second shows once the first is gone.
    tester
        .state<ScaffoldMessengerState>(find.byType(ScaffoldMessenger))
        .hideCurrentSnackBar();
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    expect(find.text(second), findsOneWidget);
    // Shown notices leave the pending list after the frame.
    expect(container.read(pendingRejectionNotificationsProvider), isEmpty);

    await tester.pumpWidget(const SizedBox.shrink());
    container.dispose();
  });

  test('a paused listener resumed after two pushes gets both notices',
      () async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final sync = _MockSyncService();
    var pushes = 0;
    when(() => sync.push()).thenAnswer((_) async => [
          {
            'status': 'REJECTED',
            'operationId': 'op-${++pushes}',
            'operation': 'CREATE_SALE',
            'reason': 'INSUFFICIENT_STOCK',
          },
        ]);
    final container = ProviderContainer(retry: noProviderRetry, overrides: [
      sharedPreferencesProvider.overrideWithValue(prefs),
      syncServiceProvider.overrideWithValue(sync),
      syncEventLoggerProvider.overrideWithValue(_MockEventLogger()),
      connectivityStreamProvider
          .overrideWith((ref) => Stream.value([ConnectivityResult.wifi])),
    ]);
    addTearDown(container.dispose);
    final seen = <List<Map<String, dynamic>>>[];
    final sub = container.listen(
        pendingRejectionNotificationsProvider, (_, next) => seen.add(next));
    addTearDown(sub.close);

    sub.pause();
    final trigger = container.read(syncTriggerNotifierProvider.notifier);
    await trigger.triggerPush();
    await trigger.triggerPush();
    sub.resume();

    // Riverpod 3 replays only the latest value to a resumed listener.
    expect(seen.last.map((r) => r['operationId']), ['op-1', 'op-2']);
  });
}
