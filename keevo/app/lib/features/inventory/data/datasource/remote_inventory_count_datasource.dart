import 'package:dio/dio.dart';

import '../../domain/model/inventory_count_model.dart';
import '../../domain/model/inventory_product_row_model.dart';

/// RemoteInventoryCountDataSource — HTTP adapter for /api/v1/inventory/sessions/{id}/*.
///
/// Story 6.2.
class RemoteInventoryCountDataSource {
  final Dio _dio;

  const RemoteInventoryCountDataSource({required Dio dio}) : _dio = dio;

  /// Sanitize nanosecond timestamps from Spring Boot to microsecond precision.
  static Map<String, dynamic> _sanitize(Map<String, dynamic> json) {
    final result = Map<String, dynamic>.from(json);
    for (final key in ['countedAt', 'updatedAt']) {
      final ts = result[key];
      if (ts is String) {
        result[key] = ts.replaceFirstMapped(
          RegExp(r'(\.\d{6})\d+'),
          (m) => m.group(1)!,
        );
      }
    }
    return result;
  }

  /// GET /api/v1/inventory/sessions/{sessionId}/products
  Future<List<InventoryProductRowModel>> getCountingProducts(
      String sessionId) async {
    final response = await _dio.get<Map<String, dynamic>>(
      '/api/v1/inventory/sessions/$sessionId/products',
    );
    final list = response.data!['data'] as List<dynamic>;
    return list
        .map((e) => InventoryProductRowModel.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  /// POST /api/v1/inventory/sessions/{sessionId}/counts
  Future<InventoryCountModel> saveCount({
    required String sessionId,
    required String productId,
    String? variantId,
    required String productName,
    String? variantLabel,
    required int theoretical,
    required int physical,
  }) async {
    final response = await _dio.post<Map<String, dynamic>>(
      '/api/v1/inventory/sessions/$sessionId/counts',
      data: {
        'productId': productId,
        if (variantId != null) 'variantId': variantId,
        'productName': productName,
        if (variantLabel != null) 'variantLabel': variantLabel,
        'theoretical': theoretical,
        'physical': physical,
      },
    );
    final data = response.data!['data'] as Map<String, dynamic>;
    return InventoryCountModel.fromJson(_sanitize(data));
  }

  /// GET /api/v1/inventory/sessions/{sessionId}/counts
  Future<List<InventoryCountModel>> getCountsForSession(
      String sessionId) async {
    final response = await _dio.get<Map<String, dynamic>>(
      '/api/v1/inventory/sessions/$sessionId/counts',
    );
    final list = response.data!['data'] as List<dynamic>;
    return list
        .map((e) => InventoryCountModel.fromJson(_sanitize(e as Map<String, dynamic>)))
        .toList();
  }
}
