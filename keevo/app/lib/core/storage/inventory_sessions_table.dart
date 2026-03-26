import 'package:drift/drift.dart';

/// Drift table definition for inventory_sessions — Story 6.1.
///
/// Records inventory counting sessions with their scope and status.
/// Status values: IN_PROGRESS | VALIDATED | CANCELLED
/// Scope values: FULL | PARTIAL
class InventorySessions extends Table {
  TextColumn get id => text()();
  TextColumn get storeId => text()();
  TextColumn get scope => text()(); // 'FULL' | 'PARTIAL'
  TextColumn get categoryIds => text().nullable()();
  TextColumn get status => text()();
  TextColumn get startedBy => text()();
  DateTimeColumn get startedAt => dateTime()();
  TextColumn get cancelledBy => text().nullable()();
  DateTimeColumn get cancelledAt => dateTime().nullable()();
  DateTimeColumn get completedAt => dateTime().nullable()();
  DateTimeColumn get updatedAt => dateTime()();
  BoolColumn get synced => boolean().withDefault(const Constant(false))();

  @override
  Set<Column> get primaryKey => {id};
}
