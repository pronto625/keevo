import 'dart:ffi';
import 'dart:io';

import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:sqlite3/open.dart';

/// A device at schema 29 upgrades to 30: sales get mobile_money_ref (the
/// Mobile Money reference was only sent to the server); old rows read back
/// with no reference.
void main() {
  setUpAll(() {
    if (!Platform.isLinux) return;
    open.overrideFor(OperatingSystem.linux, () {
      try {
        return DynamicLibrary.open('libsqlite3.so');
      } catch (_) {
        return DynamicLibrary.open('libsqlite3.so.0');
      }
    });
  });

  test('v29 → v30 adds sales.mobile_money_ref, legacy sale readable',
      () async {
    final db = AppDatabase.forTestingWith(NativeDatabase.memory(setup: (raw) {
      // sales exactly as schema 29 created it.
      raw.execute('CREATE TABLE sales (id TEXT NOT NULL, '
          'store_id TEXT NOT NULL, employee_id TEXT NOT NULL, '
          'total_amount INTEGER NOT NULL, '
          'discount_amount INTEGER NOT NULL DEFAULT 0, '
          'payment_mode TEXT NOT NULL, client_id TEXT NULL, '
          "status TEXT NULL DEFAULT 'COMPLETED', occurred_at TEXT NULL, "
          'synced INTEGER NOT NULL DEFAULT 0, synced_at TEXT NULL, '
          'created_at TEXT NOT NULL, PRIMARY KEY (id))');
      raw.execute("INSERT INTO sales (id, store_id, employee_id, total_amount, "
          "payment_mode, created_at) VALUES ('legacy', 'st-1', 'e-1', 1500, "
          "'MOBILE_MONEY', '2026-10-05T10:00:00.000')");
      // The later v31 step adds products.minimum_threshold.
      raw.execute('CREATE TABLE products (id TEXT NOT NULL PRIMARY KEY)');
      raw.userVersion = 29;
    }));
    addTearDown(db.close);

    final sale = await (db.select(db.sales)
          ..where((s) => s.id.equals('legacy')))
        .getSingle();
    expect(sale.paymentMode, 'MOBILE_MONEY');
    expect(sale.mobileMoneyRef, isNull);

    await db.customStatement(
        "UPDATE sales SET mobile_money_ref = 'MP2603.1234' WHERE id = 'legacy'");
    final updated = await (db.select(db.sales)
          ..where((s) => s.id.equals('legacy')))
        .getSingle();
    expect(updated.mobileMoneyRef, 'MP2603.1234');
  });
}
