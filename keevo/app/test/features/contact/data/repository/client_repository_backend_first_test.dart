import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/features/contact/data/datasource/local_client_datasource.dart';
import 'package:keevo/features/contact/data/datasource/remote_client_datasource.dart';
import 'package:keevo/features/contact/data/repository/client_repository_impl.dart';
import 'package:keevo/features/contact/domain/model/client_model.dart';
import 'package:mocktail/mocktail.dart';

class MockLocalClientDataSource extends Mock implements LocalClientDataSource {}

class MockRemoteClientDataSource extends Mock
    implements RemoteClientDataSource {}

class MockConnectivityService extends Mock implements ConnectivityService {}

class MockSyncService extends Mock implements SyncService {}

class FakeClientModel extends Fake implements ClientModel {}

ClientModel _testClient({String id = 'client-1'}) => ClientModel(
      id: id,
      name: 'Jean Dupont',
      phone: '+237600000000',
      email: 'jean@example.com',
      createdAt: DateTime(2026, 3, 17),
      updatedAt: DateTime(2026, 3, 17),
    );

void main() {
  late MockLocalClientDataSource mockLocal;
  late MockRemoteClientDataSource mockRemote;
  late MockConnectivityService mockConnectivity;
  late MockSyncService mockSyncService;
  late ClientRepositoryImpl repo;

  setUpAll(() => registerFallbackValue(FakeClientModel()));

  setUp(() {
    mockLocal = MockLocalClientDataSource();
    mockRemote = MockRemoteClientDataSource();
    mockConnectivity = MockConnectivityService();
    mockSyncService = MockSyncService();
    repo = ClientRepositoryImpl(
      local: mockLocal,
      remote: mockRemote,
      connectivity: mockConnectivity,
      syncService: mockSyncService,
    );
  });

  group('update — backend-first', () {
    test('online — pushes to backend first, upserts locally', () async {
      final remoteResult = _testClient();
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockLocal.getById(any()))
          .thenAnswer((_) async => _testClient());
      when(() => mockRemote.update(any(), any()))
          .thenAnswer((_) async => remoteResult);
      when(() => mockLocal.upsert(any()))
          .thenAnswer((_) async => remoteResult);

      await repo.update(id: 'client-1', name: 'Jean Updated');

      verify(() => mockRemote.update('client-1', any())).called(1);
      verify(() => mockLocal.upsert(any())).called(1);
    });

    test('online but backend fails — saves locally + queues sync', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockLocal.getById(any()))
          .thenAnswer((_) async => _testClient());
      when(() => mockRemote.update(any(), any()))
          .thenThrow(Exception('Network error'));
      when(() => mockLocal.upsert(any()))
          .thenAnswer((_) async => _testClient());
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});

      await repo.update(id: 'client-1', name: 'Jean Updated');

      verify(() => mockLocal.upsert(any())).called(1);
      verify(() => mockSyncService.queueOperation(
            operation: 'UPDATE_CLIENT',
            payload: any(named: 'payload'),
            entityId: 'client-1',
          )).called(1);
    });

    test('offline — saves locally + queues sync', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => false);
      when(() => mockLocal.getById(any()))
          .thenAnswer((_) async => _testClient());
      when(() => mockLocal.upsert(any()))
          .thenAnswer((_) async => _testClient());
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});

      await repo.update(id: 'client-1', name: 'Jean Updated');

      verifyNever(() => mockRemote.update(any(), any()));
      verify(() => mockSyncService.queueOperation(
            operation: 'UPDATE_CLIENT',
            payload: any(named: 'payload'),
            entityId: 'client-1',
          )).called(1);
    });
  });

  group('archive — backend-first', () {
    test('online — archives on backend first, then locally', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.archive(any())).thenAnswer((_) async {});
      when(() => mockLocal.archive(any())).thenAnswer((_) async {});

      await repo.archive('client-1');

      verify(() => mockRemote.archive('client-1')).called(1);
      verify(() => mockLocal.archive('client-1')).called(1);
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

      await repo.archive('client-1');

      verify(() => mockLocal.archive('client-1')).called(1);
      verify(() => mockSyncService.queueOperation(
            operation: 'ARCHIVE_CLIENT',
            payload: {'clientId': 'client-1'},
            entityId: 'client-1',
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

      await repo.archive('client-1');

      verifyNever(() => mockRemote.archive(any()));
      verify(() => mockSyncService.queueOperation(
            operation: 'ARCHIVE_CLIENT',
            payload: {'clientId': 'client-1'},
            entityId: 'client-1',
          )).called(1);
    });
  });
}
