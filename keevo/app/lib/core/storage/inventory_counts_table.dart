import 'package:drift/drift.dart';

/// Drift table definition for inventory_counts — Story 6.2.
///
/// Records physical count entries for each product (or variant)
/// in an inventory counting session.
class InventoryCounts extends Table {
  TextColumn get id => text()();
  TextColumn get sessionId => text()();
  TextColumn get productId => text()();
  TextColumn get variantId => text().nullable()();
  TextColumn get productName => text()();
  TextColumn get variantLabel => text().nullable()();
  IntColumn get theoretical => integer()();
  IntColumn get physical => integer().nullable()();
  TextColumn get countedBy => text().nullable()();
  DateTimeColumn get countedAt => dateTime().nullable()();
  DateTimeColumn get updatedAt => dateTime()();
  BoolColumn get synced => boolean().withDefault(const Constant(false))();

  @override
  Set<Column> get primaryKey => {id};
}
