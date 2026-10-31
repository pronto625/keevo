import 'package:flutter/foundation.dart';
import 'package:keevo_core/storage/app_constants.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../domain/model/notification_model.dart';
import '../datasource/local_notification_datasource.dart';
import '../datasource/remote_announcement_datasource.dart';

/// Stores the Keevo announcements sent to the company in the local
/// notifications list (Story 9-5), so they are listed even when the push was
/// never received or tapped. Called on app start and on resume.
///
/// Each announcement is inserted unread under its own id — the id FCM also
/// carries — and never overwrites a row already stored (no duplicate, read
/// state kept). `since` is the latest `sentAt` already fetched (server clock),
/// 90 days back on the first run. Offline / any error: skipped silently.
class AnnouncementSyncService {
  static const history = Duration(days: 90);

  final RemoteAnnouncementDataSource _remote;
  final LocalNotificationDataSource _local;
  final SharedPreferences _prefs;
  final DateTime Function() _now;

  AnnouncementSyncService({
    required RemoteAnnouncementDataSource remote,
    required LocalNotificationDataSource local,
    required SharedPreferences prefs,
    DateTime Function()? now,
  })  : _remote = remote,
        _local = local,
        _prefs = prefs,
        _now = now ?? DateTime.now;

  bool _inFlight = false;

  /// Returns the number of announcements newly stored (0 on failure).
  Future<int> sync() async {
    if (_inFlight) return 0;
    _inFlight = true;
    try {
      final stored = _prefs.getString(kAnnouncementsFetchedAtKey);
      final since = (stored != null ? DateTime.tryParse(stored) : null) ??
          _now().toUtc().subtract(history);
      final announcements = await _remote.fetch(since: since);
      var inserted = 0;
      DateTime latest = since;
      for (final a in announcements) {
        final added = await _local.insertIfAbsent(
          id: a.id,
          type: NotificationModel.globalAnnouncementType,
          title: a.title,
          body: a.body,
          receivedAt: a.sentAt.toLocal(),
        );
        if (added) inserted++;
        if (a.sentAt.isAfter(latest)) latest = a.sentAt;
      }
      await _prefs.setString(
          kAnnouncementsFetchedAtKey, latest.toUtc().toIso8601String());
      return inserted;
    } catch (e) {
      debugPrint('[Announcements] fetch skipped: $e');
      return 0;
    } finally {
      _inFlight = false;
    }
  }
}
