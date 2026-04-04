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

    return IconButton(
      icon: Badge(
        label: Text(
          count > 99 ? '99+' : '$count',
          style: const TextStyle(color: Colors.white, fontSize: 10),
        ),
        isLabelVisible: count > 0,
        backgroundColor: const Color(0xFFFA5252),
        child: const Icon(Icons.notifications_outlined),
      ),
      onPressed: () => context.go('/notifications'),
      tooltip: 'Notifications',
    );
  }
}
