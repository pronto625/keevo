import 'dart:developer' as dev;

import 'package:uuid/uuid.dart';

import '../../../../core/sync/connectivity_service.dart';
import '../../../../core/sync/sync_service.dart';
import '../../domain/model/inventory_session_model.dart';
import '../../domain/repository/inventory_session_repository.dart';
import '../datasource/local_inventory_session_datasource.dart';
import '../datasource/remote_inventory_session_datasource.dart';

/// InventorySessionRepositoryImpl — Backend-first implementation.
///
/// Online: remote first, cache locally.
/// Offline: insert locally + queue in sync_queue.
///
/// Story 6.1 + 5.1.
class InventorySessionRepositoryImpl implements InventorySessionRepository {
  final LocalInventorySessionDataSource _local;
  final RemoteInventorySessionDataSource _remote;
  final ConnectivityService _connectivity;
  final SyncService _syncService;

  const InventorySessionRepositoryImpl({
    required LocalInventorySessionDataSource local,
    required RemoteInventorySessionDataSource remote,
    required ConnectivityService connectivity,
    required SyncService syncService,
  })  : _local = local,
        _remote = remote,
        _connectivity = connectivity,
        _syncService = syncService;

  @override
  Future<InventorySessionModel> create({
    required String storeId,
    required String scope,
    List<String>? categoryIds,
  }) async {
    if (await _connectivity.isOnline()) {
      try {
        final session = await _remote.create(
          storeId: storeId,
          scope: scope,
          categoryIds: categoryIds,
        );
        await _local.insert(session);
        return session;
      } catch (e) {
        dev.log('Remote create failed, falling back to offline: $e',
            name: 'InventorySessionRepo');
        return _offlineCreate(storeId: storeId, scope: scope, categoryIds: categoryIds);
      }
    }
    return _offlineCreate(storeId: storeId, scope: scope, categoryIds: categoryIds);
  }

  Future<InventorySessionModel> _offlineCreate({
    required String storeId,
    required String scope,
    List<String>? categoryIds,
  }) async {
    final id = const Uuid().v4();
    final now = DateTime.now();
    final session = InventorySessionModel(
      id: id,
      storeId: storeId,
      scope: scope,
      categoryIds: categoryIds,
      status: 'IN_PROGRESS',
      startedBy: '', // actorId resolved at sync time
      startedAt: now,
      updatedAt: now,
    );
    await _local.insert(session);
    await _syncService.queueOperation(
      operation: 'CREATE_INVENTORY_SESSION',
      payload: {
        'sessionId': id,
        'storeId': storeId,
        'scope': scope,
        if (categoryIds != null) 'categoryIds': categoryIds,
      },
      entityId: id,
    );
    return session;
  }

  @override
  Future<InventorySessionModel?> getActiveByStoreId(String storeId) async {
    return _local.findActiveByStoreId(storeId);
  }

  @override
  Future<void> cancel(String sessionId) async {
    if (await _connectivity.isOnline()) {
      try {
        final cancelled = await _remote.cancel(sessionId);
        await _local.upsert(cancelled);
        return;
      } catch (e) {
        dev.log('Remote cancel failed, falling back to local: $e',
            name: 'InventorySessionRepo');
      }
    }
    // Offline cancel
    await _local.updateStatus(
      sessionId,
      'CANCELLED',
      cancelledBy: null,
      cancelledAt: DateTime.now(),
    );
  }

  @override
  Future<List<InventorySessionModel>> getHistory({
    int page = 0,
    int size = 20,
  }) async {
    return _local.findAll(page: page, size: size);
  }
}
