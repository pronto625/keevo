import 'package:drift/drift.dart';

import '../../../../core/storage/app_database.dart';
import '../../domain/model/dashboard_snapshot.dart';

/// LocalDashboardDatasource — Drift SQL aggregation queries for dashboard.
///
/// GoF Builder: getDashboardSnapshot() orchestrates multiple async queries
/// into a single DashboardSnapshot object.
/// Story 7.1 — AC2, AC3, AC4, AC7, AC8.
class LocalDashboardDatasource {
  final AppDatabase _db;

  LocalDashboardDatasource(this._db);

  /// Today's total CA across all stores (COMPLETED sales only).
  Future<int> getTodayCA() async {
    final now = DateTime.now();
    final startOfDay = DateTime(now.year, now.month, now.day);
    final result = await _db.customSelect(
      'SELECT COALESCE(SUM(total_amount), 0) AS total FROM sales '
      'WHERE status = ? AND occurred_at >= ?',
      variables: [Variable.withString('COMPLETED'), Variable.withDateTime(startOfDay)],
    ).getSingle();
    return result.read<int>('total');
  }

  /// Yesterday's total CA across all stores.
  Future<int> getYesterdayCA() async {
    final now = DateTime.now();
    final startYesterday = DateTime(now.year, now.month, now.day - 1);
    final endYesterday = DateTime(now.year, now.month, now.day);
    final result = await _db.customSelect(
      'SELECT COALESCE(SUM(total_amount), 0) AS total FROM sales '
      'WHERE status = ? AND occurred_at >= ? AND occurred_at < ?',
      variables: [
        Variable.withString('COMPLETED'),
        Variable.withDateTime(startYesterday),
        Variable.withDateTime(endYesterday),
      ],
    ).getSingle();
    return result.read<int>('total');
  }

  /// Day-before-yesterday's total CA.
  Future<int> getDayBeforeYesterdayCA() async {
    final now = DateTime.now();
    final start = DateTime(now.year, now.month, now.day - 2);
    final end = DateTime(now.year, now.month, now.day - 1);
    final result = await _db.customSelect(
      'SELECT COALESCE(SUM(total_amount), 0) AS total FROM sales '
      'WHERE status = ? AND occurred_at >= ? AND occurred_at < ?',
      variables: [
        Variable.withString('COMPLETED'),
        Variable.withDateTime(start),
        Variable.withDateTime(end),
      ],
    ).getSingle();
    return result.read<int>('total');
  }

  /// Daily CA for the last 30 days, grouped by date.
  /// Zero-fills missing days so the chart always has 30 bars.
  Future<List<DailyCA>> getDailyCAForLast30Days() async {
    final now = DateTime.now();
    final start = DateTime(now.year, now.month, now.day - 29);
    final rows = await _db.customSelect(
      'SELECT date(occurred_at) AS day, COALESCE(SUM(total_amount), 0) AS total '
      'FROM sales WHERE status = ? AND occurred_at >= ? '
      'GROUP BY date(occurred_at) ORDER BY day ASC',
      variables: [
        Variable.withString('COMPLETED'),
        Variable.withDateTime(start),
      ],
    ).get();
    final dataMap = <String, int>{};
    for (final row in rows) {
      dataMap[row.read<String>('day')] = row.read<int>('total');
    }
    return List.generate(30, (i) {
      final d = start.add(Duration(days: i));
      final key = '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
      return DailyCA(date: d, amount: dataMap[key] ?? 0);
    });
  }

  /// Count of COMPLETED sales today.
  Future<int> getTodaySalesCount() async {
    final now = DateTime.now();
    final startOfDay = DateTime(now.year, now.month, now.day);
    final result = await _db.customSelect(
      'SELECT COUNT(*) AS cnt FROM sales '
      'WHERE status = ? AND occurred_at >= ?',
      variables: [Variable.withString('COMPLETED'), Variable.withDateTime(startOfDay)],
    ).getSingle();
    return result.read<int>('cnt');
  }

  /// Total COMPLETED sales this calendar month.
  Future<int> getMonthlyTransactionCount() async {
    final now = DateTime.now();
    final startOfMonth = DateTime(now.year, now.month, 1);
    final result = await _db.customSelect(
      'SELECT COUNT(*) AS cnt FROM sales '
      'WHERE status = ? AND occurred_at >= ?',
      variables: [Variable.withString('COMPLETED'), Variable.withDateTime(startOfMonth)],
    ).getSingle();
    return result.read<int>('cnt');
  }

  /// Total COMPLETED sales for the previous calendar month.
  Future<int> getPreviousMonthTransactionCount() async {
    final now = DateTime.now();
    final startPrev = DateTime(now.year, now.month - 1, 1);
    final endPrev = DateTime(now.year, now.month, 1);
    final result = await _db.customSelect(
      'SELECT COUNT(*) AS cnt FROM sales '
      'WHERE status = ? AND occurred_at >= ? AND occurred_at < ?',
      variables: [
        Variable.withString('COMPLETED'),
        Variable.withDateTime(startPrev),
        Variable.withDateTime(endPrev),
      ],
    ).getSingle();
    return result.read<int>('cnt');
  }

  /// Average basket this calendar month: SUM(total_amount) / COUNT.
  Future<int> getMonthlyAverageBasket() async {
    final now = DateTime.now();
    final startOfMonth = DateTime(now.year, now.month, 1);
    final result = await _db.customSelect(
      'SELECT COALESCE(SUM(total_amount), 0) AS total, COUNT(*) AS cnt FROM sales '
      'WHERE status = ? AND occurred_at >= ?',
      variables: [Variable.withString('COMPLETED'), Variable.withDateTime(startOfMonth)],
    ).getSingle();
    final total = result.read<int>('total');
    final count = result.read<int>('cnt');
    return count > 0 ? (total ~/ count) : 0;
  }

  /// Average basket for the previous calendar month.
  Future<int> getPreviousMonthAverageBasket() async {
    final now = DateTime.now();
    final startPrev = DateTime(now.year, now.month - 1, 1);
    final endPrev = DateTime(now.year, now.month, 1);
    final result = await _db.customSelect(
      'SELECT COALESCE(SUM(total_amount), 0) AS total, COUNT(*) AS cnt FROM sales '
      'WHERE status = ? AND occurred_at >= ? AND occurred_at < ?',
      variables: [
        Variable.withString('COMPLETED'),
        Variable.withDateTime(startPrev),
        Variable.withDateTime(endPrev),
      ],
    ).getSingle();
    final total = result.read<int>('total');
    final count = result.read<int>('cnt');
    return count > 0 ? (total ~/ count) : 0;
  }

  /// Count of products below minimum stock threshold across all stores.
  Future<int> getLowStockCount() async {
    final result = await _db.customSelect(
      'SELECT COUNT(DISTINCT sl.product_id) AS cnt FROM stock_levels sl '
      'JOIN products p ON p.id = sl.product_id AND p.archived = 0 '
      'WHERE sl.quantity <= COALESCE(NULLIF(sl.minimum_threshold, 0), 5)',
    ).getSingle();
    return result.read<int>('cnt');
  }

  /// Top [limit] best-selling products over the last 7 rolling days.
  Future<List<TopProduct>> getWeeklyTopProducts({int limit = 5}) async {
    final now = DateTime.now();
    // Rolling 7-day window (matches UI label "7j", avoids Monday-boundary issue)
    final startOfWeek = DateTime(now.year, now.month, now.day).subtract(const Duration(days: 6));

    // First get total weekly revenue for market share calculation
    final totalResult = await _db.customSelect(
      'SELECT COALESCE(SUM(si.subtotal), 0) AS total '
      'FROM sale_items si INNER JOIN sales s ON si.sale_id = s.id '
      'WHERE s.status = ? AND s.occurred_at >= ?',
      variables: [
        Variable.withString('COMPLETED'),
        Variable.withDateTime(startOfWeek),
      ],
    ).getSingle();
    final totalRevenue = totalResult.read<int>('total');

    final rows = await _db.customSelect(
      'SELECT si.product_id, si.product_name, '
      'SUM(si.quantity) AS units_sold, SUM(si.subtotal) AS revenue '
      'FROM sale_items si INNER JOIN sales s ON si.sale_id = s.id '
      'WHERE s.status = ? AND s.occurred_at >= ? '
      'GROUP BY si.product_id, si.product_name '
      'ORDER BY units_sold DESC LIMIT ?',
      variables: [
        Variable.withString('COMPLETED'),
        Variable.withDateTime(startOfWeek),
        Variable.withInt(limit),
      ],
    ).get();

    return rows.map((row) {
      final revenue = row.read<int>('revenue');
      return TopProduct(
        productId: row.read<String>('product_id'),
        name: row.read<String>('product_name'),
        unitsSold: row.read<int>('units_sold'),
        revenue: revenue,
        sharePercent: totalRevenue > 0 ? (revenue / totalRevenue) * 100 : 0,
      );
    }).toList();
  }

  /// Per-store overview: today CA, yesterday CA, employee count.
  Future<List<StoreOverview>> getStoreOverviews() async {
    final now = DateTime.now();
    final startToday = DateTime(now.year, now.month, now.day);
    final startYesterday = DateTime(now.year, now.month, now.day - 1);

    // Get all active stores
    final stores = await _db.customSelect(
      'SELECT id, name FROM stores WHERE is_active = 1',
    ).get();

    final overviews = <StoreOverview>[];
    for (final store in stores) {
      final storeId = store.read<String>('id');
      final storeName = store.read<String>('name');

      // Today CA for this store
      final todayResult = await _db.customSelect(
        'SELECT COALESCE(SUM(total_amount), 0) AS total FROM sales '
        'WHERE status = ? AND store_id = ? AND occurred_at >= ?',
        variables: [
          Variable.withString('COMPLETED'),
          Variable.withString(storeId),
          Variable.withDateTime(startToday),
        ],
      ).getSingle();
      final todayCA = todayResult.read<int>('total');

      // Yesterday CA for this store
      final yesterdayResult = await _db.customSelect(
        'SELECT COALESCE(SUM(total_amount), 0) AS total FROM sales '
        'WHERE status = ? AND store_id = ? AND occurred_at >= ? AND occurred_at < ?',
        variables: [
          Variable.withString('COMPLETED'),
          Variable.withString(storeId),
          Variable.withDateTime(startYesterday),
          Variable.withDateTime(startToday),
        ],
      ).getSingle();
      final yesterdayCA = yesterdayResult.read<int>('total');

      // Employee count for this store
      final empResult = await _db.customSelect(
        'SELECT COUNT(*) AS cnt FROM employees '
        'WHERE store_id = ? AND status = ?',
        variables: [
          Variable.withString(storeId),
          Variable.withString('ACTIVE'),
        ],
      ).getSingle();
      final employeeCount = empResult.read<int>('cnt');

      overviews.add(StoreOverview(
        storeId: storeId,
        storeName: storeName,
        todayCA: todayCA,
        yesterdayCA: yesterdayCA,
        employeeCount: employeeCount,
        statusLevel: computeStoreStatus(todayCA, yesterdayCA),
      ));
    }

    return overviews;
  }

  /// Bottom [limit] least-selling products over the last 7 rolling days.
  /// Includes non-archived catalog products that had 0 sales so that
  /// the list isn't just a reverse of topProducts.
  Future<List<TopProduct>> getWeeklyWorstProducts({int limit = 5}) async {
    final now = DateTime.now();
    // Rolling 7-day window (matches UI label "7j", avoids Monday-boundary issue)
    final startOfWeek = DateTime(now.year, now.month, now.day).subtract(const Duration(days: 6));

    final totalResult = await _db.customSelect(
      'SELECT COALESCE(SUM(si.subtotal), 0) AS total '
      'FROM sale_items si INNER JOIN sales s ON si.sale_id = s.id '
      'WHERE s.status = ? AND s.occurred_at >= ?',
      variables: [
        Variable.withString('COMPLETED'),
        Variable.withDateTime(startOfWeek),
      ],
    ).getSingle();
    final totalRevenue = totalResult.read<int>('total');

    final rows = await _db.customSelect(
      'SELECT p.id AS product_id, p.name AS product_name, '
      'COALESCE(weekly.units_sold, 0) AS units_sold, '
      'COALESCE(weekly.revenue, 0) AS revenue '
      'FROM products p '
      'LEFT JOIN ( '
      '  SELECT si.product_id, '
      '  SUM(si.quantity) AS units_sold, SUM(si.subtotal) AS revenue '
      '  FROM sale_items si INNER JOIN sales s ON si.sale_id = s.id '
      '  WHERE s.status = ? AND s.occurred_at >= ? '
      '  GROUP BY si.product_id '
      ') weekly ON weekly.product_id = p.id '
      'WHERE p.archived = 0 '
      'ORDER BY units_sold ASC LIMIT ?',
      variables: [
        Variable.withString('COMPLETED'),
        Variable.withDateTime(startOfWeek),
        Variable.withInt(limit),
      ],
    ).get();

    return rows.map((row) {
      final revenue = row.read<int>('revenue');
      return TopProduct(
        productId: row.read<String>('product_id'),
        name: row.read<String>('product_name'),
        unitsSold: row.read<int>('units_sold'),
        revenue: revenue,
        sharePercent: totalRevenue > 0 ? (revenue / totalRevenue) * 100 : 0,
      );
    }).toList();
  }

  // ── Chart period queries ──────────────────────────────────

  /// Weekly CA for the last 12 weeks.
  /// Zero-fills missing weeks so the chart always has 12 bars.
  Future<List<DailyCA>> getWeeklyCA() async {
    final now = DateTime.now();
    final start = now.subtract(const Duration(days: 84));
    final rows = await _db.customSelect(
      "SELECT strftime('%Y-%W', occurred_at) AS period, "
      'MIN(date(occurred_at)) AS period_start, '
      'COALESCE(SUM(total_amount), 0) AS total '
      "FROM sales WHERE status = 'COMPLETED' AND occurred_at >= ? "
      "GROUP BY strftime('%Y-%W', occurred_at) ORDER BY period ASC",
      variables: [Variable.withDateTime(start)],
    ).get();
    final dataMap = <String, int>{};
    for (final r in rows) {
      dataMap[r.read<String>('period')] = r.read<int>('total');
    }
    // Generate 12 week slots starting from 11 weeks ago
    final today = DateTime(now.year, now.month, now.day);
    final monday = today.subtract(Duration(days: today.weekday - 1));
    return List.generate(12, (i) {
      final weekStart = monday.subtract(Duration(days: (11 - i) * 7));
      // Match strftime('%Y-%W') format used by SQLite
      final weekNum = _sqliteWeekW(weekStart);
      final key = '${weekStart.year}-${weekNum.toString().padLeft(2, '0')}';
      return DailyCA(date: weekStart, amount: dataMap[key] ?? 0);
    });
  }

  /// Week number matching SQLite's strftime('%W') — Monday-based, 00-53.
  static int _sqliteWeekW(DateTime date) {
    final dayOfYear = date.difference(DateTime(date.year, 1, 1)).inDays + 1;
    final wd = date.weekday % 7; // 0=Sun, 1=Mon, ..., 6=Sat
    return (dayOfYear + 6 - wd) ~/ 7;
  }

  /// Monthly CA for the last 12 months.
  /// Zero-fills missing months so the chart always has 12 bars.
  Future<List<DailyCA>> getMonthlyCA() async {
    final now = DateTime.now();
    final start = DateTime(now.year - 1, now.month, 1);
    final rows = await _db.customSelect(
      "SELECT strftime('%Y-%m', occurred_at) AS period, "
      'COALESCE(SUM(total_amount), 0) AS total '
      "FROM sales WHERE status = 'COMPLETED' AND occurred_at >= ? "
      "GROUP BY strftime('%Y-%m', occurred_at) ORDER BY period ASC",
      variables: [Variable.withDateTime(start)],
    ).get();
    final dataMap = <String, int>{};
    for (final r in rows) {
      dataMap[r.read<String>('period')] = r.read<int>('total');
    }
    return List.generate(12, (i) {
      final m = DateTime(now.year, now.month - 11 + i, 1);
      final key = '${m.year}-${m.month.toString().padLeft(2, '0')}';
      return DailyCA(date: m, amount: dataMap[key] ?? 0);
    });
  }

  /// Yearly CA for the last 5 years.
  /// Zero-fills missing years so the chart always has 5 bars.
  Future<List<DailyCA>> getYearlyCA() async {
    final now = DateTime.now();
    final start = DateTime(now.year - 4, 1, 1);
    final rows = await _db.customSelect(
      "SELECT strftime('%Y', occurred_at) AS period, "
      'COALESCE(SUM(total_amount), 0) AS total '
      "FROM sales WHERE status = 'COMPLETED' AND occurred_at >= ? "
      "GROUP BY strftime('%Y', occurred_at) ORDER BY period ASC",
      variables: [Variable.withDateTime(start)],
    ).get();
    final dataMap = <String, int>{};
    for (final r in rows) {
      dataMap[r.read<String>('period')] = r.read<int>('total');
    }
    return List.generate(5, (i) {
      final year = now.year - 4 + i;
      return DailyCA(date: DateTime(year), amount: dataMap['$year'] ?? 0);
    });
  }

  // ── Per-store queries (for store detail page) ──────────────

  /// Daily CA for a specific store (last 30 days).
  /// Zero-fills missing days so the chart always has 30 bars.
  Future<List<DailyCA>> getStoreDailyCA(String storeId) async {
    final now = DateTime.now();
    final start = DateTime(now.year, now.month, now.day - 29);
    final rows = await _db.customSelect(
      'SELECT date(occurred_at) AS day, COALESCE(SUM(total_amount), 0) AS total '
      "FROM sales WHERE status = 'COMPLETED' AND store_id = ? AND occurred_at >= ? "
      'GROUP BY date(occurred_at) ORDER BY day ASC',
      variables: [Variable.withString(storeId), Variable.withDateTime(start)],
    ).get();
    final dataMap = <String, int>{};
    for (final r in rows) {
      dataMap[r.read<String>('day')] = r.read<int>('total');
    }
    return List.generate(30, (i) {
      final d = start.add(Duration(days: i));
      final key = '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
      return DailyCA(date: d, amount: dataMap[key] ?? 0);
    });
  }

  /// Weekly CA for a specific store (last 12 weeks).
  /// Zero-fills missing weeks so the chart always has 12 bars.
  Future<List<DailyCA>> getStoreWeeklyCA(String storeId) async {
    final now = DateTime.now();
    final start = now.subtract(const Duration(days: 84));
    final rows = await _db.customSelect(
      "SELECT strftime('%Y-%W', occurred_at) AS period, "
      'MIN(date(occurred_at)) AS period_start, '
      'COALESCE(SUM(total_amount), 0) AS total '
      "FROM sales WHERE status = 'COMPLETED' AND store_id = ? AND occurred_at >= ? "
      "GROUP BY strftime('%Y-%W', occurred_at) ORDER BY period ASC",
      variables: [Variable.withString(storeId), Variable.withDateTime(start)],
    ).get();
    final dataMap = <String, int>{};
    for (final r in rows) {
      dataMap[r.read<String>('period')] = r.read<int>('total');
    }
    final today = DateTime(now.year, now.month, now.day);
    final monday = today.subtract(Duration(days: today.weekday - 1));
    return List.generate(12, (i) {
      final weekStart = monday.subtract(Duration(days: (11 - i) * 7));
      final weekNum = _sqliteWeekW(weekStart);
      final key = '${weekStart.year}-${weekNum.toString().padLeft(2, '0')}';
      return DailyCA(date: weekStart, amount: dataMap[key] ?? 0);
    });
  }

  /// Monthly CA for a specific store (last 12 months).
  /// Zero-fills missing months so the chart always has 12 bars.
  Future<List<DailyCA>> getStoreMonthlyCA(String storeId) async {
    final now = DateTime.now();
    final start = DateTime(now.year - 1, now.month, 1);
    final rows = await _db.customSelect(
      "SELECT strftime('%Y-%m', occurred_at) AS period, "
      'COALESCE(SUM(total_amount), 0) AS total '
      "FROM sales WHERE status = 'COMPLETED' AND store_id = ? AND occurred_at >= ? "
      "GROUP BY strftime('%Y-%m', occurred_at) ORDER BY period ASC",
      variables: [Variable.withString(storeId), Variable.withDateTime(start)],
    ).get();
    final dataMap = <String, int>{};
    for (final r in rows) {
      dataMap[r.read<String>('period')] = r.read<int>('total');
    }
    return List.generate(12, (i) {
      final m = DateTime(now.year, now.month - 11 + i, 1);
      final key = '${m.year}-${m.month.toString().padLeft(2, '0')}';
      return DailyCA(date: m, amount: dataMap[key] ?? 0);
    });
  }

  /// Yearly CA for a specific store (last 5 years).
  /// Zero-fills missing years so the chart always has 5 bars.
  Future<List<DailyCA>> getStoreYearlyCA(String storeId) async {
    final now = DateTime.now();
    final start = DateTime(now.year - 4, 1, 1);
    final rows = await _db.customSelect(
      "SELECT strftime('%Y', occurred_at) AS period, "
      'COALESCE(SUM(total_amount), 0) AS total '
      "FROM sales WHERE status = 'COMPLETED' AND store_id = ? AND occurred_at >= ? "
      "GROUP BY strftime('%Y', occurred_at) ORDER BY period ASC",
      variables: [Variable.withString(storeId), Variable.withDateTime(start)],
    ).get();
    final dataMap = <String, int>{};
    for (final r in rows) {
      dataMap[r.read<String>('period')] = r.read<int>('total');
    }
    return List.generate(5, (i) {
      final year = now.year - 4 + i;
      return DailyCA(date: DateTime(year), amount: dataMap['$year'] ?? 0);
    });
  }

  /// Top products for a specific store this week.
  Future<List<TopProduct>> getStoreTopProducts(String storeId, {int limit = 5}) async {
    final now = DateTime.now();
    final monday = now.subtract(Duration(days: now.weekday - 1));
    final startOfWeek = DateTime(monday.year, monday.month, monday.day);

    final totalResult = await _db.customSelect(
      'SELECT COALESCE(SUM(si.subtotal), 0) AS total '
      'FROM sale_items si INNER JOIN sales s ON si.sale_id = s.id '
      "WHERE s.status = 'COMPLETED' AND s.store_id = ? AND s.occurred_at >= ?",
      variables: [Variable.withString(storeId), Variable.withDateTime(startOfWeek)],
    ).getSingle();
    final totalRevenue = totalResult.read<int>('total');

    final rows = await _db.customSelect(
      'SELECT si.product_id, si.product_name, '
      'SUM(si.quantity) AS units_sold, SUM(si.subtotal) AS revenue '
      'FROM sale_items si INNER JOIN sales s ON si.sale_id = s.id '
      "WHERE s.status = 'COMPLETED' AND s.store_id = ? AND s.occurred_at >= ? "
      'GROUP BY si.product_id, si.product_name '
      'ORDER BY units_sold DESC LIMIT ?',
      variables: [
        Variable.withString(storeId),
        Variable.withDateTime(startOfWeek),
        Variable.withInt(limit),
      ],
    ).get();

    return rows.map((row) {
      final revenue = row.read<int>('revenue');
      return TopProduct(
        productId: row.read<String>('product_id'),
        name: row.read<String>('product_name'),
        unitsSold: row.read<int>('units_sold'),
        revenue: revenue,
        sharePercent: totalRevenue > 0 ? (revenue / totalRevenue) * 100 : 0,
      );
    }).toList();
  }

  /// Worst products for a specific store this week.
  /// Includes non-archived catalog products that had 0 sales.
  Future<List<TopProduct>> getStoreWorstProducts(String storeId, {int limit = 5}) async {
    final now = DateTime.now();
    final monday = now.subtract(Duration(days: now.weekday - 1));
    final startOfWeek = DateTime(monday.year, monday.month, monday.day);

    final totalResult = await _db.customSelect(
      'SELECT COALESCE(SUM(si.subtotal), 0) AS total '
      'FROM sale_items si INNER JOIN sales s ON si.sale_id = s.id '
      "WHERE s.status = 'COMPLETED' AND s.store_id = ? AND s.occurred_at >= ?",
      variables: [Variable.withString(storeId), Variable.withDateTime(startOfWeek)],
    ).getSingle();
    final totalRevenue = totalResult.read<int>('total');

    final rows = await _db.customSelect(
      'SELECT p.id AS product_id, p.name AS product_name, '
      'COALESCE(weekly.units_sold, 0) AS units_sold, '
      'COALESCE(weekly.revenue, 0) AS revenue '
      'FROM products p '
      'LEFT JOIN ( '
      '  SELECT si.product_id, '
      '  SUM(si.quantity) AS units_sold, SUM(si.subtotal) AS revenue '
      "  FROM sale_items si INNER JOIN sales s ON si.sale_id = s.id "
      "  WHERE s.status = 'COMPLETED' AND s.store_id = ? AND s.occurred_at >= ? "
      '  GROUP BY si.product_id '
      ') weekly ON weekly.product_id = p.id '
      'WHERE p.archived = 0 '
      'ORDER BY units_sold ASC LIMIT ?',
      variables: [
        Variable.withString(storeId),
        Variable.withDateTime(startOfWeek),
        Variable.withInt(limit),
      ],
    ).get();

    return rows.map((row) {
      final revenue = row.read<int>('revenue');
      return TopProduct(
        productId: row.read<String>('product_id'),
        name: row.read<String>('product_name'),
        unitsSold: row.read<int>('units_sold'),
        revenue: revenue,
        sharePercent: totalRevenue > 0 ? (revenue / totalRevenue) * 100 : 0,
      );
    }).toList();
  }

  /// Low stock products for a specific store.
  Future<List<LowStockProduct>> getStoreLowStockProducts(String storeId) async {
    final rows = await _db.customSelect(
      'SELECT p.id AS product_id, p.name AS product_name, '
      'sl.quantity, COALESCE(NULLIF(sl.minimum_threshold, 0), 5) AS threshold '
      'FROM stock_levels sl '
      'JOIN products p ON p.id = sl.product_id AND p.archived = 0 '
      'WHERE sl.store_id = ? AND sl.quantity <= COALESCE(NULLIF(sl.minimum_threshold, 0), 5) '
      'ORDER BY sl.quantity ASC',
      variables: [Variable.withString(storeId)],
    ).get();

    return rows.map((r) => LowStockProduct(
      productId: r.read<String>('product_id'),
      name: r.read<String>('product_name'),
      quantity: r.read<int>('quantity'),
      threshold: r.read<int>('threshold'),
    )).toList();
  }

  /// Assemble full DashboardSnapshot from all queries (Builder pattern).
  Future<DashboardSnapshot> getDashboardSnapshot() async {
    final results = await Future.wait([
      getTodayCA(),                        // 0
      getYesterdayCA(),                    // 1
      getDayBeforeYesterdayCA(),           // 2
      getMonthlyTransactionCount(),        // 3
      getMonthlyAverageBasket(),           // 4
      getPreviousMonthTransactionCount(),  // 5
      getPreviousMonthAverageBasket(),     // 6
      getLowStockCount(),                  // 7
      getTodaySalesCount(),                // 8
    ]);

    final topProducts = await getWeeklyTopProducts();
    final worstProducts = await getWeeklyWorstProducts();
    final dailyCA = await getDailyCAForLast30Days();
    final weekly = await getWeeklyCA();
    final monthly = await getMonthlyCA();
    final yearly = await getYearlyCA();
    final storeOverviews = await getStoreOverviews();

    final todayCA = results[0];
    final yesterdayCA = results[1];
    final dayBeforeCA = results[2];
    final trendPercent = yesterdayCA > 0
        ? ((todayCA - yesterdayCA) / yesterdayCA) * 100
        : 0.0;

    return DashboardSnapshot(
      todayCA: todayCA,
      yesterdayCA: yesterdayCA,
      dayBeforeYesterdayCA: dayBeforeCA,
      trendPercent: trendPercent,
      totalTransactionsMonth: results[3],
      averageBasketMonth: results[4],
      prevMonthTransactions: results[5],
      prevMonthAverageBasket: results[6],
      lowStockCount: results[7],
      weeklyTopProducts: topProducts,
      weeklyWorstProducts: worstProducts,
      dailyCALast30: dailyCA,
      weeklyCA: weekly,
      monthlyCA: monthly,
      yearlyCA: yearly,
      storeOverviews: storeOverviews,
      todaySalesCount: results[8],
    );
  }
}
