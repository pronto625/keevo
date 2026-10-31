import 'dart:ffi';
import 'dart:io';

import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:sqlite3/open.dart';

/// A device at schema 30 upgrades to 31: products get minimum_threshold (the
/// product-wide low-stock threshold the server keeps on products); existing
/// products read back with 0 (no threshold).
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

  test('v30 → v31 adds products.minimum_threshold, legacy product readable',
      () async {
    final db = AppDatabase.forTestingWith(NativeDatabase.memory(setup: (raw) {
      // products exactly as schema 30 created it.
      raw.execute('CREATE TABLE products (id TEXT NOT NULL, name TEXT NOT NULL, '
          'description TEXT NULL, sku TEXT NOT NULL DEFAULT \'\', '
          'category_id TEXT NULL, price INTEGER NOT NULL DEFAULT 0, '
          'buy_price INTEGER NOT NULL DEFAULT 0, '
          'transport_cost INTEGER NOT NULL DEFAULT 0, '
          'stock_quantity INTEGER NOT NULL DEFAULT 0, store_id TEXT NULL, '
          'photo_url TEXT NULL, archived INTEGER NOT NULL DEFAULT 0, '
          "status TEXT NOT NULL DEFAULT 'ACTIVE', created_at TEXT NOT NULL, "
          'updated_at TEXT NOT NULL, PRIMARY KEY (id))');
      raw.execute('INSERT INTO products (id, name, created_at, updated_at) '
          "VALUES ('legacy', 'Savon', '2026-10-05T10:00:00.000', "
          "'2026-10-05T10:00:00.000')");
      raw.userVersion = 30;
    }));
    addTearDown(db.close);

    final product = await (db.select(db.products)
          ..where((p) => p.id.equals('legacy')))
        .getSingle();
    expect(product.name, 'Savon');
    expect(product.minimumThreshold, 0);

    await db.customStatement(
        "UPDATE products SET minimum_threshold = 6 WHERE id = 'legacy'");
    final updated = await (db.select(db.products)
          ..where((p) => p.id.equals('legacy')))
        .getSingle();
    expect(updated.minimumThreshold, 6);
  });
}
