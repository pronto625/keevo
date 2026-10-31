import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/sync/riverpod_sync_trigger_dispatcher.dart';
import 'package:keevo/core/sync/sync_trigger_notifier.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/sync/connectivity_service.dart';
import 'package:keevo_core/sync/sync_event_logger.dart';
import 'package:keevo_core/sync/sync_service.dart';
import 'package:keevo_core/sync/sync_status_provider.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _MockSyncService extends Mock implements SyncService {}

class _MockEventLogger extends Mock implements SyncEventLogger {}

class _OnlineConnectivity implements ConnectivityService {
  @override
  Future<bool> isOnline() async => true;

  @override
  Stream<bool> get onlineStream => const Stream.empty();
}

/// Connectivity restore, the periodic timers and every local write can all
/// start a sync. Two pushes of the same queue at once replay the same
/// operations twice, so a cycle already running must be joined, never
/// duplicated.
void main() {
  late _MockSyncService sync;
  late ProviderContainer container;
  late Completer<void> releasePush;
  var pushesRunning = 0;
  var maxPushesRunning = 0;
  var pushCalls = 0;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    sync = _MockSyncService();
    final logger = _MockEventLogger();
    when(() => logger.logPushSuccess(any())).thenAnswer((_) async {});
    when(() => logger.logPushFailed(any())).thenAnswer((_) async {});
    when(() => logger.logPullSuccess(any())).thenAnswer((_) async {});
    when(() => logger.logPullFailed(any())).thenAnswer((_) async {});

    releasePush = Completer<void>();
    pushesRunning = 0;
    maxPushesRunning = 0;
    pushCalls = 0;
    when(() => sync.push()).thenAnswer((_) async {
      pushCalls++;
      pushesRunning++;
      if (pushesRunning > maxPushesRunning) maxPushesRunning = pushesRunning;
      await releasePush.future;
      pushesRunning--;
      return <Map<String, dynamic>>[];
    });
    when(() => sync.pull()).thenAnswer((_) async {});

    container = ProviderContainer(overrides: [
      sharedPreferencesProvider.overrideWithValue(prefs),
      syncServiceProvider.overrideWithValue(sync),
      syncEventLoggerProvider.overrideWithValue(logger),
      connectivityStreamProvider.overrideWith(
        (ref) => Stream.value([ConnectivityResult.wifi]),
      ),
    ]);
    addTearDown(container.dispose);
  });

  SyncTriggerNotifier notifier() =>
      container.read(syncTriggerNotifierProvider.notifier);

  test('a second triggerSync joins the running cycle', () async {
    final first = notifier().triggerSync();
    final second = notifier().triggerSync();
    releasePush.complete();
    await Future.wait([first, second]);

    expect(pushCalls, 1);
    verify(() => sync.pull()).called(1);
  });

  test('triggerPush during a sync never runs a second push concurrently',
      () async {
    final cycle = notifier().triggerSync();
    final push = notifier().triggerPush();
    await pumpEventQueue();
    expect(pushCalls, 1);
    releasePush.complete();
    await Future.wait([cycle, push]);
    await pumpEventQueue();

    expect(maxPushesRunning, 1);
    // The push asked for during the cycle still runs once the cycle is over:
    // the operation queued meanwhile may have been read too late.
    expect(pushCalls, 2);
  });

  test('triggerSync during a push-only cycle still pulls afterwards',
      () async {
    final push = notifier().triggerPush();
    final cycle = notifier().triggerSync();
    await pumpEventQueue();
    expect(maxPushesRunning, 1);
    releasePush.complete();
    await Future.wait([push, cycle]);

    expect(maxPushesRunning, 1);
    verify(() => sync.pull()).called(1);
  });

  // v1s-16-12: a login/tenant switch during a running cycle must still get
  // its own fresh full sync — the running one may predate the switch.
  test('onAppStartup during a running cycle runs a second full cycle after it',
      () async {
    final cycle = notifier().triggerSync();
    final startup = notifier().onAppStartup();
    await pumpEventQueue();
    expect(pushCalls, 1);

    releasePush.complete();
    await Future.wait([cycle, startup]);

    expect(maxPushesRunning, 1);
    expect(pushCalls, 2);
    verify(() => sync.pull()).called(2);
  });

  test('a local write during a sync does not start a concurrent push',
      () async {
    final dispatcherProvider = Provider(
        (ref) => RiverpodSyncTriggerDispatcher(ref, _OnlineConnectivity()));
    final cycle = notifier().triggerSync();

    container.read(dispatcherProvider).triggerPushIfIdle();
    await pumpEventQueue();
    expect(pushCalls, 1);

    releasePush.complete();
    await cycle;
    await pumpEventQueue();
    expect(maxPushesRunning, 1);
    expect(pushCalls, 2);
  });
}
