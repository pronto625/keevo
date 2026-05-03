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

StockTransferModel _testTransfer({
  String id = 'xfer-1',
  String status = 'COMPLETED',
}) =>
    StockTransferModel(
      id: id,
      sourceStoreId: 'store-1',
      destinationStoreId: 'store-2',
      productId: 'prod-1',
      quantity: 10,
      actorId: 'emp-1',
      occurredAt: DateTime(2026, 3, 17),
      status: status,
    );

void main() {
  late MockLocalStockTransferDataSource mockLocal;
  late MockRemoteStockTransferDataSource mockRemote;
  late MockConnectivityService mockConnectivity;
  late MockSyncService mockSyncService;
  late MockSyncTriggerDispatcher mockSyncTrigger;
  late StockTransferRepositoryImpl repo;

  setUpAll(() => registerFallbackValue(FakeStockTransferModel()));

  setUp(() {
    mockLocal = MockLocalStockTransferDataSource();
    mockRemote = MockRemoteStockTransferDataSource();
    mockConnectivity = MockConnectivityService();
    mockSyncService = MockSyncService();
    mockSyncTrigger = MockSyncTriggerDispatcher();
    repo = StockTransferRepositoryImpl(
      local: mockLocal,
      remote: mockRemote,
      connectivity: mockConnectivity,
      syncService: mockSyncService,
      syncTriggerDispatcher: mockSyncTrigger,
    );
  });

  group('executeTransfer — backend-first', () {
    test('online — calls remote first, saves locally, applies stock change',
        () async {
      final transfer = _testTransfer();
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.executeTransfer(
            sourceStoreId: any(named: 'sourceStoreId'),
            destinationStoreId: any(named: 'destinationStoreId'),
            productId: any(named: 'productId'),
            variantId: any(named: 'variantId'),
            quantity: any(named: 'quantity'),
            notes: any(named: 'notes'),
          )).thenAnswer((_) async => transfer);
      when(() => mockLocal.saveTransfer(any())).thenAnswer((_) async {});
      when(() => mockLocal.applyLocalStockChange(
            productId: any(named: 'productId'),
            variantId: any(named: 'variantId'),
            sourceStoreId: any(named: 'sourceStoreId'),
            destinationStoreId: any(named: 'destinationStoreId'),
            quantity: any(named: 'quantity'),
          )).thenAnswer((_) async {});

      final result = await repo.executeTransfer(
        sourceStoreId: 'store-1',
        destinationStoreId: 'store-2',
        productId: 'prod-1',
        quantity: 10,
      );

      expect(result.status, 'COMPLETED');
      verify(() => mockRemote.executeTransfer(
            sourceStoreId: 'store-1',
            destinationStoreId: 'store-2',
            productId: 'prod-1',
            variantId: null,
            quantity: 10,
            notes: null,
          )).called(1);
      verify(() => mockLocal.saveTransfer(any())).called(1);
    });

    test('offline — validates stock, queues via SyncService at repo level',
        () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => false);
      when(() => mockLocal.getLocalStock(
            productId: any(named: 'productId'),
            variantId: any(named: 'variantId'),
            storeId: any(named: 'storeId'),
          )).thenAnswer((_) async => 50);
      when(() => mockLocal.applyLocalStockChange(
            productId: any(named: 'productId'),
            variantId: any(named: 'variantId'),
            sourceStoreId: any(named: 'sourceStoreId'),
            destinationStoreId: any(named: 'destinationStoreId'),
            quantity: any(named: 'quantity'),
          )).thenAnswer((_) async {});
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});
      when(() => mockLocal.saveTransfer(any())).thenAnswer((_) async {});

      final result = await repo.executeTransfer(
        sourceStoreId: 'store-1',
        destinationStoreId: 'store-2',
        productId: 'prod-1',
        quantity: 10,
      );

      expect(result.status, 'PENDING_SYNC');
      verifyNever(() => mockRemote.executeTransfer(
            sourceStoreId: any(named: 'sourceStoreId'),
            destinationStoreId: any(named: 'destinationStoreId'),
            productId: any(named: 'productId'),
            variantId: any(named: 'variantId'),
            quantity: any(named: 'quantity'),
            notes: any(named: 'notes'),
          ));
      verify(() => mockSyncService.queueOperation(
            operation: 'STOCK_TRANSFER',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).called(1);
    });

    test('offline — insufficient stock throws StateError', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => false);
      when(() => mockLocal.getLocalStock(
            productId: any(named: 'productId'),
            variantId: any(named: 'variantId'),
            storeId: any(named: 'storeId'),
          )).thenAnswer((_) async => 5);

      expect(
        () => repo.executeTransfer(
          sourceStoreId: 'store-1',
          destinationStoreId: 'store-2',
          productId: 'prod-1',
          quantity: 10,
        ),
        throwsA(isA<StateError>()),
      );
    });

    test('online but backend fails — falls back to offline path with sync_queue',
        () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.executeTransfer(
            sourceStoreId: any(named: 'sourceStoreId'),
            destinationStoreId: any(named: 'destinationStoreId'),
            productId: any(named: 'productId'),
            variantId: any(named: 'variantId'),
            quantity: any(named: 'quantity'),
            notes: any(named: 'notes'),
          )).thenThrow(Exception('Network'));
      when(() => mockLocal.getLocalStock(
            productId: any(named: 'productId'),
            variantId: any(named: 'variantId'),
            storeId: any(named: 'storeId'),
          )).thenAnswer((_) async => 50);
      when(() => mockLocal.applyLocalStockChange(
            productId: any(named: 'productId'),
            variantId: any(named: 'variantId'),
            sourceStoreId: any(named: 'sourceStoreId'),
            destinationStoreId: any(named: 'destinationStoreId'),
            quantity: any(named: 'quantity'),
          )).thenAnswer((_) async {});
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});
      when(() => mockLocal.saveTransfer(any())).thenAnswer((_) async {});

      final result = await repo.executeTransfer(
        sourceStoreId: 'store-1',
        destinationStoreId: 'store-2',
        productId: 'prod-1',
        quantity: 10,
      );

      expect(result.status, 'PENDING_SYNC');
      verify(() => mockSyncService.queueOperation(
            operation: 'STOCK_TRANSFER',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).called(1);
    });
  });
}
