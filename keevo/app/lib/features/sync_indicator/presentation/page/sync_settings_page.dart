import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/sync/domain/sync_conflict.dart';
import 'package:keevo_core/sync/sync_event_logger.dart';
import 'package:keevo_core/sync/sync_rejection_messages.dart';
import 'package:keevo_core/sync/sync_status.dart';
import 'package:keevo_core/sync/sync_status_provider.dart';
import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
import 'package:keevo_core/widget/soft_icon_badge.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/sync/domain/sync_conflict_provider.dart';
import '../../../../core/sync/sync_monitoring_providers.dart';
import '../../../../core/sync/sync_trigger_notifier.dart';
import '../widget/quarantined_operations_section.dart';

/// SyncSettingsPage — Paramètres > Synchronisation.
///
/// Template Method pattern: common scaffold with per-tab content.
/// Three tabs: Historique, Conflits, File d'attente.
/// Active devices section at top.
/// FAB: force sync.
class SyncSettingsPage extends ConsumerWidget {
  const SyncSettingsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncStatus = ref.watch(syncStatusProvider);
    final isOnline = asyncStatus.value == SyncStatus.online ||
        asyncStatus.value == SyncStatus.syncing;
    final cs = Theme.of(context).colorScheme;

    return DefaultTabController(
      length: 3,
      child: Scaffold(
        backgroundColor: cs.surface,
        appBar: AppBar(
          backgroundColor: cs.surface,
          title: Text(
            'Synchronisation',
            style: TextStyle(
              color: cs.onSurface,
              fontWeight: FontWeight.w700,
              letterSpacing: -0.2,
            ),
          ),
          bottom: TabBar(
            indicatorColor: cs.primary,
            labelColor: cs.primary,
            unselectedLabelColor: cs.onSurfaceVariant,
            labelStyle: const TextStyle(fontWeight: FontWeight.w600),
            dividerColor: cs.outlineVariant.withAlpha(90),
            tabs: const [
              Tab(text: 'Historique'),
              Tab(text: 'Conflits'),
              Tab(text: 'File d\'attente'),
            ],
          ),
        ),
        body: Column(
          children: [
            _ActiveDevicesSection(),
            const Divider(),
            const Expanded(
              child: TabBarView(
                children: [
                  _HistoryTab(),
                  _ConflitsTab(),
                  _QueueTab(),
                ],
              ),
            ),
          ],
        ),
        // Couleur pleine sans ombre marquée, comme les FAB du rebrand ; grisé
        // hors-ligne pour que l'état désactivé se lise.
        floatingActionButton: FloatingActionButton.extended(
          backgroundColor: isOnline ? cs.primary : cs.surfaceContainerHighest,
          foregroundColor: isOnline ? cs.onPrimary : cs.onSurfaceVariant,
          elevation: 2,
          highlightElevation: 4,
          onPressed: isOnline
              ? () {
                  ref.read(syncTriggerNotifierProvider.notifier).triggerSync();
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Synchronisation lancée...')),
                  );
                }
              : null,
          icon: const Icon(Icons.sync_rounded),
          label: Text(isOnline ? 'Forcer la sync' : 'Hors-ligne'),
        ),
      ),
    );
  }
}

// ── Active Devices Section ──────────────────────────────────────────

// Above this count, the device list gets a bounded height + internal scroll
// instead of growing forever and squeezing the tabs below off-screen.
const _kMaxVisibleDevicesBeforeScroll = 3;
const _kDeviceListMaxHeight = 220.0;

class _ActiveDevicesSection extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final asyncDevices = ref.watch(activeDevicesProvider);
    final selfDeviceId = ref.watch(currentDeviceIdProvider).value;

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
      child: asyncDevices.when(
        data: (devices) => Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.devices_rounded,
                    size: 16, color: theme.colorScheme.onSurfaceVariant),
                const SizedBox(width: 6),
                Text(
                  'Appareils actifs — 30 derniers jours (${devices.length})',
                  style: theme.textTheme.titleSmall
                      ?.copyWith(fontWeight: FontWeight.w600),
                ),
              ],
            ),
            const SizedBox(height: 8),
            if (devices.isEmpty)
              Text('Aucun appareil synchronisé',
                  style: TextStyle(color: theme.colorScheme.onSurfaceVariant))
            else
              ConstrainedBox(
                constraints: BoxConstraints(
                  maxHeight: devices.length > _kMaxVisibleDevicesBeforeScroll
                      ? _kDeviceListMaxHeight
                      : double.infinity,
                ),
                child: Scrollbar(
                  child: ListView.separated(
                    shrinkWrap: true,
                    padding: EdgeInsets.zero,
                    physics: devices.length > _kMaxVisibleDevicesBeforeScroll
                        ? const ClampingScrollPhysics()
                        : const NeverScrollableScrollPhysics(),
                    itemCount: devices.length,
                    separatorBuilder: (_, __) => const SizedBox(height: 6),
                    itemBuilder: (_, i) => _DeviceTile(
                        device: devices[i], selfDeviceId: selfDeviceId),
                  ),
                ),
              ),
          ],
        ),
        loading: () =>
            const Center(child: CircularProgressIndicator.adaptive()),
        error: (_, __) => Text('Impossible de charger les appareils',
            style: TextStyle(color: theme.colorScheme.onSurfaceVariant)),
      ),
    );
  }
}

class _DeviceTile extends StatelessWidget {
  final Map<String, dynamic> device;
  final String? selfDeviceId;
  const _DeviceTile({required this.device, this.selfDeviceId});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final deviceId = device['deviceId'] as String?;
    // Last contact = latest of the last push and the last state update (a pull
    // updates it too): a device that only received data read « Jamais
    // synchronisé » (device test).
    DateTime? parse(Object? raw) =>
        raw is String && raw.isNotEmpty ? DateTime.tryParse(raw)?.toLocal() : null;
    final pushed = parse(device['lastPushAt']);
    final updated = parse(device['updatedAt']);
    final lastPush = pushed == null
        ? updated
        : (updated != null && updated.isAfter(pushed) ? updated : pushed);
    final freshness = _freshnessColor(lastPush, theme.colorScheme);
    // v1s-16-20: a device id is never shown — only whose device it is.
    final name = deviceId != null && deviceId == selfDeviceId
        ? 'Cet appareil'
        : 'Autre appareil';

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      decoration: BoxDecoration(
        color: theme.colorScheme.surface,
        borderRadius: BorderRadius.circular(AppTheme.radiusM),
        border: Border.all(color: theme.colorScheme.outlineVariant.withAlpha(90)),
      ),
      child: Row(
        children: [
          SoftIconBadge(
            icon: Icons.phone_android_rounded,
            color: freshness,
            size: 30,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  name,
                  style: const TextStyle(
                      fontSize: 12, fontWeight: FontWeight.w600),
                  overflow: TextOverflow.ellipsis,
                ),
                const SizedBox(height: 2),
                Text(
                  lastPush != null
                      ? 'Vu ${_humanizeDuration(DateTime.now().difference(lastPush))}'
                      : 'Jamais synchronisé',
                  style: TextStyle(
                      fontSize: 11, color: theme.colorScheme.onSurfaceVariant),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Container(
            width: 8,
            height: 8,
            decoration: BoxDecoration(color: freshness, shape: BoxShape.circle),
          ),
        ],
      ),
    );
  }

  Color _freshnessColor(DateTime? lastPush, ColorScheme cs) {
    if (lastPush == null) return cs.onSurfaceVariant;
    final diff = DateTime.now().difference(lastPush);
    if (diff.inHours < 1) return AppTheme.success;
    if (diff.inHours < 24) return AppTheme.warning;
    return AppTheme.errorColor;
  }
}

// ── History Tab ─────────────────────────────────────────────────────

class _HistoryTab extends ConsumerWidget {
  const _HistoryTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncHistory = ref.watch(syncHistoryProvider);

    return asyncHistory.when(
      data: (events) => events.isEmpty
          ? const Center(child: Text('Aucun événement de synchronisation'))
          : ListView.builder(
              padding: const EdgeInsets.symmetric(vertical: 8),
              itemCount: events.length,
              itemBuilder: (_, i) => _EventTile(event: events[i]),
            ),
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => AppErrorWidget(
        error: e,
        onRetry: () => ref.invalidate(syncHistoryProvider),
      ),
    );
  }
}

class _EventTile extends StatelessWidget {
  final SyncEvent event;
  const _EventTile({required this.event});

  @override
  Widget build(BuildContext context) {
    final isAbandon = event.type == kSyncEventAbandon;
    final icon = switch (event.type) {
      kSyncEventAbandon => Icons.delete_outline_rounded,
      'PUSH' => Icons.cloud_upload_outlined,
      'PULL' => Icons.cloud_download_outlined,
      'DIAGNOSTIC' => Icons.health_and_safety_outlined,
      _ => Icons.sync,
    };
    final color = switch (event.status) {
      'SUCCESS' || 'OK' => AppTheme.success,
      'FAILED' => AppTheme.errorColor,
      'WARN' || 'ABANDONED' => AppTheme.warning,
      _ => Theme.of(context).colorScheme.onSurfaceVariant,
    };
    final dateStr = DateFormat('dd/MM HH:mm').format(event.createdAt);
    final message = event.errorMessage == null
        ? null
        : syncEventMessage(event.type, event.status, event.errorMessage!);

    return ListTile(
      dense: true,
      leading: Icon(icon, color: color, size: 20),
      title: Text(
        isAbandon ? 'Opération abandonnée' : syncEventTitle(event.type, event.status),
        style: const TextStyle(fontSize: 13),
      ),
      subtitle: message != null
          ? Text(message,
              style: const TextStyle(fontSize: 11),
              maxLines: 2,
              overflow: TextOverflow.ellipsis)
          : (event.operationCount > 0
              ? Text('${event.operationCount} opérations',
                  style: const TextStyle(fontSize: 11))
              : null),
      trailing: Text(dateStr,
          style: TextStyle(fontSize: 11, color: Theme.of(context).colorScheme.onSurfaceVariant)),
    );
  }
}

// ── Conflits Tab ────────────────────────────────────────────────────

class _ConflitsTab extends ConsumerWidget {
  const _ConflitsTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncConflicts = ref.watch(syncConflictsProvider);

    return asyncConflicts.when(
      data: (conflicts) => conflicts.isEmpty
          ? const Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  SoftIconBadge(
                    icon: Icons.check_circle_outline_rounded,
                    color: AppTheme.success,
                    size: 72,
                  ),
                  SizedBox(height: 12),
                  Text('Aucun conflit'),
                ],
              ),
            )
          : ListView.builder(
              padding: const EdgeInsets.symmetric(vertical: 8),
              itemCount: conflicts.length,
              itemBuilder: (_, i) => _ConflictTile(conflict: conflicts[i]),
            ),
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => AppErrorWidget(
        error: e,
        onRetry: () => ref.invalidate(syncConflictsProvider),
      ),
    );
  }
}

class _ConflictTile extends StatelessWidget {
  final SyncConflict conflict;
  const _ConflictTile({required this.conflict});

  @override
  Widget build(BuildContext context) {
    final dateStr = DateFormat('dd/MM HH:mm').format(conflict.resolvedAt);
    return ListTile(
      dense: true,
      leading: Icon(
        conflict.conflictType == 'STOCK_NEGATIVE'
            ? Icons.warning_amber_rounded
            : Icons.info_outline,
        color: conflict.conflictType == 'STOCK_NEGATIVE'
            ? AppTheme.warning
            : Theme.of(context).colorScheme.primary,
        size: 20,
      ),
      title: Text(conflict.displayTitle,
          style: const TextStyle(fontSize: 13)),
      subtitle: Text(dateStr, style: const TextStyle(fontSize: 11)),
      onTap: () => _showConflictDetail(context, conflict),
    );
  }

  void _showConflictDetail(BuildContext context, SyncConflict conflict) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (ctx) => DraggableScrollableSheet(
        initialChildSize: 0.6,
        maxChildSize: 0.9,
        expand: false,
        builder: (ctx, scrollController) => ListView(
          controller: scrollController,
          padding: const EdgeInsets.all(16),
          children: [
            Text('Détail du conflit',
                style: Theme.of(ctx)
                    .textTheme
                    .titleMedium
                    ?.copyWith(fontWeight: FontWeight.w700)),
            const SizedBox(height: 12),
            // v1s-16-20: French labels only — no code, id or JSON.
            _DetailRow('Type', SyncConflict.typeLabel(conflict.conflictType)),
            _DetailRow(
                'Résolution', SyncConflict.strategyLabel(conflict.strategy)),
            if (conflict.entityType != null)
              _DetailRow(
                  'Élément', SyncConflict.entityLabel(conflict.entityType!)),
            _DetailRow('Opération',
                friendlySyncOperationLabel(conflict.operationType)),
            _DetailRow('Date', SyncConflict.formatDate(conflict.resolvedAt)),
            if (conflict.detailRows.isNotEmpty) ...[
              const SizedBox(height: 12),
              Text('Détails', style: Theme.of(ctx).textTheme.titleSmall),
              const SizedBox(height: 8),
              for (final (label, value) in conflict.detailRows)
                _DetailRow(label, value),
            ],
          ],
        ),
      ),
    );
  }
}

class _DetailRow extends StatelessWidget {
  final String label;
  final String value;
  const _DetailRow(this.label, this.value);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 90,
            child: Text(label,
                style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: Theme.of(context).colorScheme.onSurfaceVariant)),
          ),
          Expanded(
            child: Text(value, style: const TextStyle(fontSize: 12)),
          ),
        ],
      ),
    );
  }
}

// ── Queue Tab ───────────────────────────────────────────────────────

class _QueueTab extends ConsumerWidget {
  const _QueueTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncQueue = ref.watch(pendingSyncQueueProvider);

    // v1s-16-15: blocked (quarantined) operations first — they need an
    // action — then the operations waiting to be sent.
    return asyncQueue.when(
      data: (ops) => ListView(
        padding: const EdgeInsets.symmetric(vertical: 8),
        children: [
          const QuarantinedOperationsSection(),
          if (ops.isEmpty)
            const Padding(
              padding: EdgeInsets.all(32),
              child: Center(child: Text('Aucune opération en attente')),
            )
          else
            for (final op in ops) _QueueTile(op: op),
        ],
      ),
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => AppErrorWidget(
        error: e,
        onRetry: () => ref.invalidate(pendingSyncQueueProvider),
      ),
    );
  }
}

class _QueueTile extends StatelessWidget {
  final SyncQueueData op;
  const _QueueTile({required this.op});

  @override
  Widget build(BuildContext context) {
    final elapsed = DateTime.now().difference(op.createdAt);
    final timeInQueue = _humanizeDuration(elapsed);
    final isRejected = op.lastError != null;
    return ListTile(
      dense: true,
      leading: Icon(
        isRejected ? Icons.error_outline : Icons.pending_actions,
        size: 20,
        color: isRejected ? AppTheme.errorColor : AppTheme.warning,
      ),
      title: Text(friendlySyncOperationLabel(op.operation),
          style: const TextStyle(fontSize: 13)),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '$timeInQueue — ${op.retryCount} tentative${op.retryCount != 1 ? 's' : ''}',
            style: const TextStyle(fontSize: 11),
          ),
          if (isRejected)
            Padding(
              padding: const EdgeInsets.only(top: 2),
              child: Text(
                friendlySyncRejectionReason(op.lastError),
                style: const TextStyle(
                    fontSize: 11,
                    color: AppTheme.errorColor,
                    fontWeight: FontWeight.w500),
              ),
            ),
        ],
      ),
    );
  }
}

String _humanizeDuration(Duration d) {
  if (d.inMinutes < 1) return 'à l\'instant';
  if (d.inMinutes < 60) return 'il y a ${d.inMinutes} min';
  if (d.inHours < 24) return 'il y a ${d.inHours} h';
  return 'il y a ${d.inDays} j';
}
