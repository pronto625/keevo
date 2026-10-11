/// supplier_repository_offline_first_test.dart — Story 5.6 AC5 + AC10
///
/// TDD RED: verifies [SupplierRepositoryImpl] offline-first writes for
/// create() and update().
library;

import 'dart:ffi';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/core/sync/sync_trigger_dispatcher.dart';
import 'package:keevo/features/contact/data/datasource/local_supplier_datasource.dart';
import 'package:keevo/features/contact/data/datasource/remote_supplier_datasource.dart';
import 'package:keevo/features/contact/data/repository/supplier_repository_impl.dart';
import 'package:keevo/features/contact/domain/model/supplier_model.dart';
import 'package:mocktail/mocktail.dart';
import 'package:sqlite3/open.dart';

class MockLocalSupplierDataSource extends Mock
    implements LocalSupplierDataSource {}
class MockRemoteSupplierDataSource extends Mock
    implements RemoteSupplierDataSource {}
class MockConnectivityService extends Mock implements ConnectivityService {}
class MockSyncService extends Mock implements SyncService {}
class MockSyncTriggerDispatcher extends Mock implements SyncTriggerDispatcher {}

class FakeSupplierModel extends Fake implements SupplierModel {}

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
  late MockLocalSupplierDataSource mockLocal;
  late MockRemoteSupplierDataSource mockRemote;
  late MockConnectivityService mockConnectivity;
  late MockSyncService mockSyncService;
  late MockSyncTriggerDispatcher mockDispatcher;
  late SupplierRepositoryImpl repo;

  setUpAll(() {
    registerFallbackValue(FakeSupplierModel());
    registerFallbackValue(<String, dynamic>{});
  });

  setUp(() {
    mockLocal = MockLocalSupplierDataSource();
    mockRemote = MockRemoteSupplierDataSource();
    mockConnectivity = MockConnectivityService();
    mockSyncService = MockSyncService();
    mockDispatcher = MockSyncTriggerDispatcher();
    repo = SupplierRepositoryImpl(
      local: mockLocal,
      remote: mockRemote,
      connectivity: mockConnectivity,
      syncService: mockSyncService,
      syncTriggerDispatcher: mockDispatcher,
    );
  });

  group('AC5 — create() offline-first', () {
    test('create_writesLocalFirst_withUUID_queuesCreateSupplier', () async {
      final callOrder = <String>[];
      when(() => mockLocal.upsert(any())).thenAnswer((invocation) async {
        callOrder.add('local');
        // Passthrough: return the model passed by the repo so we can verify the UUID.
        return invocation.positionalArguments[0] as SupplierModel;
      });
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {
        callOrder.add('queue');
      });
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      final supplier = await repo.create(
        name: 'Grossiste Abidjan',
        phone: '+2250700000002',
      );

      expect(supplier.id.length, 36, reason: 'Should use UUID v4, not millis');
      expect(callOrder, ['local', 'queue']);
      verifyNever(() => mockRemote.create(any()));
      verify(() => mockSyncService.queueOperation(
            operation: 'CREATE_SUPPLIER',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).called(1);
    });
  });

  group('AC5 — update() offline-first', () {
    test('update_writesLocalFirst_queuesUpdateSupplier', () async {
      final existing = SupplierModel(
        id: 'supplier-uuid-1',
        name: 'Old Name',
        phone: '+2250700000002',
        productIds: const [],
        createdAt: DateTime(2026, 1, 1),
        updatedAt: DateTime(2026, 1, 1),
      );
      final callOrder = <String>[];

      when(() => mockLocal.getById('supplier-uuid-1'))
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

      await repo.update(id: 'supplier-uuid-1', name: 'New Name');

      expect(callOrder, ['local', 'queue']);
      verifyNever(() => mockRemote.update(any(), any()));
      verify(() => mockSyncService.queueOperation(
            operation: 'UPDATE_SUPPLIER',
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

      await repo.archive('supplier-uuid-1');

      expect(callOrder, ['local', 'queue']);
      verifyNever(() => mockRemote.archive(any()));
      verify(() => mockSyncService.queueOperation(
            operation: 'ARCHIVE_SUPPLIER',
            payload: {'supplierId': 'supplier-uuid-1'},
            entityId: 'supplier-uuid-1',
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
    late LocalSupplierDataSource realLocal;
    late SupplierRepositoryImpl atomicRepo;

    setUpAll(_overrideSqlite3ForLinuxTesting);

    setUp(() {
      db = AppDatabase.forTesting();
      realLocal = LocalSupplierDataSource(db);
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenThrow(Exception('queue failure'));
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);
      atomicRepo = SupplierRepositoryImpl(
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
      await realLocal.upsert(SupplierModel(
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
