import '../../../../core/storage/app_database.dart';
import '../../data/datasource/local_notification_datasource.dart';
import '../../domain/model/notification_model.dart';
import '../../domain/repository/notification_repository.dart';

class NotificationRepositoryImpl implements NotificationRepository {
  final LocalNotificationDataSource _local;
  NotificationRepositoryImpl(this._local);

  @override
  Future<void> insert(NotificationModel notification) {
    return _local.insert(
      id: notification.id,
      type: notification.type,
      title: notification.title,
      body: notification.body,
      deepLink: notification.deepLink,
      receivedAt: notification.receivedAt,
    );
  }

  @override
  Future<void> markAsRead(String id) => _local.markAsRead(id);

  @override
  Future<void> markAllAsRead() => _local.markAllAsRead();

  @override
  Future<int> countUnread() => _local.countUnread();

  @override
  Future<List<NotificationModel>> getAll() async {
    final rows = await _local.getAll();
    return rows.map(_toModel).toList();
  }

  @override
  Future<void> deleteOlderThan(DateTime cutoff) =>
      _local.deleteOlderThan(cutoff);

  NotificationModel _toModel(Notification row) {
    return NotificationModel(
      id: row.id,
      type: row.type,
      title: row.title,
      body: row.body,
      deepLink: row.deepLink,
      isRead: row.isRead,
      receivedAt: row.receivedAt,
    );
  }
}
