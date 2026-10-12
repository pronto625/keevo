/// SyncService — Abstract interface for offline-first synchronisation.
///
/// Strategy pattern: allows swapping implementations
/// (e.g. RestSyncService → PowerSyncService) without changing callers.
///
/// All implementations must reside in lib/core/sync/ and be
/// registered via Riverpod providers in lib/core/di/providers.dart.
///
/// **Drift connection contract:** every implementation must read and write
/// `sync_queue` (and any local table) through the app's single `AppDatabase`
/// (Drift) connection obtained from `appDatabaseProvider` — never a second
/// connection or isolate of its own. Repositories enqueue operations inside
/// their own Drift transactions; only the shared connection guarantees that
/// [push] sees those queue rows once the transaction commits (and never
/// before). Swapping the strategy (e.g. to PowerSync) must keep that
/// guarantee. When a second implementation appears, add an integration test
/// proving that a row enqueued inside a repository transaction is pushed by
/// it.
abstract interface class SyncService {
  /// Push queued local operations to the remote backend.
  ///
  /// Returns result maps needing user attention: CONFLICT (stock negative,
  /// LWW overwrite) and newly-REJECTED operations (business validation
  /// failure whose reason hasn't already been surfaced). Each map carries at
  /// least `operationId`, `status`, and `reason`/`conflictData`. Empty list
  /// if nothing needs attention.
  Future<List<Map<String, dynamic>>> push();

  /// Returns true if the sync_queue has pending (un-synced) operations.
  ///
  /// Used by repositories to avoid overwriting local data with stale
  /// backend values while offline changes haven't been pushed yet.
  ///
  /// Quarantined operations (refused for good, v1s-16-15) are not pending.
  Future<bool> hasPendingOperations();

  /// Number of quarantined operations: money operations refused for good,
  /// kept on the device until the user acts on them (v1s-16-15).
  Future<int> quarantinedOperationCount();

  /// Pull remote delta changes and merge into local Drift DB.
  Future<void> pull();

  /// Enqueue a local operation to be synced on next [push()].
  ///
  /// [operation] — e.g. "CREATE_SALE", "UPDATE_STOCK"
  /// [payload]   — JSON-serialisable map of the operation data
  /// [entityId]  — optional identifier of the entity being synced (Story 5.1)
  Future<void> queueOperation({
    required String operation,
    required Map<String, dynamic> payload,
    String? entityId,
  });
}
