/// client_repository_offline_first_test.dart — Story 5.6 AC4 + AC10
///
/// TDD RED: verifies [ClientRepositoryImpl] offline-first writes for
/// create() and update().
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/core/sync/sync_trigger_dispatcher.dart';
import 'package:keevo/features/contact/data/datasource/local_client_datasource.dart';
import 'package:keevo/features/contact/data/datasource/remote_client_datasource.dart';
import 'package:keevo/features/contact/data/repository/client_repository_impl.dart';
import 'package:keevo/features/contact/domain/model/client_model.dart';
import 'package:mocktail/mocktail.dart';

class MockLocalClientDataSource extends Mock implements LocalClientDataSource {}
class MockRemoteClientDataSource extends Mock implements RemoteClientDataSource {}
class MockConnectivityService extends Mock implements ConnectivityService {}
class MockSyncService extends Mock implements SyncService {}
class MockSyncTriggerDispatcher extends Mock implements SyncTriggerDispatcher {}

class FakeClientModel extends Fake implements ClientModel {}

void main() {
  late MockLocalClientDataSource mockLocal;
  late MockRemoteClientDataSource mockRemote;
  late MockConnectivityService mockConnectivity;
  late MockSyncService mockSyncService;
  late MockSyncTriggerDispatcher mockDispatcher;
  late ClientRepositoryImpl repo;

  setUpAll(() {
    registerFallbackValue(FakeClientModel());
    registerFallbackValue(<String, dynamic>{});
  });

  setUp(() {
    mockLocal = MockLocalClientDataSource();
    mockRemote = MockRemoteClientDataSource();
    mockConnectivity = MockConnectivityService();
    mockSyncService = MockSyncService();
    mockDispatcher = MockSyncTriggerDispatcher();
    repo = ClientRepositoryImpl(
      local: mockLocal,
      remote: mockRemote,
      connectivity: mockConnectivity,
      syncService: mockSyncService,
      syncTriggerDispatcher: mockDispatcher,
    );
  });

  group('AC4 — create() offline-first', () {
    test('create_writesLocalFirst_withUUID_queuesCreateClient', () async {
      final callOrder = <String>[];
      when(() => mockLocal.upsert(any())).thenAnswer((invocation) async {
        callOrder.add('local');
        // Passthrough: return the model passed by the repo so we can verify the UUID.
        return invocation.positionalArguments[0] as ClientModel;
      });
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {
        callOrder.add('queue');
      });
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      final client = await repo.create(
        name: 'Kouassi Jean',
        phone: '+2250700000001',
      );

      // UUID must be 36 chars, NOT millis-based (13 digit string)
      expect(client.id.length, 36, reason: 'Should use UUID v4, not millis');
      expect(callOrder, ['local', 'queue'],
          reason: 'local write must precede queue');
      verifyNever(() => mockRemote.create(any()));
      verify(() => mockSyncService.queueOperation(
            operation: 'CREATE_CLIENT',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).called(1);
    });
  });

  group('AC4 — update() offline-first', () {
    test('update_writesLocalFirst_queuesUpdateClient', () async {
      final existing = ClientModel(
        id: 'client-uuid-1',
        name: 'Old Name',
        phone: '+2250700000001',
        createdAt: DateTime(2026, 1, 1),
        updatedAt: DateTime(2026, 1, 1),
      );
      final callOrder = <String>[];

      when(() => mockLocal.getById('client-uuid-1'))
          .thenAnswer((_) async => existing);
      when(() => mockLocal.upsert(any())).thenAnswer((_) async {
        callOrder.add('local');
        return existing.copyWith(name: 'New Name');
      });
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {
        callOrder.add('queue');
      });
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      await repo.update(id: 'client-uuid-1', name: 'New Name');

      expect(callOrder, ['local', 'queue']);
      verifyNever(() => mockRemote.update(any(), any()));
      verify(() => mockSyncService.queueOperation(
            operation: 'UPDATE_CLIENT',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).called(1);
    });
  });
}
