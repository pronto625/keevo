/// stock_transfer_repository_offline_first_test.dart — Story 5.6 AC8 + AC10
///
/// TDD RED: verifies [StockTransferRepositoryImpl] uses a single offline-first
/// path for executeTransfer() (local-first, always).
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/core/sync/sync_trigger_dispatcher.dart';
import 'package:keevo/features/inventory/data/datasource/local_stock_transfer_datasource.dart';
import 'package:keevo/features/inventory/data/datasource/remote_stock_transfer_datasource.dart';
import 'package:keevo/features/inventory/data/repository/stock_transfer_repository_impl.dart';
import 'package:keevo/features/inventory/domain/model/stock_transfer_model.dart';
import 'package:mocktail/mocktail.dart';

class MockLocalStockTransferDataSource extends Mock
    implements LocalStockTransferDataSource {}
class MockRemoteStockTransferDataSource extends Mock
    implements RemoteStockTransferDataSource {}
class MockConnectivityService extends Mock implements ConnectivityService {}
class MockSyncService extends Mock implements SyncService {}
class MockSyncTriggerDispatcher extends Mock implements SyncTriggerDispatcher {}

class FakeStockTransferModel extends Fake implements StockTransferModel {}

void main() {
  late MockLocalStockTransferDataSource mockLocal;
  late MockRemoteStockTransferDataSource mockRemote;
  late MockConnectivityService mockConnectivity;
  late MockSyncService mockSyncService;
  late MockSyncTriggerDispatcher mockDispatcher;
  late StockTransferRepositoryImpl repo;

  setUpAll(() {
    registerFallbackValue(FakeStockTransferModel());
    registerFallbackValue(<String, dynamic>{});
  });

  setUp(() {
    mockLocal = MockLocalStockTransferDataSource();
    mockRemote = MockRemoteStockTransferDataSource();
    mockConnectivity = MockConnectivityService();
    mockSyncService = MockSyncService();
    mockDispatcher = MockSyncTriggerDispatcher();
    repo = StockTransferRepositoryImpl(
      local: mockLocal,
      remote: mockRemote,
      connectivity: mockConnectivity,
      syncService: mockSyncService,
      syncTriggerDispatcher: mockDispatcher,
    );
  });

  group('AC8 — executeTransfer() offline-first', () {
    test('executeTransfer_validatesLocalStock_orThrows', () async {
      // Insufficient stock → throws StateError
      when(() => mockLocal.getLocalStock(
            productId: any(named: 'productId'),
            storeId: any(named: 'storeId'),
          )).thenAnswer((_) async => 5); // available = 5

      expect(
        () => repo.executeTransfer(
          sourceStoreId: 'store-A',
          destinationStoreId: 'store-B',
          productId: 'prod-1',
          quantity: 10, // requesting more than available
        ),
        throwsA(isA<StateError>()),
      );
    });

    test('executeTransfer_writesLocalFirst_queuesStockTransfer', () async {
      final callOrder = <String>[];

      when(() => mockLocal.getLocalStock(
            productId: any(named: 'productId'),
            storeId: any(named: 'storeId'),
          )).thenAnswer((_) async => 50);
      // AC1: only source decrement is applied at step 1
      when(() => mockLocal.applySourceDecrement(
            productId: any(named: 'productId'),
            sourceStoreId: any(named: 'sourceStoreId'),
            quantity: any(named: 'quantity'),
          )).thenAnswer((_) async {
        callOrder.add('local-apply');
      });
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {
        callOrder.add('queue');
      });
      when(() => mockLocal.saveTransfer(any())).thenAnswer((_) async {});
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      await repo.executeTransfer(
        sourceStoreId: 'store-A',
        destinationStoreId: 'store-B',
        productId: 'prod-1',
        quantity: 5,
      );

      expect(callOrder.indexOf('local-apply'), lessThan(callOrder.indexOf('queue')));
      // Remote MUST NOT be called in 5.6 offline-first path
      verifyNever(() => mockRemote.executeTransfer(
            sourceStoreId: any(named: 'sourceStoreId'),
            destinationStoreId: any(named: 'destinationStoreId'),
            productId: any(named: 'productId'),
            quantity: any(named: 'quantity'),
          ));
      verify(() => mockSyncService.queueOperation(
            operation: 'STOCK_TRANSFER',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).called(1);
    });

    // AC1 regression: destination stock must NOT be credited at Step 1
    test('executeTransfer_doesNotCreditDestination_atStepOne', () async {
      when(() => mockLocal.getLocalStock(
            productId: any(named: 'productId'),
            storeId: any(named: 'storeId'),
          )).thenAnswer((_) async => 50);
      when(() => mockLocal.applySourceDecrement(
            productId: any(named: 'productId'),
            sourceStoreId: any(named: 'sourceStoreId'),
            quantity: any(named: 'quantity'),
          )).thenAnswer((_) async {});
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});
      when(() => mockLocal.saveTransfer(any())).thenAnswer((_) async {});
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      await repo.executeTransfer(
        sourceStoreId: 'store-A',
        destinationStoreId: 'store-B',
        productId: 'prod-1',
        quantity: 5,
      );

      // Destination increment MUST NOT be called during executeTransfer (AC1)
      verifyNever(() => mockLocal.applyDestinationIncrement(
            productId: any(named: 'productId'),
            destinationStoreId: any(named: 'destinationStoreId'),
            quantity: any(named: 'quantity'),
          ));
    });
  });

  group('AC1 — completeTransfer() credits destination locally', () {
    test('completeTransfer_appliesDestinationIncrement_afterOnlineCompletion',
        () async {
      final completed = StockTransferModel(
        id: 'transfer-1',
        sourceStoreId: 'store-A',
        destinationStoreId: 'store-B',
        productId: 'prod-1',
        quantity: 5,
        actorId: 'actor-1',
        occurredAt: DateTime.now(),
        status: 'COMPLETED',
      );

      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockLocal.getTransferById(any())).thenAnswer((_) async => null);
      when(() => mockRemote.completeTransfer(any()))
          .thenAnswer((_) async => completed);
      when(() => mockLocal.saveTransfer(any())).thenAnswer((_) async {});
      when(() => mockLocal.applyDestinationIncrement(
            productId: any(named: 'productId'),
            destinationStoreId: any(named: 'destinationStoreId'),
            quantity: any(named: 'quantity'),
          )).thenAnswer((_) async {});

      await repo.completeTransfer('transfer-1');

      verify(() => mockLocal.applyDestinationIncrement(
            productId: 'prod-1',
            destinationStoreId: 'store-B',
            quantity: 5,
          )).called(1);
    });
  });
}
