import 'package:drift/drift.dart';

/// StockLevels — per-store denormalized stock quantity.
///
/// Denormalized for offline POS performance: avoids joins at sale time.
///
/// Schema v5 (Story 2.3): added variantId, minimumThreshold.
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
}
