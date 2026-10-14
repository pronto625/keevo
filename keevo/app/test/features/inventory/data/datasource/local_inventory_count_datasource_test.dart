import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart' show Value;
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/features/inventory/data/datasource/local_inventory_count_datasource.dart';
import 'package:sqlite3/open.dart';

/// buildOfflineProductRows: the counting scope is driven by the products, so a
/// product never stocked in the store (no stock_levels row) is still counted,
/// with an expected quantity of 0 — as on the server (Full/PartialScopeResolver).
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

  late AppDatabase db;
  late LocalInventoryCountDataSource ds;

  const storeId = 'store-1';
  const sessionId = 'session-1';

  Future<void> addProduct(String id, String name,
          {String? categoryId, bool archived = false}) =>
      db.into(db.products).insert(ProductsCompanion.insert(
            id: id,
            name: name,
            categoryId: Value(categoryId),
            archived: Value(archived),
            createdAt: DateTime(2026),
            updatedAt: DateTime(2026),
          ));

  Future<void> addLevel(String productId, String store, int qty) =>
      db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
            id: 'sl-$productId-$store',
            productId: productId,
            storeId: store,
            quantity: qty,
            updatedAt: DateTime(2026),
          ));

  setUp(() async {
    db = AppDatabase.forTesting();
    ds = LocalInventoryCountDataSource(db);
    await addProduct('p-stocked', 'Banane', categoryId: 'cat-a');
    await addLevel('p-stocked', storeId, 12);
    await addProduct('p-rowless', 'Cacao', categoryId: 'cat-a');
    await addLevel('p-rowless', 'other-store', 5); // stocked elsewhere only
    await addProduct('p-other-cat', 'Ananas', categoryId: 'cat-b');
    await addProduct('p-archived', 'Zébu', categoryId: 'cat-a', archived: true);
    await addLevel('p-archived', storeId, 3);
  });
  tearDown(() => db.close());

  test('FULL: every non-archived product, rowless ones expected at 0',
      () async {
    final rows = await ds.buildOfflineProductRows(sessionId, storeId, null);

    expect(rows.map((r) => r.productId),
        ['p-other-cat', 'p-stocked', 'p-rowless']); // Ananas, Banane, Cacao
    final rowless = rows.firstWhere((r) => r.productId == 'p-rowless');
    expect(rowless.theoreticalQty, 0);
    expect(rowless.variantId, isNull);
    expect(rowless.physicalQty, isNull);
    expect(rows.firstWhere((r) => r.productId == 'p-stocked').theoreticalQty,
        12);
  });

  test('FULL: a store with no stock_levels row at all still lists products',
      () async {
    final rows = await ds.buildOfflineProductRows(sessionId, 'empty-store', null);

    expect(rows, hasLength(3));
    expect(rows.every((r) => r.theoreticalQty == 0), isTrue);
  });

  test('PARTIAL: products of the selected categories, rowless included',
      () async {
    final rows = await ds.buildOfflineProductRows(sessionId, storeId, ['cat-a']);

    expect(rows.map((r) => r.productId), ['p-stocked', 'p-rowless']);
    expect(rows.last.theoreticalQty, 0);
  });

  test('an existing count on a rowless product is merged (key product:null)',
      () async {
    await db.into(db.inventoryCounts).insert(InventoryCountsCompanion.insert(
          id: 'c-1',
          sessionId: sessionId,
          productId: 'p-rowless',
          productName: 'Cacao',
          theoretical: 0,
          physical: const Value(4),
          updatedAt: DateTime(2026),
        ));

    final rows = await ds.buildOfflineProductRows(sessionId, storeId, null);

    final rowless = rows.firstWhere((r) => r.productId == 'p-rowless');
    expect(rowless.physicalQty, 4);
  });
}
