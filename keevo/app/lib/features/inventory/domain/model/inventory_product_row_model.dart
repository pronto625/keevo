import 'package:freezed_annotation/freezed_annotation.dart';

part 'inventory_product_row_model.freezed.dart';
part 'inventory_product_row_model.g.dart';

/// InventoryProductRowModel — read model for the counting form.
/// Combines product info + theoretical stock + existing count data.
/// Story 6.2.
@freezed
class InventoryProductRowModel with _$InventoryProductRowModel {
  const InventoryProductRowModel._();

  const factory InventoryProductRowModel({
    required String productId,
    required String productName,
    String? sku,
    String? photoUrl,
    String? variantId,
    String? variantLabel,
    required int theoreticalQty,
    int? physicalQty,
    int? ecart,
  }) = _InventoryProductRowModel;

  /// Whether a physical count has been recorded.
  bool get isCounted => physicalQty != null;

  /// Composite key for product+variant lookup.
  String get compositeKey => '$productId:${variantId ?? 'null'}';

  factory InventoryProductRowModel.fromJson(Map<String, dynamic> json) =>
      _$InventoryProductRowModelFromJson(json);
}
