import 'package:drift/drift.dart';

/// Products — local product catalogue.
///
/// All monetary values use [IntColumn] (XAF — integer only, no decimals).
/// All IDs use [TextColumn] (UUID v4 strings — generated client-side).
///
/// Schema v3: added description, sku, photoUrl, archived, status.
class Products extends Table {
  TextColumn get id => text()();
  TextColumn get name => text()();
  TextColumn get description => text().nullable()();

  /// SKU reference — `KEV-XXXXXX` format (auto-generated or manual).
  TextColumn get sku => text().withDefault(const Constant(''))();

  TextColumn get categoryId => text().nullable()();

  /// Price in XAF (integer — mirrors Money value object on backend)
  IntColumn get price => integer().withDefault(const Constant(0))();

  /// Buy price in XAF
  IntColumn get buyPrice => integer().withDefault(const Constant(0))();

  /// Transport / logistics cost in XAF (added in story 2.2 — pricing engine)
  IntColumn get transportCost => integer().withDefault(const Constant(0))();

  IntColumn get stockQuantity => integer().withDefault(const Constant(0))();
  TextColumn get storeId => text().nullable()();

  /// Photo URL — local path or remote S3 URL.
  TextColumn get photoUrl => text().nullable()();

  /// Soft delete — archived products are hidden from POS but preserved.
  BoolColumn get archived => boolean().withDefault(const Constant(false))();

  /// Product lifecycle status: 'ACTIVE' (visible in POS) or 'DRAFT' (POS-created).
  TextColumn get status => text().withDefault(const Constant('ACTIVE'))();

  /// Low-stock alert threshold, product-wide (schema v31) — the server keeps it
  /// on `products.minimum_threshold`. Applies in every store, including those
  /// where the product has no stock_levels row yet. 0 = no threshold set.
  IntColumn get minimumThreshold => integer().withDefault(const Constant(0))();

  DateTimeColumn get createdAt => dateTime()();
  DateTimeColumn get updatedAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}
