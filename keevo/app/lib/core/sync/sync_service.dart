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
  /// Drives the sync cycle (and the sign-out warning). Repositories protect
  /// local data with [unsyncedEntityIds] / [unsyncedStockKeys] instead.
  ///
  /// Quarantined operations (refused for good, v1s-16-15) are not pending,
  /// nor held ones (Stories 12.7, 13.5).
  Future<bool> hasPendingOperations();

  /// Ids of the entities with a pending or held operation still queued (not
  /// quarantined, product photo uploads excluded — the same selection as the
  /// pull protection and [unsyncedStockKeys]), plus every id
  /// those operations reference in their payload (a held sale protects its
  /// client, products and store). A refresh from the server must not replace
  /// them (Story 13.8b).
  Future<Set<String>> unsyncedEntityIds();

  /// `"productId|storeId"` keys whose stock a pending or held operation
  /// changed locally: a server stock read must not overwrite them — the
  /// displayed stock is the server stock plus these local deltas (13.8b).
  Future<Set<String>> unsyncedStockKeys();

  /// Number of held operations (waiting for their module, Story 13.8b).
  Future<int> heldOperationCount();

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

/// Key of a `(product, store)` stock row in [SyncService.unsyncedStockKeys].
String unsyncedStockKey(String productId, String storeId) =>
    '$productId|$storeId';
