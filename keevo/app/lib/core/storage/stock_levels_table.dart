import 'package:drift/drift.dart';

/// StockLevels — per-store denormalized stock quantity.
///
/// Denormalized for offline POS performance: avoids joins at sale time.
class StockLevels extends Table {
  TextColumn get id => text()();
  TextColumn get productId => text()();
  TextColumn get storeId => text()();
  IntColumn get quantity => integer()();
  DateTimeColumn get updatedAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}
