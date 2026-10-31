import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:drift/drift.dart' show Value, Variable;
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/rest_sync_service.dart';
import 'package:keevo/features/catalog/data/datasource/remote_product_datasource.dart';
import 'package:keevo_core/di/core_providers.dart' show kUserRoleKey;
import 'package:keevo_core/storage/app_constants.dart';
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

/// Last-write-wins on the device: an offline edit the server found older than
/// its current version is acknowledged (APPLIED + LAST_WRITE_SUPERSEDED) but
/// not applied. The device must end up showing the server's version.
///
/// Device scenario: offline price edit 3600 at 02:44, owner sets 3700 on the
/// server at 02:48, the phone pulled at 02:49 while its edit was still queued
/// (the product was skipped and the cursor moved past 02:48), then pushes.
void main() {
  setUpAll(_overrideSqlite3ForLinuxTesting);

  const productId = 'prod-1';
  final editedAt = DateTime.utc(2026, 10, 14, 2, 44).toLocal();
  final serverEditAt = DateTime.utc(2026, 10, 14, 2, 48);
  final lastPull = DateTime.utc(2026, 10, 14, 2, 49);

  late AppDatabase db;
  late MockDio mockDio;
  late FlutterSecureStorage secureStorage;
  late RestSyncService syncService;

  setUp(() async {
    db = AppDatabase.forTesting();
    mockDio = MockDio();
    secureStorage = const FlutterSecureStorage();
    FlutterSecureStorage.setMockInitialValues({
      kLastSyncTimestampKey: lastPull.millisecondsSinceEpoch.toString(),
    });
    SharedPreferences.setMockInitialValues(
        {kPullTzFixDoneKey: true, kUserRoleKey: 'OWNER'});
    syncService = RestSyncService(
      database: db,
      remoteProducts: MockRemoteProductDataSource(),
      dio: mockDio,
      secureStorage: secureStorage,
      prefs: await SharedPreferences.getInstance(),
    );

    // Local row already carries the offline edit, and its queued operation.
    await db.customStatement(
      'INSERT INTO products (id, name, description, sku, category_id, price, '
      'buy_price, transport_cost, stock_quantity, photo_url, archived, status, '
      'created_at, updated_at) '
      "VALUES (?, 'Savon', NULL, 'SAV-1', NULL, 3600, 0, 0, 0, NULL, 0, "
      "'ACTIVE', ?, ?)",
      [productId, '2026-10-01T10:00:00.000Z', '2026-10-14T02:44:00.000Z'],
    );
    await db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
          id: 'op-1',
          operation: 'UPDATE_PRODUCT',
          payload: jsonEncode({'productId': productId, 'price': 3600}),
          createdAt: editedAt,
          entityId: const Value(productId),
        ));
  });

  tearDown(() async {
    await db.close();
  });

  void stubPush(Map<String, dynamic>? conflictData) {
    when(() => mockDio.post<dynamic>(any(), data: any(named: 'data')))
        .thenAnswer((_) async => Response(
              requestOptions: RequestOptions(path: '/api/v1/sync/push'),
              statusCode: 200,
              data: {
                'data': {
                  'results': [
                    {
                      'operationId': 'op-1',
                      'status': 'APPLIED',
                      'serverEntityId': productId,
                      'reason': conflictData == null ? null : 'LWW_SUPERSEDED',
                      'conflictData': conflictData,
                    }
                  ],
                },
              },
            ));
  }

  /// The server sends the product (updated_at 02:48) only to a pull whose
  /// `since` is not after it — like the real delta pull.
  void stubPull() {
    when(() => mockDio.get<Map<String, dynamic>>(
          any(),
          queryParameters: any(named: 'queryParameters'),
          options: any(named: 'options'),
        )).thenAnswer((invocation) async {
      final params = invocation.namedArguments[#queryParameters]
          as Map<String, dynamic>?;
      final since = params?['since'] as String?;
      final includeProduct =
          since == null || !DateTime.parse(since).isAfter(serverEditAt);
      return Response(
        requestOptions: RequestOptions(path: '/api/v1/sync/pull'),
        statusCode: 200,
        data: {
          'data': {
            'serverTimestamp': '2026-10-14T02:51:00.000Z',
            'entities': {
              'products': [
                if (includeProduct)
                  {
                    'id': productId,
                    'name': 'Savon',
                    'description': null,
                    'sku': 'SAV-1',
                    'categoryId': null,
                    'price': 3700,
                    'buyPrice': 0,
                    'transportCost': 0,
                    'stockQuantity': 0,
                    'photoUrl': null,
                    'archived': false,
                    'status': 'ACTIVE',
                    'createdAt': '2026-10-01T10:00:00.000Z',
                    'updatedAt': serverEditAt.toIso8601String(),
                  }
              ],
            },
            'counts': <String, dynamic>{},
          }
        },
      );
    });
  }

  Future<int?> localPrice() async => (await db
          .customSelect('SELECT price FROM products WHERE id = ?',
              variables: [Variable.withString(productId)])
          .getSingle())
      .data['price'] as int?;

  test('superseded edit: acknowledged, then the pull restores the kept version',
      () async {
    stubPush({
      'conflictType': 'LAST_WRITE_SUPERSEDED',
      'entityType': 'PRODUCT',
      'entityId': productId,
      'incomingTimestamp': editedAt.toUtc().toIso8601String(),
      'keptTimestamp': serverEditAt.toIso8601String(),
    });
    stubPull();

    final results = await syncService.push();

    expect(results, hasLength(1)); // reported (conflict log), not retried
    expect(await db.select(db.syncQueue).get(), isEmpty);
    final cursor = await secureStorage.read(key: kLastSyncTimestampKey);
    expect(
        int.parse(cursor!),
        editedAt
            .subtract(RestSyncService.supersededPullMargin)
            .millisecondsSinceEpoch);

    await syncService.pull();

    expect(await localPrice(), 3700);
  });

  test('plain APPLIED result leaves the pull cursor alone', () async {
    stubPush(null);

    await syncService.push();

    expect(await db.select(db.syncQueue).get(), isEmpty);
    expect(await secureStorage.read(key: kLastSyncTimestampKey),
        lastPull.millisecondsSinceEpoch.toString());
  });

  test('cursor already older than the edit is never moved forward', () async {
    final older = editedAt.subtract(const Duration(hours: 2));
    await secureStorage.write(
        key: kLastSyncTimestampKey,
        value: older.millisecondsSinceEpoch.toString());
    stubPush({'conflictType': 'LAST_WRITE_SUPERSEDED', 'entityType': 'PRODUCT'});

    await syncService.push();

    expect(await secureStorage.read(key: kLastSyncTimestampKey),
        older.millisecondsSinceEpoch.toString());
  });
}
