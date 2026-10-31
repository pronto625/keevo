/// client_repository_offline_first_test.dart — Story 5.6 AC4 + AC10
///
/// TDD RED: verifies [ClientRepositoryImpl] offline-first writes for
/// create() and update().
library;

import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart' show Value;
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/contact/data/datasource/local_client_datasource.dart';
import 'package:keevo/features/contact/data/datasource/remote_client_datasource.dart';
import 'package:keevo/features/contact/data/repository/client_repository_impl.dart';
import 'package:keevo/features/contact/domain/model/client_model.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/sync/sync_service.dart';
import 'package:keevo_core/sync/sync_trigger_dispatcher.dart';
import 'package:mocktail/mocktail.dart';
import 'package:sqlite3/open.dart';

class MockLocalClientDataSource extends Mock implements LocalClientDataSource {}
class MockRemoteClientDataSource extends Mock implements RemoteClientDataSource {}
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
    mockSyncService = MockSyncService();
    mockDispatcher = MockSyncTriggerDispatcher();
    repo = ClientRepositoryImpl(
      local: mockLocal,
      remote: mockRemote,
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

  group('unarchive() offline-first', () {
    test('unarchive_writesLocalFirst_queuesUnarchive', () async {
      final callOrder = <String>[];
      when(() => mockLocal.unarchive(any())).thenAnswer((_) async {
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

      await repo.unarchive('client-uuid-1');

      expect(callOrder, ['local', 'queue']);
      verify(() => mockSyncService.queueOperation(
            operation: 'UNARCHIVE_CLIENT',
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

  // v1s-16-14: user-typed `%` / `_` match literally in local search.
  group('LocalClientDataSource.search — literal LIKE wildcards', () {
    late AppDatabase db;
    late LocalClientDataSource local;

    setUpAll(_overrideSqlite3ForLinuxTesting);

    setUp(() async {
      db = AppDatabase.forTesting();
      local = LocalClientDataSource(db);
      final now = DateTime.now();
      for (final (id, name, phone) in [
        ('c1', 'Remise 50%', '+237690000001'),
        ('c2', 'Client 500', '+237690000002'),
      ]) {
        await db.into(db.clients).insert(ClientsCompanion.insert(
              id: id,
              name: name,
              phone: phone,
              createdAt: now,
              updatedAt: now,
            ));
      }
    });

    tearDown(() async => db.close());

    test('"50%" returns only "Remise 50%"', () async {
      final results = await local.search('50%');
      expect(results.map((c) => c.name), ['Remise 50%']);
    });

    test('phone match is literal too', () async {
      final results = await local.search('0_01');
      expect(results, isEmpty);
    });

    // v1s-16-16: case- and accent-insensitive.
    test('"helene" / "HÉLÈNE" find "Hélène"', () async {
      final now = DateTime.now();
      await db.into(db.clients).insert(ClientsCompanion.insert(
            id: 'c3',
            name: 'Hélène',
            phone: '+237690000003',
            createdAt: now,
            updatedAt: now,
          ));
      for (final q in ['helene', 'HÉLÈNE']) {
        final results = await local.search(q);
        expect(results.map((c) => c.name), ['Hélène'], reason: q);
      }
    });

    // v1s-16-13: search respects « Voir les archivés ».
    test('archived clients match only with includeArchived', () async {
      final now = DateTime.now();
      await db.into(db.clients).insert(ClientsCompanion.insert(
            id: 'c4',
            name: 'Ancien Client',
            phone: '+237690000004',
            archived: const Value(true),
            createdAt: now,
            updatedAt: now,
          ));
      expect(await local.search('ancien'), isEmpty);
      final all = await local.search('ancien', includeArchived: true);
      expect(all.map((c) => c.name), ['Ancien Client']);
    });
  });

  group('Story 13.8b — syncFromRemote()', () {
    test('a client with a pending or held operation keeps its local version',
        () async {
      ClientModel client(String id, String name) => ClientModel(
            id: id,
            name: name,
            phone: '690000000',
            createdAt: DateTime(2026, 10, 30),
            updatedAt: DateTime(2026, 10, 30),
          );
      when(() => mockRemote.getAll(includeArchived: true)).thenAnswer(
          (_) async => [client('held-1', 'Serveur'), client('c-2', 'Paul')]);
      when(() => mockSyncService.unsyncedEntityIds())
          .thenAnswer((_) async => {'held-1'});
      when(() => mockLocal.upsert(any()))
          .thenAnswer((inv) async => inv.positionalArguments[0] as ClientModel);

      await repo.syncFromRemote();

      final upserted = verify(() => mockLocal.upsert(captureAny()))
          .captured
          .cast<ClientModel>()
          .map((c) => c.id);
      expect(upserted, ['c-2']);
    });
  });
}
