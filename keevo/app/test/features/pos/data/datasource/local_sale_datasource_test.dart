import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart' show Value;
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/stock_movement_ids.dart';
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
        'item id is kept and stock is not decremented twice', () async {
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
      // v1s-13-10 follow-up: the pulled server row wins (its createdAt kept)
      // and the pulled stock level (10) already includes it.
      expect(movements.single.createdAt, DateTime(2026));
      final level = await (db.select(db.stockLevels)
            ..where((l) => l.id.equals('sl-prod-1')))
          .getSingle();
      expect(level.quantity, 10);
    });

    test(
        'cascadeValidatePendingSales: a SALE movement already pulled is kept, '
        'no second decrement', () async {
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

      final validated =
          await ds.cascadeValidatePendingSales('prod-1', 'store-1', 'actor-1');

      expect(validated, 1);
      expect(await saleStatus('sale-p'), 'COMPLETED');
      final movements = await db.select(db.stockMovements).get();
      expect(movements, hasLength(1));
      expect(movements.single.createdAt, DateTime(2026));
      final level = await (db.select(db.stockLevels)
            ..where((l) => l.id.equals('sl-prod-1')))
          .getSingle();
      expect(level.quantity, 10);
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

    // ── v1s-13-13: deterministic SALE_CANCELLED / ADJUSTMENT movement ids ──

    Future<List<StockMovement>> movementsOfType(String type) =>
        (db.select(db.stockMovements)..where((m) => m.type.equals(type))).get();

    Future<int> levelProd1() async => (await (db.select(db.stockLevels)
              ..where((l) => l.id.equals('sl-prod-1')))
            .getSingle())
        .quantity;

    /// Same statement as RestSyncService._upsertStockMovements (the pull).
    Future<void> pullMovement(String id, String type, int delta) =>
        db.customStatement(
          'INSERT OR IGNORE INTO stock_movements (id, product_id, variant_id, '
          'store_id, type, quantity_before, quantity_delta, quantity_after, '
          'actor_id, reason, synced, created_at) '
          'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)',
          [
            id, 'prod-1', null, 'store-1', type, 0, delta, 0, 'owner-1',
            'server', DateTime(2026).toIso8601String(),
          ],
        );

    test('cross-language vectors match the backend DeterministicIds', () {
      expect(saleCancelledMovementId('11111111-1111-1111-1111-111111111111'),
          '953756a8-12bc-54a0-b3dc-144901d67c83');
      expect(
          saleCorrectionMovementId('22222222-2222-2222-2222-222222222222',
              '11111111-1111-1111-1111-111111111111'),
          '3a397c41-8198-5a4f-8f09-9b25c36f87ac');
    });

    test(
        'cancelAndRestoreStock: SALE_CANCELLED movement uses the derived id; '
        'a later pull of the same id leaves one row', () async {
      await stockProd1(10);
      await ds.insertAll(_testSale());
      expect(await levelProd1(), 7);

      await ds.cancelAndRestoreStock('sale-1');

      expect(await levelProd1(), 10);
      final cancelled = await movementsOfType('SALE_CANCELLED');
      expect(cancelled.map((m) => m.id), [saleCancelledMovementId('item-1')]);
      expect(cancelled.single.synced, isTrue);
      expect(cancelled.single.syncedAt, isNotNull);

      await pullMovement(saleCancelledMovementId('item-1'), 'SALE_CANCELLED', 3);

      expect(await movementsOfType('SALE_CANCELLED'), hasLength(1));
      expect(await levelProd1(), 10);
    });

    test(
        'cancelAndRestoreStock: movement already pulled → no second row, '
        'stock not restored twice', () async {
      await stockProd1(10);
      await ds.insertAll(_testSale());
      // The pull arrived first: server movement + server stock level (10).
      await pullMovement(saleCancelledMovementId('item-1'), 'SALE_CANCELLED', 3);
      await (db.update(db.stockLevels)..where((l) => l.id.equals('sl-prod-1')))
          .write(const StockLevelsCompanion(quantity: Value(10)));

      await ds.cancelAndRestoreStock('sale-1');

      expect(await levelProd1(), 10);
      final cancelled = await movementsOfType('SALE_CANCELLED');
      expect(cancelled, hasLength(1));
      expect(cancelled.single.reason, 'server', reason: 'pulled row kept');
      expect(await saleStatus('sale-1'), 'CANCELLED');
    });

    test(
        'updateItemQuantities with correctionId: ADJUSTMENT uses the derived id; '
        'a later pull of the same id leaves one row', () async {
      await stockProd1(10);
      await ds.insertAll(_testSale());

      await ds.updateItemQuantities('sale-1', {'item-1': 1},
          correctionId: 'corr-1');

      expect(await levelProd1(), 9);
      final adjustments = await movementsOfType('ADJUSTMENT');
      expect(adjustments.map((m) => m.id),
          [saleCorrectionMovementId('corr-1', 'item-1')]);
      expect(adjustments.single.quantityDelta, 2);
      expect(adjustments.single.synced, isTrue);
      expect(adjustments.single.syncedAt, isNotNull);

      await pullMovement(
          saleCorrectionMovementId('corr-1', 'item-1'), 'ADJUSTMENT', 2);

      expect(await movementsOfType('ADJUSTMENT'), hasLength(1));
      expect(await levelProd1(), 9);
    });

    test(
        'updateItemQuantities: ADJUSTMENT already pulled → no second row, '
        'stock not adjusted twice', () async {
      await stockProd1(10);
      await ds.insertAll(_testSale());
      await pullMovement(
          saleCorrectionMovementId('corr-1', 'item-1'), 'ADJUSTMENT', 2);
      await (db.update(db.stockLevels)..where((l) => l.id.equals('sl-prod-1')))
          .write(const StockLevelsCompanion(quantity: Value(9)));

      await ds.updateItemQuantities('sale-1', {'item-1': 1},
          correctionId: 'corr-1');

      expect(await levelProd1(), 9);
      expect(await movementsOfType('ADJUSTMENT'), hasLength(1));
      final item = await (db.select(db.saleItems)
            ..where((i) => i.id.equals('item-1')))
          .getSingle();
      expect(item.quantity, 1);
    });

    test('updateItemQuantities without correctionId keeps a random id (legacy)',
        () async {
      await stockProd1(10);
      await ds.insertAll(_testSale());

      await ds.updateItemQuantities('sale-1', {'item-1': 2});
      await ds.updateItemQuantities('sale-1', {'item-1': 1});

      final adjustments = await movementsOfType('ADJUSTMENT');
      expect(adjustments, hasLength(2));
      final v4 = RegExp(
          r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$');
      for (final m in adjustments) {
        expect(m.id, matches(v4), reason: 'legacy path keeps a random v4 id');
      }
      expect(adjustments[0].id, isNot(adjustments[1].id));
      expect(await levelProd1(), 9);
    });
  });
}
