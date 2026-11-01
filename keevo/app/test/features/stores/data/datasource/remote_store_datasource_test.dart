import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/stores/data/datasource/remote_store_datasource.dart';
import 'package:keevo/features/stores/domain/exception/store_exception.dart';
import 'package:keevo/features/stores/domain/model/store_type.dart';

class _MockDio extends Mock implements Dio {}

void main() {
  late _MockDio dio;
  late RemoteStoreDataSource datasource;

  final storeJson = {
    'id': 'store-1',
    'name': 'Boutique Test',
    'type': 'STORE',
    'address': null,
    'phone': null,
    'isActive': true,
    'createdAt': '2025-01-01T10:00:00Z',
    'updatedAt': '2025-01-01T10:00:00Z',
  };

  setUp(() {
    dio = _MockDio();
    datasource = RemoteStoreDataSource(dio: dio);
  });

  group('RemoteStoreDataSource (Story 3.1 — Task 18.1)', () {
    test('getAll() calls GET /api/v1/stores', () async {
      when(() => dio.get<Map<String, dynamic>>(
            '/api/v1/stores',
            queryParameters: any(named: 'queryParameters'),
          )).thenAnswer((_) async => Response(
            data: {'data': [storeJson]},
            statusCode: 200,
            requestOptions: RequestOptions(path: '/api/v1/stores'),
          ));

      final result = await datasource.getAll();
      expect(result.length, 1);
      expect(result.first.id, 'store-1');
      verify(() => dio.get<Map<String, dynamic>>(
            '/api/v1/stores',
            queryParameters: any(named: 'queryParameters'),
          )).called(1);
    });

    test('create() calls POST /api/v1/stores with correct body', () async {
      when(() => dio.post<Map<String, dynamic>>(
            '/api/v1/stores',
            data: any(named: 'data'),
          )).thenAnswer((_) async => Response(
            data: {'data': storeJson},
            statusCode: 201,
            requestOptions: RequestOptions(path: '/api/v1/stores'),
          ));

      final result = await datasource.create(
        name: 'Boutique Test',
        type: StoreType.store,
      );
      expect(result.name, 'Boutique Test');
      verify(() => dio.post<Map<String, dynamic>>(
            '/api/v1/stores',
            data: any(named: 'data'),
          )).called(1);
    });

    test('16.8: create() refused PLAN_LIMIT_EXCEEDED carries the server limit',
        () async {
      final options = RequestOptions(path: '/api/v1/stores');
      when(() => dio.post<Map<String, dynamic>>(
            '/api/v1/stores',
            data: any(named: 'data'),
          )).thenThrow(DioException(
        requestOptions: options,
        type: DioExceptionType.badResponse,
        response: Response(requestOptions: options, statusCode: 403, data: {
          'domainCode': 'PLAN_LIMIT_EXCEEDED',
          'details': {'entity': 'stores', 'limit': 2, 'current': 2},
        }),
      ));

      await expectLater(
        datasource.create(name: 'Boutique 3', type: StoreType.store),
        throwsA(isA<StoreException>()
            .having((e) => e.domainCode, 'domainCode', 'PLAN_LIMIT_EXCEEDED')
            .having((e) => e.limit, 'limit', 2)),
      );
    });

    test('update() calls PATCH /api/v1/stores/{id}', () async {
      when(() => dio.patch<Map<String, dynamic>>(
            '/api/v1/stores/store-1',
            data: any(named: 'data'),
          )).thenAnswer((_) async => Response(
            data: {'data': storeJson},
            statusCode: 200,
            requestOptions: RequestOptions(path: '/api/v1/stores/store-1'),
          ));

      final result = await datasource.update(
        storeId: 'store-1',
        name: 'Updated Name',
      );
      expect(result.id, 'store-1');
    });

    test('deactivate() calls PATCH /api/v1/stores/{id}/deactivate', () async {
      final inactive = {...storeJson, 'isActive': false};
      when(() => dio.patch<Map<String, dynamic>>(
            '/api/v1/stores/store-1/deactivate',
          )).thenAnswer((_) async => Response(
            data: {'data': inactive},
            statusCode: 200,
            requestOptions:
                RequestOptions(path: '/api/v1/stores/store-1/deactivate'),
          ));

      final result = await datasource.deactivate('store-1');
      expect(result.isActive, isFalse);
    });
  });
}
