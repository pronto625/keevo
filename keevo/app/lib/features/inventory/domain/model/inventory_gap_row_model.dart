import 'package:freezed_annotation/freezed_annotation.dart';

part 'inventory_gap_row_model.freezed.dart';
part 'inventory_gap_row_model.g.dart';

/// InventoryGapRowModel — single product gap data in the report.
/// Mirrors InventoryGapRow backend record.
/// Story 6.3 — Gap Analysis Report.
@freezed
class InventoryGapRowModel with _$InventoryGapRowModel {
  const InventoryGapRowModel._();

  const factory InventoryGapRowModel({
    required String productId,
    required String productName,
    String? sku,
    String? photoUrl,
    String? categoryName,
    String? variantId,
    String? variantLabel,
    required int theoretical,
    required int physical,
    required int ecart,
    required int unitPriceXaf,
    required int gapValueXaf,
  }) = _InventoryGapRowModel;

  bool get isSurplus => ecart > 0;
  bool get isShortage => ecart < 0;
  bool get isConcordant => ecart == 0;

  factory InventoryGapRowModel.fromJson(Map<String, dynamic> json) =>
      _$InventoryGapRowModelFromJson(json);
}
