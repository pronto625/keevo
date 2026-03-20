import 'dart:developer' as dev;

import '../../../../core/sync/connectivity_service.dart';
import '../../../../core/sync/sync_service.dart';
import '../../domain/model/client_model.dart';
import '../../domain/repository/client_repository.dart';
import '../datasource/local_client_datasource.dart';
import '../datasource/remote_client_datasource.dart';

/// ClientRepositoryImpl — Backend-first write-through strategy (Story 2.5 + 5.1).
///
/// ALL reads come from local Drift — no network required.
/// Writes go to backend first when online, fall back to local + sync_queue when offline.
class ClientRepositoryImpl implements ClientRepository {
  final LocalClientDataSource _local;
  final RemoteClientDataSource _remote;
  final ConnectivityService _connectivity;
  final SyncService _syncService;

  const ClientRepositoryImpl({
    required LocalClientDataSource local,
    required RemoteClientDataSource remote,
    required ConnectivityService connectivity,
    required SyncService syncService,
  })  : _local = local,
        _remote = remote,
        _connectivity = connectivity,
        _syncService = syncService;

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
    try {
      final remote = await _remote.create({
        'name': name,
        'phone': phone,
        if (email != null) 'email': email,
        if (notes != null) 'notes': notes,
      });
      await _local.upsert(remote);
      return remote;
    } catch (e) {
      dev.log('ClientRepository.create: offline — local only: $e');
      final now = DateTime.now();
      return _local.upsert(ClientModel(
        id: DateTime.now().millisecondsSinceEpoch.toString(),
        name: name,
        phone: phone,
        email: email,
        notes: notes,
        createdAt: now,
        updatedAt: now,
      ));
    }
  }

  @override
  Future<ClientModel> update({
    required String id,
    String? name,
    String? phone,
    String? email,
    String? notes,
  }) async {
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

    if (await _connectivity.isOnline()) {
      try {
        final remote = await _remote.update(id, {
          if (name != null) 'name': name,
          if (phone != null) 'phone': phone,
          if (email != null) 'email': email,
          if (notes != null) 'notes': notes,
        });
        await _local.upsert(remote);
        return remote;
      } catch (e) {
        dev.log('ClientRepository.update: backend failed — local + queue: $e');
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
        return updated;
      }
    } else {
      dev.log('ClientRepository.update: offline — local + queue');
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
        dev.log('ClientRepository.archive: backend failed — local + queue: $e');
        await _local.archive(id);
        await _syncService.queueOperation(
          operation: 'ARCHIVE_CLIENT',
          payload: {'clientId': id},
          entityId: id,
        );
      }
    } else {
      await _local.archive(id);
      await _syncService.queueOperation(
        operation: 'ARCHIVE_CLIENT',
        payload: {'clientId': id},
        entityId: id,
      );
    }
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
