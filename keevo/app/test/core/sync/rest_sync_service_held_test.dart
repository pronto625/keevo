import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:drift/drift.dart' show Value;
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/rest_sync_service.dart';
import 'package:keevo/features/catalog/data/datasource/remote_product_datasource.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/sync/sync_rejection_policy.dart';
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

/// Story 12.7 — `MODULE_INACTIVE` holds an operation: the module was switched
/// off for the account, a state the owner can undo and whose data is kept for
/// life. Nothing is rolled back, stripped, dropped or quarantined; the held
/// operations (and the follow-ups of their entities) are pushed again later.
void main() {
  setUpAll(_overrideSqlite3ForLinuxTesting);

  late AppDatabase db;
  late MockDio mockDio;
  late RestSyncService syncService;
  final now = DateTime(2026, 10, 1);

  RestSyncService newService(SharedPreferences prefs) => RestSyncService(
        database: db,
        remoteProducts: MockRemoteProductDataSource(),
        dio: mockDio,
        secureStorage: const FlutterSecureStorage(),
        prefs: prefs,
      );

  late SharedPreferences prefs;

  setUp(() async {
    db = AppDatabase.forTesting();
    mockDio = MockDio();
    FlutterSecureStorage.setMockInitialValues({});
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
    syncService = newService(prefs);
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
    opClock = opClock.add(const Duration(seconds: 1));
    return db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
          id: id,
          operation: operation,
          payload: jsonEncode(payload),
          createdAt: opClock,
          entityId: Value(entityId),
        ));
  }

  final sentIds = <List<String>>[];

  /// Stubs every push answering each (operationId, status, reason) — only for
  /// the operations actually sent; records what was sent.
  void stubPushResults(List<(String, String, String?)> results) {
    when(() => mockDio.post<dynamic>(any(), data: any(named: 'data')))
        .thenAnswer((inv) async {
      final data = inv.namedArguments[#data] as Map<String, dynamic>;
      final sent = (data['operations'] as List)
          .map((o) => (o as Map)['operationId'] as String)
          .toList();
      sentIds.add(sent);
      return Response(
        requestOptions: RequestOptions(path: '/api/v1/sync/push'),
        statusCode: 200,
        data: {
          'data': {
            'results': [
              for (final (id, status, reason) in results)
                if (sent.contains(id))
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
      );
    });
  }

  Future<SyncQueueData> queued(String id) =>
      (db.select(db.syncQueue)..where((t) => t.id.equals(id))).getSingle();

  Future<List<String>> queuedIds() async =>
      (await db.select(db.syncQueue).get()).map((o) => o.id).toList();

  setUp(sentIds.clear);

  test(
      'a held product creation keeps its local row and stock; its follow-ups '
      'refused PRODUCT_NOT_FOUND are held too', () async {
    await db.into(db.products).insert(ProductsCompanion.insert(
        id: 'prod-1', name: 'Savon', createdAt: now, updatedAt: now));
    await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
        id: 'sl-1',
        productId: 'prod-1',
        storeId: 'store-1',
        quantity: 5,
        updatedAt: now));
    await insertPendingOp(
        id: 'op-1', operation: 'CREATE_PRODUCT', entityId: 'prod-1');
    await insertPendingOp(
        id: 'op-2',
        operation: 'RECORD_STOCK_ENTRY',
        entityId: 'mv-1',
        payload: {'productId': 'prod-1', 'storeId': 'store-1'});
    await insertPendingOp(
        id: 'op-3',
        operation: 'CREATE_SALE',
        entityId: 'sale-1',
        payload: {
          'storeId': 'store-1',
          'items': [
            {'productId': 'prod-1', 'quantity': 1}
          ]
        });
    stubPushResults([
      ('op-1', 'REJECTED', 'MODULE_INACTIVE'),
      ('op-2', 'REJECTED', 'PRODUCT_NOT_FOUND'),
      ('op-3', 'REJECTED', 'PRODUCT_NOT_FOUND'),
    ]);

    final notices = await syncService.push();

    expect(await db.select(db.products).get(), hasLength(1));
    expect(await db.select(db.stockLevels).get(), hasLength(1));
    expect(await queuedIds(), unorderedEquals(['op-1', 'op-2', 'op-3']));
    for (final id in ['op-1', 'op-2', 'op-3']) {
      final op = await queued(id);
      expect(op.lastError, 'MODULE_INACTIVE', reason: id);
      expect(op.quarantinedAt, isNull, reason: id);
    }
    expect(jsonDecode((await queued('op-3')).payload)['items'], isNotEmpty);
    expect(notices.map((n) => n['reason']).toSet(), {'MODULE_INACTIVE'});
    expect(notices.every((n) => n['rolledBack'] == false), isTrue);
    expect(await syncService.hasPendingOperations(), isFalse,
        reason: 'held operations do not block the pulls');
    expect(await syncService.quarantinedOperationCount(), 0);
  });

  test('a held client keeps the queued sale\'s clientId and its local sales',
      () async {
    await db.into(db.clients).insert(ClientsCompanion.insert(
        id: 'client-x',
        name: 'Awa',
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
        id: 'op-2',
        operation: 'CREATE_SALE',
        entityId: 'sale-1',
        payload: {'storeId': 'store-1', 'clientId': 'client-x'});
    stubPushResults([
      ('op-1', 'REJECTED', 'MODULE_INACTIVE'),
      ('op-2', 'APPLIED', null),
    ]);

    await syncService.push();

    expect(await db.select(db.clients).get(), hasLength(1));
    expect((await db.select(db.sales).getSingle()).clientId, 'client-x');
    expect(await queuedIds(), ['op-1']);
  });

  test(
      'live scenario: a sale refused CLIENT_NOT_FOUND after its held client is '
      'held too — kept with its clientId, not quarantined; the client-less '
      'sale is applied', () async {
    await insertPendingOp(
        id: 'op-1', operation: 'CREATE_CLIENT', entityId: 'client-x');
    await insertPendingOp(
        id: 'op-2',
        operation: 'CREATE_SALE',
        entityId: 'sale-1',
        payload: {'storeId': 'store-1', 'clientId': 'client-x'});
    await insertPendingOp(
        id: 'op-3',
        operation: 'CREATE_SALE',
        entityId: 'sale-2',
        payload: {'storeId': 'store-1'});
    stubPushResults([
      ('op-1', 'REJECTED', 'MODULE_INACTIVE'),
      ('op-2', 'REJECTED', 'CLIENT_NOT_FOUND'),
      ('op-3', 'APPLIED', null),
    ]);

    await syncService.push();

    expect(await queuedIds(), unorderedEquals(['op-1', 'op-2']));
    final sale = await queued('op-2');
    expect(sale.lastError, 'MODULE_INACTIVE');
    expect(sale.quarantinedAt, isNull);
    expect(jsonDecode(sale.payload)['clientId'], 'client-x');
    expect(await syncService.quarantinedOperationCount(), 0);
    expect(await syncService.hasPendingOperations(), isFalse);
  });

  test('a held money operation is not quarantined', () async {
    await insertPendingOp(
        id: 'op-1', operation: 'CREATE_SALE', entityId: 'sale-1');
    await insertPendingOp(
        id: 'op-2', operation: 'VALIDATE_SALE', entityId: 'sale-1');
    stubPushResults([
      ('op-1', 'REJECTED', 'MODULE_INACTIVE'),
      ('op-2', 'REJECTED', 'SALE_NOT_FOUND'),
    ]);

    final notices = await syncService.push();

    expect(quarantinesOnRejection('CREATE_SALE', 'MODULE_INACTIVE'), isFalse);
    expect(await syncService.quarantinedOperationCount(), 0);
    expect((await queued('op-1')).lastError, 'MODULE_INACTIVE');
    expect((await queued('op-2')).lastError, 'MODULE_INACTIVE',
        reason: 'the validation follows its held sale');
    expect(notices, hasLength(2));
  });

  test('held operations: one notice per operation, not re-notified', () async {
    await insertPendingOp(
        id: 'op-1', operation: 'UPDATE_CLIENT', entityId: 'client-1');
    stubPushResults([('op-1', 'REJECTED', 'MODULE_INACTIVE')]);

    expect(await syncService.push(), hasLength(1));
    // App restart: the held op is pushed again, refused again, not notified.
    syncService = newService(prefs);
    expect(await syncService.push(), isEmpty);
    expect(sentIds, hasLength(2));
  });

  test(
      'held operations are throttled, then re-pushed at the retry slot and '
      'removed once APPLIED', () async {
    await insertPendingOp(
        id: 'op-1', operation: 'CREATE_CLIENT', entityId: 'client-1');
    stubPushResults([('op-1', 'REJECTED', 'MODULE_INACTIVE')]);
    await syncService.push();
    expect(sentIds, [
      ['op-1']
    ]);

    // Same session, within the interval: not sent.
    await syncService.push();
    expect(sentIds, hasLength(1));

    // The retry slot is reached (last attempt older than the interval) and
    // the module is active again.
    await (db.update(db.syncQueue)..where((t) => t.id.equals('op-1'))).write(
        SyncQueueCompanion(
            lastAttemptAt: Value(DateTime.now()
                .subtract(heldRetryInterval + const Duration(minutes: 1)))));
    stubPushResults([('op-1', 'APPLIED', null)]);
    await syncService.push();

    expect(sentIds.last, ['op-1']);
    expect(await queuedIds(), isEmpty);
  });

  test('a held operation is re-pushed on the first push after an app start',
      () async {
    await insertPendingOp(
        id: 'op-1', operation: 'CREATE_CLIENT', entityId: 'client-1');
    stubPushResults([('op-1', 'REJECTED', 'MODULE_INACTIVE')]);
    await syncService.push();

    syncService = newService(prefs);
    stubPushResults([('op-1', 'APPLIED', null)]);
    await syncService.push();

    expect(sentIds, hasLength(2));
    expect(await queuedIds(), isEmpty);
  });

  test(
      '13.5: MODULE_READ_ONLY holds like MODULE_INACTIVE — kept, not '
      'quarantined, not pending; an operation referencing it is held with '
      'the same reason', () async {
    await insertPendingOp(
        id: 'op-1', operation: 'CREATE_CLIENT', entityId: 'client-x');
    await insertPendingOp(
        id: 'op-2',
        operation: 'CREATE_SALE',
        entityId: 'sale-1',
        payload: {'storeId': 'store-1', 'clientId': 'client-x'});
    await insertPendingOp(
        id: 'op-3',
        operation: 'CREATE_SALE',
        entityId: 'sale-2',
        payload: {'storeId': 'store-1'});
    stubPushResults([
      ('op-1', 'REJECTED', 'MODULE_READ_ONLY'),
      ('op-2', 'REJECTED', 'CLIENT_NOT_FOUND'),
      ('op-3', 'APPLIED', null),
    ]);

    final notices = await syncService.push();

    expect(await queuedIds(), unorderedEquals(['op-1', 'op-2']));
    for (final id in ['op-1', 'op-2']) {
      final op = await queued(id);
      expect(op.lastError, 'MODULE_READ_ONLY', reason: id);
      expect(op.quarantinedAt, isNull, reason: id);
    }
    expect(jsonDecode((await queued('op-2')).payload)['clientId'], 'client-x');
    expect(notices.map((n) => n['reason']).toSet(), {'MODULE_READ_ONLY'});
    expect(notices.every((n) => n['held'] == true), isTrue);
    expect(await syncService.hasPendingOperations(), isFalse);
    expect(await syncService.quarantinedOperationCount(), 0);

    // Renewal: re-pushed on the next app start and applied.
    syncService = newService(prefs);
    stubPushResults([('op-1', 'APPLIED', null), ('op-2', 'APPLIED', null)]);
    await syncService.push();
    expect(await queuedIds(), isEmpty);
  });

  test('13.5: a read-only op turning inactive is re-notified with the new '
      'reason', () async {
    await insertPendingOp(
        id: 'op-1', operation: 'UPDATE_CLIENT', entityId: 'client-1');
    stubPushResults([('op-1', 'REJECTED', 'MODULE_READ_ONLY')]);
    expect(await syncService.push(), hasLength(1));

    syncService = newService(prefs);
    stubPushResults([('op-1', 'REJECTED', 'MODULE_INACTIVE')]);
    final notices = await syncService.push();
    expect(notices.single['reason'], 'MODULE_INACTIVE');
    expect((await queued('op-1')).lastError, 'MODULE_INACTIVE');
  });

  test(
      '13.5: an operation referencing held entities with both reasons is '
      'held MODULE_INACTIVE, whatever the order, and not re-notified',
      () async {
    await insertPendingOp(
        id: 'op-1', operation: 'CREATE_CLIENT', entityId: 'client-x');
    await insertPendingOp(
        id: 'op-2', operation: 'CREATE_SUPPLIER', entityId: 'supplier-y');
    await insertPendingOp(
        id: 'op-3',
        operation: 'CREATE_SALE',
        entityId: 'sale-1',
        payload: {
          'storeId': 'store-1',
          'clientId': 'client-x',
          'supplierId': 'supplier-y'
        });
    stubPushResults([
      ('op-1', 'REJECTED', 'MODULE_READ_ONLY'),
      ('op-2', 'REJECTED', 'MODULE_INACTIVE'),
      ('op-3', 'REJECTED', 'CLIENT_NOT_FOUND'),
    ]);

    final first = await syncService.push();
    expect((await queued('op-3')).lastError, 'MODULE_INACTIVE');
    expect(first, hasLength(3));

    // App restart: same refusals → same reasons, no new notice.
    syncService = newService(prefs);
    final second = await syncService.push();
    expect(second, isEmpty);
    expect((await queued('op-3')).lastError, 'MODULE_INACTIVE');
  });

  test('13.5: a MODULE_READ_ONLY operation is throttled like MODULE_INACTIVE',
      () async {
    await insertPendingOp(
        id: 'op-1', operation: 'CREATE_CLIENT', entityId: 'client-1');
    stubPushResults([('op-1', 'REJECTED', 'MODULE_READ_ONLY')]);
    await syncService.push();
    await syncService.push(); // same session, within heldRetryInterval
    expect(sentIds, hasLength(1));

    await (db.update(db.syncQueue)..where((t) => t.id.equals('op-1'))).write(
        SyncQueueCompanion(
            lastAttemptAt: Value(DateTime.now()
                .subtract(heldRetryInterval + const Duration(minutes: 1)))));
    stubPushResults([('op-1', 'APPLIED', null)]);
    await syncService.push();
    expect(sentIds, hasLength(2));
    expect(await queuedIds(), isEmpty);
  });

  test('HTTP 503 (module state unreadable) keeps the queue as it is', () async {
    await insertPendingOp(
        id: 'op-1', operation: 'CREATE_SALE', entityId: 'sale-1');
    when(() => mockDio.post<dynamic>(any(), data: any(named: 'data')))
        .thenThrow(DioException(
      requestOptions: RequestOptions(path: '/api/v1/sync/push'),
      type: DioExceptionType.badResponse,
      response: Response(
        requestOptions: RequestOptions(path: '/api/v1/sync/push'),
        statusCode: 503,
        data: {'domainCode': 'SERVICE_UNAVAILABLE'},
      ),
    ));

    await expectLater(syncService.push(), throwsA(isA<DioException>()));

    final op = await queued('op-1');
    expect(op.lastError, isNull);
    expect(op.quarantinedAt, isNull);
    expect(await syncService.hasPendingOperations(), isTrue,
        reason: 'retried on the next push');
  });
}
