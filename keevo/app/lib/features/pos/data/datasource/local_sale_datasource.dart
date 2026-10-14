import 'dart:math';

import 'package:drift/drift.dart';
import 'package:uuid/uuid.dart';

import '../../../../core/storage/app_database.dart' hide Sale, SaleItem;
import '../../../../core/sync/stock_movement_ids.dart';
import '../../domain/model/sale_model.dart';

/// LocalSaleDataSource — persists sale data in Drift (SQLCipher).
///
/// All writes in [insertAll] run in a single atomic transaction. As of
/// Story 13.4 (F-HIGH-4), [insertAll] is itself normally called from inside
/// `SaleRepositoryImpl.recordSale()`'s own outer `_db.transaction()` — Drift
/// safely nests the two (no savepoint needed, same connection/zone), so
/// [insertAll] remains atomic on its own AND rolls back with the outer
/// transaction if a later step (e.g. a `queueOperation` call) fails.
class LocalSaleDataSource {
  final AppDatabase _db;

  const LocalSaleDataSource(this._db);

  /// Atomically persist: sale, sale_items, stock decrements, stock movements.
  ///
  /// [synced] controls the initial sync state — true when backend confirmed,
  /// false when saving offline (sync_queue managed by repository layer).
  Future<void> insertAll(Sale sale, {bool synced = false}) async {
    await _db.transaction(() async {
      // 1. Insert sale
      await _db.into(_db.sales).insert(SalesCompanion.insert(
        id: sale.id,
        storeId: sale.storeId,
        employeeId: sale.employeeId,
        totalAmount: sale.totalAmount,
        discountAmount: Value(sale.discountAmount),
        paymentMode: sale.paymentMode.value,
        mobileMoneyRef: Value(sale.mobileMoneyRef),
        clientId: Value(sale.clientId),
        status: Value(sale.status),
        occurredAt: Value(sale.occurredAt),
        createdAt: sale.createdAt,
        synced: Value(synced),
      ));

      // 2. Insert sale items
      for (final item in sale.items) {
        await _db.into(_db.saleItems).insert(SaleItemsCompanion.insert(
          id: item.id,
          saleId: sale.id,
          productId: item.productId,
          productName: item.productName,
          unitPrice: item.appliedUnitPrice,
          catalogueUnitPrice: Value(item.catalogueUnitPrice),
          quantity: item.quantity,
          subtotal: item.subtotal,
          variantId: Value(item.variantId),
          createdAt: sale.createdAt,
        ));
      }

      // 3. Decrement stock levels + insert stock movements.
      //    Skip for PENDING_VALIDATION — stock is decremented at validation time
      //    (matches backend RecordSaleService behavior).
      if (sale.status != 'PENDING_VALIDATION') {
        for (final item in sale.items) {
          // B1: use .get() to handle potential duplicate (product_id, store_id) rows.
          final stockRows = await (_db.select(_db.stockLevels)
                ..where((s) =>
                    s.productId.equals(item.productId) &
                    s.storeId.equals(sale.storeId)))
              .get();

          // A sale recorded here already happened: the full quantity leaves
          // the shelf, as the server applies it when the sale is pushed
          // (CREATE_SALE, allowNegativeStock) — stock may go negative and a
          // product never stocked in this store gets its row. The movement's
          // before/after are the real local values (before − sold = after).
          final quantityBefore = stockRows.isEmpty
              ? 0
              : stockRows.fold<int>(stockRows.first.quantity,
                  (max, r) => r.quantity > max ? r.quantity : max);
          final quantityAfter = quantityBefore - item.quantity;

          await _writeStockQuantity(
              item.productId, sale.storeId, quantityAfter, sale.createdAt);

          await _db.into(_db.stockMovements).insert(StockMovementsCompanion.insert(
            // v1s-13-8: SALE movement id = sale item id (also the CREATE_SALE
            // payload's itemId, used server-side as the movement id) so the
            // pull upserts this row instead of adding a second SALE movement.
            id: item.id,
            productId: item.productId,
            storeId: sale.storeId,
            type: 'SALE',
            quantityDelta: -item.quantity,
            actorId: sale.employeeId,
            quantityBefore: Value(quantityBefore),
            quantityAfter: Value(quantityAfter),
            createdAt: sale.createdAt,
          ));
        }
      }
    });
  }

  /// Mark a sale as synced (runs outside the main transaction).
  Future<void> markSynced(String saleId) async {
    await (_db.update(_db.sales)
          ..where((s) => s.id.equals(saleId)))
        .write(SalesCompanion(
      synced: const Value(true),
      syncedAt: Value(DateTime.now()),
    ));
  }

  /// Remove the sync queue entry for a sale (runs outside the main transaction).
  Future<void> removeSyncQueueEntry(String saleId) async {
    await (_db.delete(_db.syncQueue)
          ..where((q) =>
              q.operation.equals('CREATE_SALE') &
              q.payload.contains(saleId)))
        .go();
  }

  /// Get available stock for a product in a store.
  Future<int> getAvailableStock(String productId, String storeId) async {
    // B1: use .get() instead of .getSingleOrNull() — duplicate rows can exist
    // when upsertLevel() is bypassed (catalog insert / sync pull races).
    // Use MAX (not SUM) — duplicates represent the same physical stock, not
    // additional stock. Consistent with getLevelByStore() in LocalStockDataSource.
    final rows = await (_db.select(_db.stockLevels)
          ..where((s) =>
              s.productId.equals(productId) &
              s.storeId.equals(storeId)))
        .get();
    if (rows.isEmpty) return 0;
    return rows.map((r) => r.quantity).reduce((a, b) => a > b ? a : b);
  }

  /// Whether a `stock_levels` row exists for [productId] in [storeId]
  /// (v1s-12-10). Unlike [getAvailableStock], tells "never stocked" (false)
  /// apart from "sold out" (true, quantity 0): a draft already stocked — e.g.
  /// sold earlier by an employee and still awaiting owner validation — must
  /// not be prompted for an initial stock again.
  Future<bool> hasStockRecord(String productId, String storeId) async {
    final row = await (_db.select(_db.stockLevels)
          ..where((s) =>
              s.productId.equals(productId) & s.storeId.equals(storeId))
          ..limit(1))
        .getSingleOrNull();
    return row != null;
  }

  /// Query pending validation sales for a store.
  Future<List<Map<String, dynamic>>> getPendingSaleRows(String storeId) async {
    final rows = await (_db.select(_db.sales)
          ..where((s) =>
              s.storeId.equals(storeId) &
              s.status.equals('PENDING_VALIDATION'))
          ..orderBy([(s) => OrderingTerm.desc(s.createdAt)]))
        .get();
    return rows
        .map((r) => {
              'id': r.id,
              'storeId': r.storeId,
              'employeeId': r.employeeId,
              'clientId': r.clientId,
              'totalAmount': r.totalAmount,
              'discountAmount': r.discountAmount,
              'paymentMode': r.paymentMode,
              'status': r.status,
              'createdAt': r.createdAt.toIso8601String(),
            })
        .toList();
  }

  /// Update local item quantities/subtotals + sale total after a successful
  /// COMPLETED-sale correction (Story v1s-13-5, AC6/AC8). Recomputes each
  /// corrected item's subtotal from its existing unitPrice, and the sale's
  /// totalAmount as the sum of all item subtotals minus the existing
  /// discountAmount — mirrors CorrectSaleService's server-side recalculation.
  ///
  /// [correctionId] (v1s-13-13) is the id sent with the correction request:
  /// each ADJUSTMENT movement gets the id the backend derives from it, so the
  /// pull does not add a second row. Null (legacy) → random movement ids.
  Future<void> updateItemQuantities(
    String saleId,
    Map<String, int> itemQuantities, {
    String? correctionId,
  }) async {
    await _db.transaction(() async {
      final saleRow = await (_db.select(_db.sales)
            ..where((s) => s.id.equals(saleId)))
          .getSingleOrNull();
      // Stock only left the shelf for a COMPLETED sale — mirror the
      // server-side ADJUSTMENT so local stock matches without a pull.
      final adjustStock = saleRow?.status == 'COMPLETED';
      final now = DateTime.now();
      for (final entry in itemQuantities.entries) {
        final item = await (_db.select(_db.saleItems)
              ..where((i) => i.id.equals(entry.key)))
            .getSingleOrNull();
        if (item == null) continue;
        if (adjustStock && item.quantity != entry.value) {
          await _adjustStock(item.productId, saleRow!.storeId,
              item.quantity - entry.value, 'ADJUSTMENT', saleRow.employeeId, now,
              movementId: correctionId != null
                  ? saleCorrectionMovementId(correctionId, item.id)
                  : const Uuid().v4());
        }
        await (_db.update(_db.saleItems)
              ..where((i) => i.id.equals(entry.key)))
            .write(SaleItemsCompanion(
          quantity: Value(entry.value),
          subtotal: Value(item.unitPrice * entry.value),
        ));
      }

      final sale = await (_db.select(_db.sales)
            ..where((s) => s.id.equals(saleId)))
          .getSingleOrNull();
      if (sale == null) return;

      final items = await (_db.select(_db.saleItems)
            ..where((i) => i.saleId.equals(saleId)))
          .get();
      final newSubtotal = items.fold<int>(0, (sum, i) => sum + i.subtotal);
      await (_db.update(_db.sales)
            ..where((s) => s.id.equals(saleId)))
          .write(SalesCompanion(
        totalAmount: Value(newSubtotal - sale.discountAmount),
      ));
    });
  }

  /// Mark a sale CANCELLED locally after the backend confirmed it, putting
  /// back on the shelf what a COMPLETED sale had taken out (PENDING sales never
  /// decremented stock). Idempotent: an already-cancelled sale is left alone.
  Future<void> cancelAndRestoreStock(String saleId) async {
    await _db.transaction(() async {
      final sale = await (_db.select(_db.sales)
            ..where((s) => s.id.equals(saleId)))
          .getSingleOrNull();
      if (sale == null || sale.status == 'CANCELLED') return;
      if (sale.status == 'COMPLETED') {
        final now = DateTime.now();
        final items = await (_db.select(_db.saleItems)
              ..where((i) => i.saleId.equals(saleId)))
            .get();
        for (final item in items) {
          await _adjustStock(item.productId, sale.storeId, item.quantity,
              'SALE_CANCELLED', sale.employeeId, now,
              movementId: saleCancelledMovementId(item.id));
        }
      }
      await (_db.update(_db.sales)..where((s) => s.id.equals(saleId)))
          .write(const SalesCompanion(status: Value('CANCELLED')));
    });
  }

  /// Current local quantity of a (product, store): the highest of its rows
  /// (B1: legacy duplicates), 0 when the product has no row in the store.
  static int _currentQuantity(List<StockLevel> rows) => rows.isEmpty
      ? 0
      : rows.fold<int>(
          rows.first.quantity, (max, r) => r.quantity > max ? r.quantity : max);

  /// What a validated sale really takes out (backend ValidateSaleService):
  /// the sold quantity, capped by what is on the shelf, never below 0.
  static int _effectiveDecrement(int available, int sold) =>
      available <= 0 ? 0 : min(available, sold);

  /// Writes the quantity of a (product, store) — update, else insert.
  ///
  /// Same approach as a stock entry (LocalStockDataSource.upsertLevel): a row
  /// created here carries a local id; the next pull reconciles it with the
  /// server row on the (product_id, store_id) unique key (ON CONFLICT → the
  /// server id replaces the local one), so no duplicate row appears.
  Future<void> _writeStockQuantity(
      String productId, String storeId, int quantity, DateTime now) async {
    final updated = await (_db.update(_db.stockLevels)
          ..where((s) => s.productId.equals(productId) & s.storeId.equals(storeId)))
        .write(StockLevelsCompanion(
      quantity: Value(quantity),
      updatedAt: Value(now),
    ));
    if (updated > 0) return;
    await _db.into(_db.stockLevels).insert(StockLevelsCompanion.insert(
      id: const Uuid().v4(),
      productId: productId,
      storeId: storeId,
      quantity: quantity,
      updatedAt: now,
    ));
  }

  /// Add [delta] to a product's stock level (a decrease is floored at 0)
  /// and record the matching movement [movementId].
  ///
  /// v1s-13-13: the id is the one the backend writes for the same operation.
  /// If that movement is already here, the pull got there first (it also
  /// refreshed the stock levels from the server), so the delta is NOT applied
  /// again. `insertOnConflictUpdate` stays the race guard inside the Drift
  /// transaction.
  Future<void> _adjustStock(String productId, String storeId, int delta,
      String type, String actorId, DateTime now,
      {required String movementId}) async {
    final existing = await (_db.select(_db.stockMovements)
          ..where((m) => m.id.equals(movementId)))
        .getSingleOrNull();
    if (existing != null) return;
    final rows = await (_db.select(_db.stockLevels)
          ..where((s) => s.productId.equals(productId) & s.storeId.equals(storeId)))
        .get();
    // No row = never stocked here = quantity 0: the restore still happens
    // and creates the row (same update-or-insert as a stock entry).
    final qBefore = _currentQuantity(rows);
    // A decrease never takes the stock below 0 (nor lower than it already is).
    final qAfter = delta >= 0
        ? qBefore + delta
        : max(qBefore + delta, min(qBefore, 0));
    await _writeStockQuantity(productId, storeId, qAfter, now);
    await _db
        .into(_db.stockMovements)
        .insertOnConflictUpdate(StockMovementsCompanion.insert(
      id: movementId,
      productId: productId,
      storeId: storeId,
      type: type,
      quantityDelta: delta,
      actorId: actorId,
      quantityBefore: Value(qBefore),
      quantityAfter: Value(qAfter),
      createdAt: now,
      // Written only after the server confirmed the operation: the row mirrors
      // a server movement (same id), so it is synced and purgeable.
      synced: const Value(true),
      syncedAt: Value(now),
    ));
  }

  /// Update sale status locally.
  Future<void> updateSaleStatus(String saleId, String newStatus) async {
    await (_db.update(_db.sales)
          ..where((s) => s.id.equals(saleId)))
        .write(SalesCompanion(status: Value(newStatus)));
  }

  /// Validate a PENDING_VALIDATION sale: record initial stock + decrement + update status.
  ///
  /// Mirrors backend ValidateSaleService exactly:
  ///  1. Record STOCK_ENTRY movements for [initialStockEntries] (user-provided initial stock).
  ///  2. Record SALE movements (stock out) for each sale item.
  ///  3. Update sale status to COMPLETED.
  Future<void> validateAndDecrementStock(
    String saleId, {
    Map<String, int>? initialStockEntries,
  }) async {
    await _db.transaction(() async {
      final sale = await (_db.select(_db.sales)
            ..where((s) => s.id.equals(saleId)))
          .getSingleOrNull();
      if (sale == null) return;
      // Idempotency guard: if the sale was already completed offline
      // (e.g. a previous validate attempt succeeded locally), skip all
      // stock mutations to prevent double-decrement on retry.
      // v1s-13-10: same for any non-pending sale (a CANCELLED sale must not
      // be decremented and rewritten to COMPLETED).
      if (sale.status != 'PENDING_VALIDATION') return;

      final now = DateTime.now();

      // Step 1: Record initial stock entries (STOCK_ENTRY — positive)
      if (initialStockEntries != null) {
        for (final entry in initialStockEntries.entries) {
          final productId = entry.key;
          final qty = entry.value;
          if (qty <= 0) continue;

          // B1: use .get() to handle potential duplicate (product_id, store_id) rows.
          final stockRowsEntry = await (_db.select(_db.stockLevels)
                ..where((s) =>
                    s.productId.equals(productId) &
                    s.storeId.equals(sale.storeId)))
              .get();

          final qBefore = stockRowsEntry.fold<int>(0, (max, r) => r.quantity > max ? r.quantity : max);
          final qAfter = qBefore + qty;

          if (stockRowsEntry.isNotEmpty) {
            await (_db.update(_db.stockLevels)
                  ..where((s) =>
                      s.productId.equals(productId) &
                      s.storeId.equals(sale.storeId)))
                .write(StockLevelsCompanion(
              quantity: Value(qAfter),
              updatedAt: Value(now),
            ));
          } else {
            await _db.into(_db.stockLevels).insert(StockLevelsCompanion.insert(
              id: const Uuid().v4(),
              productId: productId,
              storeId: sale.storeId,
              quantity: qAfter,
              updatedAt: now,
            ));
          }

          await _db.into(_db.stockMovements).insert(
              StockMovementsCompanion.insert(
            id: const Uuid().v4(),
            productId: productId,
            storeId: sale.storeId,
            type: 'STOCK_ENTRY',
            quantityDelta: qty,
            actorId: sale.employeeId,
            quantityBefore: Value(qBefore),
            quantityAfter: Value(qAfter),
            createdAt: now,
          ));
        }
      }

      // Step 2: Decrement stock for each sale item (SALE — negative)
      final items = await (_db.select(_db.saleItems)
            ..where((i) => i.saleId.equals(saleId)))
          .get();

      for (final item in items) {
        // v1s-13-10 follow-up: a SALE movement with this id can only come from
        // a pull — the server already applied it and the pulled stock level
        // includes it. Keep the server row and do not decrement twice.
        if (await _saleMovementAlreadyPulled(item.id)) continue;

        // B1: use .get() to handle potential duplicate (product_id, store_id) rows.
        final stockRowsDecrement = await (_db.select(_db.stockLevels)
              ..where((s) =>
                  s.productId.equals(item.productId) &
                  s.storeId.equals(sale.storeId)))
            .get();

        // Force to 0 if insufficient (matches backend ValidateSaleService):
        // only what is on the shelf is taken out, and nothing at all — no
        // movement either — when the product has no stock (or no row) here.
        final qBefore = _currentQuantity(stockRowsDecrement);
        final qAfter = qBefore - _effectiveDecrement(qBefore, item.quantity);
        if (qAfter == qBefore) continue;

        await _writeStockQuantity(item.productId, sale.storeId, qAfter, now);

        await _db.into(_db.stockMovements).insert(
            StockMovementsCompanion.insert(
          // v1s-13-10: SALE movement id = sale item id, as on the backend
          // (ValidateSaleService) — the pull upserts this row instead of
          // adding a second SALE movement.
          id: item.id,
          productId: item.productId,
          storeId: sale.storeId,
          type: 'SALE',
          quantityDelta: qAfter - qBefore,
          actorId: sale.employeeId,
          quantityBefore: Value(qBefore),
          quantityAfter: Value(qAfter),
          createdAt: now,
        ));
      }

      // Step 3: Update sale status
      await (_db.update(_db.sales)
            ..where((s) => s.id.equals(saleId)))
          .write(const SalesCompanion(status: Value('COMPLETED')));
    });
  }

  /// v1s-13-10 follow-up: whether the SALE movement of [saleItemId] (its id is
  /// the sale item id, backend and app alike) is already in the local cache —
  /// only a pull can have put it there.
  Future<bool> _saleMovementAlreadyPulled(String saleItemId) async {
    final existing = await (_db.select(_db.stockMovements)
          ..where((m) => m.id.equals(saleItemId)))
        .getSingleOrNull();
    return existing != null;
  }

  /// Cascade-validate pending sales after a DRAFT product is activated.
  ///
  /// Finds all PENDING_VALIDATION sales in [storeId] that contain [productId],
  /// checks that every other product in each sale is ACTIVE, then marks the
  /// sale COMPLETED and decrements stocks for all items (negative stock is
  /// allowed for the ex-draft product).
  ///
  /// Returns the number of sales that were validated.
  Future<int> cascadeValidatePendingSales(
      String productId, String storeId, String actorId) async {
    final pendingRows = await _db.customSelect(
      'SELECT DISTINCT s.id FROM sales s '
      'INNER JOIN sale_items si ON si.sale_id = s.id '
      'WHERE s.store_id = ? AND s.status = ? AND si.product_id = ?',
      variables: [
        Variable.withString(storeId),
        Variable.withString('PENDING_VALIDATION'),
        Variable.withString(productId),
      ],
    ).get();

    int validated = 0;
    for (final row in pendingRows) {
      final saleId = row.read<String>('id');
      final items = await (_db.select(_db.saleItems)
            ..where((i) => i.saleId.equals(saleId)))
          .get();

      // Verify all OTHER products in the sale are ACTIVE.
      bool allActive = true;
      for (final item in items) {
        if (item.productId == productId) continue;
        final count = await _db.customSelect(
          "SELECT COUNT(*) as cnt FROM products WHERE id = ? AND status = 'ACTIVE' AND archived = 0",
          variables: [Variable.withString(item.productId)],
        ).getSingle();
        if ((count.read<int>('cnt')) == 0) {
          allActive = false;
          break;
        }
      }
      if (!allActive) continue;

      // All products active — validate and decrement stock.
      final claimed = await _db.transaction(() async {
        // v1s-13-10: conditional claim — a sale settled meanwhile (validated
        // or cancelled by another path) is skipped without touching stock.
        final updated = await (_db.update(_db.sales)
              ..where((s) =>
                  s.id.equals(saleId) &
                  s.status.equals('PENDING_VALIDATION')))
            .write(const SalesCompanion(status: Value('COMPLETED')));
        if (updated == 0) return false;

        final now = DateTime.now();
        for (final item in items) {
          // v1s-13-10 follow-up: server movement already pulled → keep it,
          // no second decrement (see validateAndDecrementStock).
          if (await _saleMovementAlreadyPulled(item.id)) continue;

          // B1: use .get() to handle potential duplicate (product_id, store_id) rows.
          final stockRowsCascade = await (_db.select(_db.stockLevels)
                ..where((s) =>
                    s.productId.equals(item.productId) &
                    s.storeId.equals(storeId)))
              .get();

          // Same rule as validateAndDecrementStock: never below 0, no
          // movement when nothing can be taken out (no stock / no row here).
          final qBefore = _currentQuantity(stockRowsCascade);
          final qAfter = qBefore - _effectiveDecrement(qBefore, item.quantity);
          if (qAfter == qBefore) continue;

          await _writeStockQuantity(item.productId, storeId, qAfter, now);

          await _db.into(_db.stockMovements).insert(
              StockMovementsCompanion.insert(
            // v1s-13-10: SALE movement id = sale item id (backend cascade does
            // the same), so the pull does not add a second SALE movement.
            id: item.id,
            productId: item.productId,
            storeId: storeId,
            type: 'SALE',
            quantityDelta: qAfter - qBefore,
            actorId: actorId,
            quantityBefore: Value(qBefore),
            quantityAfter: Value(qAfter),
            createdAt: now,
          ));
        }
        return true;
      });
      if (claimed) validated++;
    }
    return validated;
  }

  /// Apply initial stock entries for originally-draft products at checkout time.
  ///
  /// Called before [insertAll] when the sale contained draft products so that
  /// the stock check after this call sees positive stock for those products.
  ///
  /// [movementIds] (productId → id) lets the caller choose the movement ids so
  /// the queued RECORD_STOCK_ENTRY can carry the same id to the server.
  Future<void> applyInitialStockEntries(
      Map<String, int> entries, String storeId, String actorId,
      {Map<String, String> movementIds = const {}}) async {
    if (entries.isEmpty) return;
    await _db.transaction(() async {
      final now = DateTime.now();
      for (final entry in entries.entries) {
        final productId = entry.key;
        final qty = entry.value;
        if (qty <= 0) continue;

        final stockRows = await (_db.select(_db.stockLevels)
              ..where((s) =>
                  s.productId.equals(productId) &
                  s.storeId.equals(storeId)))
            .get();

        final qBefore =
            stockRows.fold<int>(0, (max, r) => r.quantity > max ? r.quantity : max);
        final qAfter = qBefore + qty;

        if (stockRows.isNotEmpty) {
          await (_db.update(_db.stockLevels)
                ..where((s) =>
                    s.productId.equals(productId) &
                    s.storeId.equals(storeId)))
              .write(StockLevelsCompanion(
            quantity: Value(qAfter),
            updatedAt: Value(now),
          ));
        } else {
          await _db.into(_db.stockLevels).insert(StockLevelsCompanion.insert(
            id: const Uuid().v4(),
            productId: productId,
            storeId: storeId,
            quantity: qAfter,
            updatedAt: now,
          ));
        }

        await _db.into(_db.stockMovements).insert(StockMovementsCompanion.insert(
          id: movementIds[productId] ?? const Uuid().v4(),
          productId: productId,
          storeId: storeId,
          type: 'STOCK_ENTRY',
          quantityDelta: qty,
          actorId: actorId,
          quantityBefore: Value(qBefore),
          quantityAfter: Value(qAfter),
          createdAt: now,
        ));
      }
    });
  }

  /// Count pending validation sales.
  Future<int> countPendingSales(String? storeId) async {
    final String sql;
    final List<Variable> variables;
    if (storeId != null) {
      sql = 'SELECT COUNT(*) as cnt FROM sales WHERE store_id = ? AND status = ?';
      variables = [
        Variable.withString(storeId),
        Variable.withString('PENDING_VALIDATION'),
      ];
    } else {
      sql = 'SELECT COUNT(*) as cnt FROM sales WHERE status = ?';
      variables = [Variable.withString('PENDING_VALIDATION')];
    }
    final rows = await _db.customSelect(sql, variables: variables).getSingle();
    return rows.read<int>('cnt');
  }
}
