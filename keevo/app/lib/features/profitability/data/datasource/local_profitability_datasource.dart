import 'package:drift/drift.dart';

import '../../../../core/storage/app_database.dart';
import '../../domain/model/product_profitability_model.dart';
import '../../domain/model/seller_leaderboard_model.dart';

/// SQL expression giving the shop's local day (`YYYY-MM-DD`) of a Drift
/// DateTime column. Drift stores local times as `…T00:30:00.000 +01:00`, and
/// SQLite's date()/strftime() would convert that to UTC (previous day); the
/// written wall-clock date is the local one. Rows stored in UTC (`…Z`) are
/// converted with 'localtime'. Rows without offset are already local.
String _localDay(String column) =>
    "(CASE WHEN $column LIKE '%Z' THEN date($column, 'localtime') "
    "ELSE substr($column, 1, 10) END)";

/// `YYYY-MM-DD` of a local date, comparable with [_localDay].
String _ymd(DateTime d) => '${d.year.toString().padLeft(4, '0')}-'
    '${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

/// LocalProfitabilityDatasource — Drift SQL aggregation queries for profitability.
///
/// Mirrors the SQL logic of JdbcProfitabilityRepository (backend) but runs
/// against the local SQLite (SQLCipher) database.
/// Column name: sale_items.unit_price (Drift) = sale_items.applied_unit_price (PostgreSQL).
///
/// Story 7.4 — Task 15.2.
class LocalProfitabilityDatasource {
  final AppDatabase _db;

  LocalProfitabilityDatasource(this._db);

  // ── Product profitability list ────────────────────────────────────────────

  Future<List<ProductProfitabilityEntry>> getProductProfitability({
    required DateTime from,
    required DateTime to,
    String sort = 'MARGIN_PCT_DESC',
    String? storeId,
  }) async {
    final toExclusive = DateTime(to.year, to.month, to.day + 1);
    final storeFilter = storeId != null ? 'AND s.store_id = ?' : '';
    final vars = <Variable>[
      Variable.withString('COMPLETED'),
      Variable.withDateTime(from),
      Variable.withDateTime(toExclusive),
      if (storeId != null) Variable.withString(storeId),
    ];

    final rows = await _db.customSelect(
      'SELECT si.product_id, p.name AS product_name, c.name AS category_name, '
      '  p.buy_price, p.transport_cost, '
      '  CAST(SUM(si.quantity) AS INTEGER) AS units_sold, '
      '  CAST(SUM(si.unit_price * si.quantity) AS INTEGER) AS total_revenue, '
      '  CAST(SUM((p.buy_price + p.transport_cost) * si.quantity) AS INTEGER) AS total_cost '
      'FROM sale_items si '
      'JOIN sales s ON s.id = si.sale_id '
      'JOIN products p ON p.id = si.product_id '
      'LEFT JOIN categories c ON c.id = p.category_id '
      'WHERE s.status = ? '
      '  AND s.occurred_at >= ? AND s.occurred_at < ? '
      '  $storeFilter '
      'GROUP BY si.product_id, p.name, c.name, p.buy_price, p.transport_cost',
      variables: vars,
    ).get();

    final entries = rows.map((row) {
      final unitsSold = row.read<int>('units_sold');
      final totalRevenue = row.read<int>('total_revenue');
      final totalCost = row.read<int>('total_cost');
      final grossMarginXaf = totalRevenue - totalCost;
      final marginPercent =
          totalCost == 0 ? 0.0 : (grossMarginXaf / totalCost) * 100.0;
      final isLoss = grossMarginXaf < 0;

      String marginLevel;
      if (isLoss || marginPercent < 0) {
        marginLevel = 'LOSS';
      } else if (marginPercent < 10) {
        marginLevel = 'LOW';
      } else if (marginPercent < 20) {
        marginLevel = 'MODERATE';
      } else {
        marginLevel = 'PROFITABLE';
      }

      return ProductProfitabilityEntry(
        productId: row.read<String>('product_id'),
        productName: row.read<String>('product_name'),
        categoryName: row.readNullable<String>('category_name'),
        unitsSold: unitsSold,
        totalRevenue: totalRevenue,
        totalCost: totalCost,
        grossMarginXaf: grossMarginXaf,
        marginPercent: marginPercent,
        isLoss: isLoss,
        storeId: storeId,
        marginLevel: marginLevel,
      );
    }).toList();

    return entries;
  }

  // ── Product profitability detail ──────────────────────────────────────────

  Future<ProductProfitabilityDetail?> getProductProfitabilityDetail({
    required String productId,
    required DateTime from,
    required DateTime to,
    String? storeId,
  }) async {
    final toExclusive = DateTime(to.year, to.month, to.day + 1);
    final storeFilter = storeId != null ? 'AND s.store_id = ? ' : '';

    // Aggregate row
    final aggRows = await _db.customSelect(
      'SELECT si.product_id, p.name AS product_name, c.name AS category_name, '
      '  p.price AS catalogue_price, p.buy_price, p.transport_cost, '
      '  CAST(SUM(si.quantity) AS INTEGER) AS units_sold, '
      '  CAST(SUM(si.unit_price * si.quantity) AS INTEGER) AS total_revenue, '
      '  CAST(SUM((p.buy_price + p.transport_cost) * si.quantity) AS INTEGER) AS total_cost, '
      '  MIN(si.unit_price) AS min_applied, '
      '  MAX(si.unit_price) AS max_applied, '
      '  CAST(AVG(si.unit_price) AS REAL) AS avg_applied '
      'FROM sale_items si '
      'JOIN sales s ON s.id = si.sale_id '
      'JOIN products p ON p.id = si.product_id '
      'LEFT JOIN categories c ON c.id = p.category_id '
      'WHERE s.status = ? AND si.product_id = ? '
      '  AND s.occurred_at >= ? AND s.occurred_at < ? '
      '$storeFilter'
      'GROUP BY si.product_id, p.name, c.name, p.price, p.buy_price, p.transport_cost',
      variables: [
        Variable.withString('COMPLETED'),
        Variable.withString(productId),
        Variable.withDateTime(from),
        Variable.withDateTime(toExclusive),
        if (storeId != null) Variable.withString(storeId),
      ],
    ).get();

    if (aggRows.isEmpty) return null;
    final row = aggRows.first;

    final totalRevenue = row.read<int>('total_revenue');
    final totalCost = row.read<int>('total_cost');
    final grossMarginXaf = totalRevenue - totalCost;
    final marginPercent =
        totalCost == 0 ? 0.0 : (grossMarginXaf / totalCost) * 100.0;
    final isLoss = grossMarginXaf < 0;

    // Sparkline: last 7 days
    final sparklineRows =
        await _getDailyMarginLast7(productId, to, storeId: storeId);

    // Top store by units
    final topStoreRows = await _db.customSelect(
      'SELECT s.store_id, st.name AS store_name, '
      '  CAST(SUM(si.quantity) AS INTEGER) AS units_sold '
      'FROM sale_items si '
      'JOIN sales s ON s.id = si.sale_id '
      'JOIN stores st ON st.id = s.store_id '
      'WHERE s.status = ? AND si.product_id = ? '
      '  AND s.occurred_at >= ? AND s.occurred_at < ? '
      '$storeFilter'
      'GROUP BY s.store_id, st.name '
      'ORDER BY units_sold DESC LIMIT 1',
      variables: [
        Variable.withString('COMPLETED'),
        Variable.withString(productId),
        Variable.withDateTime(from),
        Variable.withDateTime(toExclusive),
        if (storeId != null) Variable.withString(storeId),
      ],
    ).get();

    String? topStoreId;
    String? topStoreName;
    int? topStoreUnitsSold;
    if (topStoreRows.isNotEmpty) {
      topStoreId = topStoreRows.first.read<String>('store_id');
      topStoreName = topStoreRows.first.read<String>('store_name');
      topStoreUnitsSold = topStoreRows.first.read<int>('units_sold');
    }

    String marginLevel;
    if (isLoss || marginPercent < 0) {
      marginLevel = 'LOSS';
    } else if (marginPercent < 10) {
      marginLevel = 'LOW';
    } else if (marginPercent < 20) {
      marginLevel = 'MODERATE';
    } else {
      marginLevel = 'PROFITABLE';
    }

    return ProductProfitabilityDetail(
      productId: row.read<String>('product_id'),
      productName: row.read<String>('product_name'),
      categoryName: row.readNullable<String>('category_name'),
      unitsSold: row.read<int>('units_sold'),
      totalRevenue: totalRevenue,
      totalCost: totalCost,
      grossMarginXaf: grossMarginXaf,
      marginPercent: marginPercent,
      isLoss: isLoss,
      storeId: storeId,
      marginLevel: marginLevel,
      currentCataloguePrice: row.read<int>('catalogue_price'),
      currentBuyPrice: row.read<int>('buy_price'),
      currentTransportCost: row.read<int>('transport_cost'),
      minAppliedPrice: row.read<int>('min_applied'),
      maxAppliedPrice: row.read<int>('max_applied'),
      avgAppliedPrice: row.read<double>('avg_applied'),
      dailyMarginLast7: sparklineRows,
      topStoreId: topStoreId,
      topStoreName: topStoreName,
      topStoreUnitsSold: topStoreUnitsSold,
    );
  }

  Future<List<DailyMarginEntry>> _getDailyMarginLast7(
      String productId, DateTime endDate, {String? storeId}) async {
    final startDate = DateTime(
        endDate.year, endDate.month, endDate.day - 6);
    final toExclusive =
        DateTime(endDate.year, endDate.month, endDate.day + 1);

    final rows = await _db.customSelect(
      'SELECT ${_localDay('s.occurred_at')} AS day, '
      '  COALESCE(CAST(SUM((si.unit_price - p.buy_price - p.transport_cost) * si.quantity) AS INTEGER), 0) AS daily_margin '
      'FROM sale_items si '
      'JOIN sales s ON s.id = si.sale_id '
      'JOIN products p ON p.id = si.product_id '
      'WHERE s.status = ? AND si.product_id = ? '
      '  AND s.occurred_at >= ? AND s.occurred_at < ? '
      '${storeId != null ? 'AND s.store_id = ? ' : ''}'
      'GROUP BY ${_localDay('s.occurred_at')} ORDER BY day ASC',
      variables: [
        Variable.withString('COMPLETED'),
        Variable.withString(productId),
        Variable.withDateTime(startDate),
        Variable.withDateTime(toExclusive),
        if (storeId != null) Variable.withString(storeId),
      ],
    ).get();

    return rows
        .map((r) => DailyMarginEntry(
              date: r.read<String>('day'),
              marginXaf: r.read<int>('daily_margin'),
            ))
        .toList();
  }

  // ── Store performance ─────────────────────────────────────────────────────

  Future<List<StorePerformanceEntry>> getStorePerformance({
    required DateTime from,
    required DateTime to,
    String metric = 'CA',
  }) async {
    final toExclusive = DateTime(to.year, to.month, to.day + 1);

    final rows = await _db.customSelect(
      'SELECT s.store_id, st.name AS store_name, '
      '  COALESCE(CAST(SUM(s.total_amount) AS INTEGER), 0) AS total_revenue, '
      '  CAST(COUNT(s.id) AS INTEGER) AS sales_count, '
      '  COALESCE(CAST(SUM(s.total_amount) / NULLIF(COUNT(s.id), 0) AS INTEGER), 0) AS avg_basket '
      'FROM sales s '
      'JOIN stores st ON st.id = s.store_id '
      'WHERE s.status = ? '
      '  AND s.occurred_at >= ? AND s.occurred_at < ? '
      'GROUP BY s.store_id, st.name',
      variables: [
        Variable.withString('COMPLETED'),
        Variable.withDateTime(from),
        Variable.withDateTime(toExclusive),
      ],
    ).get();

    if (rows.isEmpty) return [];

    // Top product per store
    final topProductRows = await _db.customSelect(
      'SELECT s.store_id, si.product_name, CAST(SUM(si.quantity) AS INTEGER) AS units '
      'FROM sale_items si '
      'JOIN sales s ON s.id = si.sale_id '
      'WHERE s.status = ? '
      '  AND s.occurred_at >= ? AND s.occurred_at < ? '
      'GROUP BY s.store_id, si.product_name',
      variables: [
        Variable.withString('COMPLETED'),
        Variable.withDateTime(from),
        Variable.withDateTime(toExclusive),
      ],
    ).get();

    final topProductMap = <String, String?>{};
    final storeProductUnits = <String, Map<String, int>>{};
    for (final r in topProductRows) {
      final sId = r.read<String>('store_id');
      storeProductUnits
          .putIfAbsent(sId, () => {})[r.read<String>('product_name')] =
          r.read<int>('units');
    }
    for (final sId in storeProductUnits.keys) {
      final byUnits = storeProductUnits[sId]!;
      topProductMap[sId] = byUnits.entries
          .reduce((a, b) => a.value >= b.value ? a : b)
          .key;
    }

    // Preceding period for delta
    final periodLength = toExclusive.difference(from).inDays;
    // Previous window = the [periodLength] days just before [from]; its end
    // is exclusive at [from] so the two periods never share a day.
    final prevFrom = from.subtract(Duration(days: periodLength));
    final prevToExclusive = from;

    final prevRows = await _db.customSelect(
      'SELECT s.store_id, COALESCE(CAST(SUM(s.total_amount) AS INTEGER), 0) AS total_revenue '
      'FROM sales s '
      'WHERE s.status = ? '
      '  AND s.occurred_at >= ? AND s.occurred_at < ? '
      'GROUP BY s.store_id',
      variables: [
        Variable.withString('COMPLETED'),
        Variable.withDateTime(prevFrom),
        Variable.withDateTime(prevToExclusive),
      ],
    ).get();

    final prevRevenueMap = <String, int>{
      for (final r in prevRows)
        r.read<String>('store_id'): r.read<int>('total_revenue'),
    };

    List<Map<String, dynamic>> storeData = rows.map((row) {
      final sId = row.read<String>('store_id');
      final current = row.read<int>('total_revenue');
      final prev = prevRevenueMap[sId] ?? 0;
      // A33: no sales in the preceding period → no comparison (null).
      final double? delta =
          prev == 0 ? null : (current - prev) / prev * 100.0;
      return {
        'storeId': sId,
        'storeName': row.read<String>('store_name'),
        'totalRevenue': current,
        'salesCount': row.read<int>('sales_count'),
        'averageBasket': row.read<int>('avg_basket'),
        'topProductName': topProductMap[sId],
        'deltaPercent': delta,
      };
    }).toList();

    // Sort by metric
    switch (metric.toUpperCase()) {
      case 'SALES_COUNT':
        storeData.sort((a, b) =>
            (b['salesCount'] as int).compareTo(a['salesCount'] as int));
      case 'AVG_BASKET':
        storeData.sort((a, b) =>
            (b['averageBasket'] as int).compareTo(a['averageBasket'] as int));
      case 'CA':
      default:
        storeData.sort((a, b) =>
            (b['totalRevenue'] as int).compareTo(a['totalRevenue'] as int));
    }

    return storeData.indexed.map((pair) {
      final idx = pair.$1;
      final s = pair.$2;
      return StorePerformanceEntry(
        rank: idx + 1,
        storeId: s['storeId'] as String,
        storeName: s['storeName'] as String,
        totalRevenue: s['totalRevenue'] as int,
        salesCount: s['salesCount'] as int,
        averageBasket: s['averageBasket'] as int,
        topProductName: s['topProductName'] as String?,
        deltaPercent: s['deltaPercent'] as double?,
      );
    }).toList();
  }

  // ── Seller leaderboard (v1s-14-2) ─────────────────────────────────────────

  /// Offline mirror of `JdbcSellerLeaderboardRepository`: COMPLETED sales
  /// grouped by seller (`sales.employee_id` = user id) whose local day is in
  /// `[from, to]` (= `[from 00:00, to+1 00:00)` local),
  /// optionally scoped to [storeId], plus the active employees (same scope)
  /// without sale (0/0). Name = latest employee row of that user, else
  /// « Propriétaire » when `users.role` is OWNER, else « Vendeur inconnu ».
  /// Returned unranked (rank 0): the caller ranks with `rankSellersBy`.
  Future<List<SellerLeaderboardEntry>> getSellerLeaderboard({
    required DateTime from,
    required DateTime to,
    String? storeId,
  }) async {
    // Compare local days (not raw text): rows synced in UTC (`…Z`) would
    // otherwise be bucketed on the wrong side of the [from, to+1) window.
    final rows = await _db.customSelect(
      'WITH sold AS ('
      '  SELECT s.employee_id AS seller_id, COUNT(s.id) AS sales_count, '
      '    COALESCE(SUM(s.total_amount), 0) AS total_revenue '
      '  FROM sales s '
      '  WHERE s.status = ? '
      '    AND ${_localDay('s.occurred_at')} >= ? '
      '    AND ${_localDay('s.occurred_at')} <= ? '
      '  ${storeId != null ? 'AND s.store_id = ? ' : ''}'
      '  GROUP BY s.employee_id'
      '), sellers AS ('
      '  SELECT seller_id, sales_count, total_revenue FROM sold '
      '  UNION ALL '
      '  SELECT DISTINCT e.user_id, 0, 0 FROM employees e '
      "  WHERE e.status = 'ACTIVE' "
      '  ${storeId != null ? 'AND e.store_id = ? ' : ''}'
      '    AND NOT EXISTS (SELECT 1 FROM sold WHERE sold.seller_id = e.user_id)'
      ') '
      'SELECT x.seller_id, '
      '  CAST(x.sales_count AS INTEGER) AS sales_count, '
      '  CAST(x.total_revenue AS INTEGER) AS total_revenue, '
      "  (SELECT NULLIF(TRIM(COALESCE(emp.first_name, '') || ' ' || "
      "     COALESCE(emp.last_name, '')), '') "
      '   FROM employees emp WHERE emp.user_id = x.seller_id '
      '   ORDER BY emp.created_at DESC LIMIT 1) AS employee_name, '
      '  (SELECT u.role FROM users u WHERE u.id = x.seller_id) AS role '
      'FROM sellers x',
      variables: [
        Variable.withString('COMPLETED'),
        Variable.withString(_ymd(from)),
        Variable.withString(_ymd(to)),
        if (storeId != null) Variable.withString(storeId),
        if (storeId != null) Variable.withString(storeId),
      ],
    ).get();

    return rows.map((r) {
      final count = r.read<int>('sales_count');
      final revenue = r.read<int>('total_revenue');
      final isOwner = r.readNullable<String>('role') == 'OWNER';
      return SellerLeaderboardEntry(
        rank: 0,
        sellerId: r.read<String>('seller_id'),
        sellerName: r.readNullable<String>('employee_name') ??
            (isOwner ? 'Propriétaire' : 'Vendeur inconnu'),
        owner: isOwner,
        salesCount: count,
        totalRevenue: revenue,
        averageBasket: count == 0 ? 0 : revenue ~/ count,
      );
    }).toList();
  }
}
