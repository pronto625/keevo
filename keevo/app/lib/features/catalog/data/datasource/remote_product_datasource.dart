import 'package:dio/dio.dart';

import '../../domain/exception/product_exception.dart';
import '../../domain/model/product_response_dto.dart';

/// RemoteProductDataSource — HTTP adapter for /api/v1/products.
///
/// Uses the authenticated [Dio] instance (via AuthInterceptor).
/// Maps [DioException] HTTP errors to [ProductException].
class RemoteProductDataSource {
  final Dio _dio;

  RemoteProductDataSource({required Dio dio}) : _dio = dio;

  /// GET /api/v1/products — all non-archived products for current tenant.
  Future<List<ProductResponseDto>> getAll() async {
    try {
      final response =
          await _dio.get<Map<String, dynamic>>('/api/v1/products');
      final body = response.data!;
      final data = body['data'] as List<dynamic>;
      return data
          .map((e) => ProductResponseDto.fromJson(e as Map<String, dynamic>))
          .toList();
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  /// POST /api/v1/products — create a product on the backend.
  Future<ProductResponseDto> create(Map<String, dynamic> payload) async {
    try {
      final response =
          await _dio.post<Map<String, dynamic>>('/api/v1/products', data: payload);
      final body = response.data!;
      return ProductResponseDto.fromJson(
          body['data'] as Map<String, dynamic>);
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  /// PATCH /api/v1/products/[id] — update a product on the backend.
  Future<ProductResponseDto> update(
      String id, Map<String, dynamic> payload) async {
    try {
      final response = await _dio.patch<Map<String, dynamic>>(
          '/api/v1/products/$id',
          data: payload);
      final body = response.data!;
      return ProductResponseDto.fromJson(
          body['data'] as Map<String, dynamic>);
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  /// PATCH /api/v1/products/[id]/archive — archive a product on the backend.
  Future<void> archive(String id) async {
    try {
      await _dio.patch<void>('/api/v1/products/$id/archive');
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }
  /// PATCH /api/v1/products/[id]/unarchive — unarchive a product on the backend.
  Future<void> unarchive(String id) async {
    try {
      await _dio.patch('/api/v1/products/$id/unarchive');
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }
  ProductException _mapError(DioException e) {
    final statusCode = e.response?.statusCode;
    final domainCode =
        e.response?.data?['domainCode'] as String? ?? 'UNKNOWN';
    final message =
        e.response?.data?['error'] as String? ?? 'Erreur inconnue';

    return switch (statusCode) {
      400 => ProductException(
          domainCode: domainCode,
          message: message,
          statusCode: 400,
        ),
      401 => const ProductException(
          domainCode: 'UNAUTHORIZED',
          message: 'Non authentifié',
          statusCode: 401,
        ),
      403 => ProductException(
          domainCode: domainCode,
          message: message,
          statusCode: 403,
        ),
      404 => const ProductException(
          domainCode: 'PRODUCT_NOT_FOUND',
          message: 'Produit introuvable',
          statusCode: 404,
        ),
      409 => const ProductException(
          domainCode: 'SKU_ALREADY_EXISTS',
          message: 'Cette référence SKU est déjà utilisée',
          statusCode: 409,
        ),
      _ => ProductException(
          domainCode: 'NETWORK_ERROR',
          message: e.message ?? 'Erreur réseau',
          statusCode: statusCode,
        ),
    };
  }
}
