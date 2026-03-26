import '../repository/inventory_session_repository.dart';

/// CancelInventorySessionUseCase — delegates session cancellation.
/// Story 6.1.
class CancelInventorySessionUseCase {
  final InventorySessionRepository _repository;

  CancelInventorySessionUseCase(this._repository);

  Future<void> execute(String sessionId) {
    return _repository.cancel(sessionId);
  }
}
