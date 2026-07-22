/// sale_repository_backend_first_test.dart — Story 5.1 AC7/AC8
///
/// Dedicated backend-first verification for SaleRepositoryImpl.
/// Complements sale_repository_impl_test.dart with explicit AC coverage tags.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/data/datasource/local_sale_datasource.dart';
import 'package:keevo/features/pos/data/datasource/remote_sale_datasource.dart';
import 'package:keevo/features/pos/data/repository/sale_repository_impl.dart';
import 'package:keevo/features/pos/domain/model/payment_mode_enum.dart';
import 'package:keevo/features/pos/domain/model/sale_model.dart';
import 'package:keevo/core/storage/app_database.dart' hide Sale, SaleItem;
import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/core/sync/sync_trigger_dispatcher.dart';
import 'package:mocktail/mocktail.dart';

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

Sale _testSale({String id = 'sale-1'}) => Sale(
      id: id,
      storeId: 'store-1',
      employeeId: 'emp-1',
      paymentMode: PaymentModeEnum.cash,
      totalAmount: 1500,
      items: [
        SaleItemModel(
          id: 'item-1',
          productId: 'prod-1',
          productName: 'Savon',
          catalogueUnitPrice: 500,
          appliedUnitPrice: 500,
          quantity: 3,
          subtotal: 1500,
        ),
      ],
      occurredAt: DateTime(2026, 3, 17),
      createdAt: DateTime(2026, 3, 17),
    );

void main() {
  late MockLocalSaleDataSource mockLocal;
  late MockRemoteSaleDataSource mockRemote;
  late FakeAppDatabase fakeDb;
  late MockConnectivityService mockConnectivity;
  late MockSyncService mockSyncService;
  late MockSyncTriggerDispatcher mockSyncTrigger;
  late SaleRepositoryImpl repo;

  setUpAll(() => registerFallbackValue(FakeSale()));

  setUp(() {
    mockLocal = MockLocalSaleDataSource();
    mockRemote = MockRemoteSaleDataSource();
    fakeDb = FakeAppDatabase();
    mockConnectivity = MockConnectivityService();
    mockSyncService = MockSyncService();
    mockSyncTrigger = MockSyncTriggerDispatcher();

    repo = SaleRepositoryImpl(
      mockLocal,
      mockRemote,
      fakeDb,
      connectivity: mockConnectivity,
      syncService: mockSyncService,
      syncTriggerDispatcher: mockSyncTrigger,
    );
  });

  group('AC7 (Story 5.6) — always offline-first regardless of connectivity', () {
    test('recordSale — always writes locally + queues + triggers background push',
        () async {
      final sale = _testSale();
      when(() => mockLocal.insertAll(any(), synced: false))
          .thenAnswer((_) async {});
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});

      await repo.recordSale(sale);

      verifyNever(() => mockRemote.pushSale(any()));
      verify(() => mockLocal.insertAll(sale, synced: false)).called(1);
      verify(() => mockSyncService.queueOperation(
            operation: 'CREATE_SALE',
            payload: any(named: 'payload'),
            entityId: sale.id,
          )).called(1);
      verify(() => mockSyncTrigger.triggerPushIfIdle()).called(1);
    });
  });

  group('AC8 — offline fallback: local + sync_queue', () {
    test('recordSale offline — local insertAll(synced: false) + queueOperation',
        () async {
      final sale = _testSale();
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => false);
      when(() => mockLocal.insertAll(any(), synced: false))
          .thenAnswer((_) async {});
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});

      await repo.recordSale(sale);

      verifyNever(() => mockRemote.pushSale(any()));
      verify(() => mockLocal.insertAll(sale, synced: false)).called(1);
      verify(() => mockSyncService.queueOperation(
            operation: 'CREATE_SALE',
            payload: any(named: 'payload'),
            entityId: sale.id,
          )).called(1);
    });

    test('recordSale online but backend fails — degrades to offline path',
        () async {
      final sale = _testSale();
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.pushSale(any()))
          .thenThrow(Exception('503 Service Unavailable'));
      when(() => mockLocal.insertAll(any(), synced: false))
          .thenAnswer((_) async {});
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});

      await repo.recordSale(sale);

      verify(() => mockLocal.insertAll(sale, synced: false)).called(1);
      verify(() => mockSyncService.queueOperation(
            operation: 'CREATE_SALE',
            payload: any(named: 'payload'),
            entityId: sale.id,
          )).called(1);
    });
  });
}
