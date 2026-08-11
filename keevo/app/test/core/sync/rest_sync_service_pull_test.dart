import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_constants.dart';
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

void main() {
  setUpAll(() {
    _overrideSqlite3ForLinuxTesting();
  });

  late AppDatabase db;
  late MockDio mockDio;
  late FlutterSecureStorage secureStorage;
  late RestSyncService syncService;

  setUp(() async {
    db = AppDatabase.forTesting();
    mockDio = MockDio();
    secureStorage = const FlutterSecureStorage();
    FlutterSecureStorage.setMockInitialValues({});
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();

    syncService = RestSyncService(
      database: db,
      remoteProducts: MockRemoteProductDataSource(),
      dio: mockDio,
      secureStorage: secureStorage,
      prefs: prefs,
    );
  });

  tearDown(() async {
    await db.close();
  });

  Response<Map<String, dynamic>> pullResponse({
    String serverTimestamp = '2026-03-21T12:00:00.000Z',
    Map<String, dynamic>? entities,
  }) =>
      Response(
        requestOptions: RequestOptions(path: '/api/v1/sync/pull'),
        statusCode: 200,
        data: {
          'data': {
            'serverTimestamp': serverTimestamp,
            'entities': entities ?? <String, dynamic>{},
            'counts': <String, dynamic>{},
          }
        },
      );

  void stubPull({
    String serverTimestamp = '2026-03-21T12:00:00.000Z',
    Map<String, dynamic>? entities,
  }) {
    when(() => mockDio.get<Map<String, dynamic>>(
          any(),
          queryParameters: any(named: 'queryParameters'),
          options: any(named: 'options'),
        )).thenAnswer((_) async => pullResponse(
          serverTimestamp: serverTimestamp,
          entities: entities,
        ));
  }

  // Inserts a pending (unsynced) sync_queue row so pull-merge protection can
  // be exercised without going through the real repository write path.
  var opSeq = 0;
  Future<void> insertPendingSyncOp({
    required String operation,
    Map<String, dynamic> payload = const {},
    String? entityId,
  }) async {
    await db.customStatement(
      'INSERT INTO sync_queue (id, operation, payload, entity_id, synced, '
      'retry_count, created_at) VALUES (?, ?, ?, ?, 0, 0, ?)',
      [
        'sq-${opSeq++}',
        operation,
        jsonEncode(payload),
        entityId,
        DateTime.now().toIso8601String(),
      ],
    );
  }

  Future<void> insertLocalStockLevel({
    required String id,
    required String productId,
    required String storeId,
    required int quantity,
  }) async {
    await db.customStatement(
      'INSERT INTO stock_levels (id, product_id, variant_id, store_id, '
      'quantity, minimum_threshold, updated_at) '
      'VALUES (?, ?, NULL, ?, ?, 0, ?)',
      [id, productId, storeId, quantity, '2026-03-19T10:00:00.000Z'],
    );
  }

  group('RestSyncService.pull()', () {
    test('pull_firstTime_noSinceParam', () async {
      stubPull();

      await syncService.pull();

      final stored = await secureStorage.read(key: kLastSyncTimestampKey);
      expect(stored, isA<String>());
      final ts = DateTime.fromMillisecondsSinceEpoch(int.parse(stored!));
      expect(ts.year, 2026);
    });

    test('pull_withLastTimestamp_sendsSinceParam', () async {
      final lastSync = DateTime.utc(2026, 3, 20, 10);
      await secureStorage.write(
        key: kLastSyncTimestampKey,
        value: lastSync.millisecondsSinceEpoch.toString(),
      );
      // Also set SharedPreferences so the stale-cursor guard doesn't reset
      final prefs = await SharedPreferences.getInstance();
      await prefs.setInt(kLastSyncAtKey, lastSync.millisecondsSinceEpoch);

      stubPull();
      await syncService.pull();

      final captured = verify(() => mockDio.get<Map<String, dynamic>>(
            captureAny(),
            queryParameters: captureAny(named: 'queryParameters'),
            options: any(named: 'options'),
          )).captured;
      expect(captured[0], '/api/v1/sync/pull');
      final queryParams = captured[1] as Map<String, dynamic>?;
      expect(queryParams, isNotNull);
      expect(queryParams!['since'], contains('2026'));
    });

    test('pull_upsertsProductsIntoLocalDrift', () async {
      stubPull(entities: {
        'products': [
          {
            'id': 'prod-1',
            'name': 'Test Product',
            'description': 'desc',
            'sku': 'SKU1',
            'categoryId': null,
            'price': 5000,
            'buyPrice': 3000,
            'transportCost': 100,
            'stockQuantity': 10,
            'photoUrl': null,
            'archived': false,
            'status': 'ACTIVE',
            'createdAt': '2026-03-20T10:00:00.000Z',
            'updatedAt': '2026-03-21T10:00:00.000Z',
          }
        ]
      });

      await syncService.pull();

      final products = await db.select(db.products).get();
      expect(products, hasLength(1));
      expect(products.first.name, 'Test Product');
      expect(products.first.price, 5000);
    });

    test('pull_preservesLocalPhotoUrl_whenBackendIsNull', () async {
      await db.customStatement(
        'INSERT INTO products (id, name, price, buy_price, stock_quantity, '
        'photo_url, archived, status, created_at, updated_at) '
        'VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?)',
        [
          'prod-1', 'Old', 1000, 500, 5, '/local/photo.jpg', 'ACTIVE',
          '2026-03-19T10:00:00.000Z', '2026-03-19T10:00:00.000Z',
        ],
      );

      stubPull(entities: {
        'products': [
          {
            'id': 'prod-1',
            'name': 'Updated Name',
            'description': null,
            'sku': null,
            'categoryId': null,
            'price': 2000,
            'buyPrice': 1000,
            'transportCost': 0,
            'stockQuantity': 10,
            'photoUrl': null,
            'archived': false,
            'status': 'ACTIVE',
            'createdAt': '2026-03-19T10:00:00.000Z',
            'updatedAt': '2026-03-21T10:00:00.000Z',
          }
        ]
      });

      await syncService.pull();

      final p = (await db.select(db.products).get()).first;
      expect(p.name, 'Updated Name');
      expect(p.photoUrl, '/local/photo.jpg');
    });

    test('pull_upsertsStockLevels', () async {
      stubPull(entities: {
        'stockLevels': [
          {
            'id': 'sl-1',
            'productId': 'prod-1',
            'variantId': null,
            'storeId': 'store-1',
            'quantity': 25,
            'minimumThreshold': 5,
            'updatedAt': '2026-03-21T10:00:00.000Z',
          }
        ]
      });

      await syncService.pull();

      final levels = await db.select(db.stockLevels).get();
      expect(levels, hasLength(1));
      expect(levels.first.quantity, 25);
    });

    test('pull_pendingStockAdjust_doesNotOverwriteStockLevel', () async {
      await insertLocalStockLevel(
        id: 'sl-1', productId: 'prod-1', storeId: 'store-1', quantity: 42);
      await insertPendingSyncOp(
        operation: 'STOCK_ADJUST',
        payload: {'productId': 'prod-1', 'storeId': 'store-1', 'quantity': 42},
        entityId: 'mov-1',
      );

      stubPull(entities: {
        'stockLevels': [
          {
            'id': 'sl-1',
            'productId': 'prod-1',
            'variantId': null,
            'storeId': 'store-1',
            'quantity': 999,
            'minimumThreshold': 5,
            'updatedAt': '2026-03-21T10:00:00.000Z',
          }
        ]
      });

      await syncService.pull();

      final level = (await db.select(db.stockLevels).get()).first;
      expect(level.quantity, 42);
    });

    test('pull_pendingRecordStockEntry_doesNotOverwriteStockLevel', () async {
      await insertLocalStockLevel(
        id: 'sl-1', productId: 'prod-1', storeId: 'store-1', quantity: 7);
      await insertPendingSyncOp(
        operation: 'RECORD_STOCK_ENTRY',
        payload: {'productId': 'prod-1', 'storeId': 'store-1', 'quantity': 7},
        entityId: 'sl-1',
      );

      stubPull(entities: {
        'stockLevels': [
          {
            'id': 'sl-1',
            'productId': 'prod-1',
            'variantId': null,
            'storeId': 'store-1',
            'quantity': 999,
            'minimumThreshold': 5,
            'updatedAt': '2026-03-21T10:00:00.000Z',
          }
        ]
      });

      await syncService.pull();

      final level = (await db.select(db.stockLevels).get()).first;
      expect(level.quantity, 7);
    });

    test('pull_pendingCreateSale_doesNotOverwriteAnyItemStockLevel', () async {
      await insertLocalStockLevel(
        id: 'sl-1', productId: 'prod-1', storeId: 'store-1', quantity: 10);
      await insertLocalStockLevel(
        id: 'sl-2', productId: 'prod-2', storeId: 'store-1', quantity: 20);
      await insertPendingSyncOp(
        operation: 'CREATE_SALE',
        payload: {
          'storeId': 'store-1',
          'items': [
            {'productId': 'prod-1'},
            {'productId': 'prod-2'},
          ],
        },
        entityId: 'sale-1',
      );

      stubPull(entities: {
        'stockLevels': [
          {
            'id': 'sl-1',
            'productId': 'prod-1',
            'variantId': null,
            'storeId': 'store-1',
            'quantity': 999,
            'minimumThreshold': 5,
            'updatedAt': '2026-03-21T10:00:00.000Z',
          },
          {
            'id': 'sl-2',
            'productId': 'prod-2',
            'variantId': null,
            'storeId': 'store-1',
            'quantity': 888,
            'minimumThreshold': 5,
            'updatedAt': '2026-03-21T10:00:00.000Z',
          },
        ]
      });

      await syncService.pull();

      final levels = await db.select(db.stockLevels).get();
      final byProduct = {for (final l in levels) l.productId: l.quantity};
      expect(byProduct['prod-1'], 10);
      expect(byProduct['prod-2'], 20);
    });

    test('pull_pendingStockTransfer_doesNotOverwriteSourceOrDestination',
        () async {
      await insertLocalStockLevel(
        id: 'sl-1', productId: 'prod-1', storeId: 'store-1', quantity: 5);
      await insertLocalStockLevel(
        id: 'sl-2', productId: 'prod-1', storeId: 'store-2', quantity: 15);
      await insertPendingSyncOp(
        operation: 'STOCK_TRANSFER',
        payload: {
          'sourceStoreId': 'store-1',
          'destinationStoreId': 'store-2',
          'productId': 'prod-1',
          'quantity': 3,
        },
        entityId: 'tr-1',
      );

      stubPull(entities: {
        'stockLevels': [
          {
            'id': 'sl-1',
            'productId': 'prod-1',
            'variantId': null,
            'storeId': 'store-1',
            'quantity': 999,
            'minimumThreshold': 5,
            'updatedAt': '2026-03-21T10:00:00.000Z',
          },
          {
            'id': 'sl-2',
            'productId': 'prod-1',
            'variantId': null,
            'storeId': 'store-2',
            'quantity': 888,
            'minimumThreshold': 5,
            'updatedAt': '2026-03-21T10:00:00.000Z',
          },
        ]
      });

      await syncService.pull();

      final levels = await db.select(db.stockLevels).get();
      final byStore = {for (final l in levels) l.storeId: l.quantity};
      expect(byStore['store-1'], 5);
      expect(byStore['store-2'], 15);
    });

    test('pull_noPendingOps_stockLevelUpsertsNormally', () async {
      // Regression of pull_upsertsStockLevels — no sync_queue entry for
      // (prod-1, store-1), so the pull must still update the row normally.
      await insertLocalStockLevel(
        id: 'sl-1', productId: 'prod-1', storeId: 'store-1', quantity: 5);

      stubPull(entities: {
        'stockLevels': [
          {
            'id': 'sl-1',
            'productId': 'prod-1',
            'variantId': null,
            'storeId': 'store-1',
            'quantity': 30,
            'minimumThreshold': 5,
            'updatedAt': '2026-03-21T10:00:00.000Z',
          }
        ]
      });

      await syncService.pull();

      final level = (await db.select(db.stockLevels).get()).first;
      expect(level.quantity, 30);
    });

    test('pull_upsertsSalesWithItems', () async {
      stubPull(entities: {
        'sales': [
          {
            'id': 'sale-1',
            'storeId': 'store-1',
            'employeeId': 'emp-1',
            'clientId': null,
            'totalAmount': 10000,
            'discountAmount': 500,
            'paymentMode': 'CASH',
            'status': 'COMPLETED',
            'occurredAt': '2026-03-21T10:00:00.000Z',
            'createdAt': '2026-03-21T10:00:00.000Z',
            'items': [
              {
                'id': 'item-1',
                'productId': 'prod-1',
                'variantId': null,
                'productName': 'Prod A',
                'appliedUnitPrice': 5000,
                'catalogueUnitPrice': 5000,
                'quantity': 2,
                'subtotal': 10000,
              }
            ]
          }
        ]
      });

      await syncService.pull();

      final sales = await db.select(db.sales).get();
      expect(sales, hasLength(1));
      expect(sales.first.totalAmount, 10000);

      final items = await db.select(db.saleItems).get();
      expect(items, hasLength(1));
      expect(items.first.productName, 'Prod A');
    });

    test('pull_upsertsStockMovements_insertOnly', () async {
      stubPull(entities: {
        'stockMovements': [
          {
            'id': 'mov-1',
            'productId': 'prod-1',
            'variantId': null,
            'storeId': 'store-1',
            'movementType': 'SALE',
            'quantityBefore': 50,
            'quantityChange': -2,
            'quantityAfter': 48,
            'actorId': 'actor-1',
            'notes': null,
            'occurredAt': '2026-03-21T10:00:00.000Z',
          }
        ]
      });

      await syncService.pull();

      final movements = await db.select(db.stockMovements).get();
      expect(movements, hasLength(1));
      expect(movements.first.quantityDelta, -2);
    });

    test('pull_upsertsStockTransfers', () async {
      stubPull(entities: {
        'stockTransfers': [
          {
            'id': 'tr-1',
            'sourceStoreId': 'store-1',
            'destinationStoreId': 'store-2',
            'productId': 'prod-1',
            'variantId': null,
            'quantity': 10,
            'actorId': 'actor-1',
            'occurredAt': '2026-03-21T10:00:00.000Z',
            'status': 'COMPLETED',
            'notes': null,
          }
        ]
      });

      await syncService.pull();

      final transfers = await db.select(db.stockTransfers).get();
      expect(transfers, hasLength(1));
      expect(transfers.first.quantity, 10);
    });

    test('pull_confirmedTransfer_upsertsNormally', () async {
      // Transfer already confirmed by the server: it was removed from
      // sync_queue (no pending op), so pull must still upsert it normally.
      await db.customStatement(
        'INSERT INTO stock_transfers (id, source_store_id, '
        'destination_store_id, product_id, variant_id, quantity, actor_id, '
        'occurred_at, status, notes) '
        'VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?)',
        [
          'tr-1', 'store-1', 'store-2', 'prod-1', 10, 'actor-1',
          '2026-03-20T10:00:00.000Z', 'PENDING_SYNC', null,
        ],
      );

      stubPull(entities: {
        'stockTransfers': [
          {
            'id': 'tr-1',
            'sourceStoreId': 'store-1',
            'destinationStoreId': 'store-2',
            'productId': 'prod-1',
            'variantId': null,
            'quantity': 10,
            'actorId': 'actor-1',
            'occurredAt': '2026-03-21T10:00:00.000Z',
            'status': 'COMPLETED',
            'notes': 'done',
          }
        ]
      });

      await syncService.pull();

      // Guards the pull-merge's own logic: given a local row and a pulled row that
      // share the same id, `_upsertStockTransfers` must UPDATE in place, never INSERT
      // a duplicate. Server-side id preservation across the offline push (the actual
      // root cause of the phantom-duplicate bug, same class as CREATE_PRODUCT fixed in
      // commit 95eaa19) is a backend concern covered separately by
      // ExecuteTransferServiceTest / TransferSyncHandlerTest — this test cannot exercise
      // that boundary since both ids are supplied by the test itself.
      final transfers = await db.select(db.stockTransfers).get();
      expect(transfers, hasLength(1));
      final transfer = transfers.first;
      expect(transfer.id, 'tr-1');
      expect(transfer.status, 'COMPLETED');
      expect(transfer.notes, 'done');
    });

    test('pull_upsertsAuditEntries_insertOnly', () async {
      stubPull(entities: {
        'auditEntries': [
          {
            'id': 'audit-1',
            'userId': 'user-1',
            'entityType': 'Product',
            'entityId': 'prod-1',
            'action': 'PRODUCT_CREATED',
            'valueBefore': null,
            'valueAfter': '{"name":"test"}',
            'occurredAt': '2026-03-21T10:00:00.000Z',
          }
        ]
      });

      await syncService.pull();

      final entries = await db.select(db.auditEntries).get();
      expect(entries, hasLength(1));
      expect(entries.first.action, 'PRODUCT_CREATED');
    });

    test('pull_storesServerTimestampAsLastPullTimestamp', () async {
      stubPull(serverTimestamp: '2026-03-21T15:30:00.000Z');

      await syncService.pull();

      final stored = await secureStorage.read(key: kLastSyncTimestampKey);
      final ts = DateTime.fromMillisecondsSinceEpoch(int.parse(stored!));
      expect(ts.toUtc().hour, 15);
      expect(ts.toUtc().minute, 30);
    });

    test('pull_emptyDelta_doesNothing', () async {
      stubPull();

      await syncService.pull();

      final products = await db.select(db.products).get();
      expect(products, isEmpty);
    });
  });
}
