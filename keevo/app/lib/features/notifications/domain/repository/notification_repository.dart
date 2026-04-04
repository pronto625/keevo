import '../model/notification_model.dart';

abstract class NotificationRepository {
  Future<void> insert(NotificationModel notification);
  Future<void> markAsRead(String id);
  Future<void> markAllAsRead();
  Future<int> countUnread();
  Future<List<NotificationModel>> getAll();
  Future<void> deleteOlderThan(DateTime cutoff);
}
