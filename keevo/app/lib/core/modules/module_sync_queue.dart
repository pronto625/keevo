import 'dart:async';

import 'package:drift/drift.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/module/module_sync.dart';

import '../di/providers.dart';
import '../sync/sync_trigger_notifier.dart';

/// Story 15.5b — the app's sync queue behind the `keevo_core` port
/// [moduleSyncQueueProvider] (wired in `buildRootScope`): module packages
/// queue their operations in the same `sync_queue` as the app's features
/// ([syncServiceProvider]) and ask for a sync through the same trigger.
class AppModuleSyncQueue implements ModuleSyncQueue {
  final Ref _ref;

  AppModuleSyncQueue(this._ref);

  @override
  Future<void> enqueue({
    required String operation,
    required Map<String, dynamic> payload,
    required String entityId,
  }) => _ref
      .read(syncServiceProvider)
      .queueOperation(operation: operation, payload: payload, entityId: entityId);

  @override
  Future<Set<String>> unsyncedEntityIds() =>
      _ref.read(syncServiceProvider).unsyncedEntityIds();

  @override
  Future<List<String>> queuedOperations(String entityId) async {
    final db = _ref.read(appDatabaseProvider);
    final rows = await (db.select(db.syncQueue)
          ..where((t) => t.synced.equals(false) & t.entityId.equals(entityId))
          ..orderBy([(t) => OrderingTerm.asc(t.createdAt)]))
        .get();
    return [for (final r in rows) r.operation];
  }

  @override
  Future<void> dropQueued(String entityId) async {
    final db = _ref.read(appDatabaseProvider);
    await (db.delete(db.syncQueue)
          ..where((t) => t.synced.equals(false) & t.entityId.equals(entityId)))
        .go();
  }

  @override
  void requestSync() {
    // Root zone, after the caller's frame: a caller inside a Drift
    // transaction must not hand its zone to the push (same reason as
    // RiverpodSyncTriggerDispatcher). Offline, the cycle returns at once.
    Zone.root.run(
      () => Future.microtask(() async {
        try {
          await _ref.read(syncTriggerNotifierProvider.notifier).triggerSync();
        } catch (_) {
          // The cycle reports its own failures (sync history, retry).
        }
      }),
    );
  }

  @override
  Future<void> syncNow() async {
    try {
      await Zone.root.run(
        () => _ref.read(syncTriggerNotifierProvider.notifier).triggerSync(),
      );
    } catch (_) {
      // Same: a failed cycle is reported and retried by the trigger.
    }
  }
}
