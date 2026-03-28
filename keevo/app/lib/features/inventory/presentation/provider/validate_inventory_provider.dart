import 'dart:developer' as dev;

import 'package:drift/drift.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

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

        // Update stock_levels locally
        final stockLevel = await (db.select(db.stockLevels)
              ..where((t) =>
                  t.productId.equals(count.productId) &
                  t.storeId.equals(session.storeId)))
            .getSingleOrNull();

        if (stockLevel != null) {
          await (db.update(db.stockLevels)
                ..where((t) => t.id.equals(stockLevel.id)))
              .write(StockLevelsCompanion(
            quantity: Value(count.physical!),
            updatedAt: Value(DateTime.now()),
          ));
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
