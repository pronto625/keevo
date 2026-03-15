import '../model/cross_store_availability_model.dart';
import '../model/stock_level_model.dart';
import '../model/stock_movement_model.dart';

/// StockRepository — port (interface) for stock data access.
///
/// Implementations: [StockRepositoryImpl] (data/repository/).
/// Story 2.3.
abstract class StockRepository {
  // ── Stock Levels ─────────────────────────────────────────────────────────

  /// Returns all stock levels for [productId] across all stores.
  Future<List<StockLevelModel>> getLevels(String productId);

  /// Returns the stock level for a specific [productId] in [storeId].
  /// Returns null if not yet stocked.
  Future<StockLevelModel?> getLevelByStore(String productId, String storeId);

  // ── Stock Movements ───────────────────────────────────────────────────────

  /// Returns the movement history for [productId], newest first.
  ///
  /// Paginated: [page] 0-based, [pageSize] default 20.
  Future<List<StockMovementModel>> getMovementHistory(
    String productId, {
    String? storeId,
    String? movementType,
    DateTime? from,
    DateTime? to,
    int page = 0,
    int pageSize = 20,
  });

  // ── Cross-store availability ───────────────────────────────────────────────

  /// Returns availability for [productId] across all active stores (Story 3.4).
  ///
  /// Online-first with 3 s timeout; falls back to local Drift data on error.
  Future<CrossStoreAvailabilityModel> getCrossStoreAvailability(
      String productId);

  // ── Mutations ─────────────────────────────────────────────────────────────

  /// Records a stock entry (+quantity).
  Future<StockMovementModel> recordEntry({
    required String productId,
    String? variantId,
    required String storeId,
    required int quantity,
    String? notes,
  });

  /// Adjusts stock to an absolute [newQuantity].
  Future<StockMovementModel> adjustStock({
    required String productId,
    String? variantId,
    required String storeId,
    required int newQuantity,
    required String notes,
  });

  /// Sets the minimum stock threshold for [productId].
  Future<void> setThreshold({
    required String productId,
    required int minimumThreshold,
  });
}
