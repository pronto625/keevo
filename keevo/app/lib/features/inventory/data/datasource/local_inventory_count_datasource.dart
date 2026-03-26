import 'package:drift/drift.dart';

import '../../../../core/storage/app_database.dart';
import '../../domain/model/inventory_count_model.dart';
import '../../domain/model/inventory_product_row_model.dart';

/// LocalInventoryCountDataSource — Drift-backed offline store for inventory counts.
///
/// Story 6.2.
class LocalInventoryCountDataSource {
  final AppDatabase _db;

  const LocalInventoryCountDataSource(this._db);

  /// Upsert a count row — conflict by primary key (id).
  /// Also handles logical upsert by sessionId+productId+variantId.
  Future<void> upsert(InventoryCountModel count) async {
    // Check for existing count with same session+product+variant
    final existing = await _findBySessionProductVariant(
      count.sessionId,
      count.productId,
      count.variantId,
    );

    if (existing != null && existing.id != count.id) {
      // Update the existing row instead of inserting a duplicate
      await (_db.update(_db.inventoryCounts)
            ..where((c) => c.id.equals(existing.id)))
          .write(InventoryCountsCompanion(
        physical: Value(count.physical),
        countedBy: Value(count.countedBy),
        countedAt: Value(count.countedAt),
        updatedAt: Value(count.updatedAt),
        synced: Value(count.synced),
      ));
      return;
    }

    await _db.into(_db.inventoryCounts).insertOnConflictUpdate(
      InventoryCountsCompanion.insert(
        id: count.id,
        sessionId: count.sessionId,
        productId: count.productId,
        variantId: Value(count.variantId),
        productName: count.productName,
        variantLabel: Value(count.variantLabel),
        theoretical: count.theoretical,
        physical: Value(count.physical),
        countedBy: Value(count.countedBy),
        countedAt: Value(count.countedAt),
        updatedAt: count.updatedAt,
        synced: Value(count.synced),
      ),
    );
  }

  /// Find all counts for a session.
  Future<List<InventoryCountModel>> findBySessionId(String sessionId) async {
    final query = _db.select(_db.inventoryCounts)
      ..where((c) => c.sessionId.equals(sessionId));
    final rows = await query.get();
    return rows.map(_toModel).toList();
  }

  /// Get IDs of unsynced counts (pending push).
  Future<List<String>> getPendingIds() async {
    final query = _db.select(_db.inventoryCounts)
      ..where((c) => c.synced.equals(false));
    final rows = await query.get();
    return rows.map((r) => r.id).toList();
  }

  /// Build product rows offline from local tables:
  /// Products + StockLevels + existing InventoryCounts.
  Future<List<InventoryProductRowModel>> buildOfflineProductRows(
    String sessionId,
    String storeId,
    List<String>? categoryIds,
  ) async {
    // 1. Get stock levels for the store
    final stockQuery = _db.select(_db.stockLevels)
      ..where((sl) => sl.storeId.equals(storeId));
    final stockRows = await stockQuery.get();
    if (stockRows.isEmpty) return [];

    // 2. Collect product IDs from stock
    final productIds = stockRows.map((s) => s.productId).toSet().toList();

    // 3. Load products (filter out archived)
    final productQuery = _db.select(_db.products)
      ..where(
          (p) => p.id.isIn(productIds) & p.archived.equals(false));
    var products = await productQuery.get();

    // 4. Filter by categories if PARTIAL scope
    if (categoryIds != null && categoryIds.isNotEmpty) {
      products = products
          .where((p) =>
              p.categoryId != null && categoryIds.contains(p.categoryId))
          .toList();
    }

    // 5. Load existing counts for the session
    final counts = await findBySessionId(sessionId);
    final countLookup = <String, InventoryCountModel>{};
    for (final c in counts) {
      countLookup['${c.productId}:${c.variantId ?? 'null'}'] = c;
    }

    // 6. Build stock level lookup: productId:variantId → quantity
    final stockLookup = <String, int>{};
    for (final sl in stockRows) {
      stockLookup['${sl.productId}:${sl.variantId ?? 'null'}'] = sl.quantity;
    }

    // 7. Build product rows
    final result = <InventoryProductRowModel>[];
    for (final product in products) {
      // Find stock levels for this product
      final productStocks =
          stockRows.where((sl) => sl.productId == product.id).toList();

      for (final stock in productStocks) {
        final key = '${product.id}:${stock.variantId ?? 'null'}';
        final count = countLookup[key];
        result.add(InventoryProductRowModel(
          productId: product.id,
          productName: product.name,
          sku: product.sku.isNotEmpty ? product.sku : null,
          photoUrl: product.photoUrl,
          variantId: stock.variantId,
          variantLabel: null, // Not available in Drift stock_levels
          theoreticalQty: stock.quantity,
          physicalQty: count?.physical,
          ecart: count?.ecart,
        ));
      }
    }

    // Sort alphabetically by product name
    result.sort(
        (a, b) => a.productName.compareTo(b.productName));
    return result;
  }

  /// Find a count by session + product + variant (logical unique).
  Future<InventoryCount?> _findBySessionProductVariant(
    String sessionId,
    String productId,
    String? variantId,
  ) async {
    final query = _db.select(_db.inventoryCounts)
      ..where((c) => c.sessionId.equals(sessionId) & c.productId.equals(productId));

    if (variantId == null) {
      query.where((c) => c.variantId.isNull());
    } else {
      query.where((c) => c.variantId.equals(variantId));
    }

    final rows = await query.get();
    return rows.isEmpty ? null : rows.first;
  }

  InventoryCountModel _toModel(InventoryCount row) {
    return InventoryCountModel(
      id: row.id,
      sessionId: row.sessionId,
      productId: row.productId,
      variantId: row.variantId,
      productName: row.productName,
      variantLabel: row.variantLabel,
      theoretical: row.theoretical,
      physical: row.physical,
      countedBy: row.countedBy,
      countedAt: row.countedAt,
      updatedAt: row.updatedAt,
      synced: row.synced,
    );
  }
}
