import 'package:freezed_annotation/freezed_annotation.dart';

part 'store_product_stock_model.freezed.dart';
part 'store_product_stock_model.g.dart';

/// StoreProductStockModel — stock level for one product (or variant) in one store.
/// Mirrors StoreProductStockEntry backend domain model.
/// Story 3.2. GoF: Composite leaf.
@freezed
class StoreProductStockModel with _$StoreProductStockModel {
  const factory StoreProductStockModel({
    required String productId,
    required String productName,
    String? variantId,
    String? variantLabel,
    required String storeId,
    required int quantity,
    @Default(0) int minimumThreshold,
    required String status,   // 'NORMAL' | 'BAS' | 'CRITIQUE'
    required bool isLow,
    required bool isCritical,
  }) = _StoreProductStockModel;

  factory StoreProductStockModel.fromJson(Map<String, dynamic> json) =>
      _$StoreProductStockModelFromJson(json);
}
