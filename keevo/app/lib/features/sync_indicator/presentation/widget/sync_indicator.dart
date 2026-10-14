import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/sync/sync_gate_provider.dart';
import '../../../../core/sync/sync_gate_state.dart';
import '../../../../core/sync/sync_monitoring_providers.dart';
import '../../../../core/sync/sync_rejection_messages.dart';
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

  /// J27: rejection notices already shown in this process (operation id +
  /// reason) — a notice is shown once per newly-blocked operation.
  static final Set<String> _surfacedRejections = {};

  @visibleForTesting
  static void resetSurfacedRejections() => _surfacedRejections.clear();

  /// « Bloqué … » notices shown and maybe still on screen.
  static final List<ScaffoldFeatureController<SnackBar, SnackBarClosedReason>>
      _blockedNotices = [];

  /// J27: closes every « Bloqué … » notice still displayed.
  static void closeBlockedNotices() {
    for (final c in _blockedNotices) {
      try {
        c.close();
      } catch (_) {
        // Already closed / messenger gone.
      }
    }
    _blockedNotices.clear();
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // AC8: Listen for stock conflict notifications and show amber SnackBar
    ref.listen<List<Map<String, dynamic>>>(pendingConflictNotificationsProvider,
        (prev, conflicts) {
      if (conflicts.isEmpty) return;
      for (final c in conflicts) {
        final data = c['conflictData'] as Map<String, dynamic>?;
        if (data == null) continue;
        final productName = data['productName'] ?? 'Produit';
        final resultingStock = data['resultingStock'] ?? '?';
        final router = GoRouter.maybeOf(context); // J30: see below
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            backgroundColor: AppTheme.warningStrong,
            duration: const Duration(seconds: 5),
            // J27: since Flutter 3.32 a SnackBar with an action persists
            // until tapped — it then covered the bottom buttons for good.
            persist: false,
            showCloseIcon: true,
            closeIconColor: AppTheme.onBrand,
            // Aplat fixe warningStrong (AA) sous onBrand, couleur de contenu
            // imposée par le snackBarTheme dans les deux modes (v1s-16-17).
            content: Row(
              children: [
                const Icon(Icons.warning_amber_rounded,
                    color: AppTheme.onBrand, size: 20),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Conflit de stock : $productName \u2014 stock n\u00e9gatif ($resultingStock). V\u00e9rifiez l\u2019inventaire.',
                    style: const TextStyle(color: AppTheme.onBrand),
                  ),
                ),
              ],
            ),
            action: SnackBarAction(
              label: 'Voir',
              textColor: AppTheme.onBrand,
              onPressed: () => router?.push('/settings/sync/conflicts'),
            ),
          ),
        );
      }
      ref.read(pendingConflictNotificationsProvider.notifier).state = [];
    });

    // J27: no blocked operation left (released, re-attached, abandoned) →
    // the « Bloqué … » notices still on screen go away at once.
    ref.listen<AsyncValue<int>>(quarantinedSyncCountProvider, (prev, next) {
      if (next.valueOrNull == 0) closeBlockedNotices();
    });

    // Notify when a queued offline operation is rejected by the server
    // (duplicate name, plan limit, etc.) — previously silent, retried forever.
    ref.listen<List<Map<String, dynamic>>>(
        pendingRejectionNotificationsProvider, (prev, rejections) {
      if (rejections.isEmpty) return;
      // /settings/sync is OWNER-only: another role gets no « Voir » there.
      bool isOwner() => ref.read(currentUserRoleProvider) == 'OWNER';
      for (final r in rejections) {
        // J27: once per operation and reason, whatever rebuilds or listeners.
        final key = '${r['operationId'] ?? r['entityId']}|${r['reason']}';
        if (r['operationId'] != null && !_surfacedRejections.add(key)) continue;
        final operation =
            friendlySyncOperationLabel(r['operation'] as String? ?? '');
        final reason = friendlySyncRejectionReason(r['reason'] as String?);
        // v1s-16-12: the local change was undone (ghost row removed,
        // inventory adjustments reverted) — say so.
        final undone =
            r['rolledBack'] == true ? ' — annulé sur cet appareil' : '';
        // v1s-16-15: a money operation refused for good is set aside on the
        // device until someone acts on it.
        final quarantined = r['quarantined'] == true;
        final text = quarantined
            ? 'Bloqué ($operation) : $reason — action requise'
            : 'Non synchronisé ($operation) : $reason$undone';
        // J30: the router is captured now — « Voir » is tapped later, maybe
        // after this indicator's page was left (its context is then dead and
        // context.push silently did nothing).
        final router = GoRouter.maybeOf(context);
        final controller = ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            backgroundColor: AppTheme.errorStrong,
            duration: const Duration(seconds: 6),
            // J27: auto-dismissed (an action made it persist forever) and
            // closable; the lasting signal is the indicator « Action requise ».
            persist: false,
            showCloseIcon: true,
            closeIconColor: AppTheme.onBrand,
            content: Row(
              children: [
                const Icon(Icons.error_outline_rounded,
                    color: AppTheme.onBrand, size: 20),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    text,
                    style: const TextStyle(color: AppTheme.onBrand),
                  ),
                ),
              ],
            ),
            action: quarantined || isOwner()
                ? SnackBarAction(
                    label: 'Voir',
                    textColor: AppTheme.onBrand,
                    // /sync/blocked is open to every role; /settings/sync is
                    // OWNER-only.
                    onPressed: () => router?.push(
                        quarantined ? '/sync/blocked' : '/settings/sync'),
                  )
                : null,
          ),
        );
        if (quarantined) _blockedNotices.add(controller);
      }
      ref.read(pendingRejectionNotificationsProvider.notifier).state = [];
    });

    final asyncStatus = ref.watch(syncStatusProvider);
    final days = ref.watch(daysOfflineProvider);
    final gateState = ref.watch(syncGateStateProvider);
    final daysSinceLastSync = ref.watch(daysSinceLastSyncProvider);

    // Story 5.4: blocked state — tapping opens SyncRequiredModal directly
    if (gateState == SyncGateState.blocked) {
      return GestureDetector(
        onTap: () => SyncRequiredModal.show(context),
        child: Semantics(
          // v1s-16-13: the red dot alone says nothing to a screen reader.
          button: true,
          excludeSemantics: true,
          label: 'Accès limité : synchronisation requise. '
              'Touchez pour synchroniser.',
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
                  'Accès limité — Sync requise',
                  style: TextStyle(fontSize: 12, fontWeight: FontWeight.w500),
                ),
              ],
            ),
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
          // J25: a blocked (quarantined) operation needs the user — never a
          // plain green « En ligne » while one exists.
          final blocked =
              ref.watch(quarantinedSyncCountProvider).valueOrNull ?? 0;
          if (!isSyncing && blocked > 0) return _buildActionRequired(blocked);
          if (isSyncing) {
            return _buildIndicator(SyncStatus.syncing,
                days: 0,
                gateState: gateState,
                daysSinceLastSync: daysSinceLastSync);
          }
          return asyncStatus.when(
            data: (status) => _buildIndicator(status,
                days: days,
                gateState: gateState,
                daysSinceLastSync: daysSinceLastSync),
            // v1s-16-13: neutral while the first status loads — never a
            // green/amber "online" flash.
            loading: () => _buildLoading(context),
            error: (_, __) => _buildIndicator(SyncStatus.offlineCritical,
                days: 0,
                gateState: gateState,
                daysSinceLastSync: daysSinceLastSync),
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

    final row = Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        _buildStatusDot(status,
            isOnlineStaleWarning: isOnlineStaleWarning,
            isOnlineStaleCritical: isOnlineStaleCritical),
        const SizedBox(width: 6),
        Text(
          isOnlineStaleCritical
              ? 'Sync urgente'
              : isOnlineStaleWarning
                  ? 'En ligne (sync J-$daysSinceLastSync)'
                  : _label(status, days),
          style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w500),
        ),
      ],
    );
    if (!isOnlineStaleCritical) return row;
    // v1s-16-13: spell the urgency out for screen readers.
    return Semantics(
      excludeSemantics: true,
      label: 'Synchronisation urgente : dernière synchronisation il y a '
          '$daysSinceLastSync jour${daysSinceLastSync > 1 ? 's' : ''}',
      child: row,
    );
  }

  /// J25: at least one operation is blocked on the device (quarantined).
  Widget _buildActionRequired(int blocked) {
    return Semantics(
      excludeSemantics: true,
      label: 'Action requise : $blocked opération${blocked > 1 ? 's' : ''} '
          'bloquée${blocked > 1 ? 's' : ''}. Touchez pour voir.',
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
            'Action requise',
            style: TextStyle(fontSize: 12, fontWeight: FontWeight.w500),
          ),
        ],
      ),
    );
  }

  /// Neutral state while the sync status is not known yet (v1s-16-13).
  Widget _buildLoading(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          key: const Key('sync_dot'),
          width: 10,
          height: 10,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            border: Border.all(color: cs.outline, width: 1.5),
          ),
        ),
        const SizedBox(width: 6),
        Text(
          'Vérification…',
          style: TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w500,
            color: cs.onSurfaceVariant,
          ),
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
      SyncStatus.syncing => AppTheme.iconBlue,
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
