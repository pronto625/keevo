import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/sync/sync_gate_guard.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/local_product_datasource.dart';
import '../../data/datasource/remote_csv_import_datasource.dart';
import '../../data/datasource/remote_product_datasource.dart';
import '../../data/repository/product_repository_impl.dart';
import '../../domain/model/product_model.dart';
import '../../domain/model/product_status.dart';
import '../../domain/repository/product_repository.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';
import '../../domain/usecase/archive_product_usecase.dart';
import '../../domain/usecase/create_product_usecase.dart';
import '../../domain/usecase/get_products_usecase.dart';
import '../../domain/usecase/unarchive_product_usecase.dart';
import '../../domain/usecase/update_product_usecase.dart';

part 'product_provider.g.dart';

// ── Infrastructure providers ──────────────────────────────────────────────────

/// Local product datasource — reads/writes Drift Products table.
final localProductDataSourceProvider = Provider<LocalProductDataSource>((ref) {
  final db = ref.watch(appDatabaseProvider);
  final syncService = ref.watch(syncServiceProvider);
  return LocalProductDataSource(db, syncService);
});

/// Remote product datasource — calls authenticated backend API.
final remoteProductDataSourceProvider = Provider<RemoteProductDataSource>((ref) {
  final dio = ref.watch(dioProvider);
  return RemoteProductDataSource(dio: dio);
});

/// Remote CSV import datasource — CSV upload, template, draft creation.
final remoteCsvImportDataSourceProvider =
    Provider<RemoteCsvImportDataSource>((ref) {
  final dio = ref.watch(dioProvider);
  return RemoteCsvImportDataSource(dio: dio);
});

/// ProductRepository — offline-first concrete implementation.
final productRepositoryProvider = Provider<ProductRepository>((ref) {
  return ProductRepositoryImpl(
    local: ref.watch(localProductDataSourceProvider),
    remote: ref.watch(remoteProductDataSourceProvider),
    remoteCsv: ref.watch(remoteCsvImportDataSourceProvider),
    connectivity: ref.watch(connectivityServiceProvider),
    syncService: ref.watch(syncServiceProvider),
  );
});

// ── Use case providers ────────────────────────────────────────────────────────

final getProductsUseCaseProvider = Provider<GetProductsUseCase>((ref) {
  return GetProductsUseCase(ref.watch(productRepositoryProvider));
});

final createProductUseCaseProvider = Provider<CreateProductUseCase>((ref) {
  return CreateProductUseCase(ref.watch(productRepositoryProvider));
});

final updateProductUseCaseProvider = Provider<UpdateProductUseCase>((ref) {
  return UpdateProductUseCase(ref.watch(productRepositoryProvider));
});

final archiveProductUseCaseProvider = Provider<ArchiveProductUseCase>((ref) {
  return ArchiveProductUseCase(ref.watch(productRepositoryProvider));
});

final unarchiveProductUseCaseProvider = Provider<UnarchiveProductUseCase>((ref) {
  return UnarchiveProductUseCase(ref.watch(productRepositoryProvider));
});

// ── Feature state notifier ────────────────────────────────────────────────────

/// Holds the current search query — updated by the search bar.
final productSearchQueryProvider = StateProvider<String>((ref) => '');

/// When true, the active products tab shows only DRAFT products.
final showDraftsOnlyProvider = StateProvider<bool>((ref) => false);

/// When true, the active products tab shows only products with low stock.
final showLowStockOnlyProvider = StateProvider<bool>((ref) => false);

/// Product list state — reactive to the search query.
///
/// AC7: re-executes on every [productSearchQueryProvider] change.
/// Offline-first: reads from local Drift DB, no network required.
@riverpod
Future<List<ProductModel>> productList(ProductListRef ref) async {
  final query = ref.watch(productSearchQueryProvider);
  final activeStoreId = ref.watch(activeStoreIdProvider);
  final useCase = ref.watch(getProductsUseCaseProvider);
  final local = ref.watch(localProductDataSourceProvider);
  var products = await useCase.execute(query: query.isEmpty ? null : query);
  if (activeStoreId != null) {
    final storeProductIds = await local.getProductIdsInStore(activeStoreId);
    final productsWithStock = await local.getProductIdsWithStock();
    // Include: products with stock in this store, DRAFTs (never have stock),
    // and brand-new ACTIVE products with no stock anywhere (just promoted).
    products = products
        .where((p) =>
            storeProductIds.contains(p.id) ||
            p.status == ProductStatus.draft ||
            !productsWithStock.contains(p.id))
        .toList();
  }
  final draftsOnly = ref.watch(showDraftsOnlyProvider);
  if (draftsOnly) {
    products = products.where((p) => p.status == ProductStatus.draft).toList();
  }
  final lowStockOnly = ref.watch(showLowStockOnlyProvider);
  if (lowStockOnly) {
    final lowStockIds = await local.getLowStockProductIds();
    products = products.where((p) => lowStockIds.contains(p.id)).toList();
  }

  // Sort: products with actual stock > 0 first (from stock_levels, not the stale
  // denormalized stockQuantity field), then by updatedAt descending.
  final stockMap = activeStoreId != null
      ? await local.getStockByStore(activeStoreId)
      : await local.getTotalStock();
  products.sort((a, b) {
    final aHasStock = (stockMap[a.id] ?? 0) > 0 ? 0 : 1;
    final bHasStock = (stockMap[b.id] ?? 0) > 0 ? 0 : 1;
    if (aHasStock != bHasStock) return aHasStock.compareTo(bHasStock);
    return b.updatedAt.compareTo(a.updatedAt);
  });

  // Guard against duplicate names introduced by sync (e.g. local draft + remote
  // active for the same product).  After sorting, the "best" entry (most stock,
  // then most recent) is already first; keep it and discard later duplicates.
  final seenNames = <String>{};
  products = products.where((p) => seenNames.add(p.name.toLowerCase())).toList();

  return products;
}

/// Sync-first product list — for pickers shown before the catalog page is visited.
///
/// Always pulls from remote before returning local data, ensuring the picker
/// is populated even if the catalog page has never been opened.
@riverpod
Future<List<ProductModel>> productListForPicker(ProductListForPickerRef ref) async {
  final repo = ref.watch(productRepositoryProvider);
  await repo.syncFromRemote();
  final useCase = ref.watch(getProductsUseCaseProvider);
  return useCase.execute();
}

/// Archived products list.

/// Pending DRAFT products count — drives AC10 nav badge and banner.
///
/// Queries the local Drift DB directly. Re-evaluates when
/// [productListProvider] is invalidated (e.g., after sync or promotion).
final pendingDraftsCountProvider = FutureProvider.autoDispose<int>((ref) async {
  final local = ref.watch(localProductDataSourceProvider);
  // Re-run this provider when the product list changes.
  await ref
      .watch(productListProvider.future)
      .catchError((_) => <ProductModel>[]);
  return local.countDrafts();
});
@riverpod
Future<List<ProductModel>> archivedProductList(ArchivedProductListRef ref) async {
  final useCase = ref.watch(getProductsUseCaseProvider);
  return useCase.executeArchived();
}

/// Products with zero stock (active only, excludes drafts).
/// Applies the same active-store filter as [productListProvider].
@riverpod
Future<List<ProductModel>> outOfStockProductList(OutOfStockProductListRef ref) async {
  final useCase = ref.watch(getProductsUseCaseProvider);
  final local = ref.watch(localProductDataSourceProvider);
  final activeStoreId = ref.watch(activeStoreIdProvider);
  final query = ref.watch(productSearchQueryProvider);
  var products = await useCase.execute(query: query.isEmpty ? null : query);
  products = products
      .where((p) => !p.archived && p.status != ProductStatus.draft)
      .toList();

  if (activeStoreId != null) {
    final storeProductIds = await local.getProductIdsInStore(activeStoreId);
    final productsWithStock = await local.getProductIdsWithStock();
    final storeStock = await local.getStockByStore(activeStoreId);
    // Same inclusion rule as productList, then keep only those with zero stock
    // in the active store (using real stock_levels, not the stale product field).
    products = products
        .where((p) =>
            storeProductIds.contains(p.id) ||
            !productsWithStock.contains(p.id))
        .where((p) => (storeStock[p.id] ?? 0) == 0)
        .toList();
  } else {
    final totalStock = await local.getTotalStock();
    products = products.where((p) => (totalStock[p.id] ?? 0) == 0).toList();
  }

  return products..sort((a, b) => b.updatedAt.compareTo(a.updatedAt));
}

/// Products with low stock (at or below threshold, active only).
/// Applies the same active-store filter as [productListProvider].
@riverpod
Future<List<ProductModel>> lowStockProductList(LowStockProductListRef ref) async {
  final local = ref.watch(localProductDataSourceProvider);
  final useCase = ref.watch(getProductsUseCaseProvider);
  final activeStoreId = ref.watch(activeStoreIdProvider);
  final query = ref.watch(productSearchQueryProvider);
  var products = await useCase.execute(query: query.isEmpty ? null : query);
  products = products.where((p) => !p.archived && p.status != ProductStatus.draft).toList();

  if (activeStoreId != null) {
    final storeProductIds = await local.getProductIdsInStore(activeStoreId);
    final productsWithStock = await local.getProductIdsWithStock();
    products = products
        .where((p) =>
            storeProductIds.contains(p.id) ||
            !productsWithStock.contains(p.id))
        .toList();
  }

  final lowStockIds = await local.getLowStockProductIds();
  final stockMap = activeStoreId != null
      ? await local.getStockByStore(activeStoreId)
      : await local.getTotalStock();
  return products
      .where((p) => lowStockIds.contains(p.id))
      .toList()
    ..sort((a, b) =>
        (stockMap[a.id] ?? 0).compareTo(stockMap[b.id] ?? 0)); // worst first
}

/// Full product list notifier — handles create/update/archive with invalidation.
///
/// Usage:
/// ```dart
/// ref.read(productActionsProvider).create(name: 'T-Shirt');
/// ```
class ProductActions {
  final Ref _ref;

  const ProductActions(this._ref);

  ProductRepository get _repo => _ref.read(productRepositoryProvider);

  Future<ProductModel> create({
    required String name,
    String? description,
    String? sku,
    String? categoryId,
    int price = 0,
    int buyPrice = 0,
    int transportCost = 0,
    String? photoUrl,
  }) async {
    SyncGateGuard.assertWriteAllowed(_ref);
    final useCase = _ref.read(createProductUseCaseProvider);
    final result = await useCase.execute(
      name: name,
      description: description,
      sku: sku,
      categoryId: categoryId,
      price: price,
      buyPrice: buyPrice,
      transportCost: transportCost,
      photoUrl: photoUrl,
    );
    // Invalidate all product lists so every tab refreshes.
    _ref.invalidate(productListProvider);
    _ref.invalidate(archivedProductListProvider);
    _ref.invalidate(outOfStockProductListProvider);
    _ref.invalidate(lowStockProductListProvider);
    return result;
  }

  Future<ProductModel> update({
    required String id,
    String? name,
    String? description,
    String? sku,
    String? categoryId,
    int? price,
    int? buyPrice,
    int? transportCost,
    String? photoUrl,
  }) async {
    SyncGateGuard.assertWriteAllowed(_ref);
    final useCase = _ref.read(updateProductUseCaseProvider);
    final result = await useCase.execute(
      id: id,
      name: name,
      description: description,
      sku: sku,
      categoryId: categoryId,
      price: price,
      buyPrice: buyPrice,
      transportCost: transportCost,
      photoUrl: photoUrl,
    );
    _ref.invalidate(productListProvider);
    _ref.invalidate(archivedProductListProvider);
    _ref.invalidate(outOfStockProductListProvider);
    _ref.invalidate(lowStockProductListProvider);
    return result;
  }

  /// Promote a DRAFT product to ACTIVE (OWNER-only).
  /// Returns the final product ID (may differ if backend assigned a new ID).
  Future<String> promoteToActive(String id) async {
    final newId = await _repo.promoteToActive(id);
    _ref.invalidate(productListProvider);
    _ref.invalidate(archivedProductListProvider);
    _ref.invalidate(pendingDraftsCountProvider);
    return newId;
  }

  Future<void> archive(String id) async {
    SyncGateGuard.assertWriteAllowed(_ref);
    final useCase = _ref.read(archiveProductUseCaseProvider);
    await useCase.execute(id);
    _ref.invalidate(productListProvider);
    _ref.invalidate(archivedProductListProvider);
    _ref.invalidate(outOfStockProductListProvider);
    _ref.invalidate(lowStockProductListProvider);
  }

  Future<void> unarchive(String id) async {
    SyncGateGuard.assertWriteAllowed(_ref);
    final useCase = _ref.read(unarchiveProductUseCaseProvider);
    await useCase.execute(id);
    _ref.invalidate(productListProvider);
    _ref.invalidate(archivedProductListProvider);
    _ref.invalidate(outOfStockProductListProvider);
    _ref.invalidate(lowStockProductListProvider);
  }

  Future<void> syncFromRemote() async {
    await _repo.syncFromRemote();
    _ref.invalidate(productListProvider);
    _ref.invalidate(archivedProductListProvider);
    _ref.invalidate(outOfStockProductListProvider);
    _ref.invalidate(lowStockProductListProvider);
  }
}

final productActionsProvider = Provider<ProductActions>((ref) {
  return ProductActions(ref);
});
