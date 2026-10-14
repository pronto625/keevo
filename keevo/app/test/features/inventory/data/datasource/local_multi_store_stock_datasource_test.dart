import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/features/inventory/data/datasource/local_multi_store_stock_datasource.dart';
import 'package:keevo/features/inventory/domain/model/store_product_stock_model.dart';
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

StoreProductStockModel _buildEntry({
  required String productId,
  required String storeId,
  required int quantity,
  required int threshold,
}) =>
    StoreProductStockModel(
      productId: productId,
      productName: 'Test Product',
      storeId: storeId,
      quantity: quantity,
      minimumThreshold: threshold,
      status: quantity == 0
          ? 'CRITIQUE'
          : (threshold > 0 && quantity <= threshold ? 'BAS' : 'NORMAL'),
      isLow: threshold > 0 && quantity > 0 && quantity <= threshold,
      isCritical: quantity == 0,
    );

void main() {
  setUpAll(() {
    GoogleFonts.config.allowRuntimeFetching = false;
    _overrideSqlite3ForLinuxTesting();
  });

  group('LocalMultiStoreStockDataSource (Story 3.2 — Task 10)', () {
    late AppDatabase db;
    late LocalMultiStoreStockDataSource datasource;

    const storeId = 'store-test-001';
    const productId = 'prod-test-001';

    setUp(() async {
      db = AppDatabase.forTesting();
      datasource = LocalMultiStoreStockDataSource(db);

      // Seed a store
      await db.into(db.stores).insert(StoresCompanion.insert(
        id: storeId,
        name: 'Boutique Test',
        tenantId: 'tenant-1',
        createdAt: DateTime(2025, 1, 1),
        updatedAt: DateTime(2025, 1, 1),
      ));

      // Seed a product
      await db.into(db.products).insert(ProductsCompanion.insert(
        id: productId,
        name: 'Chaussures Test',
        price: const Value(10000),
        createdAt: DateTime(2025, 1, 1),
        updatedAt: DateTime(2025, 1, 1),
      ));

      // Seed a stock level
      await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
        id: 'sl-001',
        productId: productId,
        storeId: storeId,
        quantity: 10,
        updatedAt: DateTime(2025, 1, 1),
      ));
    });

    tearDown(() async => db.close());

    test('getStoreOverviews returns one entry per active store', () async {
      final result = await datasource.getStoreOverviews();
      expect(result.length, 1);
      expect(result.first.storeId, storeId);
      expect(result.first.storeName, 'Boutique Test');
      expect(result.first.productCount, 1);
    });

    test('getStoreStockDetail returns products for a given storeId', () async {
      final result = await datasource.getStoreStockDetail(storeId);
      expect(result.length, 1);
      expect(result.first.productId, productId);
      expect(result.first.quantity, 10);
    });

    test('searchAcrossStores returns results matching name filter', () async {
      final result = await datasource.searchAcrossStores('Chaussures');
      expect(result.length, 1);
      expect(result.first.productName, 'Chaussures Test');
    });

    test('searchAcrossStores returns empty when no match', () async {
      final result = await datasource.searchAcrossStores('XYZ_NOTEXIST');
      expect(result, isEmpty);
    });

    test('searchAcrossStores treats "_" literally (16.14)', () async {
      for (final (id, name) in [('p-ab', 'a_b'), ('p-axb', 'axb')]) {
        await db.into(db.products).insert(ProductsCompanion.insert(
          id: id,
          name: name,
          createdAt: DateTime(2025, 1, 1),
          updatedAt: DateTime(2025, 1, 1),
        ));
        await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
          id: 'sl-$id',
          productId: id,
          storeId: storeId,
          quantity: 3,
          updatedAt: DateTime(2025, 1, 1),
        ));
      }
      final result = await datasource.searchAcrossStores('a_b');
      expect(result.map((e) => e.productName), ['a_b']);
    });

    test('searchAcrossStores folds case and accents (16.16)', () async {
      await db.into(db.products).insert(ProductsCompanion.insert(
        id: 'p-epi',
        name: 'Épicerie',
        createdAt: DateTime(2025, 1, 1),
        updatedAt: DateTime(2025, 1, 1),
      ));
      await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
        id: 'sl-p-epi',
        productId: 'p-epi',
        storeId: storeId,
        quantity: 2,
        updatedAt: DateTime(2025, 1, 1),
      ));
      for (final q in ['épicerie', 'epicerie', 'EPICERIE']) {
        final result = await datasource.searchAcrossStores(q);
        expect(result.map((e) => e.productName), ['Épicerie'], reason: q);
      }
    });

    test('searchAcrossStores: exact-case "ЖУК" still finds "ЖУК" (16.16)',
        () async {
      await db.into(db.products).insert(ProductsCompanion.insert(
        id: 'p-zhuk',
        name: 'ЖУК',
        createdAt: DateTime(2025, 1, 1),
        updatedAt: DateTime(2025, 1, 1),
      ));
      await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
        id: 'sl-p-zhuk',
        productId: 'p-zhuk',
        storeId: storeId,
        quantity: 1,
        updatedAt: DateTime(2025, 1, 1),
      ));
      final result = await datasource.searchAcrossStores('ЖУК');
      expect(result.map((e) => e.productName), ['ЖУК']);
    });

    test('upsertStockLevels writes remote entries into stock_levels', () async {
      final entries = [
        _buildEntry(productId: productId, storeId: storeId, quantity: 25, threshold: 5),
      ];
      await datasource.upsertStockLevels(storeId, entries);

      final result = await datasource.getStoreStockDetail(storeId);
      expect(result.first.quantity, 25);
    });

    // ── A product never stocked in a store (no stock_levels row) is quantity 0
    // everywhere; zero or negative stock is CRITIQUE. ──────────────────────

    Future<void> addProduct(String id, String name,
            {int threshold = 0, bool archived = false}) =>
        db.into(db.products).insert(ProductsCompanion.insert(
              id: id,
              name: name,
              archived: Value(archived),
              minimumThreshold: Value(threshold),
              createdAt: DateTime(2025, 1, 1),
              updatedAt: DateTime(2025, 1, 1),
            ));

    Future<void> addLevel(String productId, String store, int qty) =>
        db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
              id: 'sl-$productId-$store',
              productId: productId,
              storeId: store,
              quantity: qty,
              updatedAt: DateTime(2025, 1, 1),
            ));

    test('getStoreStockDetail lists a product never stocked here at 0, CRITIQUE',
        () async {
      await addProduct('p-new', 'Attiéké');
      await addProduct('p-arch', 'Archivé', archived: true);

      final result = await datasource.getStoreStockDetail(storeId);

      expect(result.map((e) => e.productId), containsAll(['p-new', productId]));
      expect(result.map((e) => e.productId), isNot(contains('p-arch')));
      final rowless = result.firstWhere((e) => e.productId == 'p-new');
      expect(rowless.quantity, 0);
      expect(rowless.storeId, storeId);
      expect(rowless.status, 'CRITIQUE');
      expect(rowless.isCritical, isTrue);
      // No row was created by reading.
      expect(
          await (db.select(db.stockLevels)
                ..where((l) => l.productId.equals('p-new')))
              .get(),
          isEmpty);
    });

    test('getStoreStockDetail: negative stock is CRITIQUE, not OK', () async {
      await addProduct('p-sard', 'Sardines Boîte');
      await addLevel('p-sard', storeId, -1);

      final result = await datasource.getStoreStockDetail(storeId);

      final sardines = result.firstWhere((e) => e.productId == 'p-sard');
      expect(sardines.quantity, -1);
      expect(sardines.status, 'CRITIQUE');
      expect(sardines.isCritical, isTrue);
      expect(sardines.isLow, isFalse);
      // Sorted low-first: ruptures come before the healthy product (10).
      expect(result.last.productId, productId);
    });

    test('searchAcrossStores finds a product never stocked anywhere', () async {
      await addProduct('p-new', 'Attiéké');

      final result = await datasource.searchAcrossStores('attieke');

      expect(result, hasLength(1));
      expect(result.single.productId, 'p-new');
      expect(result.single.storeId, storeId);
      expect(result.single.quantity, 0);
      expect(result.single.status, 'CRITIQUE');
    });

    test('getStoreOverviews counts never-stocked and negative products as low',
        () async {
      await addProduct('p-new', 'Attiéké');
      await addProduct('p-sard', 'Sardines Boîte');
      await addLevel('p-sard', storeId, -1);
      await addProduct('p-arch', 'Archivé', archived: true);

      final overview = (await datasource.getStoreOverviews()).single;

      expect(overview.productCount, 2); // stocked here: Chaussures + Sardines
      expect(overview.lowStockCount, 2); // Attiéké (0) + Sardines (-1)
    });

    test('product-wide threshold applies where the product has no row',
        () async {
      await addProduct('p-thr', 'Bissap', threshold: 8);
      await addLevel('p-thr', storeId, 6);

      final result = await datasource.getStoreStockDetail(storeId);

      final bissap = result.firstWhere((e) => e.productId == 'p-thr');
      expect(bissap.minimumThreshold, 8);
      expect(bissap.status, 'BAS');
    });

    test('upsertStockLevels creates no row for a never-stocked product at 0',
        () async {
      await addProduct('p-new', 'Attiéké');

      await datasource.upsertStockLevels(storeId, [
        _buildEntry(productId: 'p-new', storeId: storeId, quantity: 0, threshold: 0),
      ]);

      expect(
          await (db.select(db.stockLevels)
                ..where((l) => l.productId.equals('p-new')))
              .get(),
          isEmpty);
      final result = await datasource.getStoreStockDetail(storeId);
      expect(result.firstWhere((e) => e.productId == 'p-new').quantity, 0);
    });
  });
}


