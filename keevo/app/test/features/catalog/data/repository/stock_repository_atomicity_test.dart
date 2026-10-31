import 'dart:ffi';

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/catalog/data/datasource/local_stock_datasource.dart';
import 'package:keevo/features/catalog/data/datasource/remote_stock_datasource.dart';
import 'package:keevo/features/catalog/data/repository/stock_repository_impl.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/sync/sync_service.dart';
import 'package:keevo_core/sync/sync_trigger_dispatcher.dart';
import 'package:mocktail/mocktail.dart';
import 'package:sqlite3/open.dart';

class _MockRemote extends Mock implements RemoteStockDataSource {}

class _MockSyncService extends Mock implements SyncService {}

class _MockDispatcher extends Mock implements SyncTriggerDispatcher {}

void main() {
  setUpAll(() {
    open.overrideFor(OperatingSystem.linux, () {
      try {
        return DynamicLibrary.open('libsqlite3.so');
      } catch (_) {
        return DynamicLibrary.open('libsqlite3.so.0');
      }
    });
  });

  late AppDatabase db;
  late StockRepositoryImpl repo;

  setUp(() async {
    db = AppDatabase.forTesting();
    await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
          id: 'sl-1',
          productId: 'prod-1',
          storeId: 'store-A',
          quantity: 20,
          updatedAt: DateTime(2026),
        ));
    final sync = _MockSyncService();
    when(() => sync.queueOperation(
          operation: any(named: 'operation'),
          payload: any(named: 'payload'),
          entityId: any(named: 'entityId'),
        )).thenThrow(Exception('app killed / disk full'));
    repo = StockRepositoryImpl(
      local: LocalStockDataSource(db),
      remote: _MockRemote(),
      syncService: sync,
      syncTriggerDispatcher: _MockDispatcher(),
    );
  });
  tearDown(() => db.close());

  Future<void> expectUntouched() async {
    final sl = await (db.select(db.stockLevels)
          ..where((t) => t.id.equals('sl-1')))
        .getSingle();
    expect(sl.quantity, 20);
    expect(await db.select(db.stockMovements).get(), isEmpty);
  }

  test('a stock entry whose queueing fails leaves no local trace', () async {
    await expectLater(
      repo.recordEntry(
          productId: 'prod-1', storeId: 'store-A', quantity: 5),
      throwsException,
    );
    await expectUntouched();
  });

  test('a stock adjustment whose queueing fails leaves no local trace',
      () async {
    await expectLater(
      repo.adjustStock(
          productId: 'prod-1',
          storeId: 'store-A',
          newQuantity: 3,
          notes: 'casse'),
      throwsException,
    );
    await expectUntouched();
  });
}
