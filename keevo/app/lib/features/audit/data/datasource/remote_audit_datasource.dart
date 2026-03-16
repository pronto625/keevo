import 'package:dio/dio.dart';

import '../../domain/exception/audit_exception.dart';
import '../../domain/model/audit_entry_dto.dart';

/// RemoteAuditDataSource — HTTP adapter to GET /api/v1/audit.
///
/// Sends paginated requests with [page] and [size] query parameters.
/// Returns [AuditPageResult] mapped from the backend paginated response.
class RemoteAuditDataSource {
  final Dio _dio;

  RemoteAuditDataSource({required Dio dio}) : _dio = dio;

  /// GET /api/v1/audit?page=N&size=N[&entityType=X][&entityId=Y]
  ///
  /// Response format: { "data": { "entries": [...], "hasMore": bool, "page": N, "size": N } }
  Future<AuditPageResult> getAuditHistoryPage({
    required int page,
    required int size,
    String? entityType,
    String? entityId,
  }) async {
    final queryParams = <String, dynamic>{
      'page': page,
      'size': size,
      if (entityType != null) 'entityType': entityType,
      if (entityId != null) 'entityId': entityId,
    };

    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/api/v1/audit',
        queryParameters: queryParams,
      );

      final body = response.data!;
      final data = body['data'] as Map<String, dynamic>;
      final dataList = data['entries'] as List<dynamic>;
      final hasMore = data['hasMore'] as bool;
      return AuditPageResult(
        entries: dataList
            .map((e) => AuditEntryDto.fromJson(e as Map<String, dynamic>))
            .toList(),
        hasMore: hasMore,
      );
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
