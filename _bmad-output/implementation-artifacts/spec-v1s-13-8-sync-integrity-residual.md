---
title: 'v1s-13-8 — Sync integrity residual (offline write paths, sync handler parity)'
type: 'bugfix'
created: '2026-10-11'
status: 'done'
baseline_revision: '767c4b5deb3784614f17132fdc192d8f48d7cc5b'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-13-context.md'
warnings: ['multiple-goals', 'oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 13.8 (epics-remediation-audit.md) lists 15 residual integrity defects on offline write paths and sync handlers: duplicated SALE movements after pull, stale/double-applied draft initial stock, non-atomic Drift write + sync_queue (stores, clients, suppliers, quick-add, product insert), store repo crash/fabricated cache, sync handlers that validate less than their REST DTO or re-execute on replay, offline inventory validation that crashes on variant mismatch / validates empty sessions / re-validates, and `InventorySessionSyncHandler` writing via `repository.save` instead of the use case.

**Approach:** Fix each item at its source with the smallest change, keep every Epic 12 RBAC rule (sync RBAC failure → REJECTED/FORBIDDEN before the use case, null auth fails closed, OWNER JWT has no storeId), and record a per-item decision; items already fixed by earlier work are closed with evidence, the one item that needs variants is routed to Story 14.1.

## Boundaries & Constraints

**Always:**
- Sync handlers: input errors → `IllegalArgumentException` or `DomainException` (→ REJECTED with the error code); RBAC checks stay first in `apply`, before any idempotency read (12-10 rule kept for inventory sessions).
- A replayed operation (same client entity id) is a no-op returning APPLIED — no second row, no second domain event.
- App: every offline local write and its `queueOperation` run in ONE Drift transaction (`AppDatabase.transaction`) — `SyncService.queueOperation` writes to the same `AppDatabase`, so Drift's zone-scoped transaction covers it.
- SALE stock movement id = the sale item id (client-generated) on BOTH sides, so the pull upserts the device's own movement row.
- Existing public signatures used by other callers stay source-compatible (add overloads / optional params).
- Any change under `keevo/app/` bumps `keevo/app/pubspec.yaml` version (patch AND build, e.g. `1.1.14+13` → `1.1.15+14`).

**Never:**
- No new Flyway migration, no Drift schema version bump, no change to the local `stock_levels` unique key.
- No change to REST controller behaviour except what flows from shared use-case/service changes listed below.
- Do not touch `deferred-work.md`; do not create story files.
- Do not add sync rejection for an OWNER because their JWT has no storeId.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Inventory session replay | CREATE_INVENTORY_SESSION with a sessionId that already exists | APPLIED, no new row, no `InventorySessionCreatedEvent` | — |
| Inventory session conflict | new sessionId, store already has an IN_PROGRESS session | REJECTED `INVENTORY_SESSION_ALREADY_ACTIVE` | app shows friendly French message |
| Inventory session unknown store | storeId not in tenant | REJECTED `INVENTORY_STORE_NOT_FOUND` | — |
| Inventory session employee other store | EMPLOYEE assigned store ≠ payload storeId | REJECTED `FORBIDDEN` (unchanged 12-10) | — |
| Inventory session created | valid payload, no active session | session saved with the CLIENT sessionId, event published | — |
| Stock adjust bad quantity | quantity `1.5`, `"3"`, or `> Integer.MAX_VALUE` | REJECTED, use case never called | IllegalArgumentException |
| Stock adjust whole double | quantity `5.0` | applied as 5 | — |
| Create store bad name | name `" a "` or 101 chars | REJECTED `VALIDATION_ERROR` | — |
| Create store name trimmed | name `"  Shop  "` | store saved with name `"Shop"` | — |
| Create client/supplier blank phone | phone `"  "` | REJECTED | IllegalArgumentException |
| Deactivate store replay | DEACTIVATE_STORE on already inactive store | APPLIED, use case NOT called (no 2nd event) | — |
| Update store replay | UPDATE_STORE with trimmed name/address/phone equal to stored | APPLIED, use case NOT called | — |
| Legacy sale payload | CREATE_SALE with `status: PENDING_VALIDATION` and no `requestedStatus` | sale recorded as PENDING_VALIDATION | invalid value → REJECTED |
| Sale movement id | CREATE_SALE / REST sale with itemId X | backend SALE movement id = X; local SALE movement id = X | itemId null → server-generated |
| Offline validation empty | session with no counted line (all `physical == null` or none) | throws, nothing written, nothing queued | user-facing French message |
| Offline re-validation | session already VALIDATED locally | returns 0, `completedAt` unchanged, no new VALIDATE_INVENTORY op | — |
| Offline validation cancelled | session CANCELLED | throws, nothing written | — |
| Offline validation variant mismatch | count has variantId V, stock row exists for (product, store) with variantId null | existing row updated, no UNIQUE violation, movement keeps count's variantId | — |
| Stale draft initial stock | draftInitialStocks has entry for product no longer in cart (or not a draft in cart) | entry neither applied locally nor queued; map cleared after any successful sale | — |
| Store offline write + queue | queueOperation throws during offline create/update/deactivate | local store row unchanged (rolled back), exception surfaces | — |
| Deactivate store not cached | offline, store id not in local DB | throws `StoreException.notFound()` before anything is written/queued | — |
| Update store not cached | offline, store id not in local DB | throws `StoreException.notFound()`, nothing written/queued (no fabricated type/isActive) | — |
| Quick-add / product insert atomic | queueOperation throws on offline quick-add or `LocalProductDataSource.insert/insertDraft` | no product/stock/count row left behind | exception surfaces |

</intent-contract>

## Code Map

Backend (`keevo/backend/src/main/java/com/keevo/`):
- `sync/sync/application/handler/InventorySessionSyncHandler.java` -- writes via `InventorySessionRepository.save`; keep the 12-10 store check (lines ~55-61), then delegate to `CreateInventorySessionUseCase` with the client sessionId.
- `inventory/counting/domain/port/in/CreateInventorySessionCommand.java` -- record (storeId, scope, categoryIds, actorId); add nullable `sessionId` (keep a 4-arg constructor for `InventorySessionController:74` and tests).
- `inventory/counting/application/service/CreateInventorySessionService.java` -- add: if `sessionId != null` and `sessionRepository.findById` present → return it (before store/active checks, no event); create with the given id.
- `inventory/counting/domain/model/InventorySession.java:64` -- `create(...)` factory; add overload taking an explicit id.
- `sync/sync/application/handler/StockAdjustSyncHandler.java:47` -- `((Number) q).intValue()` truncates; validate in `validate()` that quantity is a Number, integral (`BigDecimal` with no fractional part) and in int range.
- `sync/sync/application/handler/CreateStoreSyncHandler.java:58-63` -- validate name via a new `StoreFactory.validateName(String)` (returns trimmed name, throws `DomainException VALIDATION_ERROR`) and persist the trimmed name; refactor `store/store/domain/model/StoreFactory.java` `create` to use it.
- `sync/sync/application/handler/StoreSyncHandler.java` -- inject `StoreRepository`; after the OWNER guard: DEACTIVATE on store with `isActive()==false` → APPLIED no-op; UPDATE whose trimmed name, address, phone `Objects.equals` stored values → APPLIED no-op; missing store → let use case throw STORE_NOT_FOUND.
- `sync/sync/application/handler/CreateClientSyncHandler.java`, `CreateSupplierSyncHandler.java` -- `validate()`: phone blank → IllegalArgumentException (mirror `@NotBlank` in `catalog/contact/adapter/in/web/dto/Create{Client,Supplier}RequestDto`).
- `sync/sync/application/handler/SaleSyncHandler.java:64` -- read `requestedStatus`, fall back to legacy `status` key.
- `commerce/sale/application/service/RecordSaleService.java:86-111` -- pass `item.itemId()` as movementId to `stockOperationService.recordOperation(..., movementId, allowNegative)` (9-arg overload, `catalog/stock/domain/service/StockOperationService.java:100`) for both branches.
- Tests: `src/test/java/com/keevo/sync/sync/application/handler/{InventorySession,StockAdjust,CreateStore,Store,CreateClient,CreateSupplier,Sale}SyncHandlerTest.java`, `.../commerce/sale/application/RecordSaleServiceTest.java`, `.../inventory/counting/application/service/CreateInventorySessionServiceTest.java`.

App (`keevo/app/lib/`):
- `features/pos/presentation/provider/record_sale_notifier.dart:66-67` -- `initialStockEntries` copies the whole `draftInitialStocksProvider`; filter to product ids of `cart` items with `isDraft`; clear the provider after every successful sale. (Item "double apply on retry" already fixed: transaction at lines ~119-131 + test `draft: recordSale failure rolls back the real stock and promotion` in `test/features/pos/presentation/provider/record_sale_notifier_test.dart:458`.)
- `features/pos/data/datasource/local_sale_datasource.dart:81-91` (`insertSale`) -- SALE movement `id: const Uuid().v4()` → `id: item.id`. Other SALE inserts (validate pending ~387, cascade ~476) unchanged.
- `features/inventory/presentation/provider/validate_inventory_provider.dart` `_validateOffline` -- guards: status `VALIDATED` → return 0 (no write/queue); status ≠ `IN_PROGRESS` → throw; no count with `physical != null` → throw; stock_levels lookup: exact variant match first, else fall back to the (product, store) row.
- `features/stores/data/repository/store_repository_impl.dart` -- add optional `AppDatabase? db` ctor param; offline branches run local write + queue inside `db.transaction` (direct call when null, for existing mock tests); update/deactivate: `getById` first, null → `StoreException.notFound()`; return value computed from the cached row (no `existing!`). Wire `db` in `features/stores/presentation/provider/store_provider.dart:28` from `appDatabaseProvider`.
- `features/contact/data/repository/client_repository_impl.dart`, `supplier_repository_impl.dart` -- same optional `AppDatabase? db` + transaction around local write + queue in create/update/archive; wire in their providers.
- `features/inventory/presentation/provider/quick_add_product_provider.dart:154-265` (`_createOffline`) -- wrap the dedup-after inserts and the 3 `queueOperation`s in `db.transaction`.
- `features/catalog/data/datasource/local_product_datasource.dart:110,273` -- wrap insert + `_enqueueSync` in `_db.transaction` for `insert` and `insertDraft`.
- `core/sync/sync_rejection_messages.dart` -- add `INVENTORY_SESSION_ALREADY_ACTIVE`, `INVENTORY_STORE_NOT_FOUND`, `INVENTORY_INVALID_CATEGORIES`, `INVENTORY_SESSION_NOT_IN_PROGRESS` French messages.
- `keevo/app/pubspec.yaml` -- version bump.

## Tasks & Acceptance

**Execution:**
- `CreateInventorySessionCommand.java`, `InventorySession.java`, `CreateInventorySessionService.java` -- client-id support + idempotent replay -- use case owns all invariants.
- `InventorySessionSyncHandler.java` -- delegate to `CreateInventorySessionUseCase` after the 12-10 store check; drop direct repository writes -- parity with REST.
- `StockAdjustSyncHandler.java` -- strict integral/int-range quantity validation -- no silent truncation.
- `StoreFactory.java`, `CreateStoreSyncHandler.java` -- shared name validation, trimmed name saved -- DTO parity.
- `StoreSyncHandler.java` -- state-based idempotency guard -- no duplicate events on replay.
- `CreateClientSyncHandler.java`, `CreateSupplierSyncHandler.java` -- blank phone rejected -- DTO parity.
- `SaleSyncHandler.java` -- legacy `status` fallback -- closes stale queue payloads without a migration.
- `RecordSaleService.java` -- itemId as SALE movement id -- no duplicate movement on pull.
- Backend tests listed in Code Map -- add one test per matrix row on the backend side.
- `record_sale_notifier.dart`, `local_sale_datasource.dart` -- stale entries filtered; SALE movement id = item id.
- `validate_inventory_provider.dart` -- three guards + variant fallback lookup.
- `store_repository_impl.dart`, `client_repository_impl.dart`, `supplier_repository_impl.dart` + providers -- atomic offline write; null-safe update/deactivate.
- `quick_add_product_provider.dart`, `local_product_datasource.dart` -- atomic offline write.
- `sync_rejection_messages.dart`, `pubspec.yaml` -- messages, version bump.
- App tests: extend `test/features/pos/presentation/provider/record_sale_notifier_test.dart`, `test/features/pos/data/datasource/local_sale_datasource_test.dart`, `test/features/inventory/presentation/provider/validate_inventory_provider_test.dart`, `test/features/stores/data/repository/store_repository_impl_test.dart` (use `AppDatabase.forTesting()` + real `LocalStoreDataSource` with a throwing mock `SyncService` for the rollback case), `test/features/catalog/data/datasource/local_product_datasource_test.dart`, `test/features/inventory/presentation/provider/quick_add_product_provider_test.dart` -- one test per app-side matrix row.

**Acceptance Criteria:**
- Given any matrix row, when the scenario runs, then the observed outcome matches the row and a test proves it.
- Given the full backend suite, when run, then failures/errors are no worse than the known baseline (7 failures + 29 errors in the 10 listed persistence classes).
- Given `flutter analyze` and `flutter test`, when run, then no new issue and only the known `offline_gate_banner_test` failure.

## Spec Change Log

## Review Triage Log

### 2026-10-11 — Review pass
- verdicts: 32 findings — high 0, medium 11, low 15, false 6, maybe-false 0 (one Blind Hunter row carries two findings: store-mismatch replay + PK race)
- findings:
  - Blind Hunter
  - `[medium]` `[defer]` ValidateSaleService / SaleValidationCascadeService (and app validatePendingSale / cascade) still write SALE movements with random ids → duplicate on pull for PENDING sales validated later — pre-existing, outside the CREATE_SALE item; routed to v1s-13-10-sale-optimistic-lock (Sale validate/correct paths).
  - `[low]` `[reject]` Sales queued before upgrade keep random local SALE movement ids → still duplicated on pull — no regression (server ids were random before too); one-off transitional state, no migration worth its complexity.
  - `[low]` `[patch]` No end-to-end push→pull test — partly closed by pinning the itemId link at both boundaries (SaleSyncHandlerTest command capture + app CREATE_SALE payload assertion); pull upsert by id is existing behaviour.
  - `[medium]` `[patch]` Legacy `status` fallback rejects a stale sale on an unknown value; comment cites the wrong origin — legacy key now used only when COMPLETED/PENDING_VALIDATION, else ignored; comment fixed (commit e694ae3).
  - `[medium]` `[patch]` Offline validate guards outside the transaction → concurrent calls double-apply — status re-checked inside db.transaction; concurrent test added.
  - `[low]` `[reject]` Variant fallback may hit another non-null variant's row — variants do not exist yet (Story 14.1) and the local unique key allows a single (product, store) row, so the fallback row is the only possible row; restricting it would reintroduce the UNIQUE crash.
  - `[low]` `[patch]` Replayed session id belonging to another store reported APPLIED — FORBIDDEN on store mismatch in CreateInventorySessionService. Concurrent first-delivery PK race: `[low]` `[reject]` — same op is serialised by SyncPushService.isSettledElseClaim; two different ops with one session id are not produced by the app.
  - `[medium]` `[defer]` New CREATE_INVENTORY_SESSION rejections leave the local session IN_PROGRESS and dependent SAVE_INVENTORY_COUNT / VALIDATE_INVENTORY ops fail in cascade — client-side rejection reconciliation is the sync-rejection feedback story; routed to v1s-16-12-sync-rejection-feedback. (Before this change the server silently created a 2nd active session.)
  - `[medium]` `[defer]` Same intValue truncation in SaleSyncHandler, TransferSyncHandler, InventoryCountSyncHandler, ValidateSaleSyncHandler, ProductSyncHandler — pre-existing, outside the StockAdjust item; routed to v1s-15-9-backend-refactor-hygiene (shared helper in AbstractSyncOperationHandler).
  - `[false]` `[reject]` UPDATE_CLIENT/SUPPLIER lack blank-phone check — the REST UpdateClient/UpdateSupplier DTOs are PATCH with nullable, unannotated fields; the sync handlers already match them.
  - `[false]` `[reject]` StoreSyncHandler no-op misses replays with omitted address/phone — UpdateStoreService writes command.address()/phone() as given (omitted → null), so running the use case there is the correct outcome; the no-op is conservative by design.
  - `[medium]` `[patch]` Optional `db` silently non-atomic; no client/supplier rollback test — real-Drift rollback groups added for clients and suppliers (db kept optional to keep mock-based tests source-compatible; providers wire it).
  - `[low]` `[patch]` Housekeeping: epics checklist / routed items not updated — done at finalisation (epic brief ticked, routed items added to 13.10, 15.9, 16.12, 14.1).
  - `[low]` `[reject]` sqlite override copy-pasted in a test — test-only duplication, existing pattern in other test files.
  - `[low]` `[patch]` Trailing whitespace in a test — removed.
  - `[low]` `[reject]` French/English exception texts mixed in validate provider — the pre-existing English ones ('Session not found', 'No authenticated user') are unreachable from the UI flow; not worth churn here.
  - Edge Case Hunter
  - `[medium]` `[patch]` requestedStatus CANCELLED persisted with stock decrement — only COMPLETED / PENDING_VALIDATION accepted, else REJECTED.
  - `[low]` `[reject]` Replay of an applied create with now-invalid name/phone is REJECTED — settled ops are DUPLICATE before the handler runs (SyncPushService.isSettled); only previously REJECTED ops replay, and those never created the row.
  - `[low]` `[reject]` Concurrent PK race on session create — see above (operation claim serialises).
  - `[low]` `[patch]` Existing session of another store → FORBIDDEN (same row as Blind Hunter).
  - `[medium]` `[patch]` Concurrent validate double-apply (same root cause as Blind Hunter row) — fixed in-transaction.
  - `[low]` `[reject]` Variant fallback wrong row — same as Blind Hunter row.
  - `[low]` `[reject]` Pre-upgrade queued sales — same as Blind Hunter row.
  - `[medium]` `[patch]` Claim "re-validation idempotent" only sequential — same root cause, fixed.
  - Verification Gap
  - `[medium]` `[patch]` Client/supplier transactional branch untested — rollback groups added.
  - `[medium]` `[patch]` itemId link not pinned at either boundary — SaleSyncHandlerTest captor + app payload assertion added.
  - `[low]` `[patch]` New INVENTORY_* rejection messages not asserted — assertions added (trivial).
  - Intent Alignment (descriptive)
  - `[false]` `[reject]` Item 2 has no code change — closed with evidence (UI-3 commit 1041827, existing rollback tests), allowed by the AC "resolved or rejected with reason".
  - `[false]` `[reject]` Item 13 adds no new payload field — the client already sends a stable per-item id (`itemId`); reusing it as the movement id satisfies "movement ids in CREATE_SALE" and also fixes online REST sales.
  - `[false]` `[reject]` Item 6 does not migrate sync_queue — the item offers "migration OR confirm none remain"; a server-side tolerant read closes it for any payload still queued, with no client migration.
  - `[false]` `[reject]` Item 12 not keyed on operation id — operation-id dedup already exists in SyncPushService; the residual risk (re-executed use case on re-queued ops) is what the state-based guard closes.

## Design Notes

Per-item decisions (Story 13.8 checklist, in brief order):
1. Stale draftInitialStocks → fixed (filter to draft items in cart; clear after success).
2. Initial stock applied twice on retry → already resolved by UI-3 (commit `1041827`): entries applied inside the same Drift transaction as `recordSale`, map cleared only on success; covered by existing tests. No code.
3. UNIQUE violation on variant mismatch → fixed by fallback lookup on the (product, store) row (the only row the current unique key allows). Extending the Drift unique key with `variant_id` belongs to variants → routed to Story 14.1 (tag B, refonte).
4. Zero-count session → offline guard throws. Online validation stays server-owned (backend accepts an explicitly confirmed empty validation; the defect was the silent offline path).
5. Re-validation overwrites completedAt → VALIDATED is an idempotent no-op returning 0.
6. Stale CREATE_SALE payloads with `status` → backend accepts legacy key as fallback; no app migration needed (also safe if none remain in prod).
7. StockAdjust truncation → strict validation before `intValue`.
8. deactivateStore crash → not-cached guard before any write; return from cached row.
9. Store write+queue not atomic → single transaction (also clients/suppliers).
10. updateStore fabricated type/isActive → not-cached store throws `notFound` instead of fabricating (UI lists stores from the local cache, so this only happens on a stale id).
11. CreateStoreSyncHandler name length → `StoreFactory.validateName`; clients/suppliers phone `@NotBlank` parity.
12. StoreSyncHandler idempotency → operation-id dedup already exists (`SyncPushService.isSettledElseClaim`); residual risk is a re-queued/replayed op re-firing events → state-based no-op in the handler (REST unchanged).
13. 2nd SALE movement on pull → SALE movement id = client sale item id (already in the CREATE_SALE payload as `itemId`), applied in `RecordSaleService` so REST online sales benefit too. No new payload field needed.
14. QuickAdd / LocalProductDataSource → Drift transaction.
15. InventorySessionSyncHandler → routed through the use case with the client session id; conflict policy: an offline session that collides with another active session is REJECTED `INVENTORY_SESSION_ALREADY_ACTIVE` (same rule as REST, surfaced via sync rejection notification).

## Verification

**Commands:**
- `cd keevo/backend && mvn -q test -Dtest='InventorySessionSyncHandlerTest,StockAdjustSyncHandlerTest,CreateStoreSyncHandlerTest,StoreSyncHandlerTest,CreateClientSyncHandlerTest,CreateSupplierSyncHandlerTest,SaleSyncHandlerTest,RecordSaleServiceTest,CreateInventorySessionServiceTest,StoreFactoryTest*'` -- expected: all pass (drop the StoreFactoryTest pattern if absent; use `-Dsurefire.failIfNoSpecifiedTests=false`)
- `cd keevo/app && flutter analyze` -- expected: no new issue
- `cd keevo/app && flutter test test/features/pos test/features/inventory test/features/stores test/features/contact test/features/catalog` -- expected: all pass

## Auto Run Result

Status: done

**Summary:** All 15 Story 13.8 items closed (13 fixed in code, 1 closed with evidence — UI-3 commit 1041827 —, 1 partly fixed with the Drift unique-key extension routed to Story 14.1). Epic 12 RBAC rules preserved (OWNER guard / 12-10 employee store check run before any idempotency read; FORBIDDEN → REJECTED). `epic-13-context.md` compiled.

**Files changed (main):**
- Backend: `InventorySessionSyncHandler` (via use case), `CreateInventorySessionService`/`Command`/`InventorySession` (client id, idempotent replay, store-mismatch FORBIDDEN), `StockAdjustSyncHandler` (integral int quantity), `StoreFactory` + `CreateStoreSyncHandler` (shared 2–100 name rule), `StoreSyncHandler` (state-based no-op), `CreateClient/SupplierSyncHandler` (blank phone), `SaleSyncHandler` (requestedStatus whitelist + legacy `status`), `RecordSaleService` (SALE movement id = item id).
- App: `record_sale_notifier.dart` (stale entries), `local_sale_datasource.dart` (movement id = item id), `validate_inventory_provider.dart` (guards in/out of transaction, variant fallback), store/client/supplier repositories + providers (Drift transaction, not-cached guards), `quick_add_product_provider.dart`, `local_product_datasource.dart` (transactions), `sync_rejection_messages.dart`, `pubspec.yaml` 1.1.17+16.
- Tests added/extended in 10 backend classes and 11 app test files.

**Review:** 1 pass, 4 layers, 32 findings. Patched: 11 entries (medium 7 incl. grouped duplicates: legacy status, CANCELLED whitelist, concurrent validation, client/supplier atomic tests, itemId pinning; low: store-mismatch replay, payload/message assertions, whitespace, bookkeeping). Deferred/routed: 3 (→ v1s-13-10, v1s-15-9, v1s-16-12) + 1 routed from planning (→ Story 14.1). Rejected: 12 (6 false, 6 low — reasons in the triage log).

**Follow-up review recommendation:** false — the patched mediums are each pinned by a test that fails without the fix (concurrent validation test verified against the unpatched code); no unverified risk can be named.

**Verification:**
- Backend full `mvn test`: 2092 tests, 7 failures + 29 errors — exactly the known baseline (10 persistence classes), no new failure. Targeted classes all green. `target/` removed afterwards.
- App: `flutter analyze` 630 issues (unchanged vs baseline); full `flutter test`: 1329 passed, 1 failed (known `offline_gate_banner_test`).

**Residual risks:** sales queued by app ≤1.1.16 keep a random local SALE movement id and still duplicate once on pull (transitional, no regression); a rejected CREATE_INVENTORY_SESSION leaves the local session IN_PROGRESS until v1s-16-12; SALE movements from pending-sale validation still use random ids until v1s-13-10.
