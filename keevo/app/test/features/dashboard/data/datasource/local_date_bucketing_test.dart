import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart' show Value, Variable;
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/features/dashboard/data/datasource/local_dashboard_datasource.dart';
import 'package:keevo/features/profitability/data/datasource/local_profitability_datasource.dart';
import 'package:sqlite3/open.dart';

String _d(DateTime d) => '${d.year.toString().padLeft(4, '0')}-'
    '${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

/// Sales are bucketed by the shop's local day. Drift stores local DateTimes as
/// `2026-03-17T00:30:00.000 +01:00`; SQLite date()/strftime() convert that to
/// UTC (→ 2026-03-16), so a sale made between 00:00 and 01:00 in UTC+1 must
/// not land on the previous day / month.
void main() {
  setUpAll(() {
    if (Platform.isLinux) {
      open.overrideFor(OperatingSystem.linux, () {
        try {
          return DynamicLibrary.open('libsqlite3.so');
        } catch (_) {
          return DynamicLibrary.open('libsqlite3.so.0');
        }
      });
    }
  });

  late AppDatabase db;
  // v1s-15-8: "today" is taken per test, not once when the file loads. The
  // midnight window shrinks from the whole file run to the gap between this
  // now() and the datasource's own DateTime.now() (lib reads the clock itself).
  late DateTime today;
  late String todayStr;
  setUp(() {
    db = AppDatabase.forTesting();
    today = DateTime.now();
    todayStr = _d(today);
  });
  tearDown(() => db.close());

  Future<void> insertSale(String id, String occurredAtRaw,
      {int total = 1000}) async {
    await db.customStatement(
      'INSERT INTO sales (id, store_id, employee_id, total_amount, '
      "payment_mode, status, occurred_at, created_at) VALUES "
      "(?, 'store-1', 'emp-1', ?, 'CASH', 'COMPLETED', ?, ?)",
      [id, total, occurredAtRaw, occurredAtRaw],
    );
  }

  group('dashboard', () {
    test('a 00:30 (+01:00) sale counts on its local day', () async {
      await insertSale('s1', '${todayStr}T00:30:00.000 +01:00');

      final days = await LocalDashboardDatasource(db).getDailyCAForLast30Days();

      expect(days.last.amount, 1000);
    });

    test('a 00:30 (+01:00) sale on the 1st counts in its local month',
        () async {
      final first = DateTime(today.year, today.month, 1);
      await insertSale('s2', '${_d(first)}T00:30:00.000 +01:00');

      final months = await LocalDashboardDatasource(db).getMonthlyCA();

      expect(months.last.amount, 1000);
    });

    test('a row stored in UTC (Z) is bucketed on its local day', () async {
      final noon = DateTime(today.year, today.month, today.day, 12);
      await insertSale('s3', noon.toUtc().toIso8601String());

      final days = await LocalDashboardDatasource(db).getDailyCAForLast30Days();

      expect(days.last.amount, 1000);
    });

    // Monday 00:30 (+01:00) is Sunday in UTC: it must stay in its own week.
    test('a 00:30 (+01:00) sale on Monday counts in its local week', () async {
      final day = DateTime(today.year, today.month, today.day);
      final monday = day.subtract(Duration(days: day.weekday - 1));
      await insertSale('w1', '${_d(monday)}T00:30:00.000 +01:00');

      final ds = LocalDashboardDatasource(db);
      expect((await ds.getWeeklyCA()).last.amount, 1000);
      expect((await ds.getStoreWeeklyCA('store-1')).last.amount, 1000);
    });

    // 1 January 00:30 (+01:00) is 31 December in UTC: it must stay in its year.
    test('a 00:30 (+01:00) sale on 1 January counts in its local year',
        () async {
      await insertSale('y1', '${today.year}-01-01T00:30:00.000 +01:00');

      final ds = LocalDashboardDatasource(db);
      expect((await ds.getYearlyCA()).last.amount, 1000);
      expect((await ds.getStoreYearlyCA('store-1')).last.amount, 1000);
    });

    test('store daily and monthly charts bucket on the local day', () async {
      await insertSale('sd1', '${todayStr}T00:30:00.000 +01:00');

      final ds = LocalDashboardDatasource(db);
      expect((await ds.getStoreDailyCA('store-1')).last.amount, 1000);
      expect((await ds.getStoreMonthlyCA('store-1')).last.amount, 1000);
    });
  });

  group('profitability sparkline', () {
    test('daily margin keyed on the local day', () async {
      await db.customStatement(
        "INSERT INTO products (id, name, price, buy_price, transport_cost, "
        "created_at, updated_at) VALUES ('p1', 'Savon', 1000, 600, 0, ?, ?)",
        [todayStr, todayStr],
      );
      await insertSale('s4', '${todayStr}T00:30:00.000 +01:00');
      await db.customStatement(
        'INSERT INTO sale_items (id, sale_id, product_id, product_name, '
        "unit_price, quantity, subtotal, created_at) VALUES "
        "('i1', 's4', 'p1', 'Savon', 1000, 1, 1000, ?)",
        [todayStr],
      );

      final detail = await LocalProfitabilityDatasource(db)
          .getProductProfitabilityDetail(
              productId: 'p1',
              from: DateTime(today.year, today.month, today.day - 1),
              to: today);

      expect(detail!.dailyMarginLast7.single.date, todayStr);
    });
  });

  test('store performance: previous period does not overlap the current one',
      () async {
    final now = DateTime.now();
    await db.into(db.stores).insert(StoresCompanion.insert(
          id: 'store-1',
          name: 'Boutique',
          type: const Value('RETAIL'),
          isActive: const Value(true),
          tenantId: 't1',
          createdAt: now,
          updatedAt: now,
        ));
    Future<void> sale(String id, int total, DateTime at) =>
        db.into(db.sales).insert(SalesCompanion.insert(
              id: id,
              storeId: 'store-1',
              employeeId: 'e',
              totalAmount: total,
              paymentMode: 'CASH',
              status: const Value('COMPLETED'),
              occurredAt: Value(at),
              createdAt: now,
            ));
    await sale('cur', 1000, DateTime(2026, 3, 10, 9));
    await sale('prev', 500, DateTime(2026, 3, 9, 9));

    final perf = await LocalProfitabilityDatasource(db).getStorePerformance(
        from: DateTime(2026, 3, 10), to: DateTime(2026, 3, 16));

    // current 1000 vs previous 500 → +100 %
    expect(perf.single.deltaPercent, closeTo(100.0, 0.01));
  });

  // v1s-15-8: the Dart week key must equal SQLite's strftime('%W') for every
  // day — including years starting on a Monday (2029) or a Sunday (2034).
  test('sqliteWeekW matches SQLite strftime(%W) for every day 2024-2035',
      () async {
    final mismatches = <String>[];
    for (var year = 2024; year <= 2035; year++) {
      final rows = await db.customSelect(
        'WITH RECURSIVE d(x) AS (SELECT date(?) UNION ALL '
        "SELECT date(x, '+1 day') FROM d WHERE x < date(?)) "
        "SELECT x, strftime('%W', x) AS w FROM d",
        variables: [
          Variable.withString('$year-01-01'),
          Variable.withString('$year-12-31'),
        ],
      ).get();
      for (final r in rows) {
        final day = DateTime.parse(r.read<String>('x'));
        final expected = int.parse(r.read<String>('w'));
        final actual = LocalDashboardDatasource.sqliteWeekW(day);
        if (actual != expected) mismatches.add('$day: $actual != $expected');
      }
    }
    expect(mismatches, isEmpty);
  });
}
