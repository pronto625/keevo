import 'package:drift/drift.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../di/core_providers.dart';
import '../storage/app_database.dart';
import 'sync_rejection_policy.dart';

/// Story 13.8b — held operations stay visible.
///
/// An operation refused with `MODULE_INACTIVE` / `MODULE_READ_ONLY` (or held
/// as a follow-up of one, Stories 12.7, 13.5) is kept on the device until its
/// module is active again. The entity it created or changed (sale, client,
/// product, supplier, store…) stays in its list with a badge saying why it is
/// not on the server yet — no code, id or server reason is shown.

/// The badge text of a held entity whose dominant held reason is [reason].
String heldOperationBadgeLabel(String reason) =>
    reason == readOnlySyncRejectionReason
        ? 'En attente — abonnement expiré'
        : 'En attente — module non activé';

/// Held reason per entity id among [queue]: operations still queued (not
/// synced, not quarantined) whose `lastError` is a held reason. Several held
/// operations of one entity give their dominant reason ([dominantHeldReason]).
Map<String, String> heldReasonsByEntity(Iterable<SyncQueueData> queue) {
  final reasons = <String, List<String?>>{};
  for (final op in queue) {
    final id = op.entityId;
    if (id == null || op.synced || op.quarantinedAt != null) continue;
    if (!isHeldSyncRejection(op.lastError)) continue;
    reasons.putIfAbsent(id, () => []).add(op.lastError);
  }
  return {
    for (final e in reasons.entries) e.key: dominantHeldReason(e.value)!,
  };
}

/// Entity id → held reason, read from the local `sync_queue`. Invalidated by
/// the sync trigger after every push / pull cycle.
final heldEntityReasonsProvider =
    FutureProvider<Map<String, String>>((ref) async {
  final db = ref.watch(appDatabaseProvider);
  final held = await (db.select(db.syncQueue)
        ..where((t) =>
            t.synced.equals(false) &
            t.quarantinedAt.isNull() &
            t.lastError.isIn(heldSyncRejectionReasons)))
      .get();
  return heldReasonsByEntity(held);
});
