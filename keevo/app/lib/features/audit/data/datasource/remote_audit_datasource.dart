import 'package:dio/dio.dart';

import '../../domain/exception/audit_exception.dart';
import '../../domain/model/audit_entry_dto.dart';

/// RemoteAuditDataSource — HTTP adapter to GET /api/v1/audit.
///
/// Builds query parameters from nullable filters — omits null values.
/// Returns raw List<AuditEntryDto> mapped from the backend response.
/// Maps DioException HTTP errors to [AuditException].
class RemoteAuditDataSource {
  final Dio _dio;

  RemoteAuditDataSource({required Dio dio}) : _dio = dio;

  /// GET /api/v1/audit
  ///
  /// Query parameters are built from non-null arguments:
  /// - both present → `?entityType=X&entityId=Y`
  /// - only entityType → `?entityType=X`
  /// - neither → no query params (full tenant log)
  Future<List<AuditEntryDto>> getAuditHistory({
    String? entityType,
    String? entityId,
  }) async {
    final queryParams = <String, String>{
      if (entityType != null) 'entityType': entityType,
      if (entityId != null) 'entityId': entityId,
    };

    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/api/v1/audit',
        queryParameters: queryParams.isNotEmpty ? queryParams : null,
      );

      final body = response.data!;
      final dataList = body['data'] as List<dynamic>;
      return dataList
          .map((e) => AuditEntryDto.fromJson(e as Map<String, dynamic>))
          .toList();
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  AuditException _mapError(DioException e) {
    final statusCode = e.response?.statusCode;
    final domainCode = e.response?.data?['domainCode'] as String? ?? 'UNKNOWN';

    return switch (statusCode) {
      401 => AuditException(
          domainCode: 'UNAUTHORIZED',
          message: 'Non authentifié',
          statusCode: 401,
        ),
      403 => AuditException(
          domainCode: domainCode,
          message: e.response?.data?['error'] as String? ??
              'Les entrées du journal d\'audit ne peuvent pas être modifiées',
          statusCode: 403,
        ),
      _ => AuditException(
          domainCode: domainCode,
          message: e.response?.data?['error'] as String? ?? 'Erreur réseau',
          statusCode: statusCode,
        ),
    };
  }
}
