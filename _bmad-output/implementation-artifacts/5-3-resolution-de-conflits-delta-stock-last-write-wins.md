# Story 5.3: Résolution de Conflits — Delta Stock & Last-Write-Wins

Status: in-progress

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a system,
I want to resolve data conflicts intelligently when multiple devices modify the same entity while offline,
so that stock levels are always mathematically accurate and no data is silently overwritten without a trace.

---

## ⚠️ CRITICAL ARCHITECTURAL CONTEXT — Conflict Resolution in Push Pipeline

> **Story 5.1 (DONE) established the push pipeline: offline queue → batch POST /api/v1/sync/push → handler per operation type.**
> **Story 5.2 (DONE) established the pull pipeline: GET /api/v1/sync/pull → delta merge → local Drift upsert.**
> **Story 5.3 (THIS STORY) adds conflict resolution intelligence INTO the push pipeline.**

### Current State (after 5.1 + 5.2)

```
┌──────────────────────────────────────────────────────────────────────
│ PUSH PIPELINE (Story 5.1) — ALREADY WORKING                        │
│                                                                      │
│  SyncController.push()                                              │
│    → SyncPushService.pushBatch()                                    │
│      → for each operation (sorted by clientTimestamp):              │
│        → check idempotency (sync_operations_log)                   │
│        → SyncOperationHandlerRegistry.resolve(operationType)       │
│        → handler.handle(operation, actorId, tenantId)              │
│          → AbstractSyncOperationHandler: validate() → apply()      │
│            → delegates to domain service (RecordSaleUseCase, etc.) │
│        → log to sync_operations_log                                │
│        → publish SyncOperationProcessedEvent                       │
│      → return SyncBatchResult                                      │
└──────────────────────────────────────────────────────────────────────
```

### What Story 5.3 Adds

```
┌──────────────────────────────────────────────────────────────────────
│ PUSH PIPELINE (after Story 5.3)                                     │
│                                                                      │
│  Same flow, but each handler NOW:                                   │
│                                                                      │
│  1. STOCK OPERATIONS (SALE, STOCK_ADJUST, TRANSFER):                │
│     → Apply delta (already works — RecordSaleUseCase decrements)    │
│     → NEW: Check post-application stock level                       │
│     → If stock < 0 → return CONFLICT + conflictData                │
│     → If stock >= 0 → return APPLIED                               │
│     → NEW: Log StockConflictResolvedEvent                          │
│                                                                      │
│  2. ENTITY UPDATES (PRODUCT_UPDATE, CLIENT_UPDATE, etc.):          │
│     → NEW: Check if same entity was modified by another device     │
│       since this operation's clientTimestamp                         │
│     → If yes → apply latest (LWW) + log SyncOverwrittenEvent      │
│     → If no → normal APPLIED                                       │
│                                                                      │
│  3. APPEND-ONLY (SALE_CANCEL, AUDIT_EVENT):                        │
│     → Always applied, never overwritten (no conflict possible)     │
│                                                                      │
│ FLUTTER:                                                            │
│  → Handle CONFLICT response with conflict details                  │
│  → Display non-blocking warning                                    │
│  → Conflict log screen in Settings > Sync > Conflits              │
└──────────────────────────────────────────────────────────────────────
```

### Key Design Insight — Stock Is Already Delta-Based

The existing push handlers already implement delta-based stock:
- `SaleSyncHandler` → `RecordSaleUseCase` → decrements stock by sale quantity (delta = -quantity)
- `StockAdjustSyncHandler` → `AdjustStockUseCase` → adjusts stock by delta
- `TransferSyncHandler` → `ExecuteTransferService` → TRANSFER_OUT decrements, TRANSFER_IN increments

When two devices sell 3 and 2 units respectively while offline, both operations are applied sequentially. The math is automatically correct: `finalStock = baseStock - 3 - 2`. **No special aggregation needed.**

The NEW logic for Story 5.3 is:
1. **Negative stock detection** — After applying a delta, check if stock went negative → return CONFLICT
2. **LWW overwrite detection** — When two updates target the same entity, log which one was overwritten
3. **ConflictResolutionStrategy interface** — GoF Strategy pattern for selecting resolution approach per operation type
4. **Conflict logging and Flutter UI** — Audit trail + user-visible conflict history

---

## GoF Pattern Analysis (MANDATORY — fill before any implementation)

| Question | Answer |
|---|---|
| What variability exists in this feature? | Three distinct resolution strategies by operation type: delta-based (stock), last-write-wins (entity updates), append-only (immutable records). Each strategy has different detection, resolution, and logging semantics. Future strategies may be added (e.g., manual merge for complex entities). |
| What might change in the future? | New entity types with custom merge strategies (inventory sessions — Epic 6). Configurable resolution per entity (admin chooses LWW vs manual merge). Conflict notification channels (push notification to owner). Conflict resolution UI for manual resolution. |
| Which GoF pattern(s) apply? | **Strategy** — `ConflictResolutionStrategy` interface with `DeltaStockResolver`, `LastWriteWinsResolver`, `AppendOnlyResolver` implementations. **Observer** — `StockConflictResolvedEvent`, `SyncOverwrittenEvent` domain events. **Template Method** — `AbstractSyncOperationHandler` now includes a `resolveConflict()` step in its skeleton. **Factory Method** — `ConflictResolutionStrategyFactory` selects the correct strategy by operation type. |
| How does it enable Open/Closed principle? | Adding a new conflict strategy = implement `ConflictResolutionStrategy` + register operation types. Zero modification to `SyncPushService`, `AbstractSyncOperationHandler`, or existing handlers. |
| Where is the pattern applied? | **Strategy** → `ConflictResolutionStrategy` interface + 3 implementations (backend `sync/sync/domain/port/in/`). **Observer** → Domain events published from handlers → `ConflictEventListener` logs to `sync_conflicts_log`. **Factory Method** → `ConflictStrategyRegistry` (maps operation types → strategy instances). **Template Method** → `AbstractSyncOperationHandler.handle()` skeleton: `validate() → checkConflict() → apply() → logConflict()`. |

---

## Acceptance Criteria

### AC1 — Delta-based stock resolution (Backend)

- **Given** two devices (Device A and Device B) both sell the same product while offline simultaneously
- **When** both push their sync batches to the server
- **Then** the server applies both deltas sequentially (operations are sorted by `clientTimestamp` in `SyncPushService`):
  - Device A sold 3 units: stock decremented by 3 (via RecordSaleUseCase — already works)
  - Device B sold 2 units: stock decremented by 2 (via RecordSaleUseCase — already works)
  - `finalStock = baseStock - 3 - 2 = baseStock - 5` (both applied normally)
- **And** both operations return `status: APPLIED` — no rejection or overwriting
- **And** a `StockConflictResolvedEvent` is published with: `productId`, `storeId`, `deltaApplied` (the delta from this operation), `resultingStock`, `strategy: DELTA_SUM`, `deviceId`
- **And** the event is persisted in `sync_conflicts_log` (new table) for audit purposes

### AC2 — Negative stock detection and CONFLICT response (Backend)

- **Given** a stock delta resolution results in a negative stock value
- **When** the server applies the delta and `resultingStock < 0`
- **Then** the operation is still APPLIED (the stock is set to the negative value — stock corrections are handled by the merchant)
- **But** the response status for that operation is `CONFLICT` (not `REJECTED` — the operation WAS processed)
- **And** the conflict response includes:
  ```json
  {
    "operationId": "uuid",
    "status": "CONFLICT",
    "serverEntityId": "uuid",
    "reason": "STOCK_NEGATIVE",
    "conflictData": {
      "conflictType": "STOCK_NEGATIVE",
      "productId": "uuid",
      "productName": "Coca-Cola 50cl",
      "storeId": "uuid",
      "deltaApplied": -3,
      "resultingStock": -1,
      "previousStock": 2
    }
  }
  ```
- **And** the conflict is logged in `sync_conflicts_log` with strategy `DELTA_SUM` and details
- **And** the Flutter app displays a non-blocking SnackBar: "⚠ Conflit de stock : [produit] — stock négatif (-1). Vérifiez l'inventaire."
- **And** the conflict is visible in Paramètres > Synchronisation > Conflits

### AC3 — Last-write-wins for non-stock entity updates (Backend)

- **Given** two devices modify the same non-stock entity simultaneously (e.g., product name updated on both devices)
- **When** both push their changes
- **Then** operations are processed in `clientTimestamp` order (SyncPushService already sorts)
- **And** the first operation is applied normally → `APPLIED`
- **And** the second operation ALSO applies (overwrites the first) → `APPLIED`
- **And** a `SyncOverwrittenEvent` is logged with: `entityType`, `entityId`, `overwrittenByDeviceTimestamp`, `overwrittenTimestamp`, `strategy: LAST_WRITE_WINS`
- **And** this event is stored in `sync_conflicts_log` (not shown to user by default — visible in audit for OWNER only)
- **And** these entity types use LWW: `PRODUCT_CREATE`, `PRODUCT_UPDATE`, `ARCHIVE_PRODUCT`, `UNARCHIVE_PRODUCT`, `UPDATE_CLIENT`, `ARCHIVE_CLIENT`, `UPDATE_SUPPLIER`, `ARCHIVE_SUPPLIER`, `STORE_UPDATE`, `CREATE_EMPLOYEE`, `REASSIGN_EMPLOYEE`, `DEACTIVATE_EMPLOYEE`, `REACTIVATE_EMPLOYEE`

### AC4 — Append-only strategy for immutable operations (Backend)

- **Given** immutable operations (sale cancellation, audit events)
- **When** they arrive in a push batch
- **Then** they are always applied → never CONFLICT, never overwritten
- **And** no conflict detection is performed (append-only by nature)
- **And** operation types: `CREATE_SALE`, `CREATE_DAY_CLOSURE` (sales and closures are immutable after creation — no update possible)
- **And** deduplication is already handled by the `sync_operations_log` idempotency check (Story 5.1)

### AC5 — ConflictResolutionStrategy interface (GoF Strategy pattern — Backend)

- **Given** the push processing pipeline needs to select the correct conflict strategy
- **When** an operation is processed
- **Then** the strategy is selected automatically by `operationType` through a `ConflictStrategyRegistry`:
  - `CREATE_SALE`, `STOCK_ADJUST`, `RECORD_STOCK_ENTRY`, `STOCK_TRANSFER` → `DeltaStockStrategy` (check post-delta stock for negative)
  - `PRODUCT_CREATE`, `PRODUCT_UPDATE`, `ARCHIVE_PRODUCT`, `UNARCHIVE_PRODUCT`, `UPDATE_CLIENT`, `ARCHIVE_CLIENT`, `UPDATE_SUPPLIER`, `ARCHIVE_SUPPLIER`, `CREATE_EMPLOYEE`, `REASSIGN_EMPLOYEE`, `DEACTIVATE_EMPLOYEE`, `REACTIVATE_EMPLOYEE` → `LastWriteWinsStrategy` (log overwrite if same entity modified since clientTimestamp)
  - `CREATE_DAY_CLOSURE` → `AppendOnlyStrategy` (no conflict possible)
  - Unrecognized → `AppendOnlyStrategy` (safe fallback)
- **And** the interface is:
  ```java
  public interface ConflictResolutionStrategy {
      /**
       * Evaluate conflict AFTER the operation has been applied by the handler.
       * Returns conflict details if a conflict was detected, null otherwise.
       */
      ConflictResult evaluate(SyncOperation operation, SyncOperationResult handlerResult,
                              UUID actorId, String tenantId);
  }
  ```
- **And** `ConflictResult` is a domain record:
  ```java
  public record ConflictResult(
      String conflictType,     // "STOCK_NEGATIVE", "LAST_WRITE_WINS", null for no conflict
      String strategy,         // "DELTA_SUM", "LAST_WRITE_WINS", "APPEND_ONLY"
      Map<String, Object> conflictData  // details for the conflict response
  ) {}
  ```

### AC6 — sync_conflicts_log DDL + persistence (Backend)

- **Given** conflict events need to be persisted for audit and UI consumption
- **When** a conflict is detected (AC1, AC2, AC3)
- **Then** a record is inserted in the `sync_conflicts_log` table (per-tenant schema):
  ```sql
  CREATE TABLE IF NOT EXISTS sync_conflicts_log (
    id VARCHAR(36) PRIMARY KEY,
    operation_id VARCHAR(36) NOT NULL,
    operation_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(36),
    entity_type VARCHAR(50),
    conflict_type VARCHAR(30) NOT NULL,
    strategy VARCHAR(30) NOT NULL,
    conflict_data JSONB,
    resolved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_id VARCHAR(36)
  );
  CREATE INDEX IF NOT EXISTS idx_sync_conflicts_resolved ON sync_conflicts_log(resolved_at);
  CREATE INDEX IF NOT EXISTS idx_sync_conflicts_entity ON sync_conflicts_log(entity_id);
  ```
- **And** `TenantSchemaProvisioner` adds this DDL for new tenants
- **And** `TenantSchemaSyncService` migrates existing tenants

### AC7 — Backend endpoint: `GET /api/v1/sync/conflicts` (Backend)

- **Given** the owner (Simon) wants to review sync conflicts
- **When** he navigates to the conflict log
- **Then** the Flutter app calls `GET /api/v1/sync/conflicts?limit=50&offset=0`
- **And** the response returns:
  ```json
  {
    "data": [
      {
        "id": "uuid",
        "operationId": "uuid",
        "operationType": "CREATE_SALE",
        "entityId": "uuid",
        "entityType": "stock_level",
        "conflictType": "STOCK_NEGATIVE",
        "strategy": "DELTA_SUM",
        "conflictData": { "productId": "...", "productName": "...", "deltaApplied": -3, "resultingStock": -1, "previousStock": 2 },
        "resolvedAt": "2026-03-22T14:30:00Z",
        "actorId": "uuid"
      }
    ]
  }
  ```
- **And** the endpoint is `@PreAuthorize("hasRole('OWNER')")` — only the tenant owner can view conflicts
- **And** the response is paginated with `limit` and `offset` query params

### AC8 — Flutter: Handle CONFLICT response from push (Flutter)

- **Given** the `RestSyncService._pushBatch()` already handles `status == 'CONFLICT'` by deleting from sync_queue
- **When** Story 5.3 is implemented
- **Then** the conflict handling is enriched:
  1. Parse `conflictData` from the response (new field)
  2. If `conflictType == 'STOCK_NEGATIVE'`:
     - Show a non-blocking SnackBar: "⚠ Conflit de stock : {productName} — stock négatif ({resultingStock}). Vérifiez l'inventaire."
     - The SnackBar is amber (#FCC419 background) with a "Voir" action button
     - "Voir" → navigates to Paramètres > Synchronisation > Conflits
  3. If `conflictType == 'LAST_WRITE_WINS'`:
     - No user notification (silent — logged for OWNER audit only)
  4. Delete from sync_queue (already done — server processed it)

### AC9 — Flutter: Conflict log screen (Flutter)

- **Given** Simon taps on the SyncIndicator → "Conflits" or navigates to Paramètres > Synchronisation > Conflits
- **When** the conflict log screen loads
- **Then** it fetches `GET /api/v1/sync/conflicts?limit=50`
- **And** displays a list of conflicts sorted by `resolvedAt` DESC:
  - Each tile shows: conflict icon (⚠ amber), entity type, conflict type (text), date/time
  - `STOCK_NEGATIVE`: "Stock négatif — {productName}: {resultingStock} unités"
  - `LAST_WRITE_WINS`: "Modification écrasée — {entityType}: dernière version conservée"
- **And** tapping a tile expands to show full details (JSON conflictData formatted as key-value pairs)
- **And** the screen is accessible only for OWNER role (hidden from EMPLOYEE nav)
- **And** empty state: "Aucun conflit de synchronisation" with a check icon

### AC10 — Integration: SyncPushService conflict handling (Backend)

- **Given** the existing `SyncPushService.processOperation()` method returns `SyncOperationResult`
- **When** Story 5.3 is implemented
- **Then** the flow becomes:
  1. Check idempotency → return DUPLICATE if exists (unchanged)
  2. Resolve handler → handler.handle(operation, actorId, tenantId) → `handlerResult` (unchanged)
  3. **NEW**: If handlerResult.status == APPLIED → evaluate conflict via `ConflictStrategyRegistry.resolve(operationType)`
  4. **NEW**: If conflict detected → enrich the result with conflictData, possibly change status to CONFLICT
  5. **NEW**: If conflict detected → persist to `sync_conflicts_log`
  6. Log to `sync_operations_log` (unchanged)
  7. Publish `SyncOperationProcessedEvent` (unchanged)
- **And** if no ConflictResolutionStrategy is registered for the operation type → treat as APPEND_ONLY (no conflict evaluation)
- **And** the conflict evaluation runs AFTER the handler successfully applied the operation (not before or instead of)

### AC11 — Backend: Enrich push response with conflictData (Backend)

- **Given** the `SyncOperationResult` record currently has: `operationId`, `status`, `serverEntityId`, `reason`
- **When** Story 5.3 is implemented
- **Then** `SyncOperationResult` is extended with:
  ```java
  public record SyncOperationResult(
      String operationId,
      SyncOperationStatus status,
      String serverEntityId,
      String reason,
      Map<String, Object> conflictData  // NEW — null if no conflict
  ) {}
  ```
- **And** `SyncPushResponseDto` / `SyncOperationResultDto` include the `conflictData` map in the JSON response
- **And** existing tests are updated to include the new field (backward compatible — null when no conflict)

### AC12 — DeltaStockStrategy: post-application stock check (Backend)

- **Given** a stock-related operation was applied by its handler
- **When** `DeltaStockStrategy.evaluate()` runs
- **Then** it queries the current stock level for the affected product+store:
  - Extract `productId` and `storeId` from the operation payload
  - Query `SELECT quantity FROM stock_levels WHERE product_id = ? AND store_id = ?`
- **And** if `quantity < 0`:
  - Return `ConflictResult("STOCK_NEGATIVE", "DELTA_SUM", { productId, productName, storeId, deltaApplied, resultingStock, previousStock })`
  - `previousStock` = `resultingStock - deltaApplied` (reverse calculation)
  - `productName` is resolved via a product name lookup (or included in the original payload)
- **And** if `quantity >= 0`:
  - Return `ConflictResult(null, "DELTA_SUM", null)` — no conflict
- **And** a `StockConflictResolvedEvent` is always published (even when no conflict — for audit completeness)

### AC13 — LastWriteWinsStrategy: overwrite detection (Backend)

- **Given** a non-stock entity update was applied by its handler
- **When** `LastWriteWinsStrategy.evaluate()` runs
- **Then** it checks `sync_operations_log` for previous operations on the same `entityId`:
  - Query: `SELECT client_timestamp FROM sync_operations_log WHERE entity_id = ? AND status = 'APPLIED' AND id != ? ORDER BY client_timestamp DESC LIMIT 1`
  - If a previous operation exists AND this operation's `clientTimestamp` > previous `client_timestamp`:
    - This operation overwrote the previous one → log `SyncOverwrittenEvent`
    - Return `ConflictResult("LAST_WRITE_WINS", "LAST_WRITE_WINS", { entityType, entityId, overwrittenTimestamp, winnerTimestamp })`
  - If no previous operation or timestamps are in order → return null (no conflict)
- **And** the result status stays `APPLIED` (LWW never returns CONFLICT — the operation is always applied)
- **And** the `SyncOverwrittenEvent` is persisted in `sync_conflicts_log` for audit

### AC14 — Tests TDD obligatoires : RED → GREEN

#### Backend (JUnit 5)

- `ConflictResolutionStrategyTest.java` — interface contract
- `DeltaStockStrategyTest.java` — no conflict when stock >= 0, STOCK_NEGATIVE when < 0, correct conflictData fields
- `LastWriteWinsStrategyTest.java` — no conflict when first update, LWW detected when overwrite, correct event data
- `AppendOnlyStrategyTest.java` — always returns null (no conflict)
- `ConflictStrategyRegistryTest.java` — correct strategy resolution per operation type, fallback to append-only
- `SyncPushServiceConflictTest.java` — conflict evaluation after handler APPLIED, conflictData in result, log persisted
- `SyncConflictsLogRepositoryTest.java` — save, findByTenant paginated, findByEntityId
- `SyncControllerConflictsTest.java` — GET /sync/conflicts: 200 OWNER, 403 EMPLOYEE, 401 no auth, pagination
- `SyncOperationResultConflictDataTest.java` — SyncOperationResult with conflictData serialization
- `StockConflictResolvedEventTest.java` — event creation, all fields populated
- `SyncOverwrittenEventTest.java` — event creation, all fields populated

#### Flutter (flutter_test)

- `conflict_handler_test.dart` — parse conflictData from push response, STOCK_NEGATIVE triggers snackbar, LWW silent
- `sync_conflict_log_page_test.dart` — fetches /sync/conflicts, displays list, empty state, OWNER-only
- `sync_conflict_model_test.dart` — SyncConflict model from JSON, conflictData parsing
- `conflict_snackbar_test.dart` — amber snackbar displayed, "Voir" action navigates to conflicts page

---

## Tasks / Subtasks

> **LOI TDD : Tous les tests RED sont écrits AVANT tout code de production. Le test doit échouer en premier.**

---

### Task 1 — TDD RED: Tests backend domain model (AC5, AC11, AC12, AC13)

- [ ] **1.1** Créer `ConflictResultTest.java` — domain model record
  ```java
  // sync/sync/domain/model/ConflictResultTest.java
  // conflictResult_create_setsAllFields()
  // conflictResult_noConflict_nullConflictType()
  // conflictResult_stockNegative_hasConflictData()
  ```

- [ ] **1.2** Créer `StockConflictResolvedEventTest.java` — domain event
  ```java
  // sync/sync/domain/model/StockConflictResolvedEventTest.java
  // event_create_setsAllFields()
  // event_negativeStock_includesDeltaAndResult()
  // event_positiveStock_noConflictType()
  ```

- [ ] **1.3** Créer `SyncOverwrittenEventTest.java` — domain event
  ```java
  // sync/sync/domain/model/SyncOverwrittenEventTest.java
  // event_create_setsAllFields()
  // event_hasOverwrittenAndWinnerTimestamps()
  ```

- [ ] **1.4** Créer `SyncConflictsLogEntryTest.java` — domain model record
  ```java
  // sync/sync/domain/model/SyncConflictsLogEntryTest.java
  // entry_create_setsAllFields()
  // entry_conflictData_isJsonbMap()
  ```

- [ ] **1.5** Mettre à jour `SyncOperationResultTest.java` — add conflictData field
  ```java
  // Add test: operationResult_withConflictData_preservesMap()
  // Add test: operationResult_withoutConflictData_nullField()
  ```

### Task 2 — TDD RED: Tests backend strategy implementations (AC1, AC2, AC3, AC4, AC5)

- [ ] **2.1** Créer `DeltaStockStrategyTest.java`
  ```java
  // sync/sync/application/strategy/DeltaStockStrategyTest.java
  // evaluate_stockPositive_returnsNoConflict()
  // evaluate_stockZero_returnsNoConflict()
  // evaluate_stockNegative_returnsStockNegativeConflict()
  // evaluate_stockNegative_conflictDataHasCorrectFields()
  // evaluate_stockNegative_previousStockCalculatedCorrectly()
  // evaluate_missingProductId_returnsNoConflict()  // defensive: if payload lacks product info, skip
  // evaluate_handlerStatusRejected_skipsEvaluation()
  ```

- [ ] **2.2** Créer `LastWriteWinsStrategyTest.java`
  ```java
  // sync/sync/application/strategy/LastWriteWinsStrategyTest.java
  // evaluate_firstUpdate_returnsNoConflict()
  // evaluate_overwriteDetected_returnsLwwConflict()
  // evaluate_overwriteDetected_conflictDataHasTimestamps()
  // evaluate_sameTimestamp_returnsNoConflict()
  // evaluate_noEntityId_returnsNoConflict()
  // evaluate_handlerStatusRejected_skipsEvaluation()
  ```

- [ ] **2.3** Créer `AppendOnlyStrategyTest.java`
  ```java
  // sync/sync/application/strategy/AppendOnlyStrategyTest.java
  // evaluate_always_returnsNull()
  // evaluate_anyOperationType_returnsNull()
  ```

- [ ] **2.4** Créer `ConflictStrategyRegistryTest.java`
  ```java
  // sync/sync/application/strategy/ConflictStrategyRegistryTest.java
  // resolve_createSale_returnsDeltaStockStrategy()
  // resolve_stockAdjust_returnsDeltaStockStrategy()
  // resolve_recordStockEntry_returnsDeltaStockStrategy()
  // resolve_stockTransfer_returnsDeltaStockStrategy()
  // resolve_updateProduct_returnsLastWriteWinsStrategy()
  // resolve_updateClient_returnsLastWriteWinsStrategy()
  // resolve_updateSupplier_returnsLastWriteWinsStrategy()
  // resolve_createEmployee_returnsLastWriteWinsStrategy()
  // resolve_createDayClosure_returnsAppendOnlyStrategy()
  // resolve_unknownType_returnsAppendOnlyStrategy()
  ```

### Task 3 — TDD RED: Tests backend service integration (AC10)

- [ ] **3.1** Créer `SyncPushServiceConflictTest.java`
  ```java
  // sync/sync/application/service/SyncPushServiceConflictTest.java
  // pushBatch_handlerApplied_evaluatesConflict()
  // pushBatch_conflictDetected_enrichesResultWithConflictData()
  // pushBatch_conflictDetected_statusChangedToConflict()
  // pushBatch_conflictDetected_logsToSyncConflictsLog()
  // pushBatch_noConflict_resultUnchanged()
  // pushBatch_handlerRejected_skipsConflictEvaluation()
  // pushBatch_handlerDuplicate_skipsConflictEvaluation()
  // pushBatch_lwwConflict_statusStaysApplied() // LWW doesn't change status
  // pushBatch_stockNegative_statusChangesToConflict()
  ```

### Task 4 — TDD RED: Tests backend persistence (AC6)

- [ ] **4.1** Créer `SyncConflictsLogRepositoryAdapterTest.java`
  ```java
  // sync/sync/adapter/out/persistence/impl/SyncConflictsLogRepositoryAdapterTest.java
  // save_validEntry_persists()
  // findAll_paginated_returnsCorrectPage()
  // findByEntityId_returnsMatchingEntries()
  // save_conflictDataAsJsonb_preservesMap()
  ```

### Task 5 — TDD RED: Tests backend controller (AC7)

- [ ] **5.1** Créer `SyncControllerConflictsTest.java`
  ```java
  // sync/sync/adapter/in/rest/SyncControllerConflictsTest.java (@WebMvcTest)
  // GET /api/v1/sync/conflicts — 200 OWNER role
  // GET /api/v1/sync/conflicts — 403 EMPLOYEE role
  // GET /api/v1/sync/conflicts — 401 no auth
  // GET /api/v1/sync/conflicts — 200 with limit and offset params
  // GET /api/v1/sync/conflicts — 200 returns conflict entries with conflictData
  // GET /api/v1/sync/conflicts — 200 empty list when no conflicts
  ```

### Task 6 — Implémentation backend: domain model (AC5, AC11)

- [ ] **6.1** Créer `ConflictResult.java` — domain record
  ```java
  // sync/sync/domain/model/ConflictResult.java
  public record ConflictResult(
      String conflictType,               // "STOCK_NEGATIVE", "LAST_WRITE_WINS", null
      String strategy,                   // "DELTA_SUM", "LAST_WRITE_WINS", "APPEND_ONLY"
      Map<String, Object> conflictData   // details for response + logging
  ) {}
  ```

- [ ] **6.2** Créer `StockConflictResolvedEvent.java` — domain event
  ```java
  // sync/sync/domain/model/StockConflictResolvedEvent.java
  public record StockConflictResolvedEvent(
      String operationId,
      String productId,
      String storeId,
      int deltaApplied,
      int resultingStock,
      int previousStock,
      String strategy,           // "DELTA_SUM"
      boolean isNegativeConflict,
      String tenantId,
      Instant occurredAt
  ) {}
  ```

- [ ] **6.3** Créer `SyncOverwrittenEvent.java` — domain event
  ```java
  // sync/sync/domain/model/SyncOverwrittenEvent.java
  public record SyncOverwrittenEvent(
      String operationId,
      String entityType,
      String entityId,
      Instant overwrittenTimestamp,  // clientTimestamp of the operation that was overwritten
      Instant winnerTimestamp,       // clientTimestamp of the winning operation
      String tenantId,
      Instant occurredAt
  ) {}
  ```

- [ ] **6.4** Créer `SyncConflictsLogEntry.java` — domain model
  ```java
  // sync/sync/domain/model/SyncConflictsLogEntry.java
  public record SyncConflictsLogEntry(
      String id,
      String operationId,
      String operationType,
      String entityId,
      String entityType,
      String conflictType,
      String strategy,
      Map<String, Object> conflictData,
      Instant resolvedAt,
      String actorId
  ) {}
  ```

- [ ] **6.5** Modifier `SyncOperationResult.java` — add conflictData field
  ```java
  // sync/sync/domain/model/SyncOperationResult.java
  public record SyncOperationResult(
      String operationId,
      SyncOperationStatus status,
      String serverEntityId,
      String reason,
      Map<String, Object> conflictData  // NEW — null if no conflict
  ) {
      // Backward-compatible constructor (for existing code that doesn't set conflictData)
      public SyncOperationResult(String operationId, SyncOperationStatus status,
                                 String serverEntityId, String reason) {
          this(operationId, status, serverEntityId, reason, null);
      }
  }
  ```

### Task 7 — Implémentation backend: ports (AC5)

- [ ] **7.1** Créer `ConflictResolutionStrategy.java` (Strategy interface)
  ```java
  // sync/sync/domain/port/in/ConflictResolutionStrategy.java
  public interface ConflictResolutionStrategy {
      /**
       * Evaluate conflict AFTER the operation has been applied by the handler.
       * @return ConflictResult with details, or null if no conflict detected.
       */
      ConflictResult evaluate(SyncOperation operation, SyncOperationResult handlerResult,
                              UUID actorId, String tenantId);
  }
  ```

- [ ] **7.2** Créer `SyncConflictsLogRepository.java` (port out)
  ```java
  // sync/sync/domain/port/out/SyncConflictsLogRepository.java
  public interface SyncConflictsLogRepository {
      void save(SyncConflictsLogEntry entry);
      List<SyncConflictsLogEntry> findAll(int limit, int offset);
      List<SyncConflictsLogEntry> findByEntityId(String entityId);
  }
  ```

### Task 8 — Implémentation backend: strategy implementations (AC1, AC2, AC3, AC4, AC5, AC12, AC13)

- [ ] **8.1** Créer `DeltaStockStrategy.java`
  ```java
  // sync/sync/application/strategy/DeltaStockStrategy.java
  @Component
  public class DeltaStockStrategy implements ConflictResolutionStrategy {
      private final EntityManager entityManager;

      @Override
      public ConflictResult evaluate(SyncOperation operation, SyncOperationResult handlerResult,
                                     UUID actorId, String tenantId) {
          if (handlerResult.status() != SyncOperationStatus.APPLIED) return null;

          Map<String, Object> payload = operation.payload();
          String productId = extractProductId(payload, operation.operationType());
          String storeId = extractStoreId(payload, operation.operationType());
          if (productId == null || storeId == null) return null;

          // Query current stock level AFTER the handler applied the delta
          String schema = TenantContext.getCurrentSchema();
          Integer currentStock = queryCurrent stock(schema, productId, storeId);
          if (currentStock == null) return null;

          int deltaApplied = extractDelta(payload, operation.operationType());
          int previousStock = currentStock - deltaApplied;

          if (currentStock < 0) {
              Map<String, Object> conflictData = Map.of(
                  "conflictType", "STOCK_NEGATIVE",
                  "productId", productId,
                  "productName", resolveProductName(schema, productId),
                  "storeId", storeId,
                  "deltaApplied", deltaApplied,
                  "resultingStock", currentStock,
                  "previousStock", previousStock
              );
              return new ConflictResult("STOCK_NEGATIVE", "DELTA_SUM", conflictData);
          }

          return new ConflictResult(null, "DELTA_SUM", null);  // no conflict
      }

      private String extractProductId(Map<String, Object> payload, String opType) {
          // For CREATE_SALE: iterate items[0].productId (first item that caused the sale)
          // For STOCK_ADJUST/RECORD_STOCK_ENTRY: payload.productId
          // For STOCK_TRANSFER: payload.productId
      }

      private int extractDelta(Map<String, Object> payload, String opType) {
          // For CREATE_SALE: -sum(items[].quantity)
          // For STOCK_ADJUST: payload.quantity (can be positive or negative)
          // For RECORD_STOCK_ENTRY: +payload.quantity
          // For STOCK_TRANSFER: -payload.quantity (source store)
      }
  }
  ```

- [ ] **8.2** Créer `LastWriteWinsStrategy.java`
  ```java
  // sync/sync/application/strategy/LastWriteWinsStrategy.java
  @Component
  public class LastWriteWinsStrategy implements ConflictResolutionStrategy {
      private final SyncOperationsLogRepository logRepository;

      @Override
      public ConflictResult evaluate(SyncOperation operation, SyncOperationResult handlerResult,
                                     UUID actorId, String tenantId) {
          if (handlerResult.status() != SyncOperationStatus.APPLIED) return null;
          if (operation.entityId() == null) return null;

          // Check if another operation on the same entity was already applied
          // Query sync_operations_log for previous APPLIED ops on same entityId
          // If found with earlier client_timestamp → this operation overwrote it
          // → log SyncOverwrittenEvent, return ConflictResult
      }
  }
  ```

- [ ] **8.3** Créer `AppendOnlyStrategy.java`
  ```java
  // sync/sync/application/strategy/AppendOnlyStrategy.java
  @Component
  public class AppendOnlyStrategy implements ConflictResolutionStrategy {
      @Override
      public ConflictResult evaluate(SyncOperation operation, SyncOperationResult handlerResult,
                                     UUID actorId, String tenantId) {
          return null;  // No conflict possible for append-only operations
      }
  }
  ```

- [ ] **8.4** Créer `ConflictStrategyRegistry.java`
  ```java
  // sync/sync/application/strategy/ConflictStrategyRegistry.java
  @Component
  public class ConflictStrategyRegistry {
      private final Map<String, ConflictResolutionStrategy> strategies;
      private final AppendOnlyStrategy fallback;

      // Maps operation types → strategy
      private static final Set<String> DELTA_STOCK_OPS = Set.of(
          "CREATE_SALE", "STOCK_ADJUST", "RECORD_STOCK_ENTRY", "STOCK_TRANSFER");
      private static final Set<String> LWW_OPS = Set.of(
          "CREATE_PRODUCT", "PRODUCT_UPDATE", "UPDATE_PRODUCT",
          "ARCHIVE_PRODUCT", "UNARCHIVE_PRODUCT",
          "UPDATE_CLIENT", "ARCHIVE_CLIENT",
          "UPDATE_SUPPLIER", "ARCHIVE_SUPPLIER",
          "CREATE_EMPLOYEE", "REASSIGN_EMPLOYEE",
          "DEACTIVATE_EMPLOYEE", "REACTIVATE_EMPLOYEE");

      public ConflictStrategyRegistry(DeltaStockStrategy deltaStock,
                                      LastWriteWinsStrategy lww,
                                      AppendOnlyStrategy appendOnly) {
          this.fallback = appendOnly;
          this.strategies = new HashMap<>();
          DELTA_STOCK_OPS.forEach(op -> strategies.put(op, deltaStock));
          LWW_OPS.forEach(op -> strategies.put(op, lww));
      }

      public ConflictResolutionStrategy resolve(String operationType) {
          return strategies.getOrDefault(operationType, fallback);
      }
  }
  ```

### Task 9 — Implémentation backend: intégration dans SyncPushService (AC10)

- [ ] **9.1** Modifier `SyncPushService.processOperation()` — add conflict evaluation
  ```java
  // In SyncPushService.java:
  // After handler.handle() returns APPLIED:
  //   1. conflictRegistry.resolve(operationType) → strategy
  //   2. strategy.evaluate(operation, handlerResult, actorId, tenantId) → conflictResult
  //   3. If conflictResult.conflictType() == "STOCK_NEGATIVE":
  //        → new SyncOperationResult(opId, CONFLICT, serverId, "STOCK_NEGATIVE", conflictData)
  //   4. If conflictResult.conflictType() == "LAST_WRITE_WINS":
  //        → keep APPLIED status but add conflictData to result
  //   5. If conflictResult != null → persist SyncConflictsLogEntry
  //   6. Publish appropriate domain event
  ```

### Task 10 — Implémentation backend: persistence adapters (AC6)

- [ ] **10.1** Créer `SyncConflictsLogJpaEntity.java`
  ```java
  // sync/sync/adapter/out/persistence/entity/SyncConflictsLogJpaEntity.java
  @Entity @Table(name = "sync_conflicts_log")
  // Fields: id (String PK), operationId, operationType, entityId, entityType,
  //         conflictType, strategy, conflictData (JSONB → Map<String, Object>),
  //         resolvedAt, actorId
  ```

- [ ] **10.2** Créer `SyncConflictsLogSpringRepository.java`
  ```java
  // sync/sync/adapter/out/persistence/jpa/SyncConflictsLogSpringRepository.java
  @Repository
  public interface SyncConflictsLogSpringRepository
      extends JpaRepository<SyncConflictsLogJpaEntity, String> {
      List<SyncConflictsLogJpaEntity> findAllByOrderByResolvedAtDesc(Pageable pageable);
      List<SyncConflictsLogJpaEntity> findByEntityId(String entityId);
  }
  ```

- [ ] **10.3** Créer `SyncConflictsLogRepositoryAdapter.java`
  ```java
  // sync/sync/adapter/out/persistence/impl/SyncConflictsLogRepositoryAdapter.java
  @Component implements SyncConflictsLogRepository
  ```

- [ ] **10.4** DDL dans `TenantSchemaProvisioner.java` (AC6 SQL)

- [ ] **10.5** Migration dans `TenantSchemaSyncService` pour tenants existants

### Task 11 — Implémentation backend: REST controller + DTOs (AC7, AC11)

- [ ] **11.1** Ajouter `GET /sync/conflicts` dans `SyncController.java`
  ```java
  @GetMapping("/conflicts")
  @PreAuthorize("hasRole('OWNER')")
  @Operation(summary = "List sync conflict log",
             description = "Returns paginated list of sync conflicts for OWNER review.")
  public ResponseEntity<ApiResponseWrapper<List<SyncConflictDto>>> getConflicts(
          @RequestParam(defaultValue = "50") int limit,
          @RequestParam(defaultValue = "0") int offset) {
      // Delegate to SyncConflictsLogRepository
  }
  ```

- [ ] **11.2** Créer `SyncConflictDto.java`
  ```java
  // sync/sync/adapter/in/rest/dto/SyncConflictDto.java
  public record SyncConflictDto(
      String id,
      String operationId,
      String operationType,
      String entityId,
      String entityType,
      String conflictType,
      String strategy,
      Map<String, Object> conflictData,
      Instant resolvedAt,
      String actorId
  ) {}
  ```

- [ ] **11.3** Modifier `SyncOperationResultDto` — add conflictData field
  ```java
  // sync/sync/adapter/in/rest/dto/SyncOperationResultDto.java
  public record SyncOperationResultDto(
      String operationId,
      String status,
      String serverEntityId,
      String reason,
      Map<String, Object> conflictData  // NEW — null if no conflict
  ) {}
  ```

- [ ] **11.4** Modifier `SyncPushResponseDto.from()` — map conflictData from domain to DTO

### Task 12 — Implémentation backend: SyncOperationsLogRepository extension (AC13)

- [ ] **12.1** Ajouter `findPreviousAppliedByEntityId()` au `SyncOperationsLogRepository` port
  ```java
  // sync/sync/domain/port/out/SyncOperationsLogRepository.java
  // NEW method for LWW detection:
  Optional<SyncOperationsLogEntry> findPreviousAppliedByEntityId(
      String entityId, String excludeOperationId);
  ```

- [ ] **12.2** Implémenter dans `SyncOperationsLogRepositoryAdapter`
  ```java
  // sync/sync/adapter/out/persistence/impl/SyncOperationsLogRepositoryAdapter.java
  // Query: SELECT * FROM sync_operations_log WHERE entity_id = ? AND status = 'APPLIED'
  //        AND id != ? ORDER BY client_timestamp DESC LIMIT 1
  ```

### Task 13 — TDD RED: Tests Flutter (AC8, AC9)

- [ ] **13.1** Créer `conflict_handler_test.dart`
  ```dart
  // test/core/sync/conflict_handler_test.dart
  // handleConflictResponse_stockNegative_showsSnackbar()
  // handleConflictResponse_lastWriteWins_silent()
  // handleConflictResponse_noConflictData_noAction()
  // handleConflictResponse_parsesConflictDataCorrectly()
  ```

- [ ] **13.2** Créer `sync_conflict_model_test.dart`
  ```dart
  // test/core/sync/sync_conflict_model_test.dart
  // fromJson_validStockNegative_parsesCorrectly()
  // fromJson_validLww_parsesCorrectly()
  // fromJson_missingFields_handlesGracefully()
  ```

- [ ] **13.3** Créer `sync_conflict_log_page_test.dart`
  ```dart
  // test/features/sync_indicator/presentation/page/sync_conflict_log_page_test.dart
  // page_loadsConflicts_displaysListCorrectly()
  // page_emptyConflicts_showsEmptyState()
  // page_stockNegative_showsCorrectIcon()
  // page_lww_showsCorrectLabel()
  // page_tapConflict_expandsDetails()
  ```

- [ ] **13.4** Créer `conflict_snackbar_test.dart`
  ```dart
  // test/features/sync_indicator/presentation/widget/conflict_snackbar_test.dart
  // snackbar_stockNegative_amberBackground()
  // snackbar_stockNegative_showsProductName()
  // snackbar_voirAction_navigatesToConflictsPage()
  ```

### Task 14 — Implémentation Flutter: domain model (AC8, AC9)

- [ ] **14.1** Créer `sync_conflict.dart` — domain model
  ```dart
  // core/sync/domain/sync_conflict.dart
  class SyncConflict {
    final String id;
    final String operationId;
    final String operationType;
    final String? entityId;
    final String? entityType;
    final String conflictType;
    final String strategy;
    final Map<String, dynamic>? conflictData;
    final DateTime resolvedAt;
    final String? actorId;

    SyncConflict({...});

    factory SyncConflict.fromJson(Map<String, dynamic> json) => ...;

    String get displayTitle {
      switch (conflictType) {
        case 'STOCK_NEGATIVE':
          final productName = conflictData?['productName'] ?? 'Produit';
          final resultingStock = conflictData?['resultingStock'] ?? '?';
          return 'Stock négatif — $productName: $resultingStock unités';
        case 'LAST_WRITE_WINS':
          return 'Modification écrasée — ${entityType ?? 'Entité'}: dernière version conservée';
        default:
          return 'Conflit résolu — $conflictType';
      }
    }
  }
  ```

- [ ] **14.2** Créer `sync_conflict_provider.dart` — Riverpod provider
  ```dart
  // core/sync/presentation/provider/sync_conflict_provider.dart
  @riverpod
  Future<List<SyncConflict>> syncConflicts(Ref ref) async {
    final dio = ref.watch(dioProvider);
    final response = await dio.get('/api/v1/sync/conflicts', queryParameters: {'limit': 50});
    final list = (response.data['data'] as List).cast<Map<String, dynamic>>();
    return list.map(SyncConflict.fromJson).toList();
  }
  ```

### Task 15 — Implémentation Flutter: enrichir le push conflict handling (AC8)

- [ ] **15.1** Modifier `RestSyncService._pushBatch()` — parse conflictData
  ```dart
  // In rest_sync_service.dart, _pushBatch method:
  // Existing CONFLICT handling:
  //   await (_database.delete(_database.syncQueue)
  //         ..where((t) => t.id.equals(opId))).go();
  //
  // ADD: Collect all conflicts with conflictData for UI notification
  // Return list of conflicts from _pushBatch for the caller to display
  ```

- [ ] **15.2** Créer `ConflictNotificationService` — display non-blocking notifications
  ```dart
  // core/sync/conflict_notification_service.dart
  // showStockNegativeSnackbar(productName, resultingStock) → amber SnackBar with "Voir" action
  // Called from SyncTriggerNotifier after push returns conflicts
  ```

- [ ] **15.3** Modifier `SyncTriggerNotifier.triggerSync()` — handle push conflicts
  ```dart
  // After push() returns, check for conflicts in the response
  // Display snackbar for STOCK_NEGATIVE conflicts
  // Silent for LAST_WRITE_WINS conflicts
  ```

### Task 16 — Implémentation Flutter: Conflict Log Page (AC9)

- [ ] **16.1** Créer `sync_conflict_log_page.dart`
  ```dart
  // features/sync_indicator/presentation/page/sync_conflict_log_page.dart
  // ConsumerWidget that:
  //   1. Fetches syncConflictsProvider
  //   2. Displays as a list of ExpansionTile entries
  //   3. Each tile: icon (amber ⚠), title (displayTitle), subtitle (resolvedAt formatted)
  //   4. Expanded: formatted key-value pairs from conflictData
  //   5. Empty state: "Aucun conflit de synchronisation" + ✅ icon
  //   6. OWNER-only access (check user role)
  ```

- [ ] **16.2** Ajouter route `/settings/sync/conflicts` dans le router
  ```dart
  // In router configuration — add route for conflict log page
  // Accessible from:
  //   1. Settings > Synchronisation > Conflits
  //   2. SyncIndicator bottom sheet → "Conflits (N)" link
  //   3. Stock negative SnackBar "Voir" action
  ```

- [ ] **16.3** Modifier `sync_detail_bottom_sheet.dart` — add conflicts link
  ```dart
  // In sync_detail_bottom_sheet.dart:
  // Add a row showing "Conflits: N" (count from syncConflictsProvider)
  // Tap → navigate to /settings/sync/conflicts
  // Show only if user is OWNER
  ```

### Task 17 — Mettre à jour les tests existants (backward compatibility)

- [ ] **17.1** Mettre à jour `SyncPushServiceTest.java` — add conflictData null in existing assertions
  ```java
  // All existing SyncPushServiceTest assertions that check SyncOperationResult
  // must now account for the 5th field (conflictData = null for non-conflict operations)
  ```

- [ ] **17.2** Mettre à jour `AbstractSyncOperationHandler` subclass tests
  ```java
  // SaleSyncHandlerTest, ProductSyncHandlerTest, etc.
  // SyncOperationResult constructor now has 5 fields — update all existing assertions
  ```

- [ ] **17.3** Mettre à jour `SyncControllerTest.java` — push response includes conflictData field
  ```java
  // Response DTO now includes conflictData (null for non-conflict)
  // Verify JSON serialization includes/excludes null conflictData appropriately
  ```

- [ ] **17.4** Mettre à jour `rest_sync_service_push_test.dart` — Flutter push response parsing
  ```dart
  // push_conflictResults_parsesConflictData()
  // push_appliedResults_noConflictData()
  ```

### Task 18 — Exécuter tous les tests et itérer RED→GREEN

- [ ] **18.1** Backend: `mvn test` — 0 failures, 0 errors
- [ ] **18.2** Flutter: `flutter test --reporter=expanded` — All tests passed
- [ ] **18.3** Curl integration tests — All ✅

---

## cURL Integration Tests

```bash
#!/usr/bin/env bash
# ======================================================
# Story 5.3 — cURL Integration Tests: Conflict Resolution
# Run: bash curl-tests-story-5-3.sh
# Pre-requisite: backend running on localhost:8443
# All steps must show ✅ before story is marked done
# ======================================================
set -euo pipefail
BASE_URL="http://localhost:8443"

echo "=== Story 5.3 — Conflict Resolution Integration Tests ==="
echo ""

# ─── Step 1: Register a fresh test user and get JWT ─────────
PHONE="+237600053$(date +%S%N | head -c 3)"
REGISTER=$(curl -s -X POST "$BASE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"phone\":\"$PHONE\",\"password\":\"Test1234!\",\"firstName\":\"ConflictTest\",\"lastName\":\"User\"}")
echo "Register response: $(echo "$REGISTER" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('message',''))" 2>/dev/null)"

LOGIN_TOKEN=$(echo "$REGISTER" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('loginToken',d.get('data',{}).get('accessToken','')))" 2>/dev/null || echo "")

if echo "$REGISTER" | python3 -c "import sys,json; d=json.load(sys.stdin); ms=d.get('data',{}).get('memberships',[]); exit(0 if len(ms)>0 else 1)" 2>/dev/null; then
  TENANT_CODE=$(echo "$REGISTER" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['memberships'][0]['tenantCode'])")
  SELECT=$(curl -s -X POST "$BASE_URL/api/v1/auth/select-tenant" \
    -H "Content-Type: application/json" \
    -d "{\"loginToken\":\"$LOGIN_TOKEN\",\"tenantCode\":\"$TENANT_CODE\"}")
  JWT=$(echo "$SELECT" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
else
  JWT="$LOGIN_TOKEN"
fi

[[ -n "$JWT" && "$JWT" != "null" && "$JWT" != "" ]] && echo "✅ Step 1 — JWT obtained" || { echo "❌ Step 1 FAILED — no JWT"; exit 1; }

# ─── Step 2: Create a product with stock ─────────
PRODUCT_RESP=$(curl -s -X POST "$BASE_URL/api/v1/products" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"name":"ConflictTestProduct","price":5000,"buyPrice":2500}')
PRODUCT_ID=$(echo "$PRODUCT_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('id',''))" 2>/dev/null || echo "")
echo "Created product: $PRODUCT_ID"
[[ -n "$PRODUCT_ID" && "$PRODUCT_ID" != "" ]] && echo "✅ Step 2 — Product created" || { echo "❌ Step 2 FAILED"; exit 1; }

# ─── Step 3: Get primary store ─────────
STORES_RESP=$(curl -s -X GET "$BASE_URL/api/v1/stores" \
  -H "Authorization: Bearer $JWT")
STORE_ID=$(echo "$STORES_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); stores=d.get('data',[]); print(stores[0]['id'] if stores else '')" 2>/dev/null || echo "")
[[ -n "$STORE_ID" ]] && echo "✅ Step 3 — Store obtained: $STORE_ID" || { echo "❌ Step 3 FAILED — no store"; exit 1; }

# ─── Step 4: Add initial stock (5 units) ─────────
STOCK_OP_ID=$(python3 -c "import uuid; print(uuid.uuid4())")
STOCK_PUSH=$(curl -s -X POST "$BASE_URL/api/v1/sync/push" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"deviceId\": \"$(python3 -c 'import uuid; print(uuid.uuid4())')\",
    \"operations\": [{
      \"operationId\": \"$STOCK_OP_ID\",
      \"operationType\": \"RECORD_STOCK_ENTRY\",
      \"entityId\": \"$PRODUCT_ID\",
      \"payload\": {\"productId\":\"$PRODUCT_ID\", \"storeId\":\"$STORE_ID\", \"quantity\":5, \"notes\":\"initial stock\"},
      \"clientTimestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"
    }]
  }")
STOCK_STATUS=$(echo "$STOCK_PUSH" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('results',[])[0].get('status',''))" 2>/dev/null || echo "")
[[ "$STOCK_STATUS" == "APPLIED" ]] && echo "✅ Step 4 — Initial stock added (5 units)" || { echo "❌ Step 4 FAILED (status=$STOCK_STATUS)"; exit 1; }

sleep 1

# ─── Step 5: Simulate Device A sells 3 units (offline push) ─────────
SALE_A_OP=$(python3 -c "import uuid; print(uuid.uuid4())")
SALE_A_ID=$(python3 -c "import uuid; print(uuid.uuid4())")
SALE_A_TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
PUSH_A=$(curl -s -X POST "$BASE_URL/api/v1/sync/push" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"deviceId\": \"device-a-$(python3 -c 'import uuid; print(uuid.uuid4())')\",
    \"operations\": [{
      \"operationId\": \"$SALE_A_OP\",
      \"operationType\": \"CREATE_SALE\",
      \"entityId\": \"$SALE_A_ID\",
      \"payload\": {
        \"saleId\":\"$SALE_A_ID\", \"storeId\":\"$STORE_ID\", \"paymentMode\":\"CASH\",
        \"totalAmount\":15000, \"discountAmount\":0,
        \"items\":[{\"productId\":\"$PRODUCT_ID\", \"productName\":\"ConflictTestProduct\",
                    \"catalogueUnitPrice\":5000, \"appliedUnitPrice\":5000, \"quantity\":3, \"subtotal\":15000}]
      },
      \"clientTimestamp\": \"$SALE_A_TS\"
    }]
  }")
STATUS_A=$(echo "$PUSH_A" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('results',[])[0].get('status',''))" 2>/dev/null || echo "")
echo "Device A sale status: $STATUS_A"
[[ "$STATUS_A" == "APPLIED" ]] && echo "✅ Step 5 — Device A sale (3 units) applied" || { echo "❌ Step 5 FAILED (status=$STATUS_A)"; exit 1; }

sleep 1

# ─── Step 6: Simulate Device B sells 3 units (offline push → should cause negative stock) ─────────
SALE_B_OP=$(python3 -c "import uuid; print(uuid.uuid4())")
SALE_B_ID=$(python3 -c "import uuid; print(uuid.uuid4())")
SALE_B_TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
PUSH_B=$(curl -s -X POST "$BASE_URL/api/v1/sync/push" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"deviceId\": \"device-b-$(python3 -c 'import uuid; print(uuid.uuid4())')\",
    \"operations\": [{
      \"operationId\": \"$SALE_B_OP\",
      \"operationType\": \"CREATE_SALE\",
      \"entityId\": \"$SALE_B_ID\",
      \"payload\": {
        \"saleId\":\"$SALE_B_ID\", \"storeId\":\"$STORE_ID\", \"paymentMode\":\"CASH\",
        \"totalAmount\":15000, \"discountAmount\":0,
        \"items\":[{\"productId\":\"$PRODUCT_ID\", \"productName\":\"ConflictTestProduct\",
                    \"catalogueUnitPrice\":5000, \"appliedUnitPrice\":5000, \"quantity\":3, \"subtotal\":15000}]
      },
      \"clientTimestamp\": \"$SALE_B_TS\"
    }]
  }")
STATUS_B=$(echo "$PUSH_B" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('results',[])[0].get('status',''))" 2>/dev/null || echo "")
CONFLICT_TYPE_B=$(echo "$PUSH_B" | python3 -c "import sys,json; d=json.load(sys.stdin); r=d.get('data',{}).get('results',[])[0]; print(r.get('conflictData',{}).get('conflictType','none') if r.get('conflictData') else 'none')" 2>/dev/null || echo "none")
RESULTING_STOCK=$(echo "$PUSH_B" | python3 -c "import sys,json; d=json.load(sys.stdin); r=d.get('data',{}).get('results',[])[0]; print(r.get('conflictData',{}).get('resultingStock','?') if r.get('conflictData') else '?')" 2>/dev/null || echo "?")
echo "Device B sale status: $STATUS_B, conflictType: $CONFLICT_TYPE_B, resultingStock: $RESULTING_STOCK"
[[ "$STATUS_B" == "CONFLICT" && "$CONFLICT_TYPE_B" == "STOCK_NEGATIVE" ]] && echo "✅ Step 6 — Device B sale returns CONFLICT with STOCK_NEGATIVE (stock=$RESULTING_STOCK)" || { echo "❌ Step 6 FAILED (status=$STATUS_B, conflict=$CONFLICT_TYPE_B)"; exit 1; }

# ─── Step 7: GET /sync/conflicts — should show the stock conflict ─────────
CONFLICTS=$(curl -s -w "\n%{http_code}" -X GET "$BASE_URL/api/v1/sync/conflicts?limit=50" \
  -H "Authorization: Bearer $JWT")
HTTP_CONFLICTS=$(echo "$CONFLICTS" | tail -1)
BODY_CONFLICTS=$(echo "$CONFLICTS" | sed '$d')
CONFLICT_COUNT=$(echo "$BODY_CONFLICTS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d.get('data',[])))" 2>/dev/null || echo "0")
echo "Conflicts count: $CONFLICT_COUNT"
[[ "$HTTP_CONFLICTS" == "200" && "$CONFLICT_COUNT" -ge 1 ]] && echo "✅ Step 7 — Conflict log has entries (count=$CONFLICT_COUNT)" || { echo "❌ Step 7 FAILED (HTTP $HTTP_CONFLICTS, count=$CONFLICT_COUNT)"; exit 1; }

# ─── Step 8: Verify conflict entry details ─────────
FIRST_CONFLICT_TYPE=$(echo "$BODY_CONFLICTS" | python3 -c "import sys,json; d=json.load(sys.stdin); entries=d.get('data',[]); print(entries[0].get('conflictType','') if entries else '')" 2>/dev/null || echo "")
FIRST_STRATEGY=$(echo "$BODY_CONFLICTS" | python3 -c "import sys,json; d=json.load(sys.stdin); entries=d.get('data',[]); print(entries[0].get('strategy','') if entries else '')" 2>/dev/null || echo "")
echo "First conflict: type=$FIRST_CONFLICT_TYPE, strategy=$FIRST_STRATEGY"
[[ "$FIRST_CONFLICT_TYPE" == "STOCK_NEGATIVE" && "$FIRST_STRATEGY" == "DELTA_SUM" ]] && echo "✅ Step 8 — Conflict entry has correct type and strategy" || { echo "❌ Step 8 FAILED"; exit 1; }

# ─── Step 9: Test LWW — update same product from two "devices" ─────────
UPDATE_A_OP=$(python3 -c "import uuid; print(uuid.uuid4())")
UPDATE_B_OP=$(python3 -c "import uuid; print(uuid.uuid4())")
# Device A update (earlier timestamp)
curl -s -X POST "$BASE_URL/api/v1/sync/push" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"deviceId\": \"device-a-lww\",
    \"operations\": [{
      \"operationId\": \"$UPDATE_A_OP\",
      \"operationType\": \"UPDATE_PRODUCT\",
      \"entityId\": \"$PRODUCT_ID\",
      \"payload\": {\"productId\":\"$PRODUCT_ID\", \"name\":\"NameFromDeviceA\", \"price\":6000, \"buyPrice\":3000},
      \"clientTimestamp\": \"2026-03-22T10:00:00Z\"
    }]
  }" > /dev/null

sleep 1

# Device B update (later timestamp — should win)
PUSH_LWW=$(curl -s -X POST "$BASE_URL/api/v1/sync/push" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"deviceId\": \"device-b-lww\",
    \"operations\": [{
      \"operationId\": \"$UPDATE_B_OP\",
      \"operationType\": \"UPDATE_PRODUCT\",
      \"entityId\": \"$PRODUCT_ID\",
      \"payload\": {\"productId\":\"$PRODUCT_ID\", \"name\":\"NameFromDeviceB\", \"price\":7000, \"buyPrice\":3500},
      \"clientTimestamp\": \"2026-03-22T11:00:00Z\"
    }]
  }")
STATUS_LWW=$(echo "$PUSH_LWW" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('results',[])[0].get('status',''))" 2>/dev/null || echo "")
echo "LWW update status: $STATUS_LWW"
[[ "$STATUS_LWW" == "APPLIED" ]] && echo "✅ Step 9 — LWW: Device B update applied (later timestamp wins)" || { echo "❌ Step 9 FAILED (status=$STATUS_LWW)"; exit 1; }

# ─── Step 10: Verify LWW was logged in conflicts ─────────
sleep 1
CONFLICTS_LWW=$(curl -s -X GET "$BASE_URL/api/v1/sync/conflicts?limit=50" \
  -H "Authorization: Bearer $JWT")
LWW_LOGGED=$(echo "$CONFLICTS_LWW" | python3 -c "
import sys,json
d = json.load(sys.stdin)
entries = d.get('data',[])
lww = [e for e in entries if e.get('conflictType') == 'LAST_WRITE_WINS']
print(len(lww))
" 2>/dev/null || echo "0")
echo "LWW conflict entries: $LWW_LOGGED"
[[ "$LWW_LOGGED" -ge 1 ]] && echo "✅ Step 10 — LWW overwrite logged in conflicts" || echo "⚠ Step 10 — LWW not in conflicts (acceptable if logged only as event)"

# ─── Step 11: GET /sync/conflicts — 403 for EMPLOYEE role ─────────
# Create employee for test
EMP_PHONE="+237611053$(date +%S%N | head -c 3)"
EMP_RESP=$(curl -s -X POST "$BASE_URL/api/v1/employees" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"firstName\":\"TestEmp\",\"lastName\":\"Conflict\",\"phone\":\"$EMP_PHONE\",\"storeId\":\"$STORE_ID\"}")
EMP_PWD=$(echo "$EMP_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('generatedPassword',''))" 2>/dev/null || echo "")

if [[ -n "$EMP_PWD" && "$EMP_PWD" != "" ]]; then
  # Login as employee
  EMP_LOGIN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"phone\":\"$EMP_PHONE\",\"password\":\"$EMP_PWD\"}")
  EMP_TOKEN=$(echo "$EMP_LOGIN" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('loginToken',d.get('data',{}).get('accessToken','')))" 2>/dev/null || echo "")
  
  if echo "$EMP_LOGIN" | python3 -c "import sys,json; d=json.load(sys.stdin); ms=d.get('data',{}).get('memberships',[]); exit(0 if len(ms)>0 else 1)" 2>/dev/null; then
    EMP_TC=$(echo "$EMP_LOGIN" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['memberships'][0]['tenantCode'])")
    EMP_SELECT=$(curl -s -X POST "$BASE_URL/api/v1/auth/select-tenant" \
      -H "Content-Type: application/json" \
      -d "{\"loginToken\":\"$EMP_TOKEN\",\"tenantCode\":\"$EMP_TC\"}")
    EMP_JWT=$(echo "$EMP_SELECT" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
  else
    EMP_JWT="$EMP_TOKEN"
  fi

  if [[ -n "$EMP_JWT" && "$EMP_JWT" != "null" ]]; then
    EMP_CONFLICTS=$(curl -s -w "\n%{http_code}" -X GET "$BASE_URL/api/v1/sync/conflicts" \
      -H "Authorization: Bearer $EMP_JWT")
    HTTP_EMP=$(echo "$EMP_CONFLICTS" | tail -1)
    [[ "$HTTP_EMP" == "403" ]] && echo "✅ Step 11 — EMPLOYEE gets 403 on conflicts endpoint" || echo "⚠ Step 11 — Expected 403, got $HTTP_EMP (employee may need password change)"
  else
    echo "⚠ Step 11 — SKIPPED (employee JWT not obtained)"
  fi
else
  echo "⚠ Step 11 — SKIPPED (employee creation failed)"
fi

# ─── Step 12: Verify push with non-conflicting sale (stock positive) ─────────
# First add more stock
ADD_STOCK_OP=$(python3 -c "import uuid; print(uuid.uuid4())")
curl -s -X POST "$BASE_URL/api/v1/sync/push" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"deviceId\": \"restock-device\",
    \"operations\": [{
      \"operationId\": \"$ADD_STOCK_OP\",
      \"operationType\": \"RECORD_STOCK_ENTRY\",
      \"entityId\": \"$PRODUCT_ID\",
      \"payload\": {\"productId\":\"$PRODUCT_ID\", \"storeId\":\"$STORE_ID\", \"quantity\":20, \"notes\":\"restock\"},
      \"clientTimestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"
    }]
  }" > /dev/null

sleep 1

SAFE_SALE_OP=$(python3 -c "import uuid; print(uuid.uuid4())")
SAFE_SALE_ID=$(python3 -c "import uuid; print(uuid.uuid4())")
PUSH_SAFE=$(curl -s -X POST "$BASE_URL/api/v1/sync/push" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"deviceId\": \"safe-device\",
    \"operations\": [{
      \"operationId\": \"$SAFE_SALE_OP\",
      \"operationType\": \"CREATE_SALE\",
      \"entityId\": \"$SAFE_SALE_ID\",
      \"payload\": {
        \"saleId\":\"$SAFE_SALE_ID\", \"storeId\":\"$STORE_ID\", \"paymentMode\":\"CASH\",
        \"totalAmount\":5000, \"discountAmount\":0,
        \"items\":[{\"productId\":\"$PRODUCT_ID\", \"productName\":\"ConflictTestProduct\",
                    \"catalogueUnitPrice\":5000, \"appliedUnitPrice\":5000, \"quantity\":1, \"subtotal\":5000}]
      },
      \"clientTimestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"
    }]
  }")
STATUS_SAFE=$(echo "$PUSH_SAFE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('results',[])[0].get('status',''))" 2>/dev/null || echo "")
CONFLICT_SAFE=$(echo "$PUSH_SAFE" | python3 -c "import sys,json; d=json.load(sys.stdin); r=d.get('data',{}).get('results',[])[0]; print('yes' if r.get('conflictData') else 'no')" 2>/dev/null || echo "?")
echo "Safe sale status: $STATUS_SAFE, hasConflictData: $CONFLICT_SAFE"
[[ "$STATUS_SAFE" == "APPLIED" && "$CONFLICT_SAFE" == "no" ]] && echo "✅ Step 12 — Non-conflicting sale returns APPLIED with no conflictData" || { echo "❌ Step 12 FAILED (status=$STATUS_SAFE, conflict=$CONFLICT_SAFE)"; exit 1; }

# ─── Step 13: Append-only test — CREATE_DAY_CLOSURE never conflicts ─────────
CLOSURE_OP=$(python3 -c "import uuid; print(uuid.uuid4())")
CLOSURE_ID=$(python3 -c "import uuid; print(uuid.uuid4())")
PUSH_CLOSURE=$(curl -s -X POST "$BASE_URL/api/v1/sync/push" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"deviceId\": \"closure-device\",
    \"operations\": [{
      \"operationId\": \"$CLOSURE_OP\",
      \"operationType\": \"CREATE_DAY_CLOSURE\",
      \"entityId\": \"$CLOSURE_ID\",
      \"payload\": {\"storeId\":\"$STORE_ID\", \"closedAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\", \"totalSales\":50000, \"totalTransactions\":10, \"cashTotal\":40000, \"mobileMoneyTotal\":10000},
      \"clientTimestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"
    }]
  }")
STATUS_CLOSURE=$(echo "$PUSH_CLOSURE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('results',[])[0].get('status',''))" 2>/dev/null || echo "")
echo "Day closure status: $STATUS_CLOSURE"
# Closure may be APPLIED or REJECTED (if already closed today), but should NOT be CONFLICT
[[ "$STATUS_CLOSURE" != "CONFLICT" ]] && echo "✅ Step 13 — Day closure is append-only (no CONFLICT status)" || { echo "❌ Step 13 FAILED (status=$STATUS_CLOSURE)"; exit 1; }

# ─── Step 14: GET /sync/conflicts without auth → 401 ─────────
CONFLICTS_NOAUTH=$(curl -s -w "\n%{http_code}" -X GET "$BASE_URL/api/v1/sync/conflicts")
HTTP_NOAUTH=$(echo "$CONFLICTS_NOAUTH" | tail -1)
[[ "$HTTP_NOAUTH" == "401" ]] && echo "✅ Step 14 — No auth returns 401" || { echo "❌ Step 14 FAILED (HTTP $HTTP_NOAUTH)"; exit 1; }

echo ""
echo "✅✅✅ All cURL integration checks passed — Story 5.3 Conflict Resolution backend validated ✅✅✅"
```

---

## Dev Notes

### Architecture & Design Decisions

1. **ConflictResolutionStrategy (GoF Strategy pattern):**
   The conflict resolution uses the Strategy pattern — `ConflictResolutionStrategy` interface with 3 implementations: `DeltaStockStrategy`, `LastWriteWinsStrategy`, `AppendOnlyStrategy`. The `ConflictStrategyRegistry` maps operation types to their strategy. Adding a new resolution strategy for future entity types requires only a new implementation + registry entry — zero modification to the push pipeline.

2. **Conflict evaluation runs AFTER handler application (not before):**
   This is intentional. The handler applies the operation first (stock decrement, entity update, etc.). THEN the conflict strategy evaluates the post-application state. For stock: we check if the resulting stock went negative. For LWW: we check if the entity was previously modified by another device. This ensures the operation is always applied (even with conflict — the merchant resolves stock discrepancies manually).

3. **STOCK_NEGATIVE returns CONFLICT status, LWW returns APPLIED:**
   - `STOCK_NEGATIVE`: The operation is still applied (stock is set to negative), BUT the response status is `CONFLICT` so the Flutter app can alert the user. The negative stock serves as a signal that manual intervention is needed (recount inventory).
   - `LAST_WRITE_WINS`: The operation is applied normally. Both updates succeed — the later one simply overwrites. The overwrite is logged silently for OWNER audit. No user notification needed.

4. **sync_conflicts_log vs sync_operations_log:**
   - `sync_operations_log` (Story 5.1): tracks ALL processed operations for idempotency. Every operation gets an entry.
   - `sync_conflicts_log` (Story 5.3): tracks ONLY conflict events. Only entries where a conflict was detected. Used for the conflict log UI.
   These are separate tables with different purposes and different access patterns.

5. **SyncOperationResult backward compatibility:**
   The `SyncOperationResult` record is extended with a 5th field `conflictData`. A backward-compatible constructor is added (4 fields → null conflictData). All existing handler code continues to work without modification. Only `SyncPushService.processOperation()` enriches the result with conflictData when a conflict is detected.

6. **DeltaStockStrategy — extracting delta from different operation types:**
   The delta amount varies by operation type:
   - `CREATE_SALE`: delta = -sum(items[].quantity) — each item reduces stock
   - `STOCK_ADJUST`: delta = payload.quantity (can be positive or negative)
   - `RECORD_STOCK_ENTRY`: delta = +payload.quantity (always positive)
   - `STOCK_TRANSFER`: delta = -payload.quantity for source store
   The strategy extracts the relevant productId and storeId from the payload to query the post-application stock level.

7. **Flutter conflict handling is non-blocking:**
   The UX design spec requires that sync conflicts don't block the user. The amber SnackBar for STOCK_NEGATIVE is non-blocking (auto-dismisses after 5s) with a "Voir" action to navigate to the conflict log. LWW conflicts are completely silent. The conflict log page is accessible only to OWNER role.

### Cross-Domain Dependencies (Backend)

| Component | Depends On | Purpose |
|---|---|---|
| `DeltaStockStrategy` | `EntityManager` (read stock_levels) | Post-application stock check |
| `DeltaStockStrategy` | `EntityManager` (read products for name) | Product name for conflict message |
| `LastWriteWinsStrategy` | `SyncOperationsLogRepository` | Check previous operations on same entity |
| `SyncPushService` | `ConflictStrategyRegistry` (new dependency) | Select conflict strategy |
| `SyncPushService` | `SyncConflictsLogRepository` (new dependency) | Persist conflict events |
| `SyncController` | `SyncConflictsLogRepository` | Serve conflict log endpoint |

### Existing Code Impact Analysis

#### Backend files created:
- `sync/sync/domain/model/ConflictResult.java`
- `sync/sync/domain/model/StockConflictResolvedEvent.java`
- `sync/sync/domain/model/SyncOverwrittenEvent.java`
- `sync/sync/domain/model/SyncConflictsLogEntry.java`
- `sync/sync/domain/port/in/ConflictResolutionStrategy.java`
- `sync/sync/domain/port/out/SyncConflictsLogRepository.java`
- `sync/sync/application/strategy/DeltaStockStrategy.java`
- `sync/sync/application/strategy/LastWriteWinsStrategy.java`
- `sync/sync/application/strategy/AppendOnlyStrategy.java`
- `sync/sync/application/strategy/ConflictStrategyRegistry.java`
- `sync/sync/adapter/out/persistence/entity/SyncConflictsLogJpaEntity.java`
- `sync/sync/adapter/out/persistence/jpa/SyncConflictsLogSpringRepository.java`
- `sync/sync/adapter/out/persistence/impl/SyncConflictsLogRepositoryAdapter.java`
- `sync/sync/adapter/in/rest/dto/SyncConflictDto.java`

#### Backend files modified:
- `sync/sync/domain/model/SyncOperationResult.java` — add conflictData field + backward-compatible constructor
- `sync/sync/application/service/SyncPushService.java` — add conflict evaluation after handler APPLIED
- `sync/sync/adapter/in/rest/SyncController.java` — add GET /sync/conflicts endpoint
- `sync/sync/adapter/in/rest/dto/SyncOperationResultDto.java` — add conflictData field
- `sync/sync/adapter/in/rest/dto/SyncPushResponseDto.java` — map conflictData
- `sync/sync/domain/port/out/SyncOperationsLogRepository.java` — add findPreviousAppliedByEntityId()
- `sync/sync/adapter/out/persistence/impl/SyncOperationsLogRepositoryAdapter.java` — implement new method
- `shared/infrastructure/persistence/TenantSchemaProvisioner.java` — add sync_conflicts_log DDL
- `shared/infrastructure/persistence/TenantSchemaSyncService.java` — migrate existing tenants

#### Flutter files created:
- `core/sync/domain/sync_conflict.dart` — SyncConflict model
- `core/sync/presentation/provider/sync_conflict_provider.dart` — Riverpod provider
- `core/sync/conflict_notification_service.dart` — SnackBar display logic
- `features/sync_indicator/presentation/page/sync_conflict_log_page.dart` — Conflict log page

#### Flutter files modified:
- `core/sync/rest_sync_service.dart` — parse conflictData from push response
- `core/sync/sync_trigger_notifier.dart` — handle push conflicts, show notifications
- `features/sync_indicator/presentation/widget/sync_detail_bottom_sheet.dart` — add "Conflits (N)" link
- Router file — add /settings/sync/conflicts route

### Project Structure Notes

- Strategy implementations go in `application/strategy/` (consistent with `application/handler/` for push handlers and `application/provider/` for pull providers)
- Domain events go in `domain/model/` (consistent with `SyncOperationProcessedEvent`)
- `ConflictResolutionStrategy` interface goes in `domain/port/in/` (it's a driving port from the sync domain perspective)
- `SyncConflictsLogRepository` goes in `domain/port/out/` (persistence driven port — consistent with `SyncOperationsLogRepository`)
- Flutter `SyncConflict` model goes in `core/sync/domain/` (consistent with sync-related domain models)
- Flutter conflict log page goes in `features/sync_indicator/presentation/page/` (the sync indicator feature already owns sync UI)

### References

- [Source: _bmad-output/planning-artifacts/epics/epic-5-moteur-de-synchronisation-offline-first.md#Story 5.3]
- [Source: _bmad-output/planning-artifacts/architecture.md#Sync conflicts — Strategy pattern]
- [Source: _bmad-output/planning-artifacts/architecture.md#GoF Design Pattern Analysis]
- [Source: _bmad-output/planning-artifacts/prd.md#FR71 — résoudre les conflits multi-device via synchronisation delta-based]
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Flow 22 — Sync Forcée]
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Sync Indicator component]
- [Source: _bmad-output/implementation-artifacts/5-1-push-sync-envoi-par-lot-des-operations-en-file.md — SyncPushService, handler registry, Template Method]
- [Source: _bmad-output/implementation-artifacts/5-2-pull-sync-telechargement-delta-fusion-locale.md — DeltaEntityProvider, SyncTriggerNotifier, pull cycle]

### Previous Story Intelligence (from 5.1 + 5.2)

1. **TransactionTemplate per-operation (5.1):** SyncPushService uses `TransactionTemplate` for per-operation transactions, NOT `@Transactional` on the batch. This ensures one failed operation doesn't roll back others. Story 5.3's conflict evaluation MUST run inside the same per-operation transaction (after handler.handle() but before commit) so the stock query sees the applied delta.

2. **SyncOperationResult is already used everywhere:** Changing this record requires updating all handler tests, SyncPushServiceTest, and SyncControllerTest. Add the backward-compatible constructor to minimize test changes (most tests use the 4-arg constructor → they continue to work with null conflictData).

3. **EntityManager native queries for stock lookup (5.2 pattern):** DeltaEntityProviders in Story 5.2 use `EntityManager.createNativeQuery()` with schema-qualified table names. DeltaStockStrategy should follow the same pattern: `SELECT quantity FROM {schema}.stock_levels WHERE product_id = ? AND store_id = ?`.

4. **TenantContext.getCurrentSchema() is available inside TransactionTemplate:** The per-operation transaction runs within the tenant context (set by JwtAuthFilter). DeltaStockStrategy can safely call `TenantContext.getCurrentSchema()`.

5. **Provider invalidation pattern (5.2):** SyncTriggerNotifier invalidates all Riverpod providers after pull. For 5.3, the conflict notification should be triggered AFTER push (not after pull) — when the push response contains CONFLICT results.

6. **Test naming convention:** Backend test classes follow `{ClassName}Test.java` in the matching test package. Flutter tests follow `{feature}_test.dart`. Tests MUST be written RED first (fail before production code exists).

7. **DDL additions follow existing pattern:** `TenantSchemaProvisioner.java` adds DDL strings to the `DDL_*` constants. `TenantSchemaSyncService.ensureRequiredIndexes()` handles migration for existing tenants. The `sync_conflicts_log` DDL should follow the same pattern as `sync_operations_log`.

---

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List
