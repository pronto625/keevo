import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/modules/module_sync_queue.dart';
import 'package:keevo/core/sync/rest_sync_service.dart';
import 'package:keevo/features/catalog/data/datasource/remote_product_datasource.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/module/module_records.dart';
import 'package:keevo_core/module/module_sync.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/sync/account_read_only_exception.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqlite3/open.dart';

class _MockDio extends Mock implements Dio {}

class _MockRemoteProducts extends Mock implements RemoteProductDataSource {}

String _jwt(String tenantStatus) {
  String part(Map<String, Object> m) =>
      base64Url.encode(utf8.encode(jsonEncode(m))).replaceAll('=', '');
  return '${part({'alg': 'HS256', 'typ': 'JWT'})}.'
      '${part({'sub': 'u1', 'tenantId': 't1', 'tenantStatus': tenantStatus})}'
      '.sig';
}

/// Story 15.5b — the app's [ModuleSyncQueue] (the port module packages
/// queue their sync operations through), on a real in-memory database and
/// the real [RestSyncService].
void main() {
  setUpAll(() {
    if (!Platform.isLinux) return;
    open.overrideFor(OperatingSystem.linux, () {
      try {
        return DynamicLibrary.open('libsqlite3.so');
      } catch (_) {
        return DynamicLibrary.open('libsqlite3.so.0');
      }
    });
  });

  late AppDatabase db;

  Future<ProviderContainer> container(String tenantStatus) async {
    FlutterSecureStorage.setMockInitialValues({'jwt_token': _jwt(tenantStatus)});
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final service = RestSyncService(
      database: db,
      remoteProducts: _MockRemoteProducts(),
      dio: _MockDio(),
      secureStorage: const FlutterSecureStorage(),
      prefs: prefs,
    );
    final c = ProviderContainer(
      retry: noProviderRetry,
      overrides: [
        appDatabaseProvider.overrideWithValue(db),
        syncServiceProvider.overrideWithValue(service),
        moduleSyncQueueProvider.overrideWith(AppModuleSyncQueue.new),
      ],
    );
    addTearDown(c.dispose);
    return c;
  }

  setUp(() => db = AppDatabase.forTesting());
  tearDown(() => db.close());

  test('enqueue writes a sync_queue row with its entity id; '
      'unsyncedEntityIds / queuedOperations / dropQueued see it', () async {
    final queue = (await container('ACTIVE')).read(moduleSyncQueueProvider);

    await queue.enqueue(
      operation: 'CREATE_INVOICE',
      payload: {'id': 'inv-1'},
      entityId: 'inv-1',
    );
    await queue.enqueue(
      operation: 'ISSUE_INVOICE',
      payload: {'invoiceId': 'inv-1'},
      entityId: 'inv-1',
    );

    final row = (await db.select(db.syncQueue).get()).first;
    expect(row.operation, 'CREATE_INVOICE');
    expect(row.entityId, 'inv-1');
    expect(jsonDecode(row.payload), {'id': 'inv-1'});
    expect(await queue.unsyncedEntityIds(), contains('inv-1'));
    expect(await queue.queuedOperations('inv-1'),
        ['CREATE_INVOICE', 'ISSUE_INVOICE']);

    await queue.dropQueued('inv-1');
    expect(await db.select(db.syncQueue).get(), isEmpty);
    expect(await queue.unsyncedEntityIds(), isNot(contains('inv-1')));
  });

  test('read-only account: enqueue inside the module transaction throws and '
      'rolls the local record back too', () async {
    final queue = (await container('SUSPENDED')).read(moduleSyncQueueProvider);
    final store = ModuleRecordStore(db);

    await expectLater(
      db.transaction(() async {
        await store.put('invoicing', 'invoices', 'inv-1', {'id': 'inv-1'},
            pending: true);
        await queue.enqueue(
          operation: 'CREATE_INVOICE',
          payload: {'id': 'inv-1'},
          entityId: 'inv-1',
        );
      }),
      throwsA(isA<AccountReadOnlyException>()),
    );

    expect(await store.get('invoicing', 'invoices', 'inv-1'), isNull);
    expect(await db.select(db.syncQueue).get(), isEmpty);
  });
}
