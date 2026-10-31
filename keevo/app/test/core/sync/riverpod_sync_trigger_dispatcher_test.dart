import 'dart:async';
import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart' hide isNull;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/riverpod_sync_trigger_dispatcher.dart';
import 'package:keevo/core/sync/sync_trigger_notifier.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/sync/connectivity_service.dart';
import 'package:sqlite3/open.dart';

class _Online implements ConnectivityService {
  @override
  Future<bool> isOnline() async => true;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

/// Push that reads the queue, like the real one.
class _ReadingPushNotifier extends SyncTriggerNotifier {
  static AppDatabase? db;
  static int? queuedRowsSeen;
  static Object? error;
  static Completer<void> done = Completer<void>();

  @override
  SyncTriggerState build() => const SyncTriggerState.idle();

  @override
  Future<void> triggerPush() async {
    try {
      queuedRowsSeen = (await db!.select(db!.syncQueue).get()).length;
    } catch (e) {
      error = e;
    } finally {
      if (!done.isCompleted) done.complete();
    }
  }
}

/// Online check that counts its calls.
class _CountingOnline implements ConnectivityService {
  int calls = 0;

  @override
  Future<bool> isOnline() async {
    calls++;
    return true;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

/// Push that only counts its calls.
class _CountingPushNotifier extends SyncTriggerNotifier {
  static int pushes = 0;

  @override
  SyncTriggerState build() => const SyncTriggerState.idle();

  @override
  Future<void> triggerPush() async {
    pushes++;
  }
}

void _overrideSqlite3ForLinuxTesting() {
  if (!Platform.isLinux) return;
  open.overrideFor(OperatingSystem.linux, () {
    try {
      return DynamicLibrary.open('libsqlite3.so');
    } catch (_) {
      return DynamicLibrary.open('libsqlite3.so.0');
    }
  });
}

void main() {
  setUpAll(_overrideSqlite3ForLinuxTesting);

  // A sale with a draft product is recorded inside an outer transaction; the
  // push it triggers must not run on that transaction (before the commit, or
  // after it closed): it must see the committed rows.
  test('push triggered inside a transaction runs after the commit', () async {
    final db = AppDatabase.forTesting();
    addTearDown(db.close);
    _ReadingPushNotifier.db = db;
    _ReadingPushNotifier.queuedRowsSeen = null;
    _ReadingPushNotifier.error = null;
    _ReadingPushNotifier.done = Completer<void>();

    final container = ProviderContainer(overrides: [
      syncTriggerNotifierProvider.overrideWith(_ReadingPushNotifier.new),
    ]);
    addTearDown(container.dispose);
    final dispatcher = RiverpodSyncTriggerDispatcher(
        container.read(_refProvider), _Online());

    await db.transaction(() async {
      await db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
            id: 'op-1',
            operation: 'CREATE_SALE',
            payload: '{}',
            entityId: const Value('sale-1'),
            createdAt: DateTime.now(),
          ));
      dispatcher.triggerPushIfIdle();
    });
    // v1s-15-8: wait for the push itself, not a real 50 ms delay.
    await _ReadingPushNotifier.done.future
        .timeout(const Duration(seconds: 5));

    expect(_ReadingPushNotifier.error, isNull);
    expect(_ReadingPushNotifier.queuedRowsSeen, 1);
  });

  group('v1s-15-10: no stale Ref after the scope is disposed', () {
    late _CountingOnline online;

    ProviderContainer newContainer() {
      _CountingPushNotifier.pushes = 0;
      online = _CountingOnline();
      return ProviderContainer(overrides: [
        connectivityServiceProvider.overrideWithValue(online),
        syncTriggerNotifierProvider.overrideWith(_CountingPushNotifier.new),
      ]);
    }

    test('a live dispatcher pushes', () async {
      final container = newContainer();
      addTearDown(container.dispose);
      final dispatcher = container.read(syncTriggerDispatcherProvider);

      dispatcher.triggerPushIfIdle();
      await pumpEventQueue();

      expect(online.calls, 1);
      expect(_CountingPushNotifier.pushes, 1);
    });

    test('provider dispose turns triggerPushIfIdle into a no-op', () async {
      final container = newContainer();
      final dispatcher = container.read(syncTriggerDispatcherProvider)
          as RiverpodSyncTriggerDispatcher;

      container.dispose();
      expect(dispatcher.isDisposed, isTrue);

      dispatcher.triggerPushIfIdle();
      await pumpEventQueue();

      expect(online.calls, 0, reason: 'never touches the stale Ref');
      expect(_CountingPushNotifier.pushes, 0);
    });

    test('dispose between scheduling and the microtask: no push', () async {
      final container = newContainer();
      final dispatcher = container.read(syncTriggerDispatcherProvider);

      dispatcher.triggerPushIfIdle();
      container.dispose();
      await pumpEventQueue();

      expect(online.calls, 0);
      expect(_CountingPushNotifier.pushes, 0);
    });
  });
}

final _refProvider = Provider<Ref>((ref) => ref);
