import 'package:drift/drift.dart' show BooleanExpressionOperators;
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../storage/app_constants.dart';
import '../storage/app_database.dart';

/// Details of an automatic day-closure that has not yet been notified to the user.
class UnnotifiedClosure {
  final String id;
  final String storeName;
  final DateTime closedAt;

  const UnnotifiedClosure({
    required this.id,
    required this.storeName,
    required this.closedAt,
  });
}

/// Only automatic closures that happened within this window are announced.
/// Anything older is history pulled from the server (fresh install, new
/// device, re-login) and is never shown.
const Duration kAutoClosureNoticeWindow = Duration(hours: 48);

/// Builds the single notice shown for [closures] (French, no technical data).
///
/// One closure → « Clôture automatique : Boutique A — 5 août 2026 ».
/// Several → one grouped line instead of a sequence of notices.
String autoClosureNoticeMessage(List<UnnotifiedClosure> closures) {
  assert(closures.isNotEmpty);
  if (closures.length == 1) {
    final c = closures.single;
    final date = DateFormat('d MMMM yyyy', 'fr_FR').format(c.closedAt);
    return 'Clôture automatique : ${c.storeName} — $date';
  }
  final stores = closures.map((c) => c.storeName).toSet();
  final head = '${closures.length} journées clôturées automatiquement';
  return stores.length == 1
      ? '$head — ${stores.single}'
      : '$head (${stores.length} boutiques)';
}

/// AutoClosureNotificationChecker — Story 7.2, Task 23.
///
/// Queries the local Drift DB for recent automatic day-closures (closed
/// within [kAutoClosureNoticeWindow]) that the user has not yet been notified
/// about, and persists the seen set in SharedPreferences to avoid repeating
/// the same notification.
///
/// The seen set only ever holds ids of closures still inside the window:
/// older ones are filtered out by date anyway, so they are pruned on every
/// write and the list stays bounded.
class AutoClosureNotificationChecker {
  final AppDatabase _db;
  final SharedPreferences _prefs;
  final DateTime Function() _clock;

  AutoClosureNotificationChecker({
    required AppDatabase db,
    required SharedPreferences prefs,
    DateTime Function()? clock,
  })  : _db = db,
        _prefs = prefs,
        _clock = clock ?? DateTime.now;

  DateTime get _cutoff => _clock().subtract(kAutoClosureNoticeWindow);

  bool _isRecent(DateTime closedAt) => closedAt.isAfter(_cutoff);

  /// Returns recent automatic closures for which no notice has been shown yet,
  /// oldest first.
  ///
  /// J21: [onlyStoreId] restricts them to one store (an EMPLOYEE only hears
  /// about their own store; the owner passes null and sees every store).
  Future<List<UnnotifiedClosure>> getUnnotified({String? onlyStoreId}) async {
    final notifiedIds =
        (_prefs.getStringList(kAutoClosureNotifiedIds) ?? []).toSet();

    final closures = await (_db.select(_db.dayClosures)
          ..where((r) => onlyStoreId == null
              ? r.isAutomatic.equals(true)
              : r.isAutomatic.equals(true) & r.storeId.equals(onlyStoreId)))
        .get();

    final unnotified = closures
        .where((c) => _isRecent(c.closedAt) && !notifiedIds.contains(c.id))
        .toList()
      ..sort((a, b) => a.closedAt.compareTo(b.closedAt));
    if (unnotified.isEmpty) return [];

    final result = <UnnotifiedClosure>[];
    for (final c in unnotified) {
      final store = await (_db.select(_db.stores)
            ..where((s) => s.id.equals(c.storeId)))
          .getSingleOrNull();
      result.add(UnnotifiedClosure(
        id: c.id,
        // No technical id on screen (NFR35): generic label if not pulled yet.
        storeName: store?.name ?? 'Boutique',
        closedAt: c.closedAt,
      ));
    }
    return result;
  }

  /// Persists [ids] as notified so they are not shown again, and prunes every
  /// stored id whose closure is no longer inside the notice window (or no
  /// longer exists locally).
  Future<void> markNotified(List<String> ids) async {
    final existing = _prefs.getStringList(kAutoClosureNotifiedIds);
    if (ids.isEmpty && existing == null) return;

    final recentIds = (await (_db.select(_db.dayClosures)
              ..where((r) => r.isAutomatic.equals(true)))
            .get())
        .where((c) => _isRecent(c.closedAt))
        .map((c) => c.id)
        .toSet();

    final kept =
        <String>{...?existing, ...ids}.where(recentIds.contains).toList();
    await _prefs.setStringList(kAutoClosureNotifiedIds, kept);
  }
}
