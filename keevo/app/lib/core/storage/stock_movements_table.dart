import 'package:drift/drift.dart';

/// StockMovements — immutable audit trail of stock changes.
///
/// Subject to 30-day rolling purge (only synced rows are deleted).
/// Mirrors [StockAdjustedEvent] backend domain event.
class StockMovements extends Table {
  TextColumn get id => text()();
  TextColumn get productId => text()();
  TextColumn get storeId => text()();

  /// Movement type: 'SALE' | 'PURCHASE' | 'TRANSFER_IN' | 'TRANSFER_OUT' | 'ADJUSTMENT'
  TextColumn get type => text()();

  /// Signed quantity: positive = stock in, negative = stock out
  IntColumn get quantityDelta => integer()();

  TextColumn get actorId => text()();
  TextColumn get reason => text().nullable()();

  BoolColumn get synced => boolean().withDefault(const Constant(false))();
  DateTimeColumn get syncedAt => dateTime().nullable()();
  DateTimeColumn get createdAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}
