import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:keevo/features/inventory/data/datasource/remote_stock_transfer_datasource.dart';

class _MockDio extends Mock implements Dio {}

void main() {
  late _MockDio dio;
  late RemoteStockTransferDataSource datasource;

  final transferJson = {
    'id': 'tf-001',
    'sourceStoreId': 'src-001',
    'destinationStoreId': 'dst-001',
    'productId': 'prod-001',
    'variantId': null,
    'quantity': 5,
    'actorId': 'actor-001',
    'occurredAt': '2026-03-14T10:00:00.000Z',
    'status': 'COMPLETED',
    'notes': null,
  };

  setUp(() {
    dio = _MockDio();
    datasource = RemoteStockTransferDataSource(dio: dio);
  });

  group('RemoteStockTransferDataSource (Story 3.3 — Task 13)', () {
    test('executeTransfer POSTs to /api/v1/stock/transfers and returns model',
        () async {
      when(() => dio.post<Map<String, dynamic>>(
            '/api/v1/stock/transfers',
            data: any(named: 'data'),
          )).thenAnswer((_) async => Response(
            data: {'data': transferJson},
            statusCode: 201,
            requestOptions: RequestOptions(path: '/api/v1/stock/transfers'),
          ));

      final result = await datasource.executeTransfer(
        sourceStoreId: 'src-001',
        destinationStoreId: 'dst-001',
        productId: 'prod-001',
        quantity: 5,
      );

      expect(result.id, 'tf-001');
      expect(result.status, 'COMPLETED');
      verify(() => dio.post<Map<String, dynamic>>(
            '/api/v1/stock/transfers',
            data: any(named: 'data'),
          )).called(1);
    });

    test('getHistory GETs /api/v1/stock/transfers and returns list', () async {
      when(() => dio.get<Map<String, dynamic>>(
            '/api/v1/stock/transfers',
            queryParameters: any(named: 'queryParameters'),
          )).thenAnswer((_) async => Response(
            data: {
              'data': {
                'content': [transferJson],
                'totalElements': 1,
              }
            },
            statusCode: 200,
            requestOptions: RequestOptions(path: '/api/v1/stock/transfers'),
          ));

      final result = await datasource.getHistory();

      expect(result, hasLength(1));
      expect(result.first.id, 'tf-001');
    });

    test('getHistory passes sourceStoreId as "source" query parameter', () async {
      when(() => dio.get<Map<String, dynamic>>(
            '/api/v1/stock/transfers',
            queryParameters: any(named: 'queryParameters'),
          )).thenAnswer((_) async => Response(
            data: {
              'data': {'content': [], 'totalElements': 0}
            },
            statusCode: 200,
            requestOptions: RequestOptions(path: '/api/v1/stock/transfers'),
          ));

      await datasource.getHistory(sourceStoreId: 'src-001');

      final captured = verify(() => dio.get<Map<String, dynamic>>(
            '/api/v1/stock/transfers',
            queryParameters: captureAny(named: 'queryParameters'),
          )).captured;
      expect(
        (captured.first as Map<String, dynamic>)['source'],
        'src-001',
      );
    });

    test('getHistory passes destinationStoreId as "destination" query parameter', () async {
      when(() => dio.get<Map<String, dynamic>>(
            '/api/v1/stock/transfers',
            queryParameters: any(named: 'queryParameters'),
          )).thenAnswer((_) async => Response(
            data: {
              'data': {'content': [], 'totalElements': 0}
            },
            statusCode: 200,
            requestOptions: RequestOptions(path: '/api/v1/stock/transfers'),
          ));

      await datasource.getHistory(destinationStoreId: 'dst-001');

      final captured = verify(() => dio.get<Map<String, dynamic>>(
            '/api/v1/stock/transfers',
            queryParameters: captureAny(named: 'queryParameters'),
          )).captured;
      expect(
        (captured.first as Map<String, dynamic>)['destination'],
        'dst-001',
      );
    });

    test(
        'getHistory passes both sourceStoreId and destinationStoreId together',
        () async {
      when(() => dio.get<Map<String, dynamic>>(
            '/api/v1/stock/transfers',
            queryParameters: any(named: 'queryParameters'),
          )).thenAnswer((_) async => Response(
            data: {
              'data': {'content': [], 'totalElements': 0}
            },
            statusCode: 200,
            requestOptions: RequestOptions(path: '/api/v1/stock/transfers'),
          ));

      await datasource.getHistory(
        sourceStoreId: 'src-001',
        destinationStoreId: 'dst-001',
      );

      final captured = verify(() => dio.get<Map<String, dynamic>>(
            '/api/v1/stock/transfers',
            queryParameters: captureAny(named: 'queryParameters'),
          )).captured;
      final queryParameters = captured.first as Map<String, dynamic>;
      expect(queryParameters['source'], 'src-001');
      expect(queryParameters['destination'], 'dst-001');
    });
  });
}
