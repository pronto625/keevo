import 'package:drift/drift.dart';

import '../../../../core/storage/app_database.dart';
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

  // ── Stock Levels ─────────────────────────────────────────────────────────

  Future<List<StockLevelModel>> getLevels(String productId) async {
    final rows = await (_db.select(_db.stockLevels)
          ..where((l) => l.productId.equals(productId)))
        .get();
    return rows.map(_levelToModel).toList();
  }

  Future<StockLevelModel?> getLevelByStore(
    String productId,
    String storeId,
  ) async {
    final row = await (_db.select(_db.stockLevels)
          ..where(
            (l) => l.productId.equals(productId) & l.storeId.equals(storeId),
          ))
        .getSingleOrNull();
    return row != null ? _levelToModel(row) : null;
  }

  Future<void> upsertLevel(StockLevelModel level) async {
    // Use raw SQL to upsert on (product_id, store_id) unique index (schema v10)
    // instead of the PK `id` — prevents duplicate rows when backend rotates UUIDs.
    await _db.customStatement(
      'INSERT INTO stock_levels (id, product_id, variant_id, store_id, quantity, minimum_threshold, updated_at) '
      'VALUES (?, ?, ?, ?, ?, ?, ?) '
      'ON CONFLICT(product_id, store_id) DO UPDATE SET '
      'id = excluded.id, variant_id = excluded.variant_id, '
      'quantity = excluded.quantity, minimum_threshold = excluded.minimum_threshold, '
      'updated_at = excluded.updated_at',
      [
        level.id,
        level.productId,
        level.variantId,
        level.storeId,
        level.quantity,
        level.minimumThreshold,
        level.updatedAt.toIso8601String(),
      ],
    );
  }

  Future<void> updateThreshold(String productId, int minimumThreshold) async {
    await (_db.update(_db.stockLevels)
          ..where((l) => l.productId.equals(productId)))
        .write(StockLevelsCompanion(
          minimumThreshold: Value(minimumThreshold),
          updatedAt: Value(DateTime.now()),
        ));
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

    final entries = stores.map((s) {
      final level = levelByStore[s.id];
      final qty = level?.quantity ?? 0;
      final minThreshold = level?.minimumThreshold ?? 0;
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
    return rows.map(_movementToModel).toList();
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
