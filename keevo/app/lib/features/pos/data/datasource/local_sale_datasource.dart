import 'dart:convert';
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

  /// Atomically persist: sale, sale_items, stock decrements, stock movements, sync_queue.
  Future<void> insertAll(Sale sale) async {
    await _db.transaction(() async {
      // 1. Insert sale
      await _db.into(_db.sales).insert(SalesCompanion.insert(
        id: sale.id,
        storeId: sale.storeId,
        employeeId: sale.employeeId,
        totalAmount: sale.totalAmount,
        paymentMode: sale.paymentMode.value,
        clientId: Value(sale.clientId),
        status: Value(sale.status),
        occurredAt: Value(sale.occurredAt),
        createdAt: sale.createdAt,
      ));

      // 2. Insert sale items
      for (final item in sale.items) {
        await _db.into(_db.saleItems).insert(SaleItemsCompanion.insert(
          id: item.id,
          saleId: sale.id,
          productId: item.productId,
          productName: item.productName,
          unitPrice: item.appliedUnitPrice,
          quantity: item.quantity,
          subtotal: item.subtotal,
          variantId: Value(item.variantId),
          createdAt: sale.createdAt,
        ));
      }

      // 3. Decrement stock levels + insert stock movements
      for (final item in sale.items) {
        final stockRow = await (_db.select(_db.stockLevels)
              ..where((s) =>
                  s.productId.equals(item.productId) &
                  s.storeId.equals(sale.storeId)))
            .getSingleOrNull();

        final quantityBefore = stockRow?.quantity ?? 0;
        final quantityAfter = (quantityBefore - item.quantity).clamp(0, quantityBefore);

        if (stockRow != null) {
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

      // 4. Enqueue for sync
      await _db.into(_db.syncQueue).insert(SyncQueueCompanion.insert(
        id: const Uuid().v4(),
        operation: 'CREATE_SALE',
        payload: jsonEncode(_buildPayload(sale)),
        createdAt: sale.createdAt,
      ));
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
    final row = await (_db.select(_db.stockLevels)
          ..where((s) =>
              s.productId.equals(productId) &
              s.storeId.equals(storeId)))
        .getSingleOrNull();
    return row?.quantity ?? 0;
  }

  Map<String, dynamic> _buildPayload(Sale sale) => {
        'saleId': sale.id,
        'storeId': sale.storeId,
        'paymentMode': sale.paymentMode.value,
        'mobileMoneyRef': sale.mobileMoneyRef,
        'clientId': sale.clientId,
        'items': sale.items
            .map((i) => {
                  'productId': i.productId,
                  'variantId': i.variantId,
                  'productName': i.productName,
                  'appliedUnitPrice': i.appliedUnitPrice,
                  'quantity': i.quantity,
                })
            .toList(),
      };
}
