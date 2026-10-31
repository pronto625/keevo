import 'package:drift/drift.dart';

/// Employees — local cache of employee records.
///
/// Mirrors the `employees` table in the PostgreSQL tenant schema.
/// Schema v16: added for Story 5.1 — offline employee support.
class Employees extends Table {
  TextColumn get id => text()();
  TextColumn get userId => text()();
  TextColumn get firstName => text()();
  TextColumn get lastName => text()();
  TextColumn get storeId => text()();
  TextColumn get status => text().withDefault(const Constant('ACTIVE'))();
  BoolColumn get passwordChangeRequired =>
      boolean().withDefault(const Constant(false))();
  DateTimeColumn get createdAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}
