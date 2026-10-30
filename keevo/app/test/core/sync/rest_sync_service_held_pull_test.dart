import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:drift/drift.dart' hide isNull, isNotNull;
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/core/sync/held_operations.dart';
import 'package:keevo/core/sync/product_photo_outbox.dart';
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

/// Story 13.8b — while an operation is held (`MODULE_INACTIVE` /
/// `MODULE_READ_ONLY`), pulls never delete or replace the local entity it
/// created or changed, and its stock delta stays applied on top of the server
/// stock. Once the module is back and the operation applies, the next pull
/// converges without double counting (deterministic sale / movement ids).
void main() {
  setUpAll(_overrideSqlite3ForLinuxTesting);

  late AppDatabase db;
  late MockDio mockDio;
  late RestSyncService syncService;
  final saleAt = DateTime(2026, 10, 30, 10);

  setUp(() async {
    db = AppDatabase.forTesting();
    mockDio = MockDio();
    FlutterSecureStorage.setMockInitialValues({});
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
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

  void stubPull(Map<String, dynamic> entities) {
    when(() => mockDio.get<Map<String, dynamic>>(
          any(),
          queryParameters: any(named: 'queryParameters'),
          options: any(named: 'options'),
        )).thenAnswer((_) async => Response(
          requestOptions: RequestOptions(path: '/api/v1/sync/pull'),
          statusCode: 200,
          data: {
            'data': {
              'serverTimestamp': '2026-10-30T12:00:00.000Z',
              'entities': entities,
              'counts': <String, dynamic>{},
            }
          },
        ));
  }

  void stubPush(String operationId, String status, String? reason) {
    when(() => mockDio.post<dynamic>(any(), data: any(named: 'data')))
        .thenAnswer((_) async => Response(
              requestOptions: RequestOptions(path: '/api/v1/sync/push'),
              statusCode: 200,
              data: {
                'data': {
                  'results': [
                    {
                      'operationId': operationId,
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

  Future<void> queueOp(String id, String operation, String entityId,
      Map<String, dynamic> payload,
      {String? lastError}) {
    return db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
          id: id,
          operation: operation,
          payload: jsonEncode(payload),
          createdAt: saleAt,
          entityId: Value(entityId),
          lastError: Value(lastError),
          lastAttemptAt: Value(lastError == null ? null : saleAt),
        ));
  }

  /// The local state after an offline sale of 1 « Savon » (stock 19 → 18):
  /// sale + item + SALE movement (id = item id) + stock row, CREATE_SALE
  /// queued and held with [reason].
  Future<void> heldSale(String reason) async {
    await db.into(db.sales).insert(SalesCompanion.insert(
          id: 'sale-1',
          storeId: 'store-1',
          employeeId: 'emp-1',
          totalAmount: 500,
          paymentMode: 'CASH',
          status: const Value('COMPLETED'),
          occurredAt: Value(saleAt),
          createdAt: saleAt,
        ));
    await db.into(db.saleItems).insert(SaleItemsCompanion.insert(
          id: 'item-1',
          saleId: 'sale-1',
          productId: 'prod-1',
          productName: 'Savon',
          unitPrice: 500,
          quantity: 1,
          subtotal: 500,
          createdAt: saleAt,
        ));
    await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
          id: 'sl-1',
          productId: 'prod-1',
          storeId: 'store-1',
          quantity: 18,
          updatedAt: saleAt,
        ));
    await db.into(db.stockMovements).insert(StockMovementsCompanion.insert(
          id: 'item-1',
          productId: 'prod-1',
          storeId: 'store-1',
          type: 'SALE',
          quantityDelta: -1,
          actorId: 'emp-1',
          quantityBefore: const Value(19),
          quantityAfter: const Value(18),
          createdAt: saleAt,
        ));
    await queueOp('op-sale', 'CREATE_SALE', 'sale-1', {
      'storeId': 'store-1',
      'items': [
        {'itemId': 'item-1', 'productId': 'prod-1', 'quantity': 1}
      ],
    }, lastError: reason);
  }

  /// What the server sends while the sale is held: its stock (19), no sale.
  Map<String, dynamic> serverWithoutSale() => {
        'stockLevels': [
          {
            'id': 'srv-sl-1',
            'productId': 'prod-1',
            'storeId': 'store-1',
            'quantity': 19,
            'minimumThreshold': 0,
            'updatedAt': '2026-10-30T11:00:00.000Z',
          }
        ],
        'sales': <dynamic>[],
      };

  Future<int> localStock() async => (await (db.select(db.stockLevels)
            ..where((t) =>
                t.productId.equals('prod-1') & t.storeId.equals('store-1')))
          .getSingle())
      .quantity;

  for (final reason in ['MODULE_INACTIVE', 'MODULE_READ_ONLY']) {
    test('$reason: a held sale survives a pull with its stock decrement',
        () async {
      await heldSale(reason);
      stubPull(serverWithoutSale());

      await syncService.pull();

      expect(await db.select(db.sales).get(), hasLength(1));
      expect(await db.select(db.saleItems).get(), hasLength(1));
      expect(await localStock(), 18, reason: 'server 19 − held sale 1');
      // Held: not pending (pulls keep running) but unsynced local data that
      // repositories must not overwrite with a server read.
      expect(await syncService.hasPendingOperations(), isFalse);
      expect(await syncService.heldOperationCount(), 1);
      expect(await syncService.unsyncedStockKeys(), {'prod-1|store-1'});
      expect(await syncService.unsyncedEntityIds(), contains('sale-1'));
    });
  }

  test('a held client edit is not replaced by a pull', () async {
    await db.into(db.clients).insert(ClientsCompanion.insert(
          id: 'client-1',
          name: 'Mireille Ngono',
          phone: '690000000',
          createdAt: saleAt,
          updatedAt: saleAt,
        ));
    await queueOp('op-c', 'UPDATE_CLIENT', 'client-1',
        {'clientId': 'client-1', 'name': 'Mireille Ngono'},
        lastError: 'MODULE_INACTIVE');
    stubPull({
      'clients': [
        {
          'id': 'client-1',
          'name': 'Mireille',
          'phone': '690000000',
          'archived': false,
          'createdAt': '2026-10-30T09:00:00.000Z',
          'updatedAt': '2026-10-30T09:00:00.000Z',
        }
      ],
    });

    await syncService.pull();

    final client = await db.select(db.clients).getSingle();
    expect(client.name, 'Mireille Ngono');
  });

  test(
      'held then applied: the next pull converges — one sale, one movement, '
      'server stock', () async {
    await heldSale('MODULE_INACTIVE');
    stubPull(serverWithoutSale());
    await syncService.pull();
    expect(await localStock(), 18);

    // Module back: the held sale is re-pushed (first push of the service)
    // and applied; the server now has the sale, its SALE movement (same id
    // as the item) and stock 18.
    stubPush('op-sale', 'APPLIED', null);
    await syncService.push();
    expect(await db.select(db.syncQueue).get(), isEmpty);
    expect(await syncService.heldOperationCount(), 0);
    expect(await syncService.unsyncedStockKeys(), isEmpty);
    expect(await syncService.unsyncedEntityIds(), isEmpty);

    stubPull({
      'stockLevels': [
        {
          'id': 'srv-sl-1',
          'productId': 'prod-1',
          'storeId': 'store-1',
          'quantity': 18,
          'minimumThreshold': 0,
          'updatedAt': '2026-10-30T12:00:00.000Z',
        }
      ],
      'sales': [
        {
          'id': 'sale-1',
          'storeId': 'store-1',
          'employeeId': 'emp-1',
          'totalAmount': 500,
          'discountAmount': 0,
          'paymentMode': 'CASH',
          'status': 'COMPLETED',
          'occurredAt': '2026-10-30T09:00:00.000Z',
          'createdAt': '2026-10-30T09:00:00.000Z',
          'items': [
            {
              'id': 'item-1',
              'productId': 'prod-1',
              'productName': 'Savon',
              'appliedUnitPrice': 500,
              'quantity': 1,
              'subtotal': 500,
            }
          ],
        }
      ],
      'stockMovements': [
        {
          'id': 'item-1',
          'productId': 'prod-1',
          'storeId': 'store-1',
          'movementType': 'SALE',
          'quantityBefore': 19,
          'quantityChange': -1,
          'quantityAfter': 18,
          'actorId': 'emp-1',
          'occurredAt': '2026-10-30T09:00:00.000Z',
        }
      ],
    });
    await syncService.pull();

    expect(await db.select(db.sales).get(), hasLength(1));
    expect(await db.select(db.saleItems).get(), hasLength(1));
    expect(await db.select(db.stockMovements).get(), hasLength(1));
    expect(await localStock(), 18);
  });

  // Story 13.8b review: one selection for every protection, payload
  // references included.
  test(
      'unsyncedEntityIds: entities + ids referenced by their payloads; '
      'photo uploads and quarantined operations excluded', () async {
    await queueOp('op-sale', 'CREATE_SALE', 'sale-1', {
      'storeId': 'store-1',
      'clientId': 'client-1',
      'items': [
        {'itemId': 'item-1', 'productId': 'prod-1', 'quantity': 1}
      ],
    }, lastError: 'MODULE_INACTIVE');
    await queueOp('op-move', 'STOCK_TRANSFER', 'tr-1', {
      'productId': 'prod-2',
      'sourceStoreId': 'store-1',
      'destinationStoreId': 'store-2',
    });
    await queueOp('op-photo', ProductPhotoOutbox.uploadOperation, 'prod-9',
        {'productId': 'prod-9'});
    await db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
          id: 'op-q',
          operation: 'CREATE_SALE',
          payload: jsonEncode({'clientId': 'client-q'}),
          createdAt: saleAt,
          entityId: const Value('sale-q'),
          quarantinedAt: Value(saleAt),
        ));

    final ids = await syncService.unsyncedEntityIds();

    expect(
        ids,
        containsAll(<String>[
          'sale-1', 'client-1', 'prod-1', 'store-1', 'item-1', //
          'tr-1', 'prod-2', 'store-2',
        ]));
    expect(ids, isNot(contains('prod-9')), reason: 'photo upload');
    expect(ids, isNot(contains('sale-q')), reason: 'quarantined');
    expect(ids, isNot(contains('client-q')), reason: 'quarantined');
    expect(await syncService.unsyncedStockKeys(),
        {'prod-1|store-1', 'prod-2|store-1', 'prod-2|store-2'});
    expect(await syncService.heldOperationCount(), 1);
  });

  group('heldReasonsByEntity', () {
    SyncQueueData op(String id, String? entityId, String? lastError,
            {bool synced = false, DateTime? quarantinedAt}) =>
        SyncQueueData(
          id: id,
          operation: 'CREATE_SALE',
          payload: '{}',
          entityId: entityId,
          synced: synced,
          retryCount: 0,
          lastError: lastError,
          createdAt: saleAt,
          quarantinedAt: quarantinedAt,
        );

    test('keeps held operations only, dominant reason per entity', () {
      final map = heldReasonsByEntity([
        op('1', 'sale-1', 'MODULE_READ_ONLY'),
        op('2', 'sale-1', 'MODULE_INACTIVE'),
        op('3', 'client-1', 'MODULE_READ_ONLY'),
        op('4', 'sale-2', null),
        op('5', 'sale-3', 'INTERNAL_ERROR'),
        op('6', 'sale-4', 'MODULE_INACTIVE', synced: true),
        op('7', 'sale-5', 'MODULE_INACTIVE', quarantinedAt: saleAt),
        op('8', null, 'MODULE_INACTIVE'),
      ]);
      expect(map, {
        'sale-1': 'MODULE_INACTIVE',
        'client-1': 'MODULE_READ_ONLY',
      });
    });

    test('French badge labels, no code', () {
      expect(heldOperationBadgeLabel('MODULE_INACTIVE'),
          'En attente — module non activé');
      expect(heldOperationBadgeLabel('MODULE_READ_ONLY'),
          'En attente — abonnement expiré');
    });
  });
}
