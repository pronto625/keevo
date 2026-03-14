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

    test('upsertStockLevels writes remote entries into stock_levels', () async {
      final entries = [
        _buildEntry(productId: productId, storeId: storeId, quantity: 25, threshold: 5),
      ];
      await datasource.upsertStockLevels(storeId, entries);

      final result = await datasource.getStoreStockDetail(storeId);
      expect(result.first.quantity, 25);
    });
  });
}


