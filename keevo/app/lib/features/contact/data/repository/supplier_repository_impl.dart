import 'dart:developer' as dev;

import 'package:uuid/uuid.dart';

import '../../../../core/sync/connectivity_service.dart';
import '../../../../core/sync/sync_service.dart';
import '../../../../core/sync/sync_trigger_dispatcher.dart';
import '../../domain/model/supplier_model.dart';
import '../../domain/repository/supplier_repository.dart';
import '../datasource/local_supplier_datasource.dart';
import '../datasource/remote_supplier_datasource.dart';

/// SupplierRepositoryImpl — Offline-first write strategy (Story 5.6).
class SupplierRepositoryImpl implements SupplierRepository {
  final LocalSupplierDataSource _local;
  final RemoteSupplierDataSource _remote;
  final ConnectivityService _connectivity;
  final SyncService _syncService;
  final SyncTriggerDispatcher _syncTriggerDispatcher;

  const SupplierRepositoryImpl({
    required LocalSupplierDataSource local,
    required RemoteSupplierDataSource remote,
    required ConnectivityService connectivity,
    required SyncService syncService,
    required SyncTriggerDispatcher syncTriggerDispatcher,
  })  : _local = local,
        _remote = remote,
        _connectivity = connectivity,
        _syncService = syncService,
        _syncTriggerDispatcher = syncTriggerDispatcher;

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
    // Offline-first (Story 5.6): UUID generated client-side, instant return.
    final now = DateTime.now();
    final model = await _local.upsert(SupplierModel(
      id: const Uuid().v4(),
      name: name,
      phone: phone,
      email: email,
      productIds: productIds,
      createdAt: now,
      updatedAt: now,
    ));
    await _syncService.queueOperation(
      operation: 'CREATE_SUPPLIER',
      payload: {
        'id': model.id,
        'name': name,
        'phone': phone,
        if (email != null) 'email': email,
        if (productIds.isNotEmpty) 'productIds': productIds,
      },
      entityId: model.id,
    );
    _syncTriggerDispatcher.triggerPushIfIdle();
    return model;
  }

  @override
  Future<SupplierModel> update({
    required String id,
    String? name,
    String? phone,
    String? email,
    List<String>? productIds,
  }) async {
    // Offline-first (Story 5.6): unified path — no online/offline branching.
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
    await _local.upsert(updated);
    await _syncService.queueOperation(
      operation: 'UPDATE_SUPPLIER',
      payload: {
        'supplierId': id,
        if (name != null) 'name': name,
        if (phone != null) 'phone': phone,
        if (email != null) 'email': email,
        if (productIds != null) 'productIds': productIds,
      },
      entityId: id,
    );
    _syncTriggerDispatcher.triggerPushIfIdle();
    return updated;
  }

  @override
  Future<void> archive(String id) async {
    await _local.archive(id);
    await _syncService.queueOperation(
      operation: 'ARCHIVE_SUPPLIER',
      payload: {'supplierId': id},
      entityId: id,
    );
    _syncTriggerDispatcher.triggerPushIfIdle();
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
