import 'dart:async';
import 'dart:developer' as dev;

import 'package:drift/drift.dart';

import '../../../../core/storage/app_database.dart' hide Sale, SaleItem;
import '../../domain/model/payment_mode_enum.dart';
import '../../domain/model/sale_model.dart';
import '../../domain/repository/sale_repository.dart';
import '../datasource/local_sale_datasource.dart';
import '../datasource/remote_sale_datasource.dart';

/// SaleRepositoryImpl — write-through: local-first, background push.
class SaleRepositoryImpl implements SaleRepository {
  final LocalSaleDataSource _local;
  final RemoteSaleDataSource _remote;
  final AppDatabase _db;

  const SaleRepositoryImpl(this._local, this._remote, this._db);

  @override
  Future<void> recordSale(Sale sale) async {
    // Step 1 — ALWAYS save locally first (atomic Drift transaction)
    await _local.insertAll(sale);

    // Step 2 — Immediately attempt backend push in background (non-blocking)
    unawaited(() async {
      try {
        await _remote.pushSale(sale);
        await _local.markSynced(sale.id);
        await _local.removeSyncQueueEntry(sale.id);
      } catch (_) {
        dev.log('[SaleRepo] Backend unreachable — sale ${sale.id} queued',
            name: 'POS');
      }
    }());
  }

  @override
  Future<List<Sale>> getSalesForToday(String storeId) async {
    final now = DateTime.now();
    final startOfDay = DateTime(now.year, now.month, now.day);

    final rows = await (_db.select(_db.sales)
          ..where((s) =>
              s.storeId.equals(storeId) &
              s.createdAt.isBiggerOrEqualValue(startOfDay))
          ..orderBy([(s) => OrderingTerm.desc(s.createdAt)]))
        .get();

    final sales = <Sale>[];
    for (final row in rows) {
      final items = await (_db.select(_db.saleItems)
            ..where((i) => i.saleId.equals(row.id)))
          .get();

      sales.add(Sale(
        id: row.id,
        storeId: row.storeId,
        employeeId: row.employeeId,
        clientId: row.clientId,
        paymentMode: PaymentModeEnum.values.firstWhere(
          (m) => m.value == row.paymentMode,
          orElse: () => PaymentModeEnum.cash,
        ),
        totalAmount: row.totalAmount,
        status: row.status ?? 'COMPLETED',
        items: items
            .map((i) => SaleItemModel(
                  id: i.id,
                  productId: i.productId,
                  variantId: i.variantId,
                  productName: i.productName,
                  appliedUnitPrice: i.unitPrice,
                  quantity: i.quantity,
                  subtotal: i.subtotal,
                ))
            .toList(),
        occurredAt: row.occurredAt ?? row.createdAt,
        createdAt: row.createdAt,
      ));
    }
    return sales;
  }

  @override
  Future<List<String>> getFrequentProductIds(String storeId,
      {int limit = 12}) async {
    final results = await _db.customSelect(
      'SELECT si.product_id, COUNT(*) as cnt '
      'FROM sale_items si '
      'INNER JOIN sales s ON s.id = si.sale_id '
      'WHERE s.store_id = ? AND (s.status IS NULL OR s.status = ?) '
      'GROUP BY si.product_id '
      'ORDER BY cnt DESC '
      'LIMIT ?',
      variables: [
        Variable.withString(storeId),
        Variable.withString('COMPLETED'),
        Variable.withInt(limit),
      ],
    ).get();

    return results.map((r) => r.read<String>('product_id')).toList();
  }
}
