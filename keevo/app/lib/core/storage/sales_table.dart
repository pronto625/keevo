import 'package:drift/drift.dart';

/// Sales — POS transaction records.
///
/// Subject to 30-day rolling purge (only synced rows are deleted).
/// [totalAmount] is in XAF (integer — no decimals).
class Sales extends Table {
  TextColumn get id => text()();
  TextColumn get storeId => text()();
  TextColumn get employeeId => text()();

  /// Total XAF amount (integer)
  IntColumn get totalAmount => integer()();

  /// Payment mode: 'CASH' | 'MOBILE_MONEY'
  TextColumn get paymentMode => text()();

  BoolColumn get synced => boolean().withDefault(const Constant(false))();
  DateTimeColumn get syncedAt => dateTime().nullable()();
  DateTimeColumn get createdAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}
