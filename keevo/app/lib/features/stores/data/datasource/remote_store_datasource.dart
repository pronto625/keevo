import 'package:dio/dio.dart';

import '../../domain/exception/store_exception.dart';
import '../../domain/model/store_model.dart';
import '../../domain/model/store_type.dart';

/// RemoteStoreDataSource — HTTP adapter for /api/v1/stores (Story 3.1).
class RemoteStoreDataSource {
  final Dio _dio;

  RemoteStoreDataSource({required Dio dio}) : _dio = dio;

  /// GET /api/v1/stores?includeInactive=[includeInactive]
  Future<List<StoreModel>> getAll({bool includeInactive = false}) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/api/v1/stores',
        queryParameters: {'includeInactive': includeInactive},
      );
      final data = response.data!['data'] as List<dynamic>;
      return data
          .map((e) => _fromJson(e as Map<String, dynamic>))
          .toList();
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  /// POST /api/v1/stores
  Future<StoreModel> create({
    required String name,
    required StoreType type,
    String? address,
    String? phone,
  }) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '/api/v1/stores',
        data: {
          'name': name,
          'type': type.name.toUpperCase(),
          if (address != null) 'address': address,
          if (phone != null) 'phone': phone,
        },
      );
      return _fromJson(response.data!['data'] as Map<String, dynamic>);
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  /// PATCH /api/v1/stores/{storeId}
  Future<StoreModel> update({
    required String storeId,
    required String name,
    String? address,
    String? phone,
  }) async {
    try {
      final response = await _dio.patch<Map<String, dynamic>>(
        '/api/v1/stores/$storeId',
        data: {
          'name': name,
          if (address != null) 'address': address,
          if (phone != null) 'phone': phone,
        },
      );
      return _fromJson(response.data!['data'] as Map<String, dynamic>);
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  /// PATCH /api/v1/stores/{storeId}/deactivate
  Future<StoreModel> deactivate(String storeId) async {
    try {
      final response = await _dio.patch<Map<String, dynamic>>(
        '/api/v1/stores/$storeId/deactivate',
      );
      return _fromJson(response.data!['data'] as Map<String, dynamic>);
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  // ── Private helpers ───────────────────────────────────────────────────────

  StoreModel _fromJson(Map<String, dynamic> json) {
    return StoreModel(
      id: json['id'] as String,
      name: json['name'] as String,
      type: StoreType.fromString(json['type'] as String? ?? 'STORE'),
      address: json['address'] as String?,
      phone: json['phone'] as String?,
      isActive: json['isActive'] as bool? ?? true,
      createdAt: DateTime.parse(json['createdAt'] as String),
      updatedAt: DateTime.parse(json['updatedAt'] as String),
    );
  }

  StoreException _mapError(DioException e) {
    // No response = network/connection error — let it propagate as-is so the
    // repository can distinguish it from a business error and apply offline fallback.
    if (e.response == null) throw e;
    final domainCode = e.response?.data?['domainCode'] as String? ?? 'UNKNOWN';
    final message = e.response?.data?['error'] as String? ?? 'Erreur inconnue';
    return switch (domainCode) {
      'STORE_NOT_FOUND' => StoreException.notFound(),
      'PLAN_LIMIT_EXCEEDED' => StoreException.planLimitExceeded(),
      'WAREHOUSE_ALREADY_EXISTS' => StoreException.warehouseAlreadyExists(),
      _ => StoreException.unknown(message),
    };
  }
}
