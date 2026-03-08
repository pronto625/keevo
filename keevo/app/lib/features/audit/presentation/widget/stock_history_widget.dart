import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../domain/model/audit_entry_dto.dart';
import '../provider/audit_provider.dart';

/// StockHistoryWidget — displays an entity's audit trail.
///
/// Accepts optional [entityType] and [entityId] for filtered queries.
/// When both are null, shows the full tenant log.
///
/// AC6: embeddable in any entity detail screen (product, user, store).
class StockHistoryWidget extends ConsumerWidget {
  /// Optional filter — entity type (e.g. "Product", "User").
  final String? entityType;

  /// Optional filter — entity UUID string.
  final String? entityId;

  const StockHistoryWidget({
    super.key,
    this.entityType,
    this.entityId,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final historyAsync = ref.watch(
      auditHistoryProvider(entityType: entityType, entityId: entityId),
    );

    return historyAsync.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (err, _) => Center(
        child: Text(
          'Erreur lors du chargement de l\'historique',
          style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: Theme.of(context).colorScheme.error,
              ),
        ),
      ),
      data: (entries) => _buildList(context, entries),
    );
  }

  Widget _buildList(BuildContext context, List<AuditEntryDto> entries) {
    if (entries.isEmpty) {
      return const Center(
        child: _EmptyStateWidget(),
      );
    }

    return ListView.separated(
      padding: const EdgeInsets.symmetric(vertical: 8),
      itemCount: entries.length,
      separatorBuilder: (_, __) => const Divider(height: 1),
      itemBuilder: (context, index) => _AuditEntryTile(entry: entries[index]),
    );
  }
}

// ── Internal widgets ──────────────────────────────────────────────────────────

class _EmptyStateWidget extends StatelessWidget {
  const _EmptyStateWidget();

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Icon(
          Icons.history,
          size: 64,
          color: Theme.of(context).colorScheme.outlineVariant,
        ),
        const SizedBox(height: 16),
        Text(
          'Aucun historique disponible',
          style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
        ),
      ],
    );
  }
}

class _AuditEntryTile extends StatelessWidget {
  final AuditEntryDto entry;

  const _AuditEntryTile({required this.entry});

  /// Maps backend action codes to French labels.
  static String _actionLabel(String action) => switch (action) {
        'STOCK_ADJUSTED' => 'Ajustement stock',
        'SALE_COMPLETED' => 'Vente',
        'TRANSFER_COMPLETED' => 'Transfert',
        'STOCK_RECEIVED' => 'Entrée stock',
        'USER_REGISTERED' => 'Inscription',
        'USER_AUTHENTICATED' => 'Connexion',
        'ONBOARDING_COMPLETED' => 'Configuration boutique',
        _ => action.replaceAll('_', ' ').toLowerCase(),
      };

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final local = entry.occurredAt.toLocal();
    final formattedDate =
        '${local.day.toString().padLeft(2, '0')}/${local.month.toString().padLeft(2, '0')}/${local.year} '
        '${local.hour.toString().padLeft(2, '0')}:${local.minute.toString().padLeft(2, '0')}';

    return ListTile(
      dense: true,
      title: Text(
        _actionLabel(entry.action),
        style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w600),
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Par: ...${entry.userId.length > 8 ? entry.userId.substring(entry.userId.length - 8) : entry.userId}',
            style: theme.textTheme.bodySmall,
          ),
          if (entry.valueBefore != null || entry.valueAfter != null)
            Text(
              '${entry.valueBefore ?? "—"} → ${entry.valueAfter ?? "—"}',
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
        ],
      ),
      trailing: Text(
        formattedDate,
        style: theme.textTheme.bodySmall,
      ),
    );
  }
}
