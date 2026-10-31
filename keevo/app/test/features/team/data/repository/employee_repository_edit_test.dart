// Story 14.11 — updateEmployee / changeRole / setPassword offline rules.
//
// The backend sync handler only replays REASSIGN / (DE)ACTIVATE, so:
//  - a store-only edit goes through the existing REASSIGN_EMPLOYEE queue;
//  - any other edit, a role change or a password set need the server: a
//    refusal (4xx) is rethrown as-is, an unreachable server becomes
//    EmployeeActionRequiresConnectionException and nothing is queued.
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/team/data/datasource/local_employee_datasource.dart';
import 'package:keevo/features/team/data/datasource/remote_employee_datasource.dart';
import 'package:keevo/features/team/data/repository/employee_repository_impl.dart';
import 'package:keevo/features/team/domain/exception/employee_action_requires_connection_exception.dart';
import 'package:keevo/features/team/domain/model/employee_model.dart';
import 'package:keevo_core/sync/connectivity_service.dart';
import 'package:keevo_core/sync/sync_service.dart';
import 'package:mocktail/mocktail.dart';

class MockRemoteEmployeeDataSource extends Mock
    implements RemoteEmployeeDataSource {}

class MockLocalEmployeeDataSource extends Mock
    implements LocalEmployeeDataSource {}

class MockConnectivityService extends Mock implements ConnectivityService {}

class MockSyncService extends Mock implements SyncService {}

class FakeEmployeeModel extends Fake implements EmployeeModel {}

EmployeeModel _employee({String firstName = 'Jean', String storeId = 'store-1'}) =>
    EmployeeModel(
      id: 'emp-1',
      userId: 'user-1',
      firstName: firstName,
      lastName: 'Dupont',
      storeId: storeId,
      status: 'ACTIVE',
      passwordChangeRequired: false,
      createdAt: DateTime(2026, 3, 17),
    );

DioException _networkError() => DioException(
      requestOptions: RequestOptions(path: '/x'),
      type: DioExceptionType.connectionError,
    );

DioException _httpError(int status) => DioException(
      requestOptions: RequestOptions(path: '/x'),
      type: DioExceptionType.badResponse,
      response: Response(
        requestOptions: RequestOptions(path: '/x'),
        statusCode: status,
        data: {'error': 'refusé'},
      ),
    );

void main() {
  late MockRemoteEmployeeDataSource remote;
  late MockLocalEmployeeDataSource local;
  late MockConnectivityService connectivity;
  late MockSyncService sync;
  late EmployeeRepositoryImpl repo;

  setUpAll(() => registerFallbackValue(FakeEmployeeModel()));

  setUp(() {
    remote = MockRemoteEmployeeDataSource();
    local = MockLocalEmployeeDataSource();
    connectivity = MockConnectivityService();
    sync = MockSyncService();
    repo = EmployeeRepositoryImpl(
      remote: remote,
      local: local,
      connectivity: connectivity,
      syncService: sync,
    );
    when(() => local.upsert(any())).thenAnswer((_) async {});
    when(() => sync.queueOperation(
          operation: any(named: 'operation'),
          payload: any(named: 'payload'),
          entityId: any(named: 'entityId'),
        )).thenAnswer((_) async {});
  });

  void online(bool value) =>
      when(() => connectivity.isOnline()).thenAnswer((_) async => value);

  void stubUpdate({Object? error, EmployeeModel? result}) {
    final call = when(() => remote.updateEmployee(
          any(),
          firstName: any(named: 'firstName'),
          lastName: any(named: 'lastName'),
          phoneNumber: any(named: 'phoneNumber'),
          storeId: any(named: 'storeId'),
        ));
    if (error != null) {
      call.thenThrow(error);
    } else {
      call.thenAnswer((_) async => result ?? _employee());
    }
  }

  void verifyNothingQueued() => verifyNever(() => sync.queueOperation(
        operation: any(named: 'operation'),
        payload: any(named: 'payload'),
        entityId: any(named: 'entityId'),
      ));

  group('updateEmployee', () {
    test('online — PATCHes the changed fields then upserts locally', () async {
      online(true);
      final updated = _employee(firstName: 'Paul');
      stubUpdate(result: updated);

      final result = await repo.updateEmployee('emp-1', firstName: 'Paul');

      expect(result.firstName, 'Paul');
      verify(() => remote.updateEmployee('emp-1',
          firstName: 'Paul',
          lastName: null,
          phoneNumber: null,
          storeId: null)).called(1);
      verify(() => local.upsert(updated)).called(1);
      verifyNothingQueued();
    });

    test('store-only change goes through reassignStore (queueable)', () async {
      online(true);
      final moved = _employee(storeId: 'store-2');
      when(() => remote.reassignStore(any(), any()))
          .thenAnswer((_) async => moved);

      final result = await repo.updateEmployee('emp-1', storeId: 'store-2');

      expect(result.storeId, 'store-2');
      verify(() => remote.reassignStore('emp-1', 'store-2')).called(1);
      verifyNever(() => remote.updateEmployee(any(),
          firstName: any(named: 'firstName'),
          lastName: any(named: 'lastName'),
          phoneNumber: any(named: 'phoneNumber'),
          storeId: any(named: 'storeId')));
    });

    test('store-only change offline — local update + REASSIGN_EMPLOYEE queued',
        () async {
      online(false);
      when(() => local.updateStoreAssignment(any(), any()))
          .thenAnswer((_) async {});
      when(() => local.getById('emp-1'))
          .thenAnswer((_) async => _employee(storeId: 'store-2'));

      final result = await repo.updateEmployee('emp-1', storeId: 'store-2');

      expect(result.storeId, 'store-2');
      verify(() => sync.queueOperation(
            operation: 'REASSIGN_EMPLOYEE',
            payload: {'employeeId': 'emp-1', 'newStoreId': 'store-2'},
            entityId: 'emp-1',
          )).called(1);
    });

    test('name change offline — requires connection, nothing sent or queued',
        () async {
      online(false);

      await expectLater(
        repo.updateEmployee('emp-1', firstName: 'Paul', storeId: 'store-2'),
        throwsA(isA<EmployeeActionRequiresConnectionException>()),
      );

      verifyNever(() => remote.updateEmployee(any(),
          firstName: any(named: 'firstName'),
          lastName: any(named: 'lastName'),
          phoneNumber: any(named: 'phoneNumber'),
          storeId: any(named: 'storeId')));
      verifyNever(() => local.upsert(any()));
      verifyNothingQueued();
    });

    for (final (label, error) in [
      ('network error', _networkError()),
      ('HTTP 503', _httpError(503)),
    ]) {
      test('$label — requires connection, nothing queued', () async {
        online(true);
        stubUpdate(error: error);

        await expectLater(
          repo.updateEmployee('emp-1', phoneNumber: '+237600000000'),
          throwsA(isA<EmployeeActionRequiresConnectionException>()),
        );

        verifyNever(() => local.upsert(any()));
        verifyNothingQueued();
      });
    }

    for (final status in [403, 404, 409, 422]) {
      test('HTTP $status — refusal rethrown, nothing local or queued',
          () async {
        online(true);
        stubUpdate(error: _httpError(status));

        await expectLater(
          repo.updateEmployee('emp-1', lastName: 'Martin'),
          throwsA(isA<DioException>()),
        );

        verifyNever(() => local.upsert(any()));
        verifyNothingQueued();
      });
    }
  });

  group('changeRole', () {
    test('online — PATCHes the role', () async {
      online(true);
      when(() => remote.changeRole(any(), any())).thenAnswer((_) async {});

      await repo.changeRole('emp-1', 'OWNER');

      verify(() => remote.changeRole('emp-1', 'OWNER')).called(1);
      verifyNothingQueued();
    });

    test('offline — requires connection, remote never called', () async {
      online(false);

      await expectLater(repo.changeRole('emp-1', 'OWNER'),
          throwsA(isA<EmployeeActionRequiresConnectionException>()));

      verifyNever(() => remote.changeRole(any(), any()));
      verifyNothingQueued();
    });

    test('network error — requires connection, nothing queued', () async {
      online(true);
      when(() => remote.changeRole(any(), any())).thenThrow(_networkError());

      await expectLater(repo.changeRole('emp-1', 'OWNER'),
          throwsA(isA<EmployeeActionRequiresConnectionException>()));

      verifyNothingQueued();
    });

    test('HTTP 403 (last owner / own role) — refusal rethrown', () async {
      online(true);
      when(() => remote.changeRole(any(), any())).thenThrow(_httpError(403));

      await expectLater(
          repo.changeRole('emp-1', 'EMPLOYEE'), throwsA(isA<DioException>()));

      verifyNothingQueued();
    });
  });

  group('setPassword', () {
    test('online — POSTs the password', () async {
      online(true);
      when(() => remote.setPassword(any(), any())).thenAnswer((_) async {});

      await repo.setPassword('emp-1', 'Secret123');

      verify(() => remote.setPassword('emp-1', 'Secret123')).called(1);
      verifyNothingQueued();
    });

    test('offline — requires connection, remote never called', () async {
      online(false);

      await expectLater(repo.setPassword('emp-1', 'Secret123'),
          throwsA(isA<EmployeeActionRequiresConnectionException>()));

      verifyNever(() => remote.setPassword(any(), any()));
      verifyNothingQueued();
    });

    test('network error — requires connection, nothing queued', () async {
      online(true);
      when(() => remote.setPassword(any(), any())).thenThrow(_networkError());

      await expectLater(repo.setPassword('emp-1', 'Secret123'),
          throwsA(isA<EmployeeActionRequiresConnectionException>()));

      verifyNothingQueued();
    });

    test('HTTP 422 (weak password) — refusal rethrown', () async {
      online(true);
      when(() => remote.setPassword(any(), any())).thenThrow(_httpError(422));

      await expectLater(
          repo.setPassword('emp-1', 'short'), throwsA(isA<DioException>()));

      verifyNothingQueued();
    });
  });
}
