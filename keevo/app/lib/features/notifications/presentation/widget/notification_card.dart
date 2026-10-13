import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/soft_icon_badge.dart';
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

    final (icon, color) = _typeVisual(notification.type);
    final typeLabel = notification.typeLabel;
    final title = Text(
      notification.title,
      style: TextStyle(
        fontWeight: isUnread ? FontWeight.w700 : FontWeight.w500,
        color: theme.colorScheme.onSurface,
      ),
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
    );

    return ListTile(
      leading: SoftIconBadge(icon: icon, color: color),
      title: typeLabel == null
          ? title
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  typeLabel,
                  style: theme.textTheme.labelSmall?.copyWith(
                    color: theme.colorScheme.primary,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                title,
              ],
            ),
      subtitle: Text(
        notification.body,
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: theme.textTheme.bodySmall?.copyWith(
          color: theme.colorScheme.onSurfaceVariant,
        ),
      ),
      trailing: Text(
        _relativeTime(notification.receivedAt),
        style: theme.textTheme.labelSmall?.copyWith(
          color: theme.colorScheme.onSurfaceVariant,
        ),
      ),
      tileColor: isUnread ? AppTheme.softTint(theme.brightness) : null,
      onTap: onTap,
    );
  }

  /// Icône Material équivalente à l'emoji de [NotificationModel.typeIcon]
  /// (même regroupement de types) ; `null` = pastille teintée marque.
  (IconData, Color?) _typeVisual(String type) => switch (type) {
        'DRAFT_PRODUCT_PENDING_VALIDATION' => (
            Icons.edit_note_rounded,
            AppTheme.iconAmber
          ),
        'STOCK_THRESHOLD_BREACHED' || 'STOCK_ALERT' || 'STOCK_ALERT_BATCH' => (
            Icons.warning_amber_rounded,
            AppTheme.iconAmber
          ),
        'TREND_DOWN' => (Icons.trending_down_rounded, AppTheme.errorColor),
        'TREND_UP' => (Icons.trending_up_rounded, AppTheme.success),
        'DAILY_REPORT' || 'WEEKLY_REPORT' => (Icons.bar_chart_rounded, null),
        NotificationModel.globalAnnouncementType => (
            Icons.campaign_rounded,
            null
          ),
        _ => (Icons.notifications_outlined, null),
      };

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
