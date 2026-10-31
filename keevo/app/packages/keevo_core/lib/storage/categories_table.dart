import 'package:drift/drift.dart';

/// Categories — product category tree.
///
/// Mirrors the categories table schema from backend (Story 1.4),
/// where the DDL was already provisioned in tenant schemas.
class Categories extends Table {
  TextColumn get id => text()();
  TextColumn get name => text()();
  TextColumn get parentId => text().nullable()(); // self-referencing for subcategories
  BoolColumn get isActive => boolean().withDefault(const Constant(true))();
  BoolColumn get isCustom => boolean().withDefault(const Constant(false))();
  DateTimeColumn get createdAt => dateTime()();
  DateTimeColumn get updatedAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}
