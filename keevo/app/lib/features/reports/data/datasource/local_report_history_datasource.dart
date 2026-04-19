import 'package:drift/drift.dart';

import '../../../../core/storage/app_database.dart';
import '../../domain/model/report_history_model.dart';

/// LocalReportHistoryDataSource — reads/writes reports from local Drift DB.
/// Story 7.2 — Task 16.
class LocalReportHistoryDataSource {
  final AppDatabase _db;

  LocalReportHistoryDataSource(this._db);

  Future<List<ReportHistoryModel>> getHistory({
    required int page,
    required int size,
    String? type,
    String? storeId,
    String? actorId,
  }) async {
    final query = _db.select(_db.reports)
      ..orderBy([(t) => OrderingTerm.desc(t.reportDate)])
      ..limit(size, offset: page * size);
    if (type != null) {
      query.where((t) => t.reportType.equals(type));
    }
    if (storeId != null) {
      query.where((t) => t.storeId.equals(storeId));
    }
    if (actorId != null) {
      query.where((t) => t.actorId.equals(actorId));
    }
    final rows = await query.get();
    return rows.map(_mapToModel).toList();
  }

  Future<ReportHistoryModel?> getById(String reportId) async {
    final row = await (_db.select(_db.reports)
          ..where((t) => t.id.equals(reportId)))
        .getSingleOrNull();
    return row != null ? _mapToModel(row) : null;
  }

  Future<void> upsertReport(ReportHistoryModel model) async {
    await _db.into(_db.reports).insertOnConflictUpdate(ReportsCompanion(
          id: Value(model.id),
          tenantId: Value(model.tenantId),
          storeId: Value(model.storeId),
          actorId: Value(model.actorId),
          storeName: Value(model.storeName),
          reportType: Value(model.reportType),
          reportDate: Value(model.reportDate),
          content: Value(model.content),
          deliveryStatus: Value(model.deliveryStatus),
          deliveryAttempts: Value(model.deliveryAttempts),
          lastAttemptAt: Value(model.lastAttemptAt),
          totalRevenue: Value(model.totalRevenue),
          totalSales: Value(model.totalSales),
          isAutomatic: Value(model.isAutomatic),
          createdAt: Value(model.createdAt),
        ));
  }

  Future<void> updateDeliveryStatus(
      String reportId, String status, int attempts) async {
    await (_db.update(_db.reports)
          ..where((t) => t.id.equals(reportId)))
        .write(ReportsCompanion(
      deliveryStatus: Value(status),
      deliveryAttempts: Value(attempts),
    ));
  }

  ReportHistoryModel _mapToModel(Report row) {
    return ReportHistoryModel(
      id: row.id,
      tenantId: row.tenantId,
      storeId: row.storeId,
      actorId: row.actorId,
      storeName: row.storeName,
      reportType: row.reportType,
      reportDate: row.reportDate,
      content: row.content,
      deliveryStatus: row.deliveryStatus,
      deliveryAttempts: row.deliveryAttempts,
      lastAttemptAt: row.lastAttemptAt,
      totalRevenue: row.totalRevenue,
      totalSales: row.totalSales,
      isAutomatic: row.isAutomatic,
      createdAt: row.createdAt,
    );
  }
}
