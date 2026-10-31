import 'package:drift/drift.dart';
import 'package:flutter/foundation.dart' show visibleForTesting;

import 'package:keevo_core/storage/app_database.dart';
import '../../domain/model/dashboard_snapshot.dart';

/// SQL expression giving the shop's local day (`YYYY-MM-DD`) of a Drift
/// DateTime column. Drift stores local times as `…T00:30:00.000 +01:00`, and
/// SQLite's date()/strftime() would convert that to UTC (previous day); the
/// written wall-clock date is the local one. Rows stored in UTC (`…Z`) are
/// converted with 'localtime'. Rows without offset are already local.
String _localDay(String column) =>
    "(CASE WHEN $column LIKE '%Z' THEN date($column, 'localtime') "
    "ELSE substr($column, 1, 10) END)";

/// SQL expression giving the local Monday (`YYYY-MM-DD`) of the week of a
/// Drift DateTime column (weeks start Monday; `%w`: 0 = Sunday).
String _localMonday(String column) {
  final day = _localDay(column);
  return "date($day, '-' || ((CAST(strftime('%w', $day) AS INTEGER) + 6) % 7) || ' days')";
}

/// LocalDashboardDatasource — Drift SQL aggregation queries for dashboard.
///
/// GoF Builder: getDashboardSnapshot() orchestrates multiple async queries
/// into a single DashboardSnapshot object.
/// Story 7.1 — AC2, AC3, AC4, AC7, AC8.
class LocalDashboardDatasource {
  final AppDatabase _db;
  final DateTime Function() _now;

  /// [now] is the clock (v1s-16-19: injectable for tests); defaults to
  /// [DateTime.now] so production wiring is unchanged.
  LocalDashboardDatasource(this._db, {DateTime Function()? now})
      : _now = now ?? DateTime.now;

  /// Local Monday 00:00 of the week containing [date] (weeks start Monday).
  /// Calendar-field arithmetic only — no `Duration` subtraction, which would
  /// shift by an hour across a DST change.
  @visibleForTesting
  static DateTime mondayOf(DateTime date) =>
      DateTime(date.year, date.month, date.day - (date.weekday - 1));

  /// Test hook: the local-Monday SQL expression applied to [column].
  @visibleForTesting
  static String localMondaySql(String column) => _localMonday(column);

  /// Coarse SQL lower bound for the chart queries: one calendar day before
  /// [start]. `occurred_at` is compared as raw text, so a row synced in UTC
  /// (`<Sunday>T23:30:00.000Z`) that `_localDay` buckets into the first slot
  /// would sort before `<Monday>T00:00:00.000 +01:00`. Rows outside the window
  /// are dropped by the slot-key lookup (zero-filled maps).
  static DateTime _prefilter(DateTime start) =>
      DateTime(start.year, start.month, start.day - 1);

  static String _d(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  /// The 12 weekly slots (local Mondays 00:00), oldest → newest, ending with
  /// the week containing [now]. Shared with the remote zero-fill
  /// (dashboard_providers) so online and offline charts have the same slots.
  static List<DateTime> weeklySlots(DateTime now) {
    final m = mondayOf(now);
    return List.generate(
        12, (i) => DateTime(m.year, m.month, m.day - (11 - i) * 7));
  }

  /// First local day of [period] for the clock value [now] (null = no lower
  /// bound, « Depuis le début »). Same calendar as the server
  /// (DashboardService): weeks start Monday, months/years on the 1st.
  @visibleForTesting
  static DateTime? periodStart(RevenuePeriod period, DateTime now) {
    switch (period) {
      case RevenuePeriod.week:
        return mondayOf(now);
      case RevenuePeriod.month:
        return DateTime(now.year, now.month, 1);
      case RevenuePeriod.year:
        return DateTime(now.year, 1, 1);
      case RevenuePeriod.allTime:
        return null;
    }
  }

  /// « Chiffre d'affaires total » over [period]: all stores, COMPLETED sales
  /// only (cancelled / pending excluded; a correction rewrites the sale's
  /// `total_amount`, so the corrected amount is what is summed — same rule
  /// as « CA d'aujourd'hui »). Sales are cut on their local day, like the
  /// charts, so a sale synced in UTC lands in the right period.
  ///
  /// Only covers the sales present on this device.
  Future<PeriodRevenue> getPeriodRevenue(RevenuePeriod period) async {
    final start = periodStart(period, _now());
    final result = await _db.customSelect(
      'SELECT COALESCE(SUM(total_amount), 0) AS total, COUNT(*) AS cnt '
      "FROM sales WHERE status = 'COMPLETED'"
      '${start != null ? ' AND ${_localDay('occurred_at')} >= ?' : ''}',
      variables: [if (start != null) Variable.withString(_d(start))],
    ).getSingle();
    return PeriodRevenue(
      amount: result.read<int>('total'),
      salesCount: result.read<int>('cnt'),
    );
  }

  /// [getPeriodRevenue] for every [RevenuePeriod].
  Future<Map<RevenuePeriod, PeriodRevenue>> getPeriodRevenues() async => {
        for (final p in RevenuePeriod.values) p: await getPeriodRevenue(p),
      };

  /// Today's total CA across all stores (COMPLETED sales only).
  Future<int> getTodayCA() async {
    final now = _now();
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
    final now = _now();
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
    final now = _now();
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
    final now = _now();
    final start = DateTime(now.year, now.month, now.day - 29);
    final rows = await _db.customSelect(
      'SELECT ${_localDay('occurred_at')} AS day, COALESCE(SUM(total_amount), 0) AS total '
      'FROM sales WHERE status = ? AND occurred_at >= ? '
      'GROUP BY ${_localDay('occurred_at')} ORDER BY day ASC',
      variables: [
        Variable.withString('COMPLETED'),
        Variable.withDateTime(_prefilter(start)),
      ],
    ).get();
    final dataMap = <String, int>{};
    for (final row in rows) {
      dataMap[row.read<String>('day')] = row.read<int>('total');
    }
    return List.generate(30, (i) {
      final d = DateTime(start.year, start.month, start.day + i);
      return DailyCA(date: d, amount: dataMap[_d(d)] ?? 0);
    });
  }

  /// Count of COMPLETED sales today.
  Future<int> getTodaySalesCount() async {
    final now = _now();
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
    final now = _now();
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
    final now = _now();
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
    final now = _now();
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
    final now = _now();
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
      // 13.10b: same rows as the « stock bas » list (active stores only).
      'LEFT JOIN stores s ON s.id = sl.store_id '
      'WHERE COALESCE(s.is_active, 1) = 1 AND sl.quantity <= COALESCE(NULLIF(p.minimum_threshold, 0), '
      'NULLIF(sl.minimum_threshold, 0), 5)',
    ).getSingle();
    return result.read<int>('cnt');
  }

  /// Top [limit] best-selling products over the last 7 rolling days.
  Future<List<TopProduct>> getWeeklyTopProducts({int limit = 5}) async {
    final now = _now();
    // Rolling 7-day window (matches UI label "7j", avoids Monday-boundary issue)
    final startOfWeek = DateTime(now.year, now.month, now.day - 6);

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
    final now = _now();
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
    final now = _now();
    // Rolling 7-day window (matches UI label "7j", avoids Monday-boundary issue)
    final startOfWeek = DateTime(now.year, now.month, now.day - 6);

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
  ///
  /// v1s-16-19: buckets are keyed by the week's local Monday (`YYYY-MM-DD`),
  /// unique across years — a week straddling 31 Dec / 1 Jan is one bucket
  /// (the former `strftime('%Y-%W')` key split it and lost the January part).
  Future<List<DailyCA>> getWeeklyCA() => _weeklyCA();

  /// Monthly CA for the last 12 months.
  /// Zero-fills missing months so the chart always has 12 bars.
  Future<List<DailyCA>> getMonthlyCA() async {
    final now = _now();
    final start = DateTime(now.year - 1, now.month, 1);
    final rows = await _db.customSelect(
      "SELECT substr(${_localDay('occurred_at')}, 1, 7) AS period, "
      'COALESCE(SUM(total_amount), 0) AS total '
      "FROM sales WHERE status = 'COMPLETED' AND occurred_at >= ? "
      "GROUP BY substr(${_localDay('occurred_at')}, 1, 7) ORDER BY period ASC",
      variables: [Variable.withDateTime(_prefilter(start))],
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
    final now = _now();
    final start = DateTime(now.year - 4, 1, 1);
    final rows = await _db.customSelect(
      "SELECT substr(${_localDay('occurred_at')}, 1, 4) AS period, "
      'COALESCE(SUM(total_amount), 0) AS total '
      "FROM sales WHERE status = 'COMPLETED' AND occurred_at >= ? "
      "GROUP BY substr(${_localDay('occurred_at')}, 1, 4) ORDER BY period ASC",
      variables: [Variable.withDateTime(_prefilter(start))],
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
    final now = _now();
    final start = DateTime(now.year, now.month, now.day - 29);
    final rows = await _db.customSelect(
      'SELECT ${_localDay('occurred_at')} AS day, COALESCE(SUM(total_amount), 0) AS total '
      "FROM sales WHERE status = 'COMPLETED' AND store_id = ? AND occurred_at >= ? "
      'GROUP BY ${_localDay('occurred_at')} ORDER BY day ASC',
      variables: [Variable.withString(storeId), Variable.withDateTime(_prefilter(start))],
    ).get();
    final dataMap = <String, int>{};
    for (final r in rows) {
      dataMap[r.read<String>('day')] = r.read<int>('total');
    }
    return List.generate(30, (i) {
      final d = DateTime(start.year, start.month, start.day + i);
      return DailyCA(date: d, amount: dataMap[_d(d)] ?? 0);
    });
  }

  /// Weekly CA for a specific store (last 12 weeks).
  /// Zero-fills missing weeks so the chart always has 12 bars.
  Future<List<DailyCA>> getStoreWeeklyCA(String storeId) =>
      _weeklyCA(storeId: storeId);

  Future<List<DailyCA>> _weeklyCA({String? storeId}) async {
    final slots = weeklySlots(_now());
    final monday = _localMonday('occurred_at');
    final rows = await _db.customSelect(
      'SELECT $monday AS period, '
      'COALESCE(SUM(total_amount), 0) AS total '
      "FROM sales WHERE status = 'COMPLETED' "
      '${storeId != null ? 'AND store_id = ? ' : ''}'
      'AND occurred_at >= ? '
      'GROUP BY period ORDER BY period ASC',
      variables: [
        if (storeId != null) Variable.withString(storeId),
        Variable.withDateTime(_prefilter(slots.first)),
      ],
    ).get();
    final dataMap = <String, int>{};
    for (final r in rows) {
      dataMap[r.read<String>('period')] = r.read<int>('total');
    }
    return [
      for (final slot in slots) DailyCA(date: slot, amount: dataMap[_d(slot)] ?? 0),
    ];
  }

  /// Monthly CA for a specific store (last 12 months).
  /// Zero-fills missing months so the chart always has 12 bars.
  Future<List<DailyCA>> getStoreMonthlyCA(String storeId) async {
    final now = _now();
    final start = DateTime(now.year - 1, now.month, 1);
    final rows = await _db.customSelect(
      "SELECT substr(${_localDay('occurred_at')}, 1, 7) AS period, "
      'COALESCE(SUM(total_amount), 0) AS total '
      "FROM sales WHERE status = 'COMPLETED' AND store_id = ? AND occurred_at >= ? "
      "GROUP BY substr(${_localDay('occurred_at')}, 1, 7) ORDER BY period ASC",
      variables: [Variable.withString(storeId), Variable.withDateTime(_prefilter(start))],
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
    final now = _now();
    final start = DateTime(now.year - 4, 1, 1);
    final rows = await _db.customSelect(
      "SELECT substr(${_localDay('occurred_at')}, 1, 4) AS period, "
      'COALESCE(SUM(total_amount), 0) AS total '
      "FROM sales WHERE status = 'COMPLETED' AND store_id = ? AND occurred_at >= ? "
      "GROUP BY substr(${_localDay('occurred_at')}, 1, 4) ORDER BY period ASC",
      variables: [Variable.withString(storeId), Variable.withDateTime(_prefilter(start))],
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
    final now = _now();
    final startOfWeek = mondayOf(now);

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
    final now = _now();
    final startOfWeek = mondayOf(now);

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
      'sl.quantity, COALESCE(NULLIF(p.minimum_threshold, 0), '
      'NULLIF(sl.minimum_threshold, 0), 5) AS threshold '
      'FROM stock_levels sl '
      'JOIN products p ON p.id = sl.product_id AND p.archived = 0 '
      'WHERE sl.store_id = ? AND sl.quantity <= COALESCE(NULLIF(p.minimum_threshold, 0), '
      'NULLIF(sl.minimum_threshold, 0), 5) '
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

  /// 13.10b — free local « stock bas » list: products of [storeId]
  /// ([getStoreLowStockProducts]) or, when null, of every active store with
  /// the store name — the same rows [getLowStockCount] counts. Lowest
  /// quantity first.
  Future<List<LowStockProduct>> getLowStockProducts({String? storeId}) async {
    if (storeId != null) return getStoreLowStockProducts(storeId);
    final rows = await _db.customSelect(
      'SELECT p.id AS product_id, p.name AS product_name, '
      's.name AS store_name, sl.quantity, '
      'COALESCE(NULLIF(p.minimum_threshold, 0), '
      'NULLIF(sl.minimum_threshold, 0), 5) AS threshold '
      'FROM stock_levels sl '
      'JOIN products p ON p.id = sl.product_id AND p.archived = 0 '
      'LEFT JOIN stores s ON s.id = sl.store_id '
      'WHERE COALESCE(s.is_active, 1) = 1 '
      'AND sl.quantity <= COALESCE(NULLIF(p.minimum_threshold, 0), '
      'NULLIF(sl.minimum_threshold, 0), 5) '
      'ORDER BY sl.quantity ASC, p.name ASC',
    ).get();

    return rows
        .map((r) => LowStockProduct(
              productId: r.read<String>('product_id'),
              name: r.read<String>('product_name'),
              quantity: r.read<int>('quantity'),
              threshold: r.read<int>('threshold'),
              storeName: r.readNullable<String>('store_name'),
            ))
        .toList();
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
    final periodRevenue = await getPeriodRevenues();

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
      periodRevenue: periodRevenue,
      computedOnDevice: true,
    );
  }
}
