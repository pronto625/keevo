import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:uuid/uuid.dart';

import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/core/sync/sync_trigger_dispatcher.dart';
import 'package:keevo/features/stores/data/datasource/local_store_datasource.dart';
import 'package:keevo/features/stores/data/datasource/remote_store_datasource.dart';
import 'package:keevo/features/stores/data/repository/store_repository_impl.dart';
import 'package:keevo/features/stores/domain/exception/store_exception.dart';
import 'package:keevo/features/stores/domain/model/store_model.dart';
import 'package:keevo/features/stores/domain/model/store_type.dart';

class _MockLocalDataSource extends Mock implements LocalStoreDataSource {}

class _MockRemoteDataSource extends Mock implements RemoteStoreDataSource {}

class _MockSyncService extends Mock implements SyncService {}

class _MockSyncTriggerDispatcher extends Mock implements SyncTriggerDispatcher {}

void main() {
  late _MockLocalDataSource local;
  late _MockRemoteDataSource remote;
  late _MockSyncService syncService;
  late _MockSyncTriggerDispatcher syncTriggerDispatcher;
  late StoreRepositoryImpl repo;

  final now = DateTime(2025, 1, 1, 10, 0);
  final sampleStore = StoreModel(
    id: 'store-1',
    name: 'Boutique Test',
    type: StoreType.store,
    createdAt: now,
    updatedAt: now,
  );

  setUpAll(() {
    registerFallbackValue(StoreType.store);
    registerFallbackValue(StoreModel(
      id: 'fallback',
      name: 'fallback',
      createdAt: DateTime(2025),
      updatedAt: DateTime(2025),
    ));
  });

  setUp(() {
    local = _MockLocalDataSource();
    remote = _MockRemoteDataSource();
    syncService = _MockSyncService();
    syncTriggerDispatcher = _MockSyncTriggerDispatcher();
    repo = StoreRepositoryImpl(
      local: local,
      remote: remote,
      syncService: syncService,
      syncTriggerDispatcher: syncTriggerDispatcher,
    );
    // Default no-op stubs for sync infrastructure (existing tests don't hit offline path).
    when(() => syncService.queueOperation(
          operation: any(named: 'operation'),
          payload: any(named: 'payload'),
          entityId: any(named: 'entityId'),
        )).thenAnswer((_) async {});
    when(syncTriggerDispatcher.triggerPushIfIdle).thenReturn(null);
  });

  group('StoreRepositoryImpl (Story 3.1 — Task 18.4)', () {
    test('getStores() returns local data', () async {
      when(() => local.getAll(includeInactive: false))
          .thenAnswer((_) async => [sampleStore]);

      final result = await repo.getStores();
      expect(result.length, 1);
      verifyNever(() => remote.getAll());
    });

    test('createStore() calls remote then upserts locally', () async {
      when(() => remote.create(
            name: any(named: 'name'),
            type: any(named: 'type'),
            address: any(named: 'address'),
            phone: any(named: 'phone'),
          )).thenAnswer((_) async => sampleStore);
      when(() => local.upsert(sampleStore)).thenAnswer((_) async => sampleStore);

      final result = await repo.createStore(
        name: 'Boutique Test',
        type: StoreType.store,
      );
      expect(result, sampleStore);
      verify(() => remote.create(
            name: 'Boutique Test',
            type: StoreType.store,
            address: null,
            phone: null,
          )).called(1);
      verify(() => local.upsert(sampleStore)).called(1);
    });

    test('createStore() rethrows StoreException.planLimitExceeded', () async {
      when(() => remote.create(
            name: any(named: 'name'),
            type: any(named: 'type'),
            address: any(named: 'address'),
            phone: any(named: 'phone'),
          )).thenThrow(StoreException.planLimitExceeded());

      expect(
        () => repo.createStore(name: 'X', type: StoreType.store),
        throwsA(isA<StoreException>().having(
            (e) => e.domainCode, 'domainCode', 'PLAN_LIMIT_EXCEEDED')),
      );
    });

    test('syncFromRemote() fetches all and upserts each locally', () async {
      when(() => remote.getAll(includeInactive: true))
          .thenAnswer((_) async => [sampleStore]);
      when(() => local.upsert(any())).thenAnswer((_) async => sampleStore);

      await repo.syncFromRemote();
      verify(() => local.upsert(sampleStore)).called(1);
    });

    group('Story v1s-16-6 — offline sync_queue', () {
      test('createStore() offline queues CREATE_STORE with a real UUID id', () async {
        when(() => remote.create(
              name: any(named: 'name'),
              type: any(named: 'type'),
              address: any(named: 'address'),
              phone: any(named: 'phone'),
            )).thenThrow(Exception('network'));
        when(() => local.upsert(any())).thenAnswer((_) async => sampleStore);

        await repo.createStore(name: 'Test', type: StoreType.store);

        verify(() => local.upsert(any())).called(1);
        final captured = verify(() => syncService.queueOperation(
              operation: 'CREATE_STORE',
              payload: captureAny(named: 'payload'),
              entityId: captureAny(named: 'entityId'),
            )).captured;
        final payload = captured[0] as Map<String, dynamic>;
        expect(payload['name'], 'Test');
        expect(payload['type'], 'STORE');
        // Must be a real UUID, not 'local-...'
        final id = payload['id'] as String;
        expect(id, isNot(startsWith('local-')));
        expect(Uuid.isValidUUID(fromString: id), isTrue);
        verify(syncTriggerDispatcher.triggerPushIfIdle).called(1);
      });

      test('updateStore() offline queues UPDATE_STORE', () async {
        when(() => remote.update(
              storeId: any(named: 'storeId'),
              name: any(named: 'name'),
              address: any(named: 'address'),
              phone: any(named: 'phone'),
            )).thenThrow(Exception('network'));
        when(() => local.getById('s1'))
            .thenAnswer((_) async => sampleStore);
        when(() => local.upsert(any())).thenAnswer((_) async => sampleStore);

        await repo.updateStore(storeId: 's1', name: 'Updated');

        verify(() => local.upsert(any())).called(1);
        verify(() => syncService.queueOperation(
              operation: 'UPDATE_STORE',
              payload: any(named: 'payload'),
              entityId: 's1',
            )).called(1);
        verify(syncTriggerDispatcher.triggerPushIfIdle).called(1);
      });

      test('deactivateStore() offline queues DEACTIVATE_STORE', () async {
        when(() => remote.deactivate('s1'))
            .thenThrow(Exception('network'));
        when(() => local.deactivate('s1')).thenAnswer((_) async {});
        when(() => local.getById('s1'))
            .thenAnswer((_) async => sampleStore);

        await repo.deactivateStore('s1');

        verify(() => local.deactivate('s1')).called(1);
        verify(() => syncService.queueOperation(
              operation: 'DEACTIVATE_STORE',
              payload: {'storeId': 's1'},
              entityId: 's1',
            )).called(1);
        verify(syncTriggerDispatcher.triggerPushIfIdle).called(1);
      });
    });
  });
}
