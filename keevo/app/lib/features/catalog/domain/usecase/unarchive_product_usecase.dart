import '../repository/product_repository.dart';

/// UnarchiveProductUseCase — restore a product locally.
///
/// AC6: sets archived=false; product reappears in POS and catalogue.
class UnarchiveProductUseCase {
  final ProductRepository _repository;

  const UnarchiveProductUseCase(this._repository);

  Future<void> execute(String productId) => _repository.unarchive(productId);
}