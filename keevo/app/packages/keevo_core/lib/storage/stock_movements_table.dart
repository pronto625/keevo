import 'package:drift/drift.dart';

/// StockMovements — immutable audit trail of stock changes.
///
/// Subject to 30-day rolling purge (only synced rows are deleted).
/// Mirrors [StockAdjustedEvent] backend domain event.
///
/// Schema v5 (Story 2.3): added variantId, quantityBefore, quantityAfter.
class StockMovements extends Table {
  TextColumn get id => text()();
  TextColumn get productId => text()();

  /// Nullable — only set for variant products (Story 2.3).
  TextColumn get variantId => text().nullable()();

  TextColumn get storeId => text()();

  /// Movement type: 'SALE' | 'STOCK_ENTRY' | 'TRANSFER_IN' | 'TRANSFER_OUT' | 'ADJUSTMENT'
  TextColumn get type => text()();

  /// Quantity before the operation (Story 2.3).
  IntColumn get quantityBefore => integer().withDefault(const Constant(0))();

  /// Signed quantity delta: positive = stock in, negative = stock out.
  IntColumn get quantityDelta => integer()();

  /// Quantity after the operation (Story 2.3).
  IntColumn get quantityAfter => integer().withDefault(const Constant(0))();

  TextColumn get actorId => text()();
  TextColumn get reason => text().nullable()();

  /// Source descriptor — 'INVENTORY' for offline inventory adjustments (Story 13.7).
  TextColumn get source => text().nullable()();

  /// Inventory session ID for traceability (Story 13.7).
  TextColumn get inventorySessionId => text().nullable()();

  BoolColumn get synced => boolean().withDefault(const Constant(false))();
  DateTimeColumn get syncedAt => dateTime().nullable()();
  DateTimeColumn get createdAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}
