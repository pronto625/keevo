import '../model/stock_movement_model.dart';
import '../repository/stock_repository.dart';

/// GetStockHistoryUseCase — paginated movement history for a product.
///
/// Story 2.3.
class GetStockHistoryUseCase {
  final StockRepository _repository;

  const GetStockHistoryUseCase(this._repository);

  Future<List<StockMovementModel>> execute(
    String productId, {
    String? storeId,
    String? movementType,
    DateTime? from,
    DateTime? to,
    int page = 0,
    int pageSize = 20,
  }) =>
      _repository.getMovementHistory(
        productId,
        storeId: storeId,
        movementType: movementType,
        from: from,
        to: to,
        page: page,
        pageSize: pageSize,
      );
}
