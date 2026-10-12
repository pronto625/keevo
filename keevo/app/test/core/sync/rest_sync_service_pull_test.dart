import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:drift/drift.dart' show Value;
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/di/providers.dart' show kUserRoleKey;
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
    // The one-shot date-format re-pull already happened (tested on its own).
    SharedPreferences.setMockInitialValues(
        {kPullTzFixDoneKey: true, kUserRoleKey: 'OWNER'});
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
    bool quarantined = false,
  }) async {
    await db.customStatement(
      'INSERT INTO sync_queue (id, operation, payload, entity_id, synced, '
      'retry_count, created_at, quarantined_at) VALUES (?, ?, ?, ?, 0, 0, ?, ?)',
      [
        'sq-${opSeq++}',
        operation,
        jsonEncode(payload),
        entityId,
        DateTime.now().toIso8601String(),
        quarantined ? DateTime.now().toIso8601String() : null,
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

    // v1s-16-15: a quarantined op is never pushed — it protects nothing, the
    // server refresh sets the true level.
    test('pull_quarantinedStockAdjustAndSale_doNotProtectStockLevels',
        () async {
      await insertLocalStockLevel(
        id: 'sl-1', productId: 'prod-1', storeId: 'store-1', quantity: 42);
      await insertLocalStockLevel(
        id: 'sl-2', productId: 'prod-2', storeId: 'store-1', quantity: 20);
      await insertPendingSyncOp(
        operation: 'STOCK_ADJUST',
        payload: {'productId': 'prod-1', 'storeId': 'store-1', 'quantity': 42},
        entityId: 'mov-1',
        quarantined: true,
      );
      await insertPendingSyncOp(
        operation: 'CREATE_SALE',
        payload: {
          'storeId': 'store-1',
          'items': [
            {'productId': 'prod-2'},
          ],
        },
        entityId: 'sale-1',
        quarantined: true,
      );

      stubPull(entities: {
        'stockLevels': [
          for (final (id, product, qty) in const [
            ('sl-1', 'prod-1', 999),
            ('sl-2', 'prod-2', 888),
          ])
            {
              'id': id,
              'productId': product,
              'variantId': null,
              'storeId': 'store-1',
              'quantity': qty,
              'minimumThreshold': 5,
              'updatedAt': '2026-03-21T10:00:00.000Z',
            }
        ]
      });

      await syncService.pull();

      final levels = await db.select(db.stockLevels).get();
      final byProduct = {for (final l in levels) l.productId: l.quantity};
      expect(byProduct['prod-1'], 999);
      expect(byProduct['prod-2'], 888);
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

    test('pull_storesAuditEnrichmentKeys_v1s_18_7', () async {
      stubPull(entities: {
        'auditEntries': [
          {
            'id': 'audit-enriched',
            'userId': 'user-1',
            'entityType': 'Category',
            'entityId': 'cat-1',
            'action': 'CATEGORY_CREATED',
            'valueBefore': null,
            'valueAfter': '{"name":"Pagnes"}',
            'occurredAt': '2026-03-21T10:00:00.000Z',
            'storeId': 'store-1',
            'entityLabel': 'Pagnes',
            'deviceId': 'device-D',
            'source': 'SYNC',
            'metadata': '{"actorLabel":"Awa"}',
          },
          {
            // older server: no enrichment keys at all
            'id': 'audit-legacy',
            'userId': 'user-1',
            'entityType': 'Product',
            'entityId': 'prod-1',
            'action': 'PRODUCT_CREATED',
            'valueAfter': '{}',
            'occurredAt': '2026-03-21T09:00:00.000Z',
          },
        ]
      });

      await syncService.pull();

      final rows = {for (final e in await db.select(db.auditEntries).get()) e.id: e};
      final enriched = rows['audit-enriched']!;
      expect(enriched.storeId, 'store-1');
      expect(enriched.entityLabel, 'Pagnes');
      expect(enriched.deviceId, 'device-D');
      expect(enriched.source, 'SYNC');
      expect(enriched.metadata, '{"actorLabel":"Awa"}');
      final legacy = rows['audit-legacy']!;
      expect(legacy.storeId, isNull);
      expect(legacy.entityLabel, isNull);
      expect(legacy.metadata, isNull);
    });

    test('pull_nonOwnerDevice_dropsLocalAuditEntries_v1s_18_7', () async {
      await db.customStatement(
        'INSERT INTO audit_entries (id, user_id, entity_type, entity_id, '
        'action, value_after, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
        ['old', 'u', 'Sale', 's', 'SALE_CANCELLED', '{"justification":"x"}',
            '2026-03-20T10:00:00.000'],
      );
      await (await SharedPreferences.getInstance())
          .setString(kUserRoleKey, 'EMPLOYEE');
      stubPull(entities: {
        'auditEntries': [
          {
            'id': 'audit-new',
            'userId': 'user-1',
            'entityType': 'Product',
            'entityId': 'prod-1',
            'action': 'PRODUCT_CREATED',
            'valueAfter': '{}',
            'occurredAt': '2026-03-21T10:00:00.000Z',
          }
        ]
      });

      await syncService.pull();

      expect(await db.select(db.auditEntries).get(), isEmpty);
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

  group('RestSyncService.pull() — paging', () {
    Response<Map<String, dynamic>> page({
      required String serverTimestamp,
      required Map<String, dynamic> entities,
      bool? hasMore,
      String? nextCursor,
    }) =>
        Response(
          requestOptions: RequestOptions(path: '/api/v1/sync/pull'),
          statusCode: 200,
          data: {
            'data': {
              'serverTimestamp': serverTimestamp,
              'entities': entities,
              'counts': <String, dynamic>{},
              if (hasMore != null) 'hasMore': hasMore,
              if (nextCursor != null) 'nextCursor': nextCursor,
            }
          },
        );

    Map<String, dynamic> product(String id) => {
          'id': id,
          'name': 'P $id',
          'price': 100,
          'buyPrice': 50,
          'transportCost': 0,
          'archived': false,
          'status': 'ACTIVE',
          'stockQuantity': 0,
          'createdAt': '2026-03-21T10:00:00.000Z',
          'updatedAt': '2026-03-21T10:00:00.000Z',
        };

    test('pull_hasMore_fetchesNextPagesBeforeSavingTheCursor', () async {
      final requests = <Map<String, dynamic>?>[];
      when(() => mockDio.get<Map<String, dynamic>>(
            any(),
            queryParameters: any(named: 'queryParameters'),
            options: any(named: 'options'),
          )).thenAnswer((inv) async {
        final params =
            inv.namedArguments[#queryParameters] as Map<String, dynamic>?;
        requests.add(params == null ? null : Map.of(params));
        if (params?['cursor'] == null) {
          return page(
            serverTimestamp: '2026-03-21T08:00:00.000Z',
            entities: {'products': [product('p1')]},
            hasMore: true,
            nextCursor: 'page-2',
          );
        }
        return page(
          serverTimestamp: '2026-03-21T12:00:00.000Z',
          entities: {'products': [product('p2')]},
          hasMore: false,
        );
      });

      await syncService.pull();

      expect(requests, hasLength(2));
      expect(requests[1]!['cursor'], 'page-2');
      // Every page of one pull asks for the same delta.
      expect(requests[1]!['since'], requests[0]?['since']);
      final ids = (await db.select(db.products).get()).map((p) => p.id);
      expect(ids, containsAll(['p1', 'p2']));
      final stored = await secureStorage.read(key: kLastSyncTimestampKey);
      expect(DateTime.fromMillisecondsSinceEpoch(int.parse(stored!)).toUtc(),
          DateTime.utc(2026, 3, 21, 12));
    });

    test('pull_failingLaterPage_keepsThePreviousCursor', () async {
      final lastSync = DateTime.utc(2026, 3, 20, 10);
      await secureStorage.write(
        key: kLastSyncTimestampKey,
        value: lastSync.millisecondsSinceEpoch.toString(),
      );
      final prefs = await SharedPreferences.getInstance();
      await prefs.setInt(kLastSyncAtKey, lastSync.millisecondsSinceEpoch);

      when(() => mockDio.get<Map<String, dynamic>>(
            any(),
            queryParameters: any(named: 'queryParameters'),
            options: any(named: 'options'),
          )).thenAnswer((inv) async {
        final params =
            inv.namedArguments[#queryParameters] as Map<String, dynamic>?;
        if (params?['cursor'] == null) {
          return page(
            serverTimestamp: '2026-03-21T08:00:00.000Z',
            entities: {'products': [product('p1')]},
            hasMore: true,
            nextCursor: 'page-2',
          );
        }
        throw DioException(requestOptions: RequestOptions(path: '/x'));
      });

      await expectLater(syncService.pull(), throwsA(isA<DioException>()));

      final stored = await secureStorage.read(key: kLastSyncTimestampKey);
      expect(int.parse(stored!), lastSync.millisecondsSinceEpoch);
    });

    test('pull_responseWithoutHasMore_isASinglePage', () async {
      stubPull(serverTimestamp: '2026-03-21T15:30:00.000Z');

      await syncService.pull();

      verify(() => mockDio.get<Map<String, dynamic>>(
            any(),
            queryParameters: any(named: 'queryParameters'),
            options: any(named: 'options'),
          )).called(1);
    });
  });

  group('RestSyncService.pull() — sale updates', () {
    Map<String, dynamic> serverSale({
      required String status,
      int totalAmount = 10000,
    }) =>
        {
          'id': 'sale-1',
          'storeId': 'store-1',
          'employeeId': 'emp-1',
          'clientId': null,
          'totalAmount': totalAmount,
          'discountAmount': 0,
          'paymentMode': 'CASH',
          'status': status,
          'occurredAt': '2026-03-21T10:00:00.000Z',
          'createdAt': '2026-03-21T10:00:00.000Z',
          'items': [],
        };

    Future<void> seedLocalSale(String status, {int totalAmount = 10000}) =>
        db.customStatement(
          'INSERT INTO sales (id, store_id, employee_id, total_amount, '
          'discount_amount, payment_mode, status, synced, created_at) '
          'VALUES (?, ?, ?, ?, 0, ?, ?, 1, ?)',
          [
            'sale-1',
            'store-1',
            'emp-1',
            totalAmount,
            'CASH',
            status,
            '2026-03-21T10:00:00.000',
          ],
        );

    Future<Sale> localSale() =>
        (db.select(db.sales)..where((s) => s.id.equals('sale-1'))).getSingle();

    test('pull_cancelledOnServer_cancelsTheLocalCompletedSale', () async {
      await seedLocalSale('COMPLETED');
      stubPull(entities: {'sales': [serverSale(status: 'CANCELLED')]});

      await syncService.pull();

      expect((await localSale()).status, 'CANCELLED');
    });

    test('pull_correctedAmountOnServer_updatesTheLocalSale', () async {
      await seedLocalSale('COMPLETED', totalAmount: 10000);
      stubPull(entities: {
        'sales': [serverSale(status: 'COMPLETED', totalAmount: 8000)]
      });

      await syncService.pull();

      expect((await localSale()).totalAmount, 8000);
    });

    test('pull_stalePendingValidation_doesNotDowngradeACompletedSale',
        () async {
      await seedLocalSale('COMPLETED');
      stubPull(entities: {'sales': [serverSale(status: 'PENDING_VALIDATION')]});

      await syncService.pull();

      expect((await localSale()).status, 'COMPLETED');
    });

    test('pull_pendingLocalCancellation_isNotOverwritten', () async {
      await seedLocalSale('CANCELLED');
      await insertPendingSyncOp(
        operation: 'CANCEL_SALE',
        payload: {'saleId': 'sale-1', 'justification': 'x'},
        entityId: 'sale-1',
      );
      stubPull(entities: {'sales': [serverSale(status: 'COMPLETED')]});

      await syncService.pull();

      expect((await localSale()).status, 'CANCELLED');
    });

    // v1s-16-15: a quarantined op does not protect its entity id.
    test('pull_quarantinedCancellation_doesNotProtectTheSale', () async {
      await seedLocalSale('CANCELLED');
      await insertPendingSyncOp(
        operation: 'CANCEL_SALE',
        payload: {'saleId': 'sale-1', 'justification': 'x'},
        entityId: 'sale-1',
        quarantined: true,
      );
      stubPull(entities: {'sales': [serverSale(status: 'COMPLETED')]});

      await syncService.pull();

      expect((await localSale()).status, 'COMPLETED');
    });
  });

  group('RestSyncService.pull() — validation stock protection', () {
    Map<String, dynamic> serverLevel(String productId) => {
          'id': 'sl-$productId',
          'productId': productId,
          'variantId': null,
          'storeId': 'store-1',
          'quantity': 999,
          'minimumThreshold': 0,
          'updatedAt': '2026-03-21T10:00:00.000Z',
        };

    test('pull_pendingValidateSale_doesNotOverwriteItsStockLevels', () async {
      await insertLocalStockLevel(
          id: 'sl-prod-1', productId: 'prod-1', storeId: 'store-1', quantity: 3);
      await db.customStatement(
        'INSERT INTO sales (id, store_id, employee_id, total_amount, '
        'discount_amount, payment_mode, status, synced, created_at) '
        "VALUES ('sale-1', 'store-1', 'emp-1', 100, 0, 'CASH', 'COMPLETED', 1, "
        "'2026-03-21T10:00:00.000')",
      );
      await db.customStatement(
        'INSERT INTO sale_items (id, sale_id, product_id, product_name, '
        'unit_price, catalogue_unit_price, quantity, subtotal, created_at) '
        "VALUES ('i-1', 'sale-1', 'prod-1', 'A', 100, 100, 1, 100, "
        "'2026-03-21T10:00:00.000')",
      );
      await insertPendingSyncOp(
        operation: 'VALIDATE_SALE',
        payload: {'saleId': 'sale-1', 'justification': 'ok'},
        entityId: 'sale-1',
      );
      stubPull(entities: {'stockLevels': [serverLevel('prod-1')]});

      await syncService.pull();

      final level = await (db.select(db.stockLevels)
            ..where((l) => l.productId.equals('prod-1')))
          .getSingle();
      expect(level.quantity, 3);
    });

    // A draft product validated with an initial stock, or remapped to its
    // server id, is protected like the sale's own items until the op lands.
    test('pull_pendingValidateSale_protectsInitialStockAndRemappedProducts',
        () async {
      await insertLocalStockLevel(
          id: 'sl-new', productId: 'prod-new', storeId: 'store-1', quantity: 4);
      await insertLocalStockLevel(
          id: 'sl-srv', productId: 'prod-srv', storeId: 'store-1', quantity: 6);
      await db.customStatement(
        'INSERT INTO sales (id, store_id, employee_id, total_amount, '
        'discount_amount, payment_mode, status, synced, created_at) '
        "VALUES ('sale-2', 'store-1', 'emp-1', 100, 0, 'CASH', 'COMPLETED', 1, "
        "'2026-03-21T10:00:00.000')",
      );
      await insertPendingSyncOp(
        operation: 'VALIDATE_SALE',
        payload: {
          'saleId': 'sale-2',
          'justification': 'ok',
          'initialStockEntries': {'prod-new': 4},
          'productIdRemappings': {'draft-1': 'prod-srv'},
        },
        entityId: 'sale-2',
      );
      stubPull(entities: {
        'stockLevels': [serverLevel('prod-new'), serverLevel('prod-srv')]
      });

      await syncService.pull();

      final levels = {
        for (final l in await db.select(db.stockLevels).get()) l.productId: l.quantity
      };
      expect(levels['prod-new'], 4);
      expect(levels['prod-srv'], 6);
    });

    test('pull_pendingValidateInventory_doesNotOverwriteCountedStockLevels',
        () async {
      await insertLocalStockLevel(
          id: 'sl-prod-2', productId: 'prod-2', storeId: 'store-1', quantity: 7);
      await db.customStatement(
        'INSERT INTO inventory_sessions (id, store_id, scope, status, '
        'started_by, started_at, updated_at, synced) '
        "VALUES ('ses-1', 'store-1', 'FULL', 'VALIDATED', 'u', "
        "'2026-03-21T10:00:00.000', '2026-03-21T10:00:00.000', 0)",
      );
      await db.customStatement(
        'INSERT INTO inventory_counts (id, session_id, product_id, '
        'product_name, theoretical, physical, updated_at, synced) '
        "VALUES ('c-1', 'ses-1', 'prod-2', 'B', 5, 7, "
        "'2026-03-21T10:00:00.000', 0)",
      );
      await insertPendingSyncOp(
        operation: 'VALIDATE_INVENTORY',
        payload: {'sessionId': 'ses-1'},
        entityId: 'ses-1',
      );
      stubPull(entities: {'stockLevels': [serverLevel('prod-2')]});

      await syncService.pull();

      final level = await (db.select(db.stockLevels)
            ..where((l) => l.productId.equals('prod-2')))
          .getSingle();
      expect(level.quantity, 7);
    });
  });

  group('RestSyncService.pull() — stored timestamps', () {
    test('pull_occurredAt_readsBackAsTheSameInstantThroughDrift', () async {
      stubPull(entities: {
        'sales': [
          {
            'id': 'sale-late',
            'storeId': 'store-1',
            'employeeId': 'emp-1',
            'clientId': null,
            'totalAmount': 100,
            'discountAmount': 0,
            'paymentMode': 'CASH',
            'status': 'COMPLETED',
            'occurredAt': '2026-03-21T23:30:00Z',
            'createdAt': '2026-03-21T23:30:00Z',
            'items': [],
          }
        ]
      });

      await syncService.pull();

      final sale = await (db.select(db.sales)
            ..where((s) => s.id.equals('sale-late')))
          .getSingle();
      expect(sale.occurredAt!.toUtc(), DateTime.utc(2026, 3, 21, 23, 30));
      expect(sale.createdAt.toUtc(), DateTime.utc(2026, 3, 21, 23, 30));
    });

    test('pull_storesDatesInTheFormatDriftWritesForLocalRows', () async {
      final instant = DateTime.utc(2026, 3, 21, 23, 30);
      stubPull(entities: {
        'sales': [
          {
            'id': 'sale-pulled',
            'storeId': 'store-1',
            'employeeId': 'emp-1',
            'totalAmount': 100,
            'discountAmount': 0,
            'paymentMode': 'CASH',
            'status': 'COMPLETED',
            'occurredAt': instant.toIso8601String(),
            'createdAt': instant.toIso8601String(),
            'items': [],
          }
        ]
      });
      await syncService.pull();
      await db.into(db.sales).insert(SalesCompanion.insert(
            id: 'sale-local',
            storeId: 'store-1',
            employeeId: 'emp-1',
            totalAmount: 100,
            paymentMode: 'CASH',
            occurredAt: Value(instant.toLocal()),
            createdAt: instant.toLocal(),
          ));

      final raw = await db
          .customSelect('SELECT id, occurred_at FROM sales ORDER BY id')
          .get();
      final byId = {
        for (final r in raw) r.read<String>('id'): r.read<String>('occurred_at')
      };
      expect(byId['sale-pulled'], byId['sale-local']);
    });

    test('pull_existingClosure_isMarkedSynced', () async {
      await db.customStatement(
        'INSERT INTO day_closures (id, store_id, actor_id, closed_at, '
        'total_sales, total_revenue, cash_amount, momo_amount, '
        'pending_sales_count, pending_sales_total, is_automatic, synced, '
        "created_at) VALUES ('dc-1', 'store-1', 'u1', "
        "'2026-03-21T20:00:00.000', 1, 100, 100, 0, 0, 0, 0, 0, "
        "'2026-03-21T20:00:00.000')",
      );
      stubPull(entities: {
        'dayClosures': [
          {
            'id': 'dc-1',
            'storeId': 'store-1',
            'employeeId': 'u1',
            'closedAt': '2026-03-21T19:00:00Z',
            'totalSales': 1,
            'totalRevenue': 100,
            'cashTotal': 100,
            'mobileMoneyTotal': 0,
            'isAutomatic': false,
          }
        ]
      });

      await syncService.pull();

      final closure = await db.select(db.dayClosures).getSingle();
      expect(closure.synced, isTrue);
    });

    // The server may count late offline sales in this closure: its totals win.
    test('pull_existingClosure_takesTheServerTotals', () async {
      await db.customStatement(
        'INSERT INTO day_closures (id, store_id, actor_id, closed_at, '
        'total_sales, total_revenue, cash_amount, momo_amount, '
        'pending_sales_count, pending_sales_total, is_automatic, synced, '
        "created_at) VALUES ('dc-1', 'store-1', 'u1', "
        "'2026-03-21T20:00:00.000', 1, 100, 100, 0, 0, 0, 0, 0, "
        "'2026-03-21T20:00:00.000')",
      );
      stubPull(entities: {
        'dayClosures': [
          {
            'id': 'dc-1',
            'storeId': 'store-1',
            'employeeId': 'u1',
            'closedAt': '2026-03-21T19:00:00Z',
            'totalSales': 2,
            'totalRevenue': 350,
            'cashTotal': 100,
            'mobileMoneyTotal': 250,
            'isAutomatic': false,
          }
        ]
      });

      await syncService.pull();

      final closure = await db.select(db.dayClosures).getSingle();
      expect(closure.totalSales, 2);
      expect(closure.totalRevenue, 350);
      expect(closure.cashAmount, 100);
      expect(closure.momoAmount, 250);
    });
  });

  // Rows pulled before dates carried their local offset are read one hour
  // off; a delta pull would never resend them.
  group('one-shot full pull after the date-format change', () {
    Future<RestSyncService> serviceWith(Map<String, Object> prefsValues) async {
      SharedPreferences.setMockInitialValues(prefsValues);
      return RestSyncService(
        database: db,
        remoteProducts: MockRemoteProductDataSource(),
        dio: mockDio,
        secureStorage: secureStorage,
        prefs: await SharedPreferences.getInstance(),
      );
    }

    Future<Map<String, dynamic>?> pullParams(RestSyncService service) async {
      stubPull();
      await service.pull();
      return verify(() => mockDio.get<Map<String, dynamic>>(
            any(),
            queryParameters: captureAny(named: 'queryParameters'),
            options: any(named: 'options'),
          )).captured.last as Map<String, dynamic>?;
    }

    test('first pull after the upgrade ignores the cursor, once', () async {
      final lastSync = DateTime.utc(2026, 3, 20, 10).millisecondsSinceEpoch;
      await secureStorage.write(
          key: kLastSyncTimestampKey, value: lastSync.toString());
      final service = await serviceWith({kLastSyncAtKey: lastSync});

      expect(await pullParams(service), isNull);
      expect((await SharedPreferences.getInstance()).getBool(kPullTzFixDoneKey),
          isTrue);

      // The next pull is a delta again.
      expect((await pullParams(service))?['since'], isNotNull);
    });
  });
}
