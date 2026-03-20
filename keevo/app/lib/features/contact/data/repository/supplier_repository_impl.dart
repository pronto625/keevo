import 'dart:developer' as dev;

import '../../../../core/sync/connectivity_service.dart';
import '../../../../core/sync/sync_service.dart';
import '../../domain/model/supplier_model.dart';
import '../../domain/repository/supplier_repository.dart';
import '../datasource/local_supplier_datasource.dart';
import '../datasource/remote_supplier_datasource.dart';

/// SupplierRepositoryImpl — Backend-first write-through strategy (Story 2.5 + 5.1).
class SupplierRepositoryImpl implements SupplierRepository {
  final LocalSupplierDataSource _local;
  final RemoteSupplierDataSource _remote;
  final ConnectivityService _connectivity;
  final SyncService _syncService;

  const SupplierRepositoryImpl({
    required LocalSupplierDataSource local,
    required RemoteSupplierDataSource remote,
    required ConnectivityService connectivity,
    required SyncService syncService,
  })  : _local = local,
        _remote = remote,
        _connectivity = connectivity,
        _syncService = syncService;

  @override
  Future<List<SupplierModel>> getAll({bool includeArchived = false}) =>
      _local.getAll(includeArchived: includeArchived);

  @override
  Future<List<SupplierModel>> search(String query) => _local.search(query);

  @override
  Future<SupplierModel?> getById(String id) => _local.getById(id);

  @override
  Future<SupplierModel> create({
    required String name,
    required String phone,
    String? email,
    List<String> productIds = const [],
  }) async {
    try {
      final remote = await _remote.create({
        'name': name,
        'phone': phone,
        if (email != null) 'email': email,
        if (productIds.isNotEmpty) 'productIds': productIds,
      });
      // Merge productIds from caller: remote DTO may omit them if backend
      // version doesn't return them yet.
      final merged = remote.productIds.isNotEmpty
          ? remote
          : remote.copyWith(productIds: productIds);
      await _local.upsert(merged);
      return merged;
    } catch (e) {
      dev.log('SupplierRepository.create: offline — local only: $e');
      final now = DateTime.now();
      return _local.upsert(SupplierModel(
        id: DateTime.now().millisecondsSinceEpoch.toString(),
        name: name,
        phone: phone,
        email: email,
        productIds: productIds,
        createdAt: now,
        updatedAt: now,
      ));
    }
  }

  @override
  Future<SupplierModel> update({
    required String id,
    String? name,
    String? phone,
    String? email,
    List<String>? productIds,
  }) async {
    final existing = await _local.getById(id);
    final now = DateTime.now();
    final updated = (existing ?? SupplierModel(
      id: id,
      name: name ?? '',
      phone: phone ?? '',
      createdAt: now,
      updatedAt: now,
    )).copyWith(
      name: name ?? existing?.name ?? '',
      phone: phone ?? existing?.phone ?? '',
      email: email ?? existing?.email,
      productIds: productIds ?? existing?.productIds ?? const [],
      updatedAt: now,
    );

    final payload = {
      'supplierId': id,
      if (name != null) 'name': name,
      if (phone != null) 'phone': phone,
      if (email != null) 'email': email,
      if (productIds != null) 'productIds': productIds,
    };

    if (await _connectivity.isOnline()) {
      try {
        final remote = await _remote.update(id, {
          if (name != null) 'name': name,
          if (phone != null) 'phone': phone,
          if (email != null) 'email': email,
          if (productIds != null) 'productIds': productIds,
        });
        final resolvedIds = remote.productIds.isNotEmpty
            ? remote.productIds
            : (productIds ?? existing?.productIds ?? const []);
        final merged = remote.copyWith(productIds: resolvedIds);
        await _local.upsert(merged);
        return merged;
      } catch (e) {
        dev.log('SupplierRepository.update: backend failed — local + queue: $e');
        await _local.upsert(updated);
        await _syncService.queueOperation(
          operation: 'UPDATE_SUPPLIER',
          payload: payload,
          entityId: id,
        );
        return updated;
      }
    } else {
      dev.log('SupplierRepository.update: offline — local + queue');
      await _local.upsert(updated);
      await _syncService.queueOperation(
        operation: 'UPDATE_SUPPLIER',
        payload: payload,
        entityId: id,
      );
      return updated;
    }
  }

  @override
  Future<void> archive(String id) async {
    if (await _connectivity.isOnline()) {
      try {
        await _remote.archive(id);
        await _local.archive(id);
      } catch (e) {
        dev.log('SupplierRepository.archive: backend failed — local + queue: $e');
        await _local.archive(id);
        await _syncService.queueOperation(
          operation: 'ARCHIVE_SUPPLIER',
          payload: {'supplierId': id},
          entityId: id,
        );
      }
    } else {
      await _local.archive(id);
      await _syncService.queueOperation(
        operation: 'ARCHIVE_SUPPLIER',
        payload: {'supplierId': id},
        entityId: id,
      );
    }
  }

  @override
  Future<void> syncFromRemote() async {
    try {
      final remotes = await _remote.getAll(includeArchived: true);
      for (final s in remotes) {
        await _local.upsert(s);
      }
    } catch (e) {
      dev.log('SupplierRepository.syncFromRemote: $e');
    }
  }
}
