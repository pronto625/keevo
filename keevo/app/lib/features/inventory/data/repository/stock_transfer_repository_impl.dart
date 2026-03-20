import 'dart:developer' as dev;

import '../../../../core/sync/connectivity_service.dart';
import '../../../../core/sync/sync_service.dart';
import '../../domain/model/stock_transfer_model.dart';
import '../../domain/repository/stock_transfer_repository.dart';
import '../datasource/local_stock_transfer_datasource.dart';
import '../datasource/remote_stock_transfer_datasource.dart';

/// StockTransferRepositoryImpl — Backend-first implementation of StockTransferRepository.
///
/// Online strategy: remote first, cache result locally + apply local stock change.
/// Offline strategy:
///   1. Validate against local stock_levels (throws if insufficient)
///   2. Apply local stock change atomically
///   3. Queue in sync_queue via SyncService
///   4. Save transfer record as PENDING_SYNC
///
/// Story 3.3 + 5.1.
class StockTransferRepositoryImpl implements StockTransferRepository {
  final LocalStockTransferDataSource _local;
  final RemoteStockTransferDataSource _remote;
  final ConnectivityService _connectivity;
  final SyncService _syncService;

  const StockTransferRepositoryImpl({
    required LocalStockTransferDataSource local,
    required RemoteStockTransferDataSource remote,
    required ConnectivityService connectivity,
    required SyncService syncService,
  })  : _local = local,
        _remote = remote,
        _connectivity = connectivity,
        _syncService = syncService;

  @override
  Future<StockTransferModel> executeTransfer({
    required String sourceStoreId,
    required String destinationStoreId,
    required String productId,
    String? variantId,
    required int quantity,
    String? notes,
  }) async {
    if (await _connectivity.isOnline()) {
      try {
        final transfer = await _remote.executeTransfer(
          sourceStoreId: sourceStoreId,
          destinationStoreId: destinationStoreId,
          productId: productId,
          variantId: variantId,
          quantity: quantity,
          notes: notes,
        );
        await _local.saveTransfer(transfer);
        await _local.applyLocalStockChange(
          productId: productId,
          variantId: variantId,
          sourceStoreId: sourceStoreId,
          destinationStoreId: destinationStoreId,
          quantity: quantity,
        );
        return transfer;
      } catch (_) {
        // Backend unreachable despite connectivity — fallback to offline path
        return _offlineTransfer(
          sourceStoreId: sourceStoreId,
          destinationStoreId: destinationStoreId,
          productId: productId,
          variantId: variantId,
          quantity: quantity,
          notes: notes,
        );
      }
    } else {
      return _offlineTransfer(
        sourceStoreId: sourceStoreId,
        destinationStoreId: destinationStoreId,
        productId: productId,
        variantId: variantId,
        quantity: quantity,
        notes: notes,
      );
    }
  }

  Future<StockTransferModel> _offlineTransfer({
    required String sourceStoreId,
    required String destinationStoreId,
    required String productId,
    String? variantId,
    required int quantity,
    String? notes,
  }) async {
    // 1. Validate against local stock_levels
    final available = await _local.getLocalStock(
      productId: productId,
      variantId: variantId,
      storeId: sourceStoreId,
    );
    if (available < quantity) {
      throw StateError(
        'INSUFFICIENT_STOCK:available=$available:requested=$quantity',
      );
    }

    // 2. Apply local stock change immediately
    await _local.applyLocalStockChange(
      productId: productId,
      variantId: variantId,
      sourceStoreId: sourceStoreId,
      destinationStoreId: destinationStoreId,
      quantity: quantity,
    );

    // 3. Enqueue for background sync via SyncService
    final id = DateTime.now().millisecondsSinceEpoch.toString();
    final payload = <String, dynamic>{
      'sourceStoreId': sourceStoreId,
      'destinationStoreId': destinationStoreId,
      'productId': productId,
      if (variantId != null) 'variantId': variantId,
      'quantity': quantity,
      if (notes != null) 'notes': notes,
    };
    await _syncService.queueOperation(
      operation: 'STOCK_TRANSFER',
      payload: payload,
      entityId: id,
    );

    // 4. Save PENDING_SYNC record
    final pending = StockTransferModel(
      id: id,
      sourceStoreId: sourceStoreId,
      destinationStoreId: destinationStoreId,
      productId: productId,
      variantId: variantId,
      quantity: quantity,
      actorId: '',
      occurredAt: DateTime.now(),
      status: 'PENDING_SYNC',
      notes: notes,
    );
    dev.log('[StockTransfer] Offline — queued as PENDING_SYNC: $id',
        name: 'StockTransferRepository');
    await _local.saveTransfer(pending);
    return pending;
  }

  @override
  Future<List<StockTransferModel>> getHistory({
    String? sourceStoreId,
    String? destinationStoreId,
    String? productId,
    int page = 0,
    int pageSize = 20,
  }) async {
    if (!await _connectivity.isOnline()) {
      return _local.getHistory(
        sourceStoreId: sourceStoreId,
        destinationStoreId: destinationStoreId,
        productId: productId,
        page: page,
        pageSize: pageSize,
      );
    }
    try {
      final transfers = await _remote.getHistory(
        sourceStoreId: sourceStoreId,
        destinationStoreId: destinationStoreId,
        productId: productId,
        page: page,
        pageSize: pageSize,
      );
      for (final t in transfers) {
        await _local.saveTransfer(t);
      }
      return transfers;
    } catch (e) {
      dev.log('[StockTransfer] Remote getHistory failed — using local: $e',
          name: 'StockTransferRepository');
      return _local.getHistory(
        sourceStoreId: sourceStoreId,
        destinationStoreId: destinationStoreId,
        productId: productId,
        page: page,
        pageSize: pageSize,
      );
    }
  }

  @override
  Future<StockTransferModel> completeTransfer(String transferId) async {
    if (await _connectivity.isOnline()) {
      final transfer = await _remote.completeTransfer(transferId);
      // Persist updated status locally (COMPLETED). Local stock_levels will
      // be refreshed on next full sync since destination-only credits are
      // complex to apply partially without a source deduction here.
      await _local.saveTransfer(transfer);
      return transfer;
    } else {
      throw StateError('La réception nécessite une connexion active.');
    }
  }
}
