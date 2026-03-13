import 'dart:typed_data';

import '../../domain/model/csv_import_result.dart';
import '../../domain/model/product_model.dart';
import '../../domain/model/product_response_dto.dart';
import '../../domain/model/product_status.dart';
import '../../domain/repository/product_repository.dart';
import '../datasource/local_product_datasource.dart';
import '../datasource/remote_csv_import_datasource.dart';
import '../datasource/remote_product_datasource.dart';

/// ProductRepositoryImpl — write-through Strategy implementation.
///
/// ALL reads come from local Drift DB — no network required.
/// Writes go to the backend first (source of truth), then update
/// the local cache so the UI reflects the persisted state.
/// This prevents data loss on app reinstall until Epic 5 sync engine
/// is implemented.
class ProductRepositoryImpl implements ProductRepository {
  final LocalProductDataSource _local;
  final RemoteProductDataSource _remote;
  final RemoteCsvImportDataSource _remoteCsv;

  const ProductRepositoryImpl({
    required LocalProductDataSource local,
    required RemoteProductDataSource remote,
    required RemoteCsvImportDataSource remoteCsv,
  })  : _local = local,
        _remote = remote,
        _remoteCsv = remoteCsv;

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
    try {
      final dto = await _remote.create({
        'name': name,
        if (description != null) 'description': description,
        if (sku != null && sku.isNotEmpty) 'sku': sku,
        if (categoryId != null) 'categoryId': categoryId,
        'price': price,
        'buyPrice': buyPrice,
        'transportCost': transportCost,
      });
      final model = _dtoToModel(dto);
      await _local.upsert(model);
      return model;
    } catch (_) {
      // Offline fallback: write local only (sync engine will push later).
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
    // Local first for immediate UI feedback, then push to backend.
    final localResult = await _local.updateById(
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
    try {
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
      if (payload.isNotEmpty) await _remote.update(id, payload);
    } catch (_) {
      // Offline — local update stays; sync engine (Epic 5) will push later.
    }
    return localResult;
  }

  @override
  Future<void> archive(String id) async {
    await _local.archiveById(id);
    try {
      await _remote.archive(id);
    } catch (_) {
      // Offline fallback — sync engine will push later.
    }
  }

  @override
  Future<void> unarchive(String id) async {
    await _local.unarchiveById(id);
    try {
      await _remote.unarchive(id);
    } catch (_) {
      // Offline fallback — sync engine will push later.
    }
  }

  @override
  Future<void> syncFromRemote() async {
    final dtos = await _remote.getAll();
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
    try {
      final dto = await _remoteCsv.createDraft({
        'name': name,
        'priceVente': priceVente,
        'categoryId': categoryId,
        if (stockQuantity > 0) 'stockQuantity': stockQuantity,
      });
      final model = _dtoToModel(dto);
      await _local.upsert(model);
      return model;
    } catch (_) {
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
