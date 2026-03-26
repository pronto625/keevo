import 'dart:developer' as dev;

import 'package:uuid/uuid.dart';

import '../../../../core/sync/connectivity_service.dart';
import '../../../../core/sync/sync_service.dart';
import '../../domain/model/inventory_count_model.dart';
import '../../domain/model/inventory_product_row_model.dart';
import '../../domain/repository/inventory_count_repository.dart';
import '../datasource/local_inventory_count_datasource.dart';
import '../datasource/local_inventory_session_datasource.dart';
import '../datasource/remote_inventory_count_datasource.dart';

/// InventoryCountRepositoryImpl — Backend-First offline-capable repository.
///
/// Online: remote first → cache locally.
/// Offline: local Drift + sync_queue.
///
/// Story 6.2.
class InventoryCountRepositoryImpl implements InventoryCountRepository {
  final LocalInventoryCountDataSource _localCount;
  final LocalInventorySessionDataSource _localSession;
  final RemoteInventoryCountDataSource _remote;
  final ConnectivityService _connectivity;
  final SyncService _syncService;

  const InventoryCountRepositoryImpl({
    required LocalInventoryCountDataSource localCount,
    required LocalInventorySessionDataSource localSession,
    required RemoteInventoryCountDataSource remote,
    required ConnectivityService connectivity,
    required SyncService syncService,
  })  : _localCount = localCount,
        _localSession = localSession,
        _remote = remote,
        _connectivity = connectivity,
        _syncService = syncService;

  @override
  Future<List<InventoryProductRowModel>> getCountingProducts(
      String sessionId) async {
    if (await _connectivity.isOnline()) {
      try {
        final remote = await _remote.getCountingProducts(sessionId);
        // Cache remote counts locally for resume support
        return remote;
      } catch (e) {
        dev.log('Remote getCountingProducts failed, falling back to offline: $e',
            name: 'InventoryCountRepo');
      }
    }
    // Offline: build from local Drift tables
    final session = await _localSession.findById(sessionId);
    if (session == null) return [];

    List<String>? categoryIds;
    if (session.categoryIds != null && session.categoryIds!.isNotEmpty) {
      categoryIds = session.categoryIds;
    }

    return _localCount.buildOfflineProductRows(
      sessionId,
      session.storeId,
      categoryIds,
    );
  }

  @override
  Future<InventoryCountModel> saveCount({
    required String sessionId,
    required String productId,
    String? variantId,
    required String productName,
    String? variantLabel,
    required int theoretical,
    required int physical,
  }) async {
    if (await _connectivity.isOnline()) {
      try {
        final remote = await _remote.saveCount(
          sessionId: sessionId,
          productId: productId,
          variantId: variantId,
          productName: productName,
          variantLabel: variantLabel,
          theoretical: theoretical,
          physical: physical,
        );
        // Cache remotely-saved count locally (synced = true)
        await _localCount.upsert(remote.copyWith(synced: true));
        return remote;
      } catch (e) {
        dev.log('Remote saveCount failed, falling back to offline: $e',
            name: 'InventoryCountRepo');
      }
    }

    // Offline: create locally + queue for sync
    final now = DateTime.now();
    final id = const Uuid().v4();
    final local = InventoryCountModel(
      id: id,
      sessionId: sessionId,
      productId: productId,
      variantId: variantId,
      productName: productName,
      variantLabel: variantLabel,
      theoretical: theoretical,
      physical: physical,
      countedBy: null, // resolved at sync time
      countedAt: now,
      updatedAt: now,
      synced: false,
    );
    await _localCount.upsert(local);
    await _syncService.queueOperation(
      operation: 'SAVE_INVENTORY_COUNT',
      payload: {
        'sessionId': sessionId,
        'productId': productId,
        if (variantId != null) 'variantId': variantId,
        'productName': productName,
        if (variantLabel != null) 'variantLabel': variantLabel,
        'theoretical': theoretical,
        'physical': physical,
      },
      entityId: id,
    );
    return local;
  }

  @override
  Future<List<InventoryCountModel>> getCountsForSession(
      String sessionId) async {
    return _localCount.findBySessionId(sessionId);
  }
}
