import '../exception/product_exception.dart';
import '../model/product_model.dart';
import '../repository/product_repository.dart';

/// UpdateProductUseCase — validate and update a product locally.
///
/// AC5: validates non-empty name, then delegates to [ProductRepository].
/// Queued for backend sync via sync engine.
class UpdateProductUseCase {
  final ProductRepository _repository;

  const UpdateProductUseCase(this._repository);

  Future<ProductModel> execute({
    required String id,
    String? name,
    String? description,
    String? sku,
    String? categoryId,
    int? price,
    int? buyPrice,
    int? transportCost,
    String? photoUrl,
  }) {
    if (name != null && name.trim().isEmpty) {
      throw const ProductException(
        domainCode: 'VALIDATION_ERROR',
        message: 'Le nom du produit ne peut pas être vide',
      );
    }
    if (price != null && price < 0) {
      throw const ProductException(
        domainCode: 'VALIDATION_ERROR',
        message: 'Le prix ne peut pas être négatif',
      );
    }
    if (transportCost != null && transportCost < 0) {
      throw const ProductException(
        domainCode: 'VALIDATION_ERROR',
        message: 'Le coût de transport ne peut pas être négatif',
      );
    }

    return _repository.update(
      id: id,
      name: name?.trim(),
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
