/// supplier_repository_offline_first_test.dart — Story 5.6 AC5 + AC10
///
/// TDD RED: verifies [SupplierRepositoryImpl] offline-first writes for
/// create() and update().
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/core/sync/sync_trigger_dispatcher.dart';
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
class MockSyncTriggerDispatcher extends Mock implements SyncTriggerDispatcher {}

class FakeSupplierModel extends Fake implements SupplierModel {}

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
}
