---
title: '12.7 — Module-aware offline sync: reject operations of inactive modules'
type: 'feature'
created: '2026-10-29'
status: 'done'
baseline_revision: 'f8ffa9583ae41517e3290152f82bf61dc155ea9e'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/spec-socle-modulaire.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-2-module-registry.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The offline sync engine applies every queued operation whatever the tenant's modules. Once modules can be switched off per tenant (Epic 13), an operation of an inactive module (e.g. `CREATE_CLIENT` with `crm` off) must be refused cleanly, without breaking the batch, and each sync handler must say which module it belongs to (MOD-NFR9, MOD-NFR10, gap G1).

**Approach:** Every `SyncOperationHandler` declares its owning module id; the boot fails if a handler names a module that is not installed. `SyncPushService` reads the tenant's active modules once per push batch (13.2 `ModuleRegistry`, request-cached) and rejects, per operation, those whose module is inactive with reason `MODULE_INACTIVE`. The Flutter client shows a French message and applies its existing terminal-rejection policy (money/stock ops quarantined — zero sales lost; others dropped after one notice), so the queue never loops.

**Decisions (autonomous run, user delegated all choices 2026-10-28):**
- **Handler registry is already dynamic** (Spring collects every `SyncOperationHandler` bean; no hard-coded list) — AC2 is met by that discovery plus the module declaration. Physically moving handlers out of `sync` into their owning modules is the `KeevoModule` conversion (→ routed to 13.9), not done here.
- **`moduleId()`** is a required method on the handler contract (no default). Mapping:
  - `pos`: `CREATE_SALE`, `VALIDATE_SALE`, `CANCEL_SALE`, `CREATE_DAY_CLOSURE`
  - `catalog`: product ops (`CREATE_PRODUCT`, `CREATE_DRAFT_PRODUCT`, `UPDATE_PRODUCT`, `ARCHIVE_PRODUCT`, `UNARCHIVE_PRODUCT`, `PROMOTE_PRODUCT`), category ops (`CREATE_CATEGORY`, `TOGGLE_CATEGORY`, `RENAME_CATEGORY`)
  - `crm`: client and supplier ops (create/update/archive/unarchive)
  - `store`: `CREATE_STORE`, `UPDATE_STORE`, `DEACTIVATE_STORE`
  - `inventory`: `STOCK_ADJUST`, `RECORD_STOCK_ENTRY`, `STOCK_TRANSFER`, `SAVE_INVENTORY_COUNT`, `CREATE_INVENTORY_SESSION`, `VALIDATE_INVENTORY`
  - `core`: employee ops, `SUBMIT_FEEDBACK`
  Boot-time check (registry construction): every handler's `moduleId` ∈ `ModuleCatalog` installed ids, every operation type handled by exactly one handler (duplicates already collide — make the error explicit) → otherwise the context fails with a message naming the handler.
- **Guard**: `activeModules(tenantSchema)` read once per push batch before the loop. Per operation, after duplicate/replay detection (an operation already APPLIED keeps answering DUPLICATE even if its module is now off) and before the handler runs: module inactive → `REJECTED` with reason `MODULE_INACTIVE`, recorded exactly like other rejections (operation log + `sync_error_log` payload kept for 30 days, zero data loss); other operations of the batch proceed. A registry failure (exception) → every operation of the batch `REJECTED` with `INTERNAL_ERROR` (transient on the client: retried), never an HTTP 500 for the batch. Unknown operation type keeps `UNKNOWN_OPERATION_TYPE`.
- New `ErrorCode.MODULE_INACTIVE`. `MODULE_READ_ONLY` (EXPIRED in grace) waits for 13.5 (already routed there).
- **Pull** (`GET /sync/pull`) is unchanged here — hiding data of inactive modules is gating (→ routed to 13.10).
- **Flutter** (`keevo/app`): `MODULE_INACTIVE` added to `sync_rejection_messages.dart` → « Module non activé pour votre compte » ; it stays a terminal reason (not in `transientSyncRejectionReasons`); unit tests: message + `quarantinesOnRejection('CREATE_SALE','MODULE_INACTIVE')` true, `isTransientSyncRejection('MODULE_INACTIVE')` false. Bump `pubspec.yaml` (2.0.1+72 → 2.0.2+73).
- Routed 13.2 items handled here: guard placement per batch, REJECTED not 500, full-context MockMvc proof of the request cache (N operations in one push → 1 `tenant_modules` read).

## Boundaries & Constraints

**Always:** non-regression of every existing handler (behaviour unchanged when modules are active — today all are); RBAC rejections keep their current order/reasons; all test packages green; Flutter tests of `core/sync` green; architecture suite green.

**Never:** no writes to `tenant_modules` from production code (tests may insert rows), no activation API, no pull filtering, no handler relocation, no deploy.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| All modules active | batch of 3 mixed ops | all APPLIED as today | — |
| `crm` DISABLED | `CREATE_CLIENT` + `CREATE_SALE` | client REJECTED `MODULE_INACTIVE`, sale APPLIED | payload in `sync_error_log` |
| `pos` DISABLED | `CREATE_SALE` | REJECTED `MODULE_INACTIVE` | client quarantines it |
| Replay of applied op, module now off | same operationId | DUPLICATE | — |
| Registry throws | any batch | every op REJECTED `INTERNAL_ERROR` | HTTP 200 |
| Handler with unknown moduleId | boot | context fails naming the handler | — |
| N ops in one push | full context | 1 `tenant_modules` query | — |

</frozen-after-approval>

## Code Map

- `keevo/backend/src/main/java/com/keevo/sync/sync/application/service/SyncPushService.java` — per-op `TransactionTemplate`, rejection recording (`rejected(...)`), `processOperation`.
- `.../sync/sync/application/service/SyncOperationHandlerRegistry.java`, `.../application/handler/*SyncHandler.java` (19 handlers), the `SyncOperationHandler` port.
- `keevo/backend/src/main/java/com/keevo/shared/module/ModuleRegistry.java`, `application/ModuleCatalog.java` (13.2).
- `ErrorCode` enum (shared).
- Flutter: `keevo/app/lib/core/sync/sync_rejection_messages.dart`, `sync_rejection_policy.dart` + their tests; `keevo/app/pubspec.yaml`.

## Tasks & Acceptance

**Execution:**
- [x] `moduleId()` on the handler contract + 19 handlers + boot-time registry checks (tests).
- [x] Guard in `SyncPushService` + `ErrorCode.MODULE_INACTIVE` (unit tests for every matrix row).
- [x] Full-context Postgres IT through MockMvc `POST /api/v1/sync/push`: `crm` DISABLED row → client REJECTED / sale APPLIED / payload in `sync_error_log`; query count = 1 for the batch.
- [x] Flutter message + policy tests + pubspec bump.
- [x] Tick Story 12.7 routed items in the epic with evidence.

**Acceptance Criteria:**
- Given a tenant with a module inactive, when an operation of that module is pushed, then it is REJECTED `MODULE_INACTIVE` without affecting the other operations, and the client neither loops nor loses a sale.
- Given all modules active, when existing operations are replayed, then behaviour is unchanged.
- Given all backend test packages run one at a time and `flutter test test/core/sync`, then all pass.

## Implementation Notes

**Progress — task 1 done (2026-10-29).** `SyncOperationHandler.moduleId()` (required, no default); the 19 handlers return the spec mapping. `SyncOperationHandlerRegistry(List<SyncOperationHandler>, ModuleRegistry)` (Spring) delegates to `(List, Collection<String> installedIds)`: fails with `IllegalStateException` naming the handler class when its module is null/not installed or it supports no type, and naming the type + both handler classes when a type is handled twice. Installed ids come from the published `ModuleRegistry.installed()` (= `ModuleCatalog`), so `sync` stays on the published API of `shared.module`. `SyncOperationHandlerRegistryTest` +6 tests.

**Progress — task 2 done (2026-10-29).** `ErrorCode.MODULE_INACTIVE` (+ French message « Module non activé pour votre compte » and 403 in `GlobalExceptionHandler`, unused by HTTP today). `SyncPushService` takes `ModuleRegistry`; `activeModules(tenantId)` read once before the loop (not at all for an empty batch); exception → every non-replayed op `REJECTED INTERNAL_ERROR`. Per op: duplicate detection first (DUPLICATE kept — rejecting before it would overwrite an APPLIED log row), then the handler is resolved once, then the guard (`MODULE_INACTIVE`, before LWW pre-apply and the handler), unknown type keeps `UNKNOWN_OPERATION_TYPE`. Rejections go through the existing log + `sync_error_log` path. `SyncPushServiceModuleGuardTest` (9 tests, every unit matrix row); existing push/pull tests given a ModuleRegistry mock with all modules active. `com.keevo.sync.sync.application.**`: 407 tests green.

**Progress — task 3 done (2026-10-29).** `SyncModuleGuardPostgresIntegrationTest` (full context, `@AutoConfigureMockMvc`, real JWT + security chain, `@MockitoSpyBean TenantModuleStatePort` to count reads): `crm` DISABLED row → `POST /api/v1/sync/push` 200, client REJECTED `MODULE_INACTIVE` (operation log REJECTED, `sync_error_log` row with the payload, no client row), sale APPLIED (1 sale row), 1 `tenant_modules` read; all active, 3 ops → all APPLIED, 1 read; replay of an applied client after `crm` DISABLED → DUPLICATE, log row stays APPLIED; the 19 handler beans' type → module map equals the spec mapping. 4/4 green (one extra cached context because of the spy).

**Progress — task 4 done (2026-10-29).** `sync_rejection_messages.dart`: `MODULE_INACTIVE` → « Module non activé pour votre compte » (not added to `transientSyncRejectionReasons` → terminal). New `test/core/sync/sync_rejection_policy_test.dart` (terminal, `quarantinesOnRejection('CREATE_SALE','MODULE_INACTIVE')` true, day closure/stock too, `CREATE_CLIENT` not quarantined, `INTERNAL_ERROR` stays transient) + message test. `pubspec.yaml` 2.0.1+72 → 2.0.2+73. `flutter test test/core/sync`: 321 green; `flutter analyze` on the touched files clean.

**Progress — task 5 done (2026-10-29).** Epic Story 12.7: the three items routed from the 13.2 review (`moduleOf` source, guard placement, full-context cache proof) ticked with evidence in `epics-refonte-modulaire.md`.

**Verification (2026-10-29).** 14 packages one at a time, all rc=0, 0 failures/errors/skipped: sync 515, shared 716, architecture 28, catalog 247, crm 114, inventory 376, commerce 251, identity 437, messaging 163, reporting 389, admin 504, store 60, subscription 70, feedback 52 (total 3,922). `flutter test test/core/sync`: 321 green. Live Redmi check **not run**: no adb device attached (`adb devices` empty) — to do before closing the story. Nothing committed.

**Review patches (orchestrator decision 2026-10-29: `MODULE_INACTIVE` is undoable — the client never destroys data for it).**

- *Flutter held state — done.* `sync_rejection_policy.dart`: `heldSyncRejectionReason`, `isHeldSyncRejection`, `heldRetryInterval = 6 h` (documented); `quarantinesOnRejection` false for a held reason; stale comment fixed (the server re-evaluates REJECTED ops). `RestSyncService`: a REJECTED op with `MODULE_INACTIVE`, or one referencing (same `entityId`, or any id in its payload) the entity of another held queued op whatever its own reason, is **held** — kept with `lastError = MODULE_INACTIVE`, nothing rolled back/reverted/stripped/dropped/quarantined, one notice per op (`held: true`) when it first becomes held. Held ops are sent on the first push of the service that reaches the server (app start) and then at most every `heldRetryInterval`; excluded from `hasPendingOperations`, `pendingSyncCount` and the stale-queue diagnostic (pulls keep running), still listed in the queue screen (`pendingSyncQueue`, French reason) and still protect their entities from pull overwrites. Tests `rest_sync_service_held_test.dart` (7): held ghost product keeps its row + stock and its `RECORD_STOCK_ENTRY`/`CREATE_SALE` refused `PRODUCT_NOT_FOUND` are held; held client keeps the sale's `clientId`; held sale not quarantined, its `VALIDATE_SALE` held; no re-notice; throttled then re-pushed at the slot and removed once APPLIED; re-pushed after an app start; HTTP 503 keeps the queue (no `lastError`, still pending). `sync_rejection_policy_test.dart` rewritten.
- *Backend 503 on registry failure — done.* `SyncPushService.readModuleGate` throws `DomainException(SERVICE_UNAVAILABLE)` → HTTP 503 for the whole batch, no per-op rows/events. `registryThrows_everyOpInternalError` replaced by `registryThrows_batchUnavailable` (no log/error-log/event interaction); MockMvc IT `registryFailure_503_nothingRecorded` (`doThrow` on the `TenantModuleStatePort` spy → 503 `SERVICE_UNAVAILABLE`, no `sync_operations_log`/`sync_error_log`/sale row).
- *Unknown / DELETED tenant — done.* Published `ModuleRegistry.isKnownTenant(schema)` (same query and request cache: `ActiveModulesCache` now stores an `Entry(knownTenant, activeModules)`; `false` for the platform schema; invalid strings throw). Gate: unknown tenant → every non-replayed op REJECTED `TENANT_NOT_FOUND` (transient on the client). Tests: `ModuleRegistryServiceTest` +3 (known tenant with every module off, unknown/platform/invalid, shared cache = 1 query), Postgres IT (unknown and DELETED → false, existing → true), guard tests `unknownTenant_tenantNotFound` + `knownTenantAllOff_moduleInactive`.
- *Platform-admin push — documented.* Sync push stays forbidden for `SUPER_ADMIN` (`@PreAuthorize` OWNER/EMPLOYEE): IT `platformAdmin_pushForbidden` → 403, no `tenant_modules` read.
- *Other tests — done.* `GlobalExceptionHandlerTest.moduleInactive_mapsTo403_inFrench`; `SyncPushServiceTest` stock handlers (`STOCK_ADJUST`/`RECORD_STOCK_ENTRY`) stubbed with module `inventory`; push tests stub `isKnownTenant` true.
- *Verification of the patches.* `com.keevo.sync.**` 518 / 0 failures, `com.keevo.shared.**` 720 / 0 failures, `flutter test test/core/sync` 327 green. Not committed.

**Live-test fixes (Redmi, kv_zggt7g, `crm` DISABLED: client + sale with that client + sale without client → batch HTTP 500 replayed every 30 s).**

- *Item 1 — per-operation catch-all — done.* `SyncPushService.pushBatch` now also catches any other `RuntimeException` escaping the per-operation transaction (e.g. `JpaSystemException` "current transaction is aborted" when the log row is saved after the handler hit `sales_client_id_fkey`) → `rejectAfterRollback` with the handler's REJECTED reason when it is a real one (not `INTERNAL_ERROR`), else `DATA_INTEGRITY_VIOLATION` for a `DataAccessException`, else `INTERNAL_ERROR`; the batch stays 200 (503 only for the module-gate read). `SyncPushServiceTransactionFailureTest` (3).
- *Item 2 — unknown client on a synced sale — done.* `SaleSyncHandler` takes `crm.api.ClientQueryPort` (sync already depends on `crm :: api`) and rejects a `clientId` unknown in the tenant with `CLIENT_NOT_FOUND` before any write. `SaleSyncHandlerTest` +3. Postgres IT `crmOff_saleWithHeldClient_rejectedCleanly_otherSaleApplied` (the live scenario: 200, client `MODULE_INACTIVE`, its sale `CLIENT_NOT_FOUND` in `sync_error_log`, client-less sale APPLIED, 1 sale row). Flutter: the follow-up rule already matches `clientId` in the payload; reconciliation test added (sale refused `CLIENT_NOT_FOUND` after the held client → held with its `clientId`, not quarantined; the other sale applied).
- *Item 3 — REST sale with an unknown `clientId` — checked, not fixed.* `POST /api/v1/sales` goes `SaleController → RecordSaleService` (commerce), not through `SaleSyncHandler`: the FK violation surfaces at flush/commit as `DataIntegrityViolationException`, which `GlobalExceptionHandler` maps through its generic handler → HTTP 500 (French generic message). A clean check there needs `commerce → crm :: api`, a new module edge (crm is optional) — left for the orchestrator to decide.
- *Verification of the live-test fixes.* `com.keevo.sync.**` 525 / 0 failures, `com.keevo.commerce.**` 251 / 0 failures, `flutter test test/core/sync` 328 green. Backend on 4500 untouched. Not committed.

**Live verification (orchestrator, 2026-10-29, Redmi 23106RN0DA, dev APK 2.0.2+73, backend dev).** Tenant « Tech Centre » (kv_zggt7g), product created and stocked in the app (20). `crm` DISABLED row → airplane mode → client « Mireille Ngono » created + sale with that client + sale without client (stock 18) → reconnect. First run exposed a pre-existing batch-poisoning bug (FK `sales_client_id_fkey` → aborted tx → `JpaSystemException` → HTTP 500 for the whole batch, replayed every 30 s; journal I17) — fixed (per-op catch + `CLIENT_NOT_FOUND`). Re-run: CREATE_CLIENT REJECTED `MODULE_INACTIVE`, sale-with-client REJECTED `CLIENT_NOT_FOUND`, client-less sale APPLIED (server 1 sale); on the device the client stayed (no rollback). `crm` row removed + app restart → held ops re-pushed: CREATE_CLIENT APPLIED, held sale APPLIED; server 2 sales (1 with client), client present, stock 18; device « Mes Ventes » shows both sales, stock 18. Gap found: while held, the sale was hidden from « Mes Ventes » and the stock showed 19 (pull overwrite) → routed to 13.8 as a blocking item. REST sale with unknown client still 500 → routed to 14.3. Emulator cross-device check not done (emulator closed / PIN-locked).

## Spec Change Log

## Review Triage Log

### 2026-10-29 — Review pass (12-7)
- verdicts: 4 layers (Blind Hunter 12, Edge Case 8, Verification Gap 5, Intent 9; overlap) — high 1, medium 5, low 8 after dedup
- findings:
  - `[high]` `[patch]` `MODULE_INACTIVE` terminal on the client → ghost creates rolled back (product + stock, store + stock ops, client + sale link), inventory sessions cancelled, edits dropped, money ops quarantined until manual release — contradicts §2.2 « données conservées à vie »; orchestrator decision: held state (kept, nothing rolled back, not pending, throttled automatic re-push every 6 h + app start), follow-ups of a held entity held too
  - `[medium]` `[patch]` dependents of a refused creation failed with misleading reasons (`PRODUCT_NOT_FOUND`) and were dropped — held on the client through the follow-up rule
  - `[medium]` `[patch]` registry failure wrote a REJECTED row + error-log row + event per op on every retry — whole batch HTTP 503, nothing recorded; client keeps the queue
  - `[medium]` `[patch]` unknown/DELETED tenant → every op `MODULE_INACTIVE` (terminal) — `ModuleRegistry.isKnownTenant` → `TENANT_NOT_FOUND` (transient)
  - `[medium]` `[patch]` no reconciliation-level Flutter test — `rest_sync_service_held_test.dart` (7 tests)
  - `[low]` `[patch]` `MODULE_INACTIVE` 403 + French message untested — `GlobalExceptionHandlerTest`
  - `[low]` `[patch]` test stubs mapped stock ops to `catalog` — `inventory`
  - `[low]` `[patch]` platform-admin push — already 403 (OWNER/EMPLOYEE only), documented by a test
  - `[low]` `[patch]` stale comment about server replaying settled REJECTED results
  - `[low]` `[reject]` `installed()` during registry construction — reads the classpath catalogue, not the seeded table
  - `[low]` `[reject]` core/sync guarded like others — `core` always active (13.2 rule)
  - `[low]` `[reject]` guard before handler RBAC — documented; unchanged while modules are active
  - `[low]` `[reject]` "1 read" IT does not isolate the request cache — 1 read per batch is the stated requirement; cache owned by 13.2 tests
  - `[low]` `[reject]` mapping test not checked against manifests — manifests do not declare op types (decision: handler declaration)

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -Dtest='com.keevo.sync.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: green
- `cd keevo/app && flutter test test/core/sync` -- expected: green
- 14-package sequential loop -- expected: green
- Live (Redmi): `crm` DISABLED row for the test tenant → offline client creation + offline sale → reconnect → client refused with the French message, sale synced; row removed afterwards.
