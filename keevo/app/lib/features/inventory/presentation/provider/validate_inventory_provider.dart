import 'dart:convert';
import 'dart:developer' as dev;
import 'dart:math';

import 'package:drift/drift.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';
import 'package:uuid/uuid.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/storage/app_database.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../../catalog/presentation/provider/product_provider.dart';
import '../../../catalog/presentation/provider/stock_provider.dart';
import '../provider/gap_report_provider.dart';
import '../provider/global_stock_provider.dart';
import '../provider/inventory_session_provider.dart';

part 'validate_inventory_provider.g.dart';

/// ValidateInventoryNotifier — validates inventory session + applies adjustments.
///
/// Online: POST /api/v1/inventory/sessions/{sessionId}/validate
/// Offline: local Drift updates + sync_queue
/// Story 6.4.
@riverpod
class ValidateInventoryNotifier extends _$ValidateInventoryNotifier {
  @override
  FutureOr<void> build() {}

  /// Validates the session. Returns number of adjustments applied.
  Future<int> validate(String sessionId) async {
    // Prevent auto-dispose while async work is in progress —
    // without this, Riverpod disposes the provider during the await
    // (no widget watches this provider, only ref.read) causing
    // "Bad state: Future already completed" on state assignment.
    final link = ref.keepAlive();
    state = const AsyncLoading();
    try {
      final connectivity = ref.read(connectivityServiceProvider);
      int adjustmentsApplied;

      if (await connectivity.isOnline()) {
        // Counts saved offline (or after a failed POST) are still in the
        // queue: validating on the server first would close the session
        // without them. Push them, and if some remain, validate locally —
        // VALIDATE_INVENTORY is then queued behind the counts.
        // A direct push (single-flight) re-reads the queue once a running
        // cycle ends, unlike triggerPush which hands back the running cycle.
        if (await _hasPendingCounts(sessionId)) {
          try {
            await ref.read(syncServiceProvider).push();
          } catch (e) {
            dev.log('Push before validation failed: $e',
                name: 'ValidateInventory');
          }
        }
        adjustmentsApplied = await _hasPendingCounts(sessionId)
            ? await _validateOffline(sessionId)
            : await _validateOnline(sessionId);
      } else {
        adjustmentsApplied = await _validateOffline(sessionId);
      }

      state = const AsyncData(null);
      return adjustmentsApplied;
    } catch (e, st) {
      state = AsyncError(e, st);
      rethrow;
    } finally {
      link.close();
    }
  }

  /// Counts of this session still waiting to be pushed. A count the server
  /// rejected (lastError set) will never be applied: it must not hold the
  /// session in offline validation forever.
  Future<bool> _hasPendingCounts(String sessionId) async {
    final db = ref.read(appDatabaseProvider);
    final ops = await (db.select(db.syncQueue)
          ..where((t) =>
              t.synced.equals(false) &
              t.lastError.isNull() &
              t.operation.equals('SAVE_INVENTORY_COUNT')))
        .get();
    return ops.any((op) {
      try {
        return (jsonDecode(op.payload) as Map)['sessionId'] == sessionId;
      } catch (_) {
        return false;
      }
    });
  }

  Future<int> _validateOnline(String sessionId) async {
    final dio = ref.read(dioProvider);
    final response = await dio.post<Map<String, dynamic>>(
      '/api/v1/inventory/sessions/$sessionId/validate',
    );
    return response.data!['data']['adjustmentsApplied'] as int;
  }

  Future<int> _validateOffline(String sessionId) async {
    final db = ref.read(appDatabaseProvider);

    // Load session
    final session = await (db.select(db.inventorySessions)
          ..where((t) => t.id.equals(sessionId)))
        .getSingleOrNull();
    if (session == null) throw Exception('Session not found');

    // v1s-13-8: re-validating an already VALIDATED session is an idempotent
    // no-op — completedAt is kept and no second VALIDATE_INVENTORY is queued.
    if (session.status == 'VALIDATED') return 0;
    if (session.status != 'IN_PROGRESS') {
      throw Exception(
          'Cet inventaire n\'est plus en cours et ne peut pas être validé.');
    }

    // Load actorId BEFORE entering the transaction (external call to secure storage).
    final actorId = await ref.read(currentUserIdProvider.future);
    if (actorId == null) throw Exception('No authenticated user');

    // Load all counts
    final counts = await (db.select(db.inventoryCounts)
          ..where((t) => t.sessionId.equals(sessionId)))
        .get();

    // v1s-13-8: an offline validation with no counted line would silently
    // close the session — refuse it (online validation stays server-owned).
    if (!counts.any((c) => c.physical != null)) {
      throw Exception(
          'Aucun produit compté : comptez au moins un produit avant de valider.');
    }

    int adjustmentsApplied = 0;
    final syncService = ref.read(syncServiceProvider);
    // Local ADJUSTMENT movement ids, sent with VALIDATE_INVENTORY so the
    // server reuses them (otherwise the pull adds a second copy of each).
    final movementIds = <String, String>{};

    var alreadyClosed = false;
    await db.transaction(() async {
      // v1s-13-8: re-check inside the transaction — a concurrent validate()
      // may have passed the early checks too; only one may apply adjustments.
      final current = await (db.select(db.inventorySessions)
            ..where((t) => t.id.equals(sessionId)))
          .getSingleOrNull();
      if (current?.status != 'IN_PROGRESS') {
        alreadyClosed = true;
        return;
      }

      for (final count in counts) {
        if (count.physical == null) continue;
        final ecart = count.physical! - count.theoretical;
        if (ecart == 0) continue;

        // Variant-aware stock_levels lookup (defensive pattern, D3): exact
        // variant match first, else the (product, store) row — the only row
        // the local unique key allows (v1s-13-8: inserting a second row for a
        // variant mismatch raised a UNIQUE violation). Unique key extension
        // with variant_id is routed to Story 14.1.
        final exactRows = await (db.select(db.stockLevels)
              ..where((t) {
                final baseFilter =
                    t.productId.equals(count.productId) &
                    t.storeId.equals(session.storeId);
                final cvId = count.variantId;
                if (cvId == null) {
                  return baseFilter & t.variantId.isNull();
                }
                return baseFilter & t.variantId.equals(cvId);
              }))
            .get();
        final slRows = exactRows.isNotEmpty
            ? exactRows
            : await (db.select(db.stockLevels)
                  ..where((t) =>
                      t.productId.equals(count.productId) &
                      t.storeId.equals(session.storeId)))
                .get();
        final stockLevel = slRows.isEmpty ? null : slRows.first;

        // Same rule as the backend (ValidateInventoryService): the gap
        // measured at count time is applied on top of the current stock, so
        // sales and entries made since the count stay counted. Never below 0.
        final quantityBefore = stockLevel?.quantity ?? 0;
        final quantityDelta =
            max(ecart, -max(quantityBefore, 0)); // ecart < 0 floors at 0
        if (quantityDelta == 0) continue; // not counted in adjustmentsApplied
        final quantityAfter = quantityBefore + quantityDelta;

        final DateTime now = DateTime.now();
        final minimumThresh = stockLevel?.minimumThreshold ?? 0;

        if (stockLevel != null) {
          // Update existing stock_levels row.
          await (db.update(db.stockLevels)
                ..where((t) => t.id.equals(stockLevel.id)))
              .write(StockLevelsCompanion(
            quantity: Value(quantityAfter),
            updatedAt: Value(now),
          ));
        } else {
          // AC4: create missing stock_levels row instead of silently skipping.
          await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
            id: const Uuid().v4(),
            productId: count.productId,
            variantId: Value(count.variantId),
            storeId: session.storeId,
            quantity: quantityAfter,
            minimumThreshold: const Value(0),
            updatedAt: now,
          ));
        }

        // AC1: insert StockMovement audit trail (within same transaction).
        final movementId = const Uuid().v4();
        movementIds[_movementKey(count.productId, count.variantId)] =
            movementId;
        await db.into(db.stockMovements).insert(StockMovementsCompanion.insert(
          id: movementId,
          productId: count.productId,
          variantId: Value(count.variantId),
          storeId: session.storeId,
          type: 'ADJUSTMENT',
          quantityBefore: Value(quantityBefore),
          quantityDelta: quantityDelta,
          quantityAfter: Value(quantityAfter),
          actorId: actorId,
          reason: Value('INVENTORY:$sessionId'),
          source: const Value('INVENTORY'),
          inventorySessionId: Value(sessionId),
          createdAt: now,
        ));

        // AC6: threshold breach detection — local best-effort log only.
        if (minimumThresh > 0 && quantityAfter <= minimumThresh) {
          dev.log(
            'Threshold breach: product=${count.productId} '
            'variant=${count.variantId} qty=$quantityAfter '
            'threshold=$minimumThresh',
            name: 'ValidateInventory',
            level: 900,
          );
        }

        adjustmentsApplied++;
      }

      // Update session status to VALIDATED
      await (db.update(db.inventorySessions)
            ..where((t) => t.id.equals(sessionId)))
          .write(InventorySessionsCompanion(
        status: const Value('VALIDATED'),
        completedAt: Value(DateTime.now()),
        updatedAt: Value(DateTime.now()),
      ));

      // Queued inside the transaction: if it fails, the local adjustments
      // roll back instead of staying applied without ever reaching the server.
      await syncService.queueOperation(
        operation: 'VALIDATE_INVENTORY',
        payload: {'sessionId': sessionId, 'movementIds': movementIds},
        entityId: sessionId,
      );
    });

    if (alreadyClosed) return 0;

    dev.log('Offline validation: $adjustmentsApplied adjustments queued',
        name: 'ValidateInventory');

    return adjustmentsApplied;
  }

  /// Key matching the backend's ValidateInventoryCommand.movementKey.
  static String _movementKey(String productId, String? variantId) =>
      '$productId|${variantId ?? ''}';

  void invalidateAll() {
    _invalidateProviders();
  }

  void _invalidateProviders() {
    ref.invalidate(gapReportProvider);
    ref.invalidate(activeSessionProvider);
    ref.invalidate(sessionHistoryProvider);
    ref.invalidate(stockNotifierProvider);
    ref.invalidate(productListProvider);
    ref.invalidate(storeStockDetailProvider);
    ref.invalidate(productListForPickerProvider);
  }
}
