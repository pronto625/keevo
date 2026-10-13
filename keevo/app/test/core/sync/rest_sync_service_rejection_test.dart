import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:drift/drift.dart' show Value;
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/core/sync/rest_sync_service.dart';
import 'package:keevo/core/sync/sync_event_logger.dart';
import 'package:keevo/core/sync/sync_quarantine_service.dart';
import 'package:keevo/features/catalog/data/datasource/remote_product_datasource.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqlite3/open.dart';

class MockDio extends Mock implements Dio {}

class MockRemoteProductDataSource extends Mock
    implements RemoteProductDataSource {}

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

/// A REJECTED sync operation must not go unnoticed forever: the previous
/// behaviour only incremented retryCount and retried silently, leaving the
/// user with no idea an offline operation would never succeed (duplicate
/// name, plan limit, etc). RestSyncService now surfaces a rejection once per
/// distinct reason and records it on the row (`lastError`) so the queue
/// screen can display it too.
void main() {
  setUpAll(() {
    _overrideSqlite3ForLinuxTesting();
  });

  late AppDatabase db;
  late MockDio mockDio;
  late RestSyncService syncService;
  late SharedPreferences prefs;

  setUp(() async {
    db = AppDatabase.forTesting();
    mockDio = MockDio();
    FlutterSecureStorage.setMockInitialValues({});
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();

    syncService = RestSyncService(
      database: db,
      remoteProducts: MockRemoteProductDataSource(),
      dio: mockDio,
      secureStorage: const FlutterSecureStorage(),
      prefs: prefs,
    );
  });

  tearDown(() async {
    await db.close();
  });

  var opClock = DateTime(2026, 10, 1);
  Future<void> insertPendingOp({
    required String id,
    required String operation,
    String? entityId,
    Map<String, dynamic> payload = const {},
  }) {
    // Strictly increasing creation times: push order is deterministic.
    opClock = opClock.add(const Duration(seconds: 1));
    return db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
          id: id,
          operation: operation,
          payload: jsonEncode(payload),
          createdAt: opClock,
          entityId: Value(entityId),
        ));
  }

  /// Stubs one push answering each (operationId, status, reason).
  void stubPushResults(List<(String, String, String?)> results) {
    when(() => mockDio.post<dynamic>(any(), data: any(named: 'data')))
        .thenAnswer((_) async => Response(
              requestOptions: RequestOptions(path: '/api/v1/sync/push'),
              statusCode: 200,
              data: {
                'data': {
                  'results': [
                    for (final (id, status, reason) in results)
                      {
                        'operationId': id,
                        'status': status,
                        'serverEntityId': null,
                        'reason': reason,
                        'conflictData': null,
                      }
                  ],
                },
              },
            ));
  }

  Future<List<String>> queuedIds() async =>
      (await db.select(db.syncQueue).get()).map((o) => o.id).toList();

  void stubPushResponse(String opId, String status,
      {String? reason, String? serverEntityId}) {
    when(() => mockDio.post<dynamic>(any(), data: any(named: 'data')))
        .thenAnswer((_) async => Response(
              requestOptions: RequestOptions(path: '/api/v1/sync/push'),
              statusCode: 200,
              data: {
                'data': {
                  'results': [
                    {
                      'operationId': opId,
                      'status': status,
                      'serverEntityId': serverEntityId,
                      'reason': reason,
                      'conflictData': null,
                    }
                  ],
                },
              },
            ));
  }

  test('first REJECTED occurrence is surfaced with operation + entity info',
      () async {
    await insertPendingOp(
        id: 'op-1', operation: 'CREATE_SALE', entityId: 'sale-1');
    stubPushResponse('op-1', 'REJECTED', reason: 'INSUFFICIENT_STOCK');

    final result = await syncService.push();

    expect(result, hasLength(1));
    expect(result.single['status'], 'REJECTED');
    expect(result.single['reason'], 'INSUFFICIENT_STOCK');
    expect(result.single['operation'], 'CREATE_SALE');
    expect(result.single['entityId'], 'sale-1');
    expect(result.single['rolledBack'], false);
    expect(result.single['quarantined'], true);

    final row =
        await (db.select(db.syncQueue)..where((t) => t.id.equals('op-1')))
            .getSingle();
    expect(row.lastError, 'INSUFFICIENT_STOCK');
    expect(row.retryCount, 1);
    expect(row.quarantinedAt, isNotNull);
  });

  // A transient refusal of a money operation keeps the 16-12 behaviour:
  // kept pending, retried, surfaced once per reason.
  test('repeated REJECTED with the same reason is not re-surfaced', () async {
    await insertPendingOp(
        id: 'op-1', operation: 'CREATE_SALE', entityId: 'sale-1');
    stubPushResponse('op-1', 'REJECTED', reason: 'OPTIMISTIC_LOCK');

    await syncService.push(); // first attempt — surfaced
    final second = await syncService.push(); // retried by the queue, same reason

    expect(second, isEmpty);

    final row =
        await (db.select(db.syncQueue)..where((t) => t.id.equals('op-1')))
            .getSingle();
    expect(row.retryCount, 2, reason: 'retries keep counting even when not renotified');
    expect(row.quarantinedAt, isNull, reason: 'transient: never quarantined');
  });

  test('REJECTED with a changed reason is surfaced again', () async {
    await insertPendingOp(
        id: 'op-1', operation: 'CREATE_SALE', entityId: 'sale-1');
    stubPushResponse('op-1', 'REJECTED', reason: 'OPTIMISTIC_LOCK');
    await syncService.push();

    stubPushResponse('op-1', 'REJECTED', reason: 'INTERNAL_ERROR');
    final second = await syncService.push();

    expect(second, hasLength(1));
    expect(second.single['reason'], 'INTERNAL_ERROR');
  });

  test('a rejected money/stock-ledger operation is quarantined, not dropped',
      () async {
    final now = DateTime.now();
    await db.into(db.sales).insert(SalesCompanion.insert(
          id: 'sale-1',
          storeId: 'store-1',
          employeeId: 'u',
          totalAmount: 1000,
          paymentMode: 'CASH',
          createdAt: now,
        ));
    await insertPendingOp(
        id: 'op-1', operation: 'CREATE_SALE', entityId: 'sale-1');
    stubPushResponse('op-1', 'REJECTED', reason: 'INSUFFICIENT_STOCK');

    await syncService.push();

    expect(await queuedIds(), ['op-1']);
    expect(await db.select(db.sales).get(), hasLength(1),
        reason: 'zero sales lost: the sale is never rolled back');
    expect(await syncService.hasPendingOperations(), false,
        reason: 'a quarantined op no longer blocks the server refreshes');
    expect(await syncService.quarantinedOperationCount(), 1);
  });

  group('v1s-16-12 — terminal rejection reconciliation', () {
    final now = DateTime(2026, 10, 1);

    Future<void> product(String id, {String? categoryId}) =>
        db.into(db.products).insert(ProductsCompanion.insert(
            id: id,
            name: 'P $id',
            categoryId: Value(categoryId),
            createdAt: now,
            updatedAt: now));

    Future<void> stockLevel(String id, String productId, int quantity) =>
        db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
            id: id,
            productId: productId,
            storeId: 'store-1',
            quantity: quantity,
            updatedAt: now));

    Future<void> session(String id, String status) =>
        db.into(db.inventorySessions).insert(InventorySessionsCompanion.insert(
            id: id,
            storeId: 'store-1',
            scope: 'FULL',
            status: status,
            startedBy: '',
            startedAt: now,
            updatedAt: now,
            synced: const Value(false)));

    Future<void> adjustment(String id, String sessionId, String productId,
            int before, int delta) =>
        db.into(db.stockMovements).insert(StockMovementsCompanion.insert(
            id: id,
            productId: productId,
            storeId: 'store-1',
            type: 'ADJUSTMENT',
            quantityBefore: Value(before),
            quantityDelta: delta,
            quantityAfter: Value(before + delta),
            actorId: 'u',
            source: const Value('INVENTORY'),
            inventorySessionId: Value(sessionId),
            createdAt: now));

    Future<InventorySession> sessionRow(String id) =>
        (db.select(db.inventorySessions)..where((t) => t.id.equals(id)))
            .getSingle();

    Future<int> quantity(String stockLevelId) async =>
        (await (db.select(db.stockLevels)
                  ..where((t) => t.id.equals(stockLevelId)))
                .getSingle())
            .quantity;

    test(
        'rejected CREATE_STORE rolls the store back, drops its follow-ups and '
        'clears the active-store pref', () async {
      await db.into(db.stores).insert(StoresCompanion.insert(
          id: 'store-x',
          name: 'Annexe',
          tenantId: 't',
          createdAt: now,
          updatedAt: now));
      await prefs.setString('active_store_id', 'store-x');
      await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
          id: 'sl-x', productId: 'p1', storeId: 'store-x', quantity: 4,
          updatedAt: now));
      await stockLevel('sl-1', 'p1', 9); // store-1: untouched
      await db.into(db.stockMovements).insert(StockMovementsCompanion.insert(
          id: 'm-x', productId: 'p1', storeId: 'store-x',
          type: 'STOCK_ENTRY', quantityDelta: 4, actorId: 'u',
          createdAt: now));
      for (final (id, source, destination) in const [
        ('tr-out', 'store-x', 'store-1'),
        ('tr-in', 'store-1', 'store-x'),
        ('tr-other', 'store-1', 'store-2'),
      ]) {
        await db.into(db.stockTransfers).insert(StockTransfersCompanion.insert(
            id: id,
            sourceStoreId: source,
            destinationStoreId: destination,
            productId: 'p1',
            quantity: 1,
            actorId: 'u',
            occurredAt: now));
      }
      await insertPendingOp(
          id: 'op-1', operation: 'CREATE_STORE', entityId: 'store-x');
      await insertPendingOp(
          id: 'op-2', operation: 'UPDATE_STORE', entityId: 'store-x');
      await insertPendingOp(
          id: 'op-3', operation: 'UPDATE_CLIENT', entityId: 'client-1');
      await insertPendingOp(
          id: 'op-4',
          operation: 'RECORD_STOCK_ENTRY',
          entityId: 'sl-x',
          payload: {'productId': 'p1', 'storeId': 'store-x', 'quantity': 4});
      await insertPendingOp(
          id: 'op-5',
          operation: 'STOCK_TRANSFER',
          entityId: 't-1',
          payload: {'sourceStoreId': 'store-1', 'destinationStoreId': 'store-x'});
      await insertPendingOp(
          id: 'op-6',
          operation: 'CREATE_SALE',
          entityId: 'sale-1',
          payload: {'storeId': 'store-x'});
      stubPushResults([
        ('op-1', 'REJECTED', 'PLAN_LIMIT_EXCEEDED'),
        ('op-2', 'REJECTED', 'STORE_NOT_FOUND'),
        ('op-3', 'APPLIED', null),
        ('op-4', 'REJECTED', 'STORE_NOT_FOUND'),
        ('op-5', 'REJECTED', 'STORE_NOT_FOUND'),
        ('op-6', 'REJECTED', 'STORE_NOT_FOUND'),
      ]);

      final result = await syncService.push();

      expect(await db.select(db.stores).get(), isEmpty);
      expect((await db.select(db.stockLevels).get()).map((l) => l.id),
          ['sl-1']);
      expect(await db.select(db.stockMovements).get(), isEmpty);
      expect(await queuedIds(), ['op-6'], reason: 'a sale is never dropped');
      expect(prefs.getString('active_store_id'), isNull);
      expect(result.map((r) => r['operation']), ['CREATE_STORE', 'CREATE_SALE'],
          reason: 'cascaded rejections are not surfaced');
      expect(result.first['rolledBack'], true);
      // v1s-16-15: the ghost store's local transfer history goes too, and the
      // sale it can never be recorded in is quarantined.
      expect((await db.select(db.stockTransfers).get()).map((t) => t.id),
          ['tr-other']);
      expect(result.last['quarantined'], true);
      expect(await syncService.hasPendingOperations(), false);
    });

    test(
        'rejected CREATE_PRODUCT (PLAN_LIMIT_EXCEEDED) removes the product, its '
        'stock and counts, and its queued stock/count ops — never a sale',
        () async {
      await product('prod-x');
      await product('other');
      await stockLevel('sl-x', 'prod-x', 5);
      await stockLevel('sl-other', 'other', 3);
      await db.into(db.inventoryCounts).insert(InventoryCountsCompanion.insert(
          id: 'c-x',
          sessionId: 's-1',
          productId: 'prod-x',
          productName: 'P',
          theoretical: 0,
          updatedAt: now));
      await insertPendingOp(
          id: 'op-1', operation: 'CREATE_PRODUCT', entityId: 'prod-x');
      await insertPendingOp(
          id: 'op-2',
          operation: 'RECORD_STOCK_ENTRY',
          entityId: 'sl-x',
          payload: {'productId': 'prod-x', 'quantity': 5});
      await insertPendingOp(
          id: 'op-3',
          operation: 'SAVE_INVENTORY_COUNT',
          entityId: 'c-x',
          payload: {'sessionId': 's-1', 'productId': 'prod-x'});
      await insertPendingOp(
          id: 'op-4',
          operation: 'CREATE_SALE',
          entityId: 'sale-1',
          payload: {'productId': 'prod-x'});
      stubPushResults([
        ('op-1', 'REJECTED', 'PLAN_LIMIT_EXCEEDED'),
        ('op-2', 'REJECTED', 'PRODUCT_NOT_FOUND'),
        ('op-3', 'REJECTED', 'PRODUCT_NOT_FOUND'),
        ('op-4', 'APPLIED', null),
      ]);

      final result = await syncService.push();

      expect((await db.select(db.products).get()).map((p) => p.id), ['other']);
      expect((await db.select(db.stockLevels).get()).map((s) => s.id),
          ['sl-other']);
      expect(await db.select(db.inventoryCounts).get(), isEmpty);
      expect(await queuedIds(), isEmpty);
      expect(result, hasLength(1));
      expect(result.single['reason'], 'PLAN_LIMIT_EXCEEDED');
      expect(result.single['rolledBack'], true);
    });

    test('a cascaded sale is kept even when still queued', () async {
      await product('prod-x');
      await insertPendingOp(
          id: 'op-1', operation: 'CREATE_PRODUCT', entityId: 'prod-x');
      await insertPendingOp(
          id: 'op-2',
          operation: 'CREATE_SALE',
          entityId: 'sale-1',
          payload: {'productId': 'prod-x'});
      stubPushResults([
        ('op-1', 'REJECTED', 'VALIDATION_ERROR'),
        ('op-2', 'REJECTED', 'PRODUCT_NOT_FOUND'),
      ]);

      final result = await syncService.push();

      expect(await queuedIds(), ['op-2']);
      expect(result.map((r) => r['operation']),
          ['CREATE_PRODUCT', 'CREATE_SALE']);
    });

    test('rejected CREATE_CATEGORY deletes it and un-categorises its products',
        () async {
      await db.into(db.categories).insert(CategoriesCompanion.insert(
          id: 'cat-x', name: 'Ghost', createdAt: now, updatedAt: now));
      await db.into(db.categories).insert(CategoriesCompanion.insert(
          id: 'cat-sub',
          name: 'Sub',
          parentId: const Value('cat-x'),
          createdAt: now,
          updatedAt: now));
      await product('prod-1', categoryId: 'cat-x');
      await insertPendingOp(
          id: 'op-1', operation: 'CREATE_CATEGORY', entityId: 'cat-x');
      await insertPendingOp(
          id: 'op-2',
          operation: 'CREATE_PRODUCT',
          entityId: 'prod-1',
          payload: {'id': 'prod-1', 'name': 'P', 'categoryId': 'cat-x'});
      await insertPendingOp(
          id: 'op-3',
          operation: 'UPDATE_PRODUCT',
          entityId: 'prod-1',
          payload: {'id': 'prod-1', 'categoryId': 'cat-other'});
      stubPushResults([
        ('op-1', 'REJECTED', 'FORBIDDEN'),
        // Sent in the same batch with the ghost category: refused, but the
        // rewritten payload is retried — the product is not rolled back.
        ('op-2', 'REJECTED', 'CATEGORY_NOT_FOUND'),
        ('op-3', 'REJECTED', 'OPTIMISTIC_LOCK'),
      ]);

      final result = await syncService.push();

      expect((await db.select(db.categories).get()).map((c) => c.id),
          ['cat-sub']);
      expect((await db.select(db.categories).getSingle()).parentId, isNull);
      final p = await db.select(db.products).getSingle();
      expect(p.categoryId, isNull);
      expect(await queuedIds(), ['op-2', 'op-3']);
      final queued = {
        for (final o in await db.select(db.syncQueue).get())
          o.id: jsonDecode(o.payload) as Map<String, dynamic>
      };
      expect(queued['op-2']!.containsKey('categoryId'), false);
      expect(queued['op-3']!['categoryId'], 'cat-other');
      expect(result.map((r) => r['operation']),
          ['CREATE_CATEGORY', 'UPDATE_PRODUCT']);
      expect(result.first['rolledBack'], true);
    });

    test(
        'rejected CREATE_INVENTORY_SESSION cancels the session, reverts its '
        'offline adjustments and drops its counts and validation', () async {
      await session('s-1', 'VALIDATED');
      await product('prod-1');
      await stockLevel('sl-1', 'prod-1', 8); // 10 before, adjusted by -2
      await adjustment('m-1', 's-1', 'prod-1', 10, -2);
      await insertPendingOp(
          id: 'op-1', operation: 'CREATE_INVENTORY_SESSION', entityId: 's-1');
      await insertPendingOp(
          id: 'op-2',
          operation: 'SAVE_INVENTORY_COUNT',
          entityId: 'c-1',
          payload: {'sessionId': 's-1', 'productId': 'prod-1'});
      await insertPendingOp(
          id: 'op-3',
          operation: 'VALIDATE_INVENTORY',
          entityId: 's-1',
          payload: {'sessionId': 's-1'});
      await insertPendingOp(
          id: 'op-4',
          operation: 'SAVE_INVENTORY_COUNT',
          entityId: 'c-other',
          payload: {'sessionId': 's-2', 'productId': 'prod-1'});
      stubPushResults([
        ('op-1', 'REJECTED', 'INVENTORY_SESSION_ALREADY_ACTIVE'),
        ('op-2', 'REJECTED', 'INVENTORY_SESSION_NOT_FOUND'),
        ('op-3', 'REJECTED', 'INVENTORY_SESSION_NOT_FOUND'),
        ('op-4', 'REJECTED', 'OPTIMISTIC_LOCK'),
      ]);

      final result = await syncService.push();

      final s = await sessionRow('s-1');
      expect(s.status, 'CANCELLED');
      expect(s.cancelledAt, isNotNull);
      expect(await quantity('sl-1'), 10);
      expect(await db.select(db.stockMovements).get(), isEmpty);
      expect(await queuedIds(), ['op-4'],
          reason: 'only the other session\'s count stays');
      // The cascaded session ops are not surfaced; the other session's
      // transient refusal is (once).
      expect(result.map((r) => r['entityId']), ['s-1', 'c-other']);
      expect(result.first['rolledBack'], true);
      expect(result.last['rolledBack'], false);
    });

    test('INVENTORY_SESSION_NOT_FOUND on a count abandons the session too',
        () async {
      await session('s-1', 'IN_PROGRESS');
      await insertPendingOp(
          id: 'op-1',
          operation: 'SAVE_INVENTORY_COUNT',
          entityId: 'c-1',
          payload: {'sessionId': 's-1', 'productId': 'prod-1'});
      stubPushResults([('op-1', 'REJECTED', 'INVENTORY_SESSION_NOT_FOUND')]);

      await syncService.push();

      expect((await sessionRow('s-1')).status, 'CANCELLED');
      expect(await queuedIds(), isEmpty);
    });

    test(
        'INVENTORY_SESSION_NOT_IN_PROGRESS reverts the adjustments, drops the '
        'session ops and hands the session back to pull', () async {
      await session('s-1', 'VALIDATED');
      await product('prod-1');
      await stockLevel('sl-1', 'prod-1', 1); // 0 before... floored at 0
      await adjustment('m-1', 's-1', 'prod-1', 0, 3);
      await insertPendingOp(
          id: 'op-1',
          operation: 'SAVE_INVENTORY_COUNT',
          entityId: 'c-1',
          payload: {'sessionId': 's-1', 'productId': 'prod-1'});
      await insertPendingOp(
          id: 'op-2',
          operation: 'VALIDATE_INVENTORY',
          entityId: 's-1',
          payload: {'sessionId': 's-1'});
      stubPushResults([
        ('op-1', 'REJECTED', 'INVENTORY_SESSION_NOT_IN_PROGRESS'),
        ('op-2', 'REJECTED', 'INVENTORY_SESSION_NOT_IN_PROGRESS'),
      ]);

      final result = await syncService.push();

      expect(await quantity('sl-1'), 0, reason: 'floored at 0');
      expect(await db.select(db.stockMovements).get(), isEmpty);
      expect((await sessionRow('s-1')).synced, true);
      expect(await queuedIds(), isEmpty);
      expect(result, hasLength(1));
    });

    test('VALIDATE_INVENTORY FORBIDDEN reverts and reopens the session',
        () async {
      await session('s-1', 'VALIDATED');
      await product('prod-1');
      await stockLevel('sl-1', 'prod-1', 7);
      await adjustment('m-1', 's-1', 'prod-1', 5, 2);
      await insertPendingOp(
          id: 'op-1',
          operation: 'VALIDATE_INVENTORY',
          entityId: 's-1',
          payload: {'sessionId': 's-1'});
      stubPushResults([('op-1', 'REJECTED', 'FORBIDDEN')]);

      final result = await syncService.push();

      final s = await sessionRow('s-1');
      expect(s.status, 'IN_PROGRESS');
      expect(s.completedAt, isNull);
      expect(await quantity('sl-1'), 5);
      expect(await db.select(db.stockMovements).get(), isEmpty);
      expect(await queuedIds(), isEmpty);
      expect(result.single['rolledBack'], true);
    });

    test('CANCEL_SALE rejected with SALE_ALREADY_CANCELLED is settled silently',
        () async {
      await insertPendingOp(
          id: 'op-1', operation: 'CANCEL_SALE', entityId: 'sale-1');
      stubPushResults([('op-1', 'REJECTED', 'SALE_ALREADY_CANCELLED')]);

      final result = await syncService.push();

      expect(result, isEmpty);
      expect(await queuedIds(), isEmpty);
    });

    test(
        'rejected CREATE_CLIENT deletes the client and keeps its sales without '
        'the client', () async {
      await db.into(db.clients).insert(ClientsCompanion.insert(
          id: 'client-x',
          name: 'Ghost',
          phone: '600000000',
          createdAt: now,
          updatedAt: now));
      await db.into(db.sales).insert(SalesCompanion.insert(
          id: 'sale-1',
          storeId: 'store-1',
          employeeId: 'u',
          totalAmount: 1000,
          paymentMode: 'CASH',
          clientId: const Value('client-x'),
          createdAt: now));
      await insertPendingOp(
          id: 'op-1', operation: 'CREATE_CLIENT', entityId: 'client-x');
      await insertPendingOp(
          id: 'op-2', operation: 'UPDATE_CLIENT', entityId: 'client-x');
      await insertPendingOp(
          id: 'op-3',
          operation: 'CREATE_SALE',
          entityId: 'sale-1',
          payload: {'storeId': 'store-1', 'clientId': 'client-x'});
      stubPushResults([
        ('op-1', 'REJECTED', 'VALIDATION_ERROR'),
        ('op-2', 'REJECTED', 'CLIENT_NOT_FOUND'),
        ('op-3', 'REJECTED', 'CLIENT_NOT_FOUND'),
      ]);

      final result = await syncService.push();

      expect(await db.select(db.clients).get(), isEmpty);
      expect((await db.select(db.sales).getSingle()).clientId, isNull);
      expect(await queuedIds(), ['op-3']);
      final sale = await db.select(db.syncQueue).getSingle();
      expect(jsonDecode(sale.payload), {'storeId': 'store-1'});
      expect(result.map((r) => r['operation']), ['CREATE_CLIENT'],
          reason: 'the rewritten sale is retried, not surfaced');
      expect(result.single['rolledBack'], true);
    });

    test('rejected CREATE_SUPPLIER deletes the supplier and its product links',
        () async {
      await db.into(db.suppliers).insert(SuppliersCompanion.insert(
          id: 'sup-x',
          name: 'Ghost',
          phone: '600000000',
          createdAt: now,
          updatedAt: now));
      await db.into(db.productSuppliers).insert(
          ProductSuppliersCompanion.insert(supplierId: 'sup-x', productId: 'p1'));
      await db.into(db.productSuppliers).insert(
          ProductSuppliersCompanion.insert(supplierId: 'sup-2', productId: 'p1'));
      await insertPendingOp(
          id: 'op-1', operation: 'CREATE_SUPPLIER', entityId: 'sup-x');
      await insertPendingOp(
          id: 'op-2', operation: 'ARCHIVE_SUPPLIER', entityId: 'sup-x');
      stubPushResults([('op-1', 'REJECTED', 'FORBIDDEN')]);

      final result = await syncService.push();

      expect(await db.select(db.suppliers).get(), isEmpty);
      expect((await db.select(db.productSuppliers).get()).map((l) => l.supplierId),
          ['sup-2']);
      expect(await queuedIds(), isEmpty);
      expect(result.single['rolledBack'], true);
    });

    test('rejected CREATE_DRAFT_PRODUCT deletes the draft and its follow-ups',
        () async {
      await product('draft-x');
      await db.into(db.productSuppliers).insert(
          ProductSuppliersCompanion.insert(supplierId: 'sup-1', productId: 'draft-x'));
      await insertPendingOp(
          id: 'op-1', operation: 'CREATE_DRAFT_PRODUCT', entityId: 'draft-x');
      await insertPendingOp(
          id: 'op-2', operation: 'PROMOTE_PRODUCT', entityId: 'draft-x');
      stubPushResults([('op-1', 'REJECTED', 'PLAN_LIMIT_EXCEEDED')]);

      final result = await syncService.push();

      expect(await db.select(db.products).get(), isEmpty);
      expect(await db.select(db.productSuppliers).get(), isEmpty);
      expect(await queuedIds(), isEmpty);
      expect(result.single['operation'], 'CREATE_DRAFT_PRODUCT');
      expect(result.single['rolledBack'], true);
    });

    test('a count refused for good is removed locally', () async {
      await db.into(db.inventoryCounts).insert(InventoryCountsCompanion.insert(
          id: 'c-1',
          sessionId: 's-1',
          productId: 'p1',
          productName: 'P',
          theoretical: 0,
          updatedAt: now));
      await insertPendingOp(
          id: 'op-1',
          operation: 'SAVE_INVENTORY_COUNT',
          entityId: 'c-1',
          payload: {'sessionId': 's-1', 'productId': 'p1'});
      stubPushResults([('op-1', 'REJECTED', 'FORBIDDEN')]);

      final result = await syncService.push();

      expect(await db.select(db.inventoryCounts).get(), isEmpty);
      expect(await queuedIds(), isEmpty);
      expect(result.single['rolledBack'], true);
    });

    test('a transient rejection stays queued, surfaced once per reason',
        () async {
      await insertPendingOp(
          id: 'op-1', operation: 'UPDATE_PRODUCT', entityId: 'prod-1');
      await insertPendingOp(
          id: 'op-2', operation: 'CREATE_CLIENT', entityId: 'client-1');
      stubPushResults([
        ('op-1', 'REJECTED', 'OPTIMISTIC_LOCK'),
        ('op-2', 'REJECTED', null),
      ]);

      final result = await syncService.push();

      expect(result.map((r) => r['operation']), ['UPDATE_PRODUCT']);
      expect(result.single['reason'], 'OPTIMISTIC_LOCK');
      expect(result.single['rolledBack'], false);
      expect(await queuedIds(), ['op-1', 'op-2']);
      final row = await (db.select(db.syncQueue)
            ..where((t) => t.id.equals('op-1')))
          .getSingle();
      expect(row.lastError, 'OPTIMISTIC_LOCK');
      expect(row.retryCount, 1);

      final again = await syncService.push();
      expect(again, isEmpty, reason: 'same reason: not re-surfaced');
      expect(await queuedIds(), ['op-1', 'op-2']);
    });

    test('another terminal rejection is surfaced once and dropped', () async {
      await product('prod-1');
      await insertPendingOp(
          id: 'op-1', operation: 'UPDATE_PRODUCT', entityId: 'prod-1');
      stubPushResults([('op-1', 'REJECTED', 'Product not found: prod-1')]);

      final result = await syncService.push();

      expect(await queuedIds(), isEmpty,
          reason: 'unblocks hasPendingOperations() for the server refresh');
      expect(await syncService.hasPendingOperations(), false);
      expect(await db.select(db.products).get(), hasLength(1),
          reason: 'not a creation: nothing to roll back');
      expect(result.single['rolledBack'], false);
    });

    test('APPLIED CREATE_INVENTORY_SESSION marks the session synced', () async {
      await session('s-1', 'IN_PROGRESS');
      await insertPendingOp(
          id: 'op-1', operation: 'CREATE_INVENTORY_SESSION', entityId: 's-1');
      stubPushResults([('op-1', 'APPLIED', null)]);

      await syncService.push();

      expect((await sessionRow('s-1')).synced, true);
    });

    // v1s-16-12 follow-up: only the rejected store's selection is cleared.
    test('rejected CREATE_STORE keeps another active store selected',
        () async {
      await db.into(db.stores).insert(StoresCompanion.insert(
          id: 'store-x',
          name: 'Annexe',
          tenantId: 't',
          createdAt: now,
          updatedAt: now));
      await prefs.setString('active_store_id', 'store-1');
      await insertPendingOp(
          id: 'op-1', operation: 'CREATE_STORE', entityId: 'store-x');
      stubPushResults([('op-1', 'REJECTED', 'PLAN_LIMIT_EXCEEDED')]);

      final result = await syncService.push();

      expect(await db.select(db.stores).get(), isEmpty);
      expect(prefs.getString('active_store_id'), 'store-1');
      expect(result.single['rolledBack'], true);
    });

    test('ops removed by a cascade are not sent in a later batch', () async {
      await product('prod-x');
      await insertPendingOp(
          id: 'op-0', operation: 'CREATE_PRODUCT', entityId: 'prod-x');
      for (var i = 1; i < 50; i++) {
        await insertPendingOp(
            id: 'filler-$i', operation: 'UPDATE_CLIENT', entityId: 'c-$i');
      }
      await insertPendingOp(
          id: 'op-late', operation: 'UPDATE_PRODUCT', entityId: 'prod-x');
      final sentIds = <String>[];
      when(() => mockDio.post<dynamic>(any(), data: any(named: 'data')))
          .thenAnswer((inv) async {
        final ops = ((inv.namedArguments[#data] as Map)['operations'] as List)
            .cast<Map<String, dynamic>>();
        sentIds.addAll(ops.map((o) => o['operationId'] as String));
        return Response(
          requestOptions: RequestOptions(path: '/api/v1/sync/push'),
          statusCode: 200,
          data: {
            'data': {
              'results': [
                for (final o in ops)
                  {
                    'operationId': o['operationId'],
                    'status': o['operationId'] == 'op-0' ? 'REJECTED' : 'APPLIED',
                    'reason':
                        o['operationId'] == 'op-0' ? 'VALIDATION_ERROR' : null,
                  }
              ],
            },
          },
        );
      });

      await syncService.push();

      expect(sentIds, isNot(contains('op-late')));
      expect(await queuedIds(), isEmpty);
    });
  });

  test(
      'CREATE_PRODUCT merged server-side into an existing product drops the '
      'local duplicate product and its local stock rows', () async {
    final now = DateTime.now();
    await db.into(db.products).insert(ProductsCompanion.insert(
        id: 'local-1', name: 'Pantalon', createdAt: now, updatedAt: now));
    await db.into(db.products).insert(ProductsCompanion.insert(
        id: 'other', name: 'Autre', createdAt: now, updatedAt: now));
    await insertPendingOp(
        id: 'op-1', operation: 'CREATE_PRODUCT', entityId: 'local-1');
    stubPushResponse('op-1', 'APPLIED', serverEntityId: 'server-9');

    await syncService.push();

    final ids = (await db.select(db.products).get()).map((p) => p.id).toList();
    expect(ids, ['other'], reason: 'only the merged local duplicate is removed');
    expect(await db.select(db.syncQueue).get(), isEmpty);
  });

  // Quick-add creates a count with the local product id; once the server
  // merges the product, the count must follow it instead of pointing at a
  // product that no longer exists locally.
  test('a merged product takes its local inventory counts along', () async {
    final now = DateTime.now();
    await db.into(db.products).insert(ProductsCompanion.insert(
        id: 'local-1', name: 'Pantalon', createdAt: now, updatedAt: now));
    Future<void> count(String id, String session, String product) =>
        db.into(db.inventoryCounts).insert(InventoryCountsCompanion.insert(
              id: id,
              sessionId: session,
              productId: product,
              productName: 'Pantalon',
              theoretical: 0,
              updatedAt: now,
            ));
    await count('c-local', 'session-1', 'local-1');
    // Session 2 already counts the server product: that count wins.
    await count('c-local-2', 'session-2', 'local-1');
    await count('c-server-2', 'session-2', 'server-9');
    await insertPendingOp(
        id: 'op-1', operation: 'CREATE_PRODUCT', entityId: 'local-1');
    stubPushResponse('op-1', 'APPLIED', serverEntityId: 'server-9');

    await syncService.push();

    final counts = {
      for (final c in await db.select(db.inventoryCounts).get()) c.id: c.productId
    };
    expect(counts, {'c-local': 'server-9', 'c-server-2': 'server-9'});
  });

  test('CREATE_PRODUCT applied under its own id keeps the local product',
      () async {
    final now = DateTime.now();
    await db.into(db.products).insert(ProductsCompanion.insert(
        id: 'local-1', name: 'Pantalon', createdAt: now, updatedAt: now));
    await insertPendingOp(
        id: 'op-1', operation: 'CREATE_PRODUCT', entityId: 'local-1');
    stubPushResponse('op-1', 'APPLIED', serverEntityId: 'local-1');

    await syncService.push();

    expect(await db.select(db.products).get(), hasLength(1));
  });

  group('v1s-16-15 — quarantine of money operations refused for good', () {
    /// Answers each sent op from [answers] (default APPLIED) and records the
    /// ids sent per push.
    List<List<String>> stubServer(Map<String, (String, String?)> answers) {
      final pushes = <List<String>>[];
      when(() => mockDio.post<dynamic>(any(), data: any(named: 'data')))
          .thenAnswer((inv) async {
        final ops = ((inv.namedArguments[#data] as Map)['operations'] as List)
            .cast<Map<String, dynamic>>();
        pushes.add(ops.map((o) => o['operationId'] as String).toList());
        return Response(
          requestOptions: RequestOptions(path: '/api/v1/sync/push'),
          statusCode: 200,
          data: {
            'data': {
              'results': [
                for (final o in ops)
                  {
                    'operationId': o['operationId'],
                    'status': (answers[o['operationId']] ?? ('APPLIED', null)).$1,
                    'reason': (answers[o['operationId']] ?? ('APPLIED', null)).$2,
                  }
              ],
            },
          },
        );
      });
      return pushes;
    }

    Future<SyncQueueData?> row(String id) =>
        (db.select(db.syncQueue)..where((t) => t.id.equals(id)))
            .getSingleOrNull();

    test(
        'a refused sale is quarantined with its queued validation (chain): '
        'one notice, not pending, never sent again', () async {
      await insertPendingOp(
          id: 'sale-op', operation: 'CREATE_SALE', entityId: 'sale-1');
      await insertPendingOp(
          id: 'validate-op', operation: 'VALIDATE_SALE', entityId: 'sale-1');
      await insertPendingOp(
          id: 'other-validate', operation: 'VALIDATE_SALE', entityId: 'sale-2');
      final pushes = stubServer({
        'sale-op': ('REJECTED', 'PRODUCT_NOT_FOUND'),
        'validate-op': ('REJECTED', 'SALE_NOT_FOUND'),
      });

      final result = await syncService.push();

      expect(result, hasLength(1),
          reason: "the chained op's own refusal is not surfaced again");
      expect(result.single['operation'], 'CREATE_SALE');
      expect(result.single['quarantined'], true);
      expect((await row('sale-op'))!.quarantinedAt, isNotNull);
      expect((await row('sale-op'))!.lastError, 'PRODUCT_NOT_FOUND');
      expect((await row('validate-op'))!.quarantinedAt, isNotNull);
      expect(await row('other-validate'), isNull, reason: 'APPLIED: deleted');
      expect(await syncService.hasPendingOperations(), false);
      expect(await syncService.quarantinedOperationCount(), 2);

      await insertPendingOp(
          id: 'later', operation: 'UPDATE_CLIENT', entityId: 'c-1');
      await syncService.push();
      expect(pushes.last, ['later'], reason: 'quarantined ops are not pushed');
    });

    test('a chained op APPLIED in the same batch is deleted as usual',
        () async {
      await insertPendingOp(
          id: 'sale-op', operation: 'CREATE_SALE', entityId: 'sale-1');
      await insertPendingOp(
          id: 'cancel-op', operation: 'CANCEL_SALE', entityId: 'sale-1');
      stubServer({'sale-op': ('REJECTED', 'FORBIDDEN')});

      await syncService.push();

      expect(await queuedIds(), ['sale-op']);
      expect((await row('sale-op'))!.quarantinedAt, isNotNull);
    });

    test('terminal reasons quarantine every money / stock-ledger operation',
        () async {
      const cases = [
        ('CREATE_SALE', 'STORE_NOT_FOUND'),
        ('VALIDATE_SALE', 'FORBIDDEN'),
        ('CREATE_DAY_CLOSURE', 'STORE_NOT_FOUND'),
        ('STOCK_ADJUST', 'Product not found: p-1'),
        ('RECORD_STOCK_ENTRY', 'PRODUCT_NOT_FOUND'),
        ('STOCK_TRANSFER', 'INSUFFICIENT_STOCK'),
      ];
      final answers = <String, (String, String?)>{};
      for (final (i, (operation, reason)) in cases.indexed) {
        await insertPendingOp(
            id: 'op-$i', operation: operation, entityId: 'e-$i');
        answers['op-$i'] = ('REJECTED', reason);
      }
      stubServer(answers);

      final result = await syncService.push();

      expect(result.map((r) => r['quarantined']), everyElement(true));
      expect(result, hasLength(cases.length));
      expect(await syncService.quarantinedOperationCount(), cases.length);
      expect(await syncService.hasPendingOperations(), false);
    });

    test(
        'DATA_INTEGRITY_VIOLATION quarantines a money op, stays transient for '
        'other ops; other transient reasons never quarantine', () async {
      await insertPendingOp(
          id: 'adjust', operation: 'STOCK_ADJUST', entityId: 'sl-1');
      await insertPendingOp(
          id: 'product', operation: 'UPDATE_PRODUCT', entityId: 'p-1');
      await insertPendingOp(
          id: 'sale', operation: 'CREATE_SALE', entityId: 'sale-1');
      stubServer({
        'adjust': ('REJECTED', 'DATA_INTEGRITY_VIOLATION'),
        'product': ('REJECTED', 'DATA_INTEGRITY_VIOLATION'),
        'sale': ('REJECTED', 'OPTIMISTIC_LOCK'),
      });

      await syncService.push();

      expect((await row('adjust'))!.quarantinedAt, isNotNull);
      expect((await row('product'))!.quarantinedAt, isNull);
      expect((await row('sale'))!.quarantinedAt, isNull);
      expect((await row('sale'))!.lastError, 'OPTIMISTIC_LOCK');
      expect(await syncService.hasPendingOperations(), true);
    });

    test(
        'a validation refused while its sale is still pending is only retried; '
        'once the sale is quarantined it joins the chain silently', () async {
      await insertPendingOp(
          id: 'sale-op', operation: 'CREATE_SALE', entityId: 'sale-1');
      await insertPendingOp(
          id: 'validate-op', operation: 'VALIDATE_SALE', entityId: 'sale-1');
      stubServer({
        'sale-op': ('REJECTED', 'OPTIMISTIC_LOCK'),
        'validate-op': ('REJECTED', 'SALE_NOT_FOUND'),
      });

      final first = await syncService.push();

      expect(first.map((r) => r['operation']), ['CREATE_SALE']);
      final retried = (await row('validate-op'))!;
      expect(retried.quarantinedAt, isNull);
      expect(retried.lastError, 'SALE_NOT_FOUND');
      expect(retried.retryCount, 1);

      // The sale is now quarantined in an earlier push (simulated) while the
      // validation is still sent and refused.
      await (db.update(db.syncQueue)..where((t) => t.id.equals('sale-op')))
          .write(SyncQueueCompanion(quarantinedAt: Value(DateTime.now())));
      await (db.update(db.syncQueue)..where((t) => t.id.equals('validate-op')))
          .write(const SyncQueueCompanion(quarantinedAt: Value(null)));
      final pushes = stubServer({'validate-op': ('REJECTED', 'SALE_NOT_FOUND')});

      // _push first attaches it to the quarantined sale: nothing is sent.
      final second = await syncService.push();
      expect(pushes, isEmpty);
      expect(second, isEmpty);
      expect((await row('validate-op'))!.quarantinedAt, isNotNull);
    });

    test(
        'a validation refused while its sale is quarantined joins the chain '
        'silently', () async {
      await insertPendingOp(
          id: 'sale-op', operation: 'CREATE_SALE', entityId: 'sale-1');
      await insertPendingOp(
          id: 'validate-op', operation: 'VALIDATE_SALE', entityId: 'sale-1');
      // The sale goes into quarantine while the push is in flight (another
      // batch of the same push): emulated from the server stub.
      when(() => mockDio.post<dynamic>(any(), data: any(named: 'data')))
          .thenAnswer((inv) async {
        await (db.update(db.syncQueue)..where((t) => t.id.equals('sale-op')))
            .write(SyncQueueCompanion(quarantinedAt: Value(DateTime.now())));
        return Response(
          requestOptions: RequestOptions(path: '/api/v1/sync/push'),
          statusCode: 200,
          data: {
            'data': {
              'results': [
                {
                  'operationId': 'validate-op',
                  'status': 'REJECTED',
                  'reason': 'SALE_NOT_FOUND',
                },
              ],
            },
          },
        );
      });

      final result = await syncService.push();

      expect(result, isEmpty, reason: 'joins the chain without a notice');
      expect((await row('validate-op'))!.quarantinedAt, isNotNull);
    });

    test(
        'a validation queued after its sale was quarantined is attached to the '
        'chain, never sent on its own', () async {
      await insertPendingOp(
          id: 'sale-op', operation: 'CREATE_SALE', entityId: 'sale-1');
      final pushes = stubServer({'sale-op': ('REJECTED', 'PRODUCT_NOT_FOUND')});
      await syncService.push();

      await insertPendingOp(
          id: 'validate-op', operation: 'VALIDATE_SALE', entityId: 'sale-1');
      await insertPendingOp(
          id: 'other', operation: 'UPDATE_CLIENT', entityId: 'c-1');
      final result = await syncService.push();

      expect(pushes.last, ['other']);
      expect(result, isEmpty, reason: 'no second notice');
      expect((await row('validate-op'))!.quarantinedAt, isNotNull);
      expect(await syncService.hasPendingOperations(), false);
    });

    test('a released chain refused again goes back with one new notice',
        () async {
      await insertPendingOp(
          id: 'sale-op', operation: 'CREATE_SALE', entityId: 'sale-1');
      stubServer({'sale-op': ('REJECTED', 'PRODUCT_NOT_FOUND')});
      await syncService.push();

      // Renvoyer.
      await (db.update(db.syncQueue)..where((t) => t.id.equals('sale-op')))
          .write(const SyncQueueCompanion(quarantinedAt: Value(null)));
      expect(await syncService.hasPendingOperations(), true);

      final again = await syncService.push();

      expect(again, hasLength(1),
          reason: 'surfaced again even with the same reason');
      expect(again.single['quarantined'], true);
      expect((await row('sale-op'))!.quarantinedAt, isNotNull);
      expect((await row('sale-op'))!.retryCount, 2);
    });
  });

  // v1s-16-12 follow-up: payload rewriting + queue re-read between batches,
  // end to end on the in-memory Drift database (several 50-op batches and
  // successive pushes), the server answering per operation.
  group('v1s-16-12 — multi-batch queue rewriting (Drift in memory)', () {
    final now = DateTime(2026, 10, 1);

    /// Every push request: operationId → payload as sent.
    late List<Map<String, Map<String, dynamic>>> requests;

    /// Server: [answer] decides (status, reason) per sent operation.
    void server((String, String?) Function(Map<String, dynamic> op) answer) {
      requests = [];
      when(() => mockDio.post<dynamic>(any(), data: any(named: 'data')))
          .thenAnswer((inv) async {
        final ops = ((inv.namedArguments[#data] as Map)['operations'] as List)
            .cast<Map<String, dynamic>>();
        requests.add({
          for (final o in ops)
            o['operationId'] as String:
                Map<String, dynamic>.of(o['payload'] as Map<String, dynamic>)
        });
        return Response(
          requestOptions: RequestOptions(path: '/api/v1/sync/push'),
          statusCode: 200,
          data: {
            'data': {
              'results': [
                for (final o in ops)
                  () {
                    final (status, reason) = answer(o);
                    return {
                      'operationId': o['operationId'],
                      'status': status,
                      'reason': reason,
                    };
                  }(),
              ],
            },
          },
        );
      });
    }

    Future<void> fillers(int n) async {
      for (var i = 0; i < n; i++) {
        await insertPendingOp(
            id: 'filler-$i', operation: 'UPDATE_CLIENT', entityId: 'c-$i');
      }
    }

    test('a sale queued in a later batch is sent without the ghost client',
        () async {
      await db.into(db.clients).insert(ClientsCompanion.insert(
          id: 'client-x',
          name: 'Ghost',
          phone: '600000000',
          createdAt: now,
          updatedAt: now));
      await db.into(db.sales).insert(SalesCompanion.insert(
          id: 'sale-1',
          storeId: 'store-1',
          employeeId: 'u',
          totalAmount: 1000,
          paymentMode: 'CASH',
          clientId: const Value('client-x'),
          createdAt: now));
      await insertPendingOp(
          id: 'op-client', operation: 'CREATE_CLIENT', entityId: 'client-x');
      await fillers(55); // pushes the sale into the second batch
      await insertPendingOp(
          id: 'op-sale',
          operation: 'CREATE_SALE',
          entityId: 'sale-1',
          payload: {'storeId': 'store-1', 'clientId': 'client-x'});
      server((o) => o['operationId'] == 'op-client'
          ? ('REJECTED', 'VALIDATION_ERROR')
          : ('APPLIED', null));

      final result = await syncService.push();

      expect(requests, hasLength(2));
      expect(requests[0].containsKey('op-sale'), false);
      expect(requests[1]['op-sale'], {'storeId': 'store-1'},
          reason: 'batch 2 re-reads the rewritten payload');
      expect(await queuedIds(), isEmpty);
      expect((await db.select(db.sales).getSingle()).clientId, isNull);
      expect(result.map((r) => r['operation']), ['CREATE_CLIENT']);
    });

    test('a product queued in a later batch drops the ghost category and '
        'is kept', () async {
      await db.into(db.categories).insert(CategoriesCompanion.insert(
          id: 'cat-x', name: 'Ghost', createdAt: now, updatedAt: now));
      await db.into(db.products).insert(ProductsCompanion.insert(
          id: 'prod-1',
          name: 'Savon',
          categoryId: const Value('cat-x'),
          createdAt: now,
          updatedAt: now));
      await insertPendingOp(
          id: 'op-cat', operation: 'CREATE_CATEGORY', entityId: 'cat-x');
      await fillers(60);
      await insertPendingOp(
          id: 'op-prod',
          operation: 'CREATE_PRODUCT',
          entityId: 'prod-1',
          payload: {'id': 'prod-1', 'name': 'Savon', 'categoryId': 'cat-x'});
      server((o) => switch (o['operationId']) {
            'op-cat' => ('REJECTED', 'FORBIDDEN'),
            // A real server refuses a product still pointing at the ghost.
            'op-prod' => (o['payload'] as Map).containsKey('categoryId')
                ? ('REJECTED', 'CATEGORY_NOT_FOUND')
                : ('APPLIED', null),
            _ => ('APPLIED', null),
          });

      await syncService.push();

      expect(requests[1]['op-prod']!.containsKey('categoryId'), false);
      expect(await queuedIds(), isEmpty);
      final p = await db.select(db.products).getSingle();
      expect(p.id, 'prod-1');
      expect(p.categoryId, isNull);
    });

    test('rewritten in the same batch: retried, then sent clean on the next '
        'push', () async {
      await db.into(db.categories).insert(CategoriesCompanion.insert(
          id: 'cat-x', name: 'Ghost', createdAt: now, updatedAt: now));
      await db.into(db.products).insert(ProductsCompanion.insert(
          id: 'prod-1',
          name: 'Savon',
          categoryId: const Value('cat-x'),
          createdAt: now,
          updatedAt: now));
      await insertPendingOp(
          id: 'op-cat', operation: 'CREATE_CATEGORY', entityId: 'cat-x');
      await insertPendingOp(
          id: 'op-prod',
          operation: 'CREATE_PRODUCT',
          entityId: 'prod-1',
          payload: {'id': 'prod-1', 'name': 'Savon', 'categoryId': 'cat-x'});
      (String, String?) answer(Map<String, dynamic> o) =>
          switch (o['operationId']) {
            'op-cat' => ('REJECTED', 'FORBIDDEN'),
            'op-prod' => (o['payload'] as Map).containsKey('categoryId')
                ? ('REJECTED', 'CATEGORY_NOT_FOUND')
                : ('APPLIED', null),
            _ => ('APPLIED', null),
          };
      server(answer);

      final first = await syncService.push();
      expect(first.map((r) => r['operation']), ['CREATE_CATEGORY']);
      expect(await queuedIds(), ['op-prod']);
      expect(await db.select(db.products).get(), hasLength(1),
          reason: 'the product is not rolled back for the ghost category');

      server(answer);
      final second = await syncService.push();

      expect(requests.single['op-prod']!.containsKey('categoryId'), false);
      expect(second, isEmpty);
      expect(await queuedIds(), isEmpty);
    });

    test('a rewritten op refused for its own reason in a later batch is '
        'reconciled normally', () async {
      await db.into(db.categories).insert(CategoriesCompanion.insert(
          id: 'cat-x', name: 'Ghost', createdAt: now, updatedAt: now));
      await db.into(db.products).insert(ProductsCompanion.insert(
          id: 'prod-1',
          name: 'Savon',
          categoryId: const Value('cat-x'),
          createdAt: now,
          updatedAt: now));
      await insertPendingOp(
          id: 'op-cat', operation: 'CREATE_CATEGORY', entityId: 'cat-x');
      await fillers(52);
      await insertPendingOp(
          id: 'op-prod',
          operation: 'CREATE_PRODUCT',
          entityId: 'prod-1',
          payload: {'id': 'prod-1', 'name': 'Savon', 'categoryId': 'cat-x'});
      server((o) => switch (o['operationId']) {
            'op-cat' => ('REJECTED', 'FORBIDDEN'),
            'op-prod' => ('REJECTED', 'PLAN_LIMIT_EXCEEDED'),
            _ => ('APPLIED', null),
          });

      final result = await syncService.push();

      expect(result.map((r) => r['operation']),
          ['CREATE_CATEGORY', 'CREATE_PRODUCT']);
      expect(result.every((r) => r['rolledBack'] == true), true);
      expect(await db.select(db.products).get(), isEmpty);
      expect(await queuedIds(), isEmpty);
    });

    test('a later batch failing on the network keeps the earlier notices',
        () async {
      await db.into(db.products).insert(ProductsCompanion.insert(
          id: 'prod-x', name: 'Ghost', createdAt: now, updatedAt: now));
      await insertPendingOp(
          id: 'op-prod', operation: 'CREATE_PRODUCT', entityId: 'prod-x');
      await fillers(60);
      var call = 0;
      when(() => mockDio.post<dynamic>(any(), data: any(named: 'data')))
          .thenAnswer((inv) async {
        call++;
        if (call == 2) {
          throw DioException(
            requestOptions: RequestOptions(path: '/api/v1/sync/push'),
            type: DioExceptionType.connectionError,
          );
        }
        final ops = ((inv.namedArguments[#data] as Map)['operations'] as List)
            .cast<Map<String, dynamic>>();
        return Response(
          requestOptions: RequestOptions(path: '/api/v1/sync/push'),
          statusCode: 200,
          data: {
            'data': {
              'results': [
                for (final o in ops)
                  {
                    'operationId': o['operationId'],
                    'status':
                        o['operationId'] == 'op-prod' ? 'REJECTED' : 'APPLIED',
                    'reason': o['operationId'] == 'op-prod'
                        ? 'PLAN_LIMIT_EXCEEDED'
                        : null,
                  }
              ],
            },
          },
        );
      });

      final result = await syncService.push();

      expect(result.map((r) => r['operation']), ['CREATE_PRODUCT']);
      expect(result.single['rolledBack'], true);
      final left = await db.select(db.syncQueue).get();
      expect(left, hasLength(11), reason: 'batch 2 stays queued');
      expect(left.every((o) => o.retryCount == 1), true);
    });

    test('a first batch failing on the network still throws', () async {
      await insertPendingOp(
          id: 'op-1', operation: 'UPDATE_CLIENT', entityId: 'c-1');
      when(() => mockDio.post<dynamic>(any(), data: any(named: 'data')))
          .thenThrow(DioException(
        requestOptions: RequestOptions(path: '/api/v1/sync/push'),
        type: DioExceptionType.connectionError,
      ));

      await expectLater(syncService.push(), throwsA(isA<DioException>()));
    });

    test('a rolled-back product takes its local transfers along', () async {
      await db.into(db.products).insert(ProductsCompanion.insert(
          id: 'prod-x', name: 'Ghost', createdAt: now, updatedAt: now));
      for (final (id, product) in const [('tr-x', 'prod-x'), ('tr-ok', 'p1')]) {
        await db.into(db.stockTransfers).insert(StockTransfersCompanion.insert(
            id: id,
            sourceStoreId: 'store-1',
            destinationStoreId: 'store-2',
            productId: product,
            quantity: 1,
            actorId: 'u',
            occurredAt: now));
      }
      await insertPendingOp(
          id: 'op-prod', operation: 'CREATE_PRODUCT', entityId: 'prod-x');
      await insertPendingOp(
          id: 'op-tr',
          operation: 'STOCK_TRANSFER',
          entityId: 'tr-x',
          payload: {'productId': 'prod-x', 'quantity': 1});
      server((o) => o['operationId'] == 'op-prod'
          ? ('REJECTED', 'PLAN_LIMIT_EXCEEDED')
          : ('REJECTED', 'PRODUCT_NOT_FOUND'));

      await syncService.push();

      expect((await db.select(db.stockTransfers).get()).map((t) => t.id),
          ['tr-ok']);
      expect(await queuedIds(), isEmpty);
    });

    // v1s-16-15 follow-up: the quarantine chain end to end on Drift — the
    // sale is refused in batch 1, its validation (batch 2) joins the chain
    // instead of being sent; « Rattacher » then a new push clears it, with
    // the validation rid of the ghost product.
    test('chain across batches, then re-attach and push again', () async {
      await db.into(db.products).insert(ProductsCompanion.insert(
          id: 'p-real', name: 'Savon', createdAt: now, updatedAt: now));
      await insertPendingOp(
          id: 'op-sale',
          operation: 'CREATE_SALE',
          entityId: 'sale-1',
          payload: {
            'saleId': 'sale-1',
            'storeId': 'store-1',
            'items': [
              {'itemId': 'i-1', 'productId': 'p-ghost', 'quantity': 1},
            ],
          });
      await fillers(55);
      await insertPendingOp(
          id: 'op-validate',
          operation: 'VALIDATE_SALE',
          entityId: 'sale-1',
          payload: {
            'saleId': 'sale-1',
            'initialStockEntries': {'p-ghost': 3},
          });
      // Server: refuses anything still naming the ghost product.
      server((o) => jsonEncode(o['payload']).contains('p-ghost')
          ? ('REJECTED', 'PRODUCT_NOT_FOUND')
          : ('APPLIED', null));

      final first = await syncService.push();

      expect(requests[1].containsKey('op-validate'), false,
          reason: 'the validation joins the chain, it is not sent');
      expect(first.where((r) => r['quarantined'] == true).map((r) => r['operation']),
          ['CREATE_SALE']);
      final quarantined = (await db.select(db.syncQueue).get())
          .where((o) => o.quarantinedAt != null)
          .map((o) => o.id);
      expect(quarantined, containsAll(['op-sale', 'op-validate']));
      expect(await syncService.hasPendingOperations(), false);

      final quarantine = SyncQuarantineService(db, SyncEventLogger(db));
      await quarantine.reattachSale('op-sale',
          productIds: {'p-ghost': 'p-real'});
      final second = await syncService.push();

      expect(second, isEmpty);
      expect(await queuedIds(), isEmpty);
      expect(requests.last.keys, ['op-sale', 'op-validate']);
      expect(requests.last['op-validate'], {'saleId': 'sale-1'},
          reason: 'the ghost initial stock entry is gone');
    });

    test('abandon after a real quarantine removes the chain and the sale',
        () async {
      await db.into(db.sales).insert(SalesCompanion.insert(
          id: 'sale-1',
          storeId: 'store-1',
          employeeId: 'u',
          totalAmount: 500,
          paymentMode: 'CASH',
          createdAt: now));
      await insertPendingOp(
          id: 'op-sale',
          operation: 'CREATE_SALE',
          entityId: 'sale-1',
          payload: {'saleId': 'sale-1', 'storeId': 'store-1'});
      await insertPendingOp(
          id: 'op-cancel', operation: 'CANCEL_SALE', entityId: 'sale-1');
      server((o) => o['operationId'] == 'op-sale'
          ? ('REJECTED', 'STORE_NOT_FOUND')
          : ('REJECTED', 'SALE_NOT_FOUND'));

      await syncService.push();
      expect(await syncService.quarantinedOperationCount(), 2);

      final quarantine = SyncQuarantineService(db, SyncEventLogger(db));
      expect(await quarantine.abandon('op-cancel'), true);

      expect(await queuedIds(), isEmpty);
      expect(await db.select(db.sales).get(), isEmpty);
      expect(await syncService.quarantinedOperationCount(), 0);
    });

    test('Renvoyer through the service: refused again → back in quarantine '
        'with one new notice', () async {
      await insertPendingOp(
          id: 'op-sale',
          operation: 'CREATE_SALE',
          entityId: 'sale-1',
          payload: {'saleId': 'sale-1', 'storeId': 'store-x'});
      server((o) => ('REJECTED', 'STORE_NOT_FOUND'));
      await syncService.push();
      expect(await syncService.quarantinedOperationCount(), 1);

      final quarantine = SyncQuarantineService(db, SyncEventLogger(db));
      await quarantine.release('op-sale');
      expect(await syncService.quarantinedOperationCount(), 0);
      expect(await syncService.hasPendingOperations(), true);

      final again = await syncService.push();

      expect(requests.last.keys, ['op-sale'], reason: 'released → sent');
      expect(again.where((r) => r['quarantined'] == true), hasLength(1));
      expect(await syncService.quarantinedOperationCount(), 1);
      expect(await syncService.hasPendingOperations(), false);
    });
  });
}
