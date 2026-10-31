import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/state/keep_alive_while.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/sync/riverpod_sync_trigger_dispatcher.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/local_client_datasource.dart';
import '../../data/datasource/local_supplier_datasource.dart';
import '../../data/datasource/remote_client_datasource.dart';
import '../../data/datasource/remote_supplier_datasource.dart';
import '../../data/repository/client_repository_impl.dart';
import '../../data/repository/supplier_repository_impl.dart';
import '../../domain/model/client_model.dart';
import '../../domain/model/supplier_model.dart';
import '../../domain/repository/client_repository.dart';
import '../../domain/repository/supplier_repository.dart';

part 'contact_provider.g.dart';

// ── Infrastructure ──────────────────────────────────────────────────────────

final localClientDataSourceProvider = Provider<LocalClientDataSource>((ref) {
  return LocalClientDataSource(ref.watch(appDatabaseProvider));
});

final localSupplierDataSourceProvider = Provider<LocalSupplierDataSource>((ref) {
  return LocalSupplierDataSource(ref.watch(appDatabaseProvider));
});

final remoteClientDataSourceProvider = Provider<RemoteClientDataSource>((ref) {
  return RemoteClientDataSource(dio: ref.watch(dioProvider));
});

final remoteSupplierDataSourceProvider = Provider<RemoteSupplierDataSource>((ref) {
  return RemoteSupplierDataSource(dio: ref.watch(dioProvider));
});

// ── Repositories ─────────────────────────────────────────────────────────────

final clientRepositoryProvider = Provider<ClientRepository>((ref) {
  return ClientRepositoryImpl(
    local: ref.watch(localClientDataSourceProvider),
    remote: ref.watch(remoteClientDataSourceProvider),
    syncService: ref.watch(syncServiceProvider),
    syncTriggerDispatcher: ref.watch(syncTriggerDispatcherProvider),
    db: ref.watch(appDatabaseProvider),
  );
});

final supplierRepositoryProvider = Provider<SupplierRepository>((ref) {
  return SupplierRepositoryImpl(
    local: ref.watch(localSupplierDataSourceProvider),
    remote: ref.watch(remoteSupplierDataSourceProvider),
    syncService: ref.watch(syncServiceProvider),
    syncTriggerDispatcher: ref.watch(syncTriggerDispatcherProvider),
    db: ref.watch(appDatabaseProvider),
  );
});

// ── Client state notifier ────────────────────────────────────────────────────

@riverpod
class ClientListNotifier extends _$ClientListNotifier {
  // Mémorise le choix « Voir les archivés » pour que sync et recherche vide
  // ne le réinitialisent pas.
  bool _includeArchived = false;

  @override
  Future<List<ClientModel>> build() async {
    final repo = ref.watch(clientRepositoryProvider);
    return _query.isEmpty
        ? repo.getAll(includeArchived: _includeArchived)
        : repo.search(_query, includeArchived: _includeArchived);
  }

  // v1s-16-13: la recherche en cours survit au rafraîchissement (sync,
  // création, archivage, bascule « Voir les archivés »).
  String _query = '';

  /// Reloads the list, keeping the current search and archive filter.
  // Each reload takes a number: only the latest one may write the state
  // (a slower earlier search must not overwrite the current one).
  int _reloadSeq = 0;

  Future<void> refresh({bool? includeArchived}) =>
      ref.keepAliveWhile(() async {
        if (includeArchived != null) _includeArchived = includeArchived;
        final seq = ++_reloadSeq;
        state = const AsyncLoading();
        final repo = ref.read(clientRepositoryProvider);
        final result = await AsyncValue.guard(() => _query.isEmpty
            ? repo.getAll(includeArchived: _includeArchived)
            : repo.search(_query, includeArchived: _includeArchived));
        // Riverpod 3: a disposed notifier throws on `state` (an invalidation
        // mid-await drops the keep-alive of the old build).
        if (ref.mounted && seq == _reloadSeq) state = result;
      });

  Future<void> search(String query) =>
      ref.keepAliveWhile(() async {
        _query = query.trim();
        await refresh();
      });

  Future<ClientModel> create({
    required String name,
    required String phone,
    String? email,
    String? notes,
  }) =>
      ref.keepAliveWhile(() async {
        final result = await ref.read(clientRepositoryProvider).create(
              name: name,
              phone: phone,
              email: email,
              notes: notes,
            );
        if (ref.mounted) await refresh();
        return result;
      });

  Future<ClientModel> patchClient({
    required String id,
    String? name,
    String? phone,
    String? email,
    String? notes,
  }) =>
      ref.keepAliveWhile(() async {
        final result = await ref.read(clientRepositoryProvider).update(
              id: id,
              name: name,
              phone: phone,
              email: email,
              notes: notes,
            );
        if (ref.mounted) await refresh();
        return result;
      });

  Future<void> archive(String id) =>
      ref.keepAliveWhile(() async {
        await ref.read(clientRepositoryProvider).archive(id);
        if (ref.mounted) await refresh();
      });

  Future<void> unarchive(String id) =>
      ref.keepAliveWhile(() async {
        await ref.read(clientRepositoryProvider).unarchive(id);
        if (ref.mounted) await refresh();
      });

  Future<void> syncFromRemote() =>
      ref.keepAliveWhile(() async {
        try {
          await ref.read(clientRepositoryProvider).syncFromRemote();
        } finally {
          if (ref.mounted) await refresh();
        }
      });
}

// ── Supplier state notifier ──────────────────────────────────────────────────

@riverpod
class SupplierListNotifier extends _$SupplierListNotifier {
  // Mémorise le choix « Voir les archivés » pour que sync et recherche vide
  // ne le réinitialisent pas.
  bool _includeArchived = false;

  @override
  Future<List<SupplierModel>> build() async {
    final repo = ref.watch(supplierRepositoryProvider);
    return _query.isEmpty
        ? repo.getAll(includeArchived: _includeArchived)
        : repo.search(_query, includeArchived: _includeArchived);
  }

  // v1s-16-13: la recherche en cours survit au rafraîchissement (sync,
  // création, archivage, bascule « Voir les archivés »).
  String _query = '';

  /// Reloads the list, keeping the current search and archive filter.
  // Each reload takes a number: only the latest one may write the state
  // (a slower earlier search must not overwrite the current one).
  int _reloadSeq = 0;

  Future<void> refresh({bool? includeArchived}) =>
      ref.keepAliveWhile(() async {
        if (includeArchived != null) _includeArchived = includeArchived;
        final seq = ++_reloadSeq;
        state = const AsyncLoading();
        final repo = ref.read(supplierRepositoryProvider);
        final result = await AsyncValue.guard(() => _query.isEmpty
            ? repo.getAll(includeArchived: _includeArchived)
            : repo.search(_query, includeArchived: _includeArchived));
        // Riverpod 3: a disposed notifier throws on `state` (an invalidation
        // mid-await drops the keep-alive of the old build).
        if (ref.mounted && seq == _reloadSeq) state = result;
      });

  Future<void> search(String query) =>
      ref.keepAliveWhile(() async {
        _query = query.trim();
        await refresh();
      });

  Future<SupplierModel> create({
    required String name,
    required String phone,
    String? email,
    List<String> productIds = const [],
  }) =>
      ref.keepAliveWhile(() async {
        final result = await ref.read(supplierRepositoryProvider).create(
              name: name,
              phone: phone,
              email: email,
              productIds: productIds,
            );
        if (ref.mounted) await refresh();
        return result;
      });

  Future<SupplierModel> patchSupplier({
    required String id,
    String? name,
    String? phone,
    String? email,
    List<String>? productIds,
  }) =>
      ref.keepAliveWhile(() async {
        final result = await ref.read(supplierRepositoryProvider).update(
              id: id,
              name: name,
              phone: phone,
              email: email,
              productIds: productIds,
            );
        if (ref.mounted) await refresh();
        return result;
      });

  Future<void> archive(String id) =>
      ref.keepAliveWhile(() async {
        await ref.read(supplierRepositoryProvider).archive(id);
        if (ref.mounted) await refresh();
      });

  Future<void> unarchive(String id) =>
      ref.keepAliveWhile(() async {
        await ref.read(supplierRepositoryProvider).unarchive(id);
        if (ref.mounted) await refresh();
      });

  Future<void> syncFromRemote() =>
      ref.keepAliveWhile(() async {
        try {
          await ref.read(supplierRepositoryProvider).syncFromRemote();
        } finally {
          if (ref.mounted) await refresh();
        }
      });
}

// ── Product-supplier lookup ──────────────────────────────────────────────────

/// Fetches the supplier linked to a product.
/// Tries remote first (and caches result locally), falls back to local on error.
@riverpod
Future<SupplierModel?> productSupplier(
  Ref ref,
  String productId,
) async {
  final remote = ref.watch(remoteSupplierDataSourceProvider);
  final local = ref.watch(localSupplierDataSourceProvider);
  try {
    final supplier = await remote.getByProductId(productId);
    if (supplier != null) {
      await local.upsert(supplier);
    }
    return supplier;
  } catch (_) {
    return local.getByProductId(productId);
  }
}
