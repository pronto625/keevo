import 'package:freezed_annotation/freezed_annotation.dart';

part 'product_response_dto.freezed.dart';
part 'product_response_dto.g.dart';

/// ProductResponseDto — backend API response for a product.
///
/// Returned by GET /api/v1/products and POST /api/v1/products.
/// Maps directly from the backend [ProductResponseDto] JSON structure.
@freezed
class ProductResponseDto with _$ProductResponseDto {
  const factory ProductResponseDto({
    required String id,
    required String name,
    String? description,
    required String sku,
    String? categoryId,
    @Default(0) int price,
    @Default(0) int buyPrice,
    @Default(0) int stockQuantity,
    String? photoUrl,
    @Default(false) bool archived,
    @Default('ACTIVE') String status,
    required String createdAt,
    required String updatedAt,
  }) = _ProductResponseDto;

  factory ProductResponseDto.fromJson(Map<String, dynamic> json) =>
      _$ProductResponseDtoFromJson(json);
}
