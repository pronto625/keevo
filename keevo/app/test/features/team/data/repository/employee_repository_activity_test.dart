// v1s-14-6 — FR67 activity reads: server-only, never cached nor queued.
// Offline or unreachable server → EmployeeActionRequiresConnectionException;
// a refusal (403/404) reaches the caller as-is.
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/features/team/data/datasource/local_employee_datasource.dart';
import 'package:keevo/features/team/data/datasource/remote_employee_datasource.dart';
import 'package:keevo/features/team/data/repository/employee_repository_impl.dart';
import 'package:keevo/features/team/domain/exception/employee_action_requires_connection_exception.dart';
import 'package:keevo/features/team/domain/model/employee_activity.dart';
import 'package:mocktail/mocktail.dart';

class _MockRemote extends Mock implements RemoteEmployeeDataSource {}

class _MockLocal extends Mock implements LocalEmployeeDataSource {}

class _MockConnectivity extends Mock implements ConnectivityService {}

class _MockSync extends Mock implements SyncService {}

void main() {
  late _MockRemote remote;
  late _MockConnectivity connectivity;
  late EmployeeRepositoryImpl repo;

  const activity = EmployeeActivity(employeeId: 'emp-1', userId: 'u-1');

  setUp(() {
    remote = _MockRemote();
    connectivity = _MockConnectivity();
    repo = EmployeeRepositoryImpl(
      remote: remote,
      local: _MockLocal(),
      connectivity: connectivity,
      syncService: _MockSync(),
    );
  });

  test('online → server activity', () async {
    when(() => connectivity.isOnline()).thenAnswer((_) async => true);
    when(() => remote.listActivity()).thenAnswer((_) async => [activity]);
    when(() => remote.getActivity('emp-1')).thenAnswer((_) async => activity);

    expect(await repo.listActivity(), [activity]);
    expect(await repo.getActivity('emp-1'), activity);
  });

  test('offline → EmployeeActionRequiresConnectionException, no call', () async {
    when(() => connectivity.isOnline()).thenAnswer((_) async => false);

    await expectLater(repo.listActivity(),
        throwsA(isA<EmployeeActionRequiresConnectionException>()));
    await expectLater(repo.getActivity('emp-1'),
        throwsA(isA<EmployeeActionRequiresConnectionException>()));
    verifyNever(() => remote.listActivity());
  });

  test('server unreachable → EmployeeActionRequiresConnectionException',
      () async {
    when(() => connectivity.isOnline()).thenAnswer((_) async => true);
    when(() => remote.getActivity('emp-1')).thenThrow(DioException(
      requestOptions: RequestOptions(path: '/x'),
      type: DioExceptionType.connectionError,
    ));

    await expectLater(repo.getActivity('emp-1'),
        throwsA(isA<EmployeeActionRequiresConnectionException>()));
  });

  test('refusal (403) rethrown as-is', () async {
    when(() => connectivity.isOnline()).thenAnswer((_) async => true);
    when(() => remote.listActivity()).thenThrow(DioException(
      requestOptions: RequestOptions(path: '/x'),
      type: DioExceptionType.badResponse,
      response: Response(
          requestOptions: RequestOptions(path: '/x'), statusCode: 403),
    ));

    await expectLater(repo.listActivity(), throwsA(isA<DioException>()));
  });

  test('EmployeeActivity.fromJson maps the backend payload', () {
    final a = EmployeeActivity.fromJson({
      'employeeId': 'emp-1',
      'userId': 'u-1',
      'lastLoginAt': '2026-10-12T08:00:00Z',
      'lastActivityAt': null,
      'todaySalesCount': 2,
      'todaySalesAmount': 10000,
      'weekSalesCount': 3,
      'weekSalesAmount': 13000,
      'activeSession': true,
      'inactive': false,
    });
    expect(a.lastLoginAt, DateTime.utc(2026, 10, 12, 8));
    expect(a.lastActivityAt, isNull);
    expect(a.todaySalesAmount, 10000);
    expect(a.weekSalesCount, 3);
    expect(a.activeSession, isTrue);
  });
}
