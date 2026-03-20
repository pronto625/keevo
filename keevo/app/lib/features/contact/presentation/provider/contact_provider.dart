import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../../core/di/providers.dart';
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
    connectivity: ref.watch(connectivityServiceProvider),
    syncService: ref.watch(syncServiceProvider),
  );
});

final supplierRepositoryProvider = Provider<SupplierRepository>((ref) {
  return SupplierRepositoryImpl(
    local: ref.watch(localSupplierDataSourceProvider),
    remote: ref.watch(remoteSupplierDataSourceProvider),
    connectivity: ref.watch(connectivityServiceProvider),
    syncService: ref.watch(syncServiceProvider),
  );
});

// ── Client state notifier ────────────────────────────────────────────────────

@riverpod
class ClientListNotifier extends _$ClientListNotifier {
  @override
  Future<List<ClientModel>> build() async {
    return ref.watch(clientRepositoryProvider).getAll();
  }

  Future<void> refresh({bool includeArchived = false}) async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(
        () => ref.read(clientRepositoryProvider).getAll(includeArchived: includeArchived));
  }

  Future<void> search(String query) async {
    if (query.isEmpty) {
      await refresh();
      return;
    }
    state = const AsyncLoading();
    state = await AsyncValue.guard(
        () => ref.read(clientRepositoryProvider).search(query));
  }

  Future<ClientModel> create({
    required String name,
    required String phone,
    String? email,
    String? notes,
  }) async {
    final result = await ref.read(clientRepositoryProvider).create(
          name: name,
          phone: phone,
          email: email,
          notes: notes,
        );
    await refresh();
    return result;
  }

  Future<ClientModel> patchClient({
    required String id,
    String? name,
    String? phone,
    String? email,
    String? notes,
  }) async {
    final result = await ref.read(clientRepositoryProvider).update(
          id: id,
          name: name,
          phone: phone,
          email: email,
          notes: notes,
        );
    await refresh();
    return result;
  }

  Future<void> archive(String id) async {
    await ref.read(clientRepositoryProvider).archive(id);
    await refresh();
  }

  Future<void> syncFromRemote() async {
    await ref.read(clientRepositoryProvider).syncFromRemote();
    await refresh();
  }
}

// ── Supplier state notifier ──────────────────────────────────────────────────

@riverpod
class SupplierListNotifier extends _$SupplierListNotifier {
  @override
  Future<List<SupplierModel>> build() async {
    return ref.watch(supplierRepositoryProvider).getAll();
  }

  Future<void> refresh({bool includeArchived = false}) async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(
        () => ref.read(supplierRepositoryProvider).getAll(includeArchived: includeArchived));
  }

  Future<void> search(String query) async {
    if (query.isEmpty) {
      await refresh();
      return;
    }
    state = const AsyncLoading();
    state = await AsyncValue.guard(
        () => ref.read(supplierRepositoryProvider).search(query));
  }

  Future<SupplierModel> create({
    required String name,
    required String phone,
    String? email,
    List<String> productIds = const [],
  }) async {
    final result = await ref.read(supplierRepositoryProvider).create(
          name: name,
          phone: phone,
          email: email,
          productIds: productIds,
        );
    await refresh();
    return result;
  }

  Future<SupplierModel> patchSupplier({
    required String id,
    String? name,
    String? phone,
    String? email,
    List<String>? productIds,
  }) async {
    final result = await ref.read(supplierRepositoryProvider).update(
          id: id,
          name: name,
          phone: phone,
          email: email,
          productIds: productIds,
        );
    await refresh();
    return result;
  }

  Future<void> archive(String id) async {
    await ref.read(supplierRepositoryProvider).archive(id);
    await refresh();
  }

  Future<void> syncFromRemote() async {
    await ref.read(supplierRepositoryProvider).syncFromRemote();
    await refresh();
  }
}

// ── Product-supplier lookup ──────────────────────────────────────────────────

/// Fetches the supplier linked to a product via GET /api/v1/products/{id}/supplier.
/// Returns null when no supplier is linked or the product is not found.
@riverpod
Future<SupplierModel?> productSupplier(
  ProductSupplierRef ref,
  String productId,
) async {
  final remote = ref.watch(remoteSupplierDataSourceProvider);
  return remote.getByProductId(productId);
}
