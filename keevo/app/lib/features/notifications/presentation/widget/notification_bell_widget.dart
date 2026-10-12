import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../provider/notification_provider.dart';

/// Notification bell icon with unread badge — Story 8.0.
///
/// Shows a red badge with unread count when > 0.
/// Tapping navigates to /notifications.
class NotificationBellWidget extends ConsumerWidget {
  const NotificationBellWidget({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final countAsync = ref.watch(unreadNotificationCountProvider);
    final count = countAsync.valueOrNull ?? 0;

    final theme = Theme.of(context);

    // Même pastille que SoftIconButton, dont on ne peut pas réutiliser le
    // widget ici : il n'accepte qu'un IconData, pas le Badge de compteur.
    return Container(
      decoration: BoxDecoration(
        color: AppTheme.softTint(theme.brightness),
        borderRadius: const BorderRadius.all(Radius.circular(14)),
      ),
      child: IconButton(
        icon: Badge(
          label: Text(
            count > 99 ? '99+' : '$count',
            style: const TextStyle(color: AppTheme.onError, fontSize: 10),
          ),
          isLabelVisible: count > 0,
          backgroundColor: AppTheme.errorColor,
          child: Icon(Icons.notifications_outlined,
              color: theme.colorScheme.primary),
        ),
        onPressed: () => context.push('/notifications'),
        tooltip: 'Notifications',
      ),
    );
  }
}
