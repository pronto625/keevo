import 'package:drift/drift.dart';
import '../../../../core/storage/app_database.dart';

class LocalNotificationDataSource {
  final AppDatabase _db;
  const LocalNotificationDataSource(this._db);

  Future<void> insert({
    required String id,
    required String type,
    required String title,
    required String body,
    String? deepLink,
    required DateTime receivedAt,
  }) async {
    await _db.into(_db.notifications).insertOnConflictUpdate(
          NotificationsCompanion.insert(
            id: id,
            type: type,
            title: title,
            body: body,
            deepLink: Value(deepLink),
            isRead: const Value(false),
            receivedAt: receivedAt,
          ),
        );
  }

  Future<void> markAsRead(String id) async {
    await (_db.update(_db.notifications)
          ..where((t) => t.id.equals(id)))
        .write(const NotificationsCompanion(isRead: Value(true)));
  }

  Future<void> markAllAsRead() async {
    await (_db.update(_db.notifications)
          ..where((t) => t.isRead.equals(false)))
        .write(const NotificationsCompanion(isRead: Value(true)));
  }

  Future<int> countUnread() async {
    final query = _db.selectOnly(_db.notifications)
      ..addColumns([_db.notifications.id.count()])
      ..where(_db.notifications.isRead.equals(false));
    final row = await query.getSingle();
    return row.read(_db.notifications.id.count()) ?? 0;
  }

  Future<List<Notification>> getAll() async {
    final query = _db.select(_db.notifications)
      ..orderBy([(t) => OrderingTerm.desc(t.receivedAt)])
      ..limit(200);
    return query.get();
  }

  Future<void> deleteOlderThan(DateTime cutoff) async {
    await (_db.delete(_db.notifications)
          ..where((t) => t.receivedAt.isSmallerThanValue(cutoff)))
        .go();
  }

  Stream<int> watchUnreadCount() {
    final query = _db.selectOnly(_db.notifications)
      ..addColumns([_db.notifications.id.count()])
      ..where(_db.notifications.isRead.equals(false));
    return query.watchSingle().map(
          (row) => row.read(_db.notifications.id.count()) ?? 0,
        );
  }

  Stream<List<Notification>> watchAll() {
    final query = _db.select(_db.notifications)
      ..orderBy([(t) => OrderingTerm.desc(t.receivedAt)])
      ..limit(200);
    return query.watch();
  }
}
