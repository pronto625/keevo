import 'dart:typed_data';

import '../../domain/exception/product_exception.dart';
import '../../domain/model/csv_import_result.dart';
import '../../domain/model/product_model.dart';
import '../../domain/model/product_response_dto.dart';
import '../../domain/model/product_status.dart';
import '../../domain/repository/product_repository.dart';
import '../../../../core/sync/connectivity_service.dart';
import '../../../../core/sync/sync_service.dart';
import '../datasource/local_product_datasource.dart';
import '../datasource/remote_csv_import_datasource.dart';
import '../datasource/remote_product_datasource.dart';

/// ProductRepositoryImpl — Backend-first write-through Strategy implementation.
///
/// ALL reads come from local Drift DB — no network required.
/// Writes go to the backend first (source of truth) when online,
/// fall back to local + sync_queue when offline.
class ProductRepositoryImpl implements ProductRepository {
  final LocalProductDataSource _local;
  final RemoteProductDataSource _remote;
  final RemoteCsvImportDataSource _remoteCsv;
  final ConnectivityService _connectivity;
  final SyncService _syncService;

  const ProductRepositoryImpl({
    required LocalProductDataSource local,
    required RemoteProductDataSource remote,
    required RemoteCsvImportDataSource remoteCsv,
    required ConnectivityService connectivity,
    required SyncService syncService,
  })  : _local = local,
        _remote = remote,
        _remoteCsv = remoteCsv,
        _connectivity = connectivity,
        _syncService = syncService;

  @override
  Future<List<ProductModel>> getAll() => _local.getAll();

  @override
  Future<List<ProductModel>> getArchived() => _local.getArchived();

  @override
  Future<ProductModel?> getById(String id) => _local.getById(id);

  @override
  Future<List<ProductModel>> search(String query) => _local.search(query);

  @override
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
    // Remote first: backend assigns canonical UUID and SKU.
    // On success, upsert locally with the backend-assigned ID so that
    // syncFromRemote() won't create a duplicate on next launch.

    // Check local name uniqueness first
    final existing = await _local.search(name);
    final duplicate = existing.any(
      (p) => p.name.toLowerCase() == name.toLowerCase(),
    );
    if (duplicate) {
      throw const ProductException(
        domainCode: 'PRODUCT_NAME_ALREADY_EXISTS',
        message: 'Un produit avec ce nom existe déjà',
        statusCode: 409,
      );
    }

    // Separate the remote call from the local upsert so that a DB failure
    // after a successful remote create never triggers the offline fallback
    // and never creates a second ghost product in local DB.
    late ProductResponseDto dto;
    try {
      dto = await _remote.create({
        'name': name,
        if (description != null) 'description': description,
        if (sku != null && sku.isNotEmpty) 'sku': sku,
        if (categoryId != null) 'categoryId': categoryId,
        'price': price,
        'buyPrice': buyPrice,
        'transportCost': transportCost,
      });
    } on ProductException {
      // Business/validation error from backend — re-throw so the UI can display it.
      rethrow;
    } catch (_) {
      // Network unavailable — offline fallback: store locally and enqueue sync.
      return _local.insert(
        name: name,
        description: description,
        sku: sku,
        categoryId: categoryId,
        price: price,
        buyPrice: buyPrice,
        transportCost: transportCost,
        photoUrl: photoUrl,
      );
    }
    // Remote create succeeded — persist locally with the backend-assigned ID.
    // Any DB error here propagates to the caller (no silent data duplication).
    final model = _dtoToModel(dto).copyWith(photoUrl: photoUrl);
    await _local.upsert(model);
    return model;
  }

  @override
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
    final payload = <String, dynamic>{
      if (name != null) 'name': name,
      if (description != null) 'description': description,
      if (sku != null) 'sku': sku,
      if (categoryId != null) 'categoryId': categoryId,
      if (price != null) 'price': price,
      if (buyPrice != null) 'buyPrice': buyPrice,
      if (transportCost != null) 'transportCost': transportCost,
      if (photoUrl != null) 'photoUrl': photoUrl,
    };

    if (await _connectivity.isOnline()) {
      try {
        if (payload.isNotEmpty) {
          final dto = await _remote.update(id, payload);
          final model = _dtoToModel(dto).copyWith(photoUrl: photoUrl);
          await _local.upsert(model);
          return model;
        }
        return (await _local.getById(id))!;
      } catch (_) {
        // Fallback: save local + enqueue
        final localResult = await _local.updateById(
          id: id, name: name, description: description, sku: sku,
          categoryId: categoryId, price: price, buyPrice: buyPrice,
          transportCost: transportCost, photoUrl: photoUrl,
        );
        await _syncService.queueOperation(
          operation: 'UPDATE_PRODUCT',
          payload: {'productId': id, ...payload},
          entityId: id,
        );
        return localResult;
      }
    } else {
      final localResult = await _local.updateById(
        id: id, name: name, description: description, sku: sku,
        categoryId: categoryId, price: price, buyPrice: buyPrice,
        transportCost: transportCost, photoUrl: photoUrl,
      );
      await _syncService.queueOperation(
        operation: 'UPDATE_PRODUCT',
        payload: {'productId': id, ...payload},
        entityId: id,
      );
      return localResult;
    }
  }

  @override
  Future<String> promoteToActive(String id) async {
    final product = await _local.getById(id);
    if (product == null) return id;

    if (await _connectivity.isOnline()) {
      try {
        // Backend auto-promotes DRAFT→ACTIVE on any PATCH update.
        final dto = await _remote.update(id, {'name': product.name});
        final model = _dtoToModel(dto);
        await _local.upsert(model);
        return model.id;
      } catch (_) {
        // PATCH failed — product may only exist locally (offline draft).
        // Try creating it as a full (ACTIVE) product on the backend.
        try {
          final dto = await _remote.create({
            'name': product.name,
            if (product.description != null) 'description': product.description,
            if (product.categoryId != null) 'categoryId': product.categoryId,
            'price': product.price,
            'buyPrice': product.buyPrice,
            'transportCost': product.transportCost,
          });
          final newModel = _dtoToModel(dto);
          // Remap sale_items references before deleting the old draft.
          await _local.updateProductIdInSaleItems(id, newModel.id);
          // Replace the local draft with the backend-assigned product.
          await _local.deleteById(id);
          await _local.upsert(newModel);
          return newModel.id;
        } catch (_) {
          // CREATE failed — try to find existing backend product by name.
          try {
            final remoteDtos = await _remote.getAll();
            final match = remoteDtos.cast<ProductResponseDto?>().firstWhere(
              (dto) =>
                  dto!.name.toLowerCase() == product.name.toLowerCase(),
              orElse: () => null,
            );
            if (match != null) {
              final existingModel = _dtoToModel(match);
              await _local.updateProductIdInSaleItems(id, existingModel.id);
              await _local.deleteById(id);
              await _local.upsert(existingModel);
              return existingModel.id;
            }
          } catch (_) {
            // Sync also failed.
          }
          // All remote attempts failed — local + queue.
          await _local.promoteToActive(id);
          await _syncService.queueOperation(
            operation: 'PROMOTE_PRODUCT',
            payload: _promotePayload(id, product),
            entityId: id,
          );
          return id;
        }
      }
    } else {
      // Fully offline — promote locally, sync later.
      await _local.promoteToActive(id);
      await _syncService.queueOperation(
        operation: 'PROMOTE_PRODUCT',
        payload: _promotePayload(id, product),
        entityId: id,
      );
      return id;
    }
  }

  Map<String, dynamic> _promotePayload(String id, ProductModel p) => {
        'productId': id,
        'name': p.name,
        if (p.description != null) 'description': p.description,
        if (p.categoryId != null) 'categoryId': p.categoryId,
        'price': p.price,
        'buyPrice': p.buyPrice,
        'transportCost': p.transportCost,
      };

  @override
  Future<void> archive(String id) async {
    if (await _connectivity.isOnline()) {
      try {
        await _remote.archive(id);
        await _local.archiveById(id);
      } catch (_) {
        await _local.archiveById(id);
        await _syncService.queueOperation(
          operation: 'ARCHIVE_PRODUCT',
          payload: {'productId': id},
          entityId: id,
        );
      }
    } else {
      await _local.archiveById(id);
      await _syncService.queueOperation(
        operation: 'ARCHIVE_PRODUCT',
        payload: {'productId': id},
        entityId: id,
      );
    }
  }

  @override
  Future<void> unarchive(String id) async {
    if (await _connectivity.isOnline()) {
      try {
        await _remote.unarchive(id);
        await _local.unarchiveById(id);
      } catch (_) {
        await _local.unarchiveById(id);
        await _syncService.queueOperation(
          operation: 'UNARCHIVE_PRODUCT',
          payload: {'productId': id},
          entityId: id,
        );
      }
    } else {
      await _local.unarchiveById(id);
      await _syncService.queueOperation(
        operation: 'UNARCHIVE_PRODUCT',
        payload: {'productId': id},
        entityId: id,
      );
    }
  }

  @override
  Future<void> syncFromRemote() async {
    // Skip remote pull if sync_queue has pending operations — avoids
    // overwriting local offline changes with stale backend data.
    if (await _syncService.hasPendingOperations()) return;
    final dtos = await _remote.getAll();

    // Archive local ACTIVE products not present on the backend.
    // This covers two scenarios:
    //   1. The owner archived a product on their device — remote no longer
    //      returns it, so we mirror that archivation locally.
    //   2. A ghost "offline-fallback" copy was created by the previous
    //      create() bug (local UUID ≠ backend UUID, same name). Now that the
    //      real product has been synced, the ghost is no longer needed.
    // DRAFT products are intentionally skipped — they await owner promotion
    // and are never returned by the backend's getAll().
    final backendIds = dtos.map((d) => d.id).toSet();
    final localProducts = await _local.getAll();
    for (final local in localProducts) {
      if (local.status != ProductStatus.draft &&
          !backendIds.contains(local.id)) {
        await _local.archiveById(local.id);
      }
    }

    for (final dto in dtos) {
      await _local.upsert(_dtoToModel(dto));
    }
  }

  @override
  Future<CsvImportResult> importCsv({
    required Uint8List csvBytes,
    required String fileName,
    required Map<String, String> columnMapping,
  }) async {
    // Import requires connectivity — no offline fallback for bulk import.
    final result = await _remoteCsv.importCsv(
      csvBytes: csvBytes,
      fileName: fileName,
      columnMapping: columnMapping,
    );
    // Pull newly imported products into local cache.
    await syncFromRemote();
    return result;
  }

  @override
  Future<Uint8List> downloadCsvTemplate() =>
      _remoteCsv.downloadCsvTemplate();

  @override
  Future<ProductModel> createDraft({
    required String name,
    required int priceVente,
    required String categoryId,
    int stockQuantity = 0,
  }) async {
    // Check local name uniqueness before hitting the backend
    final existing = await _local.search(name);
    final duplicate = existing.any(
      (p) => p.name.toLowerCase() == name.toLowerCase(),
    );
    if (duplicate) {
      throw const ProductException(
        domainCode: 'PRODUCT_NAME_ALREADY_EXISTS',
        message: 'Un produit avec ce nom existe déjà',
        statusCode: 409,
      );
    }

    try {
      final dto = await _remoteCsv.createDraft({
        'name': name,
        'price': priceVente,
        'categoryId': categoryId,
        if (stockQuantity > 0) 'stockQuantity': stockQuantity,
      });
      final model = _dtoToModel(dto);
      await _local.upsert(model);
      return model;
    } catch (e) {
      // Only rethrow server-side errors (4xx/5xx with HTTP response).
      // When DioException has no response (connection refused, timeout, SSL),
      // _mapError() sets statusCode: null — we fall through to local draft
      // creation so the POS keeps working offline.
      if (e is ProductException && e.statusCode != null) rethrow;
      // Offline fallback: write locally as DRAFT, sync queue will push later.
      return _local.insertDraft(
        name: name,
        priceVente: priceVente,
        categoryId: categoryId,
        stockQuantity: stockQuantity,
      );
    }
  }

  @override
  Future<int> countDrafts() => _local.countDrafts();

  ProductModel _dtoToModel(ProductResponseDto dto) => ProductModel(
        id: dto.id,
        name: dto.name,
        description: dto.description,
        sku: dto.sku,
        categoryId: dto.categoryId,
        price: dto.price,
        buyPrice: dto.buyPrice,
        transportCost: dto.transportCost,
        stockQuantity: dto.stockQuantity,
        photoUrl: dto.photoUrl,
        archived: dto.archived,
        status: ProductStatus.fromString(dto.status),
        createdAt: DateTime.parse(dto.createdAt),
        updatedAt: DateTime.parse(dto.updatedAt),
      );
}
