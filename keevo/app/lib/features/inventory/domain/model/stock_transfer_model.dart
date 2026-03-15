import 'package:freezed_annotation/freezed_annotation.dart';

part 'stock_transfer_model.freezed.dart';
part 'stock_transfer_model.g.dart';

/// StockTransferModel — transfer record.
/// Mirrors StockTransfer backend domain model.
/// Story 3.3. GoF: Command value object.
@freezed
class StockTransferModel with _$StockTransferModel {
  const factory StockTransferModel({
    required String id,
    required String sourceStoreId,
    required String destinationStoreId,
    required String productId,
    String? variantId,
    required int quantity,
    required String actorId,
    required DateTime occurredAt,
    required String status, // 'IN_TRANSIT' | 'COMPLETED' | 'PENDING_SYNC' | 'CONFLICT'
    String? notes,
    // Denormalized display fields (populated by UI layer from local Drift)
    @Default('') String sourceStoreName,
    @Default('') String destinationStoreName,
    @Default('') String productName,
    @Default('') String variantLabel,
  }) = _StockTransferModel;

  factory StockTransferModel.fromJson(Map<String, dynamic> json) =>
      _$StockTransferModelFromJson(json);
}

extension StockTransferModelX on StockTransferModel {
  bool get isInTransit => status == 'IN_TRANSIT';
  bool get isPendingSync => status == 'PENDING_SYNC';
  bool get isCompleted => status == 'COMPLETED';
  bool get isConflict => status == 'CONFLICT';
}
