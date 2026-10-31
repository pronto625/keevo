import 'dart:ffi';

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/inventory/data/datasource/local_stock_transfer_datasource.dart';
import 'package:keevo/features/inventory/data/datasource/remote_stock_transfer_datasource.dart';
import 'package:keevo/features/inventory/data/repository/stock_transfer_repository_impl.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/sync/connectivity_service.dart';
import 'package:keevo_core/sync/sync_service.dart';
import 'package:keevo_core/sync/sync_trigger_dispatcher.dart';
import 'package:mocktail/mocktail.dart';
import 'package:sqlite3/open.dart';

class _MockRemote extends Mock implements RemoteStockTransferDataSource {}

class _MockConnectivity extends Mock implements ConnectivityService {}

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

  test('a transfer whose queueing fails leaves the source stock untouched',
      () async {
    final db = AppDatabase.forTesting();
    addTearDown(db.close);
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

    final repo = StockTransferRepositoryImpl(
      local: LocalStockTransferDataSource(db),
      remote: _MockRemote(),
      connectivity: _MockConnectivity(),
      syncService: sync,
      syncTriggerDispatcher: _MockDispatcher(),
    );

    await expectLater(
      repo.executeTransfer(
        sourceStoreId: 'store-A',
        destinationStoreId: 'store-B',
        productId: 'prod-1',
        quantity: 5,
      ),
      throwsException,
    );

    final sl = await (db.select(db.stockLevels)
          ..where((t) => t.id.equals('sl-1')))
        .getSingle();
    // Without the push queued, decrementing would lose 5 units for good.
    expect(sl.quantity, 20);
  });
}
