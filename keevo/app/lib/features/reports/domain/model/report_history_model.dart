import 'package:freezed_annotation/freezed_annotation.dart';

part 'report_history_model.freezed.dart';
part 'report_history_model.g.dart';

/// ReportHistoryModel — domain model mirroring the backend EndOfDayReport entity.
/// Story 7.2 — Rapport End-of-Day.
@freezed
class ReportHistoryModel with _$ReportHistoryModel {
  const ReportHistoryModel._();

  const factory ReportHistoryModel({
    required String id,
    required String tenantId,
    required String storeId,
    String? storeName,
    String? actorId,
    String? actorName,
    required String reportType,
    required DateTime reportDate,
    required String content,
    required String deliveryStatus,
    required int deliveryAttempts,
    DateTime? lastAttemptAt,
    required int totalRevenue,
    required int totalSales,
    required bool isAutomatic,
    required DateTime createdAt,
  }) = _ReportHistoryModel;

  factory ReportHistoryModel.fromJson(Map<String, dynamic> json) =>
      _$ReportHistoryModelFromJson(json);

  bool get isDelivered => deliveryStatus == 'SENT';
  bool get isFailed => deliveryStatus == 'FAILED' || deliveryStatus == 'IN_APP_ONLY';
}
