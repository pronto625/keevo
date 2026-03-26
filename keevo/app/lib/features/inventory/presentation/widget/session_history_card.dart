import 'package:flutter/material.dart';

import '../../domain/model/inventory_session_model.dart';

/// SessionHistoryCard — displays a single session in the history list.
/// Story 6.1.
class SessionHistoryCard extends StatelessWidget {
  final InventorySessionModel session;
  final VoidCallback? onTap;

  const SessionHistoryCard({
    super.key,
    required this.session,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final statusColor = _statusColor(theme);
    final statusLabel = _statusLabel();

    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      child: ListTile(
        onTap: onTap,
        leading: CircleAvatar(
          backgroundColor: statusColor.withValues(alpha: 0.15),
          child: Icon(
            _statusIcon(),
            color: statusColor,
            size: 20,
          ),
        ),
        title: Text(
          'Inventaire ${session.scope}',
          style: theme.textTheme.bodyLarge?.copyWith(
            fontWeight: FontWeight.w600,
          ),
        ),
        subtitle: Text(
          _formatDate(session.startedAt),
          style: theme.textTheme.bodySmall,
        ),
        trailing: Chip(
          label: Text(
            statusLabel,
            style: TextStyle(
              color: statusColor,
              fontSize: 12,
              fontWeight: FontWeight.w600,
            ),
          ),
          backgroundColor: statusColor.withValues(alpha: 0.1),
          side: BorderSide.none,
          padding: EdgeInsets.zero,
          visualDensity: VisualDensity.compact,
        ),
      ),
    );
  }

  Color _statusColor(ThemeData theme) {
    switch (session.status) {
      case 'IN_PROGRESS':
        return theme.colorScheme.primary;
      case 'VALIDATED':
        return Colors.green;
      case 'CANCELLED':
        return theme.colorScheme.error;
      default:
        return theme.colorScheme.outline;
    }
  }

  String _statusLabel() {
    switch (session.status) {
      case 'IN_PROGRESS':
        return 'En cours';
      case 'VALIDATED':
        return 'Validé';
      case 'CANCELLED':
        return 'Annulé';
      default:
        return session.status;
    }
  }

  IconData _statusIcon() {
    switch (session.status) {
      case 'IN_PROGRESS':
        return Icons.hourglass_top;
      case 'VALIDATED':
        return Icons.check_circle;
      case 'CANCELLED':
        return Icons.cancel;
      default:
        return Icons.info;
    }
  }

  String _formatDate(DateTime dt) {
    return '${dt.day.toString().padLeft(2, '0')}/'
        '${dt.month.toString().padLeft(2, '0')}/'
        '${dt.year} ${dt.hour.toString().padLeft(2, '0')}:'
        '${dt.minute.toString().padLeft(2, '0')}';
  }
}
