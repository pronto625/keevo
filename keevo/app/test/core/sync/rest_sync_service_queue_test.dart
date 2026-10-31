import 'dart:async';
import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:drift/drift.dart' show OrderingTerm, Value;
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/rest_sync_service.dart';
import 'package:keevo/features/catalog/data/datasource/remote_product_datasource.dart';
import 'package:keevo_core/storage/app_database.dart';
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

/// Offline edits of the same entity must all reach the server in order: a
/// queue that keeps only the first one syncs a stale value, which the next
/// pull then writes back over the user's latest edit.
void main() {
  setUpAll(_overrideSqlite3ForLinuxTesting);

  late AppDatabase db;
  late MockDio mockDio;
  late RestSyncService syncService;

  setUp(() async {
    db = AppDatabase.forTesting();
    mockDio = MockDio();
    FlutterSecureStorage.setMockInitialValues({});
    SharedPreferences.setMockInitialValues({});
    syncService = RestSyncService(
      database: db,
      remoteProducts: MockRemoteProductDataSource(),
      dio: mockDio,
      secureStorage: const FlutterSecureStorage(),
      prefs: await SharedPreferences.getInstance(),
    );
  });

  tearDown(() async => db.close());

  Future<List<SyncQueueData>> queue() => (db.select(db.syncQueue)
        ..orderBy([(t) => OrderingTerm.asc(t.createdAt)]))
      .get();

  void stubPushApplying(bool Function(String opId) applied) {
    when(() => mockDio.post<dynamic>(any(), data: any(named: 'data')))
        .thenAnswer((inv) async {
      final body = inv.namedArguments[#data] as Map<String, dynamic>;
      final ops = (body['operations'] as List).cast<Map<String, dynamic>>();
      return Response(
        requestOptions: RequestOptions(path: '/api/v1/sync/push'),
        statusCode: 200,
        data: {
          'data': {
            'results': [
              for (final op in ops)
                {
                  'operationId': op['operationId'],
                  'status': applied(op['operationId'] as String)
                      ? 'APPLIED'
                      : 'REJECTED',
                  // A transient reason: the op stays queued (v1s-16-12 drops
                  // non-money ops on a terminal rejection).
                  'reason': applied(op['operationId'] as String)
                      ? null
                      : 'OPTIMISTIC_LOCK',
                  'serverEntityId': null,
                  'conflictData': null,
                }
            ],
          },
        },
      );
    });
  }

  test('successive offline price edits are all queued, in order', () async {
    for (final price in [600, 700]) {
      await syncService.queueOperation(
        operation: 'UPDATE_PRODUCT',
        payload: {'id': 'p1', 'price': price},
        entityId: 'p1',
      );
    }

    final ops = await queue();
    expect(ops.map((o) => jsonDecode(o.payload)['price']), [600, 700]);
  });

  test('15.5b — two offline edits of one invoice: both UPDATE_INVOICE '
      'queued; a second ISSUE_INVOICE is a duplicate', () async {
    for (final client in ['Awa', 'Awa Traoré']) {
      await syncService.queueOperation(
        operation: 'UPDATE_INVOICE',
        payload: {'invoiceId': 'inv-1', 'client': {'name': client}},
        entityId: 'inv-1',
      );
    }
    for (var i = 0; i < 2; i++) {
      await syncService.queueOperation(
          operation: 'ISSUE_INVOICE',
          payload: {'invoiceId': 'inv-1'},
          entityId: 'inv-1');
    }
    expect((await queue()).map((o) => o.operation),
        ['UPDATE_INVOICE', 'UPDATE_INVOICE', 'ISSUE_INVOICE']);
  });

  test('archive → unarchive → archive keeps all three operations', () async {
    for (final op in ['ARCHIVE_PRODUCT', 'UNARCHIVE_PRODUCT', 'ARCHIVE_PRODUCT']) {
      await syncService.queueOperation(
          operation: op, payload: {'id': 'p1'}, entityId: 'p1');
    }

    expect((await queue()).map((o) => o.operation),
        ['ARCHIVE_PRODUCT', 'UNARCHIVE_PRODUCT', 'ARCHIVE_PRODUCT']);
  });

  test('two stock entries on the same entity id are both kept', () async {
    for (var i = 0; i < 2; i++) {
      await syncService.queueOperation(
        operation: 'RECORD_STOCK_ENTRY',
        payload: {'productId': 'p1', 'storeId': 's1', 'quantity': 5},
        entityId: 'p1',
      );
    }

    expect(await queue(), hasLength(2));
  });

  test('a double tap on a one-shot operation is still deduplicated', () async {
    for (var i = 0; i < 2; i++) {
      await syncService.queueOperation(
        operation: 'VALIDATE_SALE',
        payload: {'saleId': 'sale-1', 'justification': 'ok'},
        entityId: 'sale-1',
      );
    }

    expect(await queue(), hasLength(1));
  });

  test('an APPLIED edit removes only that operation from the queue', () async {
    for (final price in [600, 700]) {
      await syncService.queueOperation(
        operation: 'UPDATE_PRODUCT',
        payload: {'id': 'p1', 'price': price},
        entityId: 'p1',
      );
    }
    final first = (await queue()).first.id;
    stubPushApplying((id) => id == first);

    await syncService.push();

    final left = await queue();
    expect(left, hasLength(1));
    expect(jsonDecode(left.single.payload)['price'], 700);
  });

  test('an APPLIED one-shot operation still clears its legacy duplicates',
      () async {
    for (final id in ['op-a', 'op-b']) {
      await db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
            id: id,
            operation: 'CREATE_SALE',
            payload: '{}',
            createdAt: DateTime.now(),
            entityId: const Value('sale-1'),
          ));
    }
    stubPushApplying((id) => id == 'op-a');

    await syncService.push();

    expect(await queue(), isEmpty);
  });

  test('concurrent push() calls send the queue only once', () async {
    await syncService.queueOperation(
      operation: 'RECORD_STOCK_ENTRY',
      payload: {'productId': 'p1', 'storeId': 's1', 'quantity': 5},
      entityId: 'm1',
    );
    final opId = (await queue()).single.id;
    final release = Completer<void>();
    var posts = 0;
    when(() => mockDio.post<dynamic>(any(), data: any(named: 'data')))
        .thenAnswer((_) async {
      posts++;
      await release.future;
      return Response(
        requestOptions: RequestOptions(path: '/api/v1/sync/push'),
        statusCode: 200,
        data: {
          'data': {
            'results': [
              {'operationId': opId, 'status': 'APPLIED', 'conflictData': null}
            ],
          },
        },
      );
    });

    final first = syncService.push();
    final second = syncService.push();
    await pumpEventQueue();
    release.complete();
    await Future.wait([first, second]);

    expect(posts, 1);
    expect(await queue(), isEmpty);
  });

  group('CREATE_DAY_CLOSURE results', () {
    Future<void> seedClosure(String id, {required bool synced}) =>
        db.customStatement(
          'INSERT INTO day_closures (id, store_id, actor_id, closed_at, '
          'total_sales, total_revenue, cash_amount, momo_amount, '
          'pending_sales_count, pending_sales_total, is_automatic, synced, '
          'created_at) VALUES (?, ?, ?, ?, 1, 100, 100, 0, 0, 0, 0, ?, ?)',
          [
            id,
            's1',
            'u1',
            '2026-03-21T20:00:00.000',
            synced ? 1 : 0,
            '2026-03-21T20:00:00.000',
          ],
        );

    Future<void> queueClosure(String id) => db.into(db.syncQueue).insert(
          SyncQueueCompanion.insert(
            id: 'op-$id',
            operation: 'CREATE_DAY_CLOSURE',
            payload: '{}',
            createdAt: DateTime.now(),
            entityId: Value(id),
          ),
        );

    void stubStatus(String status) {
      when(() => mockDio.post<dynamic>(any(), data: any(named: 'data')))
          .thenAnswer((inv) async {
        final body = inv.namedArguments[#data] as Map<String, dynamic>;
        final ops = (body['operations'] as List).cast<Map<String, dynamic>>();
        return Response(
          requestOptions: RequestOptions(path: '/api/v1/sync/push'),
          statusCode: 200,
          data: {
            'data': {
              'results': [
                for (final op in ops)
                  {
                    'operationId': op['operationId'],
                    'status': status,
                    'conflictData': null,
                  }
              ],
            },
          },
        );
      });
    }

    Future<List<DayClosure>> closures() => db.select(db.dayClosures).get();

    test('APPLIED marks the local closure as synced', () async {
      await seedClosure('dc-1', synced: false);
      await queueClosure('dc-1');
      stubStatus('APPLIED');

      await syncService.push();

      expect((await closures()).single.synced, isTrue);
    });

    // The day was already closed on the server (e.g. by the 20:00
    // auto-closure): its closure arrives through pull, ours must go.
    test('DUPLICATE removes the local unsynced closure', () async {
      await seedClosure('dc-1', synced: false);
      await seedClosure('dc-other', synced: true);
      await queueClosure('dc-1');
      stubStatus('DUPLICATE');

      await syncService.push();

      expect((await closures()).map((c) => c.id), ['dc-other']);
    });
  });
}
