import 'package:drift/drift.dart';
import 'package:uuid/uuid.dart';

import '../storage/app_database.dart';

/// Logs sync events to local Drift sync_events table.
/// Prunes entries older than the 50 most recent on every insert.
class SyncEventLogger {
  final AppDatabase _db;

  SyncEventLogger(this._db);

  Future<void> logPushSuccess(int operationCount) =>
      _log('PUSH', 'SUCCESS', operationCount, null);

  Future<void> logPushFailed(String error) =>
      _log('PUSH', 'FAILED', 0, error);

  Future<void> logPullSuccess(int operationCount) =>
      _log('PULL', 'SUCCESS', operationCount, null);

  Future<void> logPullFailed(String error) =>
      _log('PULL', 'FAILED', 0, error);

  Future<void> logDiagnostic(String status, String? message) =>
      _log('DIAGNOSTIC', status, 0, message);

  Future<void> _log(
      String type, String status, int opCount, String? error) async {
    await _db.into(_db.syncEvents).insert(SyncEventsCompanion.insert(
          id: const Uuid().v4(),
          type: type,
          status: status,
          operationCount: Value(opCount),
          errorMessage: Value(error),
          createdAt: DateTime.now(),
        ));
    // Prune: keep only last 50 entries
    await _db.customStatement(
      'DELETE FROM sync_events WHERE id NOT IN '
      '(SELECT id FROM sync_events ORDER BY created_at DESC LIMIT 50)',
    );
  }

  Future<List<SyncEvent>> getHistory({int limit = 20}) {
    return (_db.select(_db.syncEvents)
          ..orderBy([(t) => OrderingTerm.desc(t.createdAt)])
          ..limit(limit))
        .get();
  }
}
