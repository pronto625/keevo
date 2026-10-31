import 'package:drift/drift.dart';

/// StockLevels — per-store denormalized stock quantity.
///
/// Denormalized for offline POS performance: avoids joins at sale time.
///
/// Schema v5 (Story 2.3): added variantId, minimumThreshold.
/// Schema v10 (Story 4.1): UNIQUE INDEX on (product_id, store_id) via migration.
/// Schema v25: uniqueKeys added to Drift definition so onCreate also enforces it.
class StockLevels extends Table {
  TextColumn get id => text()();
  TextColumn get productId => text()();

  /// Nullable — only set for variant products (Story 2.3).
  TextColumn get variantId => text().nullable()();

  TextColumn get storeId => text()();
  IntColumn get quantity => integer()();

  /// Minimum stock alert threshold. 0 = no alert (Story 2.3).
  IntColumn get minimumThreshold => integer().withDefault(const Constant(0))();

  DateTimeColumn get updatedAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};

  /// Enforce one stock-level row per (product, store) — prevents phantom
  /// duplicates on fresh installs where the v10 migration never runs.
  @override
  List<Set<Column>> get uniqueKeys => [
        {productId, storeId},
      ];
}
