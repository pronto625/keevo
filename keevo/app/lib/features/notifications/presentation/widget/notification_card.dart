import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../domain/model/notification_model.dart';

/// A single notification card — Story 8.0.
class NotificationCard extends StatelessWidget {
  final NotificationModel notification;
  final VoidCallback onTap;

  const NotificationCard({
    required this.notification,
    required this.onTap,
    super.key,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isUnread = !notification.isRead;

    return ListTile(
      leading: Text(
        notification.typeIcon,
        style: const TextStyle(fontSize: 24),
      ),
      title: Text(
        notification.title,
        style: TextStyle(
          fontWeight: isUnread ? FontWeight.bold : FontWeight.normal,
        ),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: Text(
        notification.body,
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: theme.textTheme.bodySmall,
      ),
      trailing: Text(
        _relativeTime(notification.receivedAt),
        style: theme.textTheme.labelSmall?.copyWith(
          color: theme.colorScheme.onSurfaceVariant,
        ),
      ),
      tileColor: isUnread
          ? theme.colorScheme.primaryContainer.withValues(alpha: 0.15)
          : null,
      onTap: onTap,
    );
  }

  String _relativeTime(DateTime date) {
    final now = DateTime.now();
    final diff = now.difference(date);

    if (diff.inMinutes < 1) return "À l'instant";
    if (diff.inMinutes < 60) return 'Il y a ${diff.inMinutes}min';
    if (diff.inHours < 24) return 'Il y a ${diff.inHours}h';
    if (diff.inDays == 1) return 'Hier';
    if (diff.inDays < 7) return 'Il y a ${diff.inDays}j';
    return DateFormat('d MMM', 'fr_FR').format(date);
  }
}
