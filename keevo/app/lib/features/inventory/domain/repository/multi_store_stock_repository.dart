import '../model/store_product_stock_model.dart';
import '../model/store_stock_summary_model.dart';

/// MultiStoreStockRepository — domain port for multi-store stock aggregation.
/// Story 3.2.
abstract class MultiStoreStockRepository {
  /// Returns aggregated stock summary for all active stores.
  /// Loads from local Drift cache; background fetch from remote when online.
  Future<List<StoreStockSummaryModel>> getOverview();

  /// Returns paginated product stock list for a specific store.
  /// [sortLowFirst]: if true, BAS/CRITIQUE entries come first.
  Future<List<StoreProductStockModel>> getStoreStockDetail(
      String storeId, {int page = 0, int size = 25, bool sortLowFirst = true});

  /// Cross-store search against local Drift data only (offline-capable).
  /// Returns all (product, store) pairs where product name matches [query].
  Future<List<StoreProductStockModel>> searchAcrossStores(String query);
}
