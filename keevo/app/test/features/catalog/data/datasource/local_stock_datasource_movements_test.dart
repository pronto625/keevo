import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/catalog/data/datasource/local_stock_datasource.dart';
import 'package:keevo_core/storage/app_database.dart';
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
  setUpAll(_overrideSqlite3ForLinuxTesting);

  late AppDatabase db;
  late LocalStockDataSource sut;

  setUp(() {
    db = AppDatabase.forTesting();
    sut = LocalStockDataSource(db);
  });

  tearDown(() => db.close());

  Future<void> insertSale({
    required String id,
    required int before,
    required DateTime at,
    String type = 'SALE',
    int delta = -1,
    String? variantId,
  }) =>
      db.into(db.stockMovements).insert(StockMovementsCompanion.insert(
            id: id,
            productId: 'p1',
            variantId: Value(variantId),
            storeId: 's1',
            type: type,
            quantityBefore: Value(before),
            quantityDelta: delta,
            quantityAfter: Value(before + delta),
            actorId: 'u1',
            createdAt: at,
          ));

  test('two unsynced one-unit sales of the same product are both kept',
      () async {
    final t = DateTime(2026, 9, 30, 10);
    await insertSale(id: 'sale-a', before: 10, at: t);
    await insertSale(id: 'sale-b', before: 9, at: t.add(const Duration(minutes: 5)));

    final history = await sut.getMovements('p1');
    await pumpEventQueue();

    expect(history.map((m) => m.id), containsAll(['sale-a', 'sale-b']));
    expect(await db.select(db.stockMovements).get(), hasLength(2));
  });

  test('exact crash-retry duplicates are still collapsed', () async {
    final t = DateTime(2026, 9, 30, 10);
    await insertSale(id: 'retry-1', before: 0, at: t);
    await insertSale(id: 'retry-2', before: 0, at: t.add(const Duration(seconds: 1)));

    final history = await sut.getMovements('p1');

    expect(history, hasLength(1));
  });

  test('a sale, a restock and a second identical sale are all kept', () async {
    final t = DateTime(2026, 9, 30, 10);
    await insertSale(id: 'sale-1', before: 10, at: t);
    await insertSale(
        id: 'restock',
        before: 9,
        delta: 1,
        type: 'STOCK_ENTRY',
        at: t.add(const Duration(minutes: 1)));
    await insertSale(id: 'sale-2', before: 10, at: t.add(const Duration(minutes: 2)));

    final history = await sut.getMovements('p1');
    await pumpEventQueue();

    expect(history.map((m) => m.id),
        containsAll(['sale-1', 'restock', 'sale-2']));
    expect(await db.select(db.stockMovements).get(), hasLength(3));
  });

  test('the same sale on two variants of one product is kept twice', () async {
    final t = DateTime(2026, 9, 30, 10);
    await insertSale(id: 'sale-red', before: 10, variantId: 'red', at: t);
    await insertSale(
        id: 'sale-blue',
        before: 10,
        variantId: 'blue',
        at: t.add(const Duration(minutes: 1)));

    final history = await sut.getMovements('p1');
    await pumpEventQueue();

    expect(history.map((m) => m.id), containsAll(['sale-red', 'sale-blue']));
    expect(await db.select(db.stockMovements).get(), hasLength(2));
  });
}
