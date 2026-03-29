import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/features/dashboard/data/datasource/local_dashboard_datasource.dart';
import 'package:sqlite3/open.dart';

void _overrideSqlite3ForLinuxTesting() {
  if (!Platform.isLinux) return;
  open.overrideFor(OperatingSystem.linux, () {
    try {
      return DynamicLibrary.open('libsqlite3.so');
    } catch (_) {
      return DynamicLibrary.open('libsqlite3.so.0');
    }
  });
}

void main() {
  setUpAll(() {
    GoogleFonts.config.allowRuntimeFetching = false;
    _overrideSqlite3ForLinuxTesting();
  });

  group('LocalDashboardDatasource (Story 7.1)', () {
    late AppDatabase db;
    late LocalDashboardDatasource datasource;

    const storeId = 'store-dash-001';

    final now = DateTime.now();

    setUp(() async {
      db = AppDatabase.forTesting();
      datasource = LocalDashboardDatasource(db);

      // Seed a store
      await db.into(db.stores).insert(StoresCompanion.insert(
            id: storeId,
            name: 'Test Store',
            type: const Value('RETAIL'),
            isActive: const Value(true),
            tenantId: 'tenant-001',
            createdAt: now,
            updatedAt: now,
          ));
    });

    tearDown(() async => db.close());

    Future<void> _insertSale({
      required DateTime occurredAt,
      required int totalAmount,
      String status = 'COMPLETED',
      String saleStoreId = storeId,
    }) async {
      final saleId = 'sale-${occurredAt.millisecondsSinceEpoch}-${totalAmount.hashCode}';
      await db.into(db.sales).insert(SalesCompanion.insert(
            id: saleId,
            storeId: saleStoreId,
            employeeId: 'emp-001',
            totalAmount: totalAmount,
            paymentMode: 'CASH',
            status: Value(status),
            occurredAt: Value(occurredAt),
            createdAt: now,
          ));
    }

    test('getTodayCA returns sum of today COMPLETED sales', () async {
      final now = DateTime.now();
      final today = DateTime(now.year, now.month, now.day, 10);
      await _insertSale(occurredAt: today, totalAmount: 5000);
      await _insertSale(occurredAt: today.add(const Duration(hours: 1)), totalAmount: 3000);
      // CANCELLED sale should not count
      await _insertSale(occurredAt: today, totalAmount: 999, status: 'CANCELLED');

      final result = await datasource.getTodayCA();
      expect(result, 8000);
    });

    test('getYesterdayCA returns sum of yesterday COMPLETED sales', () async {
      final now = DateTime.now();
      final yesterday = DateTime(now.year, now.month, now.day - 1, 14);
      await _insertSale(occurredAt: yesterday, totalAmount: 12000);
      await _insertSale(occurredAt: yesterday.add(const Duration(hours: 2)), totalAmount: 8000);

      final result = await datasource.getYesterdayCA();
      expect(result, 20000);
    });

    test('getDayBeforeYesterdayCA returns correct total', () async {
      final now = DateTime.now();
      final dayBefore = DateTime(now.year, now.month, now.day - 2, 10);
      await _insertSale(occurredAt: dayBefore, totalAmount: 7500);

      final result = await datasource.getDayBeforeYesterdayCA();
      expect(result, 7500);
    });

    test('getTodaySalesCount returns count only COMPLETED', () async {
      final now = DateTime.now();
      final today = DateTime(now.year, now.month, now.day, 9);
      await _insertSale(occurredAt: today, totalAmount: 1000);
      await _insertSale(occurredAt: today.add(const Duration(hours: 1)), totalAmount: 2000);
      await _insertSale(occurredAt: today, totalAmount: 500, status: 'CANCELLED');

      final count = await datasource.getTodaySalesCount();
      expect(count, 2);
    });

    test('getMonthlyTransactionCount counts current month only', () async {
      final now = DateTime.now();
      final thisMonth = DateTime(now.year, now.month, 5, 10);
      final lastMonth = DateTime(now.year, now.month - 1, 15, 10);

      await _insertSale(occurredAt: thisMonth, totalAmount: 1000);
      await _insertSale(occurredAt: thisMonth.add(const Duration(days: 1)), totalAmount: 2000);
      await _insertSale(occurredAt: lastMonth, totalAmount: 3000);

      final count = await datasource.getMonthlyTransactionCount();
      expect(count, 2);
    });

    test('getMonthlyAverageBasket returns average', () async {
      final now = DateTime.now();
      final d1 = DateTime(now.year, now.month, 3, 10);
      final d2 = DateTime(now.year, now.month, 4, 10);

      await _insertSale(occurredAt: d1, totalAmount: 6000);
      await _insertSale(occurredAt: d2, totalAmount: 4000);

      final avg = await datasource.getMonthlyAverageBasket();
      expect(avg, 5000); // (6000+4000)/2
    });

    test('getLowStockCount counts products below threshold', () async {
      // Seed product + stock level
      await db.into(db.products).insert(ProductsCompanion.insert(
            id: 'prod-001',
            name: 'Low Stock Item',
            createdAt: now,
            updatedAt: now,
          ));
      await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
            id: 'sl-001',
            productId: 'prod-001',
            storeId: storeId,
            quantity: 3,
            minimumThreshold: const Value(5),
            updatedAt: now,
          ));
      // Normal stock (above threshold)
      await db.into(db.products).insert(ProductsCompanion.insert(
            id: 'prod-002',
            name: 'Normal Item',
            createdAt: now,
            updatedAt: now,
          ));
      await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
            id: 'sl-002',
            productId: 'prod-002',
            storeId: storeId,
            quantity: 100,
            minimumThreshold: const Value(5),
            updatedAt: now,
          ));

      final count = await datasource.getLowStockCount();
      expect(count, 1);
    });

    test('getLowStockCount uses default threshold 5 when not set', () async {
      // Product with no explicit threshold (minimumThreshold defaults to 0)
      await db.into(db.products).insert(ProductsCompanion.insert(
            id: 'prod-nothresh',
            name: 'No Threshold Item',
            createdAt: now,
            updatedAt: now,
          ));
      await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
            id: 'sl-nothresh',
            productId: 'prod-nothresh',
            storeId: storeId,
            quantity: 3,
            // minimumThreshold defaults to 0 → effective threshold = 5
            updatedAt: now,
          ));
      // Product with no threshold but quantity above default 5
      await db.into(db.products).insert(ProductsCompanion.insert(
            id: 'prod-nothresh-ok',
            name: 'No Threshold OK',
            createdAt: now,
            updatedAt: now,
          ));
      await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
            id: 'sl-nothresh-ok',
            productId: 'prod-nothresh-ok',
            storeId: storeId,
            quantity: 10,
            updatedAt: now,
          ));

      final count = await datasource.getLowStockCount();
      expect(count, 1); // only prod-nothresh (qty 3 <= default 5)
    });

    test('getDashboardSnapshot assembles all fields', () async {
      final now = DateTime.now();
      final today = DateTime(now.year, now.month, now.day, 11);
      await _insertSale(occurredAt: today, totalAmount: 10000);

      final snapshot = await datasource.getDashboardSnapshot();
      expect(snapshot.todayCA, 10000);
      expect(snapshot.storeOverviews, isNotEmpty);
    });

    test('getStoreOverviews returns per-store data', () async {
      final now = DateTime.now();
      final today = DateTime(now.year, now.month, now.day, 10);
      await _insertSale(occurredAt: today, totalAmount: 15000);

      final overviews = await datasource.getStoreOverviews();
      expect(overviews.length, 1);
      expect(overviews.first.storeId, storeId);
      expect(overviews.first.todayCA, 15000);
    });

    test('returns 0 when no sales exist', () async {
      final todayCA = await datasource.getTodayCA();
      final yesterdayCA = await datasource.getYesterdayCA();
      expect(todayCA, 0);
      expect(yesterdayCA, 0);
    });
  });
}
