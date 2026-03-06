import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../../core/sync/sync_status.dart';
import '../../../../core/sync/sync_status_provider.dart';
import '../../../../core/di/providers.dart';
import '../../../../core/storage/app_constants.dart';

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
    final asyncStatus = ref.watch(syncStatusProvider);
    final days = ref.watch(daysOfflineProvider);

    return GestureDetector(
      onTap: () => _showSyncBottomSheet(context, ref, asyncStatus.valueOrNull),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        child: asyncStatus.when(
          data: (status) => _buildIndicator(status, days),
          loading: () => _buildIndicator(SyncStatus.syncing, 0),
          error: (_, __) => _buildIndicator(SyncStatus.offlineCritical, 0),
        ),
      ),
    );
  }

  Widget _buildIndicator(SyncStatus status, int days) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        _buildStatusDot(status),
        const SizedBox(width: 6),
        Text(
          _label(status, days),
          style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w500),
        ),
      ],
    );
  }

  Widget _buildStatusDot(SyncStatus status) {
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
        color: _dotColor(status),
        shape: BoxShape.circle,
      ),
    );
  }

  Color _dotColor(SyncStatus status) {
    return switch (status) {
      SyncStatus.online => const Color(0xFF51CF66),
      SyncStatus.syncing => const Color(0xFF339AF0),
      SyncStatus.offlineOk => const Color(0xFFFCC419),
      SyncStatus.offlineCritical => const Color(0xFFFA5252),
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

  /// Builds and shows the sync details bottom sheet (AC4).
  void _showSyncBottomSheet(
    BuildContext context,
    WidgetRef ref,
    SyncStatus? status,
  ) {
    showModalBottomSheet<void>(
      context: context,
      builder: (ctx) => _buildSyncBottomSheet(ctx, ref, status),
    );
  }

  Widget _buildSyncBottomSheet(
    BuildContext context,
    WidgetRef ref,
    SyncStatus? status,
  ) {
    final isOnline = status == SyncStatus.online || status == SyncStatus.syncing;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Synchronisation',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 12),
            _LastSyncLabel(),
            const SizedBox(height: 8),
            Text(
              'Statut : ${_label(status ?? SyncStatus.offlineCritical, 0)}',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 20),
            SizedBox(
              width: double.infinity,
              child: ElevatedButton(
                onPressed: isOnline
                    ? () async {
                        Navigator.of(context).pop();
                        final svc = ref.read(syncServiceProvider);
                        await svc.push();
                        await svc.pull();
                      }
                    : null,
                child: const Text('Synchroniser maintenant'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Displays "Dernière sync : il y a X heures / minutes" from SharedPreferences.
class _LastSyncLabel extends StatefulWidget {
  @override
  State<_LastSyncLabel> createState() => _LastSyncLabelState();
}

class _LastSyncLabelState extends State<_LastSyncLabel> {
  String _label = 'Dernière sync : inconnue';

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    final ms = prefs.getInt(kLastSyncTimestampKey);
    if (ms == null || !mounted) return;
    final ts = DateTime.fromMillisecondsSinceEpoch(ms);
    final diff = DateTime.now().difference(ts);
    final label = _humanize(diff);
    if (mounted) setState(() => _label = 'Dernière sync : $label');
  }

  String _humanize(Duration d) {
    if (d.inMinutes < 1) return 'à l\'instant';
    if (d.inMinutes < 60) return 'il y a ${d.inMinutes} min';
    if (d.inHours < 24) return 'il y a ${d.inHours} h';
    return 'il y a ${d.inDays} j';
  }

  @override
  Widget build(BuildContext context) {
    return Text(_label, style: Theme.of(context).textTheme.bodyMedium);
  }
}
