import '../model/inventory_session_model.dart';
import '../repository/inventory_session_repository.dart';

/// CreateInventorySessionUseCase — validates and delegates session creation.
/// Story 6.1.
class CreateInventorySessionUseCase {
  final InventorySessionRepository _repository;

  CreateInventorySessionUseCase(this._repository);

  Future<InventorySessionModel> execute({
    required String storeId,
    required String scope,
    List<String>? categoryIds,
  }) {
    if (scope == 'PARTIAL' && (categoryIds == null || categoryIds.isEmpty)) {
      throw ArgumentError('PARTIAL scope requires at least one category');
    }
    return _repository.create(
      storeId: storeId,
      scope: scope,
      categoryIds: categoryIds,
    );
  }
}
