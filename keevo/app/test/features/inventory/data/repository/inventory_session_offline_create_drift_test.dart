import 'dart:ffi';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/rest_sync_service.dart';
import 'package:keevo/features/catalog/data/datasource/remote_product_datasource.dart';
import 'package:keevo/features/inventory/data/datasource/local_inventory_session_datasource.dart';
import 'package:keevo/features/inventory/data/datasource/remote_inventory_session_datasource.dart';
import 'package:keevo/features/inventory/data/repository/inventory_session_repository_impl.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/sync/connectivity_service.dart';
import 'package:keevo_core/sync/sync_service.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqlite3/open.dart';

class _MockRemote extends Mock implements RemoteInventorySessionDataSource {}

class _MockConnectivity extends Mock implements ConnectivityService {}

class _MockSyncService extends Mock implements SyncService {}

class _MockDio extends Mock implements Dio {}

class _MockRemoteProducts extends Mock implements RemoteProductDataSource {}

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

/// v1s-16-12 follow-up: the offline session creation and its queue entry
/// commit or roll back together — checked on a real in-memory Drift
/// database (the mock-based test only checks the calls).
void main() {
  setUpAll(_overrideSqlite3ForLinuxTesting);

  late AppDatabase db;
  late RestSyncService realSync;
  late _MockConnectivity offline;

  setUp(() async {
    db = AppDatabase.forTesting();
    FlutterSecureStorage.setMockInitialValues({});
    SharedPreferences.setMockInitialValues({});
    realSync = RestSyncService(
      database: db,
      remoteProducts: _MockRemoteProducts(),
      dio: _MockDio(),
      secureStorage: const FlutterSecureStorage(),
      prefs: await SharedPreferences.getInstance(),
    );
    offline = _MockConnectivity();
    when(() => offline.isOnline()).thenAnswer((_) async => false);
  });

  tearDown(() => db.close());

  InventorySessionRepositoryImpl repo(SyncService sync) =>
      InventorySessionRepositoryImpl(
        local: LocalInventorySessionDataSource(db),
        remote: _MockRemote(),
        connectivity: offline,
        syncService: sync,
      );

  test('offline create stores the session and its queue entry', () async {
    final session =
        await repo(realSync).create(storeId: 'store-1', scope: 'FULL');

    final rows = await db.select(db.inventorySessions).get();
    expect(rows.map((r) => r.id), [session.id]);
    final queued = await db.select(db.syncQueue).get();
    expect(queued.map((q) => (q.operation, q.entityId)),
        [('CREATE_INVENTORY_SESSION', session.id)]);
  });

  test('a failing queue write leaves no session row behind', () async {
    final failing = _MockSyncService();
    when(() => failing.queueOperation(
          operation: any(named: 'operation'),
          payload: any(named: 'payload'),
          entityId: any(named: 'entityId'),
        )).thenAnswer((inv) async {
      // The queue row is written, then the write path fails.
      await realSync.queueOperation(
        operation: inv.namedArguments[#operation] as String,
        payload: inv.namedArguments[#payload] as Map<String, dynamic>,
        entityId: inv.namedArguments[#entityId] as String?,
      );
      throw StateError('disk full');
    });

    await expectLater(
      repo(failing).create(storeId: 'store-1', scope: 'FULL'),
      throwsA(isA<StateError>()),
    );

    expect(await db.select(db.inventorySessions).get(), isEmpty);
    expect(await db.select(db.syncQueue).get(), isEmpty);
  });
}
