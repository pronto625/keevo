/// sale_repository_offline_first_test.dart — Story 5.6 AC1 + AC10
///
/// TDD RED: verifies that [SaleRepositoryImpl] follows the offline-first
/// write pattern: local write first, then sync queue, then background push.
library;

import 'dart:ffi';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_database.dart' hide Sale, SaleItem;
import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/core/sync/sync_trigger_dispatcher.dart';
import 'package:keevo/features/pos/data/datasource/local_sale_datasource.dart';
import 'package:keevo/features/pos/data/datasource/remote_sale_datasource.dart';
import 'package:keevo/features/pos/data/repository/sale_repository_impl.dart';
import 'package:keevo/features/pos/domain/model/payment_mode_enum.dart';
import 'package:keevo/features/pos/domain/model/sale_model.dart';
import 'package:mocktail/mocktail.dart';
import 'package:sqlite3/open.dart';

class MockLocalSaleDataSource extends Mock implements LocalSaleDataSource {}
class MockRemoteSaleDataSource extends Mock implements RemoteSaleDataSource {}
class MockAppDatabase extends Mock implements AppDatabase {}
class MockConnectivityService extends Mock implements ConnectivityService {}
class MockSyncService extends Mock implements SyncService {}
class MockSyncTriggerDispatcher extends Mock implements SyncTriggerDispatcher {}

class FakeSale extends Fake implements Sale {}

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

Sale _testSale({String id = 'sale-5-6'}) => Sale(
      id: id,
      storeId: 'store-1',
      employeeId: 'emp-1',
      paymentMode: PaymentModeEnum.cash,
      totalAmount: 2000,
      items: [
        SaleItemModel(
          id: 'item-1',
          productId: 'prod-1',
          productName: 'Savon',
          catalogueUnitPrice: 1000,
          appliedUnitPrice: 1000,
          quantity: 2,
          subtotal: 2000,
        ),
      ],
      occurredAt: DateTime(2026, 4, 22),
      createdAt: DateTime(2026, 4, 22),
    );

void main() {
  late MockLocalSaleDataSource mockLocal;
  late MockRemoteSaleDataSource mockRemote;
  late MockAppDatabase mockDb;
  late MockConnectivityService mockConnectivity;
  late MockSyncService mockSyncService;
  late MockSyncTriggerDispatcher mockDispatcher;
  late SaleRepositoryImpl repo;

  setUpAll(() {
    registerFallbackValue(FakeSale());
    registerFallbackValue(<String, dynamic>{});
  });

  setUp(() {
    mockLocal = MockLocalSaleDataSource();
    mockRemote = MockRemoteSaleDataSource();
    mockDb = MockAppDatabase();
    mockConnectivity = MockConnectivityService();
    mockSyncService = MockSyncService();
    mockDispatcher = MockSyncTriggerDispatcher();
    repo = SaleRepositoryImpl(
      mockLocal,
      mockRemote,
      mockDb,
      connectivity: mockConnectivity,
      syncService: mockSyncService,
      syncTriggerDispatcher: mockDispatcher,
    );
  });

  group('AC1 — recordSale offline-first', () {
    test('recordSale_alwaysWritesLocalFirst_beforeAnyNetwork', () async {
      final sale = _testSale();
      when(() => mockLocal.insertAll(any(), synced: false))
          .thenAnswer((_) async {});
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      await repo.recordSale(sale);

      // Local write must happen — remote is NEVER called
      verify(() => mockLocal.insertAll(sale, synced: false)).called(1);
      verifyNever(() => mockRemote.pushSale(any()));
    });

    test('recordSale_queuesCreateSale_operation', () async {
      final sale = _testSale();
      when(() => mockLocal.insertAll(any(), synced: false))
          .thenAnswer((_) async {});
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      await repo.recordSale(sale);

      verify(() => mockSyncService.queueOperation(
            operation: 'CREATE_SALE',
            payload: any(named: 'payload'),
            entityId: sale.id,
          )).called(1);
    });

    test('recordSale_whenOnline_triggersPushInBackground', () async {
      final sale = _testSale();
      when(() => mockLocal.insertAll(any(), synced: false))
          .thenAnswer((_) async {});
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      await repo.recordSale(sale);

      // triggerPushIfIdle is called regardless of connectivity
      // (connectivity check happens inside the dispatcher — AC6)
      verify(() => mockDispatcher.triggerPushIfIdle()).called(1);
    });

    test('recordSale_localWriteOrdering_beforeQueue', () async {
      final sale = _testSale();
      final callOrder = <String>[];

      when(() => mockLocal.insertAll(any(), synced: false)).thenAnswer((_) async {
        callOrder.add('local');
      });
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {
        callOrder.add('queue');
      });
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      await repo.recordSale(sale);

      expect(callOrder, ['local', 'queue'],
          reason: '_local.insertAll must be called BEFORE queueOperation');
    });
  });

  // ── AC10 — immediate visibility in getSalesForToday ──────────────────────
  // Uses an in-memory Drift DB so the actual query is exercised.

  group('AC1+AC10 — recordSale immediately visible in getSalesForToday', () {
    late AppDatabase db;
    late SaleRepositoryImpl repoWithDb;
    late MockSyncService mockSyncServiceDb;
    late MockSyncTriggerDispatcher mockDispatcherDb;

    setUpAll(_overrideSqlite3ForLinuxTesting);

    setUp(() {
      db = AppDatabase.forTesting();
      mockSyncServiceDb = MockSyncService();
      mockDispatcherDb = MockSyncTriggerDispatcher();
      final localDs = LocalSaleDataSource(db);
      repoWithDb = SaleRepositoryImpl(
        localDs,
        MockRemoteSaleDataSource(),
        db,
        connectivity: MockConnectivityService(),
        syncService: mockSyncServiceDb,
        syncTriggerDispatcher: mockDispatcherDb,
      );
      when(() => mockSyncServiceDb.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});
      when(() => mockDispatcherDb.triggerPushIfIdle()).thenReturn(null);
    });

    tearDown(() async => db.close());

    test('recordSale_isImmediatelyVisibleInGetSalesForToday', () async {
      final now = DateTime.now();
      final sale = Sale(
        id: 'sale-5-6-today',
        storeId: 'store-1',
        employeeId: 'emp-1',
        paymentMode: PaymentModeEnum.cash,
        totalAmount: 2000,
        items: [
          SaleItemModel(
            id: 'item-1',
            productId: 'prod-1',
            productName: 'Savon',
            catalogueUnitPrice: 1000,
            appliedUnitPrice: 1000,
            quantity: 2,
            subtotal: 2000,
          ),
        ],
        occurredAt: now,
        createdAt: now,
      );
      await repoWithDb.recordSale(sale);

      final today = await repoWithDb.getSalesForToday(sale.storeId);

      expect(today, isNotEmpty,
          reason: 'Sale must be visible immediately after recordSale()');
      expect(today.any((s) => s.id == sale.id), isTrue);
    });
  });
}
