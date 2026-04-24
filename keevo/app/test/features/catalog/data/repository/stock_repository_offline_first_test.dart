/// stock_repository_offline_first_test.dart — Story 5.6 AC7 + AC10
///
/// TDD RED: verifies [StockRepositoryImpl] offline-first writes for
/// recordEntry() and adjustStock().
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/core/sync/sync_trigger_dispatcher.dart';
import 'package:keevo/features/catalog/data/datasource/local_stock_datasource.dart';
import 'package:keevo/features/catalog/data/datasource/remote_stock_datasource.dart';
import 'package:keevo/features/catalog/data/repository/stock_repository_impl.dart';
import 'package:keevo/features/catalog/domain/model/stock_level_model.dart';
import 'package:keevo/features/catalog/domain/model/stock_movement_model.dart';
import 'package:mocktail/mocktail.dart';

class MockLocalStockDataSource extends Mock implements LocalStockDataSource {}
class MockRemoteStockDataSource extends Mock implements RemoteStockDataSource {}
class MockConnectivityService extends Mock implements ConnectivityService {}
class MockSyncService extends Mock implements SyncService {}
class MockSyncTriggerDispatcher extends Mock implements SyncTriggerDispatcher {}

class FakeStockLevel extends Fake implements StockLevelModel {}
class FakeStockMovement extends Fake implements StockMovementModel {}

void main() {
  late MockLocalStockDataSource mockLocal;
  late MockRemoteStockDataSource mockRemote;
  late MockConnectivityService mockConnectivity;
  late MockSyncService mockSyncService;
  late MockSyncTriggerDispatcher mockDispatcher;
  late StockRepositoryImpl repo;

  setUpAll(() {
    registerFallbackValue(FakeStockLevel());
    registerFallbackValue(FakeStockMovement());
    registerFallbackValue(<String, dynamic>{});
  });

  setUp(() {
    mockLocal = MockLocalStockDataSource();
    mockRemote = MockRemoteStockDataSource();
    mockConnectivity = MockConnectivityService();
    mockSyncService = MockSyncService();
    mockDispatcher = MockSyncTriggerDispatcher();
    repo = StockRepositoryImpl(
      local: mockLocal,
      remote: mockRemote,
      syncService: mockSyncService,
      connectivity: mockConnectivity,
      syncTriggerDispatcher: mockDispatcher,
    );
  });

  group('AC7 — recordEntry() offline-first', () {
    test('recordEntry_writesLocalFirst_queuesRecordStockEntry', () async {
      final callOrder = <String>[];

      when(() => mockLocal.getLevelByStore(any(), any()))
          .thenAnswer((_) async => null);
      when(() => mockLocal.upsertLevel(any())).thenAnswer((_) async {
        callOrder.add('local-level');
      });
      when(() => mockLocal.insertMovement(any())).thenAnswer((_) async {
        callOrder.add('local-movement');
      });
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {
        callOrder.add('queue');
      });
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      await repo.recordEntry(
        productId: 'prod-1',
        storeId: 'store-1',
        quantity: 10,
      );

      // Both local writes MUST happen before queue
      expect(callOrder.indexOf('local-level'), lessThan(callOrder.indexOf('queue')));
      expect(callOrder.indexOf('local-movement'), lessThan(callOrder.indexOf('queue')));
      verifyNever(() => mockRemote.recordEntry(
            productId: any(named: 'productId'),
            storeId: any(named: 'storeId'),
            quantity: any(named: 'quantity'),
          ));
      verify(() => mockSyncService.queueOperation(
            operation: 'RECORD_STOCK_ENTRY',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).called(1);
    });
  });

  group('AC7 — adjustStock() offline-first', () {
    test('adjustStock_writesLocalFirst_queuesStockAdjust', () async {
      final callOrder = <String>[];

      when(() => mockLocal.getLevelByStore(any(), any()))
          .thenAnswer((_) async => null);
      when(() => mockLocal.upsertLevel(any())).thenAnswer((_) async {
        callOrder.add('local-level');
      });
      when(() => mockLocal.insertMovement(any())).thenAnswer((_) async {
        callOrder.add('local-movement');
      });
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {
        callOrder.add('queue');
      });
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      await repo.adjustStock(
        productId: 'prod-1',
        storeId: 'store-1',
        newQuantity: 25,
        notes: 'Inventaire',
      );

      expect(callOrder.indexOf('local-level'), lessThan(callOrder.indexOf('queue')));
      expect(callOrder.indexOf('local-movement'), lessThan(callOrder.indexOf('queue')));
      verifyNever(() => mockRemote.adjustStock(
            productId: any(named: 'productId'),
            storeId: any(named: 'storeId'),
            newQuantity: any(named: 'newQuantity'),
            notes: any(named: 'notes'),
          ));
      verify(() => mockSyncService.queueOperation(
            operation: 'STOCK_ADJUST',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).called(1);
    });
  });
}
