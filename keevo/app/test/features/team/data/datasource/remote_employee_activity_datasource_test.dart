import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/team/data/datasource/remote_employee_datasource.dart';

class MockDio extends Mock implements Dio {}

Map<String, dynamic> _activityJson(String id) => {
      'employeeId': id,
      'userId': 'u-$id',
      'lastLoginAt': '2026-10-12T08:00:00Z',
      'lastActivityAt': '2026-10-13T09:30:00Z',
      'todaySalesCount': 2,
      'todaySalesAmount': 10000,
      'weekSalesCount': 3,
      'weekSalesAmount': 13000,
      'activeSession': true,
      'inactive': false,
    };

/// v1s-14-6 — FR67 activity GET paths and `data` envelope parsing.
void main() {
  late MockDio dio;
  late RemoteEmployeeDataSource ds;

  setUp(() {
    dio = MockDio();
    ds = RemoteEmployeeDataSource(dio: dio);
  });

  Response<Map<String, dynamic>> ok(String path, Object data) => Response(
        requestOptions: RequestOptions(path: path),
        statusCode: 200,
        data: {'success': true, 'data': data},
      );

  test('listActivity GETs /api/v1/employees/activity and parses the list',
      () async {
    when(() => dio.get<Map<String, dynamic>>('/api/v1/employees/activity'))
        .thenAnswer((_) async => ok('/api/v1/employees/activity',
            [_activityJson('emp-1'), _activityJson('emp-2')]));

    final list = await ds.listActivity();

    expect(list.map((a) => a.employeeId), ['emp-1', 'emp-2']);
    expect(list.first.todaySalesAmount, 10000);
    expect(list.first.lastActivityAt, DateTime.utc(2026, 10, 13, 9, 30));
  });

  test('getActivity GETs /api/v1/employees/{id}/activity and parses data',
      () async {
    when(() =>
            dio.get<Map<String, dynamic>>('/api/v1/employees/emp-1/activity'))
        .thenAnswer((_) async =>
            ok('/api/v1/employees/emp-1/activity', _activityJson('emp-1')));

    final a = await ds.getActivity('emp-1');

    expect(a.employeeId, 'emp-1');
    expect(a.weekSalesCount, 3);
    expect(a.weekSalesAmount, 13000);
    expect(a.activeSession, isTrue);
    expect(a.lastLoginAt, DateTime.utc(2026, 10, 12, 8));
  });
}
