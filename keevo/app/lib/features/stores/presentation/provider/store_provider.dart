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
  );
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

  Future<void> refresh({bool includeInactive = false}) async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(
        () => ref.read(storeRepositoryProvider).getStores(includeInactive: includeInactive));
  }

  Future<StoreModel> createStore({
    required String name,
    required StoreType type,
    String? address,
    String? phone,
  }) async {
    final result = await ref.read(storeRepositoryProvider).createStore(
          name: name,
          type: type,
          address: address,
          phone: phone,
        );
    await refresh();
    return result;
  }

  Future<StoreModel> updateStore({
    required String storeId,
    required String name,
    String? address,
    String? phone,
  }) async {
    final result = await ref.read(storeRepositoryProvider).updateStore(
          storeId: storeId,
          name: name,
          address: address,
          phone: phone,
        );
    await refresh();
    return result;
  }

  Future<void> deactivateStore(String storeId) async {
    await ref.read(storeRepositoryProvider).deactivateStore(storeId);
    await refresh();
  }

  Future<void> syncFromRemote() async {
    await ref.read(storeRepositoryProvider).syncFromRemote();
    await refresh();
  }
}
