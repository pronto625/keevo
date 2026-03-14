import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/inventory/data/datasource/remote_multi_store_stock_datasource.dart';

class _MockDio extends Mock implements Dio {}

void main() {
  late _MockDio dio;
  late RemoteMultiStoreStockDataSource datasource;

  final summaryJson = {
    'storeId': 'store-1',
    'storeName': 'Boutique Test',
    'storeType': 'STORE',
    'productCount': 5,
    'totalValueXaf': 50000,
    'lowStockCount': 1,
  };

  final productJson = {
    'productId': 'prod-1',
    'productName': 'Chemise Bleue',
    'variantId': null,
    'variantLabel': null,
    'storeId': 'store-1',
    'quantity': 3,
    'minimumThreshold': 5,
    'status': 'BAS',
    'isLow': true,
    'isCritical': false,
  };

  setUp(() {
    dio = _MockDio();
    datasource = RemoteMultiStoreStockDataSource(dio: dio);
  });

  group('RemoteMultiStoreStockDataSource (Story 3.2 — Task 11)', () {
    test('getOverview() calls GET /api/v1/stock/overview', () async {
      when(() => dio.get<Map<String, dynamic>>(
            '/api/v1/stock/overview',
          )).thenAnswer((_) async => Response(
            data: {
              'data': [summaryJson]
            },
            statusCode: 200,
            requestOptions: RequestOptions(path: '/api/v1/stock/overview'),
          ));

      final result = await datasource.getOverview();

      expect(result.length, 1);
      expect(result.first.storeId, 'store-1');
      expect(result.first.productCount, 5);
      verify(() => dio.get<Map<String, dynamic>>('/api/v1/stock/overview')).called(1);
    });

    test('getStoreStockDetail() calls GET /api/v1/stock/stores/{id}/products with params',
        () async {
      when(() => dio.get<Map<String, dynamic>>(
            '/api/v1/stock/stores/store-1/products',
            queryParameters: any(named: 'queryParameters'),
          )).thenAnswer((_) async => Response(
            data: {
              'data': {
                'content': [productJson],
                'last': false,
              }
            },
            statusCode: 200,
            requestOptions: RequestOptions(
                path: '/api/v1/stock/stores/store-1/products'),
          ));

      final result =
          await datasource.getStoreStockDetail('store-1', page: 0, size: 25);

      expect(result.content.length, 1);
      expect(result.content.first.productId, 'prod-1');
      expect(result.content.first.isLow, isTrue);
      expect(result.hasMore, isTrue);
    });
  });
}
