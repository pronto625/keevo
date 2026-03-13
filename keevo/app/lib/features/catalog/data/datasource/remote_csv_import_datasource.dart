import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';

import '../../domain/exception/product_exception.dart';
import '../../domain/model/csv_import_result.dart';
import '../../domain/model/product_response_dto.dart';

/// RemoteCsvImportDataSource — HTTP adapter for CSV import + draft creation.
///
/// Endpoints:
///  - POST /api/v1/products/import   — multipart CSV upload
///  - GET  /api/v1/products/import/template — template download
///  - POST /api/v1/products/draft    — create a DRAFT product
class RemoteCsvImportDataSource {
  final Dio _dio;

  const RemoteCsvImportDataSource({required Dio dio}) : _dio = dio;

  /// POST /api/v1/products/import — multipart CSV upload + column mapping.
  Future<CsvImportResult> importCsv({
    required Uint8List csvBytes,
    required String fileName,
    required Map<String, String> columnMapping,
  }) async {
    try {
      final formData = FormData.fromMap({
        'file': MultipartFile.fromBytes(
          csvBytes,
          filename: fileName,
          headers: {
            Headers.contentTypeHeader: ['text/csv'],
          },
        ),
        'mapping': MultipartFile.fromString(
          jsonEncode(columnMapping),
          headers: {
            Headers.contentTypeHeader: ['application/json'],
          },
        ),
      });
      final response = await _dio.post<Map<String, dynamic>>(
        '/api/v1/products/import',
        data: formData,
      );
      return CsvImportResult.fromJson(
          response.data!['data'] as Map<String, dynamic>);
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  /// GET /api/v1/products/import/template — download CSV template bytes.
  Future<Uint8List> downloadCsvTemplate() async {
    try {
      final response = await _dio.get<List<int>>(
        '/api/v1/products/import/template',
        options: Options(responseType: ResponseType.bytes),
      );
      return Uint8List.fromList(response.data!);
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  /// POST /api/v1/products/draft — create a DRAFT product on the backend.
  Future<ProductResponseDto> createDraft(Map<String, dynamic> payload) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '/api/v1/products/draft',
        data: payload,
      );
      return ProductResponseDto.fromJson(
          response.data!['data'] as Map<String, dynamic>);
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  static ProductException _mapError(DioException e) {
    final statusCode = e.response?.statusCode;
    final domainCode =
        e.response?.data?['domainCode'] as String? ?? 'UNKNOWN';
    final message =
        e.response?.data?['error'] as String? ?? 'Erreur inconnue';

    return switch (statusCode) {
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
      409 => const ProductException(
          domainCode: 'PRODUCT_NAME_ALREADY_EXISTS',
          message: 'Un produit avec ce nom existe déjà dans votre catalogue',
          statusCode: 409,
        ),
      422 => ProductException(
          domainCode: 'CSV_PARSE_ERROR',
          message: message,
          statusCode: 422,
        ),
      _ => ProductException(
          domainCode: 'NETWORK_ERROR',
          message: e.message ?? 'Erreur réseau',
          statusCode: statusCode,
        ),
    };
  }
}
