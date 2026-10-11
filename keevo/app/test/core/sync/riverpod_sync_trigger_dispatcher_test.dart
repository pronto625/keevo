import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart' hide isNull;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/core/sync/riverpod_sync_trigger_dispatcher.dart';
import 'package:keevo/core/sync/sync_trigger_notifier.dart';
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

  @override
  SyncTriggerState build() => const SyncTriggerState.idle();

  @override
  Future<void> triggerPush() async {
    try {
      queuedRowsSeen = (await db!.select(db!.syncQueue).get()).length;
    } catch (e) {
      error = e;
    }
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
    await Future<void>.delayed(const Duration(milliseconds: 50));

    expect(_ReadingPushNotifier.error, isNull);
    expect(_ReadingPushNotifier.queuedRowsSeen, 1);
  });
}

final _refProvider = Provider<Ref>((ref) => ref);
