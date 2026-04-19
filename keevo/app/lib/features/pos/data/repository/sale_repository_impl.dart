import 'dart:developer' as dev;

import 'package:drift/drift.dart';

import '../../../../core/storage/app_database.dart' hide Sale, SaleItem;
import '../../../../core/sync/connectivity_service.dart';
import '../../../../core/sync/sync_service.dart';
import '../../domain/model/payment_mode_enum.dart';
import '../../domain/model/sale_model.dart';
import '../../domain/model/sales_history_filter.dart';
import '../../domain/repository/sale_repository.dart';
import '../datasource/local_sale_datasource.dart';
import '../datasource/remote_sale_datasource.dart';

/// SaleRepositoryImpl — Backend-first: online writes go to backend FIRST.
class SaleRepositoryImpl implements SaleRepository {
  final LocalSaleDataSource _local;
  final RemoteSaleDataSource _remote;
  final AppDatabase _db;
  final ConnectivityService _connectivity;
  final SyncService _syncService;

  const SaleRepositoryImpl(
    this._local,
    this._remote,
    this._db, {
    required ConnectivityService connectivity,
    required SyncService syncService,
  })  : _connectivity = connectivity,
        _syncService = syncService;

  @override
  Future<void> recordSale(Sale sale) async {
    if (await _connectivity.isOnline()) {
      // PATH A: Backend-first
      try {
        await _remote.pushSale(sale);
        await _local.insertAll(sale, synced: true);
      } catch (_) {
        // Backend unreachable despite connectivity — fallback
        await _local.insertAll(sale, synced: false);
        await _syncService.queueOperation(
          operation: 'CREATE_SALE',
          payload: _buildPayload(sale),
          entityId: sale.id,
        );
      }
    } else {
      // PATH B: Offline-first
      await _local.insertAll(sale, synced: false);
      await _syncService.queueOperation(
        operation: 'CREATE_SALE',
        payload: _buildPayload(sale),
        entityId: sale.id,
      );
    }
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
        discountAmount: row.discountAmount,
        status: row.status ?? 'COMPLETED',
        items: items
            .map((i) => SaleItemModel(
                  id: i.id,
                  productId: i.productId,
                  variantId: i.variantId,
                  productName: i.productName,
                  catalogueUnitPrice: i.catalogueUnitPrice,
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

  @override
  Future<List<Sale>> getPendingSales(String? storeId) async {
    // Online-first: fetch from backend (authoritative source for OWNER).
    // PENDING_VALIDATION sales may exist on the server without being in the
    // local DB (created by another device, or sync pull not yet received).
    if (await _connectivity.isOnline()) {
      try {
        final remoteSales = await _remote.getPendingSales();
        // Remove sales that were already validated/cancelled OFFLINE but whose
        // VALIDATE_SALE / CANCEL_SALE sync operation hasn't reached the backend
        // yet.  Without this filter the sale would reappear in the list after
        // every provider refresh, making the user think validation failed.
        final filtered = <Sale>[];
        for (final s in remoteSales) {
          final localRow = await (_db.select(_db.sales)
                ..where((r) => r.id.equals(s.id)))
              .getSingleOrNull();
          final localStatus = localRow?.status;
          if (localStatus == 'COMPLETED' || localStatus == 'CANCELLED') {
            continue; // Offline action already taken — skip until sync confirms
          }
          filtered.add(s);
        }
        // Filter by storeId if provided (OWNER with active store selected).
        if (storeId != null) {
          return filtered.where((s) => s.storeId == storeId).toList();
        }
        return filtered;
      } catch (_) {
        // Remote unavailable — fall through to local
      }
    }
    // Offline fallback: read from local Drift DB.
    final query = _db.select(_db.sales)
      ..where((s) {
        final cond = s.status.equals('PENDING_VALIDATION');
        return storeId != null ? cond & s.storeId.equals(storeId) : cond;
      })
      ..orderBy([(s) => OrderingTerm.desc(s.createdAt)]);
    final rows = await query.get();

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
        discountAmount: row.discountAmount,
        status: 'PENDING_VALIDATION',
        items: items
            .map((i) => SaleItemModel(
                  id: i.id,
                  productId: i.productId,
                  variantId: i.variantId,
                  productName: i.productName,
                  catalogueUnitPrice: i.catalogueUnitPrice,
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
  Future<int> countPendingSales(String? storeId) async {
    return _local.countPendingSales(storeId);
  }

  @override
  Future<void> validateSale(String saleId, String justification,
      {Map<String, String>? productIdRemappings,
      Map<String, int>? initialStockEntries}) async {
    if (await _connectivity.isOnline()) {
      try {
        await _remote.validateSale(saleId, justification,
            productIdRemappings: productIdRemappings,
            initialStockEntries: initialStockEntries);
        await _local.updateSaleStatus(saleId, 'COMPLETED');
        return;
      } catch (_) {
        // Backend unreachable — fall through to offline path
      }
    }
    // Offline: validate + initial stock + decrement (matches backend ValidateSaleService)
    await _local.validateAndDecrementStock(saleId,
        initialStockEntries: initialStockEntries);
    await _syncService.queueOperation(
      operation: 'VALIDATE_SALE',
      payload: {
        'saleId': saleId,
        'justification': justification,
        if (productIdRemappings != null && productIdRemappings.isNotEmpty)
          'productIdRemappings': productIdRemappings,
        if (initialStockEntries != null && initialStockEntries.isNotEmpty)
          'initialStockEntries': initialStockEntries,
      },
      entityId: saleId,
    );
  }

  @override
  Future<void> cancelSale(String saleId, String justification) async {
    if (await _connectivity.isOnline()) {
      try {
        await _remote.cancelSale(saleId, justification);
        await _local.updateSaleStatus(saleId, 'CANCELLED');
        return;
      } catch (_) {
        // Backend unreachable — fall through to offline path
      }
    }
    // Offline: just update status (no stock to restore — never decremented)
    await _local.updateSaleStatus(saleId, 'CANCELLED');
    await _syncService.queueOperation(
      operation: 'CANCEL_SALE',
      payload: {
        'saleId': saleId,
        'justification': justification,
      },
      entityId: saleId,
    );
  }

  @override
  Future<List<Sale>> getSalesHistory(SalesHistoryFilter filter) async {
    // Online-first: try backend, fall back to local Drift
    if (await _connectivity.isOnline()) {
      try {
        return await _remote.getSalesHistory(filter);
      } catch (e) {
        dev.log('Remote getSalesHistory failed, falling back to local: $e');
      }
    }

    // Fallback: local Drift query
    final query = _db.select(_db.sales)
      ..where((s) {
        var expr = s.storeId.equals(filter.storeId) &
            s.occurredAt.isBetweenValues(filter.from, filter.to);
        if (filter.employeeId != null) {
          expr = expr & s.employeeId.equals(filter.employeeId!);
        }
        return expr;
      })
      ..orderBy([(s) => OrderingTerm.desc(s.occurredAt)]);
    final rows = await query.get();

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
        discountAmount: row.discountAmount,
        status: row.status ?? 'COMPLETED',
        items: items
            .map((i) => SaleItemModel(
                  id: i.id,
                  productId: i.productId,
                  variantId: i.variantId,
                  productName: i.productName,
                  catalogueUnitPrice: i.catalogueUnitPrice,
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
  Future<Sale?> getSaleById(String saleId) async {
    final row = await (_db.select(_db.sales)
          ..where((s) => s.id.equals(saleId)))
        .getSingleOrNull();

    if (row == null) return null;

    final items = await (_db.select(_db.saleItems)
          ..where((i) => i.saleId.equals(saleId)))
        .get();

    return Sale(
      id: row.id,
      storeId: row.storeId,
      employeeId: row.employeeId,
      clientId: row.clientId,
      paymentMode: PaymentModeEnum.values.firstWhere(
        (m) => m.value == row.paymentMode,
        orElse: () => PaymentModeEnum.cash,
      ),
      totalAmount: row.totalAmount,
      discountAmount: row.discountAmount,
      status: row.status ?? 'COMPLETED',
      items: items
          .map((i) => SaleItemModel(
                id: i.id,
                productId: i.productId,
                variantId: i.variantId,
                productName: i.productName,
                catalogueUnitPrice: i.catalogueUnitPrice,
                appliedUnitPrice: i.unitPrice,
                quantity: i.quantity,
                subtotal: i.subtotal,
              ))
          .toList(),
      occurredAt: row.occurredAt ?? row.createdAt,
      createdAt: row.createdAt,
    );
  }

  Map<String, dynamic> _buildPayload(Sale sale) => {
        'saleId': sale.id,
        'storeId': sale.storeId,
        'paymentMode': sale.paymentMode.value,
        'mobileMoneyRef': sale.mobileMoneyRef,
        'clientId': sale.clientId,
        'discountAmount': sale.discountAmount,
        'requestedStatus': sale.status,
        'items': sale.items
            .map((i) => {
                  'itemId': i.id,
                  'productId': i.productId,
                  'variantId': i.variantId,
                  'productName': i.productName,
                  'catalogueUnitPrice': i.catalogueUnitPrice,
                  'appliedUnitPrice': i.appliedUnitPrice,
                  'quantity': i.quantity,
                })
            .toList(),
      };
}
