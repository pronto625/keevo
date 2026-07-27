import 'dart:developer' as dev;

import 'package:uuid/uuid.dart';

import '../../../../core/sync/sync_service.dart';
import '../../../../core/sync/sync_trigger_dispatcher.dart';
import '../../domain/exception/store_exception.dart';
import '../../domain/model/store_model.dart';
import '../../domain/model/store_type.dart';
import '../../domain/repository/store_repository.dart';
import '../datasource/local_store_datasource.dart';
import '../datasource/remote_store_datasource.dart';

/// StoreRepositoryImpl — write-through offline-first strategy (Story 3.1).
///
/// Reads: always from local Drift DB (background remote refresh via [syncFromRemote]).
/// Writes: remote-first; on network failure → local optimistic update + sync_queue.
class StoreRepositoryImpl implements StoreRepository {
  final LocalStoreDataSource _local;
  final RemoteStoreDataSource _remote;
  final SyncService _syncService;
  final SyncTriggerDispatcher _syncTriggerDispatcher;

  const StoreRepositoryImpl({
    required LocalStoreDataSource local,
    required RemoteStoreDataSource remote,
    required SyncService syncService,
    required SyncTriggerDispatcher syncTriggerDispatcher,
  })  : _local = local,
        _remote = remote,
        _syncService = syncService,
        _syncTriggerDispatcher = syncTriggerDispatcher;

  @override
  Future<List<StoreModel>> getStores({bool includeInactive = false}) =>
      _local.getAll(includeInactive: includeInactive);

  @override
  Future<StoreModel> createStore({
    required String name,
    required StoreType type,
    String? address,
    String? phone,
  }) async {
    // Remote-first: backend enforces plan limits and warehouse uniqueness.
    try {
      final remote = await _remote.create(
        name: name,
        type: type,
        address: address,
        phone: phone,
      );
      await _local.upsert(remote);
      return remote;
    } on StoreException {
      // Business error (PLAN_LIMIT_EXCEEDED, WAREHOUSE_ALREADY_EXISTS…) — re-throw.
      rethrow;
    } catch (e) {
      // Network unavailable — optimistic local creation + queue for sync.
      dev.log('StoreRepository.createStore: offline — local + sync_queue: $e');
      final local = StoreModel(
        id: const Uuid().v4(),
        name: name,
        type: type,
        address: address,
        phone: phone,
        isActive: true,
        createdAt: DateTime.now(),
        updatedAt: DateTime.now(),
      );
      await _local.upsert(local);
      await _syncService.queueOperation(
        operation: 'CREATE_STORE',
        payload: {
          'id': local.id,
          'name': name,
          'type': type.name.toUpperCase(),
          if (address != null) 'address': address,
          if (phone != null) 'phone': phone,
        },
        entityId: local.id,
      );
      _syncTriggerDispatcher.triggerPushIfIdle();
      return local;
    }
  }

  @override
  Future<StoreModel> updateStore({
    required String storeId,
    required String name,
    String? address,
    String? phone,
  }) async {
    try {
      final remote = await _remote.update(
        storeId: storeId,
        name: name,
        address: address,
        phone: phone,
      );
      await _local.upsert(remote);
      return remote;
    } on StoreException {
      // Business error (STORE_NOT_FOUND…) — re-throw.
      rethrow;
    } catch (e) {
      dev.log('StoreRepository.updateStore: offline — local + sync_queue: $e');
      final existing = await _local.getById(storeId);
      final now = DateTime.now();
      final updated = (existing ??
              StoreModel(
                id: storeId,
                name: name,
                createdAt: now,
                updatedAt: now,
              ))
          .copyWith(
        name: name,
        address: address ?? existing?.address,
        phone: phone ?? existing?.phone,
        updatedAt: now,
      );
      await _local.upsert(updated);
      await _syncService.queueOperation(
        operation: 'UPDATE_STORE',
        payload: {
          'storeId': storeId,
          'name': name,
          if (address != null) 'address': address,
          if (phone != null) 'phone': phone,
        },
        entityId: storeId,
      );
      _syncTriggerDispatcher.triggerPushIfIdle();
      return updated;
    }
  }

  @override
  Future<StoreModel> deactivateStore(String storeId) async {
    try {
      final remote = await _remote.deactivate(storeId);
      await _local.upsert(remote);
      return remote;
    } on StoreException {
      // Business error (STORE_NOT_FOUND…) — re-throw.
      rethrow;
    } catch (e) {
      dev.log('StoreRepository.deactivateStore: offline — local + sync_queue: $e');
      await _local.deactivate(storeId);
      await _syncService.queueOperation(
        operation: 'DEACTIVATE_STORE',
        payload: {'storeId': storeId},
        entityId: storeId,
      );
      _syncTriggerDispatcher.triggerPushIfIdle();
      final existing = await _local.getById(storeId);
      return existing!.copyWith(isActive: false);
    }
  }

  @override
  Future<void> syncFromRemote() async {
    try {
      final remotes = await _remote.getAll(includeInactive: true);
      for (final s in remotes) {
        await _local.upsert(s);
      }
    } catch (e) {
      dev.log('StoreRepository.syncFromRemote: $e');
    }
  }
}
