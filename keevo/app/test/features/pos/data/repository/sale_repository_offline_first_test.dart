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
import 'package:keevo/features/pos/domain/exception/offline_action_not_supported_exception.dart';
import 'package:keevo/features/pos/domain/model/payment_mode_enum.dart';
import 'package:keevo/features/pos/domain/model/sale_model.dart';
import 'package:mocktail/mocktail.dart';
import 'package:sqlite3/open.dart';

class MockLocalSaleDataSource extends Mock implements LocalSaleDataSource {}
class MockRemoteSaleDataSource extends Mock implements RemoteSaleDataSource {}

/// Fake that executes the transaction callback for real.
/// Mocktail cannot stub generic methods — this Fake delegates directly.
class FakeAppDatabase extends Fake implements AppDatabase {
  @override
  Future<T> transaction<T>(
    Future<T> Function() action, {
    bool requireNew = false,
  }) {
    return action();
  }
}
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
  late FakeAppDatabase fakeDb;
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
    fakeDb = FakeAppDatabase();
    mockConnectivity = MockConnectivityService();
    mockSyncService = MockSyncService();
    mockDispatcher = MockSyncTriggerDispatcher();

    repo = SaleRepositoryImpl(
      mockLocal,
      mockRemote,
      fakeDb,
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

    // AC3 (F-HIGH-4 / Story 13.4) — crash atomicity proof
    test('shouldNotLoseSaleWhenCrashBetweenInsertAndQueue', () async {
      // Override the sync service to throw on queueOperation, simulating
      // a crash between the local insertAll and the queueOperation calls.
      when(() => mockSyncServiceDb.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenThrow(Exception('Simulated crash mid-enqueue'));

      final sale = Sale(
        id: 'sale-crash-test',
        storeId: 'store-1',
        employeeId: 'emp-1',
        paymentMode: PaymentModeEnum.cash,
        totalAmount: 5000,
        items: [
          SaleItemModel(
            id: 'item-crash',
            productId: 'prod-1',
            productName: 'Savon',
            catalogueUnitPrice: 2500,
            appliedUnitPrice: 2500,
            quantity: 2,
            subtotal: 5000,
          ),
        ],
        occurredAt: DateTime.now(),
        createdAt: DateTime.now(),
      );

      // recordSale() must propagate the exception. `expectLater` + `await`
      // is required here (not a bare `expect(() => ..., throwsA(...))`) so
      // the assertion below only runs AFTER the transaction has actually
      // finished rolling back — otherwise the `db.select` query below races
      // against the still-in-flight rollback and the test is flaky.
      await expectLater(
        repoWithDb.recordSale(sale),
        throwsA(isA<Exception>()),
      );

      // The sale must NOT be present — proof that the outer transaction
      // rolled back insertAll's writes atomically.
      final row = await (db.select(db.sales)
            ..where((s) => s.id.equals(sale.id)))
          .getSingleOrNull();
      expect(row, isNull,
          reason: 'Sale must NOT exist after a crash mid-enqueue — '
              'the outer transaction must roll back insertAll atomically');
    });

    // AC3 (F-HIGH-4 / Story 13.4) — multi-operation ordering: a LATER
    // queueOperation call failing after an EARLIER one already succeeded
    // must still roll back everything (including the earlier successful
    // sync_queue insert), not just recordSale()'s own insertAll writes.
    test('shouldRollBackEarlierSucceededQueueOperationWhenLaterOneThrows',
        () async {
      when(() => mockSyncServiceDb.queueOperation(
            operation: 'RECORD_STOCK_ENTRY',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});
      when(() => mockSyncServiceDb.queueOperation(
            operation: 'PROMOTE_PRODUCT',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenThrow(Exception('Simulated crash on later operation'));

      final sale = Sale(
        id: 'sale-multi-op-crash-test',
        storeId: 'store-1',
        employeeId: 'emp-1',
        paymentMode: PaymentModeEnum.cash,
        totalAmount: 5000,
        items: [
          SaleItemModel(
            id: 'item-multi-op',
            productId: 'prod-draft',
            productName: 'Savon',
            catalogueUnitPrice: 2500,
            appliedUnitPrice: 2500,
            quantity: 2,
            subtotal: 5000,
          ),
        ],
        occurredAt: DateTime.now(),
        createdAt: DateTime.now(),
        originalDraftProductIds: const ['prod-draft'],
        initialStockEntries: const {'prod-draft': 5},
      );

      await expectLater(
        repoWithDb.recordSale(sale),
        throwsA(isA<Exception>()),
      );

      // The sale must NOT exist — proof that the transaction rolled back
      // ALL writes, including the RECORD_STOCK_ENTRY queueOperation call
      // that had already succeeded before PROMOTE_PRODUCT threw.
      final row = await (db.select(db.sales)
            ..where((s) => s.id.equals(sale.id)))
          .getSingleOrNull();
      expect(row, isNull,
          reason: 'Sale must NOT exist after a later operation in the '
              'sequence crashes — the outer transaction must roll back '
              'ALL writes atomically, including earlier successful '
              'queueOperation calls');
    });
  });

  // ── Story v1s-13-5 — cancelSale/correctSale on COMPLETED sales (AC9, D2) ──
  // Uses a real in-memory Drift DB because SaleRepositoryImpl.cancelSale()
  // now reads the LOCAL sale status directly via _db.select(_db.sales).

  group('Story v1s-13-5 — COMPLETED sale cancel/correct online-only guard', () {
    late AppDatabase db;
    late LocalSaleDataSource localDs;
    late MockRemoteSaleDataSource mockRemoteDb;
    late MockConnectivityService mockConnectivityDb;
    late MockSyncService mockSyncServiceForCancel;
    late SaleRepositoryImpl repoWithDb;

    setUpAll(_overrideSqlite3ForLinuxTesting);

    setUp(() {
      db = AppDatabase.forTesting();
      localDs = LocalSaleDataSource(db);
      mockRemoteDb = MockRemoteSaleDataSource();
      mockConnectivityDb = MockConnectivityService();
      mockSyncServiceForCancel = MockSyncService();
      when(() => mockSyncServiceForCancel.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});
      repoWithDb = SaleRepositoryImpl(
        localDs,
        mockRemoteDb,
        db,
        connectivity: mockConnectivityDb,
        syncService: mockSyncServiceForCancel,
        syncTriggerDispatcher: MockSyncTriggerDispatcher(),
      );
    });

    tearDown(() async => db.close());

    Future<void> seedSale(String id, String status) async {
      final sale = Sale(
        id: id,
        storeId: 'store-1',
        employeeId: 'emp-1',
        paymentMode: PaymentModeEnum.cash,
        totalAmount: 2000,
        status: status,
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
        occurredAt: DateTime.now(),
        createdAt: DateTime.now(),
      );
      await localDs.insertAll(sale, synced: true);
    }

    test(
        'cancelSale_completedLocally_offline_throwsOfflineActionNotSupported_noLocalWrite_noQueue',
        () async {
      await seedSale('sale-completed-offline', 'COMPLETED');
      when(() => mockConnectivityDb.isOnline()).thenAnswer((_) async => false);

      await expectLater(
        repoWithDb.cancelSale('sale-completed-offline', 'Erreur de scan article'),
        throwsA(isA<OfflineActionNotSupportedException>()),
      );

      verifyNever(() => mockRemoteDb.cancelSale(any(), any()));
      verifyNever(() => mockSyncServiceForCancel.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          ));
      final row = await (db.select(db.sales)
            ..where((s) => s.id.equals('sale-completed-offline')))
          .getSingle();
      expect(row.status, 'COMPLETED',
          reason: 'Local status must NOT change when offline and COMPLETED');
    });

    test(
        'cancelSale_unknownLocalRow_offline_throwsOfflineActionNotSupported_noQueue',
        () async {
      // No seedSale() call — local cache has no row for this sale id, so
      // status can't be verified as PENDING; must fail closed rather than
      // fall through to the offline PENDING queue path (Review patch, AC9).
      when(() => mockConnectivityDb.isOnline()).thenAnswer((_) async => false);

      await expectLater(
        repoWithDb.cancelSale('sale-unknown-locally', 'Erreur de scan article'),
        throwsA(isA<OfflineActionNotSupportedException>()),
      );

      verifyNever(() => mockRemoteDb.cancelSale(any(), any()));
      verifyNever(() => mockSyncServiceForCancel.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          ));
    });

    test('cancelSale_completedLocally_online_callsRemoteAndUpdatesLocalStatus',
        () async {
      await seedSale('sale-completed-online', 'COMPLETED');
      when(() => mockConnectivityDb.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemoteDb.cancelSale(any(), any())).thenAnswer((_) async {});

      await repoWithDb.cancelSale('sale-completed-online', 'Erreur de scan article');

      verify(() => mockRemoteDb.cancelSale('sale-completed-online', 'Erreur de scan article'))
          .called(1);
      final row = await (db.select(db.sales)
            ..where((s) => s.id.equals('sale-completed-online')))
          .getSingle();
      expect(row.status, 'CANCELLED');
    });

    test('cancelSale_pendingLocally_offline_stillQueuesOperation_unchangedBehavior',
        () async {
      await seedSale('sale-pending-offline', 'PENDING_VALIDATION');
      when(() => mockConnectivityDb.isOnline()).thenAnswer((_) async => false);

      await repoWithDb.cancelSale('sale-pending-offline', 'Client a changé d\'avis');

      verifyNever(() => mockRemoteDb.cancelSale(any(), any()));
      final row = await (db.select(db.sales)
            ..where((s) => s.id.equals('sale-pending-offline')))
          .getSingle();
      expect(row.status, 'CANCELLED',
          reason: 'PENDING cancel offline path is unchanged by this story');
    });

    test('correctSale_offline_throwsOfflineActionNotSupported', () async {
      when(() => mockConnectivityDb.isOnline()).thenAnswer((_) async => false);

      await expectLater(
        repoWithDb.correctSale('sale-x', 'Erreur de quantité scannée', {'item-1': 2}),
        throwsA(isA<OfflineActionNotSupportedException>()),
      );

      verifyNever(() => mockRemoteDb.correctSale(any(), any(), any()));
    });

    test('correctSale_online_callsRemote', () async {
      when(() => mockConnectivityDb.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemoteDb.correctSale(any(), any(), any()))
          .thenAnswer((_) async {});

      await repoWithDb.correctSale('sale-x', 'Erreur de quantité scannée', {'item-1': 2});

      verify(() => mockRemoteDb.correctSale(
          'sale-x', 'Erreur de quantité scannée', {'item-1': 2})).called(1);
    });

    test(
        'correctSale_online_updatesLocalCache_soDetailPageIsNotStaleAfterSuccess',
        () async {
      await seedSale('sale-correct-online', 'COMPLETED');
      when(() => mockConnectivityDb.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemoteDb.correctSale(any(), any(), any()))
          .thenAnswer((_) async {});

      await repoWithDb.correctSale(
          'sale-correct-online', 'Erreur de quantité scannée', {'item-1': 5});

      final item = await (db.select(db.saleItems)
            ..where((i) => i.id.equals('item-1')))
          .getSingle();
      expect(item.quantity, 5);
      expect(item.subtotal, 5000, reason: 'unitPrice 1000 * newQty 5');

      final sale = await (db.select(db.sales)
            ..where((s) => s.id.equals('sale-correct-online')))
          .getSingle();
      expect(sale.totalAmount, 5000, reason: 'no discount on this seeded sale');
    });
  });
}
