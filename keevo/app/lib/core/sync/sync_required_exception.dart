/// Thrown by [RestSyncService] when the server returns HTTP 423 SYNC_REQUIRED.
///
/// This indicates the device has not pushed for more than 7 days (server-side
/// gate check, AC10). Caught by [SyncTriggerNotifier.triggerSync()] to:
///   1. ABORT the pull cycle (do NOT call pull after server 423)
///   2. Force kLastSyncAtKey to a stale value so the client gate stays blocked
///   3. Invalidate daysSinceLastSyncProvider for immediate UI recomputation
///
/// Distinct from [WriteBlockedException] which is thrown client-side when
/// the local gate check blocks a write operation.
class SyncRequiredException implements Exception {
  final int daysSinceLastSync;
  final String? lastPushAt;

  const SyncRequiredException({
    required this.daysSinceLastSync,
    this.lastPushAt,
  });

  @override
  String toString() =>
      'SyncRequiredException: $daysSinceLastSync days since last push (server 423 gate)';
}
