import '../model/product_model.dart';
import '../repository/product_repository.dart';

/// GetProductsUseCase — fetch and search the local product catalogue.
///
/// AC7: search works fully offline (debounce 300ms applied in presentation layer).
class GetProductsUseCase {
  final ProductRepository _repository;

  const GetProductsUseCase(this._repository);

  /// Returns all non-archived products. Pass [query] to filter.
  Future<List<ProductModel>> execute({String? query}) {
    if (query != null && query.trim().isNotEmpty) {
      return _repository.search(query.trim());
    }
    return _repository.getAll();
  }

  /// Returns archived products only.
  Future<List<ProductModel>> executeArchived() => _repository.getArchived();
}
