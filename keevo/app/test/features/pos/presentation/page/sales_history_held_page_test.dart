import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart' hide isNull, isNotNull;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/storage/app_database.dart' hide Sale, SaleItem;
import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/core/sync/sync_trigger_dispatcher.dart';
import 'package:keevo/features/pos/data/datasource/local_sale_datasource.dart';
import 'package:keevo/features/pos/data/datasource/remote_sale_datasource.dart';
import 'package:keevo/features/pos/data/repository/sale_repository_impl.dart';
import 'package:keevo/features/pos/domain/model/payment_mode_enum.dart';
import 'package:keevo/features/pos/domain/model/sale_model.dart';
import 'package:keevo/features/pos/domain/model/sales_history_filter.dart';
import 'package:keevo/features/pos/presentation/page/sales_history_page.dart';
import 'package:keevo/features/pos/presentation/provider/pos_providers.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqlite3/open.dart';

class MockRemoteSaleDataSource extends Mock implements RemoteSaleDataSource {}

class MockConnectivityService extends Mock implements ConnectivityService {}

class MockSyncService extends Mock implements SyncService {}

class MockSyncTriggerDispatcher extends Mock implements SyncTriggerDispatcher {}

class _FakeFilter extends Fake implements SalesHistoryFilter {}

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

/// Story 13.8b — « Mes Ventes » online, end to end through the page: the real
/// repository merges the server list with the local sales whose CREATE_SALE
/// is still queued. A held sale gets the « En attente — module non activé »
/// badge; a normal pending (not yet pushed) sale is listed without badge
/// (accepted interpretation, spec Change Log); no id is ever displayed.
void main() {
  setUpAll(() async {
    _overrideSqlite3ForLinuxTesting();
    GoogleFonts.config.allowRuntimeFetching = false;
    await initializeDateFormatting('fr_FR');
    registerFallbackValue(_FakeFilter());
  });

  late AppDatabase db;
  late SharedPreferences prefs;
  late MockRemoteSaleDataSource remote;

  const heldId = '3f2b9c1e-0000-4000-8000-0000000000a1';
  const pendingId = '3f2b9c1e-0000-4000-8000-0000000000b2';

  Future<void> localSale(String id, DateTime at, int amount,
      {String? lastError}) async {
    await db.into(db.sales).insert(SalesCompanion.insert(
          id: id,
          storeId: 'store-1',
          employeeId: 'emp-1',
          totalAmount: amount,
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
          unitPrice: amount,
          quantity: 1,
          subtotal: amount,
          createdAt: at,
        ));
    await db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
          id: 'op-$id',
          operation: 'CREATE_SALE',
          payload: jsonEncode({'storeId': 'store-1'}),
          createdAt: at,
          entityId: Value(id),
          lastError: Value(lastError),
        ));
  }

  setUp(() async {
    db = AppDatabase.forTesting();
    SharedPreferences.setMockInitialValues({'active_store_id': 'store-1'});
    prefs = await SharedPreferences.getInstance();
    remote = MockRemoteSaleDataSource();
  });

  tearDown(() => db.close());

  testWidgets('server, held and pending sales listed; badge on the held one',
      (tester) async {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    await tester.runAsync(() async {
      await localSale(heldId, today.add(const Duration(hours: 1)), 700,
          lastError: 'MODULE_INACTIVE');
      await localSale(pendingId, today.add(const Duration(hours: 2)), 900);
    });
    when(() => remote.getSalesHistory(any())).thenAnswer((_) async => [
          Sale(
            id: 'srv-1',
            storeId: 'store-1',
            employeeId: 'emp-1',
            paymentMode: PaymentModeEnum.cash,
            totalAmount: 500,
            items: const [],
            occurredAt: today.add(const Duration(minutes: 30)),
            createdAt: today,
          ),
        ]);
    final connectivity = MockConnectivityService();
    when(() => connectivity.isOnline()).thenAnswer((_) async => true);

    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        appDatabaseProvider.overrideWithValue(db),
        currentUserIdProvider.overrideWith((ref) => Future.value('emp-1')),
        saleRepositoryProvider.overrideWithValue(SaleRepositoryImpl(
          LocalSaleDataSource(db),
          remote,
          db,
          connectivity: connectivity,
          syncService: MockSyncService(),
          syncTriggerDispatcher: MockSyncTriggerDispatcher(),
        )),
      ],
      child: const MaterialApp(home: SalesHistoryPage()),
    ));
    for (var i = 0; i < 5; i++) {
      await tester.runAsync(() => Future<void>.delayed(
          const Duration(milliseconds: 50)));
      await tester.pump();
    }

    expect(find.byType(Card), findsNWidgets(3));
    expect(find.text('En attente — module non activé'), findsOneWidget,
        reason: 'only the held sale has the badge');
    expect(find.textContaining('3f2b9c1e'), findsNothing);
    expect(find.textContaining('MODULE_'), findsNothing);
  });
}
