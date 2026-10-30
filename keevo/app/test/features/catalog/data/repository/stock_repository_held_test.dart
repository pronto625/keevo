import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:drift/drift.dart' hide isNull, isNotNull;
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/core/sync/rest_sync_service.dart';
import 'package:keevo/core/sync/sync_trigger_dispatcher.dart';
import 'package:keevo/features/catalog/data/datasource/local_stock_datasource.dart';
import 'package:keevo/features/catalog/data/datasource/remote_product_datasource.dart';
import 'package:keevo/features/catalog/data/datasource/remote_stock_datasource.dart';
import 'package:keevo/features/catalog/data/repository/stock_repository_impl.dart';
import 'package:keevo/features/catalog/domain/model/cross_store_availability_model.dart';
import 'package:keevo/features/catalog/domain/model/stock_level_model.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqlite3/open.dart';

class MockDio extends Mock implements Dio {}

class MockRemoteProductDataSource extends Mock
    implements RemoteProductDataSource {}

class MockRemoteStockDataSource extends Mock implements RemoteStockDataSource {}

class MockSyncTriggerDispatcher extends Mock implements SyncTriggerDispatcher {}

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

/// Story 13.8b — server stock reads keep the local quantity only for the
/// (product, store) rows a pending or held operation changed; a held
/// operation on an unrelated entity (a client edit) freezes nothing.
/// Real `RestSyncService` over Drift, server mocked.
void main() {
  setUpAll(_overrideSqlite3ForLinuxTesting);

  late AppDatabase db;
  late MockRemoteStockDataSource remote;
  late StockRepositoryImpl repo;
  final at = DateTime(2026, 10, 30, 10);

  setUp(() async {
    db = AppDatabase.forTesting();
    remote = MockRemoteStockDataSource();
    FlutterSecureStorage.setMockInitialValues({});
    SharedPreferences.setMockInitialValues({});
    final sync = RestSyncService(
      database: db,
      remoteProducts: MockRemoteProductDataSource(),
      dio: MockDio(),
      secureStorage: const FlutterSecureStorage(),
      prefs: await SharedPreferences.getInstance(),
    );
    repo = StockRepositoryImpl(
      local: LocalStockDataSource(db),
      remote: remote,
      syncService: sync,
      syncTriggerDispatcher: MockSyncTriggerDispatcher(),
    );
    // Local stock 18 in s1 and 5 in s2 (server: 19 and 7).
    for (final (id, store, qty) in [('sl-1', 's1', 18), ('sl-2', 's2', 5)]) {
      await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
          id: id, productId: 'p1', storeId: store, quantity: qty, updatedAt: at));
    }
    when(() => remote.getLevels('p1')).thenAnswer((_) async => [
          StockLevelModel(
              id: 'srv-1', productId: 'p1', storeId: 's1', quantity: 19,
              updatedAt: at),
          StockLevelModel(
              id: 'srv-2', productId: 'p1', storeId: 's2', quantity: 7,
              updatedAt: at),
        ]);
  });

  tearDown(() => db.close());

  Future<void> holdSaleInS1() => db.into(db.syncQueue).insert(
      SyncQueueCompanion.insert(
        id: 'op-sale',
        operation: 'CREATE_SALE',
        payload: jsonEncode({
          'storeId': 's1',
          'items': [
            {'itemId': 'i1', 'productId': 'p1', 'quantity': 1}
          ],
        }),
        createdAt: at,
        entityId: const Value('sale-1'),
        lastError: const Value('MODULE_INACTIVE'),
      ));

  Future<void> holdClientEdit() => db.into(db.syncQueue).insert(
      SyncQueueCompanion.insert(
        id: 'op-client',
        operation: 'UPDATE_CLIENT',
        payload: jsonEncode({'clientId': 'c1', 'name': 'Awa'}),
        createdAt: at,
        entityId: const Value('c1'),
        lastError: const Value('MODULE_INACTIVE'),
      ));

  Future<Map<String, int>> localLevels() async => {
        for (final l in await db.select(db.stockLevels).get())
          l.storeId: l.quantity,
      };

  test('getLevels: held sale keeps s1 at 18, s2 refreshed to 7', () async {
    await holdSaleInS1();

    final levels = await repo.getLevels('p1');

    expect({for (final l in levels) l.storeId: l.quantity}, {'s1': 18, 's2': 7});
    expect(await localLevels(), {'s1': 18, 's2': 7});
  });

  test('getLevels: a held client edit freezes no stock (server values)',
      () async {
    await holdClientEdit();

    await repo.getLevels('p1');

    expect(await localLevels(), {'s1': 19, 's2': 7});
  });

  test('getLevelByStore: protected row local, unrelated row from the server',
      () async {
    await holdSaleInS1();

    expect((await repo.getLevelByStore('p1', 's1'))!.quantity, 18);
    expect((await repo.getLevelByStore('p1', 's2'))!.quantity, 7);
  });

  test('getLevelByStore: held client edit only → server value', () async {
    await holdClientEdit();

    expect((await repo.getLevelByStore('p1', 's1'))!.quantity, 19);
  });

  test(
      'getCrossStoreAvailability: remote figures shown, protected row not '
      'written locally', () async {
    await holdSaleInS1();
    when(() => remote.fetchCrossStoreAvailability('p1')).thenAnswer((_) async =>
        CrossStoreAvailabilityModel(
          productId: 'p1',
          productName: 'Savon',
          refreshedAt: at,
          entries: const [
            CrossStoreAvailabilityEntry(
                storeId: 's1', storeName: 'Akwa', quantity: 19),
            CrossStoreAvailabilityEntry(
                storeId: 's2', storeName: 'Bonaberi', quantity: 7),
          ],
        ));

    final model = await repo.getCrossStoreAvailability('p1');

    expect(model.entries.map((e) => e.quantity), [19, 7]);
    expect(await localLevels(), {'s1': 18, 's2': 7});
  });
}
