import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../../core/sync/domain/sync_conflict_provider.dart';
import '../../../../core/sync/sync_monitoring_providers.dart';
import '../../../../core/sync/sync_status.dart';
import '../../../../core/sync/sync_status_provider.dart';
import '../../../../core/sync/sync_trigger_notifier.dart';
import '../../../../core/storage/app_constants.dart';
import '../../../../core/di/providers.dart';

/// Enhanced bottom sheet with sync monitoring details (AC1).
///
/// Shows: last sync time, pending count, active devices, conflict count.
/// Actions: sync now, view conflicts, open settings.
class SyncDetailBottomSheet extends ConsumerWidget {
  const SyncDetailBottomSheet({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncStatus = ref.watch(syncStatusProvider);
    final pendingCount = ref.watch(pendingSyncCountProvider);
    final deviceCount = ref.watch(activeDevicesProvider);
    final conflictsAsync = ref.watch(syncConflictsProvider);
    final isOnline = asyncStatus.valueOrNull == SyncStatus.online ||
        asyncStatus.valueOrNull == SyncStatus.syncing;

    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Synchronisation',
                style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 16),
            const _LastSyncRow(),
            const SizedBox(height: 8),
            // Pending count
            pendingCount.when(
              data: (count) => _InfoRow(
                icon: Icons.hourglass_bottom_rounded,
                label: count == 0
                    ? 'Aucune opération en attente'
                    : '$count opération${count > 1 ? 's' : ''} en attente d\'envoi',
                color: count > 0 ? const Color(0xFFFCC419) : null,
              ),
              loading: () => const _InfoRow(
                  icon: Icons.hourglass_empty, label: '...'),
              error: (_, __) =>
                  const _InfoRow(icon: Icons.error, label: 'Erreur'),
            ),
            const SizedBox(height: 8),
            // Conflict count
            conflictsAsync.when(
              data: (conflicts) => conflicts.isEmpty
                  ? const SizedBox.shrink()
                  : GestureDetector(
                      onTap: () {
                        Navigator.of(context).pop();
                        context.push('/settings/sync/conflicts');
                      },
                      child: _InfoRow(
                        icon: Icons.warning_amber_rounded,
                        label:
                            '${conflicts.length} conflit${conflicts.length > 1 ? 's' : ''} non résolu${conflicts.length > 1 ? 's' : ''}',
                        color: const Color(0xFFFCC419),
                      ),
                    ),
              loading: () => const SizedBox.shrink(),
              error: (_, __) => const SizedBox.shrink(),
            ),
            const SizedBox(height: 8),
            // Active devices
            deviceCount.when(
              data: (devices) => _InfoRow(
                icon: Icons.devices_rounded,
                label:
                    '${devices.length} appareil${devices.length != 1 ? 's' : ''} actif${devices.length != 1 ? 's' : ''}',
              ),
              loading: () =>
                  const _InfoRow(icon: Icons.devices, label: '...'),
              error: (_, __) =>
                  const _InfoRow(icon: Icons.devices, label: 'Hors-ligne'),
            ),
            const SizedBox(height: 20),
            // Sync now button
            SizedBox(
              width: double.infinity,
              child: ElevatedButton(
                onPressed: isOnline
                    ? () async {
                        Navigator.of(context).pop();
                        ref
                            .read(syncTriggerNotifierProvider.notifier)
                            .triggerSync();
                      }
                    : null,
                child: Text(
                    isOnline ? 'Synchroniser maintenant' : 'Hors-ligne'),
              ),
            ),
            const SizedBox(height: 8),
            // Settings link
            SizedBox(
              width: double.infinity,
              child: TextButton(
                onPressed: () {
                  Navigator.of(context).pop();
                  context.push('/settings/sync');
                },
                child: const Text('Paramètres de synchronisation'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _LastSyncRow extends StatefulWidget {
  const _LastSyncRow();

  @override
  State<_LastSyncRow> createState() => _LastSyncRowState();
}

class _LastSyncRowState extends State<_LastSyncRow> {
  String _label = 'inconnue';

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    final ms = prefs.getInt(kLastSyncAtKey);
    if (ms == null || !mounted) return;
    final ts = DateTime.fromMillisecondsSinceEpoch(ms);
    final diff = DateTime.now().difference(ts);
    final label = _humanize(diff);
    if (mounted) setState(() => _label = label);
  }

  String _humanize(Duration d) {
    if (d.inMinutes < 1) return 'à l\'instant';
    if (d.inMinutes < 60) return 'il y a ${d.inMinutes} min';
    if (d.inHours < 24) return 'il y a ${d.inHours} h';
    return 'il y a ${d.inDays} j';
  }

  @override
  Widget build(BuildContext context) {
    return _InfoRow(
      icon: Icons.access_time_rounded,
      label: 'Dernière sync : $_label',
    );
  }
}

class _InfoRow extends StatelessWidget {
  final IconData icon;
  final String label;
  final Color? color;

  const _InfoRow({required this.icon, required this.label, this.color});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Icon(icon, size: 18, color: color ?? Colors.grey[600]),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            label,
            style: TextStyle(
              fontSize: 13,
              color: color ?? Colors.grey[800],
            ),
          ),
        ),
      ],
    );
  }
}
