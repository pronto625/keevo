import '../exception/product_exception.dart';
import '../model/product_model.dart';
import '../repository/product_repository.dart';

/// CreateProductUseCase — validate and create a product locally.
///
/// AC1: validates name (required) before delegating to [ProductRepository].
/// AC2: saves locally + queues for backend sync.
class CreateProductUseCase {
  final ProductRepository _repository;

  const CreateProductUseCase(this._repository);

  Future<ProductModel> execute({
    required String name,
    String? description,
    String? sku,
    String? categoryId,
    int price = 0,
    int buyPrice = 0,
    int transportCost = 0,
    String? photoUrl,
  }) {
    final trimmed = name.trim();
    if (trimmed.isEmpty) {
      throw const ProductException(
        domainCode: 'VALIDATION_ERROR',
        message: 'Le nom du produit est requis',
      );
    }
    if (price < 0) {
      throw const ProductException(
        domainCode: 'VALIDATION_ERROR',
        message: 'Le prix ne peut pas être négatif',
      );
    }
    if (transportCost < 0) {
      throw const ProductException(
        domainCode: 'VALIDATION_ERROR',
        message: 'Le coût de transport ne peut pas être négatif',
      );
    }

    return _repository.create(
      name: trimmed,
      description: description?.trim(),
      sku: sku?.trim(),
      categoryId: categoryId,
      price: price,
      buyPrice: buyPrice,
      transportCost: transportCost,
      photoUrl: photoUrl,
    );
  }
}
