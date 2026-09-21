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

  Future<void> insertPendingOp({
    required String id,
    required String operation,
    String? entityId,
  }) {
    return db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
          id: id,
          operation: operation,
          payload: '{}',
          createdAt: DateTime.now(),
          entityId: Value(entityId),
        ));
  }

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
        id: 'op-1', operation: 'CREATE_PRODUCT', entityId: 'prod-1');
    stubPushResponse('op-1', 'REJECTED', reason: 'PRODUCT_NAME_ALREADY_EXISTS');

    final result = await syncService.push();

    expect(result, hasLength(1));
    expect(result.single['status'], 'REJECTED');
    expect(result.single['reason'], 'PRODUCT_NAME_ALREADY_EXISTS');
    expect(result.single['operation'], 'CREATE_PRODUCT');
    expect(result.single['entityId'], 'prod-1');

    final row =
        await (db.select(db.syncQueue)..where((t) => t.id.equals('op-1')))
            .getSingle();
    expect(row.lastError, 'PRODUCT_NAME_ALREADY_EXISTS');
    expect(row.retryCount, 1);
  });

  test('repeated REJECTED with the same reason is not re-surfaced', () async {
    await insertPendingOp(
        id: 'op-1', operation: 'CREATE_PRODUCT', entityId: 'prod-1');
    stubPushResponse('op-1', 'REJECTED', reason: 'PRODUCT_NAME_ALREADY_EXISTS');

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
        id: 'op-1', operation: 'CREATE_PRODUCT', entityId: 'prod-1');
    stubPushResponse('op-1', 'REJECTED', reason: 'PRODUCT_NAME_ALREADY_EXISTS');
    await syncService.push();

    stubPushResponse('op-1', 'REJECTED', reason: 'PLAN_LIMIT_EXCEEDED');
    final second = await syncService.push();

    expect(second, hasLength(1));
    expect(second.single['reason'], 'PLAN_LIMIT_EXCEEDED');
  });

  test('REJECTED operation stays in the queue (not deleted)', () async {
    await insertPendingOp(id: 'op-1', operation: 'CREATE_PRODUCT');
    stubPushResponse('op-1', 'REJECTED', reason: 'VALIDATION_ERROR');

    await syncService.push();

    final remaining = await db.select(db.syncQueue).get();
    expect(remaining, hasLength(1));
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
