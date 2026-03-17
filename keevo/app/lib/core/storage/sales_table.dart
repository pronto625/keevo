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

  /// Discount applied to the entire order (XAF integer, default 0). Story 4.2.
  IntColumn get discountAmount => integer().withDefault(const Constant(0))();

  /// Payment mode: 'CASH' | 'MOBILE_MONEY'
  TextColumn get paymentMode => text()();

  /// Optional link to a client — nullable (walk-in sales have no client).
  /// Added in Story 2.5 — Gestion Clients & Fournisseurs.
  TextColumn get clientId => text().nullable()();

  /// Sale status: 'COMPLETED' | 'CANCELLED'. Added in Story 4.1.
  TextColumn get status => text().withDefault(const Constant('COMPLETED')).nullable()();

  /// Exact timestamp of sale occurrence. Added in Story 4.1.
  DateTimeColumn get occurredAt => dateTime().nullable()();

  BoolColumn get synced => boolean().withDefault(const Constant(false))();
  DateTimeColumn get syncedAt => dateTime().nullable()();
  DateTimeColumn get createdAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}
