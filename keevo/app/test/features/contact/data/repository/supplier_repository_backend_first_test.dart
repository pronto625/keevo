import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/features/contact/data/datasource/local_supplier_datasource.dart';
import 'package:keevo/features/contact/data/datasource/remote_supplier_datasource.dart';
import 'package:keevo/features/contact/data/repository/supplier_repository_impl.dart';
import 'package:keevo/features/contact/domain/model/supplier_model.dart';
import 'package:mocktail/mocktail.dart';

class MockLocalSupplierDataSource extends Mock
    implements LocalSupplierDataSource {}

class MockRemoteSupplierDataSource extends Mock
    implements RemoteSupplierDataSource {}

class MockConnectivityService extends Mock implements ConnectivityService {}

class MockSyncService extends Mock implements SyncService {}

class FakeSupplierModel extends Fake implements SupplierModel {}

SupplierModel _testSupplier({String id = 'sup-1'}) => SupplierModel(
      id: id,
      name: 'Fournisseur ABC',
      phone: '+237600000001',
      email: 'abc@example.com',
      createdAt: DateTime(2026, 3, 17),
      updatedAt: DateTime(2026, 3, 17),
    );

void main() {
  late MockLocalSupplierDataSource mockLocal;
  late MockRemoteSupplierDataSource mockRemote;
  late MockConnectivityService mockConnectivity;
  late MockSyncService mockSyncService;
  late SupplierRepositoryImpl repo;

  setUpAll(() => registerFallbackValue(FakeSupplierModel()));

  setUp(() {
    mockLocal = MockLocalSupplierDataSource();
    mockRemote = MockRemoteSupplierDataSource();
    mockConnectivity = MockConnectivityService();
    mockSyncService = MockSyncService();
    repo = SupplierRepositoryImpl(
      local: mockLocal,
      remote: mockRemote,
      connectivity: mockConnectivity,
      syncService: mockSyncService,
    );
  });

  group('update — backend-first', () {
    test('online — pushes to backend first, upserts locally', () async {
      final remoteResult = _testSupplier();
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockLocal.getById(any()))
          .thenAnswer((_) async => _testSupplier());
      when(() => mockRemote.update(any(), any()))
          .thenAnswer((_) async => remoteResult);
      when(() => mockLocal.upsert(any()))
          .thenAnswer((_) async => remoteResult);

      await repo.update(id: 'sup-1', name: 'Fournisseur Updated');

      verify(() => mockRemote.update('sup-1', any())).called(1);
      verify(() => mockLocal.upsert(any())).called(1);
    });

    test('online but backend fails — saves locally + queues sync', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockLocal.getById(any()))
          .thenAnswer((_) async => _testSupplier());
      when(() => mockRemote.update(any(), any()))
          .thenThrow(Exception('Network error'));
      when(() => mockLocal.upsert(any()))
          .thenAnswer((_) async => _testSupplier());
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});

      await repo.update(id: 'sup-1', name: 'Fournisseur Updated');

      verify(() => mockLocal.upsert(any())).called(1);
      verify(() => mockSyncService.queueOperation(
            operation: 'UPDATE_SUPPLIER',
            payload: any(named: 'payload'),
            entityId: 'sup-1',
          )).called(1);
    });

    test('offline — saves locally + queues sync', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => false);
      when(() => mockLocal.getById(any()))
          .thenAnswer((_) async => _testSupplier());
      when(() => mockLocal.upsert(any()))
          .thenAnswer((_) async => _testSupplier());
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});

      await repo.update(id: 'sup-1', name: 'Fournisseur Updated');

      verifyNever(() => mockRemote.update(any(), any()));
      verify(() => mockSyncService.queueOperation(
            operation: 'UPDATE_SUPPLIER',
            payload: any(named: 'payload'),
            entityId: 'sup-1',
          )).called(1);
    });
  });

  group('archive — backend-first', () {
    test('online — archives on backend first, then locally', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.archive(any())).thenAnswer((_) async {});
      when(() => mockLocal.archive(any())).thenAnswer((_) async {});

      await repo.archive('sup-1');

      verify(() => mockRemote.archive('sup-1')).called(1);
      verify(() => mockLocal.archive('sup-1')).called(1);
    });

    test('online but backend fails — archives locally + queues sync',
        () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.archive(any())).thenThrow(Exception('Network'));
      when(() => mockLocal.archive(any())).thenAnswer((_) async {});
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});

      await repo.archive('sup-1');

      verify(() => mockLocal.archive('sup-1')).called(1);
      verify(() => mockSyncService.queueOperation(
            operation: 'ARCHIVE_SUPPLIER',
            payload: {'supplierId': 'sup-1'},
            entityId: 'sup-1',
          )).called(1);
    });

    test('offline — archives locally + queues sync', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => false);
      when(() => mockLocal.archive(any())).thenAnswer((_) async {});
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});

      await repo.archive('sup-1');

      verifyNever(() => mockRemote.archive(any()));
      verify(() => mockSyncService.queueOperation(
            operation: 'ARCHIVE_SUPPLIER',
            payload: {'supplierId': 'sup-1'},
            entityId: 'sup-1',
          )).called(1);
    });
  });
}
