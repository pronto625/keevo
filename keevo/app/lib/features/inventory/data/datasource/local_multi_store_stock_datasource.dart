import 'package:drift/drift.dart';

import '../../../../core/storage/app_database.dart';
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

  /// Overview SQL: one row per active store with aggregated stock stats.
  /// Note: minimum_threshold is on stock_levels (sl), not products (p).
  static const _overviewSql = '''
    SELECT
      s.id           AS store_id,
      s.name         AS store_name,
      s.type         AS store_type,
      COUNT(DISTINCT sl.product_id)                                         AS product_count,
      COALESCE(SUM(sl.quantity * p.price), 0)                              AS total_value_xaf,
      COUNT(CASE WHEN sl.minimum_threshold > 0
                  AND sl.quantity <= sl.minimum_threshold THEN 1 END)      AS low_stock_count
    FROM stores s
    LEFT JOIN stock_levels sl ON sl.store_id = s.id
    LEFT JOIN products p      ON p.id = sl.product_id AND p.archived = 0
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
    final orderClause = sortLowFirst
        ? 'CASE WHEN sl.minimum_threshold > 0 AND sl.quantity > 0 AND sl.quantity <= sl.minimum_threshold THEN 0 '
            'WHEN sl.quantity = 0 THEN 1 ELSE 2 END ASC, p.name ASC'
        : 'p.name ASC';

    final sql = '''
      SELECT
        p.id    AS product_id,
        p.name  AS product_name,
        sl.variant_id,
        sl.store_id,
        sl.quantity,
        sl.minimum_threshold
      FROM stock_levels sl
      JOIN products p ON p.id = sl.product_id
      WHERE sl.store_id = ? AND p.archived = 0
      ORDER BY $orderClause
      LIMIT $size OFFSET $offset
    ''';

    final rows = await _db
        .customSelect(sql, variables: [Variable.withString(storeId)]).get();
    return rows.map(_mapEntry).toList();
  }

  Future<List<StoreProductStockModel>> searchAcrossStores(String query) async {
    if (query.trim().isEmpty) return [];
    const sql = '''
      SELECT
        p.id    AS product_id,
        p.name  AS product_name,
        sl.variant_id,
        sl.store_id,
        sl.quantity,
        sl.minimum_threshold
      FROM stock_levels sl
      JOIN products p ON p.id = sl.product_id
      JOIN stores   s ON s.id = sl.store_id AND s.is_active = 1
      WHERE p.name LIKE ? AND p.archived = 0
      ORDER BY p.name ASC, s.name ASC
    ''';
    final rows = await _db.customSelect(
      sql,
      variables: [Variable.withString('%$query%')],
    ).get();
    return rows.map(_mapEntry).toList();
  }

  StoreProductStockModel _mapEntry(QueryRow r) {
    final qty = r.read<int>('quantity');
    final threshold = r.read<int>('minimum_threshold');
    final isCritical = qty == 0;
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

        if (updated == 0) {
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
