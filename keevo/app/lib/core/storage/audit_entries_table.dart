import 'package:drift/drift.dart';

/// AuditEntries — immutable audit trail downloaded from backend via pull sync.
///
/// Insert-only: pull sync uses INSERT OR IGNORE (no upsert).
/// Story 5.2 — Pull Sync: local offline audit history.
class AuditEntries extends Table {
  TextColumn get id => text()();
  TextColumn get userId => text()();
  TextColumn get entityType => text()();
  TextColumn get entityId => text()();
  TextColumn get action => text()();
  TextColumn get valueBefore => text().nullable()();
  TextColumn get valueAfter => text().nullable()();
  DateTimeColumn get occurredAt => dateTime()();

  // v1s-18-7 — enrichment from v1s-18-6 (null on rows written before it).
  TextColumn get storeId => text().nullable()();
  TextColumn get entityLabel => text().nullable()();
  TextColumn get deviceId => text().nullable()();

  /// ONLINE | SYNC | SYSTEM.
  TextColumn get source => text().nullable()();

  /// JSON object text (justification, motif, scope, actorLabel…).
  TextColumn get metadata => text().nullable()();

  @override
  Set<Column> get primaryKey => {id};
}
