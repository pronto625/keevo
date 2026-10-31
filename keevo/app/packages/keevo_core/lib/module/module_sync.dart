import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'keevo_module_ui.dart';
import 'module_records.dart';

/// Story 15.5b — port: the app's sync queue as a module package sees it.
///
/// A module writes its records locally ([ModuleRecordStore]) and queues the
/// matching sync operation here, inside the same Drift transaction when it
/// wants both to commit together (the app's `SyncService` writes `sync_queue`
/// on the same `AppDatabase` connection). The app pushes the operations in
/// queue order like every other one (`/api/v1/sync/push`); their rejection
/// messages, the held / quarantine rules and the pull merge are the app's.
abstract interface class ModuleSyncQueue {
  /// Queues [operation] (e.g. `CREATE_INVOICE`) for [entityId].
  Future<void> enqueue({
    required String operation,
    required Map<String, dynamic> payload,
    required String entityId,
  });

  /// Ids with an operation still queued (pending or held): their local copy
  /// must not be replaced by a server read, and a later operation on them
  /// must be queued too (it applies after the queued ones).
  Future<Set<String>> unsyncedEntityIds();

  /// Story 15.5b — the operations still queued for [entityId] (pending or
  /// held), in queue order.
  Future<List<String>> queuedOperations(String entityId);

  /// Story 15.5b — removes every operation still queued for [entityId] (a
  /// record created offline and deleted before it was ever sent).
  Future<void> dropQueued(String entityId);

  /// Starts a background push (then a pull) when the device is online;
  /// returns at once.
  void requestSync();

  /// A full sync cycle (push then pull) now, awaited — pull-to-refresh.
  /// Offline: returns without doing anything.
  Future<void> syncNow();
}

/// The app's [ModuleSyncQueue] — overridden in `buildRootScope`
/// (`modulePortOverrides`), or by a test.
final moduleSyncQueueProvider = Provider<ModuleSyncQueue>(
  (ref) => throw UnimplementedError(
    'Port keevo_core "moduleSyncQueueProvider" not provided: the app must '
    'override it (buildRootScope, keevo/app/lib/root_scope.dart) or the test '
    'must.',
  ),
);

/// Pull merger of every module of [modules], by pull entity key (e.g.
/// `invoices`). Two modules claiming the same key is a programming error
/// ([StateError]).
Map<String, ModuleEntityMerger> composeModuleMergers(
  List<KeevoModuleUi> modules,
) {
  final mergers = <String, ModuleEntityMerger>{};
  for (final module in modules) {
    for (final entry in module.pulledEntities.entries) {
      if (mergers.containsKey(entry.key)) {
        throw StateError(
          'Pulled entity "${entry.key}" registered by two modules '
          '(second: "${module.id}")',
        );
      }
      mergers[entry.key] = entry.value;
    }
  }
  return Map.unmodifiable(mergers);
}

/// Story 15.5b — record API paths of every module of [modules], by pull key
/// (`KeevoModuleUi.recordPaths`).
Map<String, String Function(String id)> composeModuleRecordPaths(
  List<KeevoModuleUi> modules,
) => Map.unmodifiable({for (final m in modules) ...m.recordPaths});
