import 'dart:ffi';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/catalog/data/datasource/local_stock_datasource.dart';
import 'package:keevo/features/catalog/data/datasource/remote_stock_datasource.dart';
import 'package:keevo/features/catalog/data/repository/stock_repository_impl.dart';
import 'package:keevo/features/inventory/data/datasource/local_stock_transfer_datasource.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/sync/sync_service.dart';
import 'package:keevo_core/sync/sync_trigger_dispatcher.dart';
import 'package:mocktail/mocktail.dart';
import 'package:sqlite3/open.dart';

class _MockRemote extends Mock implements RemoteStockDataSource {}

class _MockSyncService extends Mock implements SyncService {}

class _MockDispatcher extends Mock implements SyncTriggerDispatcher {}

/// Regression: a product never stocked in a store (no stock_levels row) gets
/// its row from a stock entry, an adjustment or a transfer receipt.
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
  late StockRepositoryImpl repo;

  setUp(() {
    db = AppDatabase.forTesting();
    final sync = _MockSyncService();
    when(() => sync.queueOperation(
          operation: any(named: 'operation'),
          payload: any(named: 'payload'),
          entityId: any(named: 'entityId'),
        )).thenAnswer((_) async {});
    repo = StockRepositoryImpl(
      local: LocalStockDataSource(db),
      remote: _MockRemote(),
      syncService: sync,
      syncTriggerDispatcher: _MockDispatcher(),
    );
  });
  tearDown(() => db.close());

  Future<List<StockLevel>> levels(String storeId) => (db.select(db.stockLevels)
        ..where((l) => l.productId.equals('prod-1'))
        ..where((l) => l.storeId.equals(storeId)))
      .get();

  test('stock entry on a never-stocked product creates the row (0 → 5)',
      () async {
    final movement = await repo.recordEntry(
        productId: 'prod-1', storeId: 'store-A', quantity: 5);

    expect((await levels('store-A')).single.quantity, 5);
    expect(movement.quantityBefore, 0);
    expect(movement.quantityAfter, 5);
  });

  test('adjustment on a never-stocked product creates the row (0 → 3)',
      () async {
    final movement = await repo.adjustStock(
        productId: 'prod-1',
        storeId: 'store-A',
        newQuantity: 3,
        notes: 'Inventaire');

    expect((await levels('store-A')).single.quantity, 3);
    expect(movement.quantityBefore, 0);
    expect(movement.quantityDelta, 3);
  });

  test('transfer receipt on a never-stocked destination creates the row',
      () async {
    await LocalStockTransferDataSource(db).applyDestinationIncrement(
      productId: 'prod-1-0000-0000',
      destinationStoreId: 'store-B-0000-0000',
      quantity: 4,
    );

    final rows = await (db.select(db.stockLevels)
          ..where((l) => l.productId.equals('prod-1-0000-0000')))
        .get();
    expect(rows.single.storeId, 'store-B-0000-0000');
    expect(rows.single.quantity, 4);
  });
}
