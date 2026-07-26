import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/sync/sync_gate_provider.dart';
import '../../../../core/sync/sync_gate_state.dart';
import 'sync_detail_bottom_sheet.dart';
import 'sync_required_modal.dart';
import '../../../../core/sync/sync_status.dart';
import '../../../../core/sync/sync_status_provider.dart';
import '../../../../core/sync/sync_trigger_notifier.dart';

/// SyncIndicator — AppBar widget showing real-time connectivity + sync state.
///
/// State pattern: renders differently for each [SyncStatus] value.
/// Observer pattern: [ConsumerWidget] rebuilds on [syncStatusProvider] changes.
/// Strategy: on tap, delegates sync action to [syncServiceProvider] (SyncService).
///
/// AC3: always visible in AppBar trailing position.
/// AC4: tapping opens a bottom sheet with last sync time + manual sync button.
class SyncIndicator extends ConsumerWidget {
  const SyncIndicator({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // AC8: Listen for stock conflict notifications and show amber SnackBar
    ref.listen<List<Map<String, dynamic>>>(pendingConflictNotificationsProvider, (prev, conflicts) {
      if (conflicts.isEmpty) return;
      for (final c in conflicts) {
        final data = c['conflictData'] as Map<String, dynamic>?;
        if (data == null) continue;
        final productName = data['productName'] ?? 'Produit';
        final resultingStock = data['resultingStock'] ?? '?';
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            backgroundColor: AppTheme.warning,
            duration: const Duration(seconds: 5),
            content: Text(
              '\u26a0 Conflit de stock : $productName \u2014 stock n\u00e9gatif ($resultingStock). V\u00e9rifiez l\u2019inventaire.',
              style: TextStyle(color: Theme.of(context).colorScheme.onSurface),
            ),
            action: SnackBarAction(
              label: 'Voir',
              textColor: Theme.of(context).colorScheme.onSurface,
              onPressed: () => context.push('/settings/sync/conflicts'),
            ),
          ),
        );
      }
      ref.read(pendingConflictNotificationsProvider.notifier).state = [];
    });

    final asyncStatus = ref.watch(syncStatusProvider);
    final days = ref.watch(daysOfflineProvider);
    final gateState = ref.watch(syncGateStateProvider);
    final daysSinceLastSync = ref.watch(daysSinceLastSyncProvider);

    // Story 5.4: blocked state — tapping opens SyncRequiredModal directly
    if (gateState == SyncGateState.blocked) {
      return GestureDetector(
        onTap: () => SyncRequiredModal.show(context),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                key: const Key('sync_dot'),
                width: 10,
                height: 10,
                decoration: const BoxDecoration(
                  color: AppTheme.errorColor,
                  shape: BoxShape.circle,
                ),
              ),
              const SizedBox(width: 6),
              const Text(
                '🔴 Accès limité — Sync requise',
                style: TextStyle(fontSize: 12, fontWeight: FontWeight.w500),
              ),
            ],
          ),
        ),
      );
    }

    return GestureDetector(
      onTap: () => _showSyncBottomSheet(context, ref, asyncStatus.valueOrNull),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        child: Builder(builder: (context) {
          final triggerState = ref.watch(syncTriggerNotifierProvider);
          final isSyncing = triggerState is SyncTriggerSyncing;
          if (isSyncing) {
            return _buildIndicator(SyncStatus.syncing,
                days: 0, gateState: gateState, daysSinceLastSync: daysSinceLastSync);
          }
          return asyncStatus.when(
            data: (status) => _buildIndicator(status,
                days: days, gateState: gateState, daysSinceLastSync: daysSinceLastSync),
            loading: () => _buildIndicator(SyncStatus.online,
                days: 0, gateState: gateState, daysSinceLastSync: daysSinceLastSync),
            error: (_, __) => _buildIndicator(SyncStatus.offlineCritical,
                days: 0, gateState: gateState, daysSinceLastSync: daysSinceLastSync),
          );
        }),
      ),
    );
  }

  Widget _buildIndicator(SyncStatus status,
      {required int days,
      required SyncGateState gateState,
      required int daysSinceLastSync}) {
    final isOnlineStaleWarning =
        status == SyncStatus.online && gateState == SyncGateState.warning;
    final isOnlineStaleCritical =
        status == SyncStatus.online && gateState == SyncGateState.critical;

    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        _buildStatusDot(status,
            isOnlineStaleWarning: isOnlineStaleWarning,
            isOnlineStaleCritical: isOnlineStaleCritical),
        const SizedBox(width: 6),
        Text(
          isOnlineStaleCritical
              ? '⚠ Sync urgente'
              : isOnlineStaleWarning
                  ? 'En ligne (sync J-$daysSinceLastSync)'
                  : _label(status, days),
          style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w500),
        ),
      ],
    );
  }

  Widget _buildStatusDot(SyncStatus status,
      {bool isOnlineStaleWarning = false, bool isOnlineStaleCritical = false}) {
    if (status == SyncStatus.syncing) {
      return const SizedBox(
        width: 10,
        height: 10,
        child: CircularProgressIndicator(strokeWidth: 1.5),
      );
    }
    return Container(
      key: const Key('sync_dot'),
      width: 10,
      height: 10,
      decoration: BoxDecoration(
        color: isOnlineStaleCritical
            ? AppTheme.errorColor
            : isOnlineStaleWarning
                ? AppTheme.warning
                : _dotColor(status),
        shape: BoxShape.circle,
      ),
    );
  }

  Color _dotColor(SyncStatus status) {
    return switch (status) {
      SyncStatus.online => AppTheme.success,
      SyncStatus.syncing => const Color(0xFF339AF0),
      SyncStatus.offlineOk => AppTheme.warning,
      SyncStatus.offlineCritical => AppTheme.errorColor,
    };
  }

  String _label(SyncStatus status, int days) {
    return switch (status) {
      SyncStatus.online => 'En ligne',
      SyncStatus.syncing => 'Synchronisation...',
      SyncStatus.offlineOk => 'Hors-ligne — Jour $days/7',
      SyncStatus.offlineCritical => 'Hors-ligne critique — Jour $days/7',
    };
  }

  /// Builds and shows the enhanced sync details bottom sheet (AC1, AC4).
  void _showSyncBottomSheet(
    BuildContext context,
    WidgetRef ref,
    SyncStatus? status,
  ) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (ctx) => const SyncDetailBottomSheet(),
    );
  }
}
