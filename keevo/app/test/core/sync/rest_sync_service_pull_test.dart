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
        )).thenAnswer((_) async => pullResponse(
          serverTimestamp: serverTimestamp,
          entities: entities,
        ));
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

      stubPull();
      await syncService.pull();

      final captured = verify(() => mockDio.get<Map<String, dynamic>>(
            captureAny(),
            queryParameters: captureAny(named: 'queryParameters'),
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
