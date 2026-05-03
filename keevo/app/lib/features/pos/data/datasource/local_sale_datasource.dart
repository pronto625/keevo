import 'package:drift/drift.dart';
import 'package:uuid/uuid.dart';

import '../../../../core/storage/app_database.dart' hide Sale, SaleItem;
import '../../domain/model/sale_model.dart';

/// LocalSaleDataSource — persists sale data in Drift (SQLCipher).
///
/// All writes in [insertAll] run in a single atomic transaction.
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

          final quantityBefore = stockRows.fold<int>(0, (max, r) => r.quantity > max ? r.quantity : max);
          final quantityAfter = (quantityBefore - item.quantity).clamp(0, quantityBefore);

          if (stockRows.isNotEmpty) {
            await (_db.update(_db.stockLevels)
                  ..where((s) =>
                      s.productId.equals(item.productId) &
                      s.storeId.equals(sale.storeId)))
                .write(StockLevelsCompanion(
              quantity: Value(quantityAfter),
            ));
          }

          await _db.into(_db.stockMovements).insert(StockMovementsCompanion.insert(
            id: const Uuid().v4(),
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
      if (sale.status == 'COMPLETED') return;

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
        // B1: use .get() to handle potential duplicate (product_id, store_id) rows.
        final stockRowsDecrement = await (_db.select(_db.stockLevels)
              ..where((s) =>
                  s.productId.equals(item.productId) &
                  s.storeId.equals(sale.storeId)))
            .get();

        final qBefore = stockRowsDecrement.fold<int>(0, (max, r) => r.quantity > max ? r.quantity : max);
        // Force to 0 if insufficient (matches backend behavior)
        final qAfter = (qBefore - item.quantity).clamp(0, qBefore);

        if (stockRowsDecrement.isNotEmpty) {
          await (_db.update(_db.stockLevels)
                ..where((s) =>
                    s.productId.equals(item.productId) &
                    s.storeId.equals(sale.storeId)))
              .write(StockLevelsCompanion(
            quantity: Value(qAfter),
            updatedAt: Value(now),
          ));
        }

        await _db.into(_db.stockMovements).insert(
            StockMovementsCompanion.insert(
          id: const Uuid().v4(),
          productId: item.productId,
          storeId: sale.storeId,
          type: 'SALE',
          quantityDelta: -item.quantity,
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
      await _db.transaction(() async {
        await (_db.update(_db.sales)
              ..where((s) => s.id.equals(saleId)))
            .write(const SalesCompanion(status: Value('COMPLETED')));

        final now = DateTime.now();
        for (final item in items) {
          // B1: use .get() to handle potential duplicate (product_id, store_id) rows.
          final stockRowsCascade = await (_db.select(_db.stockLevels)
                ..where((s) =>
                    s.productId.equals(item.productId) &
                    s.storeId.equals(storeId)))
              .get();

          final qBefore = stockRowsCascade.fold<int>(0, (max, r) => r.quantity > max ? r.quantity : max);
          final qAfter = (qBefore - item.quantity).clamp(0, qBefore);

          if (stockRowsCascade.isNotEmpty) {
            await (_db.update(_db.stockLevels)
                  ..where((s) =>
                      s.productId.equals(item.productId) &
                      s.storeId.equals(storeId)))
                .write(StockLevelsCompanion(quantity: Value(qAfter)));
          }

          await _db.into(_db.stockMovements).insert(StockMovementsCompanion.insert(
            id: const Uuid().v4(),
            productId: item.productId,
            storeId: storeId,
            type: 'SALE',
            quantityDelta: -item.quantity,
            actorId: actorId,
            quantityBefore: Value(qBefore),
            quantityAfter: Value(qAfter),
            createdAt: now,
          ));
        }
      });
      validated++;
    }
    return validated;
  }

  /// Apply initial stock entries for originally-draft products at checkout time.
  ///
  /// Called before [insertAll] when the sale contained draft products so that
  /// the stock check after this call sees positive stock for those products.
  Future<void> applyInitialStockEntries(
      Map<String, int> entries, String storeId, String actorId) async {
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
          id: const Uuid().v4(),
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
