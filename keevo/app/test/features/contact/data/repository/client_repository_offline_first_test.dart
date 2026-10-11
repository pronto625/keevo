/// client_repository_offline_first_test.dart — Story 5.6 AC4 + AC10
///
/// TDD RED: verifies [ClientRepositoryImpl] offline-first writes for
/// create() and update().
library;

import 'dart:ffi';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/core/sync/sync_trigger_dispatcher.dart';
import 'package:keevo/features/contact/data/datasource/local_client_datasource.dart';
import 'package:keevo/features/contact/data/datasource/remote_client_datasource.dart';
import 'package:keevo/features/contact/data/repository/client_repository_impl.dart';
import 'package:keevo/features/contact/domain/model/client_model.dart';
import 'package:mocktail/mocktail.dart';
import 'package:sqlite3/open.dart';

class MockLocalClientDataSource extends Mock implements LocalClientDataSource {}
class MockRemoteClientDataSource extends Mock implements RemoteClientDataSource {}
class MockConnectivityService extends Mock implements ConnectivityService {}
class MockSyncService extends Mock implements SyncService {}
class MockSyncTriggerDispatcher extends Mock implements SyncTriggerDispatcher {}

class FakeClientModel extends Fake implements ClientModel {}

void _overrideSqlite3ForLinuxTesting() {
  if (!Platform.isLinux) return;
  open.overrideFor(OperatingSystem.linux, () {
    try {
      return DynamicLibrary.open('libsqlite3.so');
    } catch (_) {
      return DynamicLibrary.open('libsqlite3.so.0');
    }
  });
}

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

  group('archive() offline-first', () {
    test('archive_writesLocalFirst_queuesArchive_evenWhenOnline', () async {
      final callOrder = <String>[];
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockLocal.archive(any())).thenAnswer((_) async {
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

      await repo.archive('client-uuid-1');

      expect(callOrder, ['local', 'queue']);
      verifyNever(() => mockRemote.archive(any()));
      verify(() => mockSyncService.queueOperation(
            operation: 'ARCHIVE_CLIENT',
            payload: {'clientId': 'client-uuid-1'},
            entityId: 'client-uuid-1',
          )).called(1);
      verify(() => mockDispatcher.triggerPushIfIdle()).called(1);
    });
  });

  group('syncFromRemote()', () {
    test('syncFromRemote_rethrowsWhenRemoteFails', () async {
      when(() => mockRemote.getAll(includeArchived: true))
          .thenThrow(Exception('network'));

      await expectLater(repo.syncFromRemote(), throwsA(isA<Exception>()));
      verifyNever(() => mockLocal.upsert(any()));
    });
  });

  // v1s-13-8: with a db, the local write and its queue entry are atomic.
  group('v1s-13-8 — local write + queue in one Drift transaction', () {
    late AppDatabase db;
    late LocalClientDataSource realLocal;
    late ClientRepositoryImpl atomicRepo;

    setUpAll(_overrideSqlite3ForLinuxTesting);

    setUp(() {
      db = AppDatabase.forTesting();
      realLocal = LocalClientDataSource(db);
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenThrow(Exception('queue failure'));
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);
      atomicRepo = ClientRepositoryImpl(
        local: realLocal,
        remote: mockRemote,
        connectivity: mockConnectivity,
        syncService: mockSyncService,
        syncTriggerDispatcher: mockDispatcher,
        db: db,
      );
    });

    tearDown(() async => db.close());

    test('create: queue failure leaves no row', () async {
      await expectLater(
        atomicRepo.create(name: 'Orphelin', phone: '+237690000001'),
        throwsA(isA<Exception>()),
      );
      expect(await realLocal.getAll(includeArchived: true), isEmpty);
    });

    test('update: queue failure leaves the original row', () async {
      final now = DateTime(2026);
      await realLocal.upsert(ClientModel(
        id: 'c-1',
        name: 'Original',
        phone: '+237690000002',
        createdAt: now,
        updatedAt: now,
      ));

      await expectLater(
        atomicRepo.update(id: 'c-1', name: 'Renamed'),
        throwsA(isA<Exception>()),
      );
      final row = await realLocal.getById('c-1');
      expect(row!.name, 'Original');
    });
  });
}
