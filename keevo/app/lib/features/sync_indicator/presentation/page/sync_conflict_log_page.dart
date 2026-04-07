import '../../../../core/theme/app_theme.dart';
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

    return Scaffold(
      appBar: AppBar(title: const Text('Conflits de synchronisation')),
      body: asyncConflicts.when(
        data: (conflicts) => conflicts.isEmpty
            ? _buildEmptyState(context)
            : _buildConflictList(conflicts),
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(
          child: Text('Erreur: $e', style: const TextStyle(color: AppTheme.errorColor)),
        ),
      ),
    );
  }

  Widget _buildEmptyState(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.check_circle_outline, size: 64, color: AppTheme.success),
          const SizedBox(height: 16),
          Text(
            'Aucun conflit de synchronisation',
            style: Theme.of(context).textTheme.titleMedium,
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

    return ExpansionTile(
      leading: Icon(
        conflict.conflictType == 'STOCK_NEGATIVE'
            ? Icons.warning_amber_rounded
            : Icons.info_outline,
        color: conflict.conflictType == 'STOCK_NEGATIVE'
            ? AppTheme.warning
            : Theme.of(context).colorScheme.primary,
      ),
      title: Text(conflict.displayTitle, style: const TextStyle(fontSize: 14)),
      subtitle: Text(dateStr, style: const TextStyle(fontSize: 12)),
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
