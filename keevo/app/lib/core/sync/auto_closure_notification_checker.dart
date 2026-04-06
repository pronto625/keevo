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

/// AutoClosureNotificationChecker — Story 7.2, Task 23.
///
/// Queries the local Drift DB for automatic day-closures that the user
/// has not yet been notified about, and persists the seen set in
/// SharedPreferences to avoid repeating the same notification.
class AutoClosureNotificationChecker {
  final AppDatabase _db;
  final SharedPreferences _prefs;

  AutoClosureNotificationChecker({
    required AppDatabase db,
    required SharedPreferences prefs,
  })  : _db = db,
        _prefs = prefs;

  /// Returns automatic closures for which no SnackBar has been shown yet.
  Future<List<UnnotifiedClosure>> getUnnotified() async {
    final notifiedIds =
        (_prefs.getStringList(kAutoClosureNotifiedIds) ?? []).toSet();

    final closures = await (_db.select(_db.dayClosures)
          ..where((r) => r.isAutomatic.equals(true)))
        .get();

    final unnotified =
        closures.where((c) => !notifiedIds.contains(c.id)).toList();
    if (unnotified.isEmpty) return [];

    final result = <UnnotifiedClosure>[];
    for (final c in unnotified) {
      final store = await (_db.select(_db.stores)
            ..where((s) => s.id.equals(c.storeId)))
          .getSingleOrNull();
      result.add(UnnotifiedClosure(
        id: c.id,
        storeName: store?.name ?? c.storeId,
        closedAt: c.closedAt,
      ));
    }
    return result;
  }

  /// Persists [ids] as notified so they are not shown again.
  Future<void> markNotified(List<String> ids) async {
    if (ids.isEmpty) return;
    final existing =
        (_prefs.getStringList(kAutoClosureNotifiedIds) ?? []).toSet();
    existing.addAll(ids);
    // Cap at 200 entries to avoid unbounded growth.
    var trimmed = existing.toList();
    if (trimmed.length > 200) {
      trimmed = trimmed.sublist(trimmed.length - 200);
    }
    await _prefs.setStringList(kAutoClosureNotifiedIds, trimmed);
  }
}
