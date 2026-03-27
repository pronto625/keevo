import 'package:freezed_annotation/freezed_annotation.dart';
import 'inventory_gap_row_model.dart';

part 'inventory_gap_report_model.freezed.dart';
part 'inventory_gap_report_model.g.dart';

/// InventoryGapReportModel — complete gap analysis report.
/// Mirrors InventoryGapReport backend value object.
/// Story 6.3 — Gap Analysis Report.
@freezed
class InventoryGapReportModel with _$InventoryGapReportModel {
  const InventoryGapReportModel._();

  const factory InventoryGapReportModel({
    required String sessionId,
    required String storeId,
    required String storeName,
    required String scope,
    required InventoryGapSummaryModel summary,
    required List<InventoryGapRowModel> concordantRows,
    required List<InventoryGapRowModel> surplusRows,
    required List<InventoryGapRowModel> shortageRows,
    required DateTime generatedAt,
  }) = _InventoryGapReportModel;

  factory InventoryGapReportModel.fromJson(Map<String, dynamic> json) =>
      _$InventoryGapReportModelFromJson(json);
}

@freezed
class InventoryGapSummaryModel with _$InventoryGapSummaryModel {
  const factory InventoryGapSummaryModel({
    required int totalCounted,
    required int totalConcordant,
    required int totalSurplus,
    required int totalShortage,
    required int totalSurplusValueXaf,
    required int totalShortageValueXaf,
  }) = _InventoryGapSummaryModel;

  factory InventoryGapSummaryModel.fromJson(Map<String, dynamic> json) =>
      _$InventoryGapSummaryModelFromJson(json);
}
