import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/state/keep_alive_while.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/sync/riverpod_sync_trigger_dispatcher.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/local_store_datasource.dart';
import '../../data/datasource/remote_store_datasource.dart';
import '../../data/repository/store_repository_impl.dart';
import '../../domain/model/store_model.dart';
import '../../domain/model/store_type.dart';
import '../../domain/repository/store_repository.dart';

part 'store_provider.g.dart';

// ── Infrastructure ─────────────────────────────────────────────────────────

final localStoreDataSourceProvider = Provider<LocalStoreDataSource>((ref) {
  return LocalStoreDataSource(ref.watch(appDatabaseProvider));
});

final remoteStoreDataSourceProvider = Provider<RemoteStoreDataSource>((ref) {
  return RemoteStoreDataSource(dio: ref.watch(dioProvider));
});

// ── Repository ──────────────────────────────────────────────────────────────

final storeRepositoryProvider = Provider<StoreRepository>((ref) {
  return StoreRepositoryImpl(
    local: ref.watch(localStoreDataSourceProvider),
    remote: ref.watch(remoteStoreDataSourceProvider),
    syncService: ref.watch(syncServiceProvider),
    syncTriggerDispatcher: ref.watch(syncTriggerDispatcherProvider),
    db: ref.watch(appDatabaseProvider),
    isOwnerSession: () => ref.read(isOwnerSessionProvider),
  );
});

// ── Stores page list ────────────────────────────────────────────────────────

/// Stores shown on the stores management page, deactivated ones included when
/// "Voir les désactivées" is on. Disposed with the page, so the choice never
/// leaks into the other store pickers ([storeListNotifierProvider]).
final managedStoresProvider = FutureProvider.autoDispose
    .family<List<StoreModel>, bool>((ref, includeInactive) {
  return ref
      .watch(storeRepositoryProvider)
      .getStores(includeInactive: includeInactive);
});

// ── State notifier ──────────────────────────────────────────────────────────

@riverpod
class StoreListNotifier extends _$StoreListNotifier {
  @override
  Future<List<StoreModel>> build() async {
    final repo = ref.watch(storeRepositoryProvider);
    // Sync from remote so local DB is populated (silent fail if offline)
    try {
      await repo.syncFromRemote();
    } catch (_) {}
    return repo.getStores();
  }

  /// Active stores only: this list feeds every store picker of the app
  /// (active store, transfers, employees…), which must never offer a
  /// deactivated store. The stores page lists inactive ones through
  /// [managedStoresProvider].
  /// J22: the store mutations wait for build()'s server pull first. The
  /// notifier is auto-disposed between screens, so a « Réactiver » rebuilt it
  /// and its pull — answered before the PATCH but finished after it — wrote
  /// the store back as inactive locally until the next manual sync.
  Future<void> _settled() async {
    try {
      await future;
    } catch (_) {
      // Offline / failed pull: the mutation decides on its own.
    }
  }

  // Riverpod 3: the stores page only reads this auto-dispose notifier, which
  // could be disposed during the awaits below — kept alive per call.
  Future<void> refresh() => ref.keepAliveWhile(() async {
        state = const AsyncLoading();
        final guarded = await AsyncValue.guard(
            () => ref.read(storeRepositoryProvider).getStores());
        // Riverpod 3: a disposed notifier throws on `state` / `ref` (an
        // invalidation mid-await drops the keep-alive of the old build).
        if (!ref.mounted) return;
        state = guarded;
        ref.invalidate(managedStoresProvider);
      });

  Future<StoreModel> createStore({
    required String name,
    required StoreType type,
    String? address,
    String? phone,
  }) =>
      ref.keepAliveWhile(() async {
        // Read before the awaits: the ref may be gone after them.
        final repo = ref.read(storeRepositoryProvider);
        await _settled();
        final result = await repo.createStore(
              name: name,
              type: type,
              address: address,
              phone: phone,
            );
        if (ref.mounted) await refresh();
        return result;
      });

  Future<StoreModel> updateStore({
    required String storeId,
    required String name,
    String? address,
    String? phone,
  }) =>
      ref.keepAliveWhile(() async {
        // Read before the awaits: the ref may be gone after them.
        final repo = ref.read(storeRepositoryProvider);
        await _settled();
        final result = await repo.updateStore(
              storeId: storeId,
              name: name,
              address: address,
              phone: phone,
            );
        if (ref.mounted) await refresh();
        return result;
      });

  Future<void> deactivateStore(String storeId) => ref.keepAliveWhile(() async {
        final repo = ref.read(storeRepositoryProvider);
        await _settled();
        await repo.deactivateStore(storeId);
        if (ref.mounted) await refresh();
      });

  Future<void> reactivateStore(String storeId) => ref.keepAliveWhile(() async {
        final repo = ref.read(storeRepositoryProvider);
        await _settled();
        await repo.reactivateStore(storeId);
        if (ref.mounted) await refresh();
      });

  Future<void> syncFromRemote() => ref.keepAliveWhile(() async {
        try {
          await ref.read(storeRepositoryProvider).syncFromRemote();
        } finally {
          if (ref.mounted) await refresh();
        }
      });
}

