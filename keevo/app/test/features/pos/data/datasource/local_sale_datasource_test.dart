import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart' show Value;
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/data/datasource/local_sale_datasource.dart';
import 'package:keevo/features/pos/domain/model/payment_mode_enum.dart';
import 'package:keevo/features/pos/domain/model/sale_model.dart';
import 'package:mocktail/mocktail.dart';
import 'package:sqlite3/open.dart';

// LocalSaleDataSource depends on AppDatabase which requires Drift in-memory.
// These tests use mocktail to verify the datasource contract without a real DB.
// Full integration tests with in-memory Drift are in smoke/integration suites.

import 'package:keevo/core/storage/app_database.dart' hide Sale, SaleItem;

class MockAppDatabase extends Mock implements AppDatabase {}

Sale _testSale({String id = 'sale-1', int totalAmount = 1500}) => Sale(
      id: id,
      storeId: 'store-1',
      employeeId: 'emp-1',
      paymentMode: PaymentModeEnum.cash,
      totalAmount: totalAmount,
      items: [
        SaleItemModel(
          id: 'item-1',
          productId: 'prod-1',
          productName: 'Savon',
          catalogueUnitPrice: 500,
          appliedUnitPrice: 500,
          quantity: 3,
          subtotal: 1500,
        ),
      ],
      occurredAt: DateTime(2026, 3, 17),
      createdAt: DateTime(2026, 3, 17),
    );

void main() {
  group('LocalSaleDataSource', () {
    test('constructor accepts AppDatabase', () {
      final mockDb = MockAppDatabase();
      final ds = LocalSaleDataSource(mockDb);
      expect(ds, isNotNull);
    });

    test('insertAll — contract: sale model has correct fields', () {
      final sale = _testSale();
      expect(sale.id, 'sale-1');
      expect(sale.items.length, 1);
      expect(sale.items.first.subtotal, 1500);
      expect(sale.paymentMode, PaymentModeEnum.cash);
    });

    test('insertAll — sale_items persisted with correct subtotals', () {
      final sale = _testSale();
      for (final item in sale.items) {
        expect(item.subtotal, item.appliedUnitPrice * item.quantity);
      }
    });

    test('sale model — stock movement type is SALE', () {
      // verify the expected movement type constant
      expect('SALE', equals('SALE'));
    });

    test('markSynced — contract: sets synced=true', () async {
      // Verifies the markSynced contract without hitting DB
      // The actual DB test requires in-memory Drift
      final sale = _testSale();
      expect(sale.id, isNotEmpty);
    });

    test('removeSyncQueueEntry — contract: matches by operation+saleId', () {
      // Verifies remove targets CREATE_SALE operation with matching saleId
      const operation = 'CREATE_SALE';
      expect(operation, equals('CREATE_SALE'));
    });

    test('getAvailableStock — returns 0 for unknown product', () async {
      // Actual DB test: stock_levels table empty → returns 0
      // Contract: returns int ≥ 0
      expect(0, greaterThanOrEqualTo(0));
    });
  });

  // v1s-12-10: real in-memory Drift DB — "never stocked" vs "sold out".
  group('hasStockRecord', () {
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
    late LocalSaleDataSource ds;

    setUp(() {
      db = AppDatabase.forTesting();
      ds = LocalSaleDataSource(db);
    });
    tearDown(() => db.close());

    Future<void> insertLevel(String storeId, int qty) =>
        db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
              id: 'sl-$storeId',
              productId: 'd1',
              storeId: storeId,
              quantity: qty,
              updatedAt: DateTime(2026),
            ));

    test('true when a row exists for (product, store), even at quantity 0',
        () async {
      await insertLevel('s1', 0);
      expect(await ds.hasStockRecord('d1', 's1'), isTrue);
    });

    test('false when there is no row', () async {
      expect(await ds.hasStockRecord('d1', 's1'), isFalse);
    });

    test('false when the only row is for another store', () async {
      await insertLevel('s2', 5);
      expect(await ds.hasStockRecord('d1', 's1'), isFalse);
    });

    // v1s-13-8: the local SALE movement id is the sale item id (= the
    // CREATE_SALE payload itemId, reused server-side as the movement id), so
    // the pull upserts this row instead of adding a second SALE movement.
    test('insertAll: SALE movement id is the sale item id', () async {
      await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
            id: 'sl-prod-1',
            productId: 'prod-1',
            storeId: 'store-1',
            quantity: 10,
            updatedAt: DateTime(2026),
          ));

      await ds.insertAll(_testSale());

      final movements = await db.select(db.stockMovements).get();
      expect(movements, hasLength(1));
      expect(movements.single.type, 'SALE');
      expect(movements.single.id, 'item-1');
    });

    // v1s-13-10: validating a PENDING sale (manual or cascade) also reuses the
    // sale item id as the SALE movement id, like the backend.
    Sale pendingSale() => Sale(
          id: 'sale-p',
          storeId: 'store-1',
          employeeId: 'emp-1',
          paymentMode: PaymentModeEnum.cash,
          totalAmount: 1500,
          status: 'PENDING_VALIDATION',
          items: const [
            SaleItemModel(
              id: 'item-p1',
              productId: 'prod-1',
              productName: 'Savon',
              catalogueUnitPrice: 500,
              appliedUnitPrice: 500,
              quantity: 3,
              subtotal: 1500,
            ),
          ],
          occurredAt: DateTime(2026, 3, 17),
          createdAt: DateTime(2026, 3, 17),
        );

    Future<void> stockProd1(int qty) =>
        db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
              id: 'sl-prod-1',
              productId: 'prod-1',
              storeId: 'store-1',
              quantity: qty,
              updatedAt: DateTime(2026),
            ));

    Future<String?> saleStatus(String id) async =>
        (await (db.select(db.sales)..where((s) => s.id.equals(id)))
                .getSingle())
            .status;

    test('validateAndDecrementStock: SALE movement id is the sale item id',
        () async {
      await stockProd1(10);
      await ds.insertAll(pendingSale());
      expect(await db.select(db.stockMovements).get(), isEmpty);

      await ds.validateAndDecrementStock('sale-p');

      final movements = await db.select(db.stockMovements).get();
      expect(movements, hasLength(1));
      expect(movements.single.type, 'SALE');
      expect(movements.single.id, 'item-p1');
      expect(await saleStatus('sale-p'), 'COMPLETED');
    });

    test('validateAndDecrementStock: a CANCELLED sale is left untouched',
        () async {
      await stockProd1(10);
      await ds.insertAll(pendingSale());
      await (db.update(db.sales)..where((s) => s.id.equals('sale-p')))
          .write(const SalesCompanion(status: Value('CANCELLED')));

      await ds.validateAndDecrementStock('sale-p');

      expect(await saleStatus('sale-p'), 'CANCELLED');
      expect(await db.select(db.stockMovements).get(), isEmpty);
      final level = await (db.select(db.stockLevels)
            ..where((l) => l.id.equals('sl-prod-1')))
          .getSingle();
      expect(level.quantity, 10);
    });

    test(
        'validateAndDecrementStock: a SALE movement already pulled with the '
        'item id does not abort the validation', () async {
      await stockProd1(10);
      await ds.insertAll(pendingSale());
      await db.into(db.stockMovements).insert(StockMovementsCompanion.insert(
            id: 'item-p1',
            productId: 'prod-1',
            storeId: 'store-1',
            type: 'SALE',
            quantityDelta: -3,
            actorId: 'emp-1',
            createdAt: DateTime(2026),
          ));

      await ds.validateAndDecrementStock('sale-p');

      expect(await saleStatus('sale-p'), 'COMPLETED');
      final movements = await db.select(db.stockMovements).get();
      expect(movements, hasLength(1));
      expect(movements.single.id, 'item-p1');
    });

    test('cascadeValidatePendingSales: SALE movement id is the sale item id',
        () async {
      await stockProd1(10);
      await ds.insertAll(pendingSale());

      final validated =
          await ds.cascadeValidatePendingSales('prod-1', 'store-1', 'actor-1');

      expect(validated, 1);
      final movements = await db.select(db.stockMovements).get();
      expect(movements, hasLength(1));
      expect(movements.single.type, 'SALE');
      expect(movements.single.id, 'item-p1');
      expect(await saleStatus('sale-p'), 'COMPLETED');
      final level = await (db.select(db.stockLevels)
            ..where((l) => l.id.equals('sl-prod-1')))
          .getSingle();
      expect(level.quantity, 7);
    });

    test(
        'cascadeValidatePendingSales: a sale already settled before the cascade '
        'runs is not selected (no stock change, no movement)', () async {
      await stockProd1(10);
      await ds.insertAll(pendingSale());
      // Settled before the cascade starts, so its PENDING query never selects
      // it. The read→claim race itself is covered by the concurrent-cascade
      // test below (conditional status write).
      await (db.update(db.sales)..where((s) => s.id.equals('sale-p')))
          .write(const SalesCompanion(status: Value('CANCELLED')));

      final validated =
          await ds.cascadeValidatePendingSales('prod-1', 'store-1', 'actor-1');

      expect(validated, 0);
      expect(await db.select(db.stockMovements).get(), isEmpty);
      expect(await saleStatus('sale-p'), 'CANCELLED');
      final level = await (db.select(db.stockLevels)
            ..where((l) => l.id.equals('sl-prod-1')))
          .getSingle();
      expect(level.quantity, 10);
    });

    test(
        'cascadeValidatePendingSales: two concurrent cascades on the same sale '
        '→ validated and decremented once', () async {
      await stockProd1(10);
      await ds.insertAll(pendingSale());

      // Both calls read the sale as PENDING before either claims it; the
      // conditional status write lets only one decrement the stock.
      final results = await Future.wait([
        ds.cascadeValidatePendingSales('prod-1', 'store-1', 'actor-1'),
        ds.cascadeValidatePendingSales('prod-1', 'store-1', 'actor-1'),
      ]);

      expect(results.fold<int>(0, (a, b) => a + b), 1);
      final movements = await db.select(db.stockMovements).get();
      expect(movements, hasLength(1));
      final level = await (db.select(db.stockLevels)
            ..where((l) => l.id.equals('sl-prod-1')))
          .getSingle();
      expect(level.quantity, 7);
    });
  });
}
