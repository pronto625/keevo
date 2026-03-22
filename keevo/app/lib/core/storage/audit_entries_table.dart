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

  @override
  Set<Column> get primaryKey => {id};
}
