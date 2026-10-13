---
title: 'v1s-15-11 — Testcontainers integration test base + pending Postgres tests'
type: 'chore'
created: '2026-10-11'
status: 'done'
baseline_revision: '62f1f0d1c7b563277346c6b63a22b30f3f237787'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-15-context.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** `mvn test` is red: 7 failures + 29 errors in 10 classes. Persistence tests point at a dev Postgres on `localhost:5444` or at H2 (which cannot run the JSONB baseline), and four unit/web tests went stale after 13-9/14-5 changes. Several behaviours (Flyway baseline, `@Modifying`+`@Version`, concurrent transfer completion, idempotent transfer replay, auth concurrency, `Clock` wiring) were never verified against a real database. Every Testcontainers class also starts its own container.

**Approach:** One JVM-wide PostgreSQL 16 Testcontainer (tmpfs, fsync off) behind a `PostgresIntegrationTest` base (`@ServiceConnection`, `test` profile) and a `FullContextIntegrationTest` base (`@SpringBootTest`). Move every DB-backed test onto it. Fix the stale tests at their real cause. Add the pending integration tests: full-context boot, tenant-scoped transfer persistence/concurrency/replay, and multi-connection auth concurrency.

## Boundaries & Constraints

**Always:** Real PostgreSQL 16 (`postgres:16-alpine`) only, never H2. One shared container per JVM. Tests that commit use unique ids/phones/schemas and never assert global row counts. Tests that need a pristine DB or mutate public DDL use `SharedPostgresContainer.freshDatabase(name)`. That method terminates the database's backends before dropping it. Concurrency tests use `CountDownLatch` start gates, bounded awaits and executor shutdown. `TenantContext` is cleared in every worker thread. Production code changes only where a failing test exposes a real bug.

**Never:** `@Disabled`, deleting a failing test, `assumeTrue` skips on a missing DB, or a dependency on `localhost:5444`. No `withReuse(true)`, since it needs per-machine config and is not CI-portable. Never touch Docker containers or volumes that Testcontainers did not create. No new Flyway migration.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Context boot | `@SpringBootTest` on shared PG | Flyway public V1,V2,V4..V13 applied, none pending; runner `isRunning`; `LockProvider` is JDBC on `public.shedlock`; one `Clock` injected into `AuthTokenCleanupJob` | — |
| Tenant provisioning | `TenantFactory.create()` in a tx | `public.tenants` row ACTIVE, schema has users/stores/products/stock_*/sales tables; `runner.runAll()` idempotent | — |
| Concurrent complete | 8 threads complete the same IN_TRANSIT transfer | all return COMPLETED; destination credited once; 1 TRANSFER_IN movement; version 1 | losers take the idempotent branch |
| `@Modifying`+`@Version` | entity loaded, then `transitionStatus` in the same tx | re-read sees COMPLETED/version+1; saving the stale (v0) domain object throws `ObjectOptimisticLockingFailureException` | — |
| Replay | same `STOCK_TRANSFER` op (same entityId) applied twice, then 2 concurrent double-fires | same transfer id each time; source decremented once; 1 transfer row | — |
| Parallel wrong OTP | 10 threads `tryClaimAttempt(token,5)` | exactly 5 succeed, `attempts` = 5 | — |
| Parallel correct OTP | 2 threads `markConsumed` | exactly one returns 1 | — |
| Device claim race | N users concurrently upsert the same device | `user_id` = first committed owner, never reassigned; exactly one success | — |
| Account deletion, DROP fails | `dropSchemaForDeletion` throws | tenant stays DELETION_PENDING, no membership/user/token deletes run, retried next day | ERROR log |

</intent-contract>

## Code Map

Test base (`keevo/backend/src/test/java/com/keevo/shared/infrastructure/persistence/`, existing package so ArchUnit sees no new module):
- `SharedPostgresContainer.java` (new): static singleton, `max_connections=300` (one Hikari pool per cached context), `freshDatabase/dropDatabase/jdbcUrl`; both use `DROP DATABASE IF EXISTS … WITH (FORCE)` (PG13+) after `pg_terminate_backend`, so a reconnecting pool cannot make the DROP fail.
- `PostgresIntegrationTest.java` (new): `@ServiceConnection` static field, `@AutoConfigureTestDatabase(NONE)`, `@ActiveProfiles("test")`. `src/test/resources/application-test.yml` (new) sets quiet logs (the dev profile logs DEBUG to `/tmp`) and `spring.datasource.hikari.maximum-pool-size: 20`: 8-thread concurrency tests plus async listeners must never wait 20 s for a connection. That is 20 × ≤ 10 cached contexts, under `max_connections=300`.
- `FullContextIntegrationTest.java` (new), `KeevoApplicationContextIntegrationTest.java` (new, AC B, already prototyped green). Flyway: assert that applied versions *contain* V1,V2,V4..V13 (not an exact list, so V14 does not break it), plus none failed and none pending. Drop the redundant SUCCESS-filter assertion. Clock: assert `getBeansOfType(Clock.class)` has exactly one entry, so every constructor-injected Clock (`CachedTokenRevocationAdapter`, `PasswordResetRateLimiter` keep it only inside Caffeine tickers) is that bean. Also assert the `AuthTokenCleanupJob` field identity.

Baseline classes and their real causes:
- `ProductRepositoryAdapterTest`, `ProductJpaEntityTest`, `SaleRepositoryAdapterTest`: H2 fails on JSONB in V1. Extend the base and drop `@Sql("/product-schema.sql")`, `@ExtendWith(SpringExtension)` and `@ActiveProfiles("test")`. Products/sales live in the Flyway public schema, with no FK except `sale_items→sales`. Delete `src/test/resources/product-schema.sql` and the `com.h2database:h2` dependency (pom).
- `JpaBaseEntityPersistTest` (5444, `ddl-auto=update`, flyway off): extend the base and drop the `@TestPropertySource`, so Flyway + validate apply.
- `SectorTemplateFactoryTest` (`@SpringBootTest` on 5444): extend `FullContextIntegrationTest`, which shares the cached context.
- `FlywayBaselineIntegrationTest`: plain JUnit, Flyway API configured like `application.yml` (baseline-on-migrate, baseline v0, schema public, `classpath:db/migration`) on `freshDatabase("keevo_flyway_baseline")`, dropped `@AfterAll`. Remove `@ActiveProfiles("flyway-test")`. `v2IsIdempotent` asserts V2 is applied exactly once and absent from a re-migrate's executed list. Hibernate validate moves to the context test.
- `AccountDeletionSchedulerTest`: 13-9 added a `findById` re-fetch that is not stubbed. See `AccountDeletionScheduler.executeDeletionForTenant` below.
- `OnboardingServiceTest.categories_should_have_valid_fields`: ids are deliberately `null` (persist path, `OnboardingService.java:76-88`). Assert null ids + distinct names.
- `FeedbackControllerTest`: validation maps to 422 (`GlobalExceptionHandler:121-131`) and `"trop court"` is exactly 10 chars, so it is valid. Expect 422 and use a 9-char description.
- `AdminFeedbackControllerTest`: `standaloneSetup` ignores the class-level `@PreAuthorize`. Wrap the controller in an AOP proxy with `AuthorizationManagerBeforeMethodInterceptor.preAuthorize()`, so `AuthorizationDeniedException` maps to 403.

Production fix: `src/main/java/com/keevo/shared/infrastructure/scheduling/AccountDeletionScheduler.java:72-170`. It marks the tenant DELETED before the DROP, the per-tenant catch swallows the exception, and the `@Transactional` commits DELETED while the schema remains. Retry is then impossible because the loop only selects DELETION_PENDING. A second bug: one `@Transactional` spans every tenant, so one tenant's SQL failure aborts the shared transaction for all later tenants. The fix has four parts:
  - **Per-tenant transaction.** Remove the method-level `@Transactional`. Each expired tenant runs in its own `TransactionTemplate` transaction (constructor-injected `PlatformTransactionManager`; keep the `Clock` overload pattern). The per-tenant catch wraps that transaction, so one failure never touches another tenant.
  - **Lock, then DROP.** Inside that transaction:
    1. Re-fetch the tenant with a row lock (`SELECT status FROM public.tenants WHERE id = ? FOR UPDATE` via `jdbcTemplate`) and skip it unless it is DELETION_PENDING. A concurrent `cancelDeletion` then waits for the commit and sees DELETED.
    2. Capture the OWNER phone numbers (memberships role OWNER → `userRepository`).
    3. `dropSchemaForDeletion` (own auto-commit connection, `IF EXISTS`, idempotent).
    4. Run the membership/orphan-user/refresh-token/device-token deletes, then save DELETED.
    If the DROP throws, the transaction rolls back and nothing is persisted.
  - **WhatsApp last.** The final WhatsApp goes to the captured phones only after the per-tenant transaction has committed. A failed or retried run therefore never sends it twice.
  - **Retry.** A failure after a successful DROP rolls back the public deletes. The tenant stays DELETION_PENDING (and locked until rollback), and the next run completes it (`DROP IF EXISTS` is a no-op).

Migrate to the shared container (remove the per-class `@Container`): `AuthSessionPostgresIntegrationTest`, `SaleOptimisticLockPostgresIntegrationTest`, `NativeSqlPostgresIntegrationTest`, `ConcurrencyPrimitivesPostgresIntegrationTest`. `SchemaProvisioningHygienePostgresIntegrationTest` drops and recreates public CHECK constraints and commits tenants, so it gets `freshDatabase("keevo_schema_hygiene")` + `@DynamicPropertySource`, with no `@ServiceConnection`. `TenantIsolationIntegrationTest` and `identity/auth/infrastructure/persistence/TenantMigrationIntegrationTest` (raw JDBC on 5444, silently skipped) move to `freshDatabase(...)` and lose the `assumeTrue` skip.

New tests:
- `catalog/stock/.../TransferPersistenceIntegrationTest` (extends `FullContextIntegrationTest`, `NOT_SUPPORTED` tx): provisions its own tenant via `TenantFactory` and seeds 2 stores, a product and source stock via JDBC in the tenant schema. Each thread sets/clears `TenantContext` and sets an OWNER `SecurityContext` where the handler needs it. Targets: `CompleteTransferService.execute` (`:50-104`), `StockTransferSpringRepository.transitionStatusNative` (`@Modifying(clearAutomatically=true)`), `StockTransferRepositoryAdapter.save` (`StockTransferJpaEntity.version` `@Version`), `TransferSyncHandler.handle` and `ExecuteTransferService` (clientId + `transactionLock.lock`).
- `identity/auth/.../AuthSessionConcurrencyPostgresIntegrationTest` (extends the base, `@DataJpaTest`, `NOT_SUPPORTED`): `PasswordResetTokenSpringRepository.tryClaimAttempt/markConsumed` and `UserSyncStateRepositoryAdapter.upsert` run in `TransactionTemplate` per thread.
- `pom.xml:158-169`: `dependency:tree` shows `docker-java-api`, `docker-java-transport-zerodep` and `docker-java-transport` all at 3.4.1, with no `docker-java-core` (Testcontainers 1.20 uses only these). Keep the pins and document the check in the comment.

## Tasks & Acceptance

**Execution:**
- Test base files + `application-test.yml` -- as mapped -- shared container.
- The 10 baseline classes -- fix as mapped -- AC A.
- `AccountDeletionScheduler.java` -- per-tenant transaction, row lock, DROP first, WhatsApp after commit, javadoc/comments -- real bug behind the stale test. `AccountDeletionSchedulerTest` (Mockito, `TransactionTemplate` over a stub manager) must cover:
  - `findById`/lock stubbed; DROP precedes the deletes and the save;
  - DROP fails → no save, no WhatsApp;
  - DROP OK but the memberships delete throws → no save, no WhatsApp; a second `executeDeletions()` completes (DROP called again, DELETED saved, WhatsApp once);
  - tenant A fails and tenant B is still deleted in the same run;
  - tenant cancelled before the lock → skipped.
- `shared/infrastructure/scheduling/AccountDeletionSchedulerPostgresIntegrationTest.java` (new, extends `FullContextIntegrationTest`) -- runs on the real database:
  - provision a tenant via `TenantFactory`, add an OWNER membership and refresh token, set it DELETION_PENDING with an expired date;
  - call `executeDeletions()` → schema gone, tenant DELETED, membership and refresh token gone;
  - a second call is a no-op.
  It proves the production fix on the real database.
- 4 Testcontainers classes + hygiene + 2 raw-JDBC tenant classes -- migrate as mapped.
- `TransferPersistenceIntegrationTest`, `AuthSessionConcurrencyPostgresIntegrationTest` -- new, per the I/O matrix -- items 5, 6, 9, 10.
- `pom.xml` -- remove h2, comment the docker-java alignment -- items 1 and 8.
- `.github/workflows/backend-ci.yml` -- remove the now-unused `services.postgres` block and the `POSTGRES_*` / `SPRING_PROFILES_ACTIVE: dev` env of the test step. The tests start their own Testcontainer, and ubuntu runners ship Docker. -- CI surface of "verified in CI".
- Epic doc: tick the 11 items. Sprint-status: done.

**Acceptance Criteria:**
- Given Docker and no Postgres on 5444, when `mvn test` runs, then it ends with 0 failures and 0 errors. The 10 baseline classes all pass, and none is disabled or skipped by an assumption.
- Given the full test run, when containers are listed during it, then exactly one `postgres:16-alpine` Testcontainer serves all Postgres tests.
- Given `KeevoApplicationContextIntegrationTest`, when the context starts, then V1..V13, the TenantSchemaMigrationRunner, ShedLock, the Clock bean and tenant provisioning are each asserted (I/O rows 1–2).
- Given each of the 11 epic items, when the story closes, then it is ticked with its resolution or rejection reason.

## Spec Change Log

### 2026-10-11 — Review pass 1 (bad_spec)
- **Trigger:** reviewers (blind, edge-case, verification-gap) showed that the spec-mandated "DROP first" reorder in `AccountDeletionScheduler` kept the single all-tenant `@Transactional`. Two consequences: one tenant's SQL failure aborts the transaction and still drops later tenants' schemas; and a failure after the DROP leaves a schemaless DELETION_PENDING tenant that `cancelDeletion` can reactivate. The final WhatsApp could also be re-sent on retry. Only Mockito covered the change.
- **Amended:** the Code Map production-fix paragraph (per-tenant `TransactionTemplate`, `FOR UPDATE` row lock before DROP, WhatsApp after commit) and the Execution task (new Mockito cases plus a real-Postgres scheduler test). Low-cost review patches were folded in: Hikari pool 20 in the test profile, `WITH (FORCE)` drops, a non-exact Flyway version assertion, the single-Clock-bean assertion, and the CI workflow cleanup.
- **Known-bad state avoided:** cross-tenant poisoning of the deletion run; a tenant reactivated without a schema; duplicate final WhatsApp; a production fix with no real-DB proof.
- **KEEP:** everything else from attempt 1 (saved as `v1s-15-11-review1-attempt.patch` next to this spec) was reviewed as good and must survive:
  - the shared container and base classes;
  - all 10 baseline-class fixes;
  - the migrations of the 4 Testcontainers classes, the hygiene class and the 2 raw-JDBC classes;
  - `TransferPersistenceIntegrationTest`, `AuthSessionConcurrencyPostgresIntegrationTest` and `KeevoApplicationContextIntegrationTest`;
  - pom/h2 removal and `product-schema.sql` deletion;
  - the epic ticks.
  Re-derivation starts by applying that patch with `git apply`, then changes only what this amendment adds.

## Review Triage Log

### 2026-10-11 — Review pass
- verdicts: 39 findings — high 2, medium 5, low 17, false 6, maybe-false 9
- findings:
  - `[low]` `[reject]` intent (a) CI workflow untouched (postgres service, dev profile env unused) — folded into the bad_spec amendment as a CI cleanup task. Test-profile precedence: SpringBootContextLoader inlines `spring.profiles.active=test` above env vars.
  - `[maybe-false]` `[reject]` intent (b) auth concurrency at repository level rather than OTP service flow — the deferred item itself names `tryClaimAttempt` / `markConsumed` / guarded upsert as the atomic statements to race. A service-level race adds hashing only (if true: low).
  - `[low]` `[bad_spec]` intent (c) Clock identity proven for 1 of 3 beans — amendment: assert a single `Clock` bean exists. The two other beans only keep it inside Caffeine tickers.
  - `[false]` `[reject]` intent (d) runner not tested on a lagging tenant — `SchemaProvisioningHygienePostgresIntegrationTest` already covers boot sync of lagging tenants. AC B asks for wiring, which `isRunning` plus `runAll` prove.
  - `[false]` `[reject]` intent (e) mutation claims unproven — the implementer ran the mutations (removing `clearAutomatically` broke 2 tests, removing the lock broke replay).
  - `[false]` `[reject]` intent (f) item 7 has no new assertion — the item is "test never executed". It now runs on Postgres and passes.
  - `[low]` `[reject]` intent (g) Flyway test config hand-copied — deliberate isolation from the app context. The app config is validated by the context test (V1..V13 applied).
  - `[false]` `[reject]` intent (h) docker-java item resolved by doc only — `dependency:tree` was run (all 3.4.1, no core). The item asked to check skew.
  - `[high]` `[bad_spec]` intent (i) / blind 1–4 / verification-gap gap / edge 1–4 AccountDeletionScheduler reorder: shared tx poisons later tenants while their schemas still get dropped; DROP-then-failure leaves a schemaless PENDING tenant; WhatsApp re-sent on retry; no real-PG test — spec amended (per-tenant tx, FOR UPDATE, WhatsApp after commit, Mockito + Postgres tests).
  - `[low]` `[reject]` intent (j) sprint-status done while spec in-review — status is finalized at the end of this run.
  - `[high]` `[bad_spec]` blind 1 cancel race / schemaless ACTIVE tenant — grouped with the reorder entry. The residual blind-write race in `AccountDeletionService.cancelDeletion` (findBySchemaName + save, no conditional update) is pre-existing and routed to v1s-15-9.
  - `[medium]` `[bad_spec]` blind 2 single @Transactional across tenants — grouped (per-tenant tx).
  - `[medium]` `[bad_spec]` blind 3 WhatsApp re-sent on partial failure — grouped (send after commit).
  - `[medium]` `[bad_spec]` blind 4 production fix has no real-Postgres test — grouped (new scheduler Postgres test).
  - `[low]` `[reject]` blind 5 committed test data piles up in shared DB — ephemeral container, unique ids. A handful of tenants per run do not slow the runner measurably.
  - `[maybe-false]` `[reject]` blind 6 schedulers active in full-context tests — the fixed-delay jobs touch notification maps, report retries and cooldowns, none of the rows the tests assert (unique product/store ids). It would settle with a flaky run log (if true: low).
  - `[maybe-false]` `[reject]` blind 7 double-fire with 2 threads too weak — the mutation run showed red without the lock (if true: low).
  - `[low]` `[bad_spec]` blind 8 Clock check covers 1 of 3 beans — same as intent (c).
  - `[low]` `[bad_spec]` blind 9 hard-coded exact migration list / redundant SUCCESS assertion — amendment: "contains" assertion, redundant line dropped.
  - `[low]` `[reject]` blind 10 Flyway test copies settings — same as intent (g).
  - `[low]` `[reject]` blind 11 no synchronized/parallel guard on freshDatabase — surefire runs classes sequentially and no parallel config exists. The guard adds complexity for an unused mode.
  - `[false]` `[reject]` blind 12 @ServiceConnection relies on class-load order — the base field initializer `SharedPostgresContainer.POSTGRES` itself triggers that class's static start, so there is no order dependency. The Hikari pool is now pinned by the amendment.
  - `[low]` `[reject]` blind 13 Feedback 400→422 / no 10-char boundary test — 422 is the existing `GlobalExceptionHandler` contract for bean validation app-wide. The test was wrong, not the API.
  - `[low]` `[reject]` blind 14 tracking files (epic comment "toutes les stories done", context ordering) — pre-existing comment and cosmetic ordering. Statuses are finalized at run end.
  - `[low]` `[reject]` blind 15 Onboarding test locks null ids — the null id is the documented production contract (`OnboardingService` persist path comment).
  - `[maybe-false]` `[reject]` verification-gap other 1 ACTIVE tenant without schema — grouped under the high entry (fixed by lock + DROP inside the per-tenant tx; residual cancel race routed to v1s-15-9).
  - `[maybe-false]` `[reject]` verification-gap other 2 one tenant failure drops later schemas — grouped (per-tenant tx).
  - `[maybe-false]` `[reject]` verification-gap other 3 WhatsApp sent more than once — grouped (after commit).
  - `[medium]` `[bad_spec]` edge 1 DROP then later failure → PENDING without schema, users can log in ~24h — grouped. With the per-tenant tx the window only exists on a post-DROP SQL failure, which is retried next run.
  - `[medium]` `[bad_spec]` edge 2 aborted outer tx keeps dropping later schemas — grouped.
  - `[low]` `[bad_spec]` edge 3 WhatsApp resend on retry — grouped.
  - `[maybe-false]` `[bad_spec]` edge 4 cancel between re-fetch and DROP — grouped (FOR UPDATE lock).
  - `[low]` `[bad_spec]` edge 5 Hikari pool 10 vs 8 workers + async listeners — amendment: pool 20 in the test profile.
  - `[maybe-false]` `[reject]` edge 6 hourly crons during full-context tests — same as blind 6.
  - `[low]` `[bad_spec]` edge 7 reconnect between terminate and DROP DATABASE — amendment: `WITH (FORCE)`.
  - `[false]` `[reject]` edge 8 dropDatabase throws in @AfterAll — a loud failure on cleanup is correct behaviour for a test fixture.
  - `[low]` `[reject]` edge 9 hygiene fresh DB never dropped — deliberate: its cached context's Hikari pool would log reconnect errors. One small DB on a tmpfs container.
  - `[low]` `[reject]` edge 10 TransferPersistence tenant never cleaned — same as blind 5.
  - `[maybe-false]` `[reject]` edge 11 anonymous path untested on AdminFeedbackController — pre-existing. v1s-15-7 already owns "SecurityConfigAdminPathEnforcementTest coverage holes … anonymous->401".

### 2026-10-11 — Review pass 2
- verdicts: 33 findings — high 0, medium 4, low 15, false 5, maybe-false 9
- findings:
  - `[medium]` `[patch]` verification-gap: FOR UPDATE lock never exercised under contention — added a real-Postgres two-thread test (cancel UPDATE in flight → scheduler waits, then skips; schema kept, ACTIVE, no WhatsApp), checked red without FOR UPDATE.
  - `[low]` `[patch]` verification-gap other: LOCK_TENANT_SQL javadoc overstates the guarantee — reworded; residual blind write points to v1s-15-9.
  - `[maybe-false]` `[reject]` verification-gap other: TenantJpaEntity has no @Version — same residual as the routed v1s-15-9 conditional-UPDATE item (if true: medium, already routed).
  - `[medium]` `[defer]` blind 1 / edge 2: cancel after a post-DROP failure reactivates a schemaless tenant — pre-existing cancel path (AccountDeletionService / AdminTenantService) without schema check; routed → v1s-15-9 (cancel must refuse when the schema is gone).
  - `[low]` `[patch]` blind 2: lock javadoc overstated — same patch as above.
  - `[medium]` `[defer]` blind 3: AdminTenantService.forceDelete sets DELETED without DROP — pre-existing (not touched by this diff); routed → v1s-15-9.
  - `[low]` `[reject]` blind 4: Clock single-bean claim is inference for 2 beans — carried from pass 1 (intent c): the amendment was implemented as specified; those beans keep the Clock only inside Caffeine tickers.
  - `[maybe-false]` `[reject]` blind 5: schedulers active during full-context tests — carried from pass 1 (blind 6).
  - `[low]` `[reject]` blind 6: committed fixtures / global sweep in the scheduler test — other classes' tenants are ACTIVE, never selected; the only order-sensitive piece (failingOnce) is patched below.
  - `[false]` `[reject]` blind 7: connection budget — `application.yml` sets `hikari.minimum-idle: 2` for every profile, so idle pools hold 2 connections; 20 is a per-context ceiling under load only.
  - `[low]` `[reject]` blind 8: product tests run on public.products, not a tenant schema — kept as the class's historical mapping test; tenant-schema persistence is covered by the transfer/scheduler full-context tests.
  - `[low]` `[patch]` blind 9: dropDatabase documented "best effort" yet throws — javadoc fixed (loud failure kept); hygiene DB not dropped is carried (pass 1 edge 9).
  - `[low]` `[reject]` blind 10: Flyway test copies config — carried (pass 1 intent g).
  - `[maybe-false]` `[reject]` blind 11: mutation claims undemonstrated — carried (pass 1 intent e / blind 7); the new lock test's mutation check is recorded in this pass.
  - `[low]` `[reject]` blind 12: AdminFeedback proxy vs app method-security config — security-slice tests are owned by v1s-15-7 (item "@PreAuthorize never exercised in controller tests").
  - `[low]` `[reject]` blind 13: Feedback 422 — carried (pass 1 blind 13).
  - `[low]` `[reject]` blind 14: scheduler unit test LENIENT / WhatsApp lost on crash between commit and send — at-most-once final message is the accepted trade-off of sending after commit; branches are covered by the Postgres test.
  - `[medium]` `[patch]` edge 1: tenant cancelled then re-requested between findAll and lock gets deleted immediately — lock query now also checks deletion_scheduled_at <= now; unit case added.
  - `[low]` `[defer]` edge 2: same as blind 1 — routed → v1s-15-9.
  - `[maybe-false]` `[reject]` edge 3: no lock_timeout — the only contender is a single-row cancel UPDATE; an indefinite wait needs a stuck session that would block every request too (if true: low).
  - `[low]` `[patch]` edge 4: failingOnce may hit another tenant — now throws only for the seeded tenant.
  - `[low]` `[reject]` edge 5: hygiene DB never dropped — carried (pass 1 edge 9).
  - `[low]` `[patch]` edge 6: dropDatabase best-effort doc — same patch as blind 9.
  - `[false]` `[reject]` edge 7: owner-lookup failure now blocks deletion — inside a PostgreSQL transaction any SQL error aborts the transaction, so a try/catch could not make it non-blocking; a failed lookup is retried next run.
  - `[false]` `[reject]` intent 1: tests changed to fit behaviour — each change is a documented wrong expectation (valid 10-char payload, null-id persist contract, standalone MockMvc ignoring @PreAuthorize); "fix the real cause" allows it.
  - `[low]` `[reject]` intent 2: production redesign beyond minimal fix — required by pass-1 high findings; recorded in Design Notes (supersedes 14-5 DN1).
  - `[maybe-false]` `[reject]` intent 3: item 10 at statement level — carried (pass 1 intent b).
  - `[maybe-false]` `[reject]` intent 4: Clock identity — carried (pass 1 intent c).
  - `[maybe-false]` `[reject]` intent 5: runner/ShedLock exercised directly — carried (pass 1 intent d); lagging-tenant boot is covered by the hygiene class.
  - `[maybe-false]` `[reject]` intent 6: CI workflow not exercised — Actions currently end in startup_failure (project memory, pre-existing); workflow aligned, local `mvn test` is the available evidence (if true: low).
  - `[false]` `[reject]` intent 7: "shared/reused" not withReuse — the spec's Never forbids withReuse (non-portable); one container per JVM is the shared reading.
  - `[false]` `[reject]` intent 8: epic context says pin core — `dependency:tree` shows no docker-java-core artifact to pin.
  - `[maybe-false]` `[reject]` intent 9: operational constraints not visible in diff — target is deleted at run end, no push/merge, no foreign container touched (if true: low).

### 2026-10-13 — Follow-up review pass
- verdicts: 37 findings — high 0, medium 5, low 17, false 10, maybe-false 5
- scope: story-scoped diff (files of `a68253f6` + `AccountDeletionConcurrencyPostgresIntegrationTest`, from `baseline_revision` to the current code), so later stories' edits (v1s-15-9, 18-1, 18-3) appear in it.
- findings:
  - `[medium]` `[patch]` verification-gap: the per-phone catch around the final WhatsApp is never exercised — added `AccountDeletionSchedulerTest.deleteNow_whatsAppFailureAfterCommit_isNonBlocking` (2 owners, first send throws: `deleteNow` true, DELETED saved, one commit, no rollback, second owner notified).
  - `[medium]` `[patch]` verification-gap: keeping a multi-tenant user is only checked against mocked SQL text — added `AccountDeletionSchedulerPostgresIntegrationTest.multiTenantUserIsPreserved` (real PG: user, device token and other membership kept, other tenant untouched).
  - `[false]` `[reject]` verification-gap other: `findOwnerPhones` failure now blocks deletion — carried (pass 2 edge 7): inside a PG transaction a failed statement aborts the transaction anyway; retried next run.
  - `[maybe-false]` `[reject]` verification-gap other: CI workflow never actually run — carried (pass 2 intent 6), Actions `startup_failure` is pre-existing.
  - `[false]` `[reject]` blind 1: diff mixes other stories' work — the follow-up diff spans `baseline_revision..HEAD`, so v1s-15-9/18-x commits on the same files are included by construction; each has its own spec.
  - `[false]` `[reject]` blind 2: `deleteNow` strands a schemaless PENDING tenant for days — `AdminTenantService.forceDelete` first commits `deletion_scheduled_at = now()`, so the next daily run selects it.
  - `[low]` `[reject]` blind 3: no alert for the stuck state — ERROR log per failed tenant exists; a metric is new surface for a rare post-DROP failure.
  - `[low]` `[reject]` blind 4: at-most-once WhatsApp not spelled out — carried (pass 2 blind 14), accepted trade-off.
  - `[low]` `[reject]` blind 5: two pooled connections per deletion (tx + DROP) — exhaustion needs pool-size concurrent admin force-deletes; Hikari `connectionTimeout` bounds the wait and rolls back.
  - `[low]` `[reject]` blind 6: tenant row read twice after locking — cosmetic round-trip; the `null` path is unreachable while the row is locked.
  - `[false]` `[reject]` blind 7: connection budget assumed — carried (pass 2 blind 7, `minimum-idle: 2`).
  - `[low]` `[reject]` blind 8: lock-wait probes may match another session — test classes run sequentially and, with scheduling now off in tests (patch below), no other session issues `FOR UPDATE` / `UPDATE public.tenants … to_regnamespace` during them.
  - `[low]` `[reject]` blind 9: leaked tenants/schemas and hygiene DB — carried (pass 1 blind 5 / edge 9 / edge 10).
  - `[low]` `[reject]` blind 10: `meta.size == 100` echoes the stub; exact log-string assertion — the cap is asserted by `ListFeedbackServiceTest.list_capsSizeAt100`; the log assertion is deliberate.
  - `[low]` `[reject]` blind 11: class-wide LENIENT in `AccountDeletionSchedulerTest` — carried (pass 2 blind 14).
  - `[low]` `[reject]` blind 12: Flyway test copies config — carried (pass 1 intent g).
  - `[maybe-false]` `[reject]` blind 13: CI drops `SPRING_PROFILES_ACTIVE: dev` for non-PG tests; no `timeout-minutes` — `ProdSecretsFailFastContextTest` sets its own profile; slices need none; local suite green without it (if true: low).
  - `[low]` `[reject]` blind 14: "V1..V13" labels / floating `postgres:16-alpine` — the label matches the `containsAll` assertion; minor-tag drift is harmless for tests.
  - `[low]` `[reject]` blind 15: product tests on `public.products` — carried (pass 2 blind 8).
  - `[medium]` `[patch]` edge 1: `findDeletionsDueBy_selectsOnlyDuePendingTenants` leaves a due DELETION_PENDING tenant with an OWNER in the shared DB — reset to ACTIVE in a `finally`.
  - `[medium]` `[patch]` edge 2: unscoped `verifyNoInteractions(whatsAppPort)` in `inFlightCancelBlocksSchedulerWhichThenSkips` fails whenever a leftover due tenant exists (and `AccountDeletionConcurrency…` sorts before it) — scoped to `never().sendReport(seed.phone(), …)`. Grouped with edge 1.
  - `[maybe-false]` `[reject]` edge 3: no `lock_timeout` on `FOR UPDATE` — carried (pass 2 edge 3).
  - `[false]` `[reject]` edge 4: `deleteNow` joining a caller transaction — its only caller `AdminTenantService.forceDelete` is deliberately non-transactional, as is `AdminTenantController`.
  - `[low]` `[reject]` edge 5: pool starvation on concurrent force-deletes — same as blind 5.
  - `[low]` `[reject]` edge 6: hygiene DB never dropped — carried (pass 1 edge 9).
  - `[low]` `[reject]` edge 7: transfer tenant never dropped — carried (pass 1 edge 10).
  - `[false]` `[reject]` edge 8: feedback cap unverified — `ListFeedbackServiceTest.list_capsSizeAt100`.
  - `[maybe-false]` `[reject]` edge 9: connection budget near 300 — carried (pass 2 blind 7) (if true: low).
  - `[false]` `[reject]` edge 10: pom adds export dependencies — diff range, see blind 1.
  - `[false]` `[reject]` intent 1: per-class vs whole-suite — the whole `mvn test` was green in pass 2 (2229 tests); this pass re-runs the touched packages in batches.
  - `[maybe-false]` `[reject]` intent 2: replay at handler level — carried (pass 1 intent b).
  - `[low]` `[reject]` intent 3: `runAll()` idempotence only shown as "no exception" — schema-level idempotence is covered by the hygiene class's boot sync; low value.
  - `[low]` `[reject]` intent 4: "DROP fails" row only mocked, ERROR log unasserted — a real DROP failure is not inducible without a faulty provisioner; the post-DROP failure is covered on PG.
  - `[false]` `[reject]` intent 5: production scope beyond the intent — `deleteNow` / `findDeletionsDueBy` come from v1s-15-9 in the diff range.
  - `[false]` `[reject]` intent 6: out-of-scope content — diff range, see blind 1.
  - `[low]` `[reject]` intent 7: `TenantContext` not cleared in scheduler/admin-cancel workers; polling instead of a start gate — those workers never set `TenantContext`; waits are bounded.
  - `[medium]` `[patch]` orchestrator check (pass 1 blind 6 / edge 6, pass 2 blind 5 — previously maybe-false): production schedulers are live in full-context tests. Verified real: `DayClosureAutoScheduler`, `WeeklyReportScheduler`, `SalesTrendDetectionScheduler` tick at minute 0 of every hour over every ACTIVE tenant of the shared DB (day closure after the EOD time), and the 03:00 `AccountDeletionScheduler` deletes any leftover due tenant — a suite crossing the hour can flake. Fix: `@EnableScheduling` moved from `KeevoApplication` to `SchedulingConfig` (`@ConditionalOnProperty keevo.scheduling.enabled`, `matchIfMissing = true`, production unchanged), turned off in `application-test.yml`; `KeevoApplicationContextIntegrationTest.schedulingDisabledInTestProfile` asserts it.

## Design Notes

- AccountDeletionScheduler (pass 1): this supersedes the 14-5 review decision DN1 ("save DELETED before DROP so retry is possible"). DN1 was wrong because the loop only retries DELETION_PENDING tenants, so a DELETED tenant with a lingering schema was never cleaned. Now the DROP comes first inside a per-tenant transaction holding the tenant row lock, and DELETED is written last, so DELETED always means "schema gone". The residual race is pre-existing: `cancelDeletion` reads, then blind-writes ACTIVE after the scheduler commit. It is routed to v1s-15-9 (conditional UPDATE `WHERE status = 'DELETION_PENDING'`).
- One shared database plus per-purpose fresh databases, rather than one database per class. Same-config classes keep Spring context caching, and @ServiceConnection always wins over URL properties, so classes needing isolation bypass the base on purpose.
- Context-test assertions run on the shared `test` database, which other classes may have touched. Assertions are therefore schema/bean-level or on rows this test created.
- Transfer tests run in a provisioned tenant through the production multi-tenant routing. That is where these code paths live in production; the public schema in a `@DataJpaTest` would not exercise `TenantContext` routing.

- Follow-up 2026-10-13 — `SchedulingConfig`: the intent limits production edits to real bugs exposed by tests. Moving `@EnableScheduling` behind a property that defaults to on changes no deployed behaviour; it removes the flakiness source (live cron ticks over the shared test database) at its root instead of guarding every assertion. ShedLock stays active, so tests that call a job bean directly still go through the lock proxy.

## Verification

**Commands:**
- `cd keevo/backend && mvn -q test -Dtest='<touched classes>'` -- expected: all green
- `cd keevo/backend && mvn test` -- expected: `Tests run: N, Failures: 0, Errors: 0`
- `rm -rf keevo/backend/target` at the end -- expected: disk reclaimed

## Auto Run Result

Status: done

**Summary:** Every PostgreSQL-backed test now runs on one JVM-wide `postgres:16-alpine` Testcontainer (tmpfs, fsync off). Spring tests reach it through `PostgresIntegrationTest` / `FullContextIntegrationTest` (`@ServiceConnection`, `test` profile). Tests that need a pristine database use `freshDatabase()`. H2, `product-schema.sql`, every `localhost:5444` dependency and every silent `assumeTrue` skip are gone.

The 10 baseline classes (7 failures + 29 errors) were fixed at their real cause:
- H2 could not run the JSONB baseline;
- the dev database on 5444 was missing;
- the `findById` stub went stale after 13-9;
- the null-id persist contract;
- a 10-character description that was actually valid, plus the app-wide 422 contract;
- standalone MockMvc ignoring `@PreAuthorize`.

Two real production bugs in `AccountDeletionScheduler` were fixed:
- **DELETED saved before the DROP, inside one all-tenant transaction:** a failed DROP left a DELETED tenant whose schema was never retried, and one tenant's failure poisoned the rest of the run. Now each tenant runs in its own transaction holding a `FOR UPDATE` lock that re-checks the expiry, the DROP comes first and DELETED is written last, and the WhatsApp is sent after commit.
- **`UUID[]` varargs bind:** every deletion of a tenant with members failed in PostgreSQL. Found by the new real-database test.

The pending integration tests were added: full context (AC B), concurrent transfer completion, `@Modifying` + `@Version`, transfer replay and double-fire, and multi-connection auth concurrency. CI no longer provisions an unused Postgres service.

**Files changed:**
- `keevo/backend/pom.xml`: h2 removed; docker-java alignment check documented.
- `.github/workflows/backend-ci.yml`: Postgres service and dev profile env removed.
- `.../scheduling/AccountDeletionScheduler.java`: per-tenant transaction, row lock, DROP-first order, WhatsApp after commit, `UUID[]` cast.
- Test base, all new: `SharedPostgresContainer`, `PostgresIntegrationTest`, `FullContextIntegrationTest`, `KeevoApplicationContextIntegrationTest`, `application-test.yml`.
- New tests: `TransferPersistenceIntegrationTest`, `AuthSessionConcurrencyPostgresIntegrationTest`, `AccountDeletionSchedulerPostgresIntegrationTest`.
- Fixed baseline classes:
  - `ProductRepositoryAdapterTest`, `ProductJpaEntityTest`, `SaleRepositoryAdapterTest`, `JpaBaseEntityPersistTest`, `SectorTemplateFactoryTest`: moved onto the base classes.
  - `FlywayBaselineIntegrationTest`: rewritten.
  - `AccountDeletionSchedulerTest`: rewritten.
  - `OnboardingServiceTest`, `FeedbackControllerTest`, `AdminFeedbackControllerTest`: expectations corrected.
- Migrated to the shared container: `AuthSessionPostgresIntegrationTest`, `SaleOptimisticLockPostgresIntegrationTest`, `NativeSqlPostgresIntegrationTest`, `ConcurrencyPrimitivesPostgresIntegrationTest`, `SchemaProvisioningHygienePostgresIntegrationTest`, `TenantIsolationIntegrationTest`, `TenantMigrationIntegrationTest`.
- Trackers: `epics-remediation-audit.md` (11 items ticked; 3 items routed to 15.9), `sprint-status.yaml`, `epic-15-context.md` (compiled).
- `v1s-15-11-review1-attempt.patch`: the pass-1 attempt, kept for the bad_spec KEEP trail.

**Review:**
- **Pass 1:** 39 findings, 1 bad_spec loop (the scheduler's transactional design); the low-cost fixes were folded into the amended spec. Rejections are recorded with their reasons in the triage log.
- **Pass 2:** 33 findings.
  - 5 patched: lock concurrency test (medium), expiry re-check under the lock (medium), lock javadoc, `failingOnce` scoping, `dropDatabase` javadoc.
  - 3 deferred and routed to v1s-15-9: `cancelDeletion` blind write, cancel on a dropped schema, `forceDelete` without DROP.
  - The rest rejected with reasons in the triage log.

**Follow-up review recommended: true.** Pass 2 patched two medium entries in `AccountDeletionScheduler` (the lock-based expiry re-check and its concurrency test). The unverified risk is the interaction between the per-tenant transaction and the row lock and a real `cancelDeletion` from the API: only a raw UPDATE contender was exercised.

**Verification:**
- `mvn -o test` (full): 2229 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS.
- During the run a single Testcontainers `postgres:16-alpine` was observed (alongside an unrelated pre-existing `nkap-pg-tmp`, left untouched).
- No outbound WhatsApp call appears in the logs.
- `keevo/backend/target` was deleted.

**Residual risks:**
- The cancel races listed above (owned by v1s-15-9).
- GitHub Actions has not run the new workflow; Actions end in `startup_failure` per project memory.
- Full-context tests keep the production schedulers active.

### Follow-up review pass — 2026-10-13

**Summary:** fresh four-layer review of the story-scoped diff (baseline → current code, including the v1s-15-9 `AccountDeletionConcurrencyPostgresIntegrationTest`). The pass-2 residual risk ("only a raw UPDATE contender was exercised") is now closed: v1s-15-9 races the real `AccountDeletionService.cancelDeletion` and `AdminTenantService.cancelDeletion` against a scheduler blocked inside the DROP, and checks that each cancel waits on the row lock and is then refused. The "schedulers active in full-context tests" risk was verified real and fixed.

**Files changed:**
- `keevo/backend/src/main/java/com/keevo/KeevoApplication.java`: `@EnableScheduling` removed.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/config/SchedulingConfig.java` (new): `@EnableScheduling` behind `keevo.scheduling.enabled`, on by default.
- `keevo/backend/src/test/resources/application-test.yml`: `keevo.scheduling.enabled: false`.
- `KeevoApplicationContextIntegrationTest`: new check that no scheduling post-processor exists in the test profile while ShedLock is still wired.
- `AccountDeletionSchedulerTest`: new test where the WhatsApp send fails after commit.
- `AccountDeletionSchedulerPostgresIntegrationTest`: new test that a user belonging to several tenants is preserved; the WhatsApp assertion is now limited to the seeded tenant.
- `AccountDeletionConcurrencyPostgresIntegrationTest`: the due tenants seeded by `findDeletionsDueBy` are reset in a `finally`.

**Review findings:** 37 findings.
- 5 medium entries patched, in 4 groups. No high.
- 0 deferred.
- 32 rejected, each with its reason in the triage log.

**Follow-up review recommended: false.** This follow-up pass patched no high entry.

**Verification:**
- Targeted run, 32 tests, 0 failures: `AccountDeletionSchedulerTest` 12, `AccountDeletionSchedulerPostgresIntegrationTest` 4, `AccountDeletionConcurrencyPostgresIntegrationTest` 11, `KeevoApplicationContextIntegrationTest` 5.
- Batch `backend/shared`: 493 tests, 0 failures, 0 errors.
- Batch `backend/architecture`: 6 tests, 0 failures, 0 errors.

**Residual risks:**
- The GitHub Actions workflow has still not run (pre-existing `startup_failure`).
- Committed fixtures still accumulate in the shared test database. This was accepted in passes 1 and 2.
