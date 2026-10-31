import 'package:drift/drift.dart';

import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/storage/search_fold.dart';
import '../../domain/model/store_product_stock_model.dart';
import '../../domain/model/store_stock_summary_model.dart';

/// LocalMultiStoreStockDataSource — Drift-backed multi-store stock aggregation.
///
/// Uses [customSelect] with raw SQL for multi-table JOINs across stores,
/// stock_levels, and products tables.
/// Story 3.2.
class LocalMultiStoreStockDataSource {
  final AppDatabase _db;

  LocalMultiStoreStockDataSource(this._db);

  /// Effective low-stock threshold of a product in a store: the product-wide
  /// threshold (server `products.minimum_threshold`, applies even where the
  /// product has no stock_levels row), else the legacy per-row one, else 0.
  static const effectiveThresholdSql =
      'COALESCE(NULLIF(p.minimum_threshold, 0), sl.minimum_threshold, 0)';

  /// Overview SQL: one row per active store with aggregated stock stats.
  /// Products come from the catalogue (non-archived) and are joined to the
  /// store's stock levels. J29: low_stock_count (Catalogue badge) counts only
  /// products stocked in the store (a stock row exists) at or below the
  /// threshold, negative included — as on the server. A product never stocked
  /// there is listed at 0 in the detail but is not an alert.
  /// product_count stays the number of products actually stocked there.
  static const _overviewSql = '''
    SELECT
      s.id           AS store_id,
      s.name         AS store_name,
      s.type         AS store_type,
      COUNT(DISTINCT sl.product_id)                                         AS product_count,
      COALESCE(SUM(sl.quantity * p.price), 0)                              AS total_value_xaf,
      COUNT(CASE WHEN sl.product_id IS NOT NULL
                  AND sl.quantity
                      <= COALESCE(NULLIF($effectiveThresholdSql, 0), 5)
                 THEN 1 END)                                                AS low_stock_count
    FROM stores s
    LEFT JOIN products p      ON p.archived = 0
    LEFT JOIN stock_levels sl ON sl.store_id = s.id AND sl.product_id = p.id
    WHERE s.is_active = 1
    GROUP BY s.id, s.name, s.type
    ORDER BY
      CASE WHEN s.type = 'WAREHOUSE' THEN 0 ELSE 1 END ASC,
      s.created_at ASC
  ''';

  Future<List<StoreStockSummaryModel>> getStoreOverviews() async {
    final rows = await _db.customSelect(_overviewSql).get();
    return rows
        .map((r) => StoreStockSummaryModel(
              storeId: r.read<String>('store_id'),
              storeName: r.read<String>('store_name'),
              storeType: r.read<String>('store_type'),
              productCount: r.read<int>('product_count'),
              totalValueXaf: r.read<int>('total_value_xaf'),
              lowStockCount: r.read<int>('low_stock_count'),
            ))
        .toList();
  }

  Future<List<StoreProductStockModel>> getStoreStockDetail(
    String storeId, {
    int page = 0,
    int size = 25,
    bool sortLowFirst = true,
  }) async {
    final offset = page * size;
    // Full expressions, not the output aliases: `minimum_threshold` exists on
    // both joined tables and would be ambiguous in ORDER BY.
    const qty = 'MAX(COALESCE(sl.quantity, 0))';
    const threshold = 'MAX($effectiveThresholdSql)';
    final orderClause = sortLowFirst
        ? 'CASE WHEN $qty <= COALESCE(NULLIF($threshold, 0), 5) AND $qty > 0 THEN 0 '
            'WHEN $qty <= 0 THEN 1 ELSE 2 END ASC, p.name ASC'
        : 'p.name ASC';

    // Starts from the catalogue: a product never stocked in this store has no
    // stock_levels row and is listed with quantity 0 instead of being absent.
    final sql = '''
      SELECT
        p.id    AS product_id,
        p.name  AS product_name,
        sl.variant_id,
        ?       AS store_id,
        MAX(COALESCE(sl.quantity, 0))   AS quantity,
        MAX($effectiveThresholdSql)     AS minimum_threshold
      FROM products p
      LEFT JOIN stock_levels sl ON sl.product_id = p.id AND sl.store_id = ?
      WHERE p.archived = 0
      GROUP BY p.id, p.name, sl.variant_id
      ORDER BY $orderClause
      LIMIT $size OFFSET $offset
    ''';

    final rows = await _db.customSelect(sql, variables: [
      Variable.withString(storeId),
      Variable.withString(storeId),
    ]).get();
    return rows.map(_mapEntry).toList();
  }

  /// Cross-store search: starts from the catalogue so a product never stocked
  /// anywhere is still found — one entry per active store, quantity 0 where
  /// the product has no stock_levels row.
  Future<List<StoreProductStockModel>> searchAcrossStores(String query) async {
    if (query.trim().isEmpty) return [];
    // v1s-16-16: case- and accent-insensitive (shared Unicode fold).
    final sql = '''
      SELECT
        p.id    AS product_id,
        p.name  AS product_name,
        sl.variant_id,
        s.id    AS store_id,
        MAX(COALESCE(sl.quantity, 0))   AS quantity,
        MAX($effectiveThresholdSql)     AS minimum_threshold
      FROM products p
      JOIN stores s ON s.is_active = 1
      LEFT JOIN stock_levels sl ON sl.product_id = p.id AND sl.store_id = s.id
      WHERE ${foldedSqlRaw('p.name')} LIKE ? ESCAPE '\\' AND p.archived = 0
      GROUP BY p.id, p.name, sl.variant_id, s.id
      ORDER BY p.name ASC, s.name ASC
    ''';
    final rows = await _db.customSelect(
      sql,
      variables: [Variable.withString(likeContainsFolded(query))],
    ).get();
    return rows.map(_mapEntry).toList();
  }

  StoreProductStockModel _mapEntry(QueryRow r) {
    final qty = r.read<int>('quantity');
    final threshold = r.read<int>('minimum_threshold');
    // Zero or negative stock is a rupture (same rule as the server).
    final isCritical = qty <= 0;
    final isLow = threshold > 0 && qty > 0 && qty <= threshold;
    final status = isCritical ? 'CRITIQUE' : (isLow ? 'BAS' : 'NORMAL');
    return StoreProductStockModel(
      productId: r.read<String>('product_id'),
      productName: r.read<String>('product_name'),
      variantId: r.readNullable<String>('variant_id'),
      storeId: r.read<String>('store_id'),
      quantity: qty,
      minimumThreshold: threshold,
      status: status,
      isLow: isLow,
      isCritical: isCritical,
    );
  }

  /// Upserts stock levels received from a remote fetch into the local SQLite cache.
  ///
  /// Updates the row if (product_id, store_id) already exists, otherwise inserts
  /// a new row with a deterministic composite ID.
  /// Called by [MultiStoreStockRepositoryImpl] when online.
  Future<void> upsertStockLevels(
      String storeId, List<StoreProductStockModel> entries) async {
    await _db.transaction(() async {
      for (final e in entries) {
        final updated = await (_db.update(_db.stockLevels)
              ..where((t) =>
                  t.productId.equals(e.productId) & t.storeId.equals(storeId)))
            .write(StockLevelsCompanion(
          quantity: Value(e.quantity),
          minimumThreshold: Value(e.minimumThreshold),
          updatedAt: Value(DateTime.now()),
        ));

        // A product never stocked in this store comes back from the server
        // at quantity 0 without a stock_levels row: no row is created for it
        // (the views already read it as 0), so the local cache stays truthful.
        if (updated == 0 && e.quantity != 0) {
          await _db.into(_db.stockLevels).insert(StockLevelsCompanion.insert(
                id: 'sync_${e.productId}_$storeId',
                productId: e.productId,
                storeId: storeId,
                quantity: e.quantity,
                minimumThreshold: Value(e.minimumThreshold),
                updatedAt: DateTime.now(),
              ));
        }
      }
    });
  }
}
