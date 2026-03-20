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
class SyncQueue extends Table {
  TextColumn get id => text()();
  TextColumn get operation => text()();
  TextColumn get payload => text()(); // JSON string
  DateTimeColumn get createdAt => dateTime()();
  BoolColumn get synced => boolean().withDefault(const Constant(false))();
  IntColumn get retryCount => integer().withDefault(const Constant(0))();
  DateTimeColumn get lastAttemptAt => dateTime().nullable()();
  TextColumn get entityId => text().nullable()();

  @override
  Set<Column> get primaryKey => {id};
}
