import '../model/stock_transfer_model.dart';
import '../repository/stock_transfer_repository.dart';

/// ExecuteTransferUseCase — validates and delegates transfer execution.
///
/// Performs client-side validation before hitting the repository so
/// offline failures are caught early without a network round-trip.
/// Story 3.3.
class ExecuteTransferUseCase {
  final StockTransferRepository _repository;

  ExecuteTransferUseCase(this._repository);

  Future<StockTransferModel> execute({
    required String sourceStoreId,
    required String destinationStoreId,
    required String productId,
    String? variantId,
    required int quantity,
    String? notes,
  }) {
    if (sourceStoreId == destinationStoreId) {
      throw ArgumentError('sourceStoreId and destinationStoreId must differ');
    }
    if (quantity <= 0) {
      throw ArgumentError('quantity must be positive');
    }
    return _repository.executeTransfer(
      sourceStoreId: sourceStoreId,
      destinationStoreId: destinationStoreId,
      productId: productId,
      variantId: variantId,
      quantity: quantity,
      notes: notes,
    );
  }
}
