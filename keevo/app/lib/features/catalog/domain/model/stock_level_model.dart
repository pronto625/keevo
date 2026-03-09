import 'package:freezed_annotation/freezed_annotation.dart';

part 'stock_level_model.freezed.dart';
part 'stock_level_model.g.dart';

/// StockLevelModel — current stock quantity for a product in a store.
///
/// Mirrors [StockLevel] backend domain entity.
/// Freezed: auto-generates equality, hashCode, copyWith, toString.
///
/// Story 2.3.
@freezed
class StockLevelModel with _$StockLevelModel {
  const StockLevelModel._();

  const factory StockLevelModel({
    required String id,
    required String productId,

    /// Nullable — only for variant products.
    String? variantId,

    required String storeId,
    required int quantity,

    /// Alert threshold. 0 = no alert configured (Story 2.3).
    @Default(0) int minimumThreshold,

    required DateTime updatedAt,
  }) = _StockLevelModel;

  factory StockLevelModel.fromJson(Map<String, dynamic> json) =>
      _$StockLevelModelFromJson(json);

  /// Returns true when stock is at or below the configured threshold.
  ///
  /// Returns false when threshold is 0 (no alert configured).
  bool get isLow => minimumThreshold > 0 && quantity <= minimumThreshold;
}
