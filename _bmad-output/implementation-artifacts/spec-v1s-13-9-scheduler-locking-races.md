---
title: 'v1s-13-9 — Scheduler locking & check-then-act races'
type: 'bugfix'
created: '2026-10-11'
status: 'done'
baseline_revision: 'dc43a5994410fa842f9cf34753002c9f67c55373'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-13-context.md'
warnings: ['multiple-goals', 'oversized']
deferred:
  - summary: >-
      Store and employee plan limits are still count-then-save without a lock.
    evidence: |-
      CreateStoreService / CreateStoreSyncHandler / CreateEmployeeService count then PlanLimitGuard then save; two concurrent creates at max-1 both pass. Same TOCTOU 13.9 closed for products, outside its 12 enumerated items. Routed to v1s-13-13-residual-check-then-act-races.
    location: >-
      keevo/backend/src/main/java/com/keevo/store/store/application/service/CreateStoreService.java:56
    severity: medium
  - summary: >-
      Weekly-report already-generated guard is check-then-act (scheduler vs manual trigger).
    evidence: |-
      WeeklyReportScheduler builds generatedStoreIds from a read then generates; ReportController.triggerWeekly can run concurrently. Routed to v1s-13-13-residual-check-then-act-races.
    location: >-
      keevo/backend/src/main/java/com/keevo/reporting/report/application/service/WeeklyReportScheduler.java
    severity: low
---

<intent-contract>

## Intent

**Problem:** Story 13.9 (epics-remediation-audit.md) lists 12 race defects: scheduled jobs run on every instance of a multi-instance deploy (day closure, account deletion, trends, and the other DB jobs incl. 12-12's AuthTokenCleanupJob), and several check-then-act sequences (product plan limit, day closure exists→save, last-owner anti-lockout, CANCEL_SALE sync guard, transfer clientId idempotency, stock-alert cooldown) can be interleaved by concurrent requests; in-memory notification batch maps keep one entry per (tenant, actor/store) forever.

**Approach:** ShedLock (JDBC provider, DB time, `public.shedlock` via Flyway V11) on every DB-driven `@Scheduled` job; a single shared transaction-scoped PostgreSQL advisory-lock port used to serialise the check-then-act sections; atomic SQL where one statement suffices (cooldown, conditional status updates); batch maps evict entries atomically once flushed/expired. Each item gets a recorded decision.

## Boundaries & Constraints

**Always:**
- Advisory locks are transaction-scoped (`pg_advisory_xact_lock`), taken through the new `TransactionLockPort` inside an existing/declared `@Transactional` boundary (adapter is `Propagation.MANDATORY`), with keys that include the tenant schema or a globally unique id.
- ShedLock only on jobs that touch the database or send notifications; never on the in-memory `flushExpiredWindows`/eviction sweeps (they must run on every instance, each owns its own map).
- `lockAtMostFor` shorter than the job period (hourly → PT50M, daily → PT2H, 5-min → PT4M); `lockAtLeastFor` ≥ PT1M for crons, PT30S for the 5-min job, to absorb clock skew between instances.
- Existing REST/sync error codes and behaviours are unchanged except where the matrix says otherwise.
- New Flyway migration = `V11__shedlock.sql` (public only). No tenant-schema DDL change.

**Never:**
- No UNIQUE(store_id, closure_date) index on tenant `day_closures` (existing prod duplicates would make the boot index sync fail; the advisory lock closes the race instead).
- No change to the Flutter app or dashboard.
- Do not touch `deferred-work.md`; no story files.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Two instances, same cron tick | job X fires on A and B | runs once (ShedLock row `public.shedlock` held by first) | second skips silently |
| Concurrent closeDay same store | auto-close + manual/sync close for same store/day | exactly one `day_closures` row; second → `DAY_ALREADY_CLOSED` | scheduler logs DAY_ALREADY_CLOSED at debug, not error |
| Concurrent product creates at limit | FREE plan, count = max-1, two creates | one succeeds, other → plan-limit DomainException (existing code) | — |
| Concurrent draft creates at limit | same via CreateDraftProductUseCase | same as above | — |
| Two owners demote each other | 2 active owners, both demote the other concurrently | one succeeds, other → `CANNOT_DEMOTE_LAST_OWNER` | — |
| CANCEL_SALE sync on sale validated meanwhile | sync op loaded/targets a PENDING sale that becomes COMPLETED | REJECTED `FORBIDDEN`, no stock restore | — |
| CANCEL_SALE sync on COMPLETED sale | status COMPLETED at load | REJECTED `FORBIDDEN` (unchanged) | — |
| Cancel pending vs concurrent validate (REST) | PENDING sale, cancel and validate race | one wins; loser → `SALE_NOT_PENDING` (or `SALE_ALREADY_CANCELLED` when the winner was a cancel); never COMPLETED-without-stock or CANCELLED-with-decrement | transaction rolled back for loser |
| Transfer double-fire same clientId | two concurrent executes with same clientId | one TRANSFER_OUT movement, both calls return the same transfer | — |
| Stock alert concurrent same product | two threshold events, no active cooldown | exactly one cooldown acquisition → one individual notification path | — |
| Cooldown active | last_sent_at within 4h | no notification, cooldown unchanged | — |
| Batch window flushed | window expired and dispatched | map entry removed | — |
| Event racing a flush | event arrives while flush removes the key | event lands in a fresh window, never lost in an orphan window | — |
| Stock alert window idle | window older than 60 s | entry removed by the periodic sweep | — |

</intent-contract>

## Code Map

Backend (`keevo/backend/`, Java under `src/main/java/com/keevo/`):
- `pom.xml` -- add `net.javacrumbs.shedlock:shedlock-spring` and `shedlock-provider-jdbc-template` 6.3.1 (already in ~/.m2).
- `src/main/resources/db/migration/V11__shedlock.sql` -- `CREATE TABLE IF NOT EXISTS public.shedlock(name VARCHAR(64) PRIMARY KEY, lock_until TIMESTAMPTZ NOT NULL, locked_at TIMESTAMPTZ NOT NULL, locked_by VARCHAR(255) NOT NULL)`.
- NEW `shared/infrastructure/config/SchedulerLockConfig.java` -- `@EnableSchedulerLock(defaultLockAtMostFor="PT30M")`, `LockProvider` = `JdbcTemplateLockProvider` (`new JdbcTemplate(dataSource)`, table `public.shedlock`, `usingDbTime()`). Single auto-configured DataSource (MultiTenantJpaConfig defines none).
- `@SchedulerLock(name=..., lockAtMostFor, lockAtLeastFor)` on: `commerce/sale/application/service/DayClosureAutoScheduler.runAutoClosure` (hourly), `shared/infrastructure/scheduling/AccountDeletionScheduler` (daily 03:00, already `@Transactional`), `messaging/notification/application/scheduler/SalesTrendDetectionScheduler.detectTrends` (hourly), `identity/auth/application/service/AuthTokenCleanupJob.cleanup` (03:30), `shared/infrastructure/scheduling/SubscriptionExpiryScheduler.downgradeExpiredSubscriptions` (02:00; drop its "add ShedLock" TODO comment), `reporting/report/application/service/WeeklyReportScheduler.runWeeklyReport` (hourly), `reporting/report/application/service/ReportDeliveryRetryService.retryFailedDeliveries` (5 min), `sync/sync/application/service/SyncErrorLogCleanupJob.cleanup` (03:00).
- NEW `shared/application/port/TransactionLockPort.java` -- `void lock(String key)`: blocks until the transaction-scoped lock for `key` is held; released at commit/rollback.
- NEW `shared/infrastructure/persistence/PostgresTransactionLockAdapter.java` -- `@Transactional(propagation = MANDATORY)`; `EntityManager` native `SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key, 0))` (EntityManager = the transaction's own connection, unlike a raw JdbcTemplate under the multi-tenant provider).
- `commerce/sale/application/service/CloseDayService.java:56-70` -- inject `TransactionLockPort`; `lock("day-closure:" + storeId)` first thing in `closeDay` (before `findLastClosureForStore`/exists checks). Covers items 3 and 5.
- `commerce/sale/application/service/DayClosureAutoScheduler.java:86-140` -- catch `DomainException` with `DAY_ALREADY_CLOSED` → debug log; log one info line per tick with tenant count and elapsed ms (item 4 monitoring).
- `catalog/product/application/usecase/CreateProductUseCase.java:98-140` -- `@Transactional` on `execute`; inject `TransactionLockPort`; `lock("product-limit:" + tenantSchema)` immediately before `countActiveProducts()`. Callers: `ProductController`, `ProductSyncHandler` (per-op tx, rollback handled by SyncPushService), `ImportCsvProductsUseCase` (no outer tx: each row its own tx, DomainException already caught per row).
- `catalog/product/application/usecase/CreateDraftProductUseCase.java:110` -- same lock before `countActiveProducts()` (already `@Transactional`).
- `identity/employee/application/service/ChangeEmployeeRoleService.java:98-103` -- in the OWNER→EMPLOYEE branch, `lock("owners:" + tenantSchema)` before `findOwnersByTenantSchemaName` (JdbcTemplate read sees the other tx's commit, which happens before its lock release).
- `commerce/sale/domain/port/in/CancelPendingSaleUseCase.java` -- add `boolean pendingOnly` to `CancelPendingSaleCommand`, keep a 4-arg constructor (`pendingOnly=false`) for `PendingSaleController:91` and tests.
- `commerce/sale/application/service/ValidateSaleService.java:135-171` -- `cancelPendingSale`: if `pendingOnly` and status COMPLETED → `DomainException(FORBIDDEN, …)` (message moved from handler); PENDING branch uses `updateStatusIfCurrent(PENDING_VALIDATION→CANCELLED)`, false → reload status: CANCELLED → `SALE_ALREADY_CANCELLED`, else `SALE_NOT_PENDING`. `validateSale:123` -- `updateStatusIfCurrent(PENDING_VALIDATION→COMPLETED)`, false → `SALE_NOT_PENDING` (tx rolls back stock writes). `SaleRepository.updateStatusIfCurrent` exists (`SaleRepositoryAdapter:144`).
- `sync/sync/application/handler/CancelSaleSyncHandler.java:54-63` -- drop the handler-side load/status check and `SaleRepository` dependency; pass `pendingOnly=true`.
- `catalog/stock/application/usecase/ExecuteTransferService.java:69-79` -- inject `TransactionLockPort`; when `clientId != null`, `lock("transfer:" + clientId)` before the `findById` idempotency check.
- `messaging/notification/domain/port/out/NotificationCooldownRepository.java` + `adapter/out/persistence/impl/JdbcNotificationCooldownAdapter.java` -- add `boolean tryAcquireCooldown(type, productId, storeId, window)`: single `INSERT … AS nc … ON CONFLICT (cooldown_type, product_id, store_id) DO UPDATE SET last_sent_at = EXCLUDED.last_sent_at WHERE nc.last_sent_at <= NOW() - (? * INTERVAL '1 second')`, returns `update count == 1`.
- `messaging/notification/application/listener/StockAlertNotificationListener.java:84-130` -- replace `existsActiveCooldown` + own `upsertCooldown` by `tryAcquireCooldown` (other batch products keep `upsertCooldown`); window bookkeeping via `batchWindows.compute(...)`; new `@Scheduled(fixedDelay = 60_000) evictExpiredWindows()` removing windows older than 60 s via `computeIfPresent` (inject `Clock` through a package-private test constructor, mirror Product listener).
- `messaging/notification/application/listener/ProductCreationNotificationListener.java:88-150`, `StockModificationNotificationListener.java:98-150` -- accumulate with `batchWindows.compute(key, …)`; flush with `computeIfPresent(key, …)` that extracts the events of an expired window and returns `null` (entry removed); empty/expired leftover windows also removed.
- `SalesTrendDetectionScheduler` keeps `existsActiveCooldown`+`upsertCooldown` (single-runner under ShedLock; acquiring before a failed push would suppress the retry).
- Tests (`src/test/java/com/keevo/…`): update constructors/mocks in `CloseDayServiceTest`, `DayClosureSyncHandlerTest`, `CreateProductUseCaseTest`, `CreateDraftProductUseCaseTest`, `ImportCsvProductsUseCaseTest`, `ProductSyncHandlerTest`, `ChangeEmployeeRoleServiceTest`, `ExecuteTransferServiceTest`, `TransferSyncHandlerTest`, `CancelSaleSyncHandlerTest`, `ValidateSaleServiceTest`, `StockAlertNotificationListenerTest`, `ProductCreationNotificationListenerTest`, `StockModificationNotificationListenerTest`, `DayClosureAutoSchedulerTest` as needed; NEW `shared/infrastructure/persistence/ConcurrencyPrimitivesPostgresIntegrationTest.java` (pattern: `AuthSessionPostgresIntegrationTest` — `@DataJpaTest` + Testcontainers postgres:16-alpine tmpfs).

## Tasks & Acceptance

**Execution:**
- `pom.xml`, `V11__shedlock.sql`, `SchedulerLockConfig.java` -- ShedLock infra.
- 8 scheduler classes above -- `@SchedulerLock` with per-job bounds -- items 10, 11 (+ AuthTokenCleanupJob and other DB jobs).
- `TransactionLockPort.java`, `PostgresTransactionLockAdapter.java` -- shared advisory lock.
- `CloseDayService.java`, `DayClosureAutoScheduler.java` -- items 3, 4, 5.
- `CreateProductUseCase.java`, `CreateDraftProductUseCase.java` -- item 1.
- `ChangeEmployeeRoleService.java` -- item 7.
- `CancelPendingSaleUseCase.java`, `ValidateSaleService.java`, `CancelSaleSyncHandler.java` -- item 6.
- `ExecuteTransferService.java` -- item 12.
- `NotificationCooldownRepository.java`, `JdbcNotificationCooldownAdapter.java`, `StockAlertNotificationListener.java` -- items 8, 9.
- `ProductCreationNotificationListener.java`, `StockModificationNotificationListener.java` -- item 2.
- Unit tests: one per matrix row that is observable without a DB (lock call order verified with Mockito `InOrder`; FORBIDDEN/SALE_NOT_PENDING mapping; map size back to 0 after flush/evict; event during flush not lost).
- NEW `src/test/java/com/keevo/shared/infrastructure/config/SchedulerLockCoverageTest.java` -- reflection test of the AC below.
- Integration test (Testcontainers): `public.shedlock` exists and `JdbcTemplateLockProvider` refuses a second concurrent lock of the same name; two transactions locking the same key serialise (second blocks until first commits), different keys do not; `lock` without a transaction throws `IllegalTransactionStateException`; `tryAcquireCooldown` true/false/true-after-window on a tenant-schema `notification_cooldowns` table (DDL from `TenantSchemaProvisioner.DDL_NOTIFICATION_COOLDOWNS`).

**Acceptance Criteria:**
- Given any matrix row, when the scenario runs, then the outcome matches and a unit or integration test proves it (multi-instance rows are proven at the primitive level: ShedLock provider + advisory lock serialisation).
- Given the Spring context, when it starts, then a `LockProvider` bean exists and every DB-driven `@Scheduled` method carries `@SchedulerLock` (a reflection test over the 8 classes asserts it, and that the flush/evict sweeps do not).
- Given the full backend suite, when run, then failures/errors are no worse than the known baseline (7 failures + 29 errors in the 10 listed persistence classes).
- Given the epic brief, when the story is done, then all 12 items of Story 13.9 are ticked with the commit and decision.

## Spec Change Log

## Review Triage Log

### 2026-10-11 — Review pass
- verdicts: 22 findings — high 0, medium 6, low 10, false 6, maybe-false 0
- findings:
  - Verification Gap
  - `[medium]` `[patch]` Lock-taking use cases rely on `@Transactional` (adapter is MANDATORY) but no test guards it — reflection test `lockTakingUseCasesAreTransactional` added in SchedulerLockCoverageTest (6 methods).
  - `[medium]` `[patch]` Shipped `SchedulerLockConfig` provider never exercised (integration test built its own) — integration test now uses `new SchedulerLockConfig().lockProvider(dataSource)`.
  - `[medium]` `[patch]` (other finding) validateSale claim after the stock loop leaks async stock events/cooldowns on a lost race — grouped with Blind Hunter #1.
  - Blind Hunter
  - `[medium]` `[patch]` validateSale claims PENDING→COMPLETED only after stock writes/events — claim moved right after load/scope checks, before any stock write; test asserts no stock op / StockForcedZeroEvent on the loser.
  - `[low]` `[patch]` Product-limit lock taken after clientId/name checks (same-clientId double-fire still races) — lock is now the first statement of both create use cases.
  - `[low]` `[reject]` `@Transactional` on CreateProductUseCase puts the synchronous audit listener in the product transaction — same contract CreateDraftProductUseCase and every sync op already have (sync runs each op in a transaction); an audit write failure rolling back the creation is consistent, and the async notifier batches 5 min so a rolled-back commit is the only exposure.
  - `[low]` `[patch]` Cooldown acquired before store/phone lookups — moved after the lookups (old order), test `lookupFailure_doesNotConsumeCooldown`.
  - `[low]` `[reject]` No `lock_timeout` on `pg_advisory_xact_lock` — the guarded sections are short single-store/tenant transactions, same unbounded-wait semantics as the row locks already taken there; a timeout would also apply to every later statement of the transaction (SET LOCAL), a wider change than the race fix.
  - `[false]` `[reject]` ShedLock vs `@Transactional` interceptor order on AccountDeletionScheduler — whichever is outer, `lockAtLeastFor=PT1M` keeps the row held past the tick, and the ShedLock insert is PK-serialised even if it joined the transaction, so no second instance can run the same tick.
  - `[false]` `[reject]` Integration test does not cover MultiTenantJpaConfig — the lock goes through the transaction's EntityManager (its own connection whatever the provider), and the JdbcTemplate reads (product count, owners) only need to see the winner's commit, which happens before the xact lock is released; correctness does not depend on sharing the connection.
  - `[medium]` `[patch]` Last-owner protection only on role change; `DeactivateEmployeeService` can deactivate the last OWNER — guard added (owners lock + count, CANNOT_DEMOTE_LAST_OWNER) with 3 tests; intent's "cannot lock out every owner".
  - `[low]` `[reject]` `tryAcquireCooldown` with null storeId never conflicts — StockThresholdBreachedEvent always carries a storeId; the pre-existing exists/upsert pair had the same property.
  - `[low]` `[reject]` Coverage test classifies sweep classes, not methods — a future DB `@Scheduled` method in a listener class is unlikely and would be reviewed; method-level listing adds churn for no current defect.
  - `[low]` `[patch]` Blank line between `@Scheduled`/`@SchedulerLock` and import placement in schedulers — cleaned in all 8 classes.
  - `[low]` `[reject]` Tick log has no warn threshold / duplicate catch branch — monitoring hook is an info line by decision; cosmetic.
  - Edge Case Hunter
  - `[low]` `[patch]` Cooldown consumed when lookup throws — same as Blind Hunter row (moved).
  - `[medium]` `[patch]` Concurrent validations both reach stock writes before the CAS — same root cause as Blind Hunter #1 (claim first).
  - `[low]` `[patch]` ReportDeliveryRetry `lockAtMostFor=PT4M` can expire mid-run — raised to PT15M (fixedDelay job: only matters on crash).
  - `[false]` `[reject]` `product-limit:null` key when tenant is null — `countActiveProducts` and the product repository are tenant-scoped and fail without a tenant; no caller reaches the lock tenant-less.
  - Intent Alignment
  - `[false]` `[reject]` Mechanisms differ from the hints (unique+catch, pass loaded status, TTL cache) — the AC accepts any resolution with a recorded reason; reasons are in Design Notes and the epic ticks.
  - `[false]` `[reject]` validateSale REST behaviour changed — intended: it is the other side of the cancel/validate race (matrix row "Cancel pending vs concurrent validate").
  - `[false]` `[reject]` No end-to-end concurrent use-case test — primitives proven on PostgreSQL + lock-order unit tests + transactional-boundary reflection test, as the AC states; already covered by the Verification Gap patches.

### 2026-10-13 — Review pass (follow-up)
- verdicts: 31 findings — high 0, medium 3, low 23, false 5, maybe-false 0
- findings:
  - `[false]` `[reject]` (intent) diff carries other stories' hunks (13-10/13-11/14-17/15-9/15-11/18-x) — follow-up diff spans baseline→current code; those hunks are reviewed in their own passes.
  - `[low]` `[reject]` (intent) races proven below the concurrency surface (mocked lock + post-commit state, primitives on PG) — carried from the first pass: lock placement is pinned per use case, the primitives are proven on PostgreSQL.
  - `[low]` `[reject]` (intent) "two instances, same tick" not tested on real jobs — the ShedLock provider refusal is proven on PG and every job's annotation is pinned by `SchedulerLockCoverageTest`; the context path is booted by `TransferPersistenceIntegrationTest`.
  - `[low]` `[reject]` (intent) ReportDeliveryRetry `lockAtMostFor=PT15M` vs "5-min → PT4M" — carried: deliberate (fixedDelay job, bound only matters on crash).
  - `[false]` `[reject]` (intent) deactivation guard outside the matrix — the intent's item is "last-owner anti-lockout"; carried first-pass patch.
  - `[false]` `[reject]` (intent) validateSale reordering beyond the sync guard — required by the matrix row "Cancel pending vs concurrent validate (REST)".
  - `[false]` `[reject]` (intent) expired-but-unflushed window keeps collecting — consistent with "never lost", documented in code.
  - `[false]` `[reject]` (blind) patch not limited to 13.9 — same as the intent row.
  - `[low]` `[patch]` (blind) last-owner guard fails open when tenant/membership cannot be resolved — fails closed now: no TenantContext → IllegalStateException, unknown tenant → `TENANT_NOT_FOUND`, before any write; test added.
  - `[low]` `[patch]` (blind) guard ignores whether the target's OWNER membership is active (idempotent deactivation of an already-inactive owner refused) — `filter(isActive)` before the role check; test added.
  - `[low]` `[patch]` (blind) deactivation answers "Impossible de rétrograder…" — `CANNOT_DEMOTE_LAST_OWNER` message reworded to « Impossible de retirer le dernier propriétaire actif du compte » (true for demotion and deactivation; app sync label already says « retirer »).
  - `[low]` `[reject]` (blind) owner count ignores `users.is_active` — no flow disables an OWNER's user row while keeping the membership active (deactivation flips the membership).
  - `[medium]` `[defer]` (blind) store/employee plan limits still count-then-save — outside the 12 enumerated items; routed to v1s-13-13-residual-check-then-act-races.
  - `[low]` `[reject]` (blind) fixedDelay retry runs once per instance — single backend instance today; attempts are capped by MAX_ATTEMPTS.
  - `[low]` `[reject]` (blind) failed send still consumes the 4 h cooldown — pre-existing semantics (cooldown was upserted before dispatch before 13.9), unchanged.
  - `[low]` `[defer]` (blind) weekly-report guard check-then-act (scheduler vs manual trigger / run > PT50M) — routed to v1s-13-13-residual-check-then-act-races.
  - `[low]` `[reject]` (blind) no use-case concurrency test on PostgreSQL — carried (same as intent row).
  - `[low]` `[reject]` (blind) ShedLock proxy not proven at runtime — standard `@EnableSchedulerLock` AOP on Spring beans; the shipped config boots in `TransferPersistenceIntegrationTest`.
  - `[low]` `[reject]` (blind) coverage scan misses `@Schedules`/`SchedulingConfigurer` — none exist in `src/main` (grep).
  - `[low]` `[reject]` (blind) "one lock per transaction" not enforced — documented contract, no caller takes two keys today; enforcement adds machinery for a non-existent case.
  - `[low]` `[patch]` (blind) product-created notification can fire before the product transaction commits (CreateProductUseCase now `@Transactional`) — listener is `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)`; reflection test.
  - `[low]` `[reject]` (blind) tick log lacks store/closure counts — carried.
  - `[low]` `[reject]` (blind) ShedLock version duplicated in pom — cosmetic.
  - `[low]` `[patch]` (edge) inactive OWNER membership guarded — grouped with the blind row.
  - `[medium]` `[patch]` (edge) `deactivateByUserId` deactivated the user's memberships in EVERY tenant (and reactivation re-enabled them all) — a user who also belongs to or owns another shop lost access there, invisibly to the per-tenant guard. Port now `deactivate/reactivate(userId, tenantId)`; both services scope to the current tenant (fail closed); tests assert only the current tenant's membership changes.
  - `[low]` `[reject]` (edge) `product-limit:null` key when TenantContext is null — unreachable: the JWT filter and the sync push always set the tenant.
  - `[low]` `[patch]` (edge) async notification for a rolled-back product — grouped with the blind AFTER_COMMIT row.
  - `[low]` `[patch]` (verification) draft clientId path: lock before idempotency lookup not pinned — `CreateDraftProductUseCaseTest.withClientId_locksBeforeIdempotencyLookup` (InOrder lock → findById, no name check/save).
  - `[medium]` `[defer]` (verification) store/employee plan-limit adoption gap — grouped with the blind row, v1s-13-13.
  - `[low]` `[patch]` (verification other) inactive membership — grouped with the blind row.
  - `[low]` `[patch]` (verification other) misleading error text — grouped with the blind row.

## Design Notes

- Why advisory locks over UNIQUE+catch: a constraint violation inside the shared sync transaction marks it rollback-only and needs a second transaction to re-read; `pg_advisory_xact_lock` serialises the critical section in place, leaves no schema change in the 100s of tenant schemas, and is released automatically on commit/rollback (also on crash — connection drop). Keys hashed with `hashtextextended(key, 0)` (bigint, PG ≥ 11).
- Lock order: only one advisory lock is taken per transaction, except a sync batch op that may hold one lock at a time per op transaction — no lock-ordering deadlock.
- Item 4 (hourly closure overhead) decision: kept hourly by design (per-tenant `eodReportTime`); before trigger time each tenant costs one preference read, after it one `exists` per store until midnight; with ShedLock it runs on one instance only. Resolved by adding the per-tick duration log as the monitoring hook; revisit if the tick exceeds ~1 min.
- Item 9 decision: single-statement conditional upsert rather than an advisory lock — the unique constraint already exists, so `ON CONFLICT … WHERE` makes check+set atomic.
- Golden example (flush with eviction):
  ```java
  batchWindows.computeIfPresent(key, (k, w) -> {
      if (w.windowStart != null && now.isAfter(w.windowStart.plus(window))) {
          if (!w.events.isEmpty()) toSend.set(new ArrayList<>(w.events));
          return null; // removed atomically; a concurrent compute() starts a fresh window
      }
      return w;
  });
  ```

- **Audit inside the product transaction (follow-up decision 2026-10-13):** `CreateProductUseCase` is `@Transactional` since 13.9, so the synchronous `AuditEventListener` runs inside it; an audit write failure (REQUIRES_NEW, throws) now aborts the creation. Accepted: the architecture requires "audit on every mutation" (Observer, immutable trail), CreateDraftProductUseCase and every sync op already had this contract, and before 13.9 the same failure returned a 500 AFTER the product was committed (client retry → duplicate product). The audit row itself stays audit-on-attempt (REQUIRES_NEW commits even if the product tx later rolls back). The async notification, in contrast, now waits for AFTER_COMMIT.
- **Membership (de)activation is tenant-scoped (follow-up 2026-10-13):** an employee deactivation/reactivation only touches the membership of the current tenant; refresh tokens are still revoked user-wide (forces a re-login elsewhere, no lockout).

## Verification

**Commands:**
- `cd keevo/backend && mvn -q test -Dtest='CloseDayServiceTest,DayClosureAutoSchedulerTest,DayClosureSyncHandlerTest,CreateProductUseCaseTest,CreateDraftProductUseCaseTest,ImportCsvProductsUseCaseTest,ProductSyncHandlerTest,ChangeEmployeeRoleServiceTest,ExecuteTransferServiceTest,TransferSyncHandlerTest,CancelSaleSyncHandlerTest,ValidateSaleServiceTest,StockAlertNotificationListenerTest,ProductCreationNotificationListenerTest,StockModificationNotificationListenerTest,ConcurrencyPrimitivesPostgresIntegrationTest,SchedulerLockCoverageTest'` -- expected: all pass.
- `cd keevo/backend && mvn test` -- expected: no new failure/error vs baseline.

## Auto Run Result

Status: done

**Summary:** ShedLock (JDBC provider, DB time, `public.shedlock` via Flyway V11) on the 8 DB-driven scheduled jobs (incl. 12-12's AuthTokenCleanupJob); a shared transaction-scoped PostgreSQL advisory-lock port (`TransactionLockPort` / `PostgresTransactionLockAdapter`, MANDATORY propagation) closing the check-then-act races on day closure, product plan limit, last-owner demotion/deactivation and transfer clientId; atomic conditional status updates for cancel/validate of pending sales (sync `pendingOnly` → FORBIDDEN on COMPLETED); atomic cooldown acquisition for stock alerts; batch-window maps evicted on flush / by a 60 s sweep. All 12 Story 13.9 items ticked in the epic brief.

**Files changed:**
- `pom.xml` — ShedLock 6.3.1 (spring + jdbc-template provider).
- `db/migration/V11__shedlock.sql` — `public.shedlock` table.
- `shared/infrastructure/config/SchedulerLockConfig.java` — `@EnableSchedulerLock`, JDBC LockProvider (DB time).
- 8 schedulers (DayClosureAuto, AccountDeletion, SalesTrendDetection, AuthTokenCleanup, SubscriptionExpiry, WeeklyReport, ReportDeliveryRetry, SyncErrorLogCleanup) — `@SchedulerLock` with per-job bounds.
- `shared/application/port/TransactionLockPort.java`, `shared/infrastructure/persistence/PostgresTransactionLockAdapter.java` — advisory xact lock.
- `CloseDayService`, `DayClosureAutoScheduler` — per-store lock; DAY_ALREADY_CLOSED at debug; per-tick monitoring log.
- `CreateProductUseCase` (now `@Transactional`), `CreateDraftProductUseCase` — per-tenant product-limit lock first.
- `ChangeEmployeeRoleService`, `DeactivateEmployeeService` — owners lock + last-owner check.
- `CancelPendingSaleUseCase`, `ValidateSaleService`, `CancelSaleSyncHandler` — `pendingOnly`, claim-first conditional transitions.
- `ExecuteTransferService` — clientId lock before idempotency lookup.
- `NotificationCooldownRepository`, `JdbcNotificationCooldownAdapter`, `StockAlertNotificationListener` — `tryAcquireCooldown`, compute-based windows, eviction sweep.
- `ProductCreationNotificationListener`, `StockModificationNotificationListener` — windows removed on flush.
- Tests: 16 existing classes updated; new `SchedulerLockCoverageTest` (5) and `ConcurrencyPrimitivesPostgresIntegrationTest` (7, Testcontainers PG16).

**Review findings:** 22 findings (0 high, 6 medium, 10 low, 6 false). Patched: 4 medium entries (transactional-boundary guard test, shipped ShedLock config exercised, validateSale claim-first, DeactivateEmployeeService last-owner guard) + 4 low (product lock first, cooldown after lookups, ReportDeliveryRetry PT15M, annotation/import formatting). Deferred: none. Rejected with reasons: see Review Triage Log (audit listener inside product tx, no lock_timeout, null storeId, class-level sweep classification, tick-log warn threshold, interceptor order, multi-tenant config, null-tenant key, 3 intent-alignment divergences).

**Follow-up review recommended:** true — 4 medium entries were patched after the review pass, notably the new `DeactivateEmployeeService` last-owner guard (fail-open when tenant/membership cannot be resolved) and the reordered `validateSale` claim; neither was seen by a reviewer.

**Verification:**
- Targeted: 18 classes, 186 tests, 0 failures/errors (incl. Testcontainers integration test).
- Full `mvn test`: 2136 tests, 7 failures + 29 errors, all in the 10 known baseline persistence classes — no new failure.

**Residual risks:**
- ShedLock wiring at full application startup only proven by reflection + integration tests (the full-context tests are in the baseline-failing set, needing a DB on localhost:5444).
- Advisory lock waits are unbounded (no lock_timeout), same as existing row locks.
- Product creation REST path now runs the synchronous audit listener inside the product transaction.

### Follow-up review pass — 2026-10-13

**Summary:** fresh 4-layer review of the post-review patches (last-owner guard on deactivation, claim-first validateSale, product audit in the product transaction). validateSale claim-first confirmed pinned (`verifyNoInteractions(stockOperationService)` on the lost race). Deactivation guard hardened (active membership only, fail closed on an unresolved tenant) and membership (de)activation scoped to the current tenant — it used to cut the user's access to every other shop. Product-created notification moved to AFTER_COMMIT; the audit-in-transaction behaviour is kept and recorded as a decision (Design Notes). Last-owner message reworded for both demotion and deactivation.

**Files changed:** `UserMembershipRepository` + adapter (`deactivate/reactivate(userId, tenantId)`), `DeactivateEmployeeService`, `ReactivateEmployeeService`, `GlobalExceptionHandler` (message), `ProductCreationNotificationListener`; tests `DeactivateEmployeeServiceTest`, `ReactivateEmployeeServiceTest`, `CreateDraftProductUseCaseTest`, `ProductCreationNotificationListenerTest`.

**Review findings:** 31 (0 high, 3 medium, 23 low, 5 false). Patched: 1 medium entry (tenant-scoped membership) + 5 low entries (fail-closed guard, active-membership guard, message, AFTER_COMMIT notification, draft lock-order test). Deferred: 2 entries → v1s-13-13-residual-check-then-act-races (store/employee plan limits — medium; weekly report scheduler vs manual trigger — low). Rejected: see the 2026-10-13 triage entry.

**Follow-up review recommended: false** — follow-up pass, no high patched (patched: 0 high, 1 medium, 5 low).

**Verification:** targeted classes (Deactivate/Reactivate/CreateDraft/ProductCreationNotificationListener/GlobalExceptionHandler/SchedulerLockCoverage/ChangeEmployeeRole) 68 tests green; batches `identity` 366, `catalog` 395, `messaging` 126, `shared` 472 — 0 failure.

**Residual risks:** store/employee plan-limit races and weekly-report double generation until v1s-13-13; refresh tokens of a deactivated employee are revoked user-wide (re-login needed in other shops).
