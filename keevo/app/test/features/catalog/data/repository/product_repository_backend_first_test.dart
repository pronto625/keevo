import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/features/catalog/data/datasource/local_product_datasource.dart';
import 'package:keevo/features/catalog/data/datasource/remote_csv_import_datasource.dart';
import 'package:keevo/features/catalog/data/datasource/remote_product_datasource.dart';
import 'package:keevo/features/catalog/data/repository/product_repository_impl.dart';
import 'package:keevo/features/catalog/domain/model/product_model.dart';
import 'package:keevo/features/catalog/domain/model/product_response_dto.dart';
import 'package:keevo/features/catalog/domain/model/product_status.dart';
import 'package:mocktail/mocktail.dart';

class MockLocalProductDataSource extends Mock implements LocalProductDataSource {}

class MockRemoteProductDataSource extends Mock
    implements RemoteProductDataSource {}

class MockRemoteCsvImportDataSource extends Mock
    implements RemoteCsvImportDataSource {}

class MockConnectivityService extends Mock implements ConnectivityService {}

class MockSyncService extends Mock implements SyncService {}

class FakeProductModel extends Fake implements ProductModel {}

ProductModel _testProduct({String id = 'prod-1'}) => ProductModel(
      id: id,
      name: 'Savon',
      sku: 'KEV-000001',
      price: 500,
      buyPrice: 300,
      transportCost: 50,
      status: ProductStatus.active,
      createdAt: DateTime(2026, 3, 17),
      updatedAt: DateTime(2026, 3, 17),
    );

ProductResponseDto _testDto({String id = 'prod-1'}) => ProductResponseDto(
      id: id,
      name: 'Savon',
      sku: 'KEV-000001',
      price: 500,
      buyPrice: 300,
      transportCost: 50,
      status: 'ACTIVE',
      createdAt: '2026-03-17T00:00:00.000Z',
      updatedAt: '2026-03-17T00:00:00.000Z',
    );

void main() {
  late MockLocalProductDataSource mockLocal;
  late MockRemoteProductDataSource mockRemote;
  late MockRemoteCsvImportDataSource mockRemoteCsv;
  late MockConnectivityService mockConnectivity;
  late MockSyncService mockSyncService;
  late ProductRepositoryImpl repo;

  setUpAll(() => registerFallbackValue(FakeProductModel()));

  setUp(() {
    mockLocal = MockLocalProductDataSource();
    mockRemote = MockRemoteProductDataSource();
    mockRemoteCsv = MockRemoteCsvImportDataSource();
    mockConnectivity = MockConnectivityService();
    mockSyncService = MockSyncService();
    repo = ProductRepositoryImpl(
      local: mockLocal,
      remote: mockRemote,
      remoteCsv: mockRemoteCsv,
      connectivity: mockConnectivity,
      syncService: mockSyncService,
    );
  });

  group('update — backend-first', () {
    test('online — pushes to backend first, upserts locally', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.update(any(), any()))
          .thenAnswer((_) async => _testDto());
      when(() => mockLocal.upsert(any())).thenAnswer((_) async => _testProduct());

      await repo.update(id: 'prod-1', name: 'Savon Updated');

      verify(() => mockRemote.update('prod-1', any())).called(1);
      verify(() => mockLocal.upsert(any())).called(1);
    });

    test('online but backend fails — saves locally + queues sync', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.update(any(), any()))
          .thenThrow(Exception('Network error'));
      when(() => mockLocal.getById(any())).thenAnswer((_) async => _testProduct());
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
          )).thenAnswer((_) async => _testProduct());
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});

      await repo.update(id: 'prod-1', name: 'Savon Updated');

      verify(() => mockLocal.updateById(
            id: 'prod-1',
            name: 'Savon Updated',
            description: any(named: 'description'),
            sku: any(named: 'sku'),
            categoryId: any(named: 'categoryId'),
            price: any(named: 'price'),
            buyPrice: any(named: 'buyPrice'),
            transportCost: any(named: 'transportCost'),
            photoUrl: any(named: 'photoUrl'),
          )).called(1);
      verify(() => mockSyncService.queueOperation(
            operation: 'UPDATE_PRODUCT',
            payload: any(named: 'payload'),
            entityId: 'prod-1',
          )).called(1);
    });

    test('offline — saves locally + queues sync', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => false);
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
          )).thenAnswer((_) async => _testProduct());
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});

      await repo.update(id: 'prod-1', name: 'Savon Updated');

      verifyNever(() => mockRemote.update(any(), any()));
      verify(() => mockSyncService.queueOperation(
            operation: 'UPDATE_PRODUCT',
            payload: any(named: 'payload'),
            entityId: 'prod-1',
          )).called(1);
    });
  });

  group('archive — backend-first', () {
    test('online — archives on backend first, then locally', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.archive(any())).thenAnswer((_) async {});
      when(() => mockLocal.archiveById(any())).thenAnswer((_) async {});

      await repo.archive('prod-1');

      verify(() => mockRemote.archive('prod-1')).called(1);
      verify(() => mockLocal.archiveById('prod-1')).called(1);
    });

    test('online but backend fails — archives locally + queues sync',
        () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.archive(any())).thenThrow(Exception('Network'));
      when(() => mockLocal.archiveById(any())).thenAnswer((_) async {});
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});

      await repo.archive('prod-1');

      verify(() => mockLocal.archiveById('prod-1')).called(1);
      verify(() => mockSyncService.queueOperation(
            operation: 'ARCHIVE_PRODUCT',
            payload: {'productId': 'prod-1'},
            entityId: 'prod-1',
          )).called(1);
    });
  });

  group('unarchive — backend-first', () {
    test('online — unarchives on backend first, then locally', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.unarchive(any())).thenAnswer((_) async {});
      when(() => mockLocal.unarchiveById(any())).thenAnswer((_) async {});

      await repo.unarchive('prod-1');

      verify(() => mockRemote.unarchive('prod-1')).called(1);
      verify(() => mockLocal.unarchiveById('prod-1')).called(1);
    });

    test('offline — unarchives locally + queues sync', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => false);
      when(() => mockLocal.unarchiveById(any())).thenAnswer((_) async {});
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});

      await repo.unarchive('prod-1');

      verifyNever(() => mockRemote.unarchive(any()));
      verify(() => mockSyncService.queueOperation(
            operation: 'UNARCHIVE_PRODUCT',
            payload: {'productId': 'prod-1'},
            entityId: 'prod-1',
          )).called(1);
    });
  });
}
