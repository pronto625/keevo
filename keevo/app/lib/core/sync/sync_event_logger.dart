import 'package:drift/drift.dart';
import 'package:uuid/uuid.dart';

import '../storage/app_database.dart';

/// Event type of an abandoned quarantined operation (v1s-16-15).
const kSyncEventAbandon = 'ABANDON';

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

  /// A quarantined operation abandoned by the owner after an export
  /// (v1s-16-15). [label] describes it (operation, date, amount).
  Future<void> logOperationAbandoned(String label) =>
      _log(kSyncEventAbandon, 'ABANDONED', 1, label);

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
      // Abandoned-operation traces (v1s-16-15, FR73a) are never rotated out.
      "DELETE FROM sync_events WHERE type != '$kSyncEventAbandon' AND id NOT IN "
      "(SELECT id FROM sync_events WHERE type != '$kSyncEventAbandon' "
      'ORDER BY created_at DESC LIMIT 50)',
    );
  }

  Future<List<SyncEvent>> getHistory({int limit = 20}) {
    return (_db.select(_db.syncEvents)
          ..orderBy([(t) => OrderingTerm.desc(t.createdAt)])
          ..limit(limit))
        .get();
  }
}
