import 'dart:convert';

import 'package:drift/drift.dart';
import 'package:uuid/uuid.dart';

import '../../../../core/storage/app_database.dart';
import '../../domain/model/day_closure_model.dart' as domain;

/// LocalDayClosureDataSource — Drift operations for day closures.
///
/// Story 4.4 — Clôture Journalière & Historique des Ventes.
class LocalDayClosureDataSource {
  final AppDatabase _db;
  final Uuid _uuid;

  LocalDayClosureDataSource(this._db, [Uuid? uuid]) : _uuid = uuid ?? const Uuid();

  /// Insert a closure record and enqueue for sync.
  Future<void> insertClosure(domain.DayClosure closure) async {
    await _db.transaction(() async {
      // 1. Insert day_closures record
      await _db.into(_db.dayClosures).insert(DayClosuresCompanion.insert(
        id: closure.id,
        storeId: closure.storeId,
        actorId: closure.actorId,
        closedAt: closure.closedAt,
        totalSales: closure.summary.totalSales,
        totalRevenue: closure.summary.totalRevenue,
        topProductId: Value(closure.summary.topProductId),
        topProductName: Value(closure.summary.topProductName),
        topProductQty: Value(closure.summary.topProductQty),
        cashAmount: closure.summary.cashAmount,
        momoAmount: closure.summary.momoAmount,
        pendingSalesCount: closure.summary.pendingSalesCount,
        pendingSalesTotal: closure.summary.pendingSalesTotal,
        isAutomatic: Value(closure.isAutomatic),
        synced: const Value(false),
        createdAt: DateTime.now(),
      ));

      // 2. Enqueue for sync
      await _db.into(_db.syncQueue).insert(SyncQueueCompanion.insert(
        id: _uuid.v4(),
        operation: 'CREATE_DAY_CLOSURE',
        payload: jsonEncode({
          'id': closure.id,
          'storeId': closure.storeId,
          'actorId': closure.actorId,
          'closedAt': closure.closedAt.toIso8601String(),
          'totalSales': closure.summary.totalSales,
          'totalRevenue': closure.summary.totalRevenue,
          'topProductId': closure.summary.topProductId,
          'topProductName': closure.summary.topProductName,
          'topProductQty': closure.summary.topProductQty,
          'cashAmount': closure.summary.cashAmount,
          'momoAmount': closure.summary.momoAmount,
          'pendingSalesCount': closure.summary.pendingSalesCount,
          'pendingSalesTotal': closure.summary.pendingSalesTotal,
          'isAutomatic': closure.isAutomatic,
        }),
        createdAt: DateTime.now(),
      ));
    });
  }

  /// Check if a closure exists for today.
  Future<bool> hasClosureToday(String storeId) async {
    final now = DateTime.now();
    final startOfDay = DateTime(now.year, now.month, now.day);
    final endOfDay = DateTime(now.year, now.month, now.day, 23, 59, 59, 999);

    final count = await (_db.selectOnly(_db.dayClosures)
          ..where(_db.dayClosures.storeId.equals(storeId) &
              _db.dayClosures.closedAt.isBetweenValues(startOfDay, endOfDay))
          ..addColumns([_db.dayClosures.id.count()]))
        .map((row) => row.read(_db.dayClosures.id.count()))
        .getSingle();

    return (count ?? 0) > 0;
  }

  /// Get the most recent closure for a store.
  Future<domain.DayClosure?> getLastClosure(String storeId) async {
    final row = await (_db.select(_db.dayClosures)
          ..where((c) => c.storeId.equals(storeId))
          ..orderBy([(c) => OrderingTerm.desc(c.closedAt)])
          ..limit(1))
        .getSingleOrNull();

    if (row == null) return null;

    return domain.DayClosure(
      id: row.id,
      storeId: row.storeId,
      actorId: row.actorId,
      closedAt: row.closedAt,
      summary: domain.DayClosureSummary(
        totalSales: row.totalSales,
        totalRevenue: row.totalRevenue,
        cashAmount: row.cashAmount,
        momoAmount: row.momoAmount,
        topProductId: row.topProductId,
        topProductName: row.topProductName,
        topProductQty: row.topProductQty,
        pendingSalesCount: row.pendingSalesCount,
        pendingSalesTotal: row.pendingSalesTotal,
      ),
      isAutomatic: row.isAutomatic,
      synced: row.synced,
    );
  }

  /// Compute today's summary from local sales.
  ///
  /// Aggregates COMPLETED sales for totalRevenue,
  /// and PENDING_VALIDATION sales for pending count/total.
  Future<domain.DayClosureSummary> computeTodaySummary(
      String storeId, String? employeeId) async {
    final now = DateTime.now();
    final startOfDay = DateTime(now.year, now.month, now.day);
    final endOfDay = DateTime(now.year, now.month, now.day, 23, 59, 59, 999);

    // Query for COMPLETED sales
    var completedQuery = _db.select(_db.sales)
      ..where((s) =>
          s.storeId.equals(storeId) &
          s.occurredAt.isBetweenValues(startOfDay, endOfDay) &
          s.status.equals('COMPLETED'));

    if (employeeId != null) {
      completedQuery = completedQuery..where((s) => s.employeeId.equals(employeeId));
    }

    final completedSales = await completedQuery.get();

    // Query for PENDING_VALIDATION sales
    var pendingQuery = _db.select(_db.sales)
      ..where((s) =>
          s.storeId.equals(storeId) &
          s.occurredAt.isBetweenValues(startOfDay, endOfDay) &
          s.status.equals('PENDING_VALIDATION'));

    if (employeeId != null) {
      pendingQuery = pendingQuery..where((s) => s.employeeId.equals(employeeId));
    }

    final pendingSales = await pendingQuery.get();

    // Aggregate totals
    int totalRevenue = 0;
    int cashAmount = 0;
    int momoAmount = 0;

    for (final sale in completedSales) {
      totalRevenue += sale.totalAmount;
      if (sale.paymentMode == 'CASH') {
        cashAmount += sale.totalAmount;
      } else {
        momoAmount += sale.totalAmount;
      }
    }

    // Compute top product
    final Map<String, _ProductAggregate> productQty = {};

    for (final sale in completedSales) {
      final items = await (_db.select(_db.saleItems)
            ..where((i) => i.saleId.equals(sale.id)))
          .get();

      for (final item in items) {
        final agg = productQty.putIfAbsent(
          item.productId,
          () => _ProductAggregate(item.productId, item.productName),
        );
        agg.qty += item.quantity;
      }
    }

    String? topProductId;
    String? topProductName;
    int topProductQty = 0;

    if (productQty.isNotEmpty) {
      final top = productQty.values.reduce((a, b) => a.qty > b.qty ? a : b);
      topProductId = top.productId;
      topProductName = top.productName;
      topProductQty = top.qty;
    }

    // Pending totals
    int pendingSalesCount = pendingSales.length;
    int pendingSalesTotal = pendingSales.fold(0, (sum, s) => sum + s.totalAmount);

    return domain.DayClosureSummary(
      totalSales: completedSales.length,
      totalRevenue: totalRevenue,
      cashAmount: cashAmount,
      momoAmount: momoAmount,
      topProductId: topProductId,
      topProductName: topProductName,
      topProductQty: topProductQty,
      pendingSalesCount: pendingSalesCount,
      pendingSalesTotal: pendingSalesTotal,
    );
  }

  /// Get today's completed sales count for the badge.
  Future<int> getTodaySalesCount(String storeId) async {
    final now = DateTime.now();
    final startOfDay = DateTime(now.year, now.month, now.day);
    final endOfDay = DateTime(now.year, now.month, now.day, 23, 59, 59, 999);

    final count = await (_db.selectOnly(_db.sales)
          ..where(_db.sales.storeId.equals(storeId) &
              _db.sales.occurredAt.isBetweenValues(startOfDay, endOfDay) &
              _db.sales.status.equals('COMPLETED'))
          ..addColumns([_db.sales.id.count()]))
        .map((row) => row.read(_db.sales.id.count()))
        .getSingle();

    return count ?? 0;
  }
}

/// Helper class for product aggregation.
class _ProductAggregate {
  final String productId;
  final String productName;
  int qty;

  _ProductAggregate(this.productId, this.productName) : qty = 0;
}
