import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/features/team/data/datasource/local_employee_datasource.dart';
import 'package:keevo/features/team/data/datasource/remote_employee_datasource.dart';
import 'package:keevo/features/team/data/repository/employee_repository_impl.dart';
import 'package:keevo/features/team/domain/model/create_employee_result.dart';
import 'package:keevo/features/team/domain/model/employee_model.dart';
import 'package:mocktail/mocktail.dart';

class MockRemoteEmployeeDataSource extends Mock
    implements RemoteEmployeeDataSource {}

class MockLocalEmployeeDataSource extends Mock
    implements LocalEmployeeDataSource {}

class MockConnectivityService extends Mock implements ConnectivityService {}

class MockSyncService extends Mock implements SyncService {}

class FakeEmployeeModel extends Fake implements EmployeeModel {}

EmployeeModel _testEmployee({String id = 'emp-1', String status = 'ACTIVE'}) =>
    EmployeeModel(
      id: id,
      userId: 'user-1',
      firstName: 'Jean',
      lastName: 'Dupont',
      storeId: 'store-1',
      status: status,
      passwordChangeRequired: false,
      createdAt: DateTime(2026, 3, 17),
    );

void main() {
  late MockRemoteEmployeeDataSource mockRemote;
  late MockLocalEmployeeDataSource mockLocal;
  late MockConnectivityService mockConnectivity;
  late MockSyncService mockSyncService;
  late EmployeeRepositoryImpl repo;

  setUpAll(() => registerFallbackValue(FakeEmployeeModel()));

  setUp(() {
    mockRemote = MockRemoteEmployeeDataSource();
    mockLocal = MockLocalEmployeeDataSource();
    mockConnectivity = MockConnectivityService();
    mockSyncService = MockSyncService();
    repo = EmployeeRepositoryImpl(
      remote: mockRemote,
      local: mockLocal,
      connectivity: mockConnectivity,
      syncService: mockSyncService,
    );
  });

  group('createEmployee — remote only (password generation)', () {
    test('delegates to remote and upserts locally', () async {
      final employee = _testEmployee();
      final result = CreateEmployeeResult(
        employee: employee,
        temporaryPassword: 'Pass1234',
      );
      when(() => mockRemote.createEmployee(
            firstName: any(named: 'firstName'),
            lastName: any(named: 'lastName'),
            phoneNumber: any(named: 'phoneNumber'),
            storeId: any(named: 'storeId'),
          )).thenAnswer((_) async => result);
      when(() => mockLocal.upsert(any())).thenAnswer((_) async {});

      final actual = await repo.createEmployee(
        firstName: 'Jean',
        lastName: 'Dupont',
        phoneNumber: '+237600000000',
        storeId: 'store-1',
      );

      expect(actual.temporaryPassword, 'Pass1234');
      verify(() => mockRemote.createEmployee(
            firstName: 'Jean',
            lastName: 'Dupont',
            phoneNumber: '+237600000000',
            storeId: 'store-1',
          )).called(1);
      verify(() => mockLocal.upsert(employee)).called(1);
    });
  });

  group('reassignStore — backend-first', () {
    test('online — reassigns on backend first, upserts locally', () async {
      final reassigned = _testEmployee(id: 'emp-1').copyWith(storeId: 'store-2');
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.reassignStore(any(), any()))
          .thenAnswer((_) async => reassigned);
      when(() => mockLocal.upsert(any())).thenAnswer((_) async {});

      final result = await repo.reassignStore('emp-1', 'store-2');

      expect(result.storeId, 'store-2');
      verify(() => mockRemote.reassignStore('emp-1', 'store-2')).called(1);
      verify(() => mockLocal.upsert(any())).called(1);
    });

    test('online but backend fails — updates locally + queues sync', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.reassignStore(any(), any()))
          .thenThrow(Exception('Network'));
      when(() => mockLocal.updateStoreAssignment(any(), any()))
          .thenAnswer((_) async {});
      when(() => mockLocal.getById(any()))
          .thenAnswer((_) async => _testEmployee().copyWith(storeId: 'store-2'));
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});

      await repo.reassignStore('emp-1', 'store-2');

      verify(() => mockLocal.updateStoreAssignment('emp-1', 'store-2'))
          .called(1);
      verify(() => mockSyncService.queueOperation(
            operation: 'REASSIGN_EMPLOYEE',
            payload: {'employeeId': 'emp-1', 'newStoreId': 'store-2'},
            entityId: 'emp-1',
          )).called(1);
    });

    test('offline — updates locally + queues sync', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => false);
      when(() => mockLocal.updateStoreAssignment(any(), any()))
          .thenAnswer((_) async {});
      when(() => mockLocal.getById(any()))
          .thenAnswer((_) async => _testEmployee().copyWith(storeId: 'store-2'));
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});

      await repo.reassignStore('emp-1', 'store-2');

      verifyNever(() => mockRemote.reassignStore(any(), any()));
      verify(() => mockSyncService.queueOperation(
            operation: 'REASSIGN_EMPLOYEE',
            payload: any(named: 'payload'),
            entityId: 'emp-1',
          )).called(1);
    });
  });

  group('deactivateEmployee — backend-first', () {
    test('online — deactivates on backend first, updates local status',
        () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.deactivateEmployee(any()))
          .thenAnswer((_) async {});
      when(() => mockLocal.updateStatus(any(), any()))
          .thenAnswer((_) async {});

      await repo.deactivateEmployee('emp-1');

      verify(() => mockRemote.deactivateEmployee('emp-1')).called(1);
      verify(() => mockLocal.updateStatus('emp-1', 'DEACTIVATED')).called(1);
    });

    test('offline — updates local status + queues sync', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => false);
      when(() => mockLocal.updateStatus(any(), any()))
          .thenAnswer((_) async {});
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});

      await repo.deactivateEmployee('emp-1');

      verifyNever(() => mockRemote.deactivateEmployee(any()));
      verify(() => mockSyncService.queueOperation(
            operation: 'DEACTIVATE_EMPLOYEE',
            payload: {'employeeId': 'emp-1'},
            entityId: 'emp-1',
          )).called(1);
    });
  });

  group('reactivateEmployee — backend-first', () {
    test('online — reactivates on backend first, updates local status',
        () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.reactivateEmployee(any()))
          .thenAnswer((_) async {});
      when(() => mockLocal.updateStatus(any(), any()))
          .thenAnswer((_) async {});

      await repo.reactivateEmployee('emp-1');

      verify(() => mockRemote.reactivateEmployee('emp-1')).called(1);
      verify(() => mockLocal.updateStatus('emp-1', 'ACTIVE')).called(1);
    });

    test('offline — updates local status + queues sync', () async {
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => false);
      when(() => mockLocal.updateStatus(any(), any()))
          .thenAnswer((_) async {});
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});

      await repo.reactivateEmployee('emp-1');

      verifyNever(() => mockRemote.reactivateEmployee(any()));
      verify(() => mockSyncService.queueOperation(
            operation: 'REACTIVATE_EMPLOYEE',
            payload: {'employeeId': 'emp-1'},
            entityId: 'emp-1',
          )).called(1);
    });
  });

  group('listEmployees — reads from local with remote refresh', () {
    test('online — fetches remote, upserts all locally, returns remote list',
        () async {
      final employees = [_testEmployee(), _testEmployee(id: 'emp-2')];
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.listEmployees())
          .thenAnswer((_) async => employees);
      when(() => mockLocal.upsertAll(any())).thenAnswer((_) async {});

      final result = await repo.listEmployees();

      expect(result.length, 2);
      verify(() => mockLocal.upsertAll(employees)).called(1);
    });

    test('online but backend fails — falls back to local', () async {
      final localEmployees = [_testEmployee()];
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.listEmployees())
          .thenThrow(Exception('Network'));
      when(() => mockLocal.getAll())
          .thenAnswer((_) async => localEmployees);

      final result = await repo.listEmployees();

      expect(result.length, 1);
      verify(() => mockLocal.getAll()).called(1);
    });

    test('offline — reads from local only', () async {
      final localEmployees = [_testEmployee()];
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => false);
      when(() => mockLocal.getAll())
          .thenAnswer((_) async => localEmployees);

      final result = await repo.listEmployees();

      expect(result.length, 1);
      verifyNever(() => mockRemote.listEmployees());
    });
  });
}
