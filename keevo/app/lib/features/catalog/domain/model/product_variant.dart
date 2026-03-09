import 'package:freezed_annotation/freezed_annotation.dart';

part 'product_variant.freezed.dart';
part 'product_variant.g.dart';

/// AC4: Product variant for sector-specific configurations
/// Only applies to "Vêtements & Shopping" sector
@freezed
class ProductVariant with _$ProductVariant {
  const factory ProductVariant({
    required String id,
    required String productId,
    @Default([]) List<VariantAxis> axes,
    @Default(0) int stockQuantity,
    @Default(0) int additionalPrice,  // Price difference from base product
  }) = _ProductVariant;

  factory ProductVariant.fromJson(Map<String, dynamic> json) => _$ProductVariantFromJson(json);
}

/// Variant axis for size, color, etc.
@freezed  
class VariantAxis with _$VariantAxis {
  const factory VariantAxis({
    required String type,  // "taille" or "couleur"
    required String value, // "M" or "#FF0000" 
  }) = _VariantAxis;

  factory VariantAxis.fromJson(Map<String, dynamic> json) => _$VariantAxisFromJson(json);
}

/// Predefined clothing sizes (editable)
enum ClothingSize {
  xs('XS'),
  s('S'), 
  m('M'),
  l('L'),
  xl('XL'),
  xxl('XXL');

  const ClothingSize(this.label);
  final String label;
}