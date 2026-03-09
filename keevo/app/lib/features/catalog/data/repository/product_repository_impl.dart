import '../../domain/model/product_model.dart';
import '../../domain/model/product_status.dart';
import '../../domain/repository/product_repository.dart';
import '../datasource/local_product_datasource.dart';
import '../datasource/remote_product_datasource.dart';

/// ProductRepositoryImpl — offline-first Strategy implementation.
///
/// ALL reads come from local Drift DB — no network required.
/// Writes touch local DB first + enqueue sync; remote is synced lazily
/// via [syncFromRemote] called by the background SyncService.
class ProductRepositoryImpl implements ProductRepository {
  final LocalProductDataSource _local;
  final RemoteProductDataSource _remote;

  const ProductRepositoryImpl({
    required LocalProductDataSource local,
    required RemoteProductDataSource remote,
  })  : _local = local,
        _remote = remote;

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
    String? photoUrl,
  }) =>
      _local.insert(
        name: name,
        description: description,
        sku: sku,
        categoryId: categoryId,
        price: price,
        buyPrice: buyPrice,
        photoUrl: photoUrl,
      );

  @override
  Future<ProductModel> update({
    required String id,
    String? name,
    String? description,
    String? sku,
    String? categoryId,
    int? price,
    int? buyPrice,
    String? photoUrl,
  }) =>
      _local.updateById(
        id: id,
        name: name,
        description: description,
        sku: sku,
        categoryId: categoryId,
        price: price,
        buyPrice: buyPrice,
        photoUrl: photoUrl,
      );

  @override
  Future<void> archive(String id) => _local.archiveById(id);

  @override
  Future<void> unarchive(String id) => _local.unarchiveById(id);

  @override
  Future<void> syncFromRemote() async {
    final dtos = await _remote.getAll();
    for (final dto in dtos) {
      await _local.upsert(ProductModel(
        id: dto.id,
        name: dto.name,
        description: dto.description,
        sku: dto.sku,
        categoryId: dto.categoryId,
        price: dto.price,
        buyPrice: dto.buyPrice,
        stockQuantity: dto.stockQuantity,
        photoUrl: dto.photoUrl,
        archived: dto.archived,
        status: ProductStatus.fromString(dto.status),
        createdAt: DateTime.parse(dto.createdAt),
        updatedAt: DateTime.parse(dto.updatedAt),
      ));
    }
  }
}
