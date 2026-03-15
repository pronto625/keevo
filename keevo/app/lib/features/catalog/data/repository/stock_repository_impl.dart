import 'dart:developer' as dev;

import '../../domain/model/cross_store_availability_model.dart';
import '../../domain/model/stock_level_model.dart';
import '../../domain/model/stock_movement_model.dart';
import '../../domain/repository/stock_repository.dart';
import '../datasource/local_stock_datasource.dart';
import '../datasource/remote_stock_datasource.dart';

/// StockRepositoryImpl — online-first with offline fallback.
///
/// Strategy:
/// - Read: try remote first, cache to local, fall back to local on error.
/// - Write: remote first, update local cache on success.
///
/// Story 2.3.
class StockRepositoryImpl implements StockRepository {
  final LocalStockDataSource _local;
  final RemoteStockDataSource _remote;

  const StockRepositoryImpl({
    required LocalStockDataSource local,
    required RemoteStockDataSource remote,
  })  : _local = local,
        _remote = remote;

  // ── Stock Levels ─────────────────────────────────────────────────────────

  @override
  Future<List<StockLevelModel>> getLevels(String productId) async {
    try {
      final levels = await _remote.getLevels(productId);
      for (final level in levels) {
        await _local.upsertLevel(level);
      }
      return levels;
    } catch (e) {
      dev.log('[Stock] Remote getLevels failed — using local cache: $e',
          name: 'StockRepository');
      return _local.getLevels(productId);
    }
  }

  @override
  Future<StockLevelModel?> getLevelByStore(
    String productId,
    String storeId,
  ) async {
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
      // Cache remote results locally so offline fallback stays up-to-date
      // and history survives an app reinstall once connectivity is restored.
      for (final m in movements) {
        await _local.upsertMovement(m);
      }
      return movements;
    } catch (e) {
      dev.log('[Stock] Remote history failed — using local: $e',
          name: 'StockRepository');
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
    final movement = await _remote.recordEntry(
      productId: productId,
      variantId: variantId,
      storeId: storeId,
      quantity: quantity,
      notes: notes,
    );
    await _local.insertMovement(movement);
    // Refresh local level cache
    await _refreshLocalLevels(productId);
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
    final movement = await _remote.adjustStock(
      productId: productId,
      variantId: variantId,
      storeId: storeId,
      newQuantity: newQuantity,
      notes: notes,
    );
    await _local.insertMovement(movement);
    await _refreshLocalLevels(productId);
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
