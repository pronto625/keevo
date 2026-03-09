import 'dart:convert';

import 'package:dio/dio.dart';

// ── ApiService Abstract Interface ────────────────────────────────────────────

/// Abstract interface for HTTP API communication.
/// Defines common CRUD operations for interacting with the backend.
abstract class ApiService {
  Future<Map<String, dynamic>> get(String path);
  Future<Map<String, dynamic>> post(String path, {Map<String, dynamic>? data});
  Future<Map<String, dynamic>> patch(String path, {Map<String, dynamic>? data});
  Future<Map<String, dynamic>> delete(String path);
}

// ── ApiException ─────────────────────────────────────────────────────────────

/// Exception thrown when an API call fails.
class ApiException implements Exception {
  final String message;
  final int statusCode;
  final String? domainCode;

  ApiException(
    this.message,
    this.statusCode, {
    this.domainCode,
  });

  /// Factory constructor to create ApiException from Dio error.
  factory ApiException.fromDioError(DioException error) {
    int statusCode = error.response?.statusCode ?? 500;
    String message = 'Network error';
    String? domainCode;

    if (error.response?.data is Map<String, dynamic>) {
      final data = error.response!.data as Map<String, dynamic>;
      message = data['message'] ?? data['error'] ?? 'Unknown error';
      domainCode = data['code']?.toString();
    } else if (error.message != null) {
      message = error.message!;
    }
    
    return ApiException(
      message,
      statusCode,
      domainCode: domainCode,
    );
  }

  @override
  String toString() => 'ApiException($statusCode${domainCode != null ? ' - $domainCode' : ''}): $message';
}

// ── DioApiService Implementation ────────────────────────────────────────────

class DioApiService extends ApiService {
  final Dio dio;

  DioApiService({required this.dio});

  @override
  Future<Map<String, dynamic>> get(String path) async {
    try {
      final response = await dio.get<Map<String, dynamic>>(path);
      return response.data ?? {};
    } on DioException catch (e) {
      throw ApiException.fromDioError(e);
    }
  }

  @override
  Future<Map<String, dynamic>> post(String path, {Map<String, dynamic>? data}) async {
    try {
      final response = await dio.post<Map<String, dynamic>>(path, data: data);
      return response.data ?? {};
    } on DioException catch (e) {
      throw ApiException.fromDioError(e);
    }
  }

  @override
  Future<Map<String, dynamic>> patch(String path, {Map<String, dynamic>? data}) async {
    try {
      final response = await dio.patch<Map<String, dynamic>>(path, data: data);
      return response.data ?? {};
    } on DioException catch (e) {
      throw ApiException.fromDioError(e);
    }
  }

  @override
  Future<Map<String, dynamic>> delete(String path) async {
    try {
      final response = await dio.delete<Map<String, dynamic>>(path);
      return response.data ?? {};
    } on DioException catch (e) {
      throw ApiException.fromDioError(e);
    }
  }
}

// ── Stub Implementation ──────────────────────────────────────────────────────

/// Stub implementation of ApiService (for development/testing).
class StubApiService implements ApiService {
  @override
  Future<Map<String, dynamic>> get(String path) async {
    // Return empty data for now
    return {'categories': []};
  }

  @override
  Future<Map<String, dynamic>> post(String path, {Map<String, dynamic>? data}) async {
    return {};
  }

  @override
  Future<Map<String, dynamic>> patch(String path, {Map<String, dynamic>? data}) async {
    return {};
  }

  @override
  Future<Map<String, dynamic>> delete(String path) async {
    return {};
  }
}

