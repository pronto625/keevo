import 'dart:developer' as dev;

import 'package:uuid/uuid.dart';

import '../../../../core/sync/connectivity_service.dart';
import '../../../../core/sync/sync_service.dart';
import '../../../../core/sync/sync_trigger_dispatcher.dart';
import '../../domain/model/stock_transfer_model.dart';
import '../../domain/repository/stock_transfer_repository.dart';
import '../datasource/local_stock_transfer_datasource.dart';
import '../datasource/remote_stock_transfer_datasource.dart';

/// StockTransferRepositoryImpl — Offline-first implementation (Story 5.6).
///
/// executeTransfer() always uses the local path:
///   1. Validate against local stock_levels (throws if insufficient)
///   2. Apply local stock change atomically
///   3. Queue in sync_queue via SyncService
///   4. Save transfer record as PENDING_SYNC
///   5. Trigger background push (fire-and-forget)
///
/// Story 3.3 + 5.6.
class StockTransferRepositoryImpl implements StockTransferRepository {
  final LocalStockTransferDataSource _local;
  final RemoteStockTransferDataSource _remote;
  final ConnectivityService _connectivity;
  final SyncService _syncService;
  final SyncTriggerDispatcher _syncTriggerDispatcher;

  const StockTransferRepositoryImpl({
    required LocalStockTransferDataSource local,
    required RemoteStockTransferDataSource remote,
    required ConnectivityService connectivity,
    required SyncService syncService,
    required SyncTriggerDispatcher syncTriggerDispatcher,
  })  : _local = local,
        _remote = remote,
        _connectivity = connectivity,
        _syncService = syncService,
        _syncTriggerDispatcher = syncTriggerDispatcher;

  @override
  Future<StockTransferModel> executeTransfer({
    required String sourceStoreId,
    required String destinationStoreId,
    required String productId,
    String? variantId,
    required int quantity,
    String? notes,
  }) async {
    // Offline-first (Story 5.6): always use local path for instant UX.
    final result = await _offlineTransfer(
      sourceStoreId: sourceStoreId,
      destinationStoreId: destinationStoreId,
      productId: productId,
      variantId: variantId,
      quantity: quantity,
      notes: notes,
    );
    _syncTriggerDispatcher.triggerPushIfIdle();
    return result;
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

    // 2. AC1: Only decrement source at Step 1. Destination is credited at Step 2
    //    (completeTransfer) to prevent premature destination stock inflation.
    await _local.applySourceDecrement(
      productId: productId,
      variantId: variantId,
      sourceStoreId: sourceStoreId,
      quantity: quantity,
    );

    // 3. Enqueue for background sync via SyncService
    final id = const Uuid().v4();
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
      final existingLocal = await _local.getTransferById(transferId);
      if (existingLocal?.status == 'COMPLETED') {
        return existingLocal!;
      }
      final transfer = await _remote.completeTransfer(transferId);
      // Persist updated status locally (COMPLETED).
      await _local.saveTransfer(transfer);
      // AC1: Credit destination stock locally now that transfer is completed.
      await _local.applyDestinationIncrement(
        productId: transfer.productId,
        variantId: transfer.variantId,
        destinationStoreId: transfer.destinationStoreId,
        quantity: transfer.quantity,
      );
      return transfer;
    } else {
      throw StateError('La réception nécessite une connexion active.');
    }
  }
}
