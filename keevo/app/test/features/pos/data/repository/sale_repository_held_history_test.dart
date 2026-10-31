import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart' hide isNull, isNotNull;
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/data/datasource/local_sale_datasource.dart';
import 'package:keevo/features/pos/data/datasource/remote_sale_datasource.dart';
import 'package:keevo/features/pos/data/repository/sale_repository_impl.dart';
import 'package:keevo/features/pos/domain/model/payment_mode_enum.dart';
import 'package:keevo/features/pos/domain/model/sale_model.dart';
import 'package:keevo/features/pos/domain/model/sales_history_filter.dart';
import 'package:keevo_core/storage/app_database.dart' hide Sale, SaleItem;
import 'package:keevo_core/sync/connectivity_service.dart';
import 'package:keevo_core/sync/sync_service.dart';
import 'package:keevo_core/sync/sync_trigger_dispatcher.dart';
import 'package:mocktail/mocktail.dart';
import 'package:sqlite3/open.dart';

class MockRemoteSaleDataSource extends Mock implements RemoteSaleDataSource {}

class MockConnectivityService extends Mock implements ConnectivityService {}

class MockSyncService extends Mock implements SyncService {}

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

/// Story 13.8b — « Mes Ventes » online: a sale whose CREATE_SALE is still
/// queued (held because its module is off, or pending) is not on the server;
/// it stays listed from the local database next to the server's sales.
void main() {
  setUpAll(_overrideSqlite3ForLinuxTesting);

  late AppDatabase db;
  late MockRemoteSaleDataSource remote;
  late MockConnectivityService connectivity;
  late SaleRepositoryImpl repo;
  final day = DateTime(2026, 10, 30);
  final filter = SalesHistoryFilter(
    storeId: 'store-1',
    employeeId: 'emp-1',
    from: day,
    to: DateTime(2026, 10, 30, 23, 59, 59),
    filterType: DateFilterType.today,
  );

  Sale serverSale(String id, DateTime at) => Sale(
        id: id,
        storeId: 'store-1',
        employeeId: 'emp-1',
        paymentMode: PaymentModeEnum.cash,
        totalAmount: 1000,
        items: const [],
        occurredAt: at,
        createdAt: at,
      );

  Future<void> localSale(String id, DateTime at,
      {String employeeId = 'emp-1'}) async {
    await db.into(db.sales).insert(SalesCompanion.insert(
          id: id,
          storeId: 'store-1',
          employeeId: employeeId,
          totalAmount: 500,
          paymentMode: 'CASH',
          status: const Value('COMPLETED'),
          occurredAt: Value(at),
          createdAt: at,
        ));
    await db.into(db.saleItems).insert(SaleItemsCompanion.insert(
          id: 'item-$id',
          saleId: id,
          productId: 'prod-1',
          productName: 'Savon',
          unitPrice: 500,
          quantity: 1,
          subtotal: 500,
          createdAt: at,
        ));
  }

  Future<void> queueCreateSale(String saleId,
      {String? lastError, DateTime? quarantinedAt, bool synced = false}) {
    return db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
          id: 'op-$saleId',
          operation: 'CREATE_SALE',
          payload: jsonEncode({'storeId': 'store-1'}),
          createdAt: day,
          entityId: Value(saleId),
          lastError: Value(lastError),
          quarantinedAt: Value(quarantinedAt),
          synced: Value(synced),
        ));
  }

  setUp(() {
    db = AppDatabase.forTesting();
    remote = MockRemoteSaleDataSource();
    connectivity = MockConnectivityService();
    when(() => connectivity.isOnline()).thenAnswer((_) async => true);
    repo = SaleRepositoryImpl(
      LocalSaleDataSource(db),
      remote,
      db,
      connectivity: connectivity,
      syncService: MockSyncService(),
      syncTriggerDispatcher: MockSyncTriggerDispatcher(),
    );
  });

  tearDown(() => db.close());

  test('a held sale missing from the server list stays listed, newest first',
      () async {
    final at = DateTime(2026, 10, 30, 11);
    await localSale('held-1', at);
    await queueCreateSale('held-1', lastError: 'MODULE_INACTIVE');
    when(() => remote.getSalesHistory(filter)).thenAnswer((_) async => [
          serverSale('srv-1', DateTime(2026, 10, 30, 12)),
          serverSale('srv-2', DateTime(2026, 10, 30, 9)),
        ]);

    final sales = await repo.getSalesHistory(filter);

    expect(sales.map((s) => s.id), ['srv-1', 'held-1', 'srv-2']);
    final held = sales[1];
    expect(held.totalAmount, 500);
    expect(held.items.single.productName, 'Savon');
  });

  test('an applied sale returned by the server is not listed twice', () async {
    final at = DateTime(2026, 10, 30, 11);
    await localSale('sale-1', at);
    await queueCreateSale('sale-1', lastError: 'MODULE_READ_ONLY');
    when(() => remote.getSalesHistory(filter))
        .thenAnswer((_) async => [serverSale('sale-1', at)]);

    final sales = await repo.getSalesHistory(filter);

    expect(sales.map((s) => s.id), ['sale-1']);
    expect(sales.single.totalAmount, 1000, reason: 'the server row wins');
  });

  test(
      'synced, quarantined, other employee or out-of-range local sales are '
      'not added', () async {
    await localSale('synced-1', DateTime(2026, 10, 30, 8));
    await queueCreateSale('synced-1', synced: true);
    await localSale('blocked-1', DateTime(2026, 10, 30, 8));
    await queueCreateSale('blocked-1',
        lastError: 'STORE_NOT_FOUND', quarantinedAt: day);
    await localSale('other-1', DateTime(2026, 10, 30, 8), employeeId: 'emp-2');
    await queueCreateSale('other-1', lastError: 'MODULE_INACTIVE');
    await localSale('old-1', DateTime(2026, 10, 20, 8));
    await queueCreateSale('old-1', lastError: 'MODULE_INACTIVE');
    when(() => remote.getSalesHistory(filter)).thenAnswer((_) async => []);

    expect(await repo.getSalesHistory(filter), isEmpty);
  });

  test('without held or pending sales the server list is returned as is',
      () async {
    final list = [serverSale('srv-1', DateTime(2026, 10, 30, 12))];
    when(() => remote.getSalesHistory(filter)).thenAnswer((_) async => list);

    expect(await repo.getSalesHistory(filter), same(list));
  });
}
