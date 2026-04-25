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
      ..where((sl) {
        final baseFilter =
            sl.productId.equals(productId) & sl.storeId.equals(storeId);
        if (variantId == null) {
          return baseFilter & sl.variantId.isNull();
        }
        return baseFilter & sl.variantId.equals(variantId);
      });
    final rows = await query.get();
    return rows.isEmpty ? 0 : rows.first.quantity;
  }

  /// Decrement source store stock only (Step 1 of two-step transfer — AC1).
  ///
  /// Destination stock is NOT credited here; it is only credited when the
  /// transfer is completed via [applyDestinationIncrement] (Step 2).
  Future<void> applySourceDecrement({
    required String productId,
    String? variantId,
    required String sourceStoreId,
    required int quantity,
  }) async {
    final variantFilter = variantId ?? '';
    await _db.customUpdate(
      'UPDATE stock_levels SET quantity = quantity - ?, updated_at = ? '
      'WHERE product_id = ? AND store_id = ? '
      'AND ((variant_id IS NULL AND ? = \'\') OR variant_id = ?)',
      variables: [
        Variable<int>(quantity),
        Variable<DateTime>(DateTime.now()),
        Variable<String>(productId),
        Variable<String>(sourceStoreId),
        Variable<String>(variantFilter),
        Variable<String>(variantFilter),
      ],
      updates: {_db.stockLevels},
    );
  }

  /// Increment destination store stock (Step 2 — transfer completion — AC1).
  ///
  /// Upserts the destination row: increments if it already exists, creates it
  /// at [quantity] if it does not.
  Future<void> applyDestinationIncrement({
    required String productId,
    String? variantId,
    required String destinationStoreId,
    required int quantity,
  }) async {
    final variantFilter = variantId ?? '';
    final updated = await _db.customUpdate(
      'UPDATE stock_levels SET quantity = quantity + ?, updated_at = ? '
      'WHERE product_id = ? AND store_id = ? '
      'AND ((variant_id IS NULL AND ? = \'\') OR variant_id = ?)',
      variables: [
        Variable<int>(quantity),
        Variable<DateTime>(DateTime.now()),
        Variable<String>(productId),
        Variable<String>(destinationStoreId),
        Variable<String>(variantFilter),
        Variable<String>(variantFilter),
      ],
      updates: {_db.stockLevels},
    );
    if (updated == 0) {
      final variantSlug = (variantId == null || variantId.isEmpty)
          ? 'base'
          : variantId.substring(0, variantId.length > 8 ? 8 : variantId.length);
      await _db.into(_db.stockLevels).insert(
            StockLevelsCompanion.insert(
              id: 'sl-${productId.substring(0, 8)}-${destinationStoreId.substring(0, 8)}-$variantSlug',
              productId: productId,
              variantId: Value(variantId),
              storeId: destinationStoreId,
              quantity: quantity,
              updatedAt: DateTime.now(),
            ),
          );
    }
  }

  /// Apply local stock change atomically: decrement source AND upsert destination.
  ///
  /// @deprecated Prefer [applySourceDecrement] at Step 1 and [applyDestinationIncrement]
  /// at Step 2 to respect the two-step transfer contract (AC1).
  Future<void> applyLocalStockChange({
    required String productId,
    String? variantId,
    required String sourceStoreId,
    required String destinationStoreId,
    required int quantity,
  }) async {
    await applySourceDecrement(
      productId: productId,
      variantId: variantId,
      sourceStoreId: sourceStoreId,
      quantity: quantity,
    );
    await applyDestinationIncrement(
      productId: productId,
      variantId: variantId,
      destinationStoreId: destinationStoreId,
      quantity: quantity,
    );
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

  Future<StockTransferModel?> getTransferById(String transferId) async {
    final row = await (_db.select(_db.stockTransfers)
          ..where((t) => t.id.equals(transferId)))
        .getSingleOrNull();
    if (row == null) return null;
    return _toModel(row);
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
