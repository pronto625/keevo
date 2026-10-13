import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/notification/fcm_service.dart';
import '../../../../features/auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/local_notification_datasource.dart';
import '../../data/datasource/remote_announcement_datasource.dart';
import '../../data/datasource/remote_device_token_datasource.dart';
import '../../data/repository/announcement_sync_service.dart';
import '../../data/repository/notification_repository_impl.dart';
import '../../domain/model/notification_model.dart';
import '../../domain/repository/notification_repository.dart';

// ── Infrastructure providers ─────────────────────────────────────────────────

final localNotificationDataSourceProvider =
    Provider<LocalNotificationDataSource>((ref) {
  return LocalNotificationDataSource(ref.watch(appDatabaseProvider));
});

final remoteDeviceTokenDataSourceProvider =
    Provider<RemoteDeviceTokenDataSource>((ref) {
  return RemoteDeviceTokenDataSource(dio: ref.watch(dioProvider));
});

/// Story 9-5 — Keevo announcements fetched on app start / resume.
final announcementSyncServiceProvider =
    Provider<AnnouncementSyncService>((ref) {
  return AnnouncementSyncService(
    remote: RemoteAnnouncementDataSource(dio: ref.watch(dioProvider)),
    local: ref.watch(localNotificationDataSourceProvider),
    prefs: ref.watch(sharedPreferencesProvider),
  );
});

final notificationRepositoryProvider = Provider<NotificationRepository>((ref) {
  return NotificationRepositoryImpl(
    ref.watch(localNotificationDataSourceProvider),
  );
});

/// Firebase seam behind [FcmService]; overridden in tests.
final fcmMessagingGatewayProvider = Provider<FcmMessagingGateway>(
  (ref) => const FirebaseFcmMessagingGateway(),
);

final fcmServiceProvider = Provider<FcmService>((ref) {
  return FcmService(
    localNotifications: ref.watch(localNotificationDataSourceProvider),
    remoteTokenDataSource: ref.watch(remoteDeviceTokenDataSourceProvider),
    messaging: ref.watch(fcmMessagingGatewayProvider),
  );
});

// ── Feature providers ────────────────────────────────────────────────────────

/// Watches unread notification count reactively via Drift stream.
final unreadNotificationCountProvider = StreamProvider<int>((ref) {
  return ref.watch(localNotificationDataSourceProvider).watchUnreadCount();
});

/// Watches all notifications reactively via Drift stream.
final notificationsListProvider =
    StreamProvider<List<NotificationModel>>((ref) {
  final datasource = ref.watch(localNotificationDataSourceProvider);
  return datasource.watchAll().map((rows) => rows
      .map((row) => NotificationModel(
            id: row.id,
            type: row.type,
            title: row.title,
            body: row.body,
            deepLink: row.deepLink,
            isRead: row.isRead,
            receivedAt: row.receivedAt,
          ))
      .toList());
});

/// Marks a single notification as read and invalidates providers.
final markAsReadProvider =
    FutureProvider.family<void, String>((ref, id) async {
  await ref.read(notificationRepositoryProvider).markAsRead(id);
  ref.invalidate(unreadNotificationCountProvider);
  ref.invalidate(notificationsListProvider);
});

/// Marks all notifications as read.
final markAllAsReadProvider = FutureProvider<void>((ref) async {
  await ref.read(notificationRepositoryProvider).markAllAsRead();
  ref.invalidate(unreadNotificationCountProvider);
  ref.invalidate(notificationsListProvider);
});
