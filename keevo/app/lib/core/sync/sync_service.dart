/// SyncService — Abstract interface for offline-first synchronisation.
///
/// Strategy pattern: allows swapping implementations
/// (e.g. RestSyncService → PowerSyncService) without changing callers.
///
/// All implementations must reside in lib/core/sync/ and be
/// registered via Riverpod providers in lib/core/di/providers.dart.
abstract interface class SyncService {
  /// Push queued local operations to the remote backend.
  Future<void> push();

  /// Pull remote delta changes and merge into local Drift DB.
  Future<void> pull();

  /// Enqueue a local operation to be synced on next [push()].
  ///
  /// [operation] — e.g. "CREATE_SALE", "UPDATE_STOCK"
  /// [payload]   — JSON-serialisable map of the operation data
  Future<void> queueOperation({
    required String operation,
    required Map<String, dynamic> payload,
  });
}
