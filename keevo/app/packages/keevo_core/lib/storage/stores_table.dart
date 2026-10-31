import 'package:drift/drift.dart';

/// Stores — physical retail locations and warehouses.
///
/// Schema updated in v7 (Story 3.1): added type, address, phone columns.
class Stores extends Table {
  TextColumn get id => text()();
  TextColumn get name => text()();
  TextColumn get tenantId => text()();
  // 'STORE' | 'WAREHOUSE' — defaults to STORE for backwards-compatibility
  TextColumn get type => text().withDefault(const Constant('STORE'))();
  TextColumn get address => text().nullable()();
  TextColumn get phone => text().nullable()();
  BoolColumn get isActive => boolean().withDefault(const Constant(true))();
  DateTimeColumn get createdAt => dateTime()();
  DateTimeColumn get updatedAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}
