import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart' show Value;
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/dashboard/data/datasource/local_dashboard_datasource.dart';
import 'package:keevo/features/dashboard/domain/model/dashboard_snapshot.dart';
import 'package:keevo/features/pos/data/datasource/local_sale_datasource.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:sqlite3/open.dart';

/// « Chiffre d'affaires total » computed offline: period boundaries, status
/// filter and corrections (same rules as DashboardService on the server).
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

  // Wednesday 18 March 2026, 15:00 — week starts Monday 16 March.
  final now = DateTime(2026, 3, 18, 15);
  late AppDatabase db;
  late LocalDashboardDatasource ds;
  var seq = 0;

  setUp(() {
    db = AppDatabase.forTesting();
    ds = LocalDashboardDatasource(db, now: () => now);
  });
  tearDown(() => db.close());

  Future<String> sale(DateTime at, int total,
      {String status = 'COMPLETED', String store = 'store-1'}) async {
    final id = 'sale-${seq++}';
    await db.into(db.sales).insert(SalesCompanion.insert(
          id: id,
          storeId: store,
          employeeId: 'emp-1',
          totalAmount: total,
          paymentMode: 'CASH',
          status: Value(status),
          occurredAt: Value(at),
          createdAt: at,
        ));
    return id;
  }

  group('periodStart', () {
    test('week = Monday, month = 1st, year = 1 January, all time = none', () {
      expect(LocalDashboardDatasource.periodStart(RevenuePeriod.week, now),
          DateTime(2026, 3, 16));
      expect(LocalDashboardDatasource.periodStart(RevenuePeriod.month, now),
          DateTime(2026, 3, 1));
      expect(LocalDashboardDatasource.periodStart(RevenuePeriod.year, now),
          DateTime(2026, 1, 1));
      expect(
          LocalDashboardDatasource.periodStart(RevenuePeriod.allTime, now), null);
    });

    test('on a Monday the week starts that very day', () {
      expect(
          LocalDashboardDatasource.periodStart(
              RevenuePeriod.week, DateTime(2026, 3, 16, 0, 5)),
          DateTime(2026, 3, 16));
    });
  });

  group('getPeriodRevenue', () {
    test('week: Sunday 23:59 excluded, Monday 00:00 included', () async {
      await sale(DateTime(2026, 3, 15, 23, 59), 700);
      await sale(DateTime(2026, 3, 16), 1000);
      await sale(DateTime(2026, 3, 18, 9), 2000);

      expect(await ds.getPeriodRevenue(RevenuePeriod.week),
          const PeriodRevenue(amount: 3000, salesCount: 2));
    });

    test('month: last day of previous month at 23:59 excluded', () async {
      await sale(DateTime(2026, 2, 28, 23, 59), 700);
      await sale(DateTime(2026, 3, 1), 1000);
      await sale(DateTime(2026, 3, 10, 12), 500);

      expect(await ds.getPeriodRevenue(RevenuePeriod.month),
          const PeriodRevenue(amount: 1500, salesCount: 2));
    });

    test('year: 31 December 23:59 excluded', () async {
      await sale(DateTime(2025, 12, 31, 23, 59), 700);
      await sale(DateTime(2026, 1, 1), 1000);
      await sale(DateTime(2026, 2, 14, 18), 250);

      expect(await ds.getPeriodRevenue(RevenuePeriod.year),
          const PeriodRevenue(amount: 1250, salesCount: 2));
    });

    test('all time: every completed sale, all stores', () async {
      await sale(DateTime(2020, 5, 1), 100);
      await sale(DateTime(2025, 12, 31, 23, 59), 700, store: 'store-2');
      await sale(DateTime(2026, 3, 18, 9), 2000);

      expect(await ds.getPeriodRevenue(RevenuePeriod.allTime),
          const PeriodRevenue(amount: 2800, salesCount: 3));
    });

    test('cancelled and pending sales are excluded', () async {
      await sale(DateTime(2026, 3, 17), 1000);
      await sale(DateTime(2026, 3, 17), 9000, status: 'CANCELLED');
      await sale(DateTime(2026, 3, 17), 8000, status: 'PENDING_VALIDATION');

      for (final p in RevenuePeriod.values) {
        expect(await ds.getPeriodRevenue(p),
            const PeriodRevenue(amount: 1000, salesCount: 1),
            reason: p.label);
      }
    });

    test('a corrected sale counts with its corrected amount', () async {
      final id = await sale(DateTime(2026, 3, 17, 10), 3000);
      await db.into(db.saleItems).insert(SaleItemsCompanion.insert(
            id: 'item-1',
            saleId: id,
            productId: 'p1',
            productName: 'Riz',
            unitPrice: 1000,
            quantity: 3,
            subtotal: 3000,
            createdAt: now,
          ));

      await LocalSaleDataSource(db).updateItemQuantities(id, {'item-1': 1});

      expect(await ds.getPeriodRevenue(RevenuePeriod.week),
          const PeriodRevenue(amount: 1000, salesCount: 1));
    });

    test('a sale stored with an offset is cut on its local day', () async {
      // Raw Drift text of a local 00:30 sale on Monday (UTC: Sunday 23:30).
      await db.customStatement(
        'INSERT INTO sales (id, store_id, employee_id, total_amount, '
        "payment_mode, status, occurred_at, created_at) VALUES "
        "('raw-1', 'store-1', 'emp-1', 400, 'CASH', 'COMPLETED', "
        "'2026-03-16T00:30:00.000 +01:00', '2026-03-16T00:30:00.000 +01:00')",
      );

      expect(await ds.getPeriodRevenue(RevenuePeriod.week),
          const PeriodRevenue(amount: 400, salesCount: 1));
    });

    test('no sale: zero amount and zero count', () async {
      expect(await ds.getPeriodRevenue(RevenuePeriod.allTime),
          const PeriodRevenue(amount: 0, salesCount: 0));
    });
  });

  test('the device snapshot carries every period, flagged as device data',
      () async {
    await sale(DateTime(2026, 3, 17), 1000);
    await sale(DateTime(2025, 6, 1), 500);

    final snapshot = await ds.getDashboardSnapshot();

    expect(snapshot.computedOnDevice, isTrue);
    expect(snapshot.periodRevenue[RevenuePeriod.week],
        const PeriodRevenue(amount: 1000, salesCount: 1));
    expect(snapshot.periodRevenue[RevenuePeriod.month],
        const PeriodRevenue(amount: 1000, salesCount: 1));
    expect(snapshot.periodRevenue[RevenuePeriod.year],
        const PeriodRevenue(amount: 1000, salesCount: 1));
    expect(snapshot.periodRevenue[RevenuePeriod.allTime],
        const PeriodRevenue(amount: 1500, salesCount: 2));
  });
}
