import 'package:drift/drift.dart';

/// Products — local product catalogue.
///
/// All monetary values use [IntColumn] (XAF — integer only, no decimals).
/// All IDs use [TextColumn] (UUID v4 strings — generated client-side).
class Products extends Table {
  TextColumn get id => text()();
  TextColumn get name => text()();
  TextColumn get categoryId => text().nullable()();

  /// Price in XAF (integer — mirrors Money value object on backend)
  IntColumn get price => integer()();

  /// Buy price in XAF
  IntColumn get buyPrice => integer()();

  IntColumn get stockQuantity => integer()();
  TextColumn get storeId => text()();
  BoolColumn get isActive => boolean().withDefault(const Constant(true))();
  DateTimeColumn get createdAt => dateTime()();
  DateTimeColumn get updatedAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}
