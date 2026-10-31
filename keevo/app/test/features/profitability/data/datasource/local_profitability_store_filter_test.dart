import 'dart:ffi';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/profitability/data/datasource/local_profitability_datasource.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:sqlite3/open.dart';

String _d(DateTime d) => '${d.year.toString().padLeft(4, '0')}-'
    '${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

/// Drift's text format for a local DateTime: `2026-03-17T12:00:00.000 +01:00`.
String _local(DateTime d) {
  final o = d.timeZoneOffset;
  final sign = o.isNegative ? '-' : '+';
  final h = o.inHours.abs().toString().padLeft(2, '0');
  final m = (o.inMinutes.abs() % 60).toString().padLeft(2, '0');
  return '${_d(d)}T12:00:00.000 $sign$h:$m';
}

/// With a store selected, the offline product detail must cover that store
/// only — totals, sparkline and best store alike (as the backend does).
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
  setUp(() => db = AppDatabase.forTesting());
  tearDown(() => db.close());

  final today = DateTime.now();
  final todayStr = _d(today);

  Future<void> insertStore(String id, String name) => db.customStatement(
        'INSERT INTO stores (id, name, type, is_active, tenant_id, created_at, '
        "updated_at) VALUES (?, ?, 'RETAIL', 1, 't1', ?, ?)",
        [id, name, todayStr, todayStr],
      );

  Future<void> insertSale(String id, String storeId, int qty) async {
    final at = _local(today);
    await db.customStatement(
      'INSERT INTO sales (id, store_id, employee_id, total_amount, '
      "payment_mode, status, occurred_at, created_at) VALUES "
      "(?, ?, 'emp-1', ?, 'CASH', 'COMPLETED', ?, ?)",
      [id, storeId, 1000 * qty, at, at],
    );
    await db.customStatement(
      'INSERT INTO sale_items (id, sale_id, product_id, product_name, '
      'unit_price, quantity, subtotal, created_at) VALUES '
      "(?, ?, 'p1', 'Savon', 1000, ?, ?, ?)",
      ['i-$id', id, qty, 1000 * qty, todayStr],
    );
  }

  test('store filter applies to totals, sparkline and best store', () async {
    await db.customStatement(
      "INSERT INTO products (id, name, price, buy_price, transport_cost, "
      "created_at, updated_at) VALUES ('p1', 'Savon', 1000, 600, 0, ?, ?)",
      [todayStr, todayStr],
    );
    await insertStore('s1', 'Akwa');
    await insertStore('s2', 'Bonapriso');
    await insertSale('a', 's1', 1);
    await insertSale('b', 's2', 5);

    final detail = await LocalProfitabilityDatasource(db)
        .getProductProfitabilityDetail(
            productId: 'p1',
            from: DateTime(today.year, today.month, today.day - 1),
            to: today,
            storeId: 's1');

    expect(detail!.storeId, 's1');
    expect(detail.unitsSold, 1);
    expect(detail.dailyMarginLast7.single.marginXaf, 400);
    expect(detail.topStoreId, 's1');
    expect(detail.topStoreUnitsSold, 1);
  });
}
