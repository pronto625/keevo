import 'package:freezed_annotation/freezed_annotation.dart';

part 'stock_movement_model.freezed.dart';
part 'stock_movement_model.g.dart';

/// StockMovementModel — immutable domain model for a stock movement record.
///
/// Mirrors [StockMovement] backend domain entity.
/// Freezed: auto-generates equality, hashCode, copyWith, toString.
///
/// Story 2.3.
@freezed
class StockMovementModel with _$StockMovementModel {
  const factory StockMovementModel({
    required String id,
    required String productId,

    /// Nullable — only for variant products.
    String? variantId,

    required String storeId,

    /// 'SALE' | 'STOCK_ENTRY' | 'TRANSFER_IN' | 'TRANSFER_OUT' | 'ADJUSTMENT'
    required String movementType,

    @Default(0) int quantityBefore,

    /// Signed delta: positive = in, negative = out.
    @Default(0) int quantityDelta,

    @Default(0) int quantityAfter,

    required String actorId,
    String? notes,

    required DateTime occurredAt,

    @Default(false) bool synced,
    DateTime? syncedAt,
  }) = _StockMovementModel;

  factory StockMovementModel.fromJson(Map<String, dynamic> json) =>
      _$StockMovementModelFromJson(json);
}
