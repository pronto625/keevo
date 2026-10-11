---
title: 'v1s-16-15 — Quarantine for terminally rejected offline money operations'
type: 'feature'
created: '2026-10-11'
status: 'done'
baseline_revision: 'd982e66776ac6cb75169850be95eb92b7523d652'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-v1s-16-12-sync-rejection-feedback.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** Since v1s-16-12 a money/stock-ledger operation (`CREATE_SALE`, `VALIDATE_SALE`, `CANCEL_SALE`, `CREATE_DAY_CLOSURE`, `STOCK_ADJUST`, `RECORD_STOCK_ENTRY`, `STOCK_TRANSFER`) refused for good by the server (e.g. a sale whose product or store was rolled back, `FORBIDDEN`) stays in `sync_queue` forever: it is replayed on every push and keeps `hasPendingOperations()` true, which disables the catalog/stock refreshes. Nobody can act on it. A sale in a store that does not exist server-side is even APPLIED (no FK on `sales.store_id`), and local `stock_transfers` rows of a rolled-back store survive the 16-12 rollback.

**Approach (PO decision, taken from PRD FR73/NFR20 "zero data loss", epic 5 `sync_error_log`):** a terminally rejected money op goes into a visible **quarantine** on the device: kept, never pushed, not counted as pending, shown in a "Bloquées" list with explicit actions — *Renvoyer* (everyone), *Exporter* (everyone), *Rattacher* a sale to existing products/store (OWNER) and *Abandonner* with a mandatory export (OWNER). Nothing is ever dropped without an export and an entry in the local sync history; the server keeps the refused payload in `sync_error_log` (30 days). The server now refuses a sale or day closure for an unknown store (`STORE_NOT_FOUND`) so it reaches quarantine (and the server error log) instead of becoming an orphan.

## Boundaries & Constraints

**Always:** quarantine = new nullable `sync_queue.quarantined_at` (Drift schema 27 → 28, `addColumn` migration). Quarantined rows are excluded from push, `hasPendingOperations()`, pending counts/list, the stale-queue diagnostic, and the pull protections (`_getPendingEntityIds`, `_getPendingStockKeys`). A `CREATE_SALE` quarantines together with its queued `VALIDATE_SALE`/`CANCEL_SALE` (same `entityId`) — the "chain"; actions act on the chain. Every reconciliation runs in one Drift transaction. French, non-technical UI text. Bump `keevo/app/pubspec.yaml` 1.1.21+20 → 1.1.22+21. Record the rule in the PRD (FR73a).

**Never:** auto-delete a money op; delete a quarantined op without a successful export (share sheet not dismissed); change the push protocol; touch transient handling for money ops other than `DATA_INTEGRITY_VIOLATION`; touch 16.13 SnackBar flood items.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Terminal money rejection | `CREATE_SALE` REJECTED `PRODUCT_NOT_FOUND` / `STORE_NOT_FOUND` / `FORBIDDEN` / free text / `DATA_INTEGRITY_VIOLATION` | `quarantined_at` = now, `lastError` = reason, chained `VALIDATE_SALE`/`CANCEL_SALE` quarantined too; one notice `quarantined: true`; `hasPendingOperations()` false if nothing else is pending; next push does not send it | chained ops' REJECTED results in the same batch are not surfaced again |
| Transient money rejection | `OPTIMISTIC_LOCK`, `INTERNAL_ERROR`, … (not `DATA_INTEGRITY_VIOLATION`) | unchanged 16-12 behaviour (kept pending, retried) | — |
| Chain op APPLIED in same batch | chained op quarantined by cascade but its result is APPLIED/DUPLICATE | deleted as usual | — |
| Renvoyer | quarantined chain | `quarantined_at` cleared on the chain, push triggered | refused again → back in quarantine, one new notice |
| Rattacher (OWNER, `CREATE_SALE` only) | lines' product replaced by local products and/or store replaced by a local store | queue payload rewritten (`productId`, `variantId` removed on remapped lines, `storeId`); local `sale_items.product_id` / `sales.store_id` updated; chain released and pushed | — |
| Abandonner (OWNER) | confirm dialog → export text shared | if the share sheet was not dismissed: chain deleted; `CREATE_SALE` → local sale + items deleted; `CREATE_DAY_CLOSURE` → unsynced local closure deleted; sync history entry "Opération abandonnée" | dismissed → nothing deleted, SnackBar "Exportez la vente avant de l'abandonner" |
| Ghost store rollback | `CREATE_STORE` terminal | additionally delete local `stock_transfers` whose source/destination is the ghost store | — |
| Unknown store server-side | `CREATE_SALE` / `CREATE_DAY_CLOSURE` with a storeId not in `stores` | REJECTED `STORE_NOT_FOUND` (logged in `sync_error_log`) | — |
| Sign-out | quarantined ops exist | `flushPendingOperations()` returns false (sign-out warning shown) | — |

</intent-contract>

## Code Map

- `keevo/app/lib/core/storage/sync_queue_table.dart` -- add `quarantinedAt`; `app_database.dart:93` `schemaVersion` 27, migration ladder near l.204/274 (`addColumn` pattern).
- `keevo/app/lib/core/sync/sync_rejection_policy.dart` -- add `quarantinesOnRejection(operation, reason)` (`moneyLedgerOperations` ∧ (terminal ∨ `DATA_INTEGRITY_VIOLATION`)), `saleChainOperations = {VALIDATE_SALE, CANCEL_SALE}`; update library doc.
- `keevo/app/lib/core/sync/rest_sync_service.dart` -- `_push` l.81 and `hasPendingOperations` l.571 filter `quarantinedAt.isNull()`; REJECTED branch l.199-270 (money branch l.255) → quarantine path; `_reconcileRejection` CREATE_STORE branch (l.360) + `_rollbackGhostEntity` 'CREATE_STORE' (l.493) → delete `stock_transfers` by `source_store_id`/`destination_store_id`; `_getPendingEntityIds` l.746, `_getPendingStockKeys` l.777 filter quarantined.
- `keevo/app/lib/core/sync/sync_service.dart` -- interface; add `Future<int> quarantinedOperationCount()`.
- `keevo/app/lib/core/sync/sync_trigger_notifier.dart:148-160` -- oldest-pending query filter quarantined.
- `keevo/app/lib/core/sync/sync_monitoring_providers.dart` -- `pendingSyncCount`/`pendingSyncQueue` exclude quarantined; add `quarantinedSyncQueue` + `quarantinedSyncCount` (riverpod codegen → build_runner).
- `keevo/app/lib/core/sync/sync_event_logger.dart` -- `_log` pattern; add `logOperationAbandoned(String label)`.
- `keevo/app/lib/features/auth/presentation/provider/sign_out_service.dart:23-32` -- also false when quarantined ops exist; tests `test/features/auth/presentation/provider/sign_out_service_test.dart`.
- `keevo/app/lib/features/sync_indicator/presentation/widget/sync_indicator.dart:66-100` -- rejection SnackBar; "Voir" pushes `/settings/sync` (OWNER-only route, `app_router.dart:140-153`).
- `keevo/app/lib/features/sync_indicator/presentation/widget/sync_detail_bottom_sheet.dart:24` -- pending count row (all roles).
- `keevo/app/lib/features/sync_indicator/presentation/page/sync_settings_page.dart:446-515` -- `_QueueTab`/`_QueueTile`.
- `keevo/app/lib/core/router/app_router.dart:574-580` -- sync routes.
- Local tables: `sales` (id, storeId, totalAmount, paymentMode, occurredAt, createdAt), `sale_items` (saleId, productId, variantId, productName, unitPrice, quantity, subtotal), `day_closures` (id, storeId, synced), `stock_transfers` (sourceStoreId, destinationStoreId). Sale payload: `sale_repository_impl.dart:_buildPayload` (`storeId`, `items[{productId, variantId, productName, quantity, appliedUnitPrice…}]`).
- `Share.share(text)` pattern: `inventory_gap_report_page.dart:246` (share_plus 10 → returns `ShareResult`).
- Role: `currentUserRoleProvider` (`core/di/providers.dart:52`, 'OWNER' | 'EMPLOYEE').
- Backend: `SaleSyncHandler` / `DayClosureSyncHandler` (`sync/sync/application/handler`), `StoreRepository.findById` (`store/store/domain/port/out`, already used by `StoreSyncHandler`), `ErrorCode.STORE_NOT_FOUND`; tests `SaleSyncHandlerTest`, `DayClosureSyncHandlerTest` (if present, else add cases to an existing handler test).
- Tests: `test/core/sync/rest_sync_service_rejection_test.dart` (in-memory DB + MockDio harness, "CREATE_SALE kept" test to update), `sync_rejection_messages_test.dart`.

## Tasks & Acceptance

**Execution:**
- `keevo/app/lib/core/storage/sync_queue_table.dart`, `app_database.dart` -- column + schema 28 migration; run build_runner.
- `keevo/app/lib/core/sync/sync_rejection_policy.dart` -- policy helpers + doc (quarantine contract).
- `keevo/app/lib/core/sync/rest_sync_service.dart` -- quarantine on rejection (chain, notice `quarantined: true`, skip chained results already quarantined this push unless APPLIED/DUPLICATE); filters; ghost-store `stock_transfers` cleanup; `quarantinedOperationCount()`.
- `keevo/app/lib/core/sync/sync_quarantine_service.dart` (new) -- `SyncQuarantineService(AppDatabase, SyncEventLogger)` + Riverpod provider: `chainOf`, `release(opId)`, `reattachSale(opId, {Map<String,String> productIds, String? storeId})`, `exportText(opId)` (French: operation label, date, reason, store, lines with qty/price/total, raw payload JSON), `abandon(opId)`.
- `keevo/app/lib/core/sync/sync_monitoring_providers.dart`, `sync_trigger_notifier.dart`, `sign_out_service.dart`, `sync_service.dart` -- filters / new providers / sign-out guard.
- `keevo/app/lib/core/sync/sync_rejection_messages.dart` -- `STORE_NOT_FOUND`, `PRODUCT_NOT_FOUND` messages if missing.
- `keevo/app/lib/features/sync_indicator/presentation/widget/quarantined_operations_section.dart` (new) -- list of quarantined ops (label, date, reason, sale summary) + actions (Renvoyer, Exporter for all; Rattacher, Abandonner for OWNER), abandon confirm dialog, reattach bottom sheet (per-line product picker with search over local active products, store picker over local stores).
- `keevo/app/lib/features/sync_indicator/presentation/page/blocked_operations_page.dart` (new) + route `/sync/blocked` (all roles) -- hosts the section.
- `sync_settings_page.dart` -- queue tab shows the section above pending ops; `sync_indicator.dart` -- quarantined notice "Bloqué (<op>) : <reason> — action requise", "Voir" → `/sync/blocked`; `sync_detail_bottom_sheet.dart` -- "N opération(s) bloquée(s)" row → `/sync/blocked` when > 0.
- Backend `SaleSyncHandler`, `DayClosureSyncHandler` -- `StoreRepository.findById(storeId)` empty → `DomainException(STORE_NOT_FOUND)`; handler tests.
- Tests: rejection test (quarantine, chain, exclusion from push/pending, transient untouched, DIV quarantined, ghost store transfers deleted); new `sync_quarantine_service_test.dart` (release, reattach rewrite + local rows, abandon deletes sale/closure + logs, exportText content); widget test for the section (OWNER sees 4 actions, EMPLOYEE 2; abandon dismissed → nothing deleted via injectable share function); sign-out test; migration smoke (column exists on `AppDatabase.forTesting`).
- `_bmad-output/planning-artifacts/prd.md` -- FR73a rule; `keevo/app/pubspec.yaml` -- 1.1.22+21.

**Acceptance Criteria:**
- Given a queued offline sale whose product was rolled back, when the server refuses it, then the next push no longer sends it, catalog/stock refreshes run again, and the OWNER sees it under "Bloquées" in Paramètres › Synchronisation › File with Renvoyer / Rattacher / Exporter / Abandonner.
- Given an EMPLOYEE device with a quarantined sale, when the SnackBar "Voir" is tapped, then `/sync/blocked` opens with Renvoyer and Exporter only.
- Given the OWNER abandons a quarantined sale and dismisses the share sheet, then the sale is still quarantined.
- Given `flutter analyze`, no new error/warning; targeted + chunked app tests green except baseline `offline_gate_banner_test`; backend `sync` package green.

## Spec Change Log

## Review Triage Log

### 2026-10-11 — Review pass
- verdicts: 45 findings — high 0, medium 16, low 20, false 9, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind) VALIDATE_SALE/CANCEL_SALE refused while its CREATE_SALE is still retrying got quarantined and stayed blocked after the sale applied — a chain op now only retries while its sale is pending, and joins silently when the sale is quarantined; tests added.
  - `[medium]` `[patch]` (blind) validations/cancellations queued after the sale's quarantine were still pushed and quarantined separately — `_push` first attaches them to the quarantined sale (one UPDATE); test added.
  - `[medium]` `[patch]` (blind) sign-out dialog claims blocked ops "seront envoyées à votre prochaine connexion" — separate text when `quarantinedOperationCount() > 0` (blocked, handled in Paramètres › Synchronisation, lost if another account signs in). Account-switch wipe stays behind the existing explicit `PreviousAccountDataException` choice.
  - `[low]` `[reject]` (blind) `ShareResultStatus.unavailable` counted as exported — the spec defines success as "share sheet not dismissed"; treating `unavailable` as failure would make Abandonner impossible on platforms that cannot report, and a confirm dialog already precedes.
  - `[medium]` `[patch]` (blind) abandon/reattach leave local stock rows inconsistent — abandon deletes the sale's unsynced stock_movements (ids = sale item ids), the unsynced movement of STOCK_ADJUST/RECORD_STOCK_ENTRY and the local stock_transfers row of STOCK_TRANSFER; reattach moves the sale's movements to the new product. The abandoned-VALIDATE_SALE status mismatch part is rare (sale already on server, validation refused for good) and the pull is no longer blocked — not fixed.
  - `[medium]` `[patch]` (blind) reattach keeps the ghost productName — remapped lines take the local product name (payload + sale_items). Variant-required check, highlighting unknown lines and disabling store-only save: low UX refinements, rejected (a new refusal just re-quarantines with a notice).
  - `[low]` `[reject]` (blind) Renvoyer/Exporter without try/catch — Drift/share exceptions are exceptional; adding error branches for them is complexity without an observed failure.
  - `[low]` `[reject]` (blind) no persistent indicator besides SnackBar/detail sheet — blocked count row in the sync detail sheet + queue tab + `/sync/blocked`; an AppBar badge belongs to the 16.13 UX residuals scope.
  - `[low]` `[patch]` (blind) epic-16-context.md still describes 16.15 as undecided and DATA_INTEGRITY_VIOLATION as transient for all ops — 16.15 decision and amended contract written in the context file.
  - `[low]` `[patch]` (blind) detail sheet pushes with the sheet context after pop — router captured before pop; tap test added.
  - `[medium]` `[patch]` (blind) test gaps on risky paths — covered by the verification-gap patches below (pull filters, providers, reattach/release widgets, route guard); real v27→v28 file migration not tested (addColumn pattern identical to v26/v27).
  - `[false]` `[reject]` (blind) backend check ignores deactivated stores — intended: an offline sale made before the deactivation really happened and must be accepted; only unknown stores are orphans. `sync_error_log` persistence of REJECTED results is existing `SyncPushService` behaviour with its own tests.
  - `[low]` `[patch]` (blind) export of stock ops has no readable product — local product name resolved, fallback "Produit inconnu sur cet appareil (<id>)". Sequential per-chain queries in the list provider: rejected (a handful of blocked ops).
  - `[medium]` `[patch]` (edge) abandon leaves pending chain ops of the sale queued — deleted with the chain (same root as blind row 2).
  - `[medium]` `[patch]` (edge) abandon of stock ops leaves local movement/transfer rows — same as blind row 5.
  - `[false]` `[reject]` (edge) CREATE_DAY_CLOSURE abandon with null entityId — `day_closure_repository_impl.dart:152` always queues `entityId: closure.id`.
  - `[low]` `[patch]` (edge) "Opération abandonnée" shown when the chain was already gone — `abandon` returns bool, SnackBar only on true.
  - `[low]` `[reject]` (edge) chain grows between export and abandon — needs a push to quarantine a new chain op during the confirm dialog; passing exported ids adds API surface for a seconds-wide window.
  - `[low]` `[reject]` (edge) share `unavailable` — same as blind row 4.
  - `[low]` `[reject]` (edge) release/export exceptions unhandled — same as blind row 7.
  - `[low]` `[reject]` (edge) reattach sheet spinner forever if the picker query fails — local Drift select on two tables; no failure mode shown.
  - `[false]` `[reject]` (edge) non-String storeId crashes the provider — every queued payload is built by the app with String ids (`sale_repository_impl._buildPayload`, transfer/closure repositories).
  - `[low]` `[reject]` (edge) chains provider error hides the embedded section — same local-query reasoning; the standalone page shows the error state with retry.
  - `[false]` `[reject]` (edge) sale line with null productId cannot be re-attached — the app always sets productId and the server requires it.
  - `[low]` `[patch]` (edge) detail sheet context — same as blind row 10.
  - `[low]` `[patch]` (edge) blocked providers stale when the pull fails after the push — `_handlePushResults` invalidates them on a quarantined notice.
  - `[medium]` `[patch]` (edge) sign-out message — same as blind row 3.
  - `[low]` `[reject]` (edge) reattach to another store leaves local stock in the old store — documented in Design Notes; quarantined/pending stock keys no longer protect it, the next pull sets the true levels.
  - `[false]` `[reject]` (edge, claim) CONFLICT result deletes a quarantined chain op — CONFLICT means the server applied the op (LWW / negative stock); deleting it is correct.
  - `[medium]` `[patch]` (edge, claim) ABANDON trace pruned with the 50-row rotation — ABANDON rows excluded from the prune; test with 60 entries.
  - `[medium]` `[patch]` (gap) pull protections' quarantine filter untested — pull tests: quarantined STOCK_ADJUST/CREATE_SALE/CANCEL_SALE no longer protect stock levels or the sale.
  - `[medium]` `[patch]` (gap) real monitoring providers untested — new `sync_monitoring_providers_test.dart` on `AppDatabase.forTesting()`.
  - `[medium]` `[patch]` (gap) queue tab hosting the section untested — File tab test shows "Bloquées (1)" with a pending tile.
  - `[medium]` `[patch]` (gap) Renvoyer/Rattacher actions and reattachable filters untested — widget tests (release + sync, reattach mapping) and service test for exclusions.
  - `[medium]` `[patch]` (gap) EMPLOYEE access to `/sync/blocked` not pinned — `global_auth_redirect_test` case added.
  - `[low]` `[patch]` (gap) blocked count counts rows, list counts chains — `quarantinedSyncCount` counts chains (NOT EXISTS).
  - `[low]` `[patch]` (gap) detail row tap untested — test added (with blind row 10).
  - `[low]` `[reject]` (intent 1) "bloquée" lives on the queue row, not the sale row — the sale physically happened; quarantine is the state of its sync operation, shown wherever sync state is shown. Recorded as the PO decision.
  - `[low]` `[reject]` (intent 2) refresh only tested through the predicate — product/stock refreshes are gated solely by `hasPendingOperations()` (`product_repository_impl.dart:242`, `stock_repository_impl.dart:49`, `multi_store_stock_repository_impl.dart:32`), which is tested.
  - `[false]` `[reject]` (intent 3) EMPLOYEE exposure widens the intent — `/settings/sync` is OWNER-only, so an employee device would otherwise have no way to see or export its blocked sale; owner-only actions stay owner-only.
  - `[false]` `[reject]` (intent 4) no client re-attach / non-sale re-attach — a rolled-back client is already stripped from queued sales by 16-12; non-sale ops have no line to re-point (Design Notes).
  - `[medium]` `[patch]` (intent 5) local trace pruned — same as edge claim row.
  - `[low]` `[reject]` (intent 6) ghost-store transfer stock effect not reversed — the store's queued transfers are dropped and the pull (no longer blocked) sets the surviving store's level.
  - `[false]` `[reject]` (intent 7) extra surfaces (backend check, DIV, sign-out, detail row) — all derived from the quarantine decision and recorded in the spec.
  - `[false]` `[reject]` (intent 8) fixed in `_pushBatch` rather than `_reconcileRejection` — descriptive; the money branch lives in `_pushBatch`.

## Design Notes

- Why quarantine rather than server-side ad-hoc lines: accepting a sale line for a product that does not exist would decrement no stock and break FR27 (movement history) and reporting; the owner, who knows what was sold, re-attaches it to a real product instead.
- Why `DATA_INTEGRITY_VIOLATION` quarantines money ops: it is a DB constraint (e.g. FK `stock_levels.product_id`), deterministic for the same payload; retrying a sale forever pins the queue.
- Abandon keeps local stock untouched (goods physically left); the next server refresh, now unblocked, sets the true level.
- Re-attach is offered for `CREATE_SALE` only; other money ops (closure, stock ops, chained validation/cancel) can be retried or abandoned.
- The server trace exists for every quarantined head op (it was REJECTED → `sync_error_log`); chained ops never sent have only the export + local history entry.

## Verification

**Commands:**
- `cd keevo/app && dart run build_runner build --delete-conflicting-outputs` -- expected: success
- `cd keevo/app && flutter test --concurrency=4 test/core/sync test/features/sync_indicator test/features/auth/presentation/provider/sign_out_service_test.dart` -- expected: green
- `run-tests-chunked.sh app test/core test/features/sync_indicator test/features/auth test/features/pos test/features/settings` -- expected: only baseline failure
- `cd keevo/app && flutter analyze` -- expected: no new error/warning
- `MAVEN_OPTS=-Xmx1g mvn -o test -Dtest=SaleSyncHandlerTest,DayClosureSyncHandlerTest -DforkCount=1 -DargLine=-Xmx1536m` then `run-tests-chunked.sh backend sync` -- expected: 0 failures

## Auto Run Result

Status: done

**Summary:** PO decision (PRD FR73a): a money/stock-ledger operation refused for good is put in **quarantine** on the device (`sync_queue.quarantined_at`, schema 28) — kept, never pushed, not counted by `hasPendingOperations()` (catalog/stock refreshes run again), not protected from pull, surfaced once ("Bloqué … — action requise"). A quarantined `CREATE_SALE` carries its `VALIDATE_SALE`/`CANCEL_SALE` (chain); chain ops of a still-retrying sale keep retrying. The list "Bloquées" lives in Paramètres › Synchronisation › File (OWNER) and on `/sync/blocked` (all roles, reached from the SnackBar and the sync detail sheet). Actions: Renvoyer and Exporter (all), Rattacher a sale to local products/store and Abandonner after a non-dismissed export (OWNER; abandon removes the chain and its local sale/movements/transfer/closure rows and leaves a never-pruned `ABANDON` sync-history entry). Server-side ad-hoc lines were rejected. Backend `SaleSyncHandler`/`DayClosureSyncHandler` refuse an unknown store with `STORE_NOT_FOUND` (previously stored as orphans). Ghost-store rollback now deletes its local `stock_transfers`. Sign-out warns about blocked operations. App 1.1.22+21.

**Files changed:**
- `keevo/app/lib/core/storage/{sync_queue_table,app_database}.dart` -- `quarantinedAt` column, schema 28 migration.
- `keevo/app/lib/core/sync/sync_rejection_policy.dart` -- `quarantinesOnRejection`, `saleChainOperations`, contract doc.
- `keevo/app/lib/core/sync/rest_sync_service.dart` -- quarantine path, chain handling, filters, ghost-store transfers, `quarantinedOperationCount()`.
- `keevo/app/lib/core/sync/sync_quarantine_service.dart` (new) -- release / reattach / export / abandon.
- `keevo/app/lib/core/sync/{sync_service,sync_monitoring_providers,sync_trigger_notifier,sync_event_logger}.dart` -- interface, providers, invalidation, ABANDON log.
- `keevo/app/lib/features/sync_indicator/presentation/{widget/quarantined_operations_section.dart,page/blocked_operations_page.dart}` (new), `sync_settings_page.dart`, `sync_indicator.dart`, `sync_detail_bottom_sheet.dart`, `core/router/app_router.dart` -- UI and `/sync/blocked`.
- `keevo/app/lib/features/auth/presentation/provider/sign_out_service.dart`, `features/settings/presentation/page/settings_page.dart` -- sign-out guard and text.
- `keevo/backend/.../handler/{SaleSyncHandler,DayClosureSyncHandler}.java` + tests -- `STORE_NOT_FOUND`.
- Tests: rejection, pull, quarantine service, monitoring providers, section widget, settings/queue tab, detail sheet, route guard, sign-out, app_database; `prd.md` FR73a; `epic-16-context.md`; `pubspec.yaml` 1.1.22+21.

**Review:** 1 pass, 45 findings — patched 22 rows in 15 entries (11 medium entries, 4 low), 0 deferred, 23 rejected with reasons in the triage log (9 false, 14 low/descriptive).

**Follow-up review recommended:** true — 11 medium entries patched; the chain-join logic (`_push` pre-UPDATE + REJECTED chain branch) and the abandon cascade are covered by in-memory unit tests only, not by a device run with a real multi-batch queue and a v27 database upgraded in place.

**Verification:** targeted files green (rejection 32, pull 34, quarantine service 13, providers 1, settings page 8, section 7, router+detail 17, misc 59). Chunked app: `test/core` 338 passed / 1 failed (baseline `offline_gate_banner_test`), `sync_indicator` 58, `auth` 79, `settings` 36, `pos` 206, `catalog` 109, `inventory` 172, `stores` 45 — all OK. `flutter analyze` 630 issues (= baseline, 9 pre-existing warnings, none in changed files). Backend chunks: `sync` 421, `shared` 386, `architecture` 3 — 0 failures/errors.

**Residual risks:** a re-attached sale's queued `VALIDATE_SALE.productIdRemappings` is not rewritten (a refusal re-quarantines it with a notice); sales screens refresh after abandon only on the next sync cycle or reopen; local stock after abandon/re-attach stays approximate until the next pull; the `.g.dart` riverpod hash of `quarantinedSyncCount` regenerates at the next build_runner run (gitignored).
