import '../model/stock_level_model.dart';
import '../repository/stock_repository.dart';

/// GetStockLevelUseCase — retrieves current stock levels for a product.
///
/// Story 2.3.
class GetStockLevelUseCase {
  final StockRepository _repository;

  const GetStockLevelUseCase(this._repository);

  /// Returns all stock levels across all stores.
  Future<List<StockLevelModel>> execute(String productId) =>
      _repository.getLevels(productId);
}
