---
title: 'v1s-16-12 — User feedback and local reconciliation on sync rejections'
type: 'bugfix'
created: '2026-10-11'
status: 'done'
baseline_revision: '590818558fec05f44168f8dfe660426da1df0edd'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
warnings: [oversized]
deferred:
  - summary: >-
      A queued CREATE_SALE referencing a rolled-back product or store is refused by the server forever and keeps hasPendingOperations() true.
    evidence: |-
      Money ops are never dropped (zero sales lost). The sale was already stuck before this story (server rejects unknown product/store); fixing it needs a product/server decision (accept unknown-product sale lines, or a discard/export action for rejected sales). Routed to v1s-16-15-sync-stuck-money-ops.
    location: >-
      keevo/app/lib/core/sync/rest_sync_service.dart (_reconcileRejection)
    severity: medium
---

<intent-contract>

## Intent

**Problem:** When the server REJECTS a queued offline operation, the app shows a one-time SnackBar and keeps the operation in `sync_queue` forever (replayed every push). Ghost local rows survive: a store, product, category, client or supplier created offline that the server refused, or an inventory session that stays IN_PROGRESS locally while its queued counts and validation fail in cascade. A stuck operation also keeps `hasPendingOperations()` true, which blocks catalog/stock refresh from the server. Several reason codes from 12-10/12-11/13-8/13-9 have no French message, or a technical fallback (`Erreur : <code>`, raw `IllegalArgumentException` text). `onAppStartup()` drops the login/tenant startup pull when a sync is already running.

**Approach:** Classify each rejection reason as *transient* (keep queued, retry silently) or *terminal*, then apply a per-operation reconciliation in `RestSyncService` when a terminal rejection arrives: roll back the ghost entity and drop its dependent queued ops, abandon a rejected inventory session (revert its offline adjustments), drop a now-useless non-financial op, or keep and flag a money/stock-ledger op (never auto-deleted). Every surfaced rejection carries a French message and whether it was undone on the device. `onAppStartup()` always gets a fresh full sync, chained after a running cycle.

## Boundaries & Constraints

**Always:** money and stock-ledger ops (`CREATE_SALE`, `VALIDATE_SALE`, `CANCEL_SALE` except already-cancelled, `CREATE_DAY_CLOSURE`, `STOCK_ADJUST`, `RECORD_STOCK_ENTRY`, `STOCK_TRANSFER`) are never deleted from the queue by a rejection (zero sales lost; the server keeps the payload in `sync_error_log`). Each rejection reconciliation runs in one Drift transaction. Messages are French and non-technical. Bump `keevo/app/pubspec.yaml` 1.1.20+19 → 1.1.21+20.

**Never:** change the backend sync protocol or handler behaviour (a backend change is test-only). No Drift schema change. No new package. Do not touch the 16.13 SnackBar flood/banner items.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Ghost create | `CREATE_STORE`/`CREATE_PRODUCT`/`CREATE_DRAFT_PRODUCT`/`CREATE_CATEGORY`/`CREATE_CLIENT`/`CREATE_SUPPLIER` REJECTED with a terminal reason (e.g. `PLAN_LIMIT_EXCEEDED`, `VALIDATION_ERROR`, `FORBIDDEN`) | local row deleted (product: also its stock_levels, stock_movements, inventory_counts; category: products' categoryId set null; store: the active-store pref is cleared if it points to it); the op and every queued op with the same `entityId` are deleted, plus product-dependent stock/count ops whose payload `productId` matches (except `CREATE_SALE`); one notice marked `rolledBack: true` | the dependent ops' own REJECTED results in the same batch are neither surfaced nor written back |
| Inventory session rejected | `CREATE_INVENTORY_SESSION` terminal, or `SAVE_INVENTORY_COUNT`/`VALIDATE_INVENTORY` with `INVENTORY_SESSION_NOT_FOUND` | local session → CANCELLED (cancelledAt = now); offline adjustments for that session (unsynced `stock_movements` with `inventory_session_id`) are reverted on `stock_levels` (floored at 0) and deleted; the op, the `VALIDATE_INVENTORY` for the session and every `SAVE_INVENTORY_COUNT` whose payload `sessionId` matches are deleted; one notice | — |
| Session closed on server | `SAVE_INVENTORY_COUNT`/`VALIDATE_INVENTORY` with `INVENTORY_SESSION_NOT_IN_PROGRESS` | offline adjustments reverted, dependent session ops dropped, local session marked `synced=1` so the next pull brings the server status; one notice | — |
| Validate refused | `VALIDATE_INVENTORY` with `FORBIDDEN` | offline adjustments reverted, session back to IN_PROGRESS, op dropped; one notice | — |
| Already cancelled | `CANCEL_SALE` with `SALE_ALREADY_CANCELLED` | treated as settled: op deleted, no notice | — |
| Money/stock op | e.g. `CREATE_SALE` `INSUFFICIENT_STOCK`, `VALIDATE_SALE` `OPTIMISTIC_LOCK` | kept in queue with `lastError`; surfaced once per reason (current behaviour) | — |
| Other terminal | `UPDATE_*`, `ARCHIVE_*`, `UNARCHIVE_PRODUCT`, `PROMOTE_PRODUCT`, `RENAME/TOGGLE_CATEGORY`, `UPDATE/DEACTIVATE_STORE`, employee ops, `SUBMIT_FEEDBACK`, other `SAVE_INVENTORY_COUNT` | op deleted after one notice (unblocks `hasPendingOperations()` so server refresh can correct local state) | — |
| Transient | `OPTIMISTIC_LOCK`, `INTERNAL_ERROR`, `TRANSACTION_ROLLED_BACK`, `DATA_INTEGRITY_VIOLATION`, `UNAUTHORIZED`, `SUBSCRIPTION_NOT_FOUND`, `TENANT_NOT_FOUND`, null | kept, `lastError` recorded, **not** surfaced as a SnackBar (visible in the queue screen) | retried on next push |
| Startup during sync | `onAppStartup()` while a cycle runs (or in criticalFailure) | a full `triggerSync()` runs after the running cycle ends (or immediately if none) | — |

</intent-contract>

## Code Map

- `keevo/app/lib/core/sync/rest_sync_service.dart` -- `_pushBatch` REJECTED branch (l.199-220) only bumps retryCount/lastError and surfaces once; APPLIED branch (l.153-197) is where `CREATE_INVENTORY_SESSION` must flip `inventory_sessions.synced=1` (pattern: day_closures l.175-186). `_dropMergedLocalProduct` (l.103-126) is the deletion pattern for a local product. `_isOneShot`/`_appendOnlyOperations` (l.31-53). Pull skips `synced=0` sessions (l.1006-1015).
- `keevo/app/lib/core/sync/sync_rejection_messages.dart` -- `friendlySyncRejectionReason` (missing: `INVENTORY_SESSION_NOT_FOUND`, `OPTIMISTIC_LOCK`, `INTERNAL_ERROR`, `TRANSACTION_ROLLED_BACK`, `DATA_INTEGRITY_VIOLATION`, `UNKNOWN_OPERATION_TYPE`, `SUBSCRIPTION_NOT_FOUND`, `TENANT_NOT_FOUND`, `WAREHOUSE_ALREADY_EXISTS`, `USER_ALREADY_EXISTS`, `EMPLOYEE_NOT_FOUND`, `CANNOT_DEMOTE_LAST_OWNER`; fallback `'Erreur : $code'` is technical; raw IAE text like `"Product not found: <id>"` arrives as reason). `friendlySyncOperationLabel` lacks `CREATE_DRAFT_PRODUCT`, `SUBMIT_FEEDBACK`, `CREATE_EMPLOYEE`.
- `keevo/app/lib/core/sync/sync_trigger_notifier.dart` -- `onAppStartup()` l.243-246 (guard drops call), `triggerSync()` l.165 (joins full cycle), `_cycle`, `_handlePushResults` l.276.
- `keevo/app/lib/features/sync_indicator/presentation/widget/sync_indicator.dart:66-100` -- rejection SnackBar `'Non synchronisé ($operation) : $reason'`.
- `keevo/app/lib/features/inventory/data/repository/inventory_session_repository_impl.dart:35-55` -- online `create()` falls back to offline create on ANY error (server 409 `INVENTORY_SESSION_ALREADY_ACTIVE` included → guaranteed rejected offline session) and stores the server session with `_local.insert` (synced=false, l.35 of local datasource); `_offlineCreate` l.57-87 not transactional.
- `keevo/app/lib/features/inventory/data/datasource/local_inventory_session_datasource.dart` -- `insert` (synced=false) / `upsert` (synced=true).
- `keevo/app/lib/features/inventory/presentation/provider/validate_inventory_provider.dart:140-262` -- offline validation writes `stock_levels` + `stock_movements` (`type ADJUSTMENT`, `source INVENTORY`, `inventorySessionId`, `quantityDelta`) then status VALIDATED.
- `keevo/app/lib/features/stores/presentation/provider/active_store_provider.dart:5` -- pref key `active_store_id`.
- Payload keys: `SAVE_INVENTORY_COUNT` `{sessionId, productId,…}` (`inventory_count_repository_impl.dart:123`); quick-add `RECORD_STOCK_ENTRY` entityId = stockLevelId, payload `productId` (`quick_add_product_provider.dart:234-261`).
- Backend (read-only evidence): `ProductSyncHandler` → `CreateProductUseCase:131-138` `PlanLimitGuard.checkProductLimit` → DomainException → `AbstractSyncOperationHandler.mapDomainException` → reason `PLAN_LIMIT_EXCEEDED`. Server replays previously REJECTED operationIds (`SyncOperationsLogRepository.isSettledElseClaim`).
- Tests: `keevo/app/test/core/sync/rest_sync_service_rejection_test.dart` (in-memory `AppDatabase.forTesting()` + MockDio harness), `sync_rejection_messages_test.dart`, `test/core/router/splash_sync_wiring_test.dart` (asserts the old no-op guard), `sync_trigger_single_flight_test.dart` (real notifier harness), `keevo/backend/src/test/java/com/keevo/sync/sync/application/handler/ProductSyncHandlerTest.java`.

## Tasks & Acceptance

**Execution:**
- `keevo/app/lib/core/sync/sync_rejection_policy.dart` -- new: `bool isTransientSyncRejection(String? reason)` and `const moneyLedgerOperations` set, documenting the client contract per reason (incl. PLAN_LIMIT_EXCEEDED = terminal → rollback for creates). -- single source for the classification.
- `keevo/app/lib/core/sync/rest_sync_service.dart` -- REJECTED branch: transient → current bookkeeping, not surfaced; `CANCEL_SALE`+`SALE_ALREADY_CANCELLED` → delete like DUPLICATE; money ops → current behaviour; otherwise run the matrix reconciliation in a transaction, delete the op, add the enriched notice (`operation`, `entityId`, `reason`, `rolledBack`) once; skip results of ops already removed by a cascade earlier in the batch. APPLIED `CREATE_INVENTORY_SESSION` → `UPDATE inventory_sessions SET synced = 1`.
- `keevo/app/lib/core/sync/sync_rejection_messages.dart` -- add the missing codes (PLAN_LIMIT_EXCEEDED: "Limite de votre forfait atteinte — passez à un forfait supérieur"); unknown UPPER_SNAKE code → "Refusé par le serveur (CODE)"; free text containing "not found" → "Élément introuvable sur le serveur", other free text → "Données refusées par le serveur"; add missing operation labels.
- `keevo/app/lib/features/sync_indicator/presentation/widget/sync_indicator.dart` -- when `rolledBack == true`, append " — annulé sur cet appareil" to the SnackBar text.
- `keevo/app/lib/core/sync/sync_trigger_notifier.dart` -- `onAppStartup()`: if `_cycle` running → `running.then((_) => triggerSync())`, else `triggerSync()`; no state guard.
- `keevo/app/lib/features/inventory/data/repository/inventory_session_repository_impl.dart` -- online create: store with `_local.upsert` (synced=true); fall back offline only for `DioException` with `response == null`, else rethrow; `_offlineCreate` insert+queue in one transaction (use the database through the local datasource or `AppDatabase.transaction`).
- `keevo/app/test/core/sync/rest_sync_service_rejection_test.dart` -- extend: one test per matrix row (store rollback + cascade + active-store pref, product PLAN_LIMIT_EXCEEDED rollback incl. stock/counts, category nulls products, inventory session cancel + revert adjustments + dependent ops dropped, NOT_IN_PROGRESS, VALIDATE FORBIDDEN, SALE_ALREADY_CANCELLED settled, CREATE_SALE kept, transient not surfaced, other terminal dropped, APPLIED session synced=1). Update the existing "stays in the queue" test to a money op.
- `keevo/app/test/core/sync/sync_rejection_messages_test.dart` -- new codes + fallbacks.
- `keevo/app/test/core/router/splash_sync_wiring_test.dart` + `keevo/app/test/core/sync/sync_trigger_single_flight_test.dart` -- replace no-op expectations: startup during a running cycle runs a second full cycle (push ×2, pull ×2) after the first completes.
- `keevo/app/test/features/inventory/data/repository/inventory_session_repository_impl_test.dart` (new) -- server 409 rethrows without local row/queue; network error falls back offline.
- `keevo/backend/src/test/java/com/keevo/sync/sync/application/handler/ProductSyncHandlerTest.java` -- `CREATE_PRODUCT` with use case throwing `PLAN_LIMIT_EXCEEDED` → REJECTED, reason `"PLAN_LIMIT_EXCEEDED"` (contract the client relies on).
- `keevo/app/pubspec.yaml` -- 1.1.21+20.

**Acceptance Criteria:**
- Given an offline-created store/product/category/client/supplier, when the server rejects its creation with a terminal reason, then after the push the row is gone from the local list, its queued follow-ups are gone, and the user sees one French SnackBar ending "— annulé sur cet appareil".
- Given an offline inventory session with queued counts and validation, when `CREATE_INVENTORY_SESSION` is rejected, then the session is CANCELLED locally, stock levels are back to their pre-validation values, and no dependent op remains in the queue.
- Given a rejected `CREATE_SALE`, then the sale and its queue entry are kept.
- Given every reason code listed in the Code Map, then `friendlySyncRejectionReason` returns a French message with no raw exception text.
- Given `flutter analyze`, no new error/warning; `flutter test` only the known `offline_gate_banner_test` failure; backend `ProductSyncHandlerTest` green.

## Spec Change Log

## Review Triage Log

### 2026-10-11 — Review pass
- verdicts: 37 findings — high 0, medium 13, low 12, false 7, maybe-false 5
- findings:
  - `[low]` `[patch]` (gap) rejection SnackBar suffix " — annulé sur cet appareil" untested — widget test added (`test/features/sync_indicator/…`), rolledBack true/false.
  - `[medium]` `[patch]` (gap) `activeStoreIdProvider` invalidation untested — notifier-level test added; grouped with the edge finding on the failed-pull path (fix below).
  - `[low]` `[patch]` (gap) no test for CREATE_CLIENT / CREATE_SUPPLIER / CREATE_DRAFT_PRODUCT rollback — one terminal-rejection test each added.
  - `[maybe-false]` `[reject]` (gap) offline session-create atomicity only tested with a mocked transaction — standard Drift zone transaction on the shared `AppDatabase` instance (`appDatabaseProvider`); proving it needs a real-DB repository test; if real only low.
  - `[medium]` `[defer]` (blind) queued CREATE_SALE for a rolled-back product/store is refused forever and pins `hasPendingOperations()` — pre-existing (the sale was already stuck before; rollback does not create it); needs a product/server decision (accept unknown-product lines or a discard/export action) → routed to new key `v1s-16-15-sync-stuck-money-ops`.
  - `[low]` `[patch]` (blind) policy doc says ledger ops never leave the queue, but ghost product/store cascades drop their stock ops — doc now states that second exception.
  - `[low]` `[reject]` (blind) cascade matches only top-level `productId`, multi-product ops missed — verified every product-carrying queued op (RECORD_STOCK_ENTRY, STOCK_ADJUST, STOCK_TRANSFER, SAVE_INVENTORY_COUNT, PROMOTE/UPDATE) uses a top-level `productId`; only CREATE_SALE has item lists and it is deliberately kept.
  - `[low]` `[patch]` (blind) orphan rows after ghost rollback (product_suppliers; store stock rows) — product/supplier rollback deletes `product_suppliers`; store rollback deletes its stock rows (see edge row). sale_items/client sales kept on purpose (money).
  - `[medium]` `[patch]` (blind) rejected CREATE_CATEGORY leaves queued product payloads with the ghost `categoryId` → CATEGORY_NOT_FOUND → product rolled back too — queued payloads rewritten without that `categoryId`; test added.
  - `[medium]` `[patch]` (blind) transient rejections retry forever silently — transient reasons are now surfaced once per reason (kept queued); also closes the intent-alignment divergence on OPTIMISTIC_LOCK.
  - `[medium]` `[patch]` (blind) SAVE_INVENTORY_COUNT refused for another terminal reason keeps the local count — local count row deleted; test added.
  - `[low]` `[reject]` (blind) VALIDATE_INVENTORY FORBIDDEN reopens with counts still pending and no hint — the FORBIDDEN message ("Action non autorisée") is shown; reopening is the correct state since the server session is still open.
  - `[medium]` `[patch]` (blind) inventory create rethrows on 502/503/504 — fallback offline restored for 5xx; only <500 responses rethrow; test added.
  - `[maybe-false]` `[reject]` (blind) atomicity of offline create never tested — duplicate of the gap row above.
  - `[low]` `[reject]` (blind) a throw in `_reconcileRejection` aborts the rest of the batch — the cycle catches and retries; ops already applied server-side come back DUPLICATE (idempotent); only the notices of that push are lost.
  - `[false]` `[reject]` (blind) `onAppStartup` chained with `.then` never runs if the running cycle errors — `_syncCycle`/`_pushCycle` catch every error internally (`catch (_) { _scheduleRetry(); }`), so the cycle future always completes normally.
  - `[low]` `[reject]` (blind) active-store reset half done (other screens' store filters; "other store kept" untested) — every screen reads the store through `activeStoreIdProvider`, which is now invalidated; the "other store" case is a plain equality check.
  - `[low]` `[patch]` (blind) coverage gaps (client/supplier/draft rollback) — same as gap row 3; other listed gaps (variant lookup, NOT_FOUND on validate) are low and share code paths already tested.
  - `[low]` `[reject]` (blind) `'not found'` free-text fallback is English-only — free-text reasons are backend Java `IllegalArgumentException` messages, always English; anything else falls to the generic French text.
  - `[medium]` `[defer]` (edge) ghost product rolled back while a queued CREATE_SALE sells it — same root cause as blind row 1 → `v1s-16-15-sync-stuck-money-ops`.
  - `[medium]` `[patch]` (edge) CREATE_CATEGORY rollback vs queued payload `categoryId` — same as blind row 5.
  - `[medium]` `[patch]` (edge) CREATE_STORE rollback leaves follow-ups referencing the store by payload `storeId`/`sourceStoreId`/`destinationStoreId` and local stock rows — store stock rows deleted and queued stock ops for the ghost store dropped (sales/closures kept); test extended.
  - `[medium]` `[patch]` (edge) CREATE_CLIENT rollback while a queued CREATE_SALE carries its `clientId` — queued sale payloads rewritten without that `clientId`, local `sales.client_id` nulled; sale kept; test added.
  - `[false]` `[reject]` (edge) free-text reason from an unmapped runtime error triggers a rollback — unmapped runtime exceptions come back as `INTERNAL_ERROR` (transient); free text is only produced by `IllegalArgumentException` (deterministic validation), see `AbstractSyncOperationHandler:33-37`.
  - `[medium]` `[patch]` (edge) SAVE_INVENTORY_COUNT other terminal reasons — same as blind row 11.
  - `[medium]` `[patch]` (edge) `activeStoreIdProvider` not invalidated when the pull fails after the push — invalidated in `_handlePushResults` on a rolled-back notice.
  - `[medium]` `[patch]` (edge) 5xx gateway skips the offline fallback — same as blind row 13.
  - `[low]` `[reject]` (edge) receiveTimeout after the server created the session → offline duplicate then rejected ALREADY_ACTIVE — pre-existing fallback; now the duplicate is cancelled locally and the online `getActiveByStoreId` returns the server session; a remote lookup before fallback adds a branch for a rare case.
  - `[low]` `[patch]` (edge, claim) ghost-product cascade drops stock-ledger ops against the "never deleted" rule — matrix row specifies it; policy doc updated (same as blind row 2).
  - `[maybe-false]` `[reject]` (edge, claim) "its queued follow-ups are gone" not met for payload-only references — after the store/client/category patches every payload-referencing follow-up is dropped or rewritten except sales, which are deliberately kept.
  - `[false]` `[reject]` (intent a) user-facing surface untested — SnackBar widget test and activeStore notifier test added; list screens re-read Drift after `_invalidateAllProviders` (existing behaviour).
  - `[maybe-false]` `[reject]` (intent b) only creates/inventory are rolled back locally; other terminal ops rely on server refresh — spec decision (Design Notes): dropping the op unblocks `hasPendingOperations()`-guarded refreshes; local undo of arbitrary edits has no prior value to restore.
  - `[false]` `[reject]` (intent c) OPTIMISTIC_LOCK never surfaced — fixed by the transient-surfacing patch.
  - `[false]` `[reject]` (intent d) full sync instead of pull-only follow-up — a full cycle includes the pull; pushing first is the existing cycle contract.
  - `[false]` `[reject]` (intent e) uneven coverage — client/supplier/draft tests added.
  - `[false]` `[reject]` (intent f) no per-item tick box — done in the epic brief at finalization.
  - `[maybe-false]` `[reject]` (intent g) backend change is test-only — descriptive, matches the epic rule.

### 2026-10-13 — Review pass (follow-up)
- verdicts: 44 findings — high 0, medium 2, low 24, false 13, maybe-false 5
- findings:
  - `[false]` `[reject]` (blind) no pubspec bump in the diff — the review diff excluded pubspec on purpose; 16-12 bumped to 1.1.21+20 in its own commit.
  - `[false]` `[reject]` (blind) hunks of 16-13/15/17/20, 18-7, 13-13 mixed in — the follow-up diff runs from the baseline to the current tree by design.
  - `[false]` `[reject]` (blind) audit upsert writes columns no migration creates — `audit_entries_table.dart` declares them (v1s-18-7 schema).
  - `[maybe-false]` `[reject]` (blind) a null role at pull wipes the owner's audit cache — v1s-18-7 code; the role is written by the splash/login claims before any sync; would need a trace of a pull with no role stored; if true only low.
  - `[low]` `[reject]` (blind) `kUserRoleKey` imported from `di/providers.dart` into core/sync — layering nit, no caller diverges.
  - `[false]` `[reject]` (blind) `onAppStartup` `.then` never runs if the cycle fails — carried: `_syncCycle`/`_pushCycle` catch every error (outer `catch (_) { _scheduleRetry(); }`), the cycle future always completes normally.
  - `[low]` `[reject]` (blind) several startup calls each queue one more full sync — idempotent extra pulls, only on login/tenant switch.
  - `[false]` `[reject]` (blind, edge) handling depends on the order of the server's results — `SyncPushService.dependencyOrder` hoists creations (categories/stores/contacts, then products, then sessions) ahead of dependents, so a ghost creation's result always precedes its dependents' results.
  - `[low]` `[reject]` (blind) ghost product/store rollback deletes the SALE movements of kept sales — those sales can only apply after « Rattacher »/« Renvoyer »; the server's movements come back by pull.
  - `[low]` `[reject]` (blind) a REJECTED without reason is never surfaced — the backend always sets a reason (`AbstractSyncOperationHandler`); null stays a silent transient by design.
  - `[low]` `[reject]` (blind) transient reasons that never clear have no retry ceiling — carried design (surfaced once, visible in the queue screen); escalation is a product decision.
  - `[low]` `[reject]` (blind) rolled-back rows stay on screen when the pull then fails — every list re-reads Drift on the next invalidation (next successful cycle); the active store, the only cross-screen pointer, is invalidated immediately.
  - `[low]` `[reject]` (blind) NOT_IN_PROGRESS session stays VALIDATED until a pull succeeds — matrix row: the next pull brings the server status.
  - `[low]` `[reject]` (blind, edge) inventory create: non-Dio errors / 408 / 429 no longer fall back offline — Dio wraps timeouts as DioException; 408/429 are rare; a refusal must not create a ghost session.
  - `[low]` `[reject]` (blind) online-create test only checks `upsert` was called — `upsert` is the synced write (datasource contract); the new Drift test covers the offline path.
  - `[low]` `[patch]` (blind) two message test names contradict their assertions — renamed.
  - `[low]` `[reject]` (blind) message coverage test misses a few reasons — each falls to a French generic message (no technical text), already tested.
  - `[low]` `[patch]` (blind) no test of a rewritten payload re-read in a later batch / rewritten op APPLIED — new group « multi-batch queue rewriting (Drift in memory) »: sale in batch 2 sent without the ghost client, product in batch 2 without the ghost category, same-batch rewrite retried then sent clean on the next push, rewritten op refused for its own reason in a later batch reconciled normally.
  - `[low]` `[patch]` (blind, gap) active store kept when a different store is rejected — test added.
  - `[low]` `[reject]` (blind) a rejected CREATE_INVENTORY_SESSION drops its counts without listing them — one notice « annulé sur cet appareil » is shown for the session (matrix row).
  - `[low]` `[reject]` (blind) whole-queue re-read per terminal rejection — bounded by 50-op batches; rejections are rare.
  - `[low]` `[patch]` (blind) policy doc: PLAN_LIMIT bullet mis-nested — moved under the ghost-creation bullet.
  - `[medium]` `[patch]` (edge) a later batch failing on the network threw away the earlier batches' notices while their ops were already rolled back / quarantined — `_push` returns the collected notices when a later batch fails (rethrows only when nothing was collected); 2 tests.
  - `[low]` `[reject]` (edge) sale/stock op quarantined when its parent creation was rejected *transiently* in the same batch — rare (transient failure of a creation); recoverable with « Renvoyer »; the guard needs a reference lookup per op.
  - `[low]` `[reject]` (edge) non-money op dropped when its parent creation was rejected transiently — same rarity; the server refresh corrects the local state.
  - `[low]` `[reject]` (edge) ghost parent category: queued child CREATE_CATEGORY keeps `parentId` — offline sub-category under a refused parent is rare; the child is then rolled back with a notice.
  - `[low]` `[reject]` (edge) ghost category in a queued session's `categoryIds` — rare; the session is abandoned with a notice.
  - `[low]` `[patch]` (edge) ghost product leaves its local `stock_transfers` rows — deleted with the product; test added.
  - `[low]` `[reject]` (edge) transfer to a ghost store: source decrement not re-credited — the stock key is no longer pending, the next pull rewrites the source level.
  - `[low]` `[reject]` (edge) queued REASSIGN_EMPLOYEE to a ghost store — refused and dropped next push, pull restores the assignment.
  - `[low]` `[reject]` (edge) count refused after an offline validation applied its adjustment — the validation op carries the server-side adjustment; pull rewrites the level.
  - `[low]` `[reject]` (edge) cascade deletes an already-quarantined op silently — it depends on a ghost and could never apply; lists refresh on the next cycle.
  - `[maybe-false]` `[reject]` (edge) audit wipe on null role — same as blind row 4.
  - `[low]` `[patch]` (edge) employee « Voir » on a plain rejection opens OWNER-only `/settings/sync` — action shown only to OWNER (blocked operations keep `/sync/blocked` for every role); widget test added.
  - `[false]` `[reject]` (edge, claim) transient reasons are surfaced — carried: deliberate first-pass patch (silent forever retries).
  - `[false]` `[reject]` (edge, claim) 5xx falls back offline — carried: deliberate first-pass patch (gateway errors are unreachability).
  - `[false]` `[reject]` (edge, claim) unknown code without « (CODE) » — overridden by the v1s-16-20 rule (no technical data shown).
  - `[false]` `[reject]` (edge, claim) money ops quarantined instead of retried — v1s-16-15 (routed from this story's deferred item) replaced that behaviour.
  - `[medium]` `[patch]` (edge, claim) AC « one SnackBar after rollback » broken by a later batch failure — same root cause as edge row 1.
  - `[low]` `[patch]` (gap) quarantined-list invalidation after push untested when pull fails — v1s-16-15 code; test added in this follow-up's 16-15 pass.
  - `[low]` `[reject]` (gap) stale-queue diagnostic skipping quarantined ops untested — rate-limited diagnostic, spurious entry at worst.
  - `[low]` `[patch]` (gap) offline session create atomicity only mock-tested — Drift in-memory test: a failing queue write leaves neither session nor queue row.
  - `[maybe-false]` `[reject]` (intent) transient surfacing contradicts the matrix — carried decision (row above).
  - `[maybe-false]` `[reject]` (intent) cascades go beyond the matrix (store stock ops, rewrites, counts) — first-pass patches toward the user outcome (no ghost follow-ups), recorded in the 2026-10-11 log.
  - `[maybe-false]` `[reject]` (intent) UI surfaces exercised through hand-built notices / mocked push — the service side is now proven on Drift across batches and pushes; the SnackBar reads the same notice map.

## Design Notes

- Why drop terminal non-money ops: the server replays a REJECTED operationId on each push and gets the same refusal; meanwhile `hasPendingOperations()` stays true, which disables `ProductRepositoryImpl.syncFromRemote` and the stock refreshes — the stuck op was actively preventing reconciliation. The server keeps the payload in `sync_error_log` (epic 5: 30-day manual recovery), so nothing is lost without a trace.
- Why keep money/stock-ledger ops: NFR "zero sales lost"; reverting a sale locally after the cash was taken is a business decision, not a sync rule. They stay visible (red) in Paramètres › Synchronisation › File.
- PLAN_LIMIT_EXCEEDED client behaviour (item 1): terminal; for `CREATE_PRODUCT`/`CREATE_DRAFT_PRODUCT`/`CREATE_STORE` the ghost is rolled back and the user is told to upgrade. Upgrading later does not resurrect the entity (the user recreates it), which avoids a product silently appearing days later.
- Transient reasons are not surfaced: they usually succeed on the next push; the queue screen still shows `lastError`.

## Verification

**Commands:**
- `cd keevo/app && flutter test test/core/sync test/core/router/splash_sync_wiring_test.dart test/features/inventory` -- expected: green except baseline `offline_gate_banner_test`
- `cd keevo/app && flutter analyze` -- expected: no new error/warning vs ~630 infos
- `cd keevo/app && flutter test` -- expected: only `offline_gate_banner_test` fails
- `cd keevo/backend && mvn -q test -Dtest=ProductSyncHandlerTest` -- expected: green

## Auto Run Result

Status: done

**Summary:** REJECTED sync operations now get a defined client behaviour. `sync_rejection_policy.dart` classifies reasons (transient → kept and retried; terminal → reconciled). Terminal rejections of offline creations (store, product, draft product, category, client, supplier) roll the ghost row back and drop or rewrite its queued follow-ups (sales are never dropped; their ghost `clientId` is stripped). A refused inventory session (or a count/validation answered `INVENTORY_SESSION_NOT_FOUND`) is cancelled locally, its offline stock adjustments reverted and its dependent ops dropped; `NOT_IN_PROGRESS` hands the session back to pull; a refused validation reopens it. `CANCEL_SALE`/`SALE_ALREADY_CANCELLED` is settled. Money/stock-ledger ops stay queued and flagged. Other terminal ops are surfaced once and dropped (unblocks `hasPendingOperations()`). Every rejection is surfaced once per reason with a French message, ending "— annulé sur cet appareil" when something was undone. `PLAN_LIMIT_EXCEEDED` is defined as terminal (rollback + "passez à un forfait supérieur") and pinned by a backend contract test. `onAppStartup()` never drops the startup sync: it chains a full sync after a running cycle. Online inventory-session creation no longer falls back offline on a server refusal (<500), stores the session as synced, and the offline create is atomic.

**Files changed:**
- `keevo/app/lib/core/sync/sync_rejection_policy.dart` -- new: transient set, money-ledger set, ghost-create set, client contract doc.
- `keevo/app/lib/core/sync/rest_sync_service.dart` -- REJECTED reconciliation (`_reconcileRejection`, `_rollbackGhostEntity`, `_revertInventoryAdjustments`), cascade bookkeeping across batches, APPLIED session → synced.
- `keevo/app/lib/core/sync/sync_rejection_messages.dart` -- missing codes, non-technical fallbacks, missing operation labels.
- `keevo/app/lib/core/sync/sync_trigger_notifier.dart` -- `onAppStartup()` chaining; active store refreshed after a rollback.
- `keevo/app/lib/features/sync_indicator/presentation/widget/sync_indicator.dart` -- "annulé sur cet appareil" suffix.
- `keevo/app/lib/features/inventory/data/{repository/inventory_session_repository_impl.dart,datasource/local_inventory_session_datasource.dart}` -- fallback only on no response/5xx, synced upsert, atomic offline create.
- `keevo/app/lib/core/storage/app_constants.dart`, `keevo/app/lib/features/stores/presentation/provider/active_store_provider.dart` -- shared `kActiveStoreIdKey`.
- Tests: `rest_sync_service_rejection_test.dart` (extended), `rest_sync_service_queue_test.dart`, `sync_rejection_messages_test.dart`, `sync_trigger_single_flight_test.dart`, `splash_sync_wiring_test.dart`, new `sync_trigger_active_store_test.dart`, new `sync_indicator_rejection_test.dart`, new `inventory_session_repository_impl_test.dart`; backend `ProductSyncHandlerTest.java` (+1).
- `keevo/app/pubspec.yaml` -- 1.1.21+20.

**Review:** 1 pass, 37 findings — 12 entries patched (7 medium, 5 low), 1 deferred (medium → `v1s-16-15-sync-stuck-money-ops`), 24 rejected with reasons in the triage log.

**Follow-up review recommended:** true — 7 medium entries patched; the patch added payload rewriting of queued ops plus a per-batch re-read of the queue (`rewritten` set), covered only by in-memory unit tests, not by a device run with a real multi-batch queue.

**Verification:** targeted 8 test files 58/58 green; `flutter analyze` 630 issues (= baseline, the 9 warnings are pre-existing, none in changed files); chunked app run: `test/core` 313 passed / 1 failed (known baseline `offline_gate_banner_test`), `test/features/inventory` 172 OK, `test/features/stores` 45 OK, `test/features/sync_indicator` 46 OK; an earlier full `flutter test` (before review patches) 1375 passed / 1 baseline failure; backend `sync` package 419 tests, 0 failures/errors.

**Residual risks:** local `stock_transfers` rows of a rolled-back store are not cleaned; a session answered `NOT_IN_PROGRESS` only gets its true status if the next delta pull includes it; queued sales referencing a rolled-back product/store stay stuck (→ v1s-16-15).


### Follow-up pass 2026-10-13

**Summary:** Notices of earlier batches survive a later batch's network failure; a ghost product takes its local transfers along; employees no longer get a « Voir » to the OWNER-only sync page. Multi-batch rewriting/re-read is now proven end to end on an in-memory Drift database (4 tests), plus atomicity of the offline session create on Drift, the « other active store kept » case, and renamed message tests.

**Files changed:** `keevo/app/lib/core/sync/rest_sync_service.dart`, `keevo/app/lib/core/sync/sync_rejection_policy.dart` (doc), `keevo/app/lib/features/sync_indicator/presentation/widget/sync_indicator.dart`, tests `test/core/sync/rest_sync_service_rejection_test.dart`, `test/core/sync/sync_rejection_messages_test.dart`, `test/features/sync_indicator/sync_indicator_rejection_test.dart`, new `test/features/inventory/data/repository/inventory_session_offline_create_drift_test.dart`, `keevo/app/pubspec.yaml` 1.1.48+47.

**Review:** 44 findings — patched: 1 medium root cause (reported twice), 8 low; rejected 35; deferred 0.

**Follow-up review recommended:** false — no high patched in this follow-up pass.

**Verification:** `flutter analyze` 0 error / 0 warning. Lots: test/core 506 ✓, inventory 191 ✓, sync_indicator 64 ✓, stores 45 ✓.

**Residual risks:** creations rejected transiently with dependents in the same batch (rare) can quarantine/drop the dependents — recoverable via « Renvoyer » / server refresh.
