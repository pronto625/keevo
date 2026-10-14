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

  /// Runs [action] in one Drift transaction. Writes made through the same
  /// database inside it (sync_queue included) commit or roll back together.
  Future<T> transaction<T>(Future<T> Function() action) =>
      _db.transaction(action);

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
  ///
  /// The scope is driven by the products (every non-archived product, or those
  /// of the selected categories for PARTIAL), as on the server: a product never
  /// stocked in the store has no stock_levels row yet and is counted with an
  /// expected quantity of 0 (variant null). Products with rows keep one row per
  /// stock level (product/variant).
  Future<List<InventoryProductRowModel>> buildOfflineProductRows(
    String sessionId,
    String storeId,
    List<String>? categoryIds,
  ) async {
    // 1. Load the products of the scope (archived ones excluded)
    final productQuery = _db.select(_db.products)
      ..where((p) => p.archived.equals(false));
    if (categoryIds != null && categoryIds.isNotEmpty) {
      productQuery.where((p) => p.categoryId.isIn(categoryIds));
    }
    final products = await productQuery.get();
    if (products.isEmpty) return [];

    // 2. Stock levels of the store, grouped by product
    final stockRows = await (_db.select(_db.stockLevels)
          ..where((sl) => sl.storeId.equals(storeId)))
        .get();
    final stocksByProduct = <String, List<StockLevel>>{};
    for (final sl in stockRows) {
      stocksByProduct.putIfAbsent(sl.productId, () => []).add(sl);
    }

    // 3. Load existing counts for the session
    final counts = await findBySessionId(sessionId);
    final countLookup = <String, InventoryCountModel>{};
    for (final c in counts) {
      countLookup['${c.productId}:${c.variantId ?? 'null'}'] = c;
    }

    // 4. Build product rows
    InventoryProductRowModel row(Product product, String? variantId, int qty) {
      final count = countLookup['${product.id}:${variantId ?? 'null'}'];
      return InventoryProductRowModel(
        productId: product.id,
        productName: product.name,
        sku: product.sku.isNotEmpty ? product.sku : null,
        photoUrl: product.photoUrl,
        variantId: variantId,
        variantLabel: null, // Not available in Drift stock_levels
        theoreticalQty: qty,
        physicalQty: count?.physical,
        ecart: count?.ecart,
      );
    }

    final result = <InventoryProductRowModel>[];
    for (final product in products) {
      final productStocks = stocksByProduct[product.id];
      if (productStocks == null || productStocks.isEmpty) {
        result.add(row(product, null, 0)); // never stocked here → expected 0
        continue;
      }
      for (final stock in productStocks) {
        result.add(row(product, stock.variantId, stock.quantity));
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
