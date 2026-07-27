import 'dart:async';
import 'dart:developer' as dev;
import 'dart:math';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:drift/drift.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../features/catalog/presentation/provider/category_provider.dart';
import '../../features/catalog/presentation/provider/product_provider.dart';
import '../../features/catalog/presentation/provider/stock_provider.dart';
import '../../features/contact/presentation/provider/contact_provider.dart';
import '../../features/dashboard/presentation/provider/dashboard_providers.dart';
import '../../features/inventory/presentation/provider/global_stock_provider.dart';
import '../../features/pos/presentation/provider/day_closure_providers.dart';
import '../../features/pos/presentation/provider/pos_providers.dart';
import '../../features/reports/presentation/provider/report_history_providers.dart';
import '../../features/stores/presentation/provider/store_provider.dart';
import '../../features/team/presentation/provider/employee_provider.dart';
import '../di/providers.dart';
import '../storage/app_constants.dart';
import 'sync_gate_provider.dart';
import 'sync_required_exception.dart';
import 'sync_status_provider.dart';

part 'sync_trigger_notifier.g.dart';

/// Provider for pending stock conflict notifications (AC8).
/// SyncTriggerNotifier populates this; SyncIndicator listens and shows SnackBars.
final pendingConflictNotificationsProvider =
    StateProvider<List<Map<String, dynamic>>>((ref) => []);

// ── State ─────────────────────────────────────────────────────────────────────

sealed class SyncTriggerState {
  const SyncTriggerState();
  const factory SyncTriggerState.idle() = SyncTriggerIdle;
  const factory SyncTriggerState.syncing() = SyncTriggerSyncing;
  const factory SyncTriggerState.criticalFailure({required Duration duration}) =
      SyncTriggerCriticalFailure;
}

class SyncTriggerIdle extends SyncTriggerState {
  const SyncTriggerIdle();
}

class SyncTriggerSyncing extends SyncTriggerState {
  const SyncTriggerSyncing();
}

class SyncTriggerCriticalFailure extends SyncTriggerState {
  final Duration duration;
  const SyncTriggerCriticalFailure({required this.duration});
}

// ── Notifier ──────────────────────────────────────────────────────────────────

@Riverpod(keepAlive: true)
class SyncTriggerNotifier extends _$SyncTriggerNotifier {
  Timer? _retryTimer;
  Timer? _periodicPushTimer;
  Timer? _periodicSyncTimer;
  Timer? _gateCheckTimer;
  int _consecutiveFailures = 0;
  DateTime? _firstFailureAt;

  @override
  SyncTriggerState build() {
    ref.listen(connectivityStreamProvider, (prev, next) {
      next.whenData((results) {
        final isOnline = results.any((r) =>
            r == ConnectivityResult.mobile ||
            r == ConnectivityResult.wifi ||
            r == ConnectivityResult.ethernet);
        if (isOnline) _onConnectivityRestored();
      });
    });

    // Periodic push check every 30s — catches pending queue when backend
    // was down but network stayed connected.
    _periodicPushTimer = Timer.periodic(const Duration(seconds: 30), (_) {
      _tryPeriodicPush();
    });

    // AC6: Periodic full push→pull cycle every 5 minutes for multi-device freshness
    _periodicSyncTimer = Timer.periodic(const Duration(minutes: 5), (_) {
      if (state is SyncTriggerIdle) triggerSync();
    });

    // Story 5.4: 30-min gate state refresh
    _gateCheckTimer = Timer.periodic(const Duration(minutes: 30), (_) {
      ref.invalidate(daysSinceLastSyncProvider);
    });

    ref.onDispose(() {
      _retryTimer?.cancel();
      _periodicPushTimer?.cancel();
      _periodicSyncTimer?.cancel();
      _gateCheckTimer?.cancel();
    });

    return const SyncTriggerState.idle();
  }

  void _onConnectivityRestored() {
    // Debounce 3 seconds
    _retryTimer?.cancel();
    _retryTimer = Timer(const Duration(seconds: 3), () {
      triggerSync();
    });
  }

  Future<void> _tryPeriodicPush() async {
    if (state is! SyncTriggerIdle) return;
    final syncService = ref.read(syncServiceProvider);
    final hasPending = await syncService.hasPendingOperations();
    if (hasPending) {
      // Check if queue is stale (ops pending > 1 hour) — trigger diagnostic
      final db = ref.read(appDatabaseProvider);
      final oldestPending = await (db.select(db.syncQueue)
            ..where((t) => t.synced.equals(false))
            ..orderBy([(t) => OrderingTerm.asc(t.createdAt)])
            ..limit(1))
          .getSingleOrNull();
      if (oldestPending != null &&
          DateTime.now().difference(oldestPending.createdAt).inHours >= 1) {
        await ref.read(syncDiagnosticRunnerProvider).runIfNeeded();
      }
      triggerSync();
    }
  }

  Future<void> triggerSync() async {
    state = const SyncTriggerState.syncing();
    try {
      final syncService = ref.read(syncServiceProvider);
      final eventLogger = ref.read(syncEventLoggerProvider);

      // Step 1: Push pending offline ops
      List<Map<String, dynamic>> conflicts = [];
      try {
        conflicts = await syncService.push();
        await eventLogger.logPushSuccess(conflicts.length);
      } on SyncRequiredException catch (e) {
        // Server 423: device is stale server-side.
        // ABORT pull — pull would write kLastSyncAtKey=now and falsely reopen the gate.
        dev.log(
          'Push blocked by server 423 gate: ${e.daysSinceLastSync} days stale',
          name: 'SyncTrigger',
        );
        await eventLogger.logPushFailed('423 gate: ${e.daysSinceLastSync} days stale');
        // Force client gate to stay blocked (align with server reality)
        final prefs = ref.read(sharedPreferencesProvider);
        final staleMs = DateTime.now()
            .subtract(const Duration(days: 7))
            .millisecondsSinceEpoch;
        await prefs.setInt(kLastSyncAtKey, staleMs);
        ref.invalidate(daysSinceLastSyncProvider);
        rethrow; // outer catch handles _scheduleRetry
      } catch (e) {
        dev.log('Push failed during sync cycle: $e', name: 'SyncTrigger');
        await eventLogger.logPushFailed(e.toString());
        // Non-423 push failure: proceed with pull (pull is NOT gated)
      }

      // Notify UI about STOCK_NEGATIVE conflicts via provider (AC8)
      final stockConflicts = conflicts
          .where((c) => c['status'] == 'CONFLICT')
          .toList();
      if (stockConflicts.isNotEmpty) {
        ref.read(pendingConflictNotificationsProvider.notifier).state =
            stockConflicts;
      }
      // Log LWW conflicts silently (LAST_WRITE_WINS — no user notification)
      for (final c in conflicts) {
        if (c['status'] == 'APPLIED' && c['conflictData'] != null) {
          dev.log('LWW overwrite detected', name: 'SyncTrigger');
        }
      }

      // Step 2: Pull server delta
      try {
        await syncService.pull();
        await eventLogger.logPullSuccess(0);
      } catch (e) {
        await eventLogger.logPullFailed(e.toString());
        rethrow;
      }

      _consecutiveFailures = 0;
      _firstFailureAt = null;
      // Invalidate ALL entity providers so UI refreshes with pulled data
      _invalidateAllProviders();
      state = const SyncTriggerState.idle();
    } catch (_) {
      _consecutiveFailures++;
      _firstFailureAt ??= DateTime.now();
      _scheduleRetry();
    }
  }

  /// AC6: Called after JWT validation at app startup to pull latest server state.
  Future<void> onAppStartup() async {
    if (state is! SyncTriggerIdle) return;
    triggerSync();
  }

  /// Push-only trigger — backward compatibility for connectivity restore.
  Future<void> triggerPush() async {
    state = const SyncTriggerState.syncing();
    try {
      final syncService = ref.read(syncServiceProvider);
      await syncService.push();
      _consecutiveFailures = 0;
      _firstFailureAt = null;
      _invalidateAllProviders();
      state = const SyncTriggerState.idle();
    } catch (_) {
      _consecutiveFailures++;
      _firstFailureAt ??= DateTime.now();
      _scheduleRetry();
    }
  }

  /// Invalidate all Riverpod providers for entity types refreshed by pull.
  void _invalidateAllProviders() {
    // ── Catalog ───────────────────────────────────────────────────────────
    ref.invalidate(stockNotifierProvider);
    ref.invalidate(productListForPickerProvider);
    ref.invalidate(productListProvider);
    ref.invalidate(archivedProductListProvider);
    ref.invalidate(outOfStockProductListProvider);
    ref.invalidate(lowStockProductListProvider);
    ref.invalidate(categoriesProvider);
    // ── Contacts ─────────────────────────────────────────────────────────
    ref.invalidate(clientListNotifierProvider);
    ref.invalidate(supplierListNotifierProvider);
    // ── Stores ───────────────────────────────────────────────────────────
    ref.invalidate(storeListNotifierProvider);
    // ── Inventory ────────────────────────────────────────────────────────
    ref.invalidate(globalStockOverviewProvider);
    ref.invalidate(storeStockDetailProvider);
    // ── Dashboard ────────────────────────────────────────────────────────
    ref.invalidate(dashboardSnapshotProvider);
    // ── Team ─────────────────────────────────────────────────────────────
    ref.invalidate(employeeListProvider);
    // ── Reports & Day closure ─────────────────────────────────────────────
    ref.invalidate(reportHistoryProvider);
    ref.invalidate(todaySummaryProvider);
    ref.invalidate(dayClosureStateProvider);
    ref.invalidate(lastClosureProvider);
    ref.invalidate(salesHistoryProvider);
    // ── POS — Pending sales ───────────────────────────────────────────────
    ref.invalidate(pendingSalesProvider);
    ref.invalidate(pendingSalesCountProvider);
  }

  void _scheduleRetry() {
    final delayMs = min(
      (pow(2, _consecutiveFailures) * 1000).toInt(),
      300000, // max 5 min
    );
    // Add ±20% jitter
    final jitter = (delayMs * 0.2 * (Random().nextDouble() * 2 - 1)).toInt();
    _retryTimer = Timer(Duration(milliseconds: delayMs + jitter), triggerPush);

    // Check critical failure threshold
    if (_consecutiveFailures >= 10 && _firstFailureAt != null) {
      final duration = DateTime.now().difference(_firstFailureAt!);
      if (duration.inHours >= 24) {
        state = SyncTriggerState.criticalFailure(duration: duration);
      }
    }
  }
}
