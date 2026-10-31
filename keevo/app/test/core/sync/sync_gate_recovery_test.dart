import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/sync/sync_trigger_notifier.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:keevo_core/storage/app_constants.dart';
import 'package:keevo_core/sync/sync_event_logger.dart';
import 'package:keevo_core/sync/sync_gate_provider.dart';
import 'package:keevo_core/sync/sync_required_exception.dart';
import 'package:keevo_core/sync/sync_service.dart';
import 'package:keevo_core/sync/sync_status_provider.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _MockSyncService extends Mock implements SyncService {}

class _MockEventLogger extends Mock implements SyncEventLogger {}

/// A device offline for more than 7 days gets HTTP 423 on push. The server
/// lifts that gate on a successful pull, so the cycle must pull and then push
/// again — otherwise the queued sales can never leave the device.
void main() {
  late _MockSyncService sync;
  late ProviderContainer container;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    sync = _MockSyncService();
    final logger = _MockEventLogger();
    when(() => logger.logPushSuccess(any())).thenAnswer((_) async {});
    when(() => logger.logPushFailed(any())).thenAnswer((_) async {});
    when(() => logger.logPullSuccess(any())).thenAnswer((_) async {});
    when(() => logger.logPullFailed(any())).thenAnswer((_) async {});

    container = ProviderContainer(retry: noProviderRetry, overrides: [
      sharedPreferencesProvider.overrideWithValue(prefs),
      syncServiceProvider.overrideWithValue(sync),
      syncEventLoggerProvider.overrideWithValue(logger),
      connectivityStreamProvider.overrideWith(
        (ref) => Stream.value([ConnectivityResult.wifi]),
      ),
    ]);
    addTearDown(container.dispose);
  });

  test('a 423 on push is followed by a pull and a second push', () async {
    var pushCalls = 0;
    when(() => sync.push()).thenAnswer((_) async {
      pushCalls++;
      if (pushCalls == 1) {
        throw const SyncRequiredException(daysSinceLastSync: 9);
      }
      return <Map<String, dynamic>>[];
    });
    when(() => sync.pull()).thenAnswer((_) async {});

    await container.read(syncTriggerNotifierProvider.notifier).triggerSync();

    verify(() => sync.pull()).called(1);
    expect(pushCalls, 2);
    expect(container.read(syncTriggerNotifierProvider), isA<SyncTriggerIdle>());
  });

  test('a 423 that persists after the pull leaves the cycle in retry',
      () async {
    when(() => sync.push())
        .thenThrow(const SyncRequiredException(daysSinceLastSync: 9));
    when(() => sync.pull()).thenAnswer((_) async {});

    await container.read(syncTriggerNotifierProvider.notifier).triggerSync();

    verify(() => sync.pull()).called(1);
    verify(() => sync.push()).called(2);
    expect(
      container.read(syncTriggerNotifierProvider),
      isNot(isA<SyncTriggerIdle>()),
    );
  });

  // J23 (device test): the gate provider caches the last-sync time. A
  // successful cycle must recompute it — before the fix the gate stayed
  // blocked in memory until the app was restarted.
  test('J23: a successful cycle reopens a stale (8 days) gate', () async {
    final prefs = container.read(sharedPreferencesProvider);
    await prefs.setInt(
        kLastSyncAtKey,
        DateTime.now()
            .subtract(const Duration(days: 8))
            .millisecondsSinceEpoch);
    container.invalidate(daysSinceLastSyncProvider);
    expect(container.read(syncGateStateProvider).isWriteBlocked, isTrue);

    when(() => sync.push()).thenAnswer((_) async => <Map<String, dynamic>>[]);
    when(() => sync.pull()).thenAnswer((_) async {
      await prefs.setInt(
          kLastSyncAtKey, DateTime.now().millisecondsSinceEpoch);
    });

    await container.read(syncTriggerNotifierProvider.notifier).triggerSync();

    expect(container.read(syncGateStateProvider).isWriteBlocked, isFalse);
  });
}
