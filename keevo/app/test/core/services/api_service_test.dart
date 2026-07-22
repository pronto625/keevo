import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/services/api_service.dart';

void main() {
  group('ApiException.fromDioError', () {
    test('extracts domainCode from correct field', () {
      final dioError = DioException(
        requestOptions: RequestOptions(path: '/api/v1/products/999'),
        response: Response(
          statusCode: 404,
          requestOptions: RequestOptions(path: '/api/v1/products/999'),
          data: {
            'error': 'Produit introuvable',
            'code': 'NOT_FOUND',
            'domainCode': 'PRODUCT_NOT_FOUND',
          },
        ),
        type: DioExceptionType.badResponse,
      );

      final exception = ApiException.fromDioError(dioError);

      expect(exception.statusCode, 404);
      expect(exception.domainCode, 'PRODUCT_NOT_FOUND');
      expect(exception.message, 'Produit introuvable');
    });

    test('domainCode is null when response data has no domainCode field', () {
      final dioError = DioException(
        requestOptions: RequestOptions(path: '/api/v1/health'),
        response: Response(
          statusCode: 500,
          requestOptions: RequestOptions(path: '/api/v1/health'),
          data: {
            'error': 'Internal server error',
            'code': 'INTERNAL_ERROR',
          },
        ),
        type: DioExceptionType.badResponse,
      );

      final exception = ApiException.fromDioError(dioError);

      expect(exception.domainCode, isNull);
      expect(exception.message, 'Internal server error');
    });

    test('handles non-Map response data gracefully', () {
      final dioError = DioException(
        requestOptions: RequestOptions(path: '/api/v1/error'),
        response: Response(
          statusCode: 500,
          requestOptions: RequestOptions(path: '/api/v1/error'),
          data: 'Internal Server Error',
        ),
        type: DioExceptionType.badResponse,
        message: 'Connection error',
      );

      final exception = ApiException.fromDioError(dioError);

      expect(exception.statusCode, 500);
      expect(exception.domainCode, isNull);
      expect(exception.message, 'Connection error');
    });
  });
}
