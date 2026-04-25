import 'dart:developer' as dev;

import 'package:uuid/uuid.dart';

import '../../domain/model/cross_store_availability_model.dart';
import '../../domain/model/stock_level_model.dart';
import '../../domain/model/stock_movement_model.dart';
import '../../domain/repository/stock_repository.dart';
import '../../../../core/sync/connectivity_service.dart';
import '../../../../core/sync/sync_service.dart';
import '../../../../core/sync/sync_trigger_dispatcher.dart';
import '../datasource/local_stock_datasource.dart';
import '../datasource/remote_stock_datasource.dart';

/// StockRepositoryImpl — Offline-first writes (Story 5.6).
///
/// Read: try remote first, cache to local, fall back to local on error.
/// Write: always local-first, queue sync for background push.
///
/// Story 2.3.
class StockRepositoryImpl implements StockRepository {
  final LocalStockDataSource _local;
  final RemoteStockDataSource _remote;
  final SyncService _syncService;
  final ConnectivityService _connectivity;
  final SyncTriggerDispatcher _syncTriggerDispatcher;

  const StockRepositoryImpl({
    required LocalStockDataSource local,
    required RemoteStockDataSource remote,
    required SyncService syncService,
    required ConnectivityService connectivity,
    required SyncTriggerDispatcher syncTriggerDispatcher,
  })  : _local = local,
        _remote = remote,
        _syncService = syncService,
        _connectivity = connectivity,
        _syncTriggerDispatcher = syncTriggerDispatcher;

  // ── Stock Levels ─────────────────────────────────────────────────────────

  @override
  Future<List<StockLevelModel>> getLevels(String productId) async {
    // Best-effort: refresh local cache from remote when no pending operations.
    // Always return LOCAL data so the catalog reads the same source as the POS
    // (which only queries the local DB). Returning remote data directly caused
    // the catalog to show a cross-store sum when the owner has no active store
    // selected, making per-product stock appear doubled vs POS.
    if (!await _syncService.hasPendingOperations()) {
      try {
        final levels = await _remote.getLevels(productId);
        for (final level in levels) {
          await _local.upsertLevel(level);
        }
      } catch (e) {
        dev.log('[Stock] Remote getLevels failed — using local cache: $e',
            name: 'StockRepository');
      }
    }
    return _local.getLevels(productId);
  }

  @override
  Future<StockLevelModel?> getLevelByStore(
    String productId,
    String storeId,
  ) async {
    if (await _syncService.hasPendingOperations()) {
      return _local.getLevelByStore(productId, storeId);
    }
    try {
      final levels = await _remote.getLevels(productId);
      final matching = levels.where((l) => l.storeId == storeId).toList();
      return matching.isNotEmpty ? matching.first : null;
    } catch (e) {
      return _local.getLevelByStore(productId, storeId);
    }
  }

  // ── Cross-store availability (Story 3.4) ─────────────────────────────────

  @override
  Future<CrossStoreAvailabilityModel> getCrossStoreAvailability(
      String productId) async {
    try {
      final model = await _remote.fetchCrossStoreAvailability(productId);
      // AC2: update local Drift cache on remote success
      // Use sync_ prefix + productId_storeId to match Story 3.2 cache convention
      for (final entry in model.entries) {
        await _local.upsertLevel(StockLevelModel(
          id: 'sync_${productId}_${entry.storeId}',
          productId: productId,
          storeId: entry.storeId,
          quantity: entry.quantity,
          minimumThreshold: entry.minimumThreshold,
          updatedAt: model.refreshedAt,
        ));
      }
      return model;
    } catch (e) {
      dev.log('[Stock] Remote getCrossStoreAvailability failed — using local cache: $e',
          name: 'StockRepository');
      return _local.getLocalAvailability(productId);
    }
  }

  // ── Stock Movements ───────────────────────────────────────────────────────

  @override
  Future<List<StockMovementModel>> getMovementHistory(
    String productId, {
    String? storeId,
    String? movementType,
    DateTime? from,
    DateTime? to,
    int page = 0,
    int pageSize = 20,
  }) async {
    // Best-effort: sync remote movements into local cache when online.
    // Always return from local so offline SALE movements (with local UUIDs)
    // are never hidden by a purely-remote result set.
    if (!await _syncService.hasPendingOperations()) {
      try {
        final movements = await _remote.getHistory(
          productId: productId,
          storeId: storeId,
          movementType: movementType,
          from: from,
          to: to,
          page: page,
          pageSize: pageSize,
        );
        for (final m in movements) {
          await _local.upsertMovement(m);
        }
      } catch (e) {
        dev.log('[Stock] Remote history failed — using local: $e',
            name: 'StockRepository');
      }
    }
    return _local.getMovements(
      productId,
      storeId: storeId,
      movementType: movementType,
      from: from,
      to: to,
      page: page,
      pageSize: pageSize,
    );
  }

  // ── Mutations ─────────────────────────────────────────────────────────────

  @override
  Future<StockMovementModel> recordEntry({
    required String productId,
    String? variantId,
    required String storeId,
    required int quantity,
    String? notes,
  }) async {
    // Offline-first (Story 5.6): always use local path, no remote call.
    final movement = await _recordEntryLocally(
      productId: productId,
      variantId: variantId,
      storeId: storeId,
      quantity: quantity,
      notes: notes,
    );
    _syncTriggerDispatcher.triggerPushIfIdle();
    return movement;
  }

  Future<StockMovementModel> _recordEntryLocally({
    required String productId,
    String? variantId,
    required String storeId,
    required int quantity,
    String? notes,
  }) async {
    final currentLevel = await _local.getLevelByStore(productId, storeId);
    final before = currentLevel?.quantity ?? 0;
    final after = before + quantity;

    final movement = StockMovementModel(
      id: const Uuid().v4(),
      productId: productId,
      variantId: variantId,
      storeId: storeId,
      movementType: 'STOCK_ENTRY',
      quantityBefore: before,
      quantityDelta: quantity,
      quantityAfter: after,
      actorId: 'local',
      notes: notes,
      occurredAt: DateTime.now(),
      synced: false,
    );
    await _local.insertMovement(movement);
    await _local.upsertLevel(StockLevelModel(
      id: currentLevel?.id ?? const Uuid().v4(),
      productId: productId,
      variantId: variantId,
      storeId: storeId,
      quantity: after,
      minimumThreshold: currentLevel?.minimumThreshold ?? 0,
      updatedAt: DateTime.now(),
    ));
    await _syncService.queueOperation(
      operation: 'RECORD_STOCK_ENTRY',
      payload: {
        'productId': productId,
        if (variantId != null) 'variantId': variantId,
        'storeId': storeId,
        'quantity': quantity,
        if (notes != null) 'notes': notes,
      },
      entityId: movement.id,
    );
    return movement;
  }

  @override
  Future<StockMovementModel> adjustStock({
    required String productId,
    String? variantId,
    required String storeId,
    required int newQuantity,
    required String notes,
  }) async {
    // Offline-first (Story 5.6): always use local path, no remote call.
    final movement = await _adjustStockLocally(
      productId: productId,
      variantId: variantId,
      storeId: storeId,
      newQuantity: newQuantity,
      notes: notes,
    );
    _syncTriggerDispatcher.triggerPushIfIdle();
    return movement;
  }

  Future<StockMovementModel> _adjustStockLocally({
    required String productId,
    String? variantId,
    required String storeId,
    required int newQuantity,
    required String notes,
  }) async {
    final currentLevel = await _local.getLevelByStore(productId, storeId);
    final before = currentLevel?.quantity ?? 0;
    final delta = newQuantity - before;

    final movement = StockMovementModel(
      id: const Uuid().v4(),
      productId: productId,
      variantId: variantId,
      storeId: storeId,
      movementType: 'STOCK_ADJUST',
      quantityBefore: before,
      quantityDelta: delta,
      quantityAfter: newQuantity,
      actorId: 'local',
      notes: notes,
      occurredAt: DateTime.now(),
      synced: false,
    );
    await _local.insertMovement(movement);
    await _local.upsertLevel(StockLevelModel(
      id: currentLevel?.id ?? const Uuid().v4(),
      productId: productId,
      variantId: variantId,
      storeId: storeId,
      quantity: newQuantity,
      minimumThreshold: currentLevel?.minimumThreshold ?? 0,
      updatedAt: DateTime.now(),
    ));
    await _syncService.queueOperation(
      operation: 'STOCK_ADJUST',
      payload: {
        'productId': productId,
        if (variantId != null) 'variantId': variantId,
        'storeId': storeId,
        'quantity': newQuantity,
        if (notes.isNotEmpty) 'notes': notes,
      },
      entityId: movement.id,
    );
    return movement;
  }

  @override
  Future<void> setThreshold({
    required String productId,
    required int minimumThreshold,
  }) async {
    await _remote.setThreshold(
      productId: productId,
      minimumThreshold: minimumThreshold,
    );
    // Best-effort local update — does not fail the operation if offline cache
    // cannot be updated (mirrors the _refreshLocalLevels pattern).
    try {
      await _local.updateThreshold(productId, minimumThreshold);
    } catch (_) {
      // Ignore — remote succeeded; local will be refreshed on next sync.
    }
  }

  // ── Private ───────────────────────────────────────────────────────────────

  Future<void> _refreshLocalLevels(String productId) async {
    try {
      final levels = await _remote.getLevels(productId);
      for (final level in levels) {
        await _local.upsertLevel(level);
      }
    } catch (_) {
      // Best-effort — local may be stale until next sync
    }
  }
}
