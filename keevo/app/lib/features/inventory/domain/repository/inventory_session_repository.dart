import '../model/inventory_session_model.dart';

/// InventorySessionRepository — domain port for inventory session operations.
/// Story 6.1.
abstract class InventorySessionRepository {
  /// Create a new inventory session.
  Future<InventorySessionModel> create({
    required String storeId,
    required String scope,
    List<String>? categoryIds,
  });

  /// Get the active (IN_PROGRESS) session for a store, if any.
  Future<InventorySessionModel?> getActiveByStoreId(String storeId);

  /// Cancel a session by ID.
  Future<void> cancel(String sessionId);

  /// Get paginated session history.
  Future<List<InventorySessionModel>> getHistory({
    int page = 0,
    int size = 20,
  });
}
