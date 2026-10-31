import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/sync/account_read_only_exception.dart';
import 'package:keevo_core/sync/sync_gate_provider.dart';

import '../../features/settings/presentation/provider/account_status_provider.dart';

export 'package:keevo_core/sync/account_read_only_exception.dart';

/// SyncGateGuard — Template Method pattern for write protection.
///
/// All write notifiers call [SyncGateGuard.assertWriteAllowed] BEFORE
/// calling repository write methods. If the gate is blocked, throws
/// [WriteBlockedException], which the calling UI catches and uses to
/// show [SyncRequiredModal]. A read-only account (suspended, deletion
/// pending — A50) throws [AccountReadOnlyException] instead, whose French
/// message the UI shows as is. `RestSyncService.queueOperation` applies the
/// same account check to every queued write (backstop).
class SyncGateGuard {
  const SyncGateGuard._();

  /// Throws [AccountReadOnlyException] while the session's `tenantStatus`
  /// claim is SUSPENDED or DELETION_PENDING, then [WriteBlockedException] if
  /// [daysSinceLastSync] >= 7. No-op otherwise (open, warning, critical —
  /// writes permitted).
  static void assertWriteAllowed(Ref ref) {
    assertAccountWritable(ref.read(tenantStatusClaimProvider).valueOrNull);
    var gate = ref.read(syncGateStateProvider);
    if (gate.isWriteBlocked) {
      // J23: the last-sync time may have been written by a sync (or a pull
      // from another screen) since the gate was computed — re-read it before
      // refusing, so a device that just synced is never kept blocked.
      ref.invalidate(daysSinceLastSyncProvider);
      gate = ref.read(syncGateStateProvider);
    }
    if (gate.isWriteBlocked) throw const WriteBlockedException();
  }

  /// Throws [AccountReadOnlyException] when [tenantStatus] makes the account
  /// read-only. For callers holding a `WidgetRef`: pass
  /// `ref.read(tenantStatusClaimProvider).valueOrNull`.
  static void assertAccountWritable(String? tenantStatus) {
    final readOnly = AccountReadOnlyException.forTenantStatus(tenantStatus);
    if (readOnly != null) throw readOnly;
  }
}

/// Thrown by [SyncGateGuard.assertWriteAllowed()] when [daysSinceLastSync] >= 7.
///
/// Caught by write notifiers to display [SyncRequiredModal].
class WriteBlockedException implements Exception {
  const WriteBlockedException();

  @override
  String toString() => 'WriteBlockedException: sync required before writing';
}
