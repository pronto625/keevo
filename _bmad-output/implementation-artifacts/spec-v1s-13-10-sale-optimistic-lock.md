---
title: 'v1s-13-10 — Optimistic lock on Sale (@Version + 409) and deterministic SALE movement ids on validation'
type: 'bugfix'
created: '2026-10-11'
status: 'done'
baseline_revision: '502965056e301c8a22b5901d6f97ab2981dc26d5'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-13-context.md'
warnings: [oversized]
deferred:
  - summary: >-
      SALE_CANCELLED and ADJUSTMENT movements still get random ids (backend and app), so a pull after an owner cancel/correct duplicates the history row.
    evidence: |-
      ValidateSaleService (cancel) / CorrectSaleService call the 7-arg recordOperation (random id); the app's _adjustStock uses Uuid().v4(). Outside the intent (SALE movements only). Routed to v1s-13-13-residual-check-then-act-races.
    location: >-
      keevo/app/lib/features/pos/data/datasource/local_sale_datasource.dart (_adjustStock)
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Concurrent cancel/correct on the same COMPLETED sale can overwrite each other: a correction that read the sale before a cancel commits re-saves it as COMPLETED (resurrecting a cancelled sale, stock double-adjusted), two corrections both apply their ADJUSTMENT deltas, and a cancel that read pre-correction quantities restores the wrong amount. Separately, validating a PENDING sale (manual or cascade, backend and app) writes SALE movements with random ids, so the device's pull adds a second SALE movement.

**Approach:** Mirror v1s-13-1: `@Version long version` on `sales` (JPA entity + domain `Sale`), every write path bumps it, the COMPLETED→CANCELLED claim and the correction save are version-checked, and a lost race surfaces as `OptimisticLockingFailureException` → existing 409 `OPTIMISTIC_LOCK`. SALE movements written on validation reuse the sale item id on both sides (as v1s-13-8 did for RecordSaleService).

## Boundaries & Constraints

**Always:** primitive `long` version (Hibernate 6.6 still inserts on merge of an assigned-id entity with a primitive version — same as `StockLevelJpaEntity`); keep the v1s-13-9 conditional status claims before any stock write; stock rolls back with the losing transaction; new Flyway migration = V12; tenant schemas get the column via `TenantSchemaProvisioner` + `TenantSchemaSyncService` constants (V5 pattern); existing 11-arg `Sale` constructor keeps working (version 0).

**Never:** pessimistic row locks or advisory locks for this story; version checks on the PENDING_VALIDATION claims (status CAS is sufficient there, PENDING sales cannot be corrected); new ErrorCode (reuse `OPTIMISTIC_LOCK`); touching deferred-work.md; sync-protocol changes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Correct after concurrent cancel | correct read COMPLETED v0, cancel commits first (v1) | correct fails, its ADJUSTMENT movements roll back, sale stays CANCELLED | 409 OPTIMISTIC_LOCK |
| Two concurrent corrections | both read v0 | first commits (v1), second fails and rolls back | 409 OPTIMISTIC_LOCK |
| Cancel after concurrent correct | cancel read v0 items, correct commits v1 | versioned claim updates 0 rows, sale still COMPLETED → no SALE_CANCELLED movement | 409 OPTIMISTIC_LOCK |
| Double cancel | second cancel loses the claim, sale CANCELLED | unchanged (13-5/13-9) | SALE_ALREADY_CANCELLED |
| New sale | RecordSaleService saves version 0 | row inserted with version 0 | none |
| Manual validation / cascade | PENDING sale with items i1,i2 | SALE movements have ids i1,i2 (backend and app) | none |
| Cascade loses race | sale no longer PENDING when cascade claims it | sale skipped, no stock movement, no event | none (skip + debug log) |

</intent-contract>

## Code Map

Backend (`keevo/backend/src/main/java/com/keevo/`):
- `commerce/sale/adapter/out/persistence/entity/SaleJpaEntity.java` -- add `@Version @Column(name="version", nullable=false) private long version;` + accessors.
- `commerce/sale/domain/model/Sale.java` -- add `private final long version` + `getVersion()`; new 12-arg constructor (version last), existing 11-arg delegates with 0 (used by `SaleFactory`, tests).
- `commerce/sale/adapter/out/persistence/impl/SaleRepositoryAdapter.java` -- `toJpaEntity` sets version, `toDomain` reads it; `save` uses `saveAndFlush` so a stale version fails inside the service call; implement new versioned claim.
- `commerce/sale/adapter/out/persistence/jpa/SaleSpringRepository.java:85-91` -- `updateStatusIfCurrent` sets `s.version = s.version + 1`; new `updateStatusIfCurrentAndVersion(id, expected, expectedVersion, newStatus, now)` with `AND s.version = :expectedVersion`.
- `commerce/sale/domain/port/out/SaleRepository.java` -- new `boolean updateStatusIfCurrent(UUID saleId, SaleStatus expected, long expectedVersion, SaleStatus newStatus)` (doc: also bumps version).
- `commerce/sale/application/service/ValidateSaleService.java:163-185` -- COMPLETED branch uses the versioned claim with `sale.getVersion()`; on false reload: CANCELLED → `SALE_ALREADY_CANCELLED`, otherwise throw `org.springframework.dao.OptimisticLockingFailureException`. `:120-129` SALE movement via 9-arg `recordOperation(..., item.getId(), false)`.
- `commerce/sale/application/service/CorrectSaleService.java:111-115` -- corrected `Sale` built with `sale.getVersion()` (12-arg ctor).
- `commerce/sale/application/service/SaleValidationCascadeService.java:92-104` -- replace `updateStatus` by `updateStatusIfCurrent(PENDING_VALIDATION→COMPLETED)`; false → return false (debug log); SALE movement id = `item.getId()` (allowNegative false, unchanged semantics).
- `shared/infrastructure/web/GlobalExceptionHandler.java:172` -- read-only: already maps `OptimisticLockingFailureException` (incl. `ObjectOptimisticLockingFailureException`) to 409 `OPTIMISTIC_LOCK` with a French message.
- `src/main/resources/db/migration/V12__sale_optimistic_lock.sql` -- `ALTER TABLE sales ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;` (public template).
- `shared/infrastructure/persistence/TenantSchemaProvisioner.java` -- `DDL_SALES_MIGRATE_VERSION` constant, executed after `DDL_SALES_MIGRATE_UPDATED_AT` (~line 882); `TenantSchemaSyncService.java:~238` executes it too.

App (`keevo/app/`):
- `lib/features/pos/data/datasource/local_sale_datasource.dart` -- `validateAndDecrementStock` (~line 386) and `cascadeValidatePendingSales` (~line 476) SALE movement `id: item.id`; cascade status write conditional on `status == 'PENDING_VALIDATION'` and skipped (no stock change) when 0 rows.
- `lib/features/pos/presentation/page/sale_detail_page.dart:~255` -- `_runOwnerAction` catch: on `DioException` with status 409 also call `_refreshAfterOwnerAction()` (silent refresh, 13-1 convention).
- `pubspec.yaml` -- 1.1.17+16 → 1.1.18+17.

## Tasks & Acceptance

**Execution:**
- `V12__sale_optimistic_lock.sql`, `TenantSchemaProvisioner.java`, `TenantSchemaSyncService.java` -- version column everywhere.
- `SaleJpaEntity.java`, `Sale.java`, `SaleRepository.java`, `SaleSpringRepository.java`, `SaleRepositoryAdapter.java` -- versioned persistence + versioned claim; all status CAS bump version.
- `ValidateSaleService.java`, `CorrectSaleService.java`, `SaleValidationCascadeService.java` -- items 1-3 backend.
- `local_sale_datasource.dart`, `sale_detail_page.dart`, `pubspec.yaml` -- item 3 app + 409 refresh.
- Unit tests: `CorrectSaleServiceTest` (version propagated), `ValidateSaleServiceTest` (versioned claim; lost claim COMPLETED → `OptimisticLockingFailureException`, CANCELLED → `SALE_ALREADY_CANCELLED`, no stock op; validate SALE movement id = item id), `SaleValidationCascadeServiceTest` (movement id = item id; lost claim → no stock op/event); fix compile of other tests touching changed signatures.
- NEW `src/test/java/com/keevo/commerce/sale/adapter/out/persistence/SaleOptimisticLockPostgresIntegrationTest.java` -- `@DataJpaTest(ddl-auto=validate)` + Testcontainers postgres:16-alpine (pattern `ConcurrencyPrimitivesPostgresIntegrationTest`), `@Import(SaleRepositoryAdapter)`: new sale inserted with version 0; status CAS bumps version; stale save after a concurrent claim → `ObjectOptimisticLockingFailureException`; versioned claim with stale version → false, current → true.
- App tests in `test/features/pos/data/datasource/local_sale_datasource_test.dart`: validate and cascade write SALE movements with id = item id; cascade skips a sale no longer PENDING.

**Acceptance Criteria:**
- Given any matrix row, when the scenario runs, then the outcome matches and a unit or integration test proves it.
- Given the HTTP surface, when a correct/cancel loses a version race, then `POST /api/v1/sales/{id}/correct|cancel` answers 409 with domainCode `OPTIMISTIC_LOCK` (existing handler, proven by exception type + `GlobalExceptionHandlerTest`).
- Given the full backend suite, when run, then failures/errors are no worse than the baseline (7 failures + 29 errors in the 10 listed classes); `flutter analyze` adds no issue and `flutter test` keeps only the known `offline_gate_banner_test` failure.
- Given the epic brief, when done, then the 3 items of Story 13.10 are ticked with the commit and decision.

## Spec Change Log

## Review Triage Log

### 2026-10-11 — Review pass
- verdicts: 31 findings — high 0, medium 9, low 11, false 9, maybe-false 2
- findings:
  - `[low]` `[reject]` (blind) Version never reaches the client; two owners correcting from stale screens = last write wins — corrections are absolute quantities and stock deltas are recomputed server-side from the fresh read, so stock stays consistent and the audit log keeps both; fixing needs new public API/sync surface (version in DTO, pull, Drift) beyond the "mirror 13-1" intent; rare in practice.
  - `[medium]` `[patch]` (blind) 409 "silent refresh" re-reads stale local Drift and `_locallyUpdated` blocks didUpdateWidget — fixed: `_resyncAfterConflict()` = best-effort `SyncService.pull()`, reset `_locallyUpdated`, then invalidate; widget test added.
  - `[low]` `[patch]` (blind) app `validateAndDecrementStock` only guards COMPLETED, a locally CANCELLED sale would be decremented and rewritten COMPLETED — fixed: return unless `PENDING_VALIDATION`; test added.
  - `[medium]` `[patch]` (blind) deterministic SALE movement ids inserted with plain insert → PK conflict if a pull already wrote the row — fixed: `insertOnConflictUpdate` in validate + cascade; test added.
  - `[false]` `[reject]` (blind) cascade's `clearAutomatically` loses the publisher's managed changes — only publisher is `UpdateProductUseCase:131`; it flushes first (`flushAutomatically`) and afterwards only uses the domain `saved` object to publish another event; no managed entity is mutated after the event.
  - `[low]` `[patch]` (blind) `never()` on 7-arg `recordOperation` is vacuous now that the 9-arg overload is called — fixed: `verifyNoInteractions(stockOperationService)` (6 places).
  - `[low]` `[reject]` (blind) matrix row 1 rollback of ADJUSTMENT movements not proven at service level — the stale `saveAndFlush` throws inside the `@Transactional` `correctSale` (unit test proves propagation, integration test proves the stale save throws on PostgreSQL); RuntimeException rollback is framework-guaranteed; a full service-on-DB harness would be disproportionate.
  - `[low]` `[patch]` (blind) Spring DAO exception built in the application service — fixed: `DomainException(ErrorCode.OPTIMISTIC_LOCK)` (already mapped to 409).
  - `[false]` `[reject]` (blind) `lostCompletedRace` misreports null/PENDING status — sales are never deleted and COMPLETED never goes back to PENDING_VALIDATION; unreachable.
  - `[low]` `[reject]` (blind) port inconsistency (default non-atomic 3-arg CAS, unused `updateStatus`) — no caller diverges (adapter overrides both); cosmetic cleanup, no named harm.
  - `[medium]` `[patch]` (blind) tenant-schema `DDL_SALES_MIGRATE_VERSION` untested — fixed: `TenantSchemaSyncServiceTest.should_add_sales_version_column` + new `TenantSchemaProvisionerSaleVersionDDLTest`.
  - `[false]` `[reject]` (blind) epic items ticked without commit hash / context says in progress — finalization step adds the commit hash and marks done; not a code defect.
  - `[low]` `[reject]` (blind) `saveAndFlush` also flushes RecordSaleService inserts — the INSERT would be flushed by the following stock writes/commit anyway; negligible cost, deliberate (Design Notes).
  - `[medium]` `[patch]` (edge) 409 refresh reloads stale local sale — same root cause as blind #2; fixed there.
  - `[medium]` `[patch]` (edge) `_locallyUpdated` ignores refreshed sale after a 409 — same root cause as blind #2; fixed there.
  - `[low]` `[reject]` (edge) backend cascade throws INSUFFICIENT_STOCK when 0 < available < qty (allowNegative=false) — pre-existing (7-arg overload already passed false), and PENDING_VALIDATION sales are unreachable from clients since the 2026-05-03 redesign (v1s-15-5 note); not worth new branching here.
  - `[medium]` `[patch]` (edge) app validate PK conflict on pre-pulled movement — same root cause as blind #4; fixed.
  - `[medium]` `[patch]` (edge) app cascade loop aborted by PK conflict — same root cause as blind #4; fixed.
  - `[low]` `[patch]` (edge) vacuous `never()` in SaleValidationCascadeServiceTest:112 — same as blind #6; fixed.
  - `[medium]` `[patch]` (verification-gap) tenant DDL not verified — same as blind #11; fixed.
  - `[low]` `[patch]` (verification-gap) 409 refresh branch untested — fixed: widget test "cancel 409: pulls, re-reads the sale and shows the error".
  - `[medium]` `[patch]` (verification-gap other) 409 refresh does not show current sale — same as blind #2; fixed.
  - `[low]` `[patch]` (verification-gap other) "no longer PENDING" app test overstates — renamed + comment points to the concurrent-cascade test (mutation-proven by the reviewer).
  - `[false]` `[reject]` (intent) service-level two-transaction race not exercised — duplicate of blind #7 framing; adapter-level PostgreSQL test covers the version mechanics and unit tests the service wiring.
  - `[false]` `[reject]` (intent) 409 only inferred via GlobalExceptionHandlerTest — spec AC chose this surface; `OPTIMISTIC_LOCK` mapping to 409 is tested for both the DAO exception and the domain code.
  - `[false]` `[reject]` (intent) offline sync cancel now REJECTED on version loss — sync passes `pendingOnly=true`: COMPLETED → FORBIDDEN before any claim, and the PENDING claim is status-only; the versioned claim is unreachable from sync.
  - `[false]` `[reject]` (intent) app 409 handling untested — now covered by the new widget test.
  - `[maybe-false]` `[reject]` (intent) no pull round-trip test for item 3 — pull upsert-by-id was established and tested by v1s-13-8; if wrong it would only be low (duplicate history row).
  - `[maybe-false]` `[reject]` (intent) app movement quantity vs server `effectiveDecrement` differ — same id means the pull replaces the local row with the server's values; would only be low.
  - `[false]` `[reject]` (intent) concurrent-cascade app test serialises — reviewer's mutation run showed it fails without the conditional claim, so it does prove the claim.
  - `[false]` `[reject]` (intent) scope additions (saveAndFlush, version bump on CAS, app refresh, pubspec bump) — all required by the spec/project conventions.

### 2026-10-13 — Review pass (follow-up)
- verdicts: 31 findings — high 0, medium 4, low 14, false 13, maybe-false 0
- findings:
  - `[low]` `[reject]` (intent) correction rollback of ADJUSTMENTs and the 409 body not exercised end to end — carried: stale save proven on PostgreSQL, propagation inside the `@Transactional` service by unit test, mapping by `GlobalExceptionHandlerTest`.
  - `[low]` `[reject]` (intent) cancel-after-correction "no stock restored" only mocked — carried: the versioned claim is proven on PG; `verifyNoInteractions(stockOperationService)` pins the service.
  - `[low]` `[reject]` (intent) `remapItemProductIds` does not bump `sales.version` — only runs inside PENDING validation, whose claims are status-only by design (Design Notes).
  - `[low]` `[reject]` (intent) no pull round-trip test — carried (pull upsert-by-id from v1s-13-8).
  - `[false]` `[reject]` (intent) app manual validation reads then checks instead of a conditional claim — it runs inside one Drift transaction (SQLite single writer), so nothing interleaves.
  - `[false]` `[reject]` (intent) 409 page handler beyond the intent — carried: the 13-1 convention for the HTTP surface the intent names.
  - `[false]` `[reject]` (intent) cancel path throws `DomainException(OPTIMISTIC_LOCK)`, not the DAO exception — same 409 `OPTIMISTIC_LOCK` body.
  - `[false]` `[reject]` (intent) patch carries other stories' hunks — follow-up diff = baseline→current code.
  - `[low]` `[patch]` (verification) 409 resync test never set `_locallyUpdated` nor a failing pull — two widget tests: correction then 409 with the server now CANCELLED → page shows the server state (mutation-checked: fails without the override reset); pull throwing → error shown and sale re-read.
  - `[false]` `[reject]` (verification other) review base — same as the scope row.
  - `[false]` `[reject]` (blind) diff wider than 13-10 — same.
  - `[false]` `[reject]` (blind) `pubspec.yaml` bump missing — the review diff filtered `.yaml`; the story commit bumped it. This follow-up bumps 1.1.40+39 → 1.1.41+40.
  - `[false]` `[reject]` (blind) patch depends on code it does not include — those symbols exist in the tree (v1s-13-8 / 18-6).
  - `[low]` `[reject]` (blind) correction checks the version after the ADJUSTMENT writes (a stock error can mask the sale conflict) — needs two owners correcting/cancelling the same sale at once; the losing transaction still rolls back entirely; a versioned claim first is new surface for a rare case.
  - `[medium]` `[defer]` (blind) SALE_CANCELLED/ADJUSTMENT movements keep random ids → duplicate history after a pull — outside the intent (SALE only); routed to v1s-13-13-residual-check-then-act-races.
  - `[medium]` `[patch]` (blind) `insertOnConflictUpdate` overwrote a pulled server SALE movement with local values — app validate + cascade now skip an item whose SALE movement (id = item id) is already cached: the server row is kept and stock is not decremented twice; plain insert otherwise; tests for both paths.
  - `[low]` `[patch]` (blind) 409 resync awaits an unbounded pull while the owner buttons are disabled — `pull().timeout(15 s)` (caught like any pull failure); widget test with a hanging pull.
  - `[low]` `[patch]` (blind) app 409 test gaps — grouped with the verification row (correct-409 / non-409 share the same catch; not duplicated).
  - `[low]` `[reject]` (blind) integration-test gaps (remap bump, right version wrong status, Javadoc V1–V12) — remap: see intent row; wrong status is the same SQL predicate; Javadoc cosmetic.
  - `[low]` `[reject]` (blind) Code Map/Tasks still name `OptimisticLockingFailureException` — the fix would be a spec edit; the triage log already records the change.
  - `[low]` `[reject]` (blind) leftover non-atomic `updateStatus` / default 3-arg CAS — carried.
  - `[false]` `[reject]` (blind) `lostCompletedRace` misreports a missing sale — carried: sales are never deleted.
  - `[false]` `[reject]` (edge) async notification for ADJUSTMENTs later rolled back — `StockModificationNotificationListener` skips non-EMPLOYEE actors and cancel/correct are OWNER-only.
  - `[low]` `[reject]` (edge) backend cascade INSUFFICIENT_STOCK when 0 < stock < qty — carried (pre-existing, PENDING path unreachable from current clients).
  - `[medium]` `[patch]` (edge) pulled movement + still-PENDING local sale → double decrement — grouped with the insertOnConflictUpdate row.
  - `[medium]` `[patch]` (edge) pulled server row overwritten — grouped with the same row.
  - `[low]` `[reject]` (edge) local SALE movement quantities differ from the backend's `effectiveDecrement` — the pull replaces the row by id when the server has one; when the server skipped it (0 stock) the local row is a cosmetic history line.
  - `[low]` `[patch]` (edge) hanging pull keeps buttons disabled — grouped with the timeout row.
  - `[false]` `[reject]` (edge) sale deleted between claim and re-read — carried.
  - `[false]` `[reject]` (edge claim) exception type vs spec — same as the intent row.
  - `[false]` `[reject]` (edge claim) pubspec — same as the blind row.

## Design Notes

- Why the cancel claim checks the version, not just the status: a correction does not change the status, so a status-only CAS lets a cancel that read pre-correction items restore the wrong quantities. The PENDING claims stay status-only: PENDING items change only through the validator's own remap in the same transaction.
- `saveAndFlush` in the adapter: the stale-version UPDATE fails inside `correctSale` (before events) instead of at commit; both surface as `OptimisticLockingFailureException` → 409.
- Bulk JPQL updates bypass Hibernate versioning, so `updateStatusIfCurrent` increments `version` explicitly.
- Cascade lost claim = skip, not error: it runs inside a product activation / stock change, which must not fail because another actor already settled the sale.

## Verification

**Commands:**
- `cd keevo/backend && mvn -q test -Dtest='CorrectSaleServiceTest,ValidateSaleServiceTest,SaleValidationCascadeServiceTest,SaleOptimisticLockPostgresIntegrationTest,GlobalExceptionHandlerTest,TenantSchema*Test'` -- expected: green.
- `cd keevo/backend && mvn test` -- expected: baseline only.
- `cd keevo/app && flutter analyze && flutter test` -- expected: no new issue; only known failure.

## Auto Run Result

**Summary:** `Sale` is now optimistically locked (`@Version long version`, Flyway V12 + tenant DDL). Every status CAS bumps the version; the COMPLETED→CANCELLED claim is version-checked (lost: CANCELLED → `SALE_ALREADY_CANCELLED`, otherwise `DomainException(OPTIMISTIC_LOCK)` → 409); the correction saves with the version it read (`saveAndFlush` → `ObjectOptimisticLockingFailureException` → 409, ADJUSTMENTs roll back). SALE movements written on validation (manual + cascade, backend + app) use the sale item id; both cascades claim PENDING_VALIDATION→COMPLETED conditionally. The app's sale detail pulls + re-reads on 409. App 1.1.18+17.

**Files changed:**
- `keevo/backend/.../SaleJpaEntity.java`, `Sale.java` -- version field (12-arg ctor; 11-arg = version 0).
- `SaleRepository.java`, `SaleSpringRepository.java`, `SaleRepositoryAdapter.java` -- version mapping, `saveAndFlush`, CAS bumps version, new versioned claim.
- `ValidateSaleService.java`, `CorrectSaleService.java`, `SaleValidationCascadeService.java` -- versioned cancel claim, versioned correction, conditional cascade, SALE movement id = item id.
- `V12__sale_optimistic_lock.sql`, `TenantSchemaProvisioner.java`, `TenantSchemaSyncService.java` -- `sales.version` column everywhere.
- `keevo/app/.../local_sale_datasource.dart` -- SALE movement id = item id (`insertOnConflictUpdate`), validate only from PENDING_VALIDATION, conditional cascade claim.
- `keevo/app/.../sale_detail_page.dart` -- 409 → pull + reset local override + re-read.
- Tests: `SaleOptimisticLockPostgresIntegrationTest` (new), `TenantSchemaProvisionerSaleVersionDDLTest` (new), `TenantSchemaSyncServiceTest`, `ValidateSaleServiceTest`, `CorrectSaleServiceTest`, `SaleValidationCascadeServiceTest`, `SaleRepositoryAdapterUpdatedAtTest`, app `local_sale_datasource_test`, `sale_detail_page_test`.

**Review findings:** 31 findings (0 high, 9 medium, 11 low, 9 false, 2 maybe-false). Patched: 3 medium entries (409 refresh shows stale local sale; deterministic-id PK conflict on pre-pulled movement; tenant DDL untested) and 5 low entries (validate from CANCELLED, vacuous `never()` checks, DAO exception in service → domain code, 409 widget test, mislabelled app test). Deferred: none. Rejected: see triage log (stale-screen last-write-wins = outside the server-side "mirror 13-1" scope and stock-safe; pre-existing cascade INSUFFICIENT_STOCK on unreachable PENDING path; framework-guaranteed rollback; false positives on clearAutomatically, sync path, lostCompletedRace).

**Follow-up review recommended:** true — 3 medium entries patched; specific unverified risk: the review-added 409 resync path in `sale_detail_page.dart` (`SyncService.pull()` from a page action, then local override reset) was not itself reviewed by an independent pass.

**Verification:** backend `mvn test` = 2149 tests, 7 failures + 29 errors, exactly the 10 baseline classes (no new failure); targeted classes green (incl. Testcontainers PostgreSQL 16 integration test). `flutter test` = 1336 passed, 1 failed (known `offline_gate_banner_test`). `flutter analyze` = 630 issues, identical to pre-change count, none in touched files.

**Residual risks:** concurrent stale-screen corrections remain last-write-wins (version not exposed to clients); backend cascade can still throw INSUFFICIENT_STOCK when 0 < stock < qty (pre-existing, PENDING path unreachable from current clients).

### Follow-up review pass — 2026-10-13

**Summary:** fresh 4-layer review focused on the post-review app path « pull + re-read on 409 ». The reset of the local override is now proven by a widget test (correction, then a 409 with the server sale CANCELLED → the page shows CANCELLED; mutation-checked), as are a failing and a hanging pull (pull bounded to 15 s). App validation/cascade no longer overwrite a pulled server SALE movement nor decrement stock a second time when that movement is already cached. App 1.1.41+40.

**Files changed:** `keevo/app/lib/features/pos/presentation/page/sale_detail_page.dart` (pull timeout), `keevo/app/lib/features/pos/data/datasource/local_sale_datasource.dart` (pulled movement wins), `keevo/app/pubspec.yaml`; tests `sale_detail_page_test.dart` (+3), `local_sale_datasource_test.dart` (+1, 1 strengthened).

**Review findings:** 31 (0 high, 4 medium, 14 low, 13 false). Patched: 1 medium entry (pulled movement) + 2 low entries (409 tests, pull timeout). Deferred: 1 medium → v1s-13-13-residual-check-then-act-races (deterministic SALE_CANCELLED/ADJUSTMENT ids). Rejected: see the 2026-10-13 triage entry.

**Follow-up review recommended: false** — follow-up pass, no high patched (patched: 0 high, 1 medium, 2 low).

**Verification:** `sale_detail_page_test.dart` + `local_sale_datasource_test.dart` green; app batch `test/features/pos` 233 tests, 0 failure; `flutter analyze` 0 error / 0 warning (625 infos).

**Residual risks:** duplicate SALE_CANCELLED/ADJUSTMENT history rows after a pull until v1s-13-13; concurrent stale-screen corrections stay last-write-wins.
