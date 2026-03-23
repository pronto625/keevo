import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'sync_gate_provider.dart';

/// SyncGateGuard — Template Method pattern for write protection.
///
/// All write notifiers call [SyncGateGuard.assertWriteAllowed] BEFORE
/// calling repository write methods. If the gate is blocked, throws
/// [WriteBlockedException], which the calling UI catches and uses to
/// show [SyncRequiredModal].
class SyncGateGuard {
  const SyncGateGuard._();

  /// Throws [WriteBlockedException] if [daysSinceLastSync] >= 7.
  /// No-op for all other states (open, warning, critical — writes permitted).
  static void assertWriteAllowed(Ref ref) {
    final gate = ref.read(syncGateStateProvider);
    if (gate.isWriteBlocked) throw const WriteBlockedException();
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
