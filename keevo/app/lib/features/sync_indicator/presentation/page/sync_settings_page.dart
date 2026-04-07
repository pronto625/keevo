import '../../../../core/theme/app_theme.dart';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../../../core/sync/domain/sync_conflict.dart';
import '../../../../core/sync/domain/sync_conflict_provider.dart';
import '../../../../core/sync/sync_monitoring_providers.dart';
import '../../../../core/sync/sync_status.dart';
import '../../../../core/sync/sync_status_provider.dart';
import '../../../../core/sync/sync_trigger_notifier.dart';
import '../../../../core/storage/app_database.dart';

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
    final isOnline = asyncStatus.valueOrNull == SyncStatus.online ||
        asyncStatus.valueOrNull == SyncStatus.syncing;

    return DefaultTabController(
      length: 3,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Synchronisation'),
          bottom: const TabBar(
            tabs: [
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
        floatingActionButton: FloatingActionButton.extended(
          onPressed: isOnline
              ? () {
                  ref.read(syncTriggerNotifierProvider.notifier).triggerSync();
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Synchronisation lancée...')),
                  );
                }
              : null,
          icon: const Icon(Icons.sync),
          label: Text(isOnline ? 'Forcer la sync' : 'Hors-ligne'),
        ),
      ),
    );
  }
}

// ── Active Devices Section ──────────────────────────────────────────

class _ActiveDevicesSection extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncDevices = ref.watch(activeDevicesProvider);

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
      child: asyncDevices.when(
        data: (devices) => Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Appareils actifs (${devices.length})',
              style: Theme.of(context).textTheme.titleSmall,
            ),
            const SizedBox(height: 8),
            if (devices.isEmpty)
              Text('Aucun appareil synchronisé',
                  style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant))
            else
              ...devices.map((d) => _DeviceTile(device: d)),
          ],
        ),
        loading: () =>
            const Center(child: CircularProgressIndicator.adaptive()),
        error: (_, __) => Text('Impossible de charger les appareils',
            style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant)),
      ),
    );
  }
}

class _DeviceTile extends StatelessWidget {
  final Map<String, dynamic> device;
  const _DeviceTile({required this.device});

  @override
  Widget build(BuildContext context) {
    final deviceId = device['deviceId'] ?? '-';
    final lastPush = device['lastPushAt'] ?? '';
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: Row(
        children: [
          Icon(Icons.phone_android, size: 16, color: Theme.of(context).colorScheme.onSurfaceVariant),
          const SizedBox(width: 8),
          Expanded(
            child: Text(deviceId,
                style: const TextStyle(fontSize: 13),
                overflow: TextOverflow.ellipsis),
          ),
          Text(
            lastPush.isNotEmpty ? _formatDate(lastPush) : '-',
            style: TextStyle(fontSize: 12, color: Theme.of(context).colorScheme.onSurfaceVariant),
          ),
        ],
      ),
    );
  }

  String _formatDate(String iso) {
    try {
      return DateFormat('dd/MM HH:mm').format(DateTime.parse(iso));
    } catch (_) {
      return iso;
    }
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
      error: (e, _) =>
          Center(child: Text('Erreur: $e', style: const TextStyle(color: AppTheme.errorColor))),
    );
  }
}

class _EventTile extends StatelessWidget {
  final SyncEvent event;
  const _EventTile({required this.event});

  @override
  Widget build(BuildContext context) {
    final icon = switch (event.type) {
      'PUSH' => Icons.cloud_upload_outlined,
      'PULL' => Icons.cloud_download_outlined,
      'DIAGNOSTIC' => Icons.health_and_safety_outlined,
      _ => Icons.sync,
    };
    final color = switch (event.status) {
      'SUCCESS' || 'OK' => AppTheme.success,
      'FAILED' => AppTheme.errorColor,
      'WARN' => AppTheme.warning,
      _ => Theme.of(context).colorScheme.onSurfaceVariant,
    };
    final dateStr = DateFormat('dd/MM HH:mm').format(event.createdAt);

    return ListTile(
      dense: true,
      leading: Icon(icon, color: color, size: 20),
      title: Text(
        '${event.type} — ${event.status}',
        style: const TextStyle(fontSize: 13),
      ),
      subtitle: event.errorMessage != null
          ? Text(event.errorMessage!,
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
                  Icon(Icons.check_circle_outline, size: 48, color: AppTheme.success),
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
      error: (e, _) =>
          Center(child: Text('Erreur: $e', style: const TextStyle(color: AppTheme.errorColor))),
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
                style: Theme.of(ctx).textTheme.titleMedium),
            const SizedBox(height: 12),
            _DetailRow('Type', conflict.conflictType),
            _DetailRow('Stratégie', conflict.strategy),
            _DetailRow('Entité', conflict.entityId ?? '-'),
            _DetailRow('Opération', conflict.operationType),
            _DetailRow('Date', conflict.resolvedAt.toIso8601String()),
            if (conflict.conflictData != null) ...[
              const SizedBox(height: 12),
              Text('Données', style: Theme.of(ctx).textTheme.titleSmall),
              const SizedBox(height: 8),
              SelectableText(
                const JsonEncoder.withIndent('  ')
                    .convert(conflict.conflictData),
                style:
                    const TextStyle(fontFamily: 'monospace', fontSize: 12),
              ),
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

    return asyncQueue.when(
      data: (ops) => ops.isEmpty
          ? const Center(child: Text('Aucune opération en attente'))
          : ListView.builder(
              padding: const EdgeInsets.symmetric(vertical: 8),
              itemCount: ops.length,
              itemBuilder: (_, i) => _QueueTile(op: ops[i]),
            ),
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) =>
          Center(child: Text('Erreur: $e', style: const TextStyle(color: AppTheme.errorColor))),
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
    final entityLabel = op.entityId != null
        ? op.entityId!.length > 8
            ? op.entityId!.substring(0, 8)
            : op.entityId!
        : '-';
    return ListTile(
      dense: true,
      leading: const Icon(Icons.pending_actions, size: 20, color: AppTheme.warning),
      title: Text(op.operation, style: const TextStyle(fontSize: 13)),
      subtitle: Text(
        '$entityLabel — $timeInQueue — ${op.retryCount} tentative${op.retryCount != 1 ? 's' : ''}',
        style: const TextStyle(fontSize: 11),
      ),
    );
  }

  String _humanizeDuration(Duration d) {
    if (d.inMinutes < 1) return 'à l\'instant';
    if (d.inMinutes < 60) return 'il y a ${d.inMinutes} min';
    if (d.inHours < 24) return 'il y a ${d.inHours} h';
    return 'il y a ${d.inDays} j';
  }
}
