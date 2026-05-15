import 'package:dio/dio.dart';

import '../../domain/exception/report_exception.dart';
import '../../domain/model/report_history_model.dart';

/// RemoteReportHistoryDataSource — fetches reports from the backend REST API.
/// Story 7.2 — Task 17.
class RemoteReportHistoryDataSource {
  final Dio _dio;

  RemoteReportHistoryDataSource(this._dio);

  Future<List<ReportHistoryModel>> fetchHistory({
    required int page,
    required int size,
    String? type,
    String? storeId,
    String? actorId,
  }) async {
    final queryParams = <String, dynamic>{
      'page': page,
      'size': size,
      if (type != null) 'type': type,
      if (storeId != null) 'storeId': storeId,
      if (actorId != null) 'actorId': actorId,
    };
    final response = await _dio.get<Map<String, dynamic>>(
      '/api/v1/reports',
      queryParameters: queryParams,
    );
    final data = response.data!['data'] as Map<String, dynamic>;
    final items = (data['items'] as List<dynamic>)
        .map((e) => ReportHistoryModel.fromJson(e as Map<String, dynamic>))
        .toList();
    return items;
  }

  Future<ReportHistoryModel> fetchById(String reportId) async {
    final response = await _dio.get<Map<String, dynamic>>(
      '/api/v1/reports/$reportId',
    );
    return ReportHistoryModel.fromJson(
        response.data!['data'] as Map<String, dynamic>);
  }

  Future<void> resend(String reportId) async {
    try {
      await _dio.post<void>('/api/v1/reports/$reportId/resend');
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  // ── Mapping helpers ───────────────────────────────────────────────────────

  ReportException _mapError(DioException e) {
    final data = e.response?.data;
    final domainCode =
        (data is Map ? data['domainCode'] as String? : null) ?? 'REPORT_ERROR';
    final message =
        (data is Map ? data['error'] as String? : null) ?? e.message ?? '';
    return ReportException(domainCode: domainCode, message: message);
  }
}
