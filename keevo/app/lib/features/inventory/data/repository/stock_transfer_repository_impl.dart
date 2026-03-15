import 'dart:convert';
import 'dart:developer' as dev;

import 'package:uuid/uuid.dart';

import '../../../../core/storage/app_database.dart';
import '../../domain/model/stock_transfer_model.dart';
import '../../domain/repository/stock_transfer_repository.dart';
import '../datasource/local_stock_transfer_datasource.dart';
import '../datasource/remote_stock_transfer_datasource.dart';

/// StockTransferRepositoryImpl — offline-first implementation of StockTransferRepository.
///
/// Online strategy: remote first, cache result locally + apply local stock change.
/// Offline strategy:
///   1. Validate against local stock_levels (throws if insufficient)
///   2. Apply local stock change atomically (decrement source, increment destination)
///   3. Queue in sync_queue with operation = 'STOCK_TRANSFER'
///   4. Save transfer record as PENDING_SYNC
///
/// Story 3.3.
class StockTransferRepositoryImpl implements StockTransferRepository {
  final LocalStockTransferDataSource _local;
  final RemoteStockTransferDataSource _remote;
  final AppDatabase _db;
  final bool Function() _isOnline;

  const StockTransferRepositoryImpl({
    required LocalStockTransferDataSource local,
    required RemoteStockTransferDataSource remote,
    required AppDatabase db,
    required bool Function() isOnline,
  })  : _local = local,
        _remote = remote,
        _db = db,
        _isOnline = isOnline;

  @override
  Future<StockTransferModel> executeTransfer({
    required String sourceStoreId,
    required String destinationStoreId,
    required String productId,
    String? variantId,
    required int quantity,
    String? notes,
  }) async {
    if (_isOnline()) {
      final transfer = await _remote.executeTransfer(
        sourceStoreId: sourceStoreId,
        destinationStoreId: destinationStoreId,
        productId: productId,
        variantId: variantId,
        quantity: quantity,
        notes: notes,
      );
      await _local.saveTransfer(transfer);
      // Write-through: keep local Drift stock in sync
      await _local.applyLocalStockChange(
        productId: productId,
        variantId: variantId,
        sourceStoreId: sourceStoreId,
        destinationStoreId: destinationStoreId,
        quantity: quantity,
      );
      return transfer;
    } else {
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

      // 3. Enqueue for background sync
      final id = const Uuid().v4();
      await _db.into(_db.syncQueue).insert(
        SyncQueueCompanion.insert(
          id: id,
          operation: 'STOCK_TRANSFER',
          payload: jsonEncode({
            'sourceStoreId': sourceStoreId,
            'destinationStoreId': destinationStoreId,
            'productId': productId,
            if (variantId != null) 'variantId': variantId,
            'quantity': quantity,
            if (notes != null) 'notes': notes,
          }),
          createdAt: DateTime.now(),
        ),
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
  }

  @override
  Future<List<StockTransferModel>> getHistory({
    String? sourceStoreId,
    String? destinationStoreId,
    String? productId,
    int page = 0,
    int pageSize = 20,
  }) async {
    if (!_isOnline()) {
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
    if (_isOnline()) {
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
