import 'package:drift/drift.dart';

import '../../../../core/storage/app_database.dart';
import '../../domain/model/stock_transfer_model.dart';

/// LocalStockTransferDataSource — Drift-backed offline store for transfers.
///
/// Story 3.3.
class LocalStockTransferDataSource {
  final AppDatabase _db;

  const LocalStockTransferDataSource(this._db);

  /// Returns current local stock quantity for a product/store pair.
  /// Returns 0 if no stock_levels row exists.
  Future<int> getLocalStock({
    required String productId,
    String? variantId,
    required String storeId,
  }) async {
    final query = _db.select(_db.stockLevels)
      ..where((sl) =>
          sl.productId.equals(productId) & sl.storeId.equals(storeId));
    final rows = await query.get();
    return rows.isEmpty ? 0 : rows.first.quantity;
  }

  /// Apply local stock change atomically: decrement source, upsert destination.
  /// Called during offline transfer to keep Drift stock_levels consistent.
  Future<void> applyLocalStockChange({
    required String productId,
    String? variantId,
    required String sourceStoreId,
    required String destinationStoreId,
    required int quantity,
  }) async {
    await _db.transaction(() async {
      // Atomic decrement on source
      await _db.customUpdate(
        'UPDATE stock_levels SET quantity = quantity - ?, updated_at = ? '
        'WHERE product_id = ? AND store_id = ?',
        variables: [
          Variable<int>(quantity),
          Variable<DateTime>(DateTime.now()),
          Variable<String>(productId),
          Variable<String>(sourceStoreId),
        ],
        updates: {_db.stockLevels},
      );
      // Upsert destination: increment if row exists, else create at quantity.
      // stock_levels has no UNIQUE(product_id, store_id) constraint, so we
      // cannot use ON CONFLICT(...). Use UPDATE-then-INSERT instead.
      final updated = await _db.customUpdate(
        'UPDATE stock_levels SET quantity = quantity + ?, updated_at = ? '
        'WHERE product_id = ? AND store_id = ?',
        variables: [
          Variable<int>(quantity),
          Variable<DateTime>(DateTime.now()),
          Variable<String>(productId),
          Variable<String>(destinationStoreId),
        ],
        updates: {_db.stockLevels},
      );
      if (updated == 0) {
        // No existing row for this product/destination — create one.
        await _db.into(_db.stockLevels).insert(
          StockLevelsCompanion.insert(
            id: 'sl-${productId.substring(0, 8)}-${destinationStoreId.substring(0, 8)}',
            productId: productId,
            variantId: Value(variantId),
            storeId: destinationStoreId,
            quantity: quantity,
            updatedAt: DateTime.now(),
          ),
        );
      }
    });
  }

  /// Upsert a transfer into local storage.
  Future<void> saveTransfer(StockTransferModel transfer) async {
    await _db.into(_db.stockTransfers).insertOnConflictUpdate(
      StockTransfersCompanion(
        id: Value(transfer.id),
        sourceStoreId: Value(transfer.sourceStoreId),
        destinationStoreId: Value(transfer.destinationStoreId),
        productId: Value(transfer.productId),
        variantId: Value(transfer.variantId),
        quantity: Value(transfer.quantity),
        actorId: Value(transfer.actorId),
        occurredAt: Value(transfer.occurredAt),
        status: Value(transfer.status),
        notes: Value(transfer.notes),
        sourceStoreName: Value(transfer.sourceStoreName),
        destinationStoreName: Value(transfer.destinationStoreName),
        productName: Value(transfer.productName),
        variantLabel: Value(transfer.variantLabel),
      ),
    );
  }

  /// Query local transfer history with optional filters.
  Future<List<StockTransferModel>> getHistory({
    String? sourceStoreId,
    String? destinationStoreId,
    String? productId,
    int page = 0,
    int pageSize = 20,
  }) async {
    final query = _db.select(_db.stockTransfers)
      ..where((t) {
        Expression<bool> where = const Constant(true);
        if (sourceStoreId != null) {
          where = where & t.sourceStoreId.equals(sourceStoreId);
        }
        if (destinationStoreId != null) {
          where = where & t.destinationStoreId.equals(destinationStoreId);
        }
        if (productId != null) {
          where = where & t.productId.equals(productId);
        }
        return where;
      })
      ..orderBy([(t) => OrderingTerm.desc(t.occurredAt)])
      ..limit(pageSize, offset: page * pageSize);

    final rows = await query.get();
    return rows.map(_toModel).toList();
  }

  StockTransferModel _toModel(StockTransfer row) => StockTransferModel(
        id: row.id,
        sourceStoreId: row.sourceStoreId,
        destinationStoreId: row.destinationStoreId,
        productId: row.productId,
        variantId: row.variantId,
        quantity: row.quantity,
        actorId: row.actorId,
        occurredAt: row.occurredAt,
        status: row.status,
        notes: row.notes,
        sourceStoreName: row.sourceStoreName,
        destinationStoreName: row.destinationStoreName,
        productName: row.productName,
        variantLabel: row.variantLabel,
      );
}
