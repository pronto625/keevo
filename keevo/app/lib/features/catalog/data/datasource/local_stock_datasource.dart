import 'package:drift/drift.dart';

import 'package:keevo_core/storage/app_database.dart';
import '../../domain/model/cross_store_availability_model.dart';
import '../../domain/model/stock_level_model.dart';
import '../../domain/model/stock_movement_model.dart';

/// LocalStockDataSource — Drift-backed offline stock store.
///
/// Reads come from here (offline-first).
/// Writes queue to SyncQueue; remote push is handled by SyncService.
///
/// Story 2.3.
class LocalStockDataSource {
  final AppDatabase _db;

  const LocalStockDataSource(this._db);

  /// Runs [action] in one Drift transaction. Writes made through the same
  /// database inside it (sync_queue included) commit or roll back together.
  Future<T> transaction<T>(Future<T> Function() action) =>
      _db.transaction(action);

  // ── Stock Levels ─────────────────────────────────────────────────────────

  Future<List<StockLevelModel>> getLevels(String productId) async {
    final rows = await (_db.select(_db.stockLevels)
          ..where((l) => l.productId.equals(productId)))
        .get();
    final productThreshold = await getProductThreshold(productId);
    return rows
        .map((r) => _withThreshold(_levelToModel(r), productThreshold))
        .toList();
  }

  /// Product-wide low-stock threshold (server `products.minimum_threshold`),
  /// 0 when none is set or the product is unknown locally.
  Future<int> getProductThreshold(String productId) async {
    final row = await (_db.select(_db.products)
          ..where((p) => p.id.equals(productId)))
        .getSingleOrNull();
    return row?.minimumThreshold ?? 0;
  }

  /// The product-wide threshold wins over the legacy per-row one (the server
  /// only keeps the product one; the row value may be stale after a pull).
  static StockLevelModel _withThreshold(StockLevelModel level, int productThreshold) =>
      productThreshold > 0
          ? level.copyWith(minimumThreshold: productThreshold)
          : level;

  Future<StockLevelModel?> getLevelByStore(
    String productId,
    String storeId,
  ) async {
    // B1: use .get() instead of .getSingleOrNull() — duplicate rows can exist
    // when upsertLevel() is bypassed (catalog insert / sync pull races).
    // Take the row with the highest quantity as the authoritative value.
    final rows = await (_db.select(_db.stockLevels)
          ..where(
            (l) => l.productId.equals(productId) & l.storeId.equals(storeId),
          ))
        .get();
    if (rows.isEmpty) return null;
    final row = rows.reduce((a, b) => a.quantity >= b.quantity ? a : b);
    return _withThreshold(_levelToModel(row), await getProductThreshold(productId));
  }

  Future<void> upsertLevel(StockLevelModel level) async {
    // stock_levels has no UNIQUE(product_id, store_id) constraint — only PK on id.
    // Use UPDATE + fallback INSERT to avoid SQLite conflict-target errors.
    final rowsUpdated = await _db.customUpdate(
      'UPDATE stock_levels SET id = ?, variant_id = ?, quantity = ?, '
      'minimum_threshold = ?, updated_at = ? '
      'WHERE product_id = ? AND store_id = ?',
      variables: [
        Variable(level.id),
        Variable(level.variantId),
        Variable(level.quantity),
        Variable(level.minimumThreshold),
        Variable(level.updatedAt.toIso8601String()),
        Variable(level.productId),
        Variable(level.storeId),
      ],
      updates: {_db.stockLevels},
    );
    if (rowsUpdated == 0) {
      await _db.into(_db.stockLevels).insert(StockLevelsCompanion.insert(
        id: level.id,
        productId: level.productId,
        variantId: Value(level.variantId),
        storeId: level.storeId,
        quantity: level.quantity,
        minimumThreshold: Value(level.minimumThreshold),
        updatedAt: level.updatedAt,
      ));
    }
  }

  /// Sets the low-stock threshold locally, product-wide like the server
  /// (`products.minimum_threshold`): it is stored on the product, so it applies
  /// in every store — including one where the product has no stock_levels row
  /// yet — and the existing rows are aligned for older readers.
  Future<void> updateThreshold(String productId, int minimumThreshold) async {
    await _db.transaction(() async {
      await (_db.update(_db.products)..where((p) => p.id.equals(productId)))
          .write(ProductsCompanion(minimumThreshold: Value(minimumThreshold)));
      await (_db.update(_db.stockLevels)
            ..where((l) => l.productId.equals(productId)))
          .write(StockLevelsCompanion(
            minimumThreshold: Value(minimumThreshold),
            updatedAt: Value(DateTime.now()),
          ));
    });
  }

  // ── Cross-store availability (Story 3.4) ─────────────────────────────────

  /// Builds a [CrossStoreAvailabilityModel] from Drift by joining active stores
  /// with their stock level for [productId].
  ///
  /// Stores without a stock record are included with quantity = 0.
  Future<CrossStoreAvailabilityModel> getLocalAvailability(
      String productId) async {
    final stores = await (_db.select(_db.stores)
          ..where((s) => s.isActive.equals(true))
          ..orderBy([(s) => OrderingTerm.asc(s.createdAt)]))
        .get();

    final levels = await (_db.select(_db.stockLevels)
          ..where((l) => l.productId.equals(productId)))
        .get();

    final levelByStore = {
      for (final l in levels) l.storeId: l,
    };
    final productThreshold = await getProductThreshold(productId);

    final entries = stores.map((s) {
      final level = levelByStore[s.id];
      final qty = level?.quantity ?? 0;
      final minThreshold = productThreshold > 0
          ? productThreshold
          : (level?.minimumThreshold ?? 0);
      final isLow = qty > 0 && minThreshold > 0 && qty <= minThreshold;
      return CrossStoreAvailabilityEntry(
        storeId: s.id,
        storeName: s.name,
        storeType: s.type,
        quantity: qty,
        minimumThreshold: minThreshold,
        isLow: isLow,
      );
    }).toList();

    return CrossStoreAvailabilityModel(
      productId: productId,
      productName: '',
      entries: entries,
      refreshedAt: DateTime.now(),
    );
  }

  // ── Stock Movements ───────────────────────────────────────────────────────

  Future<List<StockMovementModel>> getMovements(
    String productId, {
    String? storeId,
    String? movementType,
    DateTime? from,
    DateTime? to,
    int page = 0,
    int pageSize = 20,
  }) async {
    final query = _db.select(_db.stockMovements)
      ..where((m) {
        Expression<bool> where = m.productId.equals(productId);
        if (storeId != null) where = where & m.storeId.equals(storeId);
        if (movementType != null) where = where & m.type.equals(movementType);
        if (from != null) {
          where = where & m.createdAt.isBiggerOrEqualValue(from);
        }
        if (to != null) {
          where = where & m.createdAt.isSmallerOrEqualValue(to);
        }
        return where;
      })
      ..orderBy([(m) => OrderingTerm.desc(m.createdAt)])
      ..limit(pageSize, offset: page * pageSize);

    final rows = await query.get();
    final models = rows.map(_movementToModel).toList();

    // Suppress unsynced orphan duplicates caused by a previous crash during
    // upsertLevel: each failed retry inserted a new movement with
    // quantityBefore=0. Only that signature is collapsed (most recent kept);
    // any other unsynced movement is real, even when identical to another
    // one (sale 10→9, restock 9→10, sale 10→9). The variant is part of the
    // key: two variants of one product are different stock levels.
    final seen = <String, bool>{};
    final deduped = <StockMovementModel>[];
    for (final m in models) {
      if (m.synced || m.quantityBefore != 0) {
        deduped.add(m);
      } else {
        final key = '${m.productId}|${m.variantId}|${m.storeId}|'
            '${m.movementType}|${m.quantityDelta}|${m.quantityAfter}';
        if (!seen.containsKey(key)) {
          seen[key] = true;
          deduped.add(m);
        }
        // Asynchronously delete the extra orphan to keep the DB clean.
        else {
          _db.customUpdate(
            'DELETE FROM stock_movements WHERE id = ?',
            variables: [Variable.withString(m.id)],
            updates: {_db.stockMovements},
          );
        }
      }
    }
    return deduped;
  }

  /// Insert a new movement (from a local mutation — not yet synced).
  Future<void> insertMovement(StockMovementModel movement) async {
    await _db.into(_db.stockMovements).insertOnConflictUpdate(
      StockMovementsCompanion(
        id: Value(movement.id),
        productId: Value(movement.productId),
        variantId: Value(movement.variantId),
        storeId: Value(movement.storeId),
        type: Value(movement.movementType),
        quantityBefore: Value(movement.quantityBefore),
        quantityDelta: Value(movement.quantityDelta),
        quantityAfter: Value(movement.quantityAfter),
        actorId: Value(movement.actorId),
        reason: Value(movement.notes),
        synced: const Value(false),
        createdAt: Value(movement.occurredAt),
      ),
    );
  }

  /// Upsert a movement that was fetched from the remote (already synced).
  Future<void> upsertMovement(StockMovementModel movement) async {
    await _db.into(_db.stockMovements).insertOnConflictUpdate(
      StockMovementsCompanion(
        id: Value(movement.id),
        productId: Value(movement.productId),
        variantId: Value(movement.variantId),
        storeId: Value(movement.storeId),
        type: Value(movement.movementType),
        quantityBefore: Value(movement.quantityBefore),
        quantityDelta: Value(movement.quantityDelta),
        quantityAfter: Value(movement.quantityAfter),
        actorId: Value(movement.actorId),
        reason: Value(movement.notes),
        synced: const Value(true),
        createdAt: Value(movement.occurredAt),
      ),
    );
  }

  // ── Mappers ───────────────────────────────────────────────────────────────

  StockLevelModel _levelToModel(StockLevel row) => StockLevelModel(
        id: row.id,
        productId: row.productId,
        variantId: row.variantId,
        storeId: row.storeId,
        quantity: row.quantity,
        minimumThreshold: row.minimumThreshold,
        updatedAt: row.updatedAt,
      );

  StockMovementModel _movementToModel(StockMovement row) => StockMovementModel(
        id: row.id,
        productId: row.productId,
        variantId: row.variantId,
        storeId: row.storeId,
        movementType: row.type,
        quantityBefore: row.quantityBefore,
        quantityDelta: row.quantityDelta,
        quantityAfter: row.quantityAfter,
        actorId: row.actorId,
        notes: row.reason,
        occurredAt: row.createdAt,
        synced: row.synced,
        syncedAt: row.syncedAt,
      );
}
