import '../model/sale_model.dart';
import '../model/sales_history_filter.dart';

/// SaleRepository — port interface for sale persistence + sync.
abstract interface class SaleRepository {
  /// Saves locally + queues sync.
  Future<void> recordSale(Sale sale);

  /// Sales for today's close of day (Story 4.3 stub).
  Future<List<Sale>> getSalesForToday(String storeId);

  /// Sales history with date range filter (Story 4.4).
  ///
  /// Returns sales matching the filter criteria, sorted by occurredAt DESC.
  Future<List<Sale>> getSalesHistory(SalesHistoryFilter filter);

  /// Most frequently sold product IDs for POS grid.
  Future<List<String>> getFrequentProductIds(String storeId, {int limit = 12});

  /// Pending validation sales for OWNER review (Story 4.3).
  /// [storeId] null = all stores.
  Future<List<Sale>> getPendingSales(String? storeId);

  /// Count pending validation sales (for badge).
  /// [storeId] null = all stores.
  Future<int> countPendingSales(String? storeId);

  /// Manually validate a pending sale (OWNER-only, Story 4.3).
  /// [productIdRemappings] maps old draft product IDs to new promoted IDs.
  /// [initialStockEntries] maps promoted product IDs to initial stock quantities.
  Future<void> validateSale(String saleId, String justification,
      {Map<String, String>? productIdRemappings,
      Map<String, int>? initialStockEntries});

  /// Cancel a sale (OWNER-only, Story 4.3 for PENDING; Story v1s-13-5 for COMPLETED
  /// with stock restoration — online-only for COMPLETED, see Décision D2).
  Future<void> cancelSale(String saleId, String justification);

  /// Correct item quantities on a COMPLETED sale (OWNER-only, online-only, Story v1s-13-5).
  /// [itemQuantities] maps saleItemId -> new quantity, only for changed items.
  Future<void> correctSale(
      String saleId, String justification, Map<String, int> itemQuantities);

  /// Get a single sale by ID (Story 4.4 — Sale Detail Page).
  Future<Sale?> getSaleById(String saleId);
}
