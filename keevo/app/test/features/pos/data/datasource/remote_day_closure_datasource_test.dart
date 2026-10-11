import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/data/datasource/remote_day_closure_datasource.dart';
import 'package:keevo/features/pos/domain/model/sales_history_filter.dart';
import 'package:mocktail/mocktail.dart';

class MockDio extends Mock implements Dio {}

Map<String, dynamic> _saleJson(String id) => {
      'id': id,
      'storeId': 'store-1',
      'employeeId': 'emp-1',
      'paymentMode': 'CASH',
      'totalAmount': 1500,
      'discountAmount': 0,
      'status': 'COMPLETED',
      'occurredAt': '2026-03-17T10:00:00Z',
      'createdAt': '2026-03-17T10:00:00Z',
      'items': [
        {
          'id': 'item-$id',
          'productId': 'p1',
          'productName': 'Savon',
          'catalogueUnitPrice': 600,
          'appliedUnitPrice': 500,
          'quantity': 3,
          'subtotal': 1500,
        }
      ],
    };

Response<dynamic> _page(List<Map<String, dynamic>> content) => Response(
      requestOptions: RequestOptions(path: '/api/v1/sales/history'),
      statusCode: 200,
      data: {
        'data': {'content': content},
      },
    );

void main() {
  late MockDio dio;
  late RemoteDayClosureDataSource ds;
  final filter = SalesHistoryFilter.today(storeId: 'store-1');

  setUp(() {
    dio = MockDio();
    ds = RemoteDayClosureDataSource(dio);
  });

  test('maps appliedUnitPrice and subtotal from SaleItemHistoryDto', () async {
    when(() => dio.get(any(), queryParameters: any(named: 'queryParameters')))
        .thenAnswer((_) async => _page([_saleJson('s1')]));

    final sales = await ds.fetchSalesHistory(filter);

    final item = sales.single.items.single;
    expect(item.appliedUnitPrice, 500);
    expect(item.catalogueUnitPrice, 600);
    expect(item.subtotal, 1500);
  });

  test('fetchAllSalesHistory walks every page until a short page', () async {
    when(() => dio.get(any(), queryParameters: any(named: 'queryParameters')))
        .thenAnswer((inv) async {
      final params = inv.namedArguments[#queryParameters] as Map;
      final page = params['page'] as int;
      final size = params['size'] as int;
      final count = page == 0 ? size : 1;
      return _page([
        for (var i = 0; i < count; i++) _saleJson('p$page-$i'),
      ]);
    });

    final sales = await ds.fetchAllSalesHistory(filter);

    expect(sales.length, 101);
    verify(() => dio.get(any(), queryParameters: any(named: 'queryParameters')))
        .called(2);
  });

  // Offset paging, newest first: a sale recorded between two requests shifts
  // the next page, which then repeats the previous page's last sale.
  test('fetchAllSalesHistory keeps a sale repeated across pages once',
      () async {
    when(() => dio.get(any(), queryParameters: any(named: 'queryParameters')))
        .thenAnswer((inv) async {
      final params = inv.namedArguments[#queryParameters] as Map;
      final page = params['page'] as int;
      final size = params['size'] as int;
      return _page(page == 0
          ? [for (var i = 0; i < size; i++) _saleJson('s$i')]
          : [_saleJson('s${size - 1}'), _saleJson('last')]);
    });

    final sales = await ds.fetchAllSalesHistory(filter);

    expect(sales.length, 101);
    expect(sales.map((s) => s.id).toSet().length, 101);
  });
}
