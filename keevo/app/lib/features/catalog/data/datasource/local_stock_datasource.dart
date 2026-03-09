import 'package:drift/drift.dart';

import '../../../../core/storage/app_database.dart';
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
    await _db.into(_db.stockLevels).insertOnConflictUpdate(
      StockLevelsCompanion(
        id: Value(level.id),
        productId: Value(level.productId),
        variantId: Value(level.variantId),
        storeId: Value(level.storeId),
        quantity: Value(level.quantity),
        minimumThreshold: Value(level.minimumThreshold),
        updatedAt: Value(level.updatedAt),
      ),
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
