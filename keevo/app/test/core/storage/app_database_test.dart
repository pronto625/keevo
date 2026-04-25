import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:sqlite3/open.dart';

/// Opens the system SQLite library on Linux desktop for testing.
/// `sqlcipher_flutter_libs` only provides native libs for Android/iOS;
/// on the Linux test host we load the system `libsqlite3.so.0` directly.
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

  group('AppDatabase', () {
    late AppDatabase db;

    setUp(() {
      db = AppDatabase.forTesting(); // in-memory, no encryption
    });

    tearDown(() async => db.close());

    test('schemaVersion is 25', () => expect(db.schemaVersion, 25));

    test('Products table accepts integer price (XAF — no floats)', () async {
      const id = 'prod-001';
      await db.into(db.products).insert(ProductsCompanion.insert(
        id: id,
        name: 'Chemise bleue',
        price: const Value(15000),
        buyPrice: const Value(8000),
        stockQuantity: const Value(10),
        storeId: const Value('store-1'),
        createdAt: DateTime.now(),
        updatedAt: DateTime.now(),
      ));
      final p = await (db.select(db.products)
            ..where((t) => t.id.equals(id)))
          .getSingle();
      expect(p.price, isA<int>());
      expect(p.price, 15000);
    });

    test('StockMovements table stores quantityDelta as int', () async {
      await db.into(db.stockMovements).insert(StockMovementsCompanion.insert(
        id: 'mv-1',
        productId: 'prod-1',
        storeId: 'store-1',
        type: 'SALE',
        quantityDelta: -3,
        actorId: 'user-1',
        synced: const Value(false),
        createdAt: DateTime.now(),
      ));
      final mv = await db.select(db.stockMovements).getSingle();
      expect(mv.quantityDelta, -3);
    });

    test('sync_queue table still functional (not broken by migration)', () async {
      await db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
        id: 'sq-1',
        operation: 'TEST',
        payload: '{}',
        createdAt: DateTime.now(),
      ));
      final rows = await db.select(db.syncQueue).get();
      expect(rows.first.synced, false);
    });

    test('purgeOldTransactionalData deletes only synced rows older than 30 days', () async {
      final oldDate = DateTime.now().subtract(const Duration(days: 35));
      final recentDate = DateTime.now().subtract(const Duration(days: 5));

      // Old synced sale — should be purged
      await db.into(db.sales).insert(SalesCompanion.insert(
        id: 'sale-old',
        storeId: 's1',
        employeeId: 'e1',
        totalAmount: 5000,
        paymentMode: 'CASH',
        synced: const Value(true),
        syncedAt: Value(oldDate),
        createdAt: oldDate,
      ));

      // Recent synced sale — should survive
      await db.into(db.sales).insert(SalesCompanion.insert(
        id: 'sale-recent',
        storeId: 's1',
        employeeId: 'e1',
        totalAmount: 3000,
        paymentMode: 'CASH',
        synced: const Value(true),
        syncedAt: Value(recentDate),
        createdAt: recentDate,
      ));

      // Old unsynced sale — MUST NOT BE PURGED
      await db.into(db.sales).insert(SalesCompanion.insert(
        id: 'sale-unsynced',
        storeId: 's1',
        employeeId: 'e1',
        totalAmount: 2000,
        paymentMode: 'MOBILE_MONEY',
        synced: const Value(false),
        createdAt: oldDate,
      ));

      await db.purgeOldTransactionalData();

      final remaining = await db.select(db.sales).get();
      expect(remaining.map((s) => s.id).toList(),
          containsAll(['sale-recent', 'sale-unsynced']));
      expect(remaining.map((s) => s.id), isNot(contains('sale-old')));
    });

    test('purgeOldTransactionalData also removes sale_items for purged sales',
        () async {
      final oldDate = DateTime.now().subtract(const Duration(days: 35));
      final recentDate = DateTime.now().subtract(const Duration(days: 5));

      // Old synced sale with one item — both should be purged.
      await db.into(db.sales).insert(SalesCompanion.insert(
        id: 'sale-del',
        storeId: 's1',
        employeeId: 'e1',
        totalAmount: 1000,
        paymentMode: 'CASH',
        synced: const Value(true),
        syncedAt: Value(oldDate),
        createdAt: oldDate,
      ));
      await db.into(db.saleItems).insert(SaleItemsCompanion.insert(
        id: 'item-del',
        saleId: 'sale-del',
        productId: 'p1',
        productName: 'Item A',
        unitPrice: 1000,
        quantity: 1,
        subtotal: 1000,
        createdAt: oldDate,
      ));

      // Recent synced sale with one item — both should survive.
      await db.into(db.sales).insert(SalesCompanion.insert(
        id: 'sale-keep',
        storeId: 's1',
        employeeId: 'e1',
        totalAmount: 2000,
        paymentMode: 'CASH',
        synced: const Value(true),
        syncedAt: Value(recentDate),
        createdAt: recentDate,
      ));
      await db.into(db.saleItems).insert(SaleItemsCompanion.insert(
        id: 'item-keep',
        saleId: 'sale-keep',
        productId: 'p2',
        productName: 'Item B',
        unitPrice: 2000,
        quantity: 1,
        subtotal: 2000,
        createdAt: recentDate,
      ));

      await db.purgeOldTransactionalData();

      final items = await db.select(db.saleItems).get();
      expect(items.map((i) => i.id), isNot(contains('item-del')));
      expect(items.map((i) => i.id), contains('item-keep'));
    });
  });
}
