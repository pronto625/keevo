---
title: '11-5 — Performance fixes: admin tenant-list N+1, pending-sales N+1, keyset pull indexes'
type: 'refactor'
created: '2026-10-13'
status: 'done'
baseline_revision: '8700dd45b4bbecbcae9efbe116d0c3c8c73aeb28'
review_loop_iteration: 1
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-11-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/11-5-correctifs-performance-n1-bug-index.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 11-5 (ready-for-dev) lists audit-confirmed performance defects still present: the admin tenant list fires one `SELECT schema_name … WHERE id = ?` per row (and the detail path re-queries it), the pending-sales list lazily loads `sale_items` once per sale, and the keyset pull (`DeltaPageQuery`, ORDER BY cursor, id) has no supporting indexes on large tenant tables. The day-closure "index bug" (AC5) is already fixed on this branch (`DayClosureDeltaProvider` maps `totalRevenue` = `row[5]`, no `totalTransactions` key).

**Approach:** Implement story file tasks 1, 2 and 4 as written, record AC5 as already satisfied (with a regression test that `totalSales` = row[4] and `totalRevenue` = row[5]), and close the routed keyset-index item with tenant-schema indexes matching the cursor/id order of each delta provider.

## Boundaries & Constraints

**Always:** Tenant list/detail responses byte-identical to today for the same data (only query count changes). Pending sales: `SELECT DISTINCT s … LEFT JOIN FETCH s.items WHERE s.storeId = :storeId AND s.status = :status` (same pattern as `findByIdWithItems`), one row per sale. Keyset indexes: for each table read by a delta provider through `DeltaPageQuery`, an index on `(<cursor column>, id)` created idempotently: for a NEW schema (empty tables) during provisioning with plain `CREATE INDEX IF NOT EXISTS`, each statement in its own try/catch (a failed index is logged and never fails provisioning); for EXISTING schemas by a dedicated background job — never inside `TenantSchemaSyncService.doSync`, never on a request thread — started after `ApplicationReadyEvent` on a single instance (ShedLock) and re-run daily, which for each valid schema and each index runs in autocommit `SET statement_timeout = '15min'` then `CREATE INDEX CONCURRENTLY IF NOT EXISTS …`, first dropping (`DROP INDEX CONCURRENTLY`) an existing index of that name that is INVALID (`pg_index.indisvalid = false`) or whose definition differs from the expected one; one failure is logged with the exception and the job continues; when the cursor is an expression (e.g. `COALESCE(updated_at, created_at)`), index that same expression; when the id is compared as text (`id::text`), index the same expression. Schema names stay gated by `TenantSchema.isValid`.

**Never:** No change to the 9-1 tenant DTOs or labels (that is spec 9-1-completion); no change to cross-schema metric queries other than reusing the preloaded schema name; no change to sync payloads.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| List page of 25 | 25 tenants | exactly one schema-name query for the page (or none if the list query carries it) | — |
| Tenant with null/invalid schema | schema missing | item kept with zero metrics, as today | — |
| Pending sale with 3 items | 2 sales | 2 sales, each 3 items, one SQL statement | — |
| Existing tenant schema | already provisioned | indexes built by the background job (CONCURRENTLY, autocommit), idempotent, no write lock held, no request blocked | failure logged with cause, retried next run |
| Invalid leftover index | a CONCURRENTLY build was interrupted | dropped and rebuilt by the next run | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/admin/tenant/application/service/AdminTenantService.java:112,144,257-290` -- list/detail enrichment, `safeLoadSchemaName`.
- `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/out/persistence/jpa/SaleSpringRepository.java:26-35` -- `findByIdWithItems` pattern, derived `findByStoreIdAndStatus`.
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/DeltaPageQuery.java:16-44` and every `*DeltaProvider` in that package -- cursor expression and id expression per table.
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/DayClosureDeltaProvider.java:36-45` -- AC5 already fixed.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java` + `TenantSchemaSyncService` -- DDL for new and existing schemas.

## Tasks & Acceptance

**Execution:**
- `AdminTenantService.java` + `AdminTenantServiceTest.java` -- story Task 1 (batched schema names, detail reuse, remove `safeLoadSchemaName` if unused).
- `SaleSpringRepository.java` + a JPA test (`@DataJpaTest` on the Testcontainers base) -- story Task 2.
- `DayClosureDeltaProviderTest.java` -- regression test for AC5.
- `TenantSchemaProvisioner.java` -- keyset index DDL list (one per delta table, per-index try/catch at provisioning).
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/*DeltaProvider.java` -- expose each cursor expression as a `static final String CURSOR` used in its `DeltaPageQuery.fetch` call.
- new `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/SyncKeysetIndexJob.java` (+ scheduling) -- background build for existing schemas.
- tests: job integration test on Testcontainers (index created on a populated table without blocking a concurrent insert, INVALID index rebuilt, wrong-definition index replaced, idempotent second run, schemas dropped in `@AfterEach`); guard test (sync test package) asserting every provider `CURSOR` equals the leading key of its index definition; `AdminTenantServiceTest` detail-path happy test with exactly one `schema_name` lookup; `SalePendingFetchJoinPostgresIntegrationTest` adapter path counts statements (== 1) and deletes its rows after each test.
- `11-5-correctifs-performance-n1-bug-index.md` -- tick tasks and routed items, Status done, completion notes (AC5 already fixed by an earlier rename).

**Acceptance Criteria:**
- Given the admin tenant list of 25 rows, when it loads, then the number of `schema_name` lookups does not grow with the row count (asserted in test).
- Given an existing tenant schema, when the app starts or the schema sync runs, then the keyset indexes exist (asserted by an integration test querying `pg_indexes`).

## Spec Change Log

### 2026-10-13 — Loopback 1 (bad_spec, orchestrator decision)
- Trigger: review pass 1 — keyset indexes were built with plain `CREATE INDEX` inside the `doSync` transaction (boot + first request via `syncIfNeeded`): SHARE locks on 15 tables held until commit, build time unbounded (`lock_timeout` bounds only waits), a skipped index never retried within the instance, no INVALID/definition repair.
- Amended (Boundaries, matrix, tasks): existing schemas get the indexes from a dedicated background job (after ApplicationReadyEvent, ShedLock, daily rerun) using `CREATE INDEX CONCURRENTLY` in autocommit with a statement timeout, INVALID/different-definition repair; provisioning builds them per-index guarded; provider cursors exposed as constants with a guard test. The `<intent-contract>` mechanism sentence was amended by the orchestrator under the user's standing instruction to decide from the docs (epic 11 context: performance without blocking tenants) instead of halting.
- Known-bad state avoided: write-blocking index builds on the request path at deploy.
- KEEP: `AdminTenantService` batched schema-name lookup and detail-path reuse (as implemented, add `e` to the WARN log); `SaleSpringRepository.findByStoreIdAndStatus` JOIN FETCH and `SalePendingFetchJoinPostgresIntegrationTest`; `DayClosureDeltaProviderTest` regression; the 15 index definitions (table, cursor expression, `CAST(id AS text)`) in `TenantSchemaProvisioner`; story file updates (fix the contradictory ticks: Task 3 subtasks and Task 4 full-suite tick → mark N/A with reason).
- Attempt saved: `11-5-review1-attempt.patch`.

## Review Triage Log

### 2026-10-13 — Review pass (after loopback 1)
- verdicts: 33 findings — high 0, medium 6, low 15, false 4, maybe-false 0 (+ intent rows descriptive)
- findings:
  - `[medium]` `[patch]` (blind) OR keyset predicate prevents an index start bound — row-value `(cursor, CAST(id AS text)) > (:afterCursor, :afterId)`; `DeltaPageQueryKeysetIndexPostgresIntegrationTest` (20k rows, EXPLAIN on real SQL, Index Scan asserted, page content checked).
  - `[medium]` `[patch]` (blind) deferred item closed without EXPLAIN evidence — same test.
  - `[low]` `[reject]` (blind) a timing-out build repeats daily silently — tables are small at < 200 tenants; ERROR log each time; INVALID index dropped on next run.
  - `[low]` `[patch]` (blind) run can outlive the 6 h lock — 5 h budget, remaining schemas logged.
  - `[low]` `[reject]` (blind) shutdown mid-build leaves INVALID indexes — repaired by the next run (INVALID drop+rebuild).
  - `[low]` `[reject]` (blind) write cost of 15 indexes unmeasured — one 2-column index per synced table, required by the keyset pull; modest at current volumes.
  - `[low]` `[patch]` (blind) guard test uses a hand-written provider map — classpath scan of `DeltaEntityProvider` components.
  - `[medium]` `[patch]` (blind) job orchestration / provisioning best-effort untested — Postgres `runAll` test (ACTIVE/DELETED/missing schema), Mockito `runAtStartup` tests, provisioning bogus-index test.
  - `[low]` `[patch]` (blind) batched lookup failure path untested — unit test added.
  - `[medium]` `[defer→routed]` (blind) per-tenant metric N+1 (2 queries per tenant) not routed — routed to story 9-1 (owner of the admin tenant list) and its completion spec.
  - `[low]` `[reject]` (blind) variable-length IN list — page size is fixed at 25; statement cache cost negligible.
  - `[low]` `[patch]` (blind) misleading day-closure test data kept — merged into one semantically right test.
  - `[low]` `[patch]` (blind) pending list without ORDER BY — `ORDER BY s.occurredAt`, test asserts order; DISTINCT kept (same pattern as `findByIdWithItems`).
  - `[low]` `[reject]` (blind) « writes not blocked » test proves less than its name — CONCURRENTLY semantics are PostgreSQL's; the test pins the mode used.
  - `[low]` `[patch]` (blind) story bookkeeping — parents marked N/A, obsolete Dev Note flagged, File List completed; full-suite AC6 run by the orchestrator (13 packages green).
  - `[medium]` `[patch]` (edge) RuntimeException escapes ensureIndex — caught per index.
  - `[low]` `[reject]` (edge) dropping another session's in-progress build — single-instance ShedLock; only this job builds these indexes.
  - `[low]` `[patch]` (edge) lock expiry — same as blind budget row.
  - `[low]` `[patch]` (edge) unlock throws — caught + WARN.
  - `[low]` `[patch]` (edge) RESET failure returns a 15 min-timeout connection to the pool — connection aborted.
  - `[low]` `[patch]` (edge) SKIPPED folded into unchanged — `skipped` counter.
  - `[false]` `[reject]` (edge) cursor column absent in an unmigrated schema — the boot schema sync (`TenantSchemaMigrationRunner`) adds columns before `ApplicationReadyEvent`.
  - `[false]` `[reject]` (edge, claim) AC « app starts or schema sync » not met by sync — AC wording predates loopback 1; the amended Boundaries define the background job; editing the AC would edit this build's spec.
  - `[medium]` `[patch]` (gap) batched IN SQL never on real PostgreSQL — `AdminTenantListPostgresIntegrationTest`.
  - `[medium]` `[patch]` (gap) runAll/loadSchemas/startup lock never run — same as blind orchestration row.
  - `[false]` `[reject]` (intent) A/B/C/D readings and surfaces — descriptive; C2 planner evidence now added; B2 out of the named surface.
  - `[false]` `[reject]` (intent) failure-scope of batched lookup — accepted, logged with cause, tested.

### 2026-10-13 — Review pass
- verdicts: 30 findings — high 1, medium 5, low 12, false 4, maybe-false 0 (+ intent rows descriptive)
- findings:
  - `[high]` `[bad_spec]` (blind) keyset indexes lock 15 tables for writes during the sync transaction at boot/first request — triggered loopback 1 (background CONCURRENTLY job).
  - `[high]` `[bad_spec]` (blind) no bound on build time on the request thread — same root (loopback 1).
  - `[low]` `[patch]` (blind) batched lookup failure logged without the exception — KEEP note: add `e` (applied in loopback).
  - `[medium]` `[patch]` (blind) detail path single lookup untested — detail happy-path test (loopback task).
  - `[medium]` `[patch]` (blind) adapter path does not count statements — statement count == 1 (loopback task).
  - `[medium]` `[patch]` (blind) integration tests leave sales and schemas behind — cleanup in `@AfterEach` (loopback task).
  - `[medium]` `[bad_spec]` (blind) index list can drift from providers — provider `CURSOR` constants + guard test (loopback 1).
  - `[low]` `[reject]` (blind) EXPLAIN test weak — replaced by the job integration test; plan choice with bind parameters is the planner's business once the index matches the ORDER BY.
  - `[low]` `[patch]` (blind) story ticks contradict notes — mark N/A with reason (KEEP note).
  - `[false]` `[reject]` (blind) status inconsistent — orchestrator finalizes all three.
  - `[low]` `[reject]` (blind) scope grew (indexes) — the routed item is part of the story (`### Deferred items routed`), rollout note added by loopback (job runs in background).
  - `[low]` `[reject]` (blind) duplicate day-closure test — explicit regression for AC5 requested by the spec; cheap.
  - `[low]` `[reject]` (blind) DISTINCT cost — same pattern as `findByIdWithItems`; pending lists are tiny.
  - `[medium]` `[bad_spec]` (blind) IF NOT EXISTS keeps a wrong-definition index — definition check + rebuild (loopback 1).
  - `[high]` `[bad_spec]` (edge) skipped index never retried within the instance — daily job rerun (loopback 1).
  - `[high]` `[bad_spec]` (edge) build time unbounded on request path — same root.
  - `[high]` `[bad_spec]` (edge) SHARE locks held until final commit — same root.
  - `[low]` `[bad_spec]` (edge) provisioning fails on an index failure — per-index try/catch (loopback 1).
  - `[low]` `[reject]` (edge) one bad row zeroes the whole page — ids come from the same `public.tenants` uuid column; a failure there is a DB failure, logged with cause now.
  - `[false]` `[reject]` (edge) mixed timestamp types make GREATEST non-immutable — `stock_transfers.occurred_at` and `updated_at` are both `TIMESTAMPTZ` in the provisioner DDL.
  - `[medium]` `[patch]` (edge) test schemas never dropped — same as blind cleanup row.
  - `[low]` `[patch]` (edge) `runner.runAll()` syncs foreign tenants in the test — new job test calls the job on its own schemas only.
  - `[high]` `[bad_spec]` (edge, claim) « next sync retries » false within the instance — same root as loopback 1.
  - `[medium]` `[bad_spec]` (edge, claim) request can stall on a slow build — same root.
  - `[medium]` `[patch]` (gap) detail path untested — same as blind row 4.
  - `[low]` `[reject]` (gap) batched IN query only mocked — the old per-row lookup was mock-only too; covered indirectly by the 9-x admin integration tests reading `public.tenants`; low.
  - `[medium]` `[bad_spec]` (gap) index/cursor coupling — same as blind drift row.
  - `[low]` `[patch]` (gap other) schemas not dropped — same as cleanup row.
  - `[false]` `[reject]` (intent) A/B/C/D readings — descriptive; B2 (unscoped `findByStatus`) is not this story's surface (the spec names `findByStoreIdAndStatus`, the owner flow has near-zero traffic per its javadoc).
  - `[false]` `[reject]` (intent) failure scope of the batched lookup — accepted, logged with cause.


### 2026-10-14 — Follow-up review pass (4 reviewers on 2fb… → 5d6d20cb diff, triaged against current code)
- verdicts: high 0, medium 3, low 9, false/reject 4
- findings:
  - `[medium]` `[patch]` (edge) a valid index with another definition was dropped before its replacement existed — rebuilt CONCURRENTLY under `<name>_tmp`, then swapped (drop old + rename); INVALID leftovers still drop → create.
  - `[medium]` `[patch]` (edge) after a `ConnectionAbortedException` the remaining indexes of the schema were reported "unchanged" — current + remaining counted FAILED.
  - `[medium]` `[patch]` (gap) 5 h budget only checked per schema — also checked before each index (`ensureIndexes(schema, startedAt)`).
  - `[low]` `[patch]` pending sales ordered by `occurredAt` only → `ORDER BY s.occurredAt, s.id`; `pg_class.relkind` check (a non-index relation holding the name → WARN + FAILED); shared ShedLock constants for annotation and `runAtStartup`; provisioner catches `SQLException | RuntimeException` per index with a guarded savepoint rollback; `statement_timeout` reset asserted; random `kv_[a-z0-9]{6}` schemas in ITs; store counts asserted per tenant; `TenantSchema.validate` before the swap SQL (guard test).
  - `[false]` `[reject]` keyset index on every provider table at startup is too slow — background job with budget, by design (loopback decision).

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.admin.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.commerce.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.sync.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.shared.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures

## Auto Run Result

Status: done

**Summary:** Story 11-5 closed. Admin tenant list loads schema names in one batched query per page (detail path reuses its single lookup); pending sales load their items with one JOIN FETCH query, ordered; AC5 (day-closure mapping) was already fixed and is pinned by a regression test; the keyset pull now uses a row-value bound and 15 `(cursor, CAST(id AS text))` tenant-schema indexes — created per-index at provisioning and, for existing schemas, by `SyncKeysetIndexJob` (after startup and daily, ShedLock, `CREATE INDEX CONCURRENTLY` in autocommit with a 15 min statement timeout, INVALID / wrong-definition repair, 5 h budget). Provider cursors are `CURSOR` constants guarded by a classpath-scanning test.

**Files changed:** `admin/tenant/application/service/AdminTenantService.java`, `commerce/sale/adapter/out/persistence/jpa/SaleSpringRepository.java`, `sync/sync/application/provider/{DeltaPageQuery,*DeltaProvider}.java`, `shared/infrastructure/persistence/{TenantSchemaProvisioner,SyncKeysetIndexJob}.java`; tests `AdminTenantServiceTest`, `AdminTenantListPostgresIntegrationTest`, `SalePendingFetchJoinPostgresIntegrationTest`, `DayClosureDeltaProviderTest`, `DeltaPageQueryTest`, `DeltaPageQueryKeysetIndexPostgresIntegrationTest`, `SyncKeysetIndexesMatchProvidersTest`, `SyncKeysetIndexJobTest`, `SyncKeysetIndexJobPostgresIntegrationTest`, `SchedulerLockCoverageTest`; story file `11-5-correctifs-performance-n1-bug-index.md`.

**Review:** pass 1 — 30 findings, 1 high root (index build in the sync transaction) → loopback 1 (orchestrator-decided mechanism, attempt saved as `11-5-review1-attempt.patch`); pass 2 — 33 findings, patched 6 medium entries and 11 low, 1 routed (per-tenant metric N+1 → 9-1), rejected 15.

**Follow-up review recommended:** true — 6 medium entries patched in pass 2; unverified risk: the row-value keyset bound is proven on real PostgreSQL for `sales` and `stock_transfers` only, the other 13 tables rely on the shared SQL builder.

**Verification:** backend chunked regression, one package at a time: admin 111, architecture 6, catalog 395, commerce 232, feedback 37, identity 371, inventory 165, messaging 126, reporting 375, shared 508, store 42, subscription 34, sync 439 — 0 failures, 0 errors.

**Residual risks:** a CONCURRENTLY build waits for long-open transactions (bounded by the statement timeout, retried next run); the startup/daily triggers themselves are unit-tested, not run end-to-end.

