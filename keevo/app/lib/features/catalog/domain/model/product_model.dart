import 'package:freezed_annotation/freezed_annotation.dart';

import 'product_status.dart';

part 'product_model.freezed.dart';
part 'product_model.g.dart';

/// ProductModel — domain model for a product in the local catalogue.
///
/// Immutable Freezed value object — auto-generates equality, hashCode,
/// copyWith and toString.
///
/// Mirrors the [Products] Drift table and the backend [Product] entity.
@freezed
class ProductModel with _$ProductModel {
  const factory ProductModel({
    required String id,
    required String name,
    String? description,

    /// SKU reference — `KEV-XXXXXX` format.
    @Default('') String sku,

    String? categoryId,

    /// Price in XAF — integer only (no decimals).
    @Default(0) int price,

    /// Buy price in XAF — integer only.
    @Default(0) int buyPrice,

    @Default(0) int stockQuantity,
    String? storeId,
    String? photoUrl,

    /// When true, product is hidden from POS but preserved in archive.
    @Default(false) bool archived,

    @Default(ProductStatus.active) ProductStatus status,

    required DateTime createdAt,
    required DateTime updatedAt,
  }) = _ProductModel;

  factory ProductModel.fromJson(Map<String, dynamic> json) =>
      _$ProductModelFromJson(json);
}
