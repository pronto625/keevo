import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/data/datasource/remote_day_closure_datasource.dart';
import 'package:keevo/features/pos/domain/model/day_closure_model.dart';
import 'package:mocktail/mocktail.dart';

class MockDio extends Mock implements Dio {}

void main() {
  // The server must keep the local closure id (no duplicate row after pull)
  // and close the day the closure was made.
  test('pushClosure sends storeId, id and closedAt in UTC', () async {
    final dio = MockDio();
    when(() => dio.post(any(), data: any(named: 'data'))).thenAnswer(
      (_) async => Response(
        requestOptions: RequestOptions(path: '/api/v1/day-closures'),
        statusCode: 201,
      ),
    );
    final closure = DayClosure(
      id: 'closure-1',
      storeId: 'store-1',
      actorId: 'emp-1',
      closedAt: DateTime(2026, 9, 28, 21, 0),
      summary: const DayClosureSummary(
        totalSales: 0,
        totalRevenue: 0,
        cashAmount: 0,
        momoAmount: 0,
        topProductQty: 0,
        pendingSalesCount: 0,
        pendingSalesTotal: 0,
      ),
      isAutomatic: false,
      synced: false,
    );

    await RemoteDayClosureDataSource(dio).pushClosure(closure);

    final data = verify(() =>
            dio.post('/api/v1/day-closures', data: captureAny(named: 'data')))
        .captured
        .single as Map<String, dynamic>;
    expect(data['storeId'], 'store-1');
    expect(data['id'], 'closure-1');
    expect(data['closedAt'], closure.closedAt.toUtc().toIso8601String());
  });

  test('pushClosure returns the summary the server computed', () async {
    final dio = MockDio();
    when(() => dio.post(any(), data: any(named: 'data'))).thenAnswer(
      (_) async => Response(
        requestOptions: RequestOptions(path: '/api/v1/day-closures'),
        statusCode: 201,
        data: {
          'data': {
            'id': 'closure-1',
            'summary': {
              'totalSales': 4,
              'totalRevenue': 9000,
              'topProductId': 'p1',
              'topProductName': 'Savon',
              'topProductQty': 3,
              'cashAmount': 5000,
              'momoAmount': 4000,
              'pendingSalesCount': 0,
              'pendingSalesTotal': 0,
            },
          },
        },
      ),
    );
    final closure = DayClosure(
      id: 'closure-1',
      storeId: 'store-1',
      actorId: 'emp-1',
      closedAt: DateTime(2026, 9, 28, 21, 0),
      summary: const DayClosureSummary(
        totalSales: 3,
        totalRevenue: 7000,
        cashAmount: 3000,
        momoAmount: 4000,
        topProductId: 'p1',
        topProductQty: 2,
        topProductRevenue: 1200,
        pendingSalesCount: 0,
        pendingSalesTotal: 0,
      ),
      isAutomatic: false,
    );

    final summary = await RemoteDayClosureDataSource(dio).pushClosure(closure);

    expect(summary, isNotNull);
    expect(summary!.totalSales, 4);
    expect(summary.totalRevenue, 9000);
    expect(summary.cashAmount, 5000);
    expect(summary.topProductName, 'Savon');
    // Not sent by the server: kept from the local summary (same product).
    expect(summary.topProductRevenue, 1200);
  });
}
