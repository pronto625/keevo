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
  // v1s-16-19: the datasource takes an injected clock — "today" is fixed, so
  // there is no midnight window between the test and the lib any more.
  final today = DateTime(2026, 3, 18, 15); // a Wednesday
  final todayStr = _d(today);
  LocalDashboardDatasource dashboard({DateTime? at}) =>
      LocalDashboardDatasource(db, now: () => at ?? today);
  setUp(() {
    db = AppDatabase.forTesting();
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

      final days = await dashboard().getDailyCAForLast30Days();

      expect(days.last.amount, 1000);
    });

    test('a 00:30 (+01:00) sale on the 1st counts in its local month',
        () async {
      final first = DateTime(today.year, today.month, 1);
      await insertSale('s2', '${_d(first)}T00:30:00.000 +01:00');

      final months = await dashboard().getMonthlyCA();

      expect(months.last.amount, 1000);
    });

    test('a row stored in UTC (Z) is bucketed on its local day', () async {
      final noon = DateTime(today.year, today.month, today.day, 12);
      await insertSale('s3', noon.toUtc().toIso8601String());

      final days = await dashboard().getDailyCAForLast30Days();

      expect(days.last.amount, 1000);
    });

    // Monday 00:30 (+01:00) is Sunday in UTC: it must stay in its own week.
    test('a 00:30 (+01:00) sale on Monday counts in its local week', () async {
      final monday = LocalDashboardDatasource.mondayOf(today);
      await insertSale('w1', '${_d(monday)}T00:30:00.000 +01:00');

      final ds = dashboard();
      expect((await ds.getWeeklyCA()).last.amount, 1000);
      expect((await ds.getStoreWeeklyCA('store-1')).last.amount, 1000);
    });

    // 1 January 00:30 (+01:00) is 31 December in UTC: it must stay in its year.
    test('a 00:30 (+01:00) sale on 1 January counts in its local year',
        () async {
      await insertSale('y1', '${today.year}-01-01T00:30:00.000 +01:00');

      final ds = dashboard();
      expect((await ds.getYearlyCA()).last.amount, 1000);
      expect((await ds.getStoreYearlyCA('store-1')).last.amount, 1000);
    });

    test('store daily and monthly charts bucket on the local day', () async {
      await insertSale('sd1', '${todayStr}T00:30:00.000 +01:00');

      final ds = dashboard();
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

  group('weekly buckets (v1s-16-19)', () {
    // A week starting in December: its January days must not be lost.
    test('a week straddling the year end is one bucket (all stores)',
        () async {
      await insertSale('ye1', '2025-12-30T10:00:00.000 +01:00', total: 500);
      await insertSale('ye2', '2026-01-02T10:00:00.000 +01:00', total: 300);

      final weeks =
          await dashboard(at: DateTime(2026, 1, 7, 15)).getWeeklyCA();

      final slot = weeks.singleWhere((w) => _d(w.date) == '2025-12-29');
      expect(slot.amount, 800);
      expect(weeks.fold<int>(0, (a, w) => a + w.amount), 800);
    });

    test('a week straddling the year end is one bucket (one store)',
        () async {
      await insertSale('ys1', '2025-12-30T10:00:00.000 +01:00', total: 500);
      await insertSale('ys2', '2026-01-02T10:00:00.000 +01:00', total: 300);

      final weeks = await dashboard(at: DateTime(2026, 1, 7, 15))
          .getStoreWeeklyCA('store-1');

      final slot = weeks.singleWhere((w) => _d(w.date) == '2025-12-29');
      expect(slot.amount, 800);
    });

    test('a sale on the oldest slot Monday at 00:30 is inside the window',
        () async {
      final ds = dashboard();
      final oldest = (await ds.getWeeklyCA()).first.date;
      await insertSale('old1', '${_d(oldest)}T00:30:00.000 +01:00');

      expect((await ds.getWeeklyCA()).first.amount, 1000);
    });

    // A synced row stored in UTC (Z) at 00:30 local on the oldest Monday is
    // `<Sunday>T23:30Z` east of UTC: the raw-text window must not drop it.
    test('a Z-stored 00:30 local sale on the oldest slot Monday counts',
        () async {
      final ds = dashboard();
      final oldest = (await ds.getWeeklyCA()).first.date;
      await insertSale(
          'oldz',
          DateTime(oldest.year, oldest.month, oldest.day, 0, 30)
              .toUtc()
              .toIso8601String());

      expect((await ds.getWeeklyCA()).first.amount, 1000);
      expect((await ds.getStoreWeeklyCA('store-1')).first.amount, 1000);
    });

    // SQL Monday key == Dart Monday for every day 2024-2035.
    test('SQL local-Monday key matches mondayOf for every day 2024-2035',
        () async {
      final mismatches = <String>[];
      final rows = await db.customSelect(
        'WITH RECURSIVE d(x) AS (SELECT date(?) UNION ALL '
        "SELECT date(x, '+1 day') FROM d WHERE x < date(?)) "
        'SELECT x, ${LocalDashboardDatasource.localMondaySql('x')} AS m FROM d',
        variables: [
          Variable.withString('2024-01-01'),
          Variable.withString('2035-12-31'),
        ],
      ).get();
      expect(rows.length, greaterThan(4000));
      for (final r in rows) {
        final x = r.read<String>('x');
        final expected = _d(LocalDashboardDatasource.mondayOf(DateTime.parse(x)));
        final actual = r.read<String>('m');
        if (actual != expected) mismatches.add('$x: $actual != $expected');
      }
      expect(mismatches, isEmpty);
    });

    // Meaningful in a DST zone (run once with TZ=Europe/Paris); green in UTC.
    test('12 slots are local Mondays 00:00, 7 calendar days apart (2024-2035)',
        () {
      final problems = <String>[];
      for (var d = DateTime(2024, 1, 1);
          d.isBefore(DateTime(2036, 1, 1));
          d = DateTime(d.year, d.month, d.day + 1)) {
        for (final hour in const [0, 12, 23]) {
          final now = DateTime(d.year, d.month, d.day, hour, 30);
          final slots = LocalDashboardDatasource.weeklySlots(now);
          if (slots.length != 12) problems.add('$now: ${slots.length} slots');
          for (var i = 0; i < slots.length; i++) {
            final s = slots[i];
            if (s.weekday != DateTime.monday ||
                s.hour != 0 ||
                s.minute != 0) {
              problems.add('$now: slot $i = $s');
            }
            if (i > 0) {
              final p = slots[i - 1];
              if (DateTime(p.year, p.month, p.day + 7) != s) {
                problems.add('$now: $p -> $s');
              }
            }
          }
          final last = slots.last;
          if (now.isBefore(last) ||
              !now.isBefore(DateTime(last.year, last.month, last.day + 7))) {
            problems.add('$now: last slot $last does not contain now');
          }
        }
      }
      expect(problems.take(20).toList(), isEmpty);
    });
  });
}
