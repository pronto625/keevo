import 'dart:developer' as dev;

import 'package:uuid/uuid.dart';

import '../../../../core/sync/connectivity_service.dart';
import '../../../../core/sync/sync_service.dart';
import '../../../../core/sync/sync_trigger_dispatcher.dart';
import '../../domain/model/client_model.dart';
import '../../domain/repository/client_repository.dart';
import '../datasource/local_client_datasource.dart';
import '../datasource/remote_client_datasource.dart';

/// ClientRepositoryImpl — Offline-first write strategy (Story 5.6).
///
/// ALL reads come from local Drift — no network required.
/// ALL writes go local-first, then queue a sync operation for background push.
class ClientRepositoryImpl implements ClientRepository {
  final LocalClientDataSource _local;
  final RemoteClientDataSource _remote;
  final ConnectivityService _connectivity;
  final SyncService _syncService;
  final SyncTriggerDispatcher _syncTriggerDispatcher;

  const ClientRepositoryImpl({
    required LocalClientDataSource local,
    required RemoteClientDataSource remote,
    required ConnectivityService connectivity,
    required SyncService syncService,
    required SyncTriggerDispatcher syncTriggerDispatcher,
  })  : _local = local,
        _remote = remote,
        _connectivity = connectivity,
        _syncService = syncService,
        _syncTriggerDispatcher = syncTriggerDispatcher;

  @override
  Future<List<ClientModel>> getAll({bool includeArchived = false}) =>
      _local.getAll(includeArchived: includeArchived);

  @override
  Future<List<ClientModel>> search(String query) => _local.search(query);

  @override
  Future<ClientModel?> getById(String id) => _local.getById(id);

  @override
  Future<ClientModel> create({
    required String name,
    required String phone,
    String? email,
    String? notes,
  }) async {
    // Offline-first (Story 5.6): UUID generated client-side, instant return.
    final now = DateTime.now();
    final model = await _local.upsert(ClientModel(
      id: const Uuid().v4(),
      name: name,
      phone: phone,
      email: email,
      notes: notes,
      createdAt: now,
      updatedAt: now,
    ));
    await _syncService.queueOperation(
      operation: 'CREATE_CLIENT',
      payload: {
        'id': model.id,
        'name': name,
        'phone': phone,
        if (email != null) 'email': email,
        if (notes != null) 'notes': notes,
      },
      entityId: model.id,
    );
    _syncTriggerDispatcher.triggerPushIfIdle();
    return model;
  }

  @override
  Future<ClientModel> update({
    required String id,
    String? name,
    String? phone,
    String? email,
    String? notes,
  }) async {
    // Offline-first (Story 5.6): unified path — no online/offline branching.
    final existing = await _local.getById(id);
    final now = DateTime.now();
    final updated = (existing ?? ClientModel(
      id: id,
      name: name ?? '',
      phone: phone ?? '',
      createdAt: now,
      updatedAt: now,
    )).copyWith(
      name: name ?? existing?.name ?? '',
      phone: phone ?? existing?.phone ?? '',
      email: email ?? existing?.email,
      notes: notes ?? existing?.notes,
      updatedAt: now,
    );
    await _local.upsert(updated);
    await _syncService.queueOperation(
      operation: 'UPDATE_CLIENT',
      payload: {
        'clientId': id,
        if (name != null) 'name': name,
        if (phone != null) 'phone': phone,
        if (email != null) 'email': email,
        if (notes != null) 'notes': notes,
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
      operation: 'ARCHIVE_CLIENT',
      payload: {'clientId': id},
      entityId: id,
    );
    _syncTriggerDispatcher.triggerPushIfIdle();
  }

  @override
  Future<void> syncFromRemote() async {
    try {
      final remotes = await _remote.getAll(includeArchived: true);
      for (final c in remotes) {
        await _local.upsert(c);
      }
    } catch (e) {
      dev.log('ClientRepository.syncFromRemote: $e');
    }
  }
}
