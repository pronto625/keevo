import '../model/inventory_count_model.dart';
import '../model/inventory_product_row_model.dart';

/// InventoryCountRepository — domain port for inventory count operations.
/// Story 6.2.
abstract class InventoryCountRepository {
  /// Get products in scope for the counting form.
  Future<List<InventoryProductRowModel>> getCountingProducts(String sessionId);

  /// Save or update a physical count.
  Future<InventoryCountModel> saveCount({
    required String sessionId,
    required String productId,
    String? variantId,
    required String productName,
    String? variantLabel,
    required int theoretical,
    required int physical,
  });

  /// Get all counts for a session (for resume).
  Future<List<InventoryCountModel>> getCountsForSession(String sessionId);
}
