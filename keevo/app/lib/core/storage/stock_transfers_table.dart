import 'package:drift/drift.dart';

/// Drift table definition for stock_transfers — Story 3.3.
///
/// Records every inter-store stock transfer with its status.
/// Status values: COMPLETED | PENDING_SYNC | CONFLICT
class StockTransfers extends Table {
  TextColumn get id => text()();
  TextColumn get sourceStoreId => text()();
  TextColumn get destinationStoreId => text()();
  TextColumn get productId => text()();
  TextColumn get variantId => text().nullable()();
  IntColumn get quantity => integer()();
  TextColumn get actorId => text()();
  DateTimeColumn get occurredAt => dateTime()();
  TextColumn get status =>
      text().withDefault(const Constant('COMPLETED'))();
  TextColumn get notes => text().nullable()();
  TextColumn get sourceStoreName =>
      text().withDefault(const Constant(''))();
  TextColumn get destinationStoreName =>
      text().withDefault(const Constant(''))();
  TextColumn get productName =>
      text().withDefault(const Constant(''))();
  TextColumn get variantLabel =>
      text().withDefault(const Constant(''))();

  @override
  Set<Column> get primaryKey => {id};
}
