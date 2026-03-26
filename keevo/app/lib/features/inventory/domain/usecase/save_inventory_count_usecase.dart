import '../model/inventory_count_model.dart';
import '../repository/inventory_count_repository.dart';

/// SaveInventoryCountUseCase — delegates physical count save to repository.
/// Story 6.2.
class SaveInventoryCountUseCase {
  final InventoryCountRepository _repository;

  SaveInventoryCountUseCase(this._repository);

  Future<InventoryCountModel> execute({
    required String sessionId,
    required String productId,
    String? variantId,
    required String productName,
    String? variantLabel,
    required int theoretical,
    required int physical,
  }) {
    return _repository.saveCount(
      sessionId: sessionId,
      productId: productId,
      variantId: variantId,
      productName: productName,
      variantLabel: variantLabel,
      theoretical: theoretical,
      physical: physical,
    );
  }
}
