import 'package:dio/dio.dart';

import '../../domain/exception/audit_exception.dart';
import '../../domain/model/audit_entry_dto.dart';
import '../../domain/model/audit_filter.dart';

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

  /// v1s-18-7 — `GET /api/v1/audit` with the history filters and keyset
  /// [cursor]. `from`/`to` are sent as ISO-8601 UTC instants.
  Future<AuditSlice> getAuditSlice(
    AuditFilter filter, {
    String? cursor,
    int size = 20,
  }) async {
    final query = filter.normalizedQuery;
    final queryParams = <String, dynamic>{
      'size': size,
      if (filter.from != null) 'from': filter.from!.toUtc().toIso8601String(),
      if (filter.to != null) 'to': filter.to!.toUtc().toIso8601String(),
      if (filter.storeId != null) 'storeId': filter.storeId,
      if (filter.userId != null) 'userId': filter.userId,
      if (filter.action != null) 'action': filter.action,
      if (filter.entityType != null) 'entityType': filter.entityType,
      if (query != null) 'q': query,
      if (cursor != null) 'cursor': cursor,
    };
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/api/v1/audit',
        queryParameters: queryParams,
      );
      final data = response.data!['data'] as Map<String, dynamic>;
      final entries = (data['entries'] as List<dynamic>)
          .map((e) => AuditEntryDto.fromJson(e as Map<String, dynamic>))
          .toList();
      final hasMore = data['hasMore'] as bool? ?? false;
      final nextCursor = data['nextCursor'] as String?;
      return AuditSlice(
        entries: entries,
        nextCursor: hasMore ? nextCursor : null,
      );
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  /// v1s-18-7 — `GET /api/v1/audit/{id}`; 404 → `AUDIT_ENTRY_NOT_FOUND`.
  Future<AuditEntryDto> getById(String id) async {
    try {
      final response =
          await _dio.get<Map<String, dynamic>>('/api/v1/audit/$id');
      return AuditEntryDto.fromJson(
          response.data!['data'] as Map<String, dynamic>);
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  AuditException _mapError(DioException e) {
    final statusCode = e.response?.statusCode;
    final body = e.response?.data;
    final domainCode =
        (body is Map ? body['domainCode'] as String? : null) ?? 'UNKNOWN';
    // A text / HTML error body (proxy, gateway) carries no message.
    final serverMessage =
        body is Map && body['error'] is String ? body['error'] as String : null;
    if (statusCode == null) {
      // No HTTP response: connectivity problem (timeout, DNS, refused…).
      return const AuditException(
        domainCode: 'NETWORK_ERROR',
        message: 'Impossible de joindre le serveur',
      );
    }
    if (statusCode == 404) {
      return AuditException(
        domainCode:
            domainCode == 'UNKNOWN' ? 'AUDIT_ENTRY_NOT_FOUND' : domainCode,
        message: 'Action introuvable',
        statusCode: 404,
      );
    }

    return switch (statusCode) {
      401 => const AuditException(
          domainCode: 'UNAUTHORIZED',
          message: 'Non authentifié',
          statusCode: 401,
        ),
      403 => AuditException(
          domainCode: domainCode,
          message: serverMessage ??
              'Les entrées du journal d\'audit ne peuvent pas être modifiées',
          statusCode: 403,
        ),
      _ => AuditException(
          domainCode: domainCode,
          message: serverMessage ?? 'Erreur réseau',
          statusCode: statusCode,
        ),
    };
  }
}
