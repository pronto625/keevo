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
import 'package:keevo_core/sync/account_read_only_exception.dart';
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

String _jwt(String tenantStatus) {
  String part(Map<String, Object> m) =>
      base64Url.encode(utf8.encode(jsonEncode(m))).replaceAll('=', '');
  return '${part({'alg': 'HS256', 'typ': 'JWT'})}.'
      '${part({'sub': 'u1', 'tenantId': 't1', 'tenantStatus': tenantStatus})}'
      '.sig';
}

/// A50 — read-only account (suspended, deletion pending): no new write is
/// queued, and the operations already queued are kept — never sent, never
/// counted as failed attempts, never quarantined — until the state lifts.
void main() {
  setUpAll(_overrideSqlite3ForLinuxTesting);

  late AppDatabase db;
  late MockDio dio;

  Future<RestSyncService> service(String tenantStatus) async {
    FlutterSecureStorage.setMockInitialValues({'jwt_token': _jwt(tenantStatus)});
    SharedPreferences.setMockInitialValues({});
    return RestSyncService(
      database: db,
      remoteProducts: MockRemoteProductDataSource(),
      dio: dio,
      secureStorage: const FlutterSecureStorage(),
      prefs: await SharedPreferences.getInstance(),
    );
  }

  Future<void> seedQueuedOperation() =>
      db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
            id: 'op-1',
            operation: 'CREATE_CLIENT',
            payload: jsonEncode({'id': 'c1', 'name': 'Awa'}),
            createdAt: DateTime(2026, 10, 1),
            entityId: const Value('c1'),
          ));

  setUp(() {
    db = AppDatabase.forTesting();
    dio = MockDio();
  });

  tearDown(() async => db.close());

  group('queueOperation', () {
    for (final (status, reason) in [
      ('SUSPENDED', AccountReadOnlyReason.suspended),
      ('DELETION_PENDING', AccountReadOnlyReason.deletionPending),
    ]) {
      test('refuses a write while the claim says $status', () async {
        final sync = await service(status);

        await expectLater(
          sync.queueOperation(
              operation: 'CREATE_CLIENT', payload: {'id': 'c1'}, entityId: 'c1'),
          throwsA(isA<AccountReadOnlyException>()
              .having((e) => e.reason, 'reason', reason)),
        );
        expect(await db.select(db.syncQueue).get(), isEmpty);
      });
    }

    test('the refusal rolls back the local write of the same transaction',
        () async {
      final sync = await service('DELETION_PENDING');

      await expectLater(
        db.transaction(() async {
          await db.customStatement(
              'INSERT INTO clients (id, name, phone, created_at, updated_at) '
              "VALUES ('c1', 'Awa', '600', 0, 0)");
          await sync.queueOperation(
              operation: 'CREATE_CLIENT', payload: {'id': 'c1'}, entityId: 'c1');
        }),
        throwsA(isA<AccountReadOnlyException>()),
      );
      expect(await db.select(db.clients).get(), isEmpty);
    });

    test('an active account queues as usual', () async {
      final sync = await service('ACTIVE');

      await sync.queueOperation(
          operation: 'CREATE_CLIENT', payload: {'id': 'c1'}, entityId: 'c1');

      expect(await db.select(db.syncQueue).get(), hasLength(1));
    });
  });

  group('push', () {
    test('a read-only claim keeps the queue untouched and sends nothing',
        () async {
      final sync = await service('SUSPENDED');
      await seedQueuedOperation();

      await expectLater(sync.push(), throwsA(isA<AccountReadOnlyException>()));

      verifyNever(() => dio.post<dynamic>(any(), data: any(named: 'data')));
      final op = (await db.select(db.syncQueue).get()).single;
      expect(op.retryCount, 0);
      expect(op.quarantinedAt, isNull);
      expect(op.synced, isFalse);
    });

    test('a 403 ACCOUNT_DELETION_PENDING keeps the operations queued, '
        'without counting an attempt', () async {
      final sync = await service('ACTIVE');
      await seedQueuedOperation();
      when(() => dio.post<dynamic>(any(), data: any(named: 'data')))
          .thenThrow(DioException(
        requestOptions: RequestOptions(path: '/api/v1/sync/push'),
        response: Response(
          requestOptions: RequestOptions(path: '/api/v1/sync/push'),
          statusCode: 403,
          data: {'domainCode': 'ACCOUNT_DELETION_PENDING'},
        ),
        type: DioExceptionType.badResponse,
      ));

      await expectLater(
        sync.push(),
        throwsA(isA<AccountReadOnlyException>().having((e) => e.reason,
            'reason', AccountReadOnlyReason.deletionPending)),
      );

      final op = (await db.select(db.syncQueue).get()).single;
      expect(op.retryCount, 0);
      expect(op.quarantinedAt, isNull);
    });

    test('the kept operations are sent once the account is writable again',
        () async {
      await seedQueuedOperation();
      final readOnly = await service('DELETION_PENDING');
      await expectLater(
          readOnly.push(), throwsA(isA<AccountReadOnlyException>()));

      // Deletion cancelled: the renewed token says ACTIVE.
      final active = await service('ACTIVE');
      when(() => dio.post<dynamic>(any(), data: any(named: 'data')))
          .thenAnswer((_) async => Response(
                requestOptions: RequestOptions(path: '/api/v1/sync/push'),
                statusCode: 200,
                data: {
                  'data': {
                    'results': [
                      {'operationId': 'op-1', 'status': 'APPLIED'},
                    ],
                  },
                },
              ));

      await active.push();

      expect(await db.select(db.syncQueue).get(), isEmpty);
    });
  });
}
