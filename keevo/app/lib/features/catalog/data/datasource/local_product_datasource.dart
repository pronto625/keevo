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

  /// Returns the set of product IDs that have a stock level entry for [storeId].
  /// Used to filter the catalogue when an active store is selected.
  Future<Set<String>> getProductIdsInStore(String storeId) async {
    const sql =
        'SELECT DISTINCT product_id FROM stock_levels WHERE store_id = ?';
    final rows = await _db
        .customSelect(sql, variables: [Variable.withString(storeId)]).get();
    return rows.map((r) => r.read<String>('product_id')).toSet();
  }

  /// Returns all product IDs that have at least one stock_levels entry (any store).
  /// Used to distinguish brand-new products (no stock yet) from products with stock.
  Future<Set<String>> getProductIdsWithStock() async {
    const sql = 'SELECT DISTINCT product_id FROM stock_levels';
    final rows = await _db.customSelect(sql).get();
    return rows.map((r) => r.read<String>('product_id')).toSet();
  }

  // ── Write operations ─────────────────────────────────────────────────────

  Future<ProductModel> insert({
    required String name,
    String? description,
    String? sku,
    String? categoryId,
    int price = 0,
    int buyPrice = 0,
    int transportCost = 0,
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
      transportCost: Value(transportCost),
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
      'transportCost': transportCost,
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
    int? transportCost,
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
      transportCost: transportCost != null ? Value(transportCost) : const Value.absent(),
      photoUrl: photoUrl != null ? Value(photoUrl) : const Value.absent(),
      updatedAt: Value(now),
    ));

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
  }

  /// Promote a DRAFT product to ACTIVE locally.
  Future<void> promoteToActive(String id) async {
    final now = DateTime.now();
    await (_db.update(_db.products)
          ..where((p) => p.id.equals(id)))
        .write(ProductsCompanion(
      status: const Value('ACTIVE'),
      updatedAt: Value(now),
    ));
  }

  /// Delete a product by ID (used when replacing a local draft with backend-assigned product).
  Future<void> deleteById(String id) async {
    await (_db.delete(_db.products)..where((p) => p.id.equals(id))).go();
  }

  /// Update product_id in sale_items when a draft is promoted to a new backend-assigned ID.
  /// Preserves the pending-sale link after ID remapping.
  /// Also patches CREATE_SALE entries in sync_queue so the backend receives the new ID.
  Future<void> updateProductIdInSaleItems(String oldId, String newId) async {
    if (oldId == newId) return;
    await _db.customUpdate(
      'UPDATE sale_items SET product_id = ? WHERE product_id = ?',
      variables: [
        Variable.withString(newId),
        Variable.withString(oldId),
      ],
      updates: {_db.saleItems},
    );
    // Patch sync_queue payloads that reference the old product ID.
    await _db.customUpdate(
      "UPDATE sync_queue SET payload = REPLACE(payload, ?, ?) "
      "WHERE operation = 'CREATE_SALE' AND payload LIKE ?",
      variables: [
        Variable.withString(oldId),
        Variable.withString(newId),
        Variable.withString('%$oldId%'),
      ],
      updates: {_db.syncQueue},
    );
  }

  /// Count DRAFT (non-archived) products in local DB.
  ///
  /// Used by [pendingDraftsCountProvider] to drive AC10 nav badge.
  Future<int> countDrafts() async {
    final countExpr = _db.products.id.count();
    final query = _db.selectOnly(_db.products)
      ..addColumns([countExpr])
      ..where(
        _db.products.status.equals('DRAFT') &
            _db.products.archived.equals(false),
      );
    final result = await query.getSingle();
    return result.read(countExpr) ?? 0;
  }

  /// Insert a product with DRAFT status for on-the-fly POS creation (AC6).
  Future<ProductModel> insertDraft({
    required String name,
    required int priceVente,
    required String categoryId,
    int stockQuantity = 0,
  }) async {
    final id = _uuid.v4();
    final now = DateTime.now();
    final effectiveSku =
        'KEV-${_uuid.v4().replaceAll('-', '').substring(0, 6).toUpperCase()}';

    final companion = ProductsCompanion.insert(
      id: id,
      name: name,
      description: const Value(null),
      sku: Value(effectiveSku),
      categoryId: Value(categoryId),
      price: Value(priceVente),
      buyPrice: const Value(0),
      transportCost: const Value(0),
      photoUrl: const Value(null),
      archived: const Value(false),
      status: const Value('DRAFT'),
      createdAt: now,
      updatedAt: now,
    );
    await _db.into(_db.products).insert(companion);

    await _enqueueSync('CREATE_DRAFT_PRODUCT', {
      'id': id,
      'name': name,
      'priceVente': priceVente,
      'categoryId': categoryId,
      if (stockQuantity > 0) 'stockQuantity': stockQuantity,
    });

    return _toModel(
      await (_db.select(_db.products)..where((p) => p.id.equals(id)))
          .getSingle(),
    );
  }

  /// Upsert a product synced from backend.
  ///
  /// Preserves local-only [photoUrl] when the incoming model has none
  /// (backend does not store product images).
  Future<void> upsert(ProductModel model) async {
    // If incoming photoUrl is null, preserve any existing local value.
    String? effectivePhotoUrl = model.photoUrl;
    if (effectivePhotoUrl == null) {
      final existing = await (_db.select(_db.products)
            ..where((p) => p.id.equals(model.id)))
          .getSingleOrNull();
      effectivePhotoUrl = existing?.photoUrl;
    }

    await _db.into(_db.products).insertOnConflictUpdate(ProductsCompanion(
      id: Value(model.id),
      name: Value(model.name),
      description: Value(model.description),
      sku: Value(model.sku),
      categoryId: Value(model.categoryId),
      price: Value(model.price),
      buyPrice: Value(model.buyPrice),
      transportCost: Value(model.transportCost),
      stockQuantity: Value(model.stockQuantity),
      storeId: Value(model.storeId),
      photoUrl: Value(effectivePhotoUrl),
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
        transportCost: row.transportCost,
        stockQuantity: row.stockQuantity,
        storeId: row.storeId,
        photoUrl: row.photoUrl,
        archived: row.archived,
        status: ProductStatus.fromString(row.status),
        createdAt: row.createdAt,
        updatedAt: row.updatedAt,
      );
}
