import '../model/sale_model.dart';

/// SaleRepository — port interface for sale persistence + sync.
abstract interface class SaleRepository {
  /// Saves locally + queues sync.
  Future<void> recordSale(Sale sale);

  /// Sales for today's close of day (Story 4.3 stub).
  Future<List<Sale>> getSalesForToday(String storeId);

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

  /// Cancel a pending sale (OWNER-only, Story 4.3).
  Future<void> cancelSale(String saleId, String justification);
}
