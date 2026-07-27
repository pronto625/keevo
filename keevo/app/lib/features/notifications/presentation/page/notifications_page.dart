import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/widget/app_error_widget.dart';

import '../../domain/model/notification_model.dart';
import '../provider/notification_provider.dart';
import '../widget/notification_card.dart';

/// Notifications list page — Story 8.0.
class NotificationsPage extends ConsumerStatefulWidget {
  const NotificationsPage({super.key});

  @override
  ConsumerState<NotificationsPage> createState() => _NotificationsPageState();
}

class _NotificationsPageState extends ConsumerState<NotificationsPage> {
  @override
  void initState() {
    super.initState();
    // Purge notifications older than 30 days on page open.
    Future.microtask(() {
      final cutoff = DateTime.now().subtract(const Duration(days: 30));
      ref.read(notificationRepositoryProvider).deleteOlderThan(cutoff);
    });
  }

  @override
  Widget build(BuildContext context) {
    final notificationsAsync = ref.watch(notificationsListProvider);
    final unreadAsync = ref.watch(unreadNotificationCountProvider);
    final unread = unreadAsync.valueOrNull ?? 0;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Notifications'),
        leading: context.canPop()
            ? IconButton(
                icon: const Icon(Icons.arrow_back_ios_new_rounded),
                onPressed: () => context.pop(),
              )
            : null,
        actions: [
          if (unread > 0)
            TextButton(
              onPressed: () => ref.refresh(markAllAsReadProvider),
              child: const Text('Tout marquer comme lu'),
            ),
        ],
      ),
      body: notificationsAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => AppErrorWidget(error: e),
        data: (notifications) => notifications.isEmpty
            ? _buildEmptyState(context)
            : _buildList(notifications),
      ),
    );
  }

  Widget _buildEmptyState(BuildContext context) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            Icons.notifications_off_outlined,
            size: 64,
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
          const SizedBox(height: 16),
          Text(
            'Aucune notification',
            style: Theme.of(context).textTheme.titleMedium?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
          ),
        ],
      ),
    );
  }

  Widget _buildList(List<NotificationModel> notifications) {
    return ListView.separated(
      itemCount: notifications.length,
      separatorBuilder: (_, __) => const Divider(height: 1),
      itemBuilder: (context, index) {
        final notification = notifications[index];
        return NotificationCard(
          notification: notification,
          onTap: () => _onNotificationTap(notification),
        );
      },
    );
  }

  void _onNotificationTap(NotificationModel notification) {
    if (!notification.isRead) {
      ref.read(markAsReadProvider(notification.id));
    }
    if (notification.deepLink != null && notification.deepLink!.isNotEmpty) {
      // Use push() so the back button always returns to the notifications list.
      context.push(notification.deepLink!);
    }
  }
}
