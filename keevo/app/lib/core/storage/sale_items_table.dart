import 'package:drift/drift.dart';

/// SaleItems — line items belonging to a [Sales] record.
///
/// De-normalised snapshot of product at time of sale (name, unitPrice)
/// so the record is self-contained even if the product is later modified.
class SaleItems extends Table {
  TextColumn get id => text()();
  TextColumn get saleId => text()();
  TextColumn get productId => text()();

  /// Optional variant ID — nullable. Added in Story 4.1.
  TextColumn get variantId => text().nullable()();

  /// Snapshot of the product name at sale time
  TextColumn get productName => text()();

  /// Unit price at sale time in XAF
  IntColumn get unitPrice => integer()();

  /// Catalogue price at sale time (Story 4.2). For audit: delta = catalogueUnitPrice - unitPrice.
  IntColumn get catalogueUnitPrice => integer().withDefault(const Constant(0))();

  IntColumn get quantity => integer()();

  /// Pre-computed subtotal in XAF (unitPrice × quantity)
  IntColumn get subtotal => integer()();

  DateTimeColumn get createdAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}
