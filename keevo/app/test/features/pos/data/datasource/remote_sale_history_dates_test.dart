import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/pos/data/datasource/remote_sale_datasource.dart';
import 'package:keevo/features/pos/domain/model/sales_history_filter.dart';

class _MockDio extends Mock implements Dio {}

/// « Mes Ventes » bug: a sale made at 00:56 in Douala (UTC+1) came back from
/// the server as 23:56Z and was displayed as 23:56. Dates must be local.
void main() {
  late _MockDio dio;
  late RemoteSaleDataSource datasource;

  const utcIso = '2026-10-13T23:56:03Z';
  final instant = DateTime.parse(utcIso);

  setUp(() {
    dio = _MockDio();
    datasource = RemoteSaleDataSource(dio);
  });

  test('getSalesHistory() exposes occurredAt/createdAt in local time',
      () async {
    when(() => dio.get<Map<String, dynamic>>(
          '/api/v1/sales/history',
          queryParameters: any(named: 'queryParameters'),
        )).thenAnswer((_) async => Response(
          data: {
            'data': {
              'content': [
                {
                  'id': 'sale-1',
                  'storeId': 'store-1',
                  'employeeId': 'emp-1',
                  'paymentMode': 'CASH',
                  'totalAmount': 1000,
                  'status': 'COMPLETED',
                  'items': <dynamic>[],
                  'occurredAt': utcIso,
                  'createdAt': utcIso,
                },
              ],
            },
          },
          statusCode: 200,
          requestOptions: RequestOptions(path: '/api/v1/sales/history'),
        ));

    final sales = await datasource.getSalesHistory(SalesHistoryFilter(
      storeId: 'store-1',
      from: DateTime(2026, 10, 13),
      to: DateTime(2026, 10, 14, 23, 59, 59),
      filterType: DateFilterType.custom,
    ));

    final sale = sales.single;
    expect(sale.occurredAt.isUtc, isFalse);
    expect(sale.occurredAt.isAtSameMomentAs(instant), isTrue);
    expect(sale.occurredAt.hour, instant.toLocal().hour);
    expect(sale.createdAt.isUtc, isFalse);
    expect(sale.createdAt.isAtSameMomentAs(instant), isTrue);
  });
}
