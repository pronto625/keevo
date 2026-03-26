import 'dart:convert';

import 'package:drift/drift.dart';

import '../../../../core/storage/app_database.dart';
import '../../domain/model/inventory_session_model.dart';

/// LocalInventorySessionDataSource — Drift-backed offline store for inventory sessions.
///
/// Story 6.1.
class LocalInventorySessionDataSource {
  final AppDatabase _db;

  const LocalInventorySessionDataSource(this._db);

  /// Insert a new session row.
  Future<void> insert(InventorySessionModel session) async {
    await _db.into(_db.inventorySessions).insert(
      InventorySessionsCompanion.insert(
        id: session.id,
        storeId: session.storeId,
        scope: session.scope,
        categoryIds: Value(
          session.categoryIds != null
              ? jsonEncode(session.categoryIds)
              : null,
        ),
        status: session.status,
        startedBy: session.startedBy,
        startedAt: session.startedAt,
        cancelledBy: Value(session.cancelledBy),
        cancelledAt: Value(session.cancelledAt),
        completedAt: Value(session.completedAt),
        updatedAt: session.updatedAt,
        synced: Value(false),
      ),
    );
  }

  /// Upsert a session (used by pull sync).
  Future<void> upsert(InventorySessionModel session) async {
    await _db.into(_db.inventorySessions).insertOnConflictUpdate(
      InventorySessionsCompanion.insert(
        id: session.id,
        storeId: session.storeId,
        scope: session.scope,
        categoryIds: Value(
          session.categoryIds != null
              ? jsonEncode(session.categoryIds)
              : null,
        ),
        status: session.status,
        startedBy: session.startedBy,
        startedAt: session.startedAt,
        cancelledBy: Value(session.cancelledBy),
        cancelledAt: Value(session.cancelledAt),
        completedAt: Value(session.completedAt),
        updatedAt: session.updatedAt,
        synced: Value(true),
      ),
    );
  }

  /// Find the active (IN_PROGRESS) session for a store.
  Future<InventorySessionModel?> findActiveByStoreId(String storeId) async {
    final query = _db.select(_db.inventorySessions)
      ..where((s) =>
          s.storeId.equals(storeId) & s.status.equals('IN_PROGRESS'));
    final rows = await query.get();
    return rows.isEmpty ? null : _toModel(rows.first);
  }

  /// Find a session by ID.
  Future<InventorySessionModel?> findById(String id) async {
    final query = _db.select(_db.inventorySessions)
      ..where((s) => s.id.equals(id));
    final rows = await query.get();
    return rows.isEmpty ? null : _toModel(rows.first);
  }

  /// Find all sessions ordered by started_at DESC.
  Future<List<InventorySessionModel>> findAll({
    int page = 0,
    int size = 20,
  }) async {
    final query = _db.select(_db.inventorySessions)
      ..orderBy([
        (s) => OrderingTerm(expression: s.startedAt, mode: OrderingMode.desc),
      ])
      ..limit(size, offset: page * size);
    final rows = await query.get();
    return rows.map(_toModel).toList();
  }

  /// Update status (for cancel operations).
  Future<void> updateStatus(
    String sessionId,
    String status, {
    String? cancelledBy,
    DateTime? cancelledAt,
  }) async {
    final companion = InventorySessionsCompanion(
      status: Value(status),
      cancelledBy: Value(cancelledBy),
      cancelledAt: Value(cancelledAt),
      updatedAt: Value(DateTime.now()),
    );
    await (_db.update(_db.inventorySessions)
          ..where((s) => s.id.equals(sessionId)))
        .write(companion);
  }

  /// Check if a session ID exists locally with synced=false (pending).
  Future<bool> isPending(String sessionId) async {
    final query = _db.select(_db.inventorySessions)
      ..where((s) => s.id.equals(sessionId) & s.synced.equals(false));
    final rows = await query.get();
    return rows.isNotEmpty;
  }

  InventorySessionModel _toModel(InventorySession row) {
    List<String>? categoryIds;
    if (row.categoryIds != null && row.categoryIds!.isNotEmpty) {
      categoryIds =
          (jsonDecode(row.categoryIds!) as List).cast<String>();
    }
    return InventorySessionModel(
      id: row.id,
      storeId: row.storeId,
      scope: row.scope,
      categoryIds: categoryIds,
      status: row.status,
      startedBy: row.startedBy,
      startedAt: row.startedAt,
      cancelledBy: row.cancelledBy,
      cancelledAt: row.cancelledAt,
      completedAt: row.completedAt,
      updatedAt: row.updatedAt,
    );
  }
}
