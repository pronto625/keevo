import 'dart:developer' as dev;

import '../../domain/model/report_history_model.dart';
import '../../domain/repository/report_history_repository.dart';
import '../datasource/local_report_history_datasource.dart';
import '../datasource/remote_report_history_datasource.dart';

/// ReportHistoryRepositoryImpl — Backend-First-When-Online strategy.
/// Story 7.2 — Task 18.
///
/// Online: fetch from backend and cache locally.
/// Offline: serve from local Drift cache.
class ReportHistoryRepositoryImpl implements ReportHistoryRepository {
  final LocalReportHistoryDataSource _local;
  final RemoteReportHistoryDataSource _remote;

  ReportHistoryRepositoryImpl({
    required LocalReportHistoryDataSource local,
    required RemoteReportHistoryDataSource remote,
  })  : _local = local,
        _remote = remote;

  @override
  Future<List<ReportHistoryModel>> getReportHistory({
    required int page,
    required int size,
    String? type,
    String? storeId,
    String? actorId,
  }) async {
    try {
      final remoteList = await _remote.fetchHistory(
          page: page, size: size, type: type,
          storeId: storeId, actorId: actorId);
      for (final report in remoteList) {
        await _local.upsertReport(report);
      }
      return remoteList;
    } catch (e) {
      dev.log('Remote report history failed, using local cache: $e',
          name: 'ReportHistoryRepository');
      return _local.getHistory(page: page, size: size, type: type);
    }
  }

  @override
  Future<ReportHistoryModel?> getReportById(String reportId) async {
    try {
      final report = await _remote.fetchById(reportId);
      await _local.upsertReport(report);
      return report;
    } catch (e) {
      dev.log('Remote fetchById failed, using local: $e',
          name: 'ReportHistoryRepository');
      return _local.getById(reportId);
    }
  }

  @override
  Future<void> resendReport(String reportId) async {
    // Resend is online-only — requires backend connectivity
    await _remote.resend(reportId);
  }
}
