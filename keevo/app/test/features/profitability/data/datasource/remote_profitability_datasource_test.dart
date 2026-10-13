import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/profitability/data/datasource/remote_profitability_datasource.dart';

class _MockDio extends Mock implements Dio {}

/// v1s-14-2 — `fetchSellerLeaderboard` path, query and envelope.
void main() {
  late _MockDio dio;
  late RemoteProfitabilityDatasource datasource;

  const path = '/api/v1/reporting/leaderboard';
  final rowJson = {
    'rank': 1,
    'sellerId': 'u1',
    'sellerName': 'Awa Ndiaye',
    'owner': false,
    'salesCount': 2,
    'totalRevenue': 10000,
    'averageBasket': 5000,
  };

  setUp(() {
    dio = _MockDio();
    datasource = RemoteProfitabilityDatasource(dio);
    when(() => dio.get<Map<String, dynamic>>(
          path,
          queryParameters: any(named: 'queryParameters'),
        )).thenAnswer((_) async => Response(
          data: {
            'data': [rowJson]
          },
          statusCode: 200,
          requestOptions: RequestOptions(path: path),
        ));
  });

  Map<String, dynamic> query() => verify(() => dio.get<Map<String, dynamic>>(
        path,
        queryParameters: captureAny(named: 'queryParameters'),
      )).captured.single as Map<String, dynamic>;

  test('GET leaderboard with from/to/storeId, parses the data envelope',
      () async {
    final result = await datasource.fetchSellerLeaderboard(
        from: '2026-10-01', to: '2026-10-07', storeId: 's1');

    expect(query(), {'from': '2026-10-01', 'to': '2026-10-07', 'storeId': 's1'});
    final e = result.single;
    expect(e.rank, 1);
    expect(e.sellerId, 'u1');
    expect(e.sellerName, 'Awa Ndiaye');
    expect(e.owner, isFalse);
    expect(e.salesCount, 2);
    expect(e.totalRevenue, 10000);
    expect(e.averageBasket, 5000);
  });

  test('storeId omitted from the query when null', () async {
    await datasource.fetchSellerLeaderboard(from: '2026-10-01', to: '2026-10-01');

    expect(query(), {'from': '2026-10-01', 'to': '2026-10-01'});
  });
}
