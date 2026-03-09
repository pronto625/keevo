import '../repository/product_repository.dart';

/// ArchiveProductUseCase — soft-delete a product locally.
///
/// AC6: sets archived=true; product disappears from POS but history preserved.
/// No product is ever permanently deleted.
/// Operation queued for backend sync.
class ArchiveProductUseCase {
  final ProductRepository _repository;

  const ArchiveProductUseCase(this._repository);

  Future<void> execute(String productId) => _repository.archive(productId);
}
