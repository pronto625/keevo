import 'dart:ffi';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:sqlite3/open.dart';

import 'package:keevo/core/storage/app_database.dart';

import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/core/sync/sync_trigger_dispatcher.dart';
import 'package:keevo/features/inventory/data/datasource/local_inventory_count_datasource.dart';
import 'package:keevo/features/inventory/data/datasource/local_inventory_session_datasource.dart';
import 'package:keevo/features/inventory/data/datasource/remote_inventory_count_datasource.dart';
import 'package:keevo/features/inventory/data/repository/inventory_count_repository_impl.dart';
import 'package:keevo/features/inventory/domain/model/inventory_count_model.dart';

class _MockLocalCount extends Mock implements LocalInventoryCountDataSource {
  // Runs the transactional block directly (no database behind the mock).
  @override
  Future<T> transaction<T>(Future<T> Function() action) => action();
}

class _MockLocalSession extends Mock
    implements LocalInventorySessionDataSource {}

class _MockRemote extends Mock implements RemoteInventoryCountDataSource {}

class _MockConnectivity extends Mock implements ConnectivityService {}

class _MockSyncService extends Mock implements SyncService {}

class _MockDispatcher extends Mock implements SyncTriggerDispatcher {}

void main() {
  setUpAll(() {
    registerFallbackValue(InventoryCountModel(
      id: 'x',
      sessionId: 's',
      productId: 'p',
      productName: 'n',
      theoretical: 0,
      updatedAt: DateTime(2026),
    ));
  });

  test('saveCount queued after a failed POST triggers a background push',
      () async {
    final local = _MockLocalCount();
    final remote = _MockRemote();
    final connectivity = _MockConnectivity();
    final sync = _MockSyncService();
    final dispatcher = _MockDispatcher();

    when(() => connectivity.isOnline()).thenAnswer((_) async => true);
    when(() => remote.saveCount(
          sessionId: any(named: 'sessionId'),
          productId: any(named: 'productId'),
          variantId: any(named: 'variantId'),
          productName: any(named: 'productName'),
          variantLabel: any(named: 'variantLabel'),
          theoretical: any(named: 'theoretical'),
          physical: any(named: 'physical'),
        )).thenThrow(DioException(requestOptions: RequestOptions()));
    when(() => local.upsert(any())).thenAnswer((_) async {});
    when(() => sync.queueOperation(
          operation: any(named: 'operation'),
          payload: any(named: 'payload'),
          entityId: any(named: 'entityId'),
        )).thenAnswer((_) async {});

    final repo = InventoryCountRepositoryImpl(
      localCount: local,
      localSession: _MockLocalSession(),
      remote: remote,
      connectivity: connectivity,
      syncService: sync,
      syncTriggerDispatcher: dispatcher,
    );

    await repo.saveCount(
      sessionId: 'sess-1',
      productId: 'prod-1',
      productName: 'Riz',
      theoretical: 10,
      physical: 7,
    );

    verify(() => sync.queueOperation(
          operation: 'SAVE_INVENTORY_COUNT',
          payload: any(named: 'payload'),
          entityId: any(named: 'entityId'),
        )).called(1);
    verify(() => dispatcher.triggerPushIfIdle()).called(1);
  });

  test('an offline count whose queueing fails is not kept locally', () async {
    open.overrideFor(OperatingSystem.linux, () {
      try {
        return DynamicLibrary.open('libsqlite3.so');
      } catch (_) {
        return DynamicLibrary.open('libsqlite3.so.0');
      }
    });
    final db = AppDatabase.forTesting();
    addTearDown(db.close);
    final connectivity = _MockConnectivity();
    final sync = _MockSyncService();
    when(() => connectivity.isOnline()).thenAnswer((_) async => false);
    when(() => sync.queueOperation(
          operation: any(named: 'operation'),
          payload: any(named: 'payload'),
          entityId: any(named: 'entityId'),
        )).thenThrow(Exception('app killed / disk full'));

    final repo = InventoryCountRepositoryImpl(
      localCount: LocalInventoryCountDataSource(db),
      localSession: _MockLocalSession(),
      remote: _MockRemote(),
      connectivity: connectivity,
      syncService: sync,
    );

    await expectLater(
      repo.saveCount(
        sessionId: 'sess-1',
        productId: 'prod-1',
        productName: 'Riz',
        theoretical: 10,
        physical: 7,
      ),
      throwsException,
    );

    // A count saved but never queued would be silently ignored at validation.
    expect(await db.select(db.inventoryCounts).get(), isEmpty);
  });
}
