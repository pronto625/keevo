/// product_repository_offline_first_test.dart — Story 5.6 AC2 + AC10
///
/// TDD RED: verifies [ProductRepositoryImpl] offline-first writes for
/// create(), update(), archive(), unarchive().
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/core/sync/sync_trigger_dispatcher.dart';
import 'package:keevo/features/catalog/data/datasource/local_product_datasource.dart';
import 'package:keevo/features/catalog/data/datasource/remote_csv_import_datasource.dart';
import 'package:keevo/features/catalog/data/datasource/remote_product_datasource.dart';
import 'package:keevo/features/catalog/data/repository/product_repository_impl.dart';
import 'package:keevo/features/catalog/domain/exception/product_exception.dart';
import 'package:keevo/features/catalog/domain/model/product_model.dart';
import 'package:mocktail/mocktail.dart';

class MockLocalProductDataSource extends Mock implements LocalProductDataSource {}
class MockRemoteProductDataSource extends Mock implements RemoteProductDataSource {}
class MockRemoteCsvImportDataSource extends Mock implements RemoteCsvImportDataSource {}
class MockConnectivityService extends Mock implements ConnectivityService {}
class MockSyncService extends Mock implements SyncService {}
class MockSyncTriggerDispatcher extends Mock implements SyncTriggerDispatcher {}

class FakeProductModel extends Fake implements ProductModel {}

ProductModel _testProduct({String id = 'prod-uuid-5-6'}) => ProductModel(
      id: id,
      name: 'Savon Test',
      price: 500,
      buyPrice: 300,
      transportCost: 0,
      createdAt: DateTime(2026, 4, 22),
      updatedAt: DateTime(2026, 4, 22),
    );

void main() {
  late MockLocalProductDataSource mockLocal;
  late MockRemoteProductDataSource mockRemote;
  late MockRemoteCsvImportDataSource mockRemoteCsv;
  late MockConnectivityService mockConnectivity;
  late MockSyncService mockSyncService;
  late MockSyncTriggerDispatcher mockDispatcher;
  late ProductRepositoryImpl repo;

  setUpAll(() {
    registerFallbackValue(FakeProductModel());
    registerFallbackValue(<String, dynamic>{});
  });

  setUp(() {
    mockLocal = MockLocalProductDataSource();
    mockRemote = MockRemoteProductDataSource();
    mockRemoteCsv = MockRemoteCsvImportDataSource();
    mockConnectivity = MockConnectivityService();
    mockSyncService = MockSyncService();
    mockDispatcher = MockSyncTriggerDispatcher();
    repo = ProductRepositoryImpl(
      local: mockLocal,
      remote: mockRemote,
      remoteCsv: mockRemoteCsv,
      connectivity: mockConnectivity,
      syncService: mockSyncService,
      syncTriggerDispatcher: mockDispatcher,
    );
  });

  group('AC2 — create() offline-first', () {
    // Helper: stub _local.insert(...) with all optional named params.
    void _stubInsert(ProductModel returnValue) {
      when(() => mockLocal.insert(
            name: any(named: 'name'),
            description: any(named: 'description'),
            sku: any(named: 'sku'),
            categoryId: any(named: 'categoryId'),
            price: any(named: 'price'),
            buyPrice: any(named: 'buyPrice'),
            transportCost: any(named: 'transportCost'),
            photoUrl: any(named: 'photoUrl'),
          )).thenAnswer((_) async => returnValue);
    }

    test('create_writesLocalFirst_withGeneratedUUID', () async {
      // _local.insert() generates UUID + persists locally (queue is internal to datasource).
      _stubInsert(_testProduct(id: 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'));
      when(() => mockLocal.search(any())).thenAnswer((_) async => []);
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      final product = await repo.create(name: 'Nouveau produit', price: 500);

      expect(product.id.length, 36, reason: 'UUID v4 is 36 chars');
      verify(() => mockLocal.insert(
            name: any(named: 'name'),
            description: any(named: 'description'),
            sku: any(named: 'sku'),
            categoryId: any(named: 'categoryId'),
            price: any(named: 'price'),
            buyPrice: any(named: 'buyPrice'),
            transportCost: any(named: 'transportCost'),
            photoUrl: any(named: 'photoUrl'),
          )).called(1);
      verifyNever(() => mockRemote.create(any()));
    });

    test('create_triggersDispatcherAfterLocalWrite', () async {
      // CREATE_PRODUCT queue is enqueued inside _local.insert() (datasource responsibility).
      // Repo responsibility: trigger push after local write.
      _stubInsert(_testProduct(id: 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'));
      when(() => mockLocal.search(any())).thenAnswer((_) async => []);
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      await repo.create(name: 'Nouveau produit', price: 500);

      verify(() => mockDispatcher.triggerPushIfIdle()).called(1);
    });

    test('create_duplicateName_throwsProductException_beforeWrite', () async {
      final dupe = ProductModel(
        id: 'existing-1',
        name: 'Dupe',
        price: 500,
        buyPrice: 0,
        transportCost: 0,
        createdAt: DateTime(2026, 4, 22),
        updatedAt: DateTime(2026, 4, 22),
      );
      when(() => mockLocal.search('Dupe')).thenAnswer((_) async => [dupe]);
      when(() => mockLocal.search(any())).thenAnswer((_) async => [dupe]);

      await expectLater(
        () => repo.create(name: 'Dupe', price: 500),
        throwsA(isA<ProductException>()),
      );
      verifyNever(() => mockLocal.insert(
            name: any(named: 'name'),
            description: any(named: 'description'),
            sku: any(named: 'sku'),
            categoryId: any(named: 'categoryId'),
            price: any(named: 'price'),
            buyPrice: any(named: 'buyPrice'),
            transportCost: any(named: 'transportCost'),
            photoUrl: any(named: 'photoUrl'),
          ));
    });
  });

  group('AC2 — update() offline-first', () {
    test('update_writesLocalFirst_queuesUpdateProduct', () async {
      final callOrder = <String>[];
      when(() => mockLocal.updateById(
            id: any(named: 'id'),
            name: any(named: 'name'),
            description: any(named: 'description'),
            sku: any(named: 'sku'),
            categoryId: any(named: 'categoryId'),
            price: any(named: 'price'),
            buyPrice: any(named: 'buyPrice'),
            transportCost: any(named: 'transportCost'),
            photoUrl: any(named: 'photoUrl'),
          )).thenAnswer((_) async {
        callOrder.add('local');
        return _testProduct();
      });
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {
        callOrder.add('queue');
      });
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      await repo.update(id: 'prod-uuid-5-6', name: 'Updated');

      expect(callOrder, ['local', 'queue'],
          reason: 'local write must precede queue');
      verify(() => mockSyncService.queueOperation(
            operation: 'UPDATE_PRODUCT',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).called(1);
      verifyNever(() => mockRemote.update(any(), any()));
    });
  });

  group('AC2 — archive() offline-first', () {
    test('archive_writesLocalFirst_queuesArchiveProduct', () async {
      final callOrder = <String>[];
      when(() => mockLocal.archiveById(any())).thenAnswer((_) async {
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

      await repo.archive('prod-uuid-5-6');

      expect(callOrder, ['local', 'queue']);
      verify(() => mockSyncService.queueOperation(
            operation: 'ARCHIVE_PRODUCT',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).called(1);
      verifyNever(() => mockRemote.archive(any()));
    });
  });
}
