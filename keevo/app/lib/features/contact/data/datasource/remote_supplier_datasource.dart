import 'package:dio/dio.dart';

import '../../domain/exception/contact_exception.dart';
import '../../domain/model/supplier_model.dart';

/// RemoteSupplierDataSource — HTTP adapter for /api/v1/suppliers (Story 2.5).
class RemoteSupplierDataSource {
  final Dio _dio;

  RemoteSupplierDataSource({required Dio dio}) : _dio = dio;

  Future<List<SupplierModel>> getAll({bool includeArchived = false}) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/api/v1/suppliers',
        queryParameters: {'includeArchived': includeArchived},
      );
      final data = response.data!['data'] as List<dynamic>;
      return data
          .map((e) => SupplierModel.fromJson(e as Map<String, dynamic>))
          .toList();
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  Future<List<SupplierModel>> search(String query) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/api/v1/suppliers',
        queryParameters: {'search': query},
      );
      final data = response.data!['data'] as List<dynamic>;
      return data
          .map((e) => SupplierModel.fromJson(e as Map<String, dynamic>))
          .toList();
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  Future<SupplierModel> create(Map<String, dynamic> payload) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '/api/v1/suppliers',
        data: payload,
      );
      return SupplierModel.fromJson(
          response.data!['data'] as Map<String, dynamic>);
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  Future<SupplierModel> update(String id, Map<String, dynamic> payload) async {
    try {
      final response = await _dio.patch<Map<String, dynamic>>(
        '/api/v1/suppliers/$id',
        data: payload,
      );
      return SupplierModel.fromJson(
          response.data!['data'] as Map<String, dynamic>);
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  Future<SupplierModel?> getByProductId(String productId) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/api/v1/products/$productId/supplier',
      );
      final data = response.data?['data'];
      if (data == null) return null;
      return SupplierModel.fromJson(data as Map<String, dynamic>);
    } on DioException catch (e) {
      if (e.response?.statusCode == 404) return null;
      throw _mapError(e);
    }
  }

  Future<void> archive(String id) async {
    try {
      await _dio.delete<void>('/api/v1/suppliers/$id');
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  ContactException _mapError(DioException e) {
    if (e.response == null) throw e; // réseau indisponible — le repository gère le fallback
    final domainCode =
        e.response?.data?['domainCode'] as String? ?? 'UNKNOWN';
    final message =
        e.response?.data?['error'] as String? ?? 'Erreur inconnue';
    if (domainCode == 'SUPPLIER_NOT_FOUND') {
      return ContactException.supplierNotFound();
    }
    return ContactException.unknown(message);
  }
}
