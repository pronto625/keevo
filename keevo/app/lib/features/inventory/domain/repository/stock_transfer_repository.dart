import '../model/stock_transfer_model.dart';

/// StockTransferRepository — domain port for transfer operations.
/// Story 3.3.
abstract class StockTransferRepository {
  /// Execute a transfer. Returns the created transfer record.
  Future<StockTransferModel> executeTransfer({
    required String sourceStoreId,
    required String destinationStoreId,
    required String productId,
    String? variantId,
    required int quantity,
    String? notes,
  });

  /// Step 2 — Receive a transfer. Marks it COMPLETED and credits destination.
  Future<StockTransferModel> completeTransfer(String transferId);

  /// Paginated transfer history with optional filters.
  Future<List<StockTransferModel>> getHistory({
    String? sourceStoreId,
    String? destinationStoreId,
    int page = 0,
    int pageSize = 20,
  });
}
