import 'dart:async';
import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart' show DatabaseConnection;
import 'package:drift/native.dart';
import 'package:keevo_core/module/module_records.dart';
import 'package:keevo_core/module/module_sync.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/storage/search_fold.dart';
import 'package:keevo_core/sync/connectivity_service.dart';
import 'package:sqlite3/open.dart';

/// Story 15.5b — test support of the offline-first invoices: the system
/// SQLite for an in-memory [AppDatabase], a switchable connectivity and a
/// recording sync queue (the `moduleSyncQueueProvider` port).

/// In-memory databases on Linux need the system library (`libsqlite3.so.0`
/// when the `.so` symlink is missing).
void useSystemSqlite() {
  if (!Platform.isLinux) return;
  open.overrideFor(OperatingSystem.linux, () {
    try {
      return DynamicLibrary.open('libsqlite3.so');
    } catch (_) {
      return DynamicLibrary.open('libsqlite3.so.0');
    }
  });
}

class FakeConnectivity implements ConnectivityService {
  bool online;

  FakeConnectivity({this.online = true});

  @override
  Future<bool> isOnline() async => online;

  @override
  Stream<bool> get onlineStream => const Stream.empty();
}

typedef QueuedOp = ({
  String operation,
  Map<String, dynamic> payload,
  String entityId,
});

/// Records the queued operations; [unsyncedEntityIds] = their entities until
/// [clear] (the app's push applied them).
class FakeModuleSyncQueue implements ModuleSyncQueue {
  final List<QueuedOp> queued = [];
  int syncRequests = 0;
  int syncNowCalls = 0;

  /// Thrown by [enqueue] when set (e.g. an account read-only).
  Object? enqueueError;

  @override
  Future<void> enqueue({
    required String operation,
    required Map<String, dynamic> payload,
    required String entityId,
  }) async {
    final error = enqueueError;
    if (error != null) throw error;
    queued.add((operation: operation, payload: payload, entityId: entityId));
  }

  @override
  Future<Set<String>> unsyncedEntityIds() async => {
    for (final q in queued) q.entityId,
  };

  @override
  Future<List<String>> queuedOperations(String entityId) async => [
    for (final q in queued)
      if (q.entityId == entityId) q.operation,
  ];

  @override
  Future<void> dropQueued(String entityId) async =>
      queued.removeWhere((q) => q.entityId == entityId);

  @override
  void requestSync() => syncRequests++;

  @override
  Future<void> syncNow() async => syncNowCalls++;

  void clear() => queued.clear();

  List<String> get operations => [for (final q in queued) q.operation];
}

/// Stores [rows] (API-shaped invoices) as the pull would.
Future<void> seedInvoices(
  AppDatabase db,
  List<Map<String, dynamic>> rows, {
  bool pending = false,
}) async {
  final store = ModuleRecordStore(db);
  for (final row in rows) {
    await store.put(
      'invoicing',
      'invoices',
      row['id'] as String,
      row,
      pending: pending,
    );
  }
}

/// An in-memory database whose query streams close synchronously: a widget
/// test leaves no pending timer when its scope (and the streams) go away.
AppDatabase testDatabase() => AppDatabase.forTestingWith(
  DatabaseConnection(
    NativeDatabase.memory(setup: registerSearchFold),
    closeStreamsSynchronously: true,
  ),
);
