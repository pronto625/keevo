import 'package:drift/drift.dart';

/// SyncQueue — Drift table for outbound operation queue.
///
/// Stores local operations that must be pushed to the backend.
/// Columns:
///   id            — UUID string (primary key, client-generated)
///   operation     — Operation type string e.g. "CREATE_SALE"
///   payload       — JSON string of operation data
///   createdAt     — Timestamp of operation creation
///   synced        — True once server has acknowledged
///   retryCount    — Number of push attempts (Story 5.1)
///   lastAttemptAt — Timestamp of last push attempt (Story 5.1)
///   entityId      — Identifier of the entity being synced (Story 5.1)
///   lastError     — Server rejection reason (domain error code), if the most
///                   recent push attempt was REJECTED. Null while pending or
///                   after a successful push. Used to notify the user once
///                   per distinct reason instead of retrying silently forever.
class SyncQueue extends Table {
  TextColumn get id => text()();
  TextColumn get operation => text()();
  TextColumn get payload => text()(); // JSON string
  DateTimeColumn get createdAt => dateTime()();
  BoolColumn get synced => boolean().withDefault(const Constant(false))();
  IntColumn get retryCount => integer().withDefault(const Constant(0))();
  DateTimeColumn get lastAttemptAt => dateTime().nullable()();
  TextColumn get entityId => text().nullable()();
  TextColumn get lastError => text().nullable()();

  @override
  Set<Column> get primaryKey => {id};
}
