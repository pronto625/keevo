import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:keevo/core/auth/session_refresher.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/sync/account_read_only_exception.dart';
import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/core/sync/sync_event_logger.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/core/sync/sync_status_provider.dart';
import 'package:keevo/core/sync/sync_trigger_notifier.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';

class _MockSyncService extends Mock implements SyncService {}

class _MockEventLogger extends Mock implements SyncEventLogger {}

class _MockRefresher extends Mock implements SessionRefresher {}

class _FakeConnectivity implements ConnectivityService {
  bool online;
  _FakeConnectivity(this.online);

  @override
  Future<bool> isOnline() async => online;

  @override
  Stream<bool> get onlineStream => const Stream.empty();
}

/// A44 — offline, the app made a push attempt (and counted one more
/// « tentative » on every queued operation) at each retry tick. Nothing is
/// tried while the connectivity service says offline; the sync resumes when
/// the network comes back. A50 — a read-only account is not a failure to
/// retry either.
void main() {
  late _MockSyncService sync;
  late _MockEventLogger logger;
  late _MockRefresher refresher;
  late _FakeConnectivity connectivity;
  late StreamController<List<ConnectivityResult>> connectivityEvents;
  late ProviderContainer container;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    sync = _MockSyncService();
    logger = _MockEventLogger();
    refresher = _MockRefresher();
    connectivity = _FakeConnectivity(false);
    connectivityEvents = StreamController<List<ConnectivityResult>>();
    when(() => logger.logPushSuccess(any())).thenAnswer((_) async {});
    when(() => logger.logPushFailed(any())).thenAnswer((_) async {});
    when(() => logger.logPullSuccess(any())).thenAnswer((_) async {});
    when(() => logger.logPullFailed(any())).thenAnswer((_) async {});
    when(() => sync.push()).thenAnswer((_) async => <Map<String, dynamic>>[]);
    when(() => sync.pull()).thenAnswer((_) async {});
    when(() => refresher.refresh())
        .thenAnswer((_) async => RefreshOutcome.renewed);

    container = ProviderContainer(overrides: [
      sharedPreferencesProvider.overrideWithValue(prefs),
      syncServiceProvider.overrideWithValue(sync),
      syncEventLoggerProvider.overrideWithValue(logger),
      connectivityServiceProvider.overrideWithValue(connectivity),
      connectivityStreamProvider.overrideWith((ref) => connectivityEvents.stream),
      sessionRefresherProvider.overrideWithValue(refresher),
    ]);
    addTearDown(() async {
      container.dispose();
      await connectivityEvents.close();
    });
  });

  SyncTriggerNotifier notifier() =>
      container.read(syncTriggerNotifierProvider.notifier);

  test('offline: neither a sync nor a push is attempted', () async {
    await notifier().triggerSync();
    await notifier().triggerPush();

    verifyNever(() => sync.push());
    verifyNever(() => sync.pull());
    expect(container.read(syncTriggerNotifierProvider), isA<SyncTriggerIdle>());
  });

  test('the sync resumes when the network comes back', () async {
    container.listen(syncTriggerNotifierProvider, (_, __) {});
    await notifier().triggerSync();
    verifyNever(() => sync.push());

    connectivity.online = true;
    connectivityEvents.add([ConnectivityResult.wifi]);
    // Connectivity restore is debounced by 3 seconds.
    await Future<void>.delayed(const Duration(milliseconds: 3300));

    verify(() => sync.push()).called(1);
    verify(() => sync.pull()).called(1);
  });

  test('read-only account: the push is held, the pull still runs, no retry',
      () async {
    connectivity.online = true;
    when(() => sync.push()).thenThrow(
        const AccountReadOnlyException(AccountReadOnlyReason.deletionPending));

    await notifier().triggerSync();
    await notifier().triggerSync();

    verify(() => sync.pull()).called(2);
    // One history entry per read-only episode, not one per cycle.
    verify(() => logger.logPushFailed(kSyncPushReadOnlyMessage)).called(1);
    // A throttled renewal notices a reactivation / a cancelled deletion.
    verify(() => refresher.refresh()).called(1);
    expect(container.read(syncTriggerNotifierProvider), isA<SyncTriggerIdle>());
  });

  test('read-only account on a push-only cycle: idle, not a failure',
      () async {
    connectivity.online = true;
    when(() => sync.push()).thenThrow(
        const AccountReadOnlyException(AccountReadOnlyReason.suspended));

    await notifier().triggerPush();

    expect(container.read(syncTriggerNotifierProvider), isA<SyncTriggerIdle>());
  });
}
