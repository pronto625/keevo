import 'dart:ffi';
import 'dart:io';

import 'package:dio/dio.dart';
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

/// Story 5.2 — AC7 Pull-Sync Merge Protection Tests
///
/// Validates that pull upserts skip entities with pending (unsynced)
/// local changes in sync_queue, while still upserting everything else.
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

  void stubPull({Map<String, dynamic>? entities}) {
    when(() => mockDio.get<Map<String, dynamic>>(
          any(),
          queryParameters: any(named: 'queryParameters'),
          options: any(named: 'options'),
        )).thenAnswer((_) async => pullResponse(entities: entities));
  }

  Future<void> _insertPendingSyncOp({
    required String entityId,
    String operation = 'UPDATE_PRODUCT',
  }) async {
    await db.customStatement(
      'INSERT INTO sync_queue (id, operation, payload, entity_id, synced, retry_count, created_at) '
      'VALUES (?, ?, ?, ?, 0, 0, ?)',
      [
        'sq-${DateTime.now().microsecondsSinceEpoch}',
        operation,
        '{"name":"local"}',
        entityId,
        DateTime.now().toIso8601String(),
      ],
    );
  }

  group('AC7 — Pull merge skips unsynced entities', () {
    test('product_withPendingPush_isNotOverwritten', () async {
      // Insert an existing local product
      await db.customStatement(
        'INSERT INTO products (id, name, price, buy_price, stock_quantity, '
        'archived, status, created_at, updated_at) '
        'VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?)',
        [
          'prod-1', 'Local Name', 9999, 500, 5, 'ACTIVE',
          '2026-03-19T10:00:00.000Z', '2026-03-19T10:00:00.000Z',
        ],
      );

      // Queue an unsynced operation for this product
      await _insertPendingSyncOp(entityId: 'prod-1');

      // Server sends an update for the same product
      stubPull(entities: {
        'products': [
          {
            'id': 'prod-1',
            'name': 'Server Name',
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

      // Local product should NOT be overwritten
      final products = await db.select(db.products).get();
      expect(products, hasLength(1));
      expect(products.first.name, 'Local Name');
      expect(products.first.price, 9999);
    });

    test('product_withoutPendingPush_isUpdated', () async {
      // No pending op for prod-2 → should be upserted normally
      stubPull(entities: {
        'products': [
          {
            'id': 'prod-2',
            'name': 'New From Server',
            'description': null,
            'sku': null,
            'categoryId': null,
            'price': 3000,
            'buyPrice': 1500,
            'transportCost': 0,
            'stockQuantity': 20,
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
      expect(products.first.name, 'New From Server');
    });

    test('category_withPendingPush_isNotOverwritten', () async {
      await db.customStatement(
        'INSERT INTO categories (id, name, is_active, is_custom, created_at, updated_at) '
        'VALUES (?, ?, 1, 1, ?, ?)',
        ['cat-1', 'Local Cat', '2026-03-19T10:00:00.000Z', '2026-03-19T10:00:00.000Z'],
      );
      await _insertPendingSyncOp(entityId: 'cat-1', operation: 'UPDATE_CATEGORY');

      stubPull(entities: {
        'categories': [
          {
            'id': 'cat-1',
            'name': 'Server Cat',
            'parentId': null,
            'isActive': true,
            'isCustom': true,
            'createdAt': '2026-03-19T10:00:00.000Z',
            'updatedAt': '2026-03-21T10:00:00.000Z',
          }
        ]
      });

      await syncService.pull();

      final cats = await db.select(db.categories).get();
      expect(cats, hasLength(1));
      expect(cats.first.name, 'Local Cat');
    });

    test(
        'category_deletePending_TOGGLE_CATEGORY_isActiveFalse_isNotReactivated',
        () async {
      // Story 14.14 AC3: deleteCategory() soft-deactivates locally
      // (isActive=false) and queues a TOGGLE_CATEGORY(isActive:false) op.
      // A stale pull that still reports the server-side isActive=true
      // (i.e. arrives before the queued push has been processed) must NOT
      // reactivate the category locally, thanks to the AC7 pending-entity
      // guard below.
      await db.customStatement(
        'INSERT INTO categories (id, name, is_active, is_custom, created_at, updated_at) '
        'VALUES (?, ?, 0, 1, ?, ?)',
        ['cat-del-1', 'À supprimer', '2026-03-19T10:00:00.000Z', '2026-03-19T10:00:00.000Z'],
      );
      await _insertPendingSyncOp(
        entityId: 'cat-del-1',
        operation: 'TOGGLE_CATEGORY',
      );

      stubPull(entities: {
        'categories': [
          {
            'id': 'cat-del-1',
            'name': 'À supprimer',
            'parentId': null,
            'isActive': true, // server hasn't processed the delete push yet
            'isCustom': true,
            'createdAt': '2026-03-19T10:00:00.000Z',
            'updatedAt': '2026-03-21T10:00:00.000Z',
          }
        ]
      });

      await syncService.pull();

      final cats = await db.select(db.categories).get();
      expect(cats, hasLength(1));
      expect(cats.first.id, 'cat-del-1');
      expect(cats.first.isActive, isFalse,
          reason: 'AC7 guard must skip the stale pull upsert because '
              'cat-del-1 has a pending unsynced TOGGLE_CATEGORY op');
    });

    test('client_withPendingPush_isNotOverwritten', () async {
      await db.customStatement(
        'INSERT INTO clients (id, name, phone, archived, created_at, updated_at) '
        'VALUES (?, ?, ?, 0, ?, ?)',
        ['cli-1', 'Local Client', '+237600000000', '2026-03-19T10:00:00.000Z', '2026-03-19T10:00:00.000Z'],
      );
      await _insertPendingSyncOp(entityId: 'cli-1', operation: 'UPDATE_CLIENT');

      stubPull(entities: {
        'clients': [
          {
            'id': 'cli-1',
            'name': 'Server Client',
            'phone': null,
            'email': null,
            'notes': null,
            'archived': false,
            'createdAt': '2026-03-19T10:00:00.000Z',
            'updatedAt': '2026-03-21T10:00:00.000Z',
          }
        ]
      });

      await syncService.pull();

      final clients = await db.select(db.clients).get();
      expect(clients, hasLength(1));
      expect(clients.first.name, 'Local Client');
    });

    test('supplier_withPendingPush_isNotOverwritten', () async {
      await db.customStatement(
        'INSERT INTO suppliers (id, name, phone, archived, created_at, updated_at) '
        'VALUES (?, ?, ?, 0, ?, ?)',
        ['sup-1', 'Local Supplier', '+237600000000', '2026-03-19T10:00:00.000Z', '2026-03-19T10:00:00.000Z'],
      );
      await _insertPendingSyncOp(entityId: 'sup-1', operation: 'UPDATE_SUPPLIER');

      stubPull(entities: {
        'suppliers': [
          {
            'id': 'sup-1',
            'name': 'Server Supplier',
            'phone': null,
            'email': null,
            'archived': false,
            'createdAt': '2026-03-19T10:00:00.000Z',
            'updatedAt': '2026-03-21T10:00:00.000Z',
          }
        ]
      });

      await syncService.pull();

      final suppliers = await db.select(db.suppliers).get();
      expect(suppliers, hasLength(1));
      expect(suppliers.first.name, 'Local Supplier');
    });

    test('stockLevels_alwaysUpserted_evenWithPendingOps', () async {
      // Stock levels are always overwritten by server (no AC7 protection)
      await _insertPendingSyncOp(entityId: 'sl-1', operation: 'UPDATE_STOCK');

      stubPull(entities: {
        'stockLevels': [
          {
            'id': 'sl-1',
            'productId': 'prod-1',
            'variantId': null,
            'storeId': 'store-1',
            'quantity': 99,
            'minimumThreshold': 5,
            'updatedAt': '2026-03-21T10:00:00.000Z',
          }
        ]
      });

      await syncService.pull();

      final levels = await db.select(db.stockLevels).get();
      expect(levels, hasLength(1));
      expect(levels.first.quantity, 99);
    });

    test('auditEntries_insertOnly_duplicateIgnored', () async {
      // Pre-insert an audit entry
      await db.customStatement(
        'INSERT INTO audit_entries (id, user_id, entity_type, entity_id, '
        'action, occurred_at) VALUES (?, ?, ?, ?, ?, ?)',
        ['audit-1', 'user-1', 'Product', 'prod-1', 'PRODUCT_CREATED',
         '2026-03-21T10:00:00.000Z'],
      );

      // Server sends same audit entry again
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

      // Should still be only 1 entry (INSERT OR IGNORE)
      final entries = await db.select(db.auditEntries).get();
      expect(entries, hasLength(1));
    });

    test('mixedEntities_pendingSkipped_othersUpserted', () async {
      // prod-1 has pending ops, prod-2 does not
      await _insertPendingSyncOp(entityId: 'prod-1');

      stubPull(entities: {
        'products': [
          {
            'id': 'prod-1',
            'name': 'Server Prod 1',
            'description': null,
            'sku': null,
            'categoryId': null,
            'price': 1000,
            'buyPrice': 500,
            'transportCost': 0,
            'stockQuantity': 5,
            'photoUrl': null,
            'archived': false,
            'status': 'ACTIVE',
            'createdAt': '2026-03-20T10:00:00.000Z',
            'updatedAt': '2026-03-21T10:00:00.000Z',
          },
          {
            'id': 'prod-2',
            'name': 'Server Prod 2',
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
            'createdAt': '2026-03-20T10:00:00.000Z',
            'updatedAt': '2026-03-21T10:00:00.000Z',
          },
        ]
      });

      await syncService.pull();

      final products = await db.select(db.products).get();
      // Only prod-2 should be inserted (prod-1 skipped due to pending)
      expect(products, hasLength(1));
      expect(products.first.id, 'prod-2');
      expect(products.first.name, 'Server Prod 2');
    });

    test('newProduct_notInSyncQueue_isInserted', () async {
      // Even with other pending ops, a brand-new product ID is inserted
      await _insertPendingSyncOp(entityId: 'prod-other');

      stubPull(entities: {
        'products': [
          {
            'id': 'prod-new',
            'name': 'Brand New',
            'description': null,
            'sku': null,
            'categoryId': null,
            'price': 5000,
            'buyPrice': 2500,
            'transportCost': 0,
            'stockQuantity': 100,
            'photoUrl': null,
            'archived': false,
            'status': 'ACTIVE',
            'createdAt': '2026-03-21T10:00:00.000Z',
            'updatedAt': '2026-03-21T10:00:00.000Z',
          }
        ]
      });

      await syncService.pull();

      final products = await db.select(db.products).get();
      expect(products, hasLength(1));
      expect(products.first.name, 'Brand New');
    });
  });
}
