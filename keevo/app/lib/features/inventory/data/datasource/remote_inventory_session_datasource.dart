import 'package:dio/dio.dart';

import '../../domain/model/inventory_session_model.dart';

/// RemoteInventorySessionDataSource — HTTP adapter for /api/v1/inventory/sessions.
///
/// Story 6.1.
class RemoteInventorySessionDataSource {
  final Dio _dio;

  const RemoteInventorySessionDataSource({required Dio dio}) : _dio = dio;

  /// Sanitize nanosecond timestamps from Spring Boot to microsecond precision.
  static Map<String, dynamic> _sanitize(Map<String, dynamic> json) {
    final result = Map<String, dynamic>.from(json);
    for (final key in ['startedAt', 'cancelledAt', 'completedAt', 'updatedAt']) {
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

  /// POST /api/v1/inventory/sessions
  Future<InventorySessionModel> create({
    required String storeId,
    required String scope,
    List<String>? categoryIds,
  }) async {
    final response = await _dio.post<Map<String, dynamic>>(
      '/api/v1/inventory/sessions',
      data: {
        'storeId': storeId,
        'scope': scope,
        if (categoryIds != null) 'categoryIds': categoryIds,
      },
    );
    final data = response.data!['data'] as Map<String, dynamic>;
    return InventorySessionModel.fromJson(_sanitize(data));
  }

  /// GET /api/v1/inventory/sessions/active?storeId=
  Future<InventorySessionModel?> getActive(String storeId) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/api/v1/inventory/sessions/active',
        queryParameters: {'storeId': storeId},
      );
      final data = response.data!['data'] as Map<String, dynamic>;
      return InventorySessionModel.fromJson(_sanitize(data));
    } on DioException catch (e) {
      if (e.response?.statusCode == 404) return null;
      rethrow;
    }
  }

  /// POST /api/v1/inventory/sessions/{id}/cancel
  Future<InventorySessionModel> cancel(String sessionId) async {
    final response = await _dio.post<Map<String, dynamic>>(
      '/api/v1/inventory/sessions/$sessionId/cancel',
    );
    final data = response.data!['data'] as Map<String, dynamic>;
    return InventorySessionModel.fromJson(_sanitize(data));
  }

  /// GET /api/v1/inventory/sessions?page=&size=
  Future<List<InventorySessionModel>> getHistory({
    int page = 0,
    int size = 20,
  }) async {
    final response = await _dio.get<Map<String, dynamic>>(
      '/api/v1/inventory/sessions',
      queryParameters: {'page': page, 'size': size},
    );
    final list = response.data!['data'] as List<dynamic>;
    return list
        .map((e) =>
            InventorySessionModel.fromJson(_sanitize(e as Map<String, dynamic>)))
        .toList();
  }
}
