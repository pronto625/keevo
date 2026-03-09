import 'package:drift/drift.dart';
import 'package:uuid/uuid.dart';
import 'dart:developer' as dev;

import '../../../../core/storage/app_database.dart';
import '../../../../core/sync/sync_service.dart';
import '../../domain/model/product_model.dart';
import '../../domain/model/product_status.dart';

/// LocalProductDataSource — Drift-backed local product store.
///
/// Offline-first: all reads come from here. Writes go here first,
/// then are mirrored to [SyncQueue] for later push to backend.
class LocalProductDataSource {
  final AppDatabase _db;
  final SyncService _syncService;

  LocalProductDataSource(this._db, this._syncService);

  static const _uuid = Uuid();

  // ── Read operations ─────────────────────────────────────────────────────

  Future<List<ProductModel>> getAll() async {
    final rows = await (_db.select(_db.products)
          ..where((p) => p.archived.equals(false))
          ..orderBy([(p) => OrderingTerm.asc(p.name)]))
        .get();
    return rows.map(_toModel).toList();
  }

  Future<List<ProductModel>> getArchived() async {
    final rows = await (_db.select(_db.products)
          ..where((p) => p.archived.equals(true))
          ..orderBy([(p) => OrderingTerm.asc(p.name)]))
        .get();
    return rows.map(_toModel).toList();
  }

  Future<ProductModel?> getById(String id) async {
    final row = await (_db.select(_db.products)
          ..where((p) => p.id.equals(id)))
        .getSingleOrNull();
    return row != null ? _toModel(row) : null;
  }

  Future<List<ProductModel>> search(String query) async {
    final q = '%${query.toLowerCase()}%';
    final rows = await (_db.select(_db.products)
          ..where(
            (p) =>
                p.archived.equals(false) &
                (p.name.lower().like(q) |
                    p.sku.lower().like(q)),
          )
          ..orderBy([(p) => OrderingTerm.asc(p.name)]))
        .get();
    return rows.map(_toModel).toList();
  }

  // ── Write operations ─────────────────────────────────────────────────────

  Future<ProductModel> insert({
    required String name,
    String? description,
    String? sku,
    String? categoryId,
    int price = 0,
    int buyPrice = 0,
    String? photoUrl,
  }) async {
    final id = _uuid.v4();
    final now = DateTime.now();
    final effectiveSku = (sku != null && sku.isNotEmpty)
        ? sku
        : 'KEV-${_uuid.v4().replaceAll('-', '').substring(0, 6).toUpperCase()}';

    final companion = ProductsCompanion.insert(
      id: id,
      name: name,
      description: Value(description),
      sku: Value(effectiveSku),
      categoryId: Value(categoryId),
      price: Value(price),
      buyPrice: Value(buyPrice),
      photoUrl: Value(photoUrl),
      archived: const Value(false),
      status: const Value('ACTIVE'),
      createdAt: now,
      updatedAt: now,
    );
    await _db.into(_db.products).insert(companion);

    // Enqueue sync operation.
    await _enqueueSync('CREATE_PRODUCT', {
      'id': id,
      'name': name,
      'description': description,
      'sku': effectiveSku,
      'categoryId': categoryId,
      'price': price,
      'buyPrice': buyPrice,
      'photoUrl': photoUrl,
    });

    return _toModel(await (_db.select(_db.products)
          ..where((p) => p.id.equals(id)))
        .getSingle());
  }

  Future<ProductModel> updateById({
    required String id,
    String? name,
    String? description,
    String? sku,
    String? categoryId,
    int? price,
    int? buyPrice,
    String? photoUrl,
  }) async {
    final now = DateTime.now();
    await (_db.update(_db.products)
          ..where((p) => p.id.equals(id)))
        .write(ProductsCompanion(
      name: name != null ? Value(name) : const Value.absent(),
      description:
          description != null ? Value(description) : const Value.absent(),
      sku: sku != null ? Value(sku) : const Value.absent(),
      categoryId:
          categoryId != null ? Value(categoryId) : const Value.absent(),
      price: price != null ? Value(price) : const Value.absent(),
      buyPrice: buyPrice != null ? Value(buyPrice) : const Value.absent(),
      photoUrl: photoUrl != null ? Value(photoUrl) : const Value.absent(),
      updatedAt: Value(now),
    ));

    await _enqueueSync('UPDATE_PRODUCT', {
      'id': id,
      if (name != null) 'name': name,
      if (description != null) 'description': description,
      if (sku != null) 'sku': sku,
      if (categoryId != null) 'categoryId': categoryId,
      if (price != null) 'price': price,
      if (buyPrice != null) 'buyPrice': buyPrice,
      if (photoUrl != null) 'photoUrl': photoUrl,
    });

    return _toModel(await (_db.select(_db.products)
          ..where((p) => p.id.equals(id)))
        .getSingle());
  }

  Future<void> archiveById(String id) async {
    final now = DateTime.now();
    await (_db.update(_db.products)
          ..where((p) => p.id.equals(id)))
        .write(ProductsCompanion(
      archived: const Value(true),
      updatedAt: Value(now),
    ));
    await _enqueueSync('ARCHIVE_PRODUCT', {'productId': id});
  }

  /// Unarchive a product (restore to active state).
  Future<void> unarchiveById(String id) async {
    final now = DateTime.now();
    await (_db.update(_db.products)
          ..where((p) => p.id.equals(id)))
        .write(ProductsCompanion(
      archived: const Value(false),
      updatedAt: Value(now),
    ));
    await _enqueueSync('UNARCHIVE_PRODUCT', {'productId': id});
  }

  /// Upsert a product synced from backend.
  Future<void> upsert(ProductModel model) async {
    await _db.into(_db.products).insertOnConflictUpdate(ProductsCompanion(
      id: Value(model.id),
      name: Value(model.name),
      description: Value(model.description),
      sku: Value(model.sku),
      categoryId: Value(model.categoryId),
      price: Value(model.price),
      buyPrice: Value(model.buyPrice),
      stockQuantity: Value(model.stockQuantity),
      storeId: Value(model.storeId),
      photoUrl: Value(model.photoUrl),
      archived: Value(model.archived),
      status: Value(model.status.value),
      createdAt: Value(model.createdAt),
      updatedAt: Value(model.updatedAt),
    ));
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  Future<void> _enqueueSync(
    String operation,
    Map<String, dynamic> payload,
  ) async {
    dev.log('📤 LocalProductDataSource enqueueing: $operation', name: 'ProductSync');
    await _syncService.queueOperation(
      operation: operation,
      payload: payload,
    );
  }

  ProductModel _toModel(Product row) => ProductModel(
        id: row.id,
        name: row.name,
        description: row.description,
        sku: row.sku,
        categoryId: row.categoryId,
        price: row.price,
        buyPrice: row.buyPrice,
        stockQuantity: row.stockQuantity,
        storeId: row.storeId,
        photoUrl: row.photoUrl,
        archived: row.archived,
        status: ProductStatus.fromString(row.status),
        createdAt: row.createdAt,
        updatedAt: row.updatedAt,
      );
}
