import 'dart:developer' as dev;

import '../../domain/model/store_model.dart';
import '../../domain/model/store_type.dart';
import '../../domain/repository/store_repository.dart';
import '../datasource/local_store_datasource.dart';
import '../datasource/remote_store_datasource.dart';

/// StoreRepositoryImpl — write-through offline-first strategy (Story 3.1).
///
/// Reads: always from local Drift DB (background remote refresh via [syncFromRemote]).
/// Writes: remote-first; on network failure → local optimistic update + log.
class StoreRepositoryImpl implements StoreRepository {
  final LocalStoreDataSource _local;
  final RemoteStoreDataSource _remote;

  const StoreRepositoryImpl({
    required LocalStoreDataSource local,
    required RemoteStoreDataSource remote,
  })  : _local = local,
        _remote = remote;

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
    // Always go remote first for create — plan & warehouse-uniqueness checks
    // live on the server and cannot be replicated offline safely.
    final remote = await _remote.create(
      name: name,
      type: type,
      address: address,
      phone: phone,
    );
    await _local.upsert(remote);
    return remote;
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
    } catch (e) {
      dev.log('StoreRepository.updateStore: offline — local only: $e');
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
      return updated;
    }
  }

  @override
  Future<StoreModel> deactivateStore(String storeId) async {
    try {
      final remote = await _remote.deactivate(storeId);
      await _local.upsert(remote);
      return remote;
    } catch (e) {
      dev.log('StoreRepository.deactivateStore: offline — local only: $e');
      await _local.deactivate(storeId);
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
