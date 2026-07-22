import 'dart:developer' as dev;

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
        adjustmentsApplied = await _validateOnline(sessionId);
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

    // Load actorId BEFORE entering the transaction (external call to secure storage).
    final actorId = await ref.read(currentUserIdProvider.future);
    if (actorId == null) throw Exception('No authenticated user');

    // Load all counts
    final counts = await (db.select(db.inventoryCounts)
          ..where((t) => t.sessionId.equals(sessionId)))
        .get();

    int adjustmentsApplied = 0;

    await db.transaction(() async {
      for (final count in counts) {
        if (count.physical == null) continue;
        final ecart = count.physical! - count.theoretical;
        if (ecart == 0) continue;

        // Variant-aware stock_levels lookup (defensive pattern, D3).
        final slQuery = db.select(db.stockLevels)
          ..where((t) {
            final baseFilter =
                t.productId.equals(count.productId) &
                t.storeId.equals(session.storeId);
            final cvId = count.variantId;
            if (cvId == null) {
              return baseFilter & t.variantId.isNull();
            }
            return baseFilter & t.variantId.equals(cvId);
          });
        final slRows = await slQuery.get();
        final stockLevel = slRows.isEmpty ? null : slRows.first;

        // AC2: delta live (mirror backend ValidateInventoryService.loadCurrentStockQuantity).
        final quantityBefore = stockLevel?.quantity ?? 0;
        final quantityDelta = count.physical! - quantityBefore;
        if (quantityDelta == 0) continue; // skip, do NOT count in adjustmentsApplied (AC2)

        final DateTime now = DateTime.now();
        final minimumThresh = stockLevel?.minimumThreshold ?? 0;

        if (stockLevel != null) {
          // Update existing stock_levels row.
          await (db.update(db.stockLevels)
                ..where((t) => t.id.equals(stockLevel.id)))
              .write(StockLevelsCompanion(
            quantity: Value(count.physical!),
            updatedAt: Value(now),
          ));
        } else {
          // AC4: create missing stock_levels row instead of silently skipping.
          await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
            id: const Uuid().v4(),
            productId: count.productId,
            variantId: Value(count.variantId),
            storeId: session.storeId,
            quantity: count.physical!,
            minimumThreshold: const Value(0),
            updatedAt: now,
          ));
        }

        // AC1: insert StockMovement audit trail (within same transaction).
        await db.into(db.stockMovements).insert(StockMovementsCompanion.insert(
          id: const Uuid().v4(),
          productId: count.productId,
          variantId: Value(count.variantId),
          storeId: session.storeId,
          type: 'ADJUSTMENT',
          quantityBefore: Value(quantityBefore),
          quantityDelta: quantityDelta,
          quantityAfter: Value(count.physical!),
          actorId: actorId,
          reason: Value('INVENTORY:$sessionId'),
          source: const Value('INVENTORY'),
          inventorySessionId: Value(sessionId),
          createdAt: now,
        ));

        // AC6: threshold breach detection — local best-effort log only.
        if (minimumThresh > 0 && count.physical! <= minimumThresh) {
          dev.log(
            'Threshold breach: product=${count.productId} '
            'variant=${count.variantId} qty=${count.physical} '
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
    });

    // Queue for sync
    final syncService = ref.read(syncServiceProvider);
    await syncService.queueOperation(
      operation: 'VALIDATE_INVENTORY',
      payload: {'sessionId': sessionId},
      entityId: sessionId,
    );

    dev.log('Offline validation: $adjustmentsApplied adjustments queued',
        name: 'ValidateInventory');

    return adjustmentsApplied;
  }

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
