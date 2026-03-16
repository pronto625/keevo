import '../model/sale_model.dart';

/// SaleRepository — port interface for sale persistence + sync.
abstract interface class SaleRepository {
  /// Saves locally + queues sync.
  Future<void> recordSale(Sale sale);

  /// Sales for today's close of day (Story 4.3 stub).
  Future<List<Sale>> getSalesForToday(String storeId);

  /// Most frequently sold product IDs for POS grid.
  Future<List<String>> getFrequentProductIds(String storeId, {int limit = 12});
}
