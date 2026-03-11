import 'package:dio/dio.dart';

import '../../domain/exception/contact_exception.dart';
import '../../domain/model/client_model.dart';

/// RemoteClientDataSource — HTTP adapter for /api/v1/clients (Story 2.5).
class RemoteClientDataSource {
  final Dio _dio;

  RemoteClientDataSource({required Dio dio}) : _dio = dio;

  Future<List<ClientModel>> getAll({bool includeArchived = false}) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/api/v1/clients',
        queryParameters: {'includeArchived': includeArchived},
      );
      final data = response.data!['data'] as List<dynamic>;
      return data
          .map((e) => ClientModel.fromJson(e as Map<String, dynamic>))
          .toList();
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  Future<List<ClientModel>> search(String query) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/api/v1/clients',
        queryParameters: {'search': query},
      );
      final data = response.data!['data'] as List<dynamic>;
      return data
          .map((e) => ClientModel.fromJson(e as Map<String, dynamic>))
          .toList();
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  Future<ClientModel> create(Map<String, dynamic> payload) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '/api/v1/clients',
        data: payload,
      );
      return ClientModel.fromJson(
          response.data!['data'] as Map<String, dynamic>);
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  Future<ClientModel> update(String id, Map<String, dynamic> payload) async {
    try {
      final response = await _dio.patch<Map<String, dynamic>>(
        '/api/v1/clients/$id',
        data: payload,
      );
      return ClientModel.fromJson(
          response.data!['data'] as Map<String, dynamic>);
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  Future<void> archive(String id) async {
    try {
      await _dio.delete<void>('/api/v1/clients/$id');
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  ContactException _mapError(DioException e) {
    final domainCode =
        e.response?.data?['domainCode'] as String? ?? 'UNKNOWN';
    final message =
        e.response?.data?['error'] as String? ?? 'Erreur inconnue';
    if (domainCode == 'CLIENT_NOT_FOUND') {
      return ContactException.clientNotFound();
    }
    return ContactException.unknown(message);
  }
}
