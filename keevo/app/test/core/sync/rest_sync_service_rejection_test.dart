import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:drift/drift.dart' show Value;
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/core/sync/rest_sync_service.dart';
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

    final row =
        await (db.select(db.syncQueue)..where((t) => t.id.equals('op-1')))
            .getSingle();
    expect(row.lastError, 'INSUFFICIENT_STOCK');
    expect(row.retryCount, 1);
  });

  test('repeated REJECTED with the same reason is not re-surfaced', () async {
    await insertPendingOp(
        id: 'op-1', operation: 'CREATE_SALE', entityId: 'sale-1');
    stubPushResponse('op-1', 'REJECTED', reason: 'INSUFFICIENT_STOCK');

    await syncService.push(); // first attempt — surfaced
    final second = await syncService.push(); // retried by the queue, same reason

    expect(second, isEmpty);

    final row =
        await (db.select(db.syncQueue)..where((t) => t.id.equals('op-1')))
            .getSingle();
    expect(row.retryCount, 2, reason: 'retries keep counting even when not renotified');
  });

  test('REJECTED with a changed reason is surfaced again', () async {
    await insertPendingOp(
        id: 'op-1', operation: 'CREATE_SALE', entityId: 'sale-1');
    stubPushResponse('op-1', 'REJECTED', reason: 'INSUFFICIENT_STOCK');
    await syncService.push();

    stubPushResponse('op-1', 'REJECTED', reason: 'STORE_NOT_ACTIVE');
    final second = await syncService.push();

    expect(second, hasLength(1));
    expect(second.single['reason'], 'STORE_NOT_ACTIVE');
  });

  test('a rejected money/stock-ledger operation stays in the queue', () async {
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
}
