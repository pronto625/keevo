import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../../../core/sync/domain/sync_conflict.dart';
import '../../../../core/sync/domain/sync_conflict_provider.dart';

/// SyncConflictLogPage — Displays the list of sync conflicts for OWNER review.
///
/// AC9: OWNER can view conflict history from Settings > Sync > Conflits.
class SyncConflictLogPage extends ConsumerWidget {
  const SyncConflictLogPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncConflicts = ref.watch(syncConflictsProvider);
    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      backgroundColor: cs.surface,
      appBar: AppBar(
        backgroundColor: cs.surface,
        title: Text(
          'Conflits de synchronisation',
          style: TextStyle(
            color: cs.onSurface,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.2,
          ),
        ),
      ),
      body: asyncConflicts.when(
        data: (conflicts) => conflicts.isEmpty
            ? _buildEmptyState(context)
            : _buildConflictList(conflicts),
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => AppErrorWidget(error: e),
      ),
    );
  }

  Widget _buildEmptyState(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const SoftIconBadge(
            icon: Icons.check_circle_outline_rounded,
            color: AppTheme.success,
            size: 88,
          ),
          const SizedBox(height: 16),
          Text(
            'Aucun conflit de synchronisation',
            style: Theme.of(context)
                .textTheme
                .titleMedium
                ?.copyWith(fontWeight: FontWeight.w600),
          ),
        ],
      ),
    );
  }

  Widget _buildConflictList(List<SyncConflict> conflicts) {
    return ListView.builder(
      padding: const EdgeInsets.symmetric(vertical: 8),
      itemCount: conflicts.length,
      itemBuilder: (context, index) => _ConflictTile(conflict: conflicts[index]),
    );
  }
}

class _ConflictTile extends StatelessWidget {
  final SyncConflict conflict;
  const _ConflictTile({required this.conflict});

  @override
  Widget build(BuildContext context) {
    final dateStr = DateFormat('dd/MM/yyyy HH:mm').format(conflict.resolvedAt);

    final isStock = conflict.conflictType == 'STOCK_NEGATIVE';

    return ExpansionTile(
      leading: SoftIconBadge(
        icon: isStock ? Icons.warning_amber_rounded : Icons.info_outline_rounded,
        // Sans couleur : pastille teintée marque (cas informatif).
        color: isStock ? AppTheme.warning : null,
        size: 36,
      ),
      title: Text(conflict.displayTitle,
          style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
      subtitle: Text(dateStr,
          style: TextStyle(
              fontSize: 12,
              color: Theme.of(context).colorScheme.onSurfaceVariant)),
      children: [
        if (conflict.conflictData != null)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: conflict.conflictData!.entries
                  .map((e) => Padding(
                        padding: const EdgeInsets.only(bottom: 4),
                        child: Text('${e.key}: ${e.value}',
                            style: const TextStyle(fontSize: 13)),
                      ))
                  .toList(),
            ),
          ),
      ],
    );
  }
}
