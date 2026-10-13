---
title: 'v1s-13-13 — Residual check-then-act races (plan limits, weekly report, movement ids, commit lag)'
type: 'bugfix'
created: '2026-10-13'
status: 'done'
baseline_revision: '7c28ada54f3e06b34153ffb53aa491f2af6fed3d'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-13-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-v1s-13-9-scheduler-locking-races.md'
warnings: ['multiple-goals', 'oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Four residual races routed by the 2026-10-13 follow-up reviews of 13.9/13.10/13.11: (1) store and employee plan limits are count → save without a lock, so two concurrent creates at max−1 both pass `PlanLimitGuard`; (2) the weekly report "already generated" guard is check-then-act (ShedLocked scheduler vs owner's manual trigger) → duplicate report for a store/week; (3) SALE_CANCELLED and ADJUSTMENT movements get random ids on backend and app → after an owner cancel/correct, the pull adds a second history row; (4) a sale whose `created_at` is stamped before a closure/weekly report's `receivedUntil` but commits after the report queries ran is excluded from that report and the next one.

**Approach:** reuse the 13.9 `TransactionLockPort` (pg_advisory_xact_lock, MANDATORY tx) for (1) and (2); derive RFC 4122 UUIDv5 movement ids identically on both sides for (3); apply a 5 s commit-safety margin to `receivedUntil` (persisted as the chain anchor) for (4). Each race is proven on a real PostgreSQL.

## Boundaries & Constraints

**Always:** lock taken BEFORE the count/guard read and inside the caller's transaction; lock keys `store-limit:<schema>`, `employee-limit:<schema>`, `weekly-report:<storeId>:<sunday ISO date>`; every new lock-taking entry point listed in `SchedulerLockCoverageTest.lockTakingUseCasesAreTransactional`; UUIDv5 namespace `4b6f1d2e-8c3a-4e5f-9a7b-1c2d3e4f5a6b`, names `SALE_CANCELLED:<saleItemId>` and `ADJUSTMENT:<correctionId>:<saleItemId>` (lower-case canonical UUID strings) — byte-identical on Java and Dart; app version bump 1.1.42+41 → 1.1.43+42; no technical ids shown to users (rule 11).

**Never:** no schema/DDL change in tenant schemas (no unique index); no new Flyway migration; no change to the product-limit code of 13.9; no running of full mvn/flutter suites; no change of the closure "exactly-once" predicate in `ClosureWindow.covers`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Store limit race | FREE plan (1 store), 0 active, two creates (REST or CREATE_STORE sync) concurrently | exactly one store saved | other → `PLAN_LIMIT_EXCEEDED` (sync: REJECTED) |
| Sync store replay race | same CREATE_STORE id pushed twice concurrently | one store, both APPLIED | lock taken before the idempotency `findById` |
| Employee limit race | plan at max−1 employees, two creates concurrently | exactly one employee | other → plan-limit DomainException, tx rolled back (no user/membership left) |
| Weekly report race | scheduler + manual trigger for same store/Sunday | one WEEKLY report row | loser returns the existing report (no second WhatsApp send) |
| Cancel movement id | owner cancels COMPLETED sale online | backend SALE_CANCELLED id = v5(`SALE_CANCELLED:<itemId>`); app writes same id; pull INSERT OR IGNORE → one row | — |
| Correction movement id | app sends `correctionId` (v4) with POST /correct | backend ADJUSTMENT id = v5(`ADJUSTMENT:<correctionId>:<itemId>`), app same | `correctionId` absent (old app) → server random id (legacy behaviour) |
| Pull arrived first | movement id already in Drift when local cancel/correct writes | local stock NOT adjusted again, no second row | — |
| Commit lag | sale `created_at` = T ≤ R, commits after report at R | not in report R (receivedUntil = R − 5 s < T), counted once in the next one (createdAt > lateFrom = R − 5 s) | sale stamped > 5 s before a still-uncommitted commit remains a documented residual |

</intent-contract>

## Code Map

Backend (`keevo/backend/src/main/java/com/keevo/`):
- `shared/application/port/TransactionLockPort.java`, `shared/infrastructure/persistence/PostgresTransactionLockAdapter.java` -- reuse as is (MANDATORY propagation).
- `store/store/application/service/CreateStoreService.java:49-56` -- already `@Transactional`; inject `TransactionLockPort`; `lock("store-limit:" + TenantContext.getCurrentTenant())` before `findActivePlan`/`countActive()`.
- `sync/sync/application/handler/CreateStoreSyncHandler.java:73-92` -- inject the port; lock right after the OWNER guard, BEFORE the idempotency `findById`. Runs inside `SyncPushService` per-op `transactionTemplate.execute` (`sync/sync/application/service/SyncPushService.java:97`) → MANDATORY satisfied.
- `identity/employee/application/service/CreateEmployeeService.java:72-87` -- already `@Transactional`; lock `employee-limit:<schema>` before `countActiveEmployees()` (JdbcTemplate count in `subscription/plan/adapter/out/persistence/impl/EmployeeCountAdapter.java` sees the winner's commit, released only at commit).
- `reporting/report/application/service/WeeklyReportGenerator.java:68-88` -- `generateWeeklyReport` is the bean entry point (its call to `generateReport` is a self-invocation, so today there is NO transaction). Add `@Transactional` + inject `TransactionLockPort`; `lock("weekly-report:" + storeId + ":" + weekEnd.atZone(WAT).toLocalDate())` first; the existing `findByKey` idempotency guard in `AbstractReportGenerator.generateReport:56-63` (actorId null handled, `EndOfDayReportSpringRepository:55-62`) then sees the winner's row and returns it. Callers: `WeeklyReportScheduler.processTenant:131-148` (catches per store), `ReportController.triggerWeekly:182-209`.
- NEW `shared/domain/DeterministicIds.java` -- `uuidV5(UUID ns, String name)` (SHA-1, version 5, IETF variant), `saleCancelledMovementId(UUID itemId)`, `saleCorrectionMovementId(UUID correctionId, UUID itemId)`.
- `commerce/sale/application/service/ValidateSaleService.java:182-187` -- cancel loop → 9-arg `recordOperation(..., DeterministicIds.saleCancelledMovementId(item.getId()), false)`.
- `commerce/sale/domain/port/in/CorrectSaleUseCase.java` -- add `UUID correctionId` to `CorrectSaleCommand` (keep a 5-arg constructor → null); `adapter/in/rest/dto/CorrectSaleRequestDto.java` + `PendingSaleController.java:98` -- optional `correctionId`.
- `commerce/sale/application/service/CorrectSaleService.java:95-103` -- movementId = `correctionId != null ? saleCorrectionMovementId(correctionId, before.getId()) : null`.
- `commerce/sale/domain/model/ClosureWindow.java` -- add `public static final Duration COMMIT_SAFETY_MARGIN = Duration.ofSeconds(5)` and `static Instant receivedLimit(Instant now)` = `now − margin`.
- `commerce/sale/application/service/CloseDayService.java:72-110` -- `receivedAt = ClosureWindow.receivedLimit(now)` used for `forClosure(closedAt, receivedAt, previous)` AND the persisted `DayClosure` receivedAt (closedAt logic unchanged).
- `reporting/report/application/service/WeeklyReportWindowResolver.java:76` -- `generatedAt = receivedLimit(now).truncatedTo(MICROS)` (so end, receivedUntil and the persisted createdAt stay one anchor).

App (`keevo/app/`):
- NEW `lib/core/sync/stock_movement_ids.dart` -- `saleCancelledMovementId(itemId)`, `saleCorrectionMovementId(correctionId, itemId)` via `Uuid().v5(namespace, name)` (uuid ^4.4.0).
- `lib/features/pos/data/datasource/local_sale_datasource.dart:177-276` -- `_adjustStock` takes `movementId`: if a `stock_movements` row with that id exists → return (pull already applied server state); else adjust stock + `insertOnConflictUpdate`. `cancelAndRestoreStock` passes `saleCancelledMovementId(item.id)`; `updateItemQuantities(saleId, quantities, {String? correctionId})` passes `saleCorrectionMovementId` (null → v4 legacy).
- `lib/features/pos/data/datasource/remote_sale_datasource.dart:69-78` -- send `correctionId`; `lib/features/pos/data/repository/sale_repository_impl.dart:337-348` -- generate v4 `correctionId`, pass to remote and local.
- Pull: `lib/core/sync/rest_sync_service.dart:1357` `_upsertStockMovements` = INSERT OR IGNORE (unchanged).
- `pubspec.yaml:4` -- `1.1.43+42`.

Tests (existing to update): `CreateStoreServiceTest`, `CreateStoreSyncHandlerTest`, `CreateEmployeeServiceTest`, `WeeklyReportGeneratorTest`, `WeeklyReportWindowResolverTest`, `CloseDayServiceTest`, `CorrectSaleServiceTest`, `ValidateSaleServiceTest`, `SchedulerLockCoverageTest`, app `local_sale_datasource_test.dart`, `sale_repository_impl_test.dart` (+ any remote mock signature).

## Tasks & Acceptance

**Execution:**
- `CreateStoreService.java`, `CreateStoreSyncHandler.java`, `CreateEmployeeService.java` -- inject port, lock before count (sync: before idempotency read) -- item 1.
- `WeeklyReportGenerator.java` -- `@Transactional` entry + `weekly-report` lock first -- item 2.
- `DeterministicIds.java`, `ValidateSaleService.java`, `CorrectSaleUseCase.java`, `CorrectSaleRequestDto.java`, `PendingSaleController.java`, `CorrectSaleService.java` -- deterministic SALE_CANCELLED/ADJUSTMENT ids -- item 3.
- `ClosureWindow.java`, `CloseDayService.java`, `WeeklyReportWindowResolver.java` -- commit-safety margin as the chained anchor -- item 4.
- App files above + `pubspec.yaml` -- same ids, skip-if-exists, insertOnConflictUpdate, correctionId -- item 3.
- Unit tests: InOrder `lock → count → save` (store service, sync handler incl. lock before `findById`, employee service), weekly generator lock before data collection/`findByKey`, `DeterministicIdsTest` with the cross-language vectors `v5(SALE_CANCELLED:11111111-1111-1111-1111-111111111111) = 953756a8-12bc-54a0-b3dc-144901d67c83` and `v5(ADJUSTMENT:22222222-2222-2222-2222-222222222222:11111111-1111-1111-1111-111111111111) = 3a397c41-8198-5a4f-8f09-9b25c36f87ac` (Python `uuid.uuid5`), cancel/correct pass the derived ids, closure/weekly windows use `now − 5 s`; add `CreateStoreService.execute`, `CreateEmployeeService.execute`, `WeeklyReportGenerator.generateWeeklyReport` to `SchedulerLockCoverageTest.lockTakingUseCasesAreTransactional`.
- NEW `src/test/java/com/keevo/shared/infrastructure/persistence/ResidualRacesPostgresIntegrationTest.java` (extends `FullContextIntegrationTest`, `@Transactional(NOT_SUPPORTED)`, own provisioned tenant via `TenantFactory` like `AccountDeletionConcurrencyPostgresIntegrationTest`) -- for store, employee and weekly report: thread A opens a tx, takes the same lock key, inserts the competing row, holds; thread B calls the production bean (REST use case; and CREATE_STORE handler inside a `TransactionTemplate`) → B must not finish within ~700 ms; A commits → B fails with the plan-limit code / returns the existing report; final row count = 1. (Counting before the lock would make B save → test fails.) Plus: cancel + correct a COMPLETED sale through the production beans → movement ids equal `DeterministicIds` values.
- Commit-lag proof on PostgreSQL (`SaleRepositoryAdapter`, in the integration test above or a `@DataJpaTest` subclass of `PostgresIntegrationTest`): sale inserted in an uncommitted tx with `created_at = R − 1 s`; `findForClosure` with `forClosure(closedAt=R, receivedLimit(R), previous)` → absent; commit; the chained next window (lateFrom = `receivedLimit(R)`) → present exactly once.
- App tests: cancel/correct write the derived ids; a pulled movement (INSERT OR IGNORE) with the same id after the local write leaves one row; when the pulled row exists first, the local write neither duplicates nor re-adjusts stock; repository sends `correctionId` and passes it to the local datasource.

**Acceptance Criteria:**
- Given a FREE tenant with 0 stores, when two store creations run concurrently (REST or sync), then exactly one store exists and the other gets the plan-limit refusal.
- Given an owner triggers the weekly report while the scheduler generates it, then one WEEKLY row exists for that store/Sunday and the owner gets 200.
- Given an owner cancels or corrects a COMPLETED sale online, when the device pulls, then the history shows one movement per item and operation.
- Given a sale committed just after a closure/weekly report ran, then it appears in exactly one of the two chained reports.
- Given the change, then `flutter analyze` keeps 0 errors / 0 warnings and every touched test batch passes.

## Spec Change Log

## Review Triage Log

### 2026-10-13 — Review pass
- verdicts: 27 findings — high 0, medium 4, low 14, false 7, maybe-false 2
- findings:
  - `[false]` `[reject]` IA: weekly lock placed in the generator, not in the scheduler/controller — both callers go through `generateWeeklyReport`, whose `findByKey` guard now runs under the lock; the callers' pre-check is only a shortcut.
  - `[low]` `[reject]` IA: race tests use a raw-SQL thread A instead of two use-case calls — the harness is deterministic and stronger (fails if the count ran before the lock, verified by disabling the store lock); a random two-call race proves less.
  - `[false]` `[reject]` IA: ADJUSTMENT id is not "itemId + type" — `itemId + type` collides on repeated corrections (PK violation); `correctionId` is the deliberate discriminator (Design Notes).
  - `[low]` `[reject]` IA/BH: commit-lag PG test builds windows by hand instead of driving CloseDayService/resolver — the margin wiring is pinned by CloseDayServiceTest / WeeklyReportWindowResolverTest / scheduler / controller unit tests; the PG test proves the query semantics.
  - `[false]` `[reject]` IA: no item rejected — all four items implemented, residual documented.
  - `[medium]` `[patch]` VG: no test pins the `correctionId` key in the real RemoteSaleDataSource request body — added `remote_sale_datasource_test.dart` (mocked Dio, present/absent cases).
  - `[low]` `[patch]` VG/ECH/BH: DeterministicIdsTest javadoc cites a non-existent `stock_movement_ids_test.dart` — repointed to `local_sale_datasource_test.dart`.
  - `[medium]` `[patch]` VG/ECH/BH: WhatsApp send + multi-store check inside the locked weekly transaction (lock held across HTTP; rollback after send → resend) — delivery and multi-store check moved to an afterCommit synchronization; follow-up verification found that writes in afterCommit join the committed tx and are lost, so they run in a REQUIRES_NEW TransactionTemplate; PG test `weeklyReport_afterCommitDelivery_statusPersisted` asserts delivery_status ≠ PENDING.
  - `[low]` `[reject]` ECH: pull inserted the server movement but skipped the stock level (pending local stock op) → local skip leaves stock without the delta — needs a pull concurrent with the REST call within milliseconds AND a pending stock op on that product; fix requires a level-freshness flag.
  - `[low]` `[reject]` ECH: correction response lost then retried → new correctionId, server/local ids differ — retry after a committed correction is a server no-op; requires a lost response; fix moves id generation to the UI intent level.
  - `[medium]` (grouped with the WhatsApp row above) `[patch]` ECH: lock and connection held across the WhatsApp HTTP call — fixed by the same afterCommit change.
  - `[low]` `[patch]` ECH: report generated in Sunday's first 5 s → end clamped after the createdAt anchor, next start before it — previousEnd now `max(min(prevCreatedAt, endOfDay(prevSunday)), prevSunday 00:00 WAT)`; resolver chain test added.
  - `[low]` `[reject]` ECH: first closure after deploy within 5 s of a legacy receivedAt=now → lateFrom > receivedUntil once — harmless (empty late range), one-off at deploy.
  - `[low]` `[patch]` BH: local deduplicated movement stays synced=false forever (never purged) — written with `synced: true, syncedAt: now` (row mirrors a server-confirmed operation); asserted in tests.
  - `[low]` `[reject]` BH: `insertOnConflictUpdate` would overwrite the pulled server row — conflict unreachable after the in-transaction existence check (Drift serialises writes); kept as the brief requested.
  - `[maybe-false]` `[reject]` BH: reused correctionId with different quantities → PK violation 500 — only a buggy/malicious client can do it (app generates a fresh v4 per request; a same-request retry is a no-op); would be low.
  - `[medium]` `[defer]` BH: employee reactivation bypasses the plan limit (no check, no lock) — pre-existing, not a race introduced here; routed to v1s-14-13-plan-free-limits-enforcement (story reopened).
  - `[low]` `[reject]` BH: concurrent creates with the same phone → raw integrity error instead of USER_ALREADY_EXISTS — pre-existing, rare, DB unique constraint still protects data; mapping is more than a direct fix.
  - `[maybe-false]` `[reject]` BH: 5 s margin is unmeasured — sale transactions are single-op; documented residual accepted in Design Notes; would be low.
  - `[low]` `[patch]` BH: `waitsOnAdvisoryLock` matched any advisory waiter on the shared DB — now filtered on the race's key via pg_locks (classid/objid = hashtextextended(key,0)).
  - `[false]` `[reject]` BH: commit-lag test bypasses production code (duplicate of IA row above, logged separately) — see IA row.
  - `[low]` `[patch]` BH: legacy-path Dart test near-tautological — now asserts two v4 ids that differ.
  - `[low]` `[reject]` BH: nullable correctionId parameter dead in production — kept for the legacy/backward-compatible path and tests; no harm.
  - `[low]` `[reject]` BH: older apps still duplicate ADJUSTMENT rows, no minimum-version gate — transitional like 13-8's SALE ids; no regression vs before.
  - `[false]` `[reject]` BH: REQUIRES_NEW on `generateReport` silently ignored — documented in the generator javadoc; the entry point owns the transaction by design.
  - `[false]` `[reject]` ECH: legacy `insertOnConflictUpdate` race guard comment misleading (dup of BH row) — comment accurate after the existence check.
  - `[false]` `[reject]` VG: backend controller test does not tie the app key name — covered by the new app remote test above.

### 2026-10-13 — Follow-up review pass
- verdicts: 23 findings — high 0, medium 5, low 16, false 2, maybe-false 0
- orchestrator check: the REQUIRES_NEW redesign of the weekly delivery is now proven — with `PROPAGATION_REQUIRES_NEW` removed from `WeeklyReportGenerator.afterCommitTx`, `ResidualRacesPostgresIntegrationTest.weeklyReport_afterCommitDelivery_statusPersisted` fails (status stays `PENDING`); restored, it passes.
- findings:
  - `[low]` `[reject]` intent rows 1–3: the PG race uses a hand-written competitor holding the same lock — it proves the production bean waits on the key and sees the commit; lock-before-count order is pinned by InOrder unit tests on every entry point.
  - `[low]` `[reject]` intent row 4: "no second WhatsApp" only checked with mocks — the loser returns before `deliverReport` (one row asserted on PG); the send itself is mocked by design.
  - `[medium]` `[patch]` intent rows 5–7: app side only shown with a copied pull statement — grouped with verification-gap (real pull test below).
  - `[low]` `[reject]` intent row 8: commit-lag PG test builds the windows by hand — services' `now − 5 s` persistence is pinned by `CloseDayServiceTest` / `WeeklyReportWindowResolverTest`; the PG test proves the window maths against a real uncommitted sale.
  - `[false]` `[reject]` intent scope: version 1.1.44, Sunday clamps, after-commit delivery, v1s-14-13 material — later bump/story in the diff range, or review-pass patches recorded above.
  - `[medium]` `[patch]` verification-gap: de-duplication on pull checked only against a hand-copied statement — added `rest_sync_service_pull_test.dart` `pull_stockMovement_existingLocalId_keepsSingleRowWithServerFields` (local row under `saleCancelledMovementId`, real `pull()` of the same id → one row, no PK error).
  - `[medium]` `[patch]` blind 1: the device's own row (cashier as actor, no reason, local quantities, no variant) wins for good with `INSERT OR IGNORE` — the pull now upserts movements (`ON CONFLICT(id) DO UPDATE`, server fields win, `synced = 1`, local `synced_at` kept); asserted by the new pull test.
  - `[low]` `[reject]` blind 2: early return in `_adjustStock` when the movement was pulled but the stock level was skipped (pending local op) — self-heals on the next pull once the pending op is pushed; no double-apply.
  - `[low]` `[reject]` blind 3: legacy random-id branch marked synced — no caller passes a null `correctionId` (`SaleRepositoryImpl.correctSale` always sends one).
  - `[medium]` `[patch]` blind 4: a crash/failed save between commit and the after-commit send leaves the weekly report PENDING for ever (retry selects FAILED only, the guard blocks regeneration) — `findPendingRetries` now also returns PENDING reports older than 15 min (`EndOfDayReportRepositoryAdapter.STALE_PENDING_AFTER`; every other path writes the final status in its creating transaction); PG test `weeklyReport_stalePending_isRetried`.
  - `[false]` `[reject]` blind 5: `..._rolledBack_neverSends` cannot fail — a send performed before commit (the regression it guards) makes `verifyNoInteractions` fail; only the afterCommit hook is deliberately not driven.
  - `[low]` `[reject]` blind 6: commit-lag test hand-built; 5 s margin unmonitored — same as intent row 8; stamp-to-commit is a single local transaction.
  - `[low]` `[reject]` blind 7: phone uniqueness checked before the employee lock; `employee-limit:null` — pre-existing order (concurrent same-phone creates hit the unique constraint); tenant paths always set `TenantContext`.
  - `[low]` `[reject]` blind 8: `correctionId` not an idempotency key — a replay of the same correction computes no delta (quantities already applied); a different correction reusing an id needs a client bug (fresh v4 per correction).
  - `[low]` `[reject]` blind 9: cross-language vectors placement / upper-case / RFC example — both sides pin the same two vectors; ids are lower-case on both sides.
  - `[low]` `[patch]` blind 10: version skip and 14-13 material (false, diff range); local rows omit `variantId` — fixed by the authoritative pull (server `variant_id` wins). Grouped with blind 1.
  - `[medium]` `[patch]` edge 1: PENDING forever after a crash — grouped with blind 4.
  - `[low]` `[patch]` edge 2: an exception in the first afterCommit step skips the remaining synchronizations — `afterCommit` now catches and logs; unit test `generateWeeklyReport_afterCommitFailure_isContained_andNextStepStillRuns`.
  - `[low]` `[patch]` edge 3: the manual trigger returns 500 for a committed report and stops the other stores — same fix as edge 2.
  - `[low]` `[reject]` edge 4: reused `correctionId` would merge over an earlier movement — same as blind 8.
  - `[low]` `[reject]` edge 5: `employee-limit:null` — same as blind 7.
  - `[low]` `[reject]` edge 6: `store-limit:null`; sync handler ignores its `tenantId` argument — the sync service sets `TenantContext` to the same tenant before dispatch.
  - `[low]` `[reject]` edge 7: stale stock level after the early return — same as blind 2.

## Design Notes

- **Item 2 lock vs unique index:** a unique index on `reports(store_id, report_type, report_date)` needs tenant DDL + sync for 100s of schemas and a catch in a REQUIRES_NEW tx; the advisory lock + existing `findByKey` guard is the 13.9 pattern and keeps DDL untouched. WhatsApp delivery runs inside the locked tx (same as daily reports' REQUIRES_NEW template); the loser waits for it, acceptable for a weekly job.
- **Item 3 correction discriminator:** a sale can be corrected several times (3→2→3→2), so `itemId + type` would collide (backend PK violation). The client sends a per-request `correctionId` (v4); a retried request after commit is a no-op (quantities already equal → no movement), so reuse cannot collide. Cancel happens once per sale (versioned status claim, 13.10), so `itemId` alone is unique.
- **Item 3 pull-first:** the pull is `INSERT OR IGNORE` and also refreshes stock levels; if the server row is already local, re-applying the delta would double-count stock — hence skip-if-exists before `insertOnConflictUpdate` (kept as the race guard inside the Drift transaction).
- **Item 4 margin choice:** DB-clock stamping cannot help (`now()` in PG is transaction START, not commit). `receivedUntil = now − 5 s` becomes the persisted anchor (`DayClosure.receivedAt`, weekly report `createdAt`), so the existing exactly-once proof of `ClosureWindow` holds unchanged: a sale received in the last 5 s is counted by the next closure/report as a late arrival. Residual: a sale whose stamping-to-commit exceeds 5 s (sale txs are single-op, < 1 s) — accepted and documented.
- **Item 1 sync replay:** locking before the idempotency read makes a concurrent replay of the same CREATE_STORE id return APPLIED instead of a spurious plan-limit REJECTED.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -o test -Dtest=<touched test classes> -DforkCount=1 -DargLine=-Xmx1536m` -- 0 failures/errors.
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh backend store identity reporting commerce sync shared architecture` (one package at a time) -- 0 failures/errors.
- `cd keevo/app && flutter test --concurrency=4 test/features/pos` then `run-tests-chunked.sh app test/core test/features/inventory` -- 0 failures; `flutter analyze` -- 0 errors, 0 warnings.

**Machine constraint (user rule, 15 GB RAM):** never run `mvn test` or `flutter test` on a whole suite, never two suites in parallel or in background; run targeted classes/files first, then the chunked script one package/folder at a time, each in the foreground with a timeout. Run `build_runner` only if freezed/riverpod/drift annotations change. Do not commit; do not touch existing Docker containers/volumes.

## Auto Run Result

Status: done

**Summary:** the four residual races of Story 13.13 are closed. Store/employee plan limits take `store-limit:<schema>` / `employee-limit:<schema>` before counting (REST, CREATE_STORE sync — before its idempotency read). The weekly report takes `weekly-report:<storeId>:<sunday>` in a now-transactional `WeeklyReportGenerator.generateWeeklyReport`, with WhatsApp delivery after commit (REQUIRES_NEW). SALE_CANCELLED / ADJUSTMENT movement ids are UUIDv5 on both sides (`DeterministicIds` / `stock_movement_ids.dart`; corrections carry a client `correctionId`); the app skips already-pulled movements and writes with `insertOnConflictUpdate`. Closures and weekly reports receive up to `now − 5 s`, persisted as the chain anchor.

**Files:** backend `CreateStoreService`, `CreateStoreSyncHandler`, `CreateEmployeeService`, `WeeklyReportGenerator`, `WeeklyReportWindowResolver`, `ClosureWindow`, `CloseDayService`, `ValidateSaleService`, `CorrectSaleService`, `CorrectSaleUseCase`, `CorrectSaleRequestDto`, `PendingSaleController`, new `shared/domain/DeterministicIds`; tests incl. new `ResidualRacesPostgresIntegrationTest` (8 PG tests) and `DeterministicIdsTest`. App `local_sale_datasource.dart`, `remote_sale_datasource.dart`, `sale_repository_impl.dart`, new `core/sync/stock_movement_ids.dart`, `pubspec.yaml` 1.1.43+42, tests incl. new `remote_sale_datasource_test.dart`.

**Decisions:** weekly window end clamped to ≥ Sunday 00:00 WAT (report stays dated by its Sunday under the margin) and the chained previousEnd clamped likewise; employee lock taken before phone-uniqueness check (harmless); ADJUSTMENT discriminator = client `correctionId` (older apps → random id, transitional).

**Review:** 27 findings — 8 patch rows (2 medium groups: after-commit delivery, remote body test), 1 deferred (employee reactivation plan limit → v1s-14-13-plan-free-limits-enforcement, reopened), 18 rejected with reasons in the triage log.

**Follow-up review recommended: true** — patched medium entries: 2, low: 6. Unverified risk: the weekly delivery path was redesigned during review (afterCommit + REQUIRES_NEW TransactionTemplate); the new PG test was not shown to fail without REQUIRES_NEW.

**Verification:** targeted backend classes green; ResidualRacesPostgresIntegrationTest 8/8 on PostgreSQL; chunked backend store 42, identity 367, commerce 230, sync 433, architecture 6 (pre-review), reporting 374, shared 489 (post-review) — 0 failure/error; app test/features/pos 245, test/core 489, test/features/inventory 186 — all pass; flutter analyze 0 error / 0 warning (625 infos).

**Residual risks:** a sale whose stamp-to-commit exceeds 5 s can still be missed; corrections from apps < 1.1.43 still duplicate one ADJUSTMENT row on pull.

### Follow-up review pass — 2026-10-13

**Summary:** fresh four-layer review of the story-scoped diff. The weekly delivery redesign (`afterCommit` + `REQUIRES_NEW`) is proven by mutation: without `REQUIRES_NEW` the PG test fails because the status stays `PENDING`. Two real regressions of the redesign were closed:
- a crash between commit and send left the report `PENDING` for ever;
- an exception after commit escaped to the caller and skipped the next step.

The app pull now treats the server row as the truth for a movement id the device already wrote, so the owner's cancel or correction history no longer shows the cashier with no reason.

**Files changed:**
- `EndOfDayReportSpringRepository` and `EndOfDayReportRepositoryAdapter`: retry query extended to stale `PENDING` reports.
- `WeeklyReportGenerator`: guarded `afterCommit`.
- App `rest_sync_service.dart`: movement upsert.
- Tests:
  - `WeeklyReportGeneratorTest` (+1)
  - `ResidualRacesPostgresIntegrationTest` (+1)
  - `rest_sync_service_pull_test.dart` (+1)
  - `local_sale_datasource_test.dart` (helper aligned)
- `pubspec.yaml`: 1.1.45+44.

**Review findings:** 23 in total.
- 3 medium entries patched (pull de-duplication test, authoritative pull, stale `PENDING` retry), plus 2 low entries patched.
- 0 deferred.
- The rest were rejected, with reasons in the triage log.

**Follow-up review recommended: false.** No high entry was patched.

**Verification:**
- Targeted run, 0 failures:
  - `ResidualRacesPostgresIntegrationTest`: 10
  - `WeeklyReportGeneratorTest`: 16
  - `ReportDeliveryRetryServiceTest`: 6
- Batch `backend/reporting`: 375 tests, 0 failures.
- App batches, all passed:
  - `test/core`: 490
  - `test/features/pos`: 245
  - `test/features/inventory`: 186
- `flutter analyze`: 0 errors, 0 warnings, 625 infos. None of these infos come from the lines changed here.

**Residual risks:**
- A stale `PENDING` weekly report for a tenant set to in-app delivery would be retried over WhatsApp. This only happens after a crash.
