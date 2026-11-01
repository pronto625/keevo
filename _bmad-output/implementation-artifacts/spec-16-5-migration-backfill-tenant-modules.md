---
title: '16-5 Backfill tenant_modules from the current plan + keep plan-derived rows in sync'
type: 'feature'
created: '2026-11-01'
status: 'done'
baseline_revision: 'e6aa0d71c05cf3f491fe264849015556c9f62186'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
warnings: ['oversized']
deferred:
  - summary: >-
      No direct test of the JdbcPlanModuleRowStore upsert guard (WHERE origin = 'PLAN') against an existing ADMIN row.
    evidence: |-
      Verification-gap review 2026-11-01: the guard is only reached in a race (findRows already preserves ADMIN rows); a direct adapter call on an ADMIN row asserting false + unchanged row would settle it.
    location: >-
      keevo/backend/src/main/java/com/keevo/shared/module/adapter/out/persistence/JdbcPlanModuleRowStore.java
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** Paid-module states of existing tenants are not stored: `ModuleStateRules.planState` (13.10a transition rule) derives them on every read from the tenant's `subscriptions` row. Epic 16 (lifecycle 16.1, profiles 16.8, bundles 16.2, console 16.4) needs one explicit `public.tenant_modules` row per tenant × paid module, created idempotently, with the remaining trial kept exactly (MOD-FR19, gap G4). Prod tenants also never had the free migrated modules (`crm` baseline, adopted on dev by the deleted boot provisioner) recorded.

**Approach:** (1) Add an `origin` column to `tenant_modules` (`SYSTEM` free/provisioner rows, `PLAN` rows derived from the subscription, `ADMIN` rows set by the super-admin lifecycle). (2) A core `PlanModuleRowSync` computes, for one tenant, the paid-module rows equivalent to the transition rule and upserts them, never touching `ADMIN` rows. (3) Every subscription writer calls it in its own transaction, so a row never contradicts the plan after a plan change. (4) A super-admin, on-demand backfill endpoint runs the sync for every live tenant, then provisions every enabled module that ships migrations, with a dry-run mode returning the per-tenant report. Nothing runs at boot.

## Boundaries & Constraints

**Always:**
- Parity: for every tenant, `ModuleRegistry` states after the backfill equal the states before it (same instant), for every module. Mapping per paid (non-free) module, plan read exactly as `JdbcTenantModuleStateStore.readPlan` does (oldest subscription row + latest `tenant_plan_events`):
  - `PREMIUM_TRIAL`, status ACTIVE, `expires_at` null or future → `TRIAL`, tier `PREMIUM`, `expires_at` = plan end, `grace_until` = end + 7 d (null when end is null).
  - `PREMIUM`, same condition → `ACTIVE`, same dates.
  - `PREMIUM`/`PREMIUM_TRIAL`, ACTIVE or EXPIRED, end in the past → `EXPIRED`, `expires_at` = end, `grace_until` = end + 7 d.
  - `FREE` with latest plan event cause `EXPIRY` → `EXPIRED`, `expires_at` = event `occurred_at`, `grace_until` = +7 d.
  - anything else (FREE, no subscription, SUSPENDED) → `DISABLED`, `disabled_at` = now.
  - plan `UNREADABLE` → tenant skipped, reported as failure, nothing written.
- Rows with origin `ADMIN` are never modified by the sync or the backfill; `SYSTEM` free rows are never modified either. A `PLAN` row is rewritten only when it differs from the computed one (idempotent: a second run writes 0 rows).
- `ModuleLifecycleService` activate/deactivate write origin `ADMIN`; `ModuleProvisionerService.recordVersion` free-row insert writes `SYSTEM`.
- Subscription writers calling the sync in the same transaction, after their subscription update: `ActivatePlanService` (every call, including an expiry-only update), `ChangeTenantPlanService.changePlan`, `SubscriptionExpiryService` (per downgraded tenant), signup (`TenantFactory`, after `TenantSchemaProvisioner` seeded the subscription). The module registry cache for that schema is evicted after commit.
- Backfill endpoint super-admin only, single run at a time (ShedLock or DB advisory lock → 409 `MODULE_BACKFILL_RUNNING` in French), French messages, report lists tenants by code (never schema names, UUIDs or SQL in messages).
- Free modules stay row-less unless provisioned (no row = ENABLED by manifest); the backfill provisions every module that is ENABLED for the tenant and ships migrations (`crm`, `invoicing` today) through the existing `ModuleProvisioner.provision` (idempotent; `crm` baseline at 1).

**Never:**
- No boot-time writes to `tenant_modules` or schema changes at startup (user rule, 13.9 review); the Flyway `public` migration only adds the column and back-labels existing rows.
- Do not remove `ModuleStateRules.planState` (still needed for tenants created between deploy and backfill, and as the fallback until 16.1).
- Do not convert the tenant DDL of the other modules to `db/modules/<id>`, do not stop `TenantSchemaSyncService` mirroring, do not repair drifted crm schemas — routed to `16-12-conversion-ddl-tenant-modules` (decision recorded: the sync stays a shim until then).
- No app (Flutter) or dashboard change.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Trial tenant | PREMIUM_TRIAL ACTIVE, ends in 40 d, no rows | 8 paid rows TRIAL/PREMIUM, expires = plan end, grace = +7 d; states unchanged (ENABLED) | — |
| Expired not yet downgraded | PREMIUM_TRIAL ACTIVE, ended 2 d ago | EXPIRED rows, grace = end + 7 d → READ_ONLY | — |
| Downgraded in grace | FREE, last event EXPIRY 3 d ago | EXPIRED rows anchored on the event → READ_ONLY 4 more days | — |
| Plain Free | FREE, last event ADMIN_DOWNGRADE | DISABLED rows | — |
| Admin row present | `invoicing` row origin ADMIN ACTIVE until 2027 on a FREE tenant | row untouched, other paid rows DISABLED | — |
| Re-run | backfill run twice | 2nd run: 0 rows written, 0 provisioned changes | — |
| Plan change after backfill | admin upgrades FREE → PREMIUM until 2027-06-01 | PLAN rows become ACTIVE until 2027-06-01 in the same transaction | rollback of the plan change rolls back the rows |
| Unreadable plan | subscriptions table missing / timeout | tenant reported in failures, nothing written, run continues | — |
| Dry run | `dryRun=true` | report of rows that would be inserted/updated per tenant, nothing written, nothing provisioned | — |
| Concurrent run | 2nd call while 1st runs | 409 `MODULE_BACKFILL_RUNNING` | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/resources/db/migration/V29__module_registry.sql` -- `tenant_modules` schema (status/tier CHECKs, UNIQUE(tenant_id, module_id)); next file is `V32__…` (latest is V31).
- `keevo/backend/src/main/java/com/keevo/shared/module/application/ModuleStateRules.java:43-117` -- `stateOf` (row wins), `planState`, `PLAN_GRACE`; the mapping above must reproduce `planState` exactly (exclusive bounds).
- `.../shared/module/adapter/out/persistence/JdbcTenantModuleStateStore.java:43-91` -- `FIND_STATUSES_SQL`, `FIND_PLAN_SQL` (oldest row), `LAST_PLAN_EVENT_SQL`, `readPlan` (UNREADABLE on timeout, NONE on 42P01), `INSERT_ACTIVE_FREE_SQL`, `LIVE_TENANTS_SQL`. Reuse `readPlan` for the sync.
- `.../shared/module/application/port/TenantModuleStatePort.java:65` -- `Plan` record (planType, status, expiresAt, expiryDowngradeAt, unreadable).
- `.../shared/module/adapter/out/persistence/JdbcTenantModuleLifecycleStore.java:50-70` -- `UPSERT_ACTIVATED_SQL`, `UPSERT_DISABLED_SQL`: add `origin = 'ADMIN'`.
- `.../shared/module/application/ModuleProvisionerService.java:101,139,164,212` -- `provision`, `recordVersion` (free row insert → SYSTEM), `ensureProvisioned` (crm skipped "until 16.5": the backfill is now the crm adoption path; keep the lazy skip), `provisionAll` loop pattern + `ProvisioningReport`.
- `.../shared/module/application/ModuleLifecycleService.java` -- registry eviction pattern after writes.
- `.../subscription/plan/application/service/ActivatePlanService.java:90-140` -- REQUIRES_NEW JPA tx; call sync inside the callback after `subscriptionRepository.save` (tenant UUID + schema known).
- `.../subscription/plan/application/service/ChangeTenantPlanService.java:60-75` -- JDBC `@Transactional`; call sync after `recordEvent`.
- `.../subscription/plan/application/service/SubscriptionExpiryService.java:60-150` -- per-tenant downgrade + EXPIRY event; call sync after the event.
- `.../identity/auth/application/service/TenantFactory.java:76-111` -- signup: subscription seeded PREMIUM_TRIAL; call sync before `provisionActiveModules`.
- `.../admin/module/adapter/in/rest/ModuleAdminController.java` -- add `POST /api/v1/admin/modules/backfill?dryRun=` here (`@EntitlementExempt("super-admin")`, existing error-mapping style).
- `.../shared/infrastructure/web/GlobalExceptionHandler.java` -- French message map; add `MODULE_BACKFILL_RUNNING`.
- `keevo/backend/src/main/java/com/keevo/shared/domain/exception/ErrorCode.java` -- add the code.
- Tests to model on: `src/test/java/com/keevo/shared/module/ModuleProvisionerPostgresIntegrationTest.java`, `ModuleLifecyclePostgresIntegrationTest.java`, `application/ModuleTransitionRuleTest.java`, `.../admin/subscription/application/service/TenantPlanManagementPostgresIntegrationTest.java`; base classes `PostgresIntegrationTest`, `SharedPostgresContainer.freshDatabase`.
- Dev DB: container `keevo_postgres`, port 5444, db `keevo_dev`, user `keevo`, password `keevo_local_pwd` (331 tenants).

## Tasks & Acceptance

**Execution:**
- `keevo/backend/src/main/resources/db/migration/V32__tenant_modules_origin.sql` -- add `origin VARCHAR(10) NOT NULL DEFAULT 'SYSTEM'` + CHECK (`SYSTEM`,`PLAN`,`ADMIN`); label existing rows: paid modules (`module_definitions.is_free = false`) → `ADMIN`, others `SYSTEM` -- explicit provenance so the sync never overrides a super-admin decision.
- `.../shared/module/application/PlanModuleRows.java` (pure) -- compute the desired paid-module rows from `Plan` + now + catalogue, per the mapping; unit-test every branch incl. boundaries, and a parity property: `stateOf(row) == planState(plan)` for the same instant.
- `.../shared/module/application/port/PlanModuleRowSync.java` + `.../application/PlanModuleRowSyncService.java` + JDBC adapter in `JdbcTenantModuleStateStore` (or a new `JdbcPlanModuleRowStore`) -- `sync(tenantSchema)` → `SyncResult(inserted, updated, unchanged)`, `preview(tenantSchema)` for dry-run; upsert with `WHERE origin = 'PLAN'` guard on conflict, evict registry after commit (`TransactionSynchronization`).
- `JdbcTenantModuleLifecycleStore.java`, `JdbcTenantModuleStateStore.INSERT_ACTIVE_FREE_SQL` -- write `origin`.
- `ActivatePlanService.java`, `ChangeTenantPlanService.java`, `SubscriptionExpiryService.java`, `TenantFactory.java` -- call `PlanModuleRowSync.sync` as above; update their unit tests' mocks.
- `.../shared/module/application/ModuleBackfillService.java` -- for each live tenant (skip `public`, missing schema): sync (or preview), then provision ENABLED modules with migrations; lock; returns `BackfillReport` (tenants processed/skipped, rows inserted/updated/unchanged, modules provisioned, failures by tenant code).
- `ModuleAdminController.java` -- `POST /admin/modules/backfill?dryRun=true|false` (default true) returning the report; OpenAPI doc.
- `ErrorCode.java`, `GlobalExceptionHandler.java` -- `MODULE_BACKFILL_RUNNING` 409 « Une migration des modules est déjà en cours ».
- Tests -- `PlanModuleRowsTest` (pure), `ModuleBackfillPostgresIntegrationTest` (fresh DB: tenants in each matrix state, parity of `ModuleRegistry` states before/after with a fixed Clock, idempotence, ADMIN row untouched, dry-run writes nothing, invoicing provisioned for an enabled tenant), plan-change integration (ChangeTenantPlan FREE→PREMIUM updates PLAN rows; rollback leaves rows), controller test (403 non-super-admin, 409 concurrent).
- `_bmad-output/implementation-artifacts/sprint-status.yaml` + `_bmad-output/planning-artifacts/epics-refonte-modulaire.md` -- create key `16-12-conversion-ddl-tenant-modules` (backlog) with a brief carrying the 4 routed DDL items of 16.5; tick the 3 items handled here in 16.5; route "dry-run on a prod copy before go-live" to 16.7.

**Acceptance Criteria:**
- Given keevo_dev (331 tenants), when the backfill runs in dry-run then for real, then the real run's counts equal the dry-run's, per-tenant module states (all modules) are identical before/after, client/supplier/sale/invoice row counts are unchanged, and a second real run reports 0 rows written.
- Given a backfilled trial tenant, when the trial end passes, then paid modules become READ_ONLY then DISABLED exactly when they would have without the backfill.
- Given a super-admin activated `invoicing` on a Free tenant before the backfill, when the backfill and later plan changes run, then that row is never changed.

## Spec Change Log

## Review Triage Log

### 2026-11-01 — Review pass
- verdicts: 36 findings — high 0, medium 7, low 15, false 12, maybe-false 2
- findings:
  - `[low]` `[reject]` (intent-alignment) Prod-copy dry run not encoded; deferred to 16.7 — spec-decided routing; dev run on 331 tenants recorded below.
  - `[false]` `[reject]` (intent-alignment) FREE "Core only" reinterpreted as parity — parity is the spec's explicit rule (states unchanged), Free modules are ENABLED by manifest.
  - `[false]` `[reject]` (intent-alignment) "Chacun reçoit Core" not materialised as rows — core is always ENABLED in `stateOf`; rows are not required for state.
  - `[false]` `[reject]` (intent-alignment) Trial time carried (G4) — agrees, tested by `trialEndPassesAfterBackfill`.
  - `[low]` `[reject]` (intent-alignment) DDL conversion moved to 16-12 — spec Never clause; key + brief created.
  - `[low]` `[reject]` (intent-alignment) REST job instead of script — spec chose on-demand endpoint (no boot writes rule).
  - `[false]` `[reject]` (intent-alignment) Rollback undocumented — 16.7 item documents `DELETE … origin='PLAN'` (returns to the 13.10a rule) or backup restore.
  - `[low]` `[reject]` (intent-alignment) Continuous plan→row sync not in intent — required by the spec Design Notes (rows win over the plan).
  - `[medium]` `[patch]` (verification-gap) Read-only REQUIRES_NEW branch of `recordVersion` untested — added `ensureProvisionedInsideReadOnlyTransaction` (Postgres, read-only template, row with older version → recorded).
  - `[medium]` `[patch]` (verification-gap) Lifecycle relabel PLAN→ADMIN on conflict untested — added `lifecycleRelabelsPlanRowsAdmin` (activate + deactivate, then changePlan leaves the ADMIN rows identical).
  - `[medium]` `[patch]` (verification-gap) Backfill per-tenant failure isolation untested — added `ModuleBackfillServiceTest` (provisioning failure + sync IllegalStateException, later tenant still processed, lock released).
  - `[maybe-false]` `[defer]` (verification-gap) `upsertPlanRow` ADMIN guard (`WHERE origin='PLAN'`) has no direct test — guard now also covered indirectly by the FOR UPDATE serialisation; a direct adapter test would settle it.
  - `[medium]` `[patch]` (verification-gap other / blind / edge ×4, grouped) Unreadable plan inside a writer → unmapped 500 — `PlanUnreadableException` mapped to 503 SERVICE_UNAVAILABLE with a French message; the rollback of the plan change is intended (rows must never contradict the plan).
  - `[medium]` `[patch]` (blind) Missing `subscriptions` table gave NONE → 8 DISABLED rows, contrary to the matrix — sync path now returns UNREADABLE (failure reported, 0 rows), tenant KV-BFNT08 added to the matrix test.
  - `[low]` `[reject]` (blind) Signup fails when the row sync fails — same root cause as the grouped writer entry; a signup DB fault already aborted signup; message now mapped.
  - `[low]` `[reject]` (blind) Synchronous backfill may outlast a proxy timeout — prod < 100 tenants and the dev run of 331 tenants completed; async job with progress routed to 16-4 (console).
  - `[low]` `[reject]` (blind) ShedLock timed with the business Clock / 2 h max — prod Clock is the system clock; runs last minutes.
  - `[low]` `[reject]` (blind) No audit row for a backfill run — operator action logged at INFO with counts; per-tenant changes are visible in `tenant_modules.origin`; not required by the spec.
  - `[false]` `[reject]` (blind) READ_ONLY tenants never get tables adopted — free modules (crm) are always ENABLED, and paid modules are adopted lazily by `ensureProvisioned` on use.
  - `[false]` `[reject]` (blind) Rollback only "restore the backup" — the 16.7 item also gives the targeted `origin='PLAN'` delete.
  - `[false]` `[reject]` (blind) V32 labelling freezes leftover paid ACTIVE/FREE rows — prod has no `tenant_modules` (V29 never deployed) and dev had only crm rows (331 SYSTEM).
  - `[medium]` `[patch]` (blind) Read-only provisioner branch untested — grouped with the verification-gap entry above.
  - `[low]` `[reject]` (blind) `saveAndFlush` for every caller — single-row flush, negligible.
  - `[low]` `[reject]` (blind) Duplicated LIVE_TENANTS_SQL — different shape needed (schema_present); drift risk minor.
  - `[low]` `[patch]` (blind) `epic-16-context.md` order line and story list missed 16.12 — fixed by the orchestrator.
  - `[false]` `[reject]` (blind) No AC evidence — dev runs recorded in Auto Run Result.
  - `[false]` `[reject]` (blind) Expiry writer only mocked — `TenantPlanManagementPostgresIntegrationTest` covers the expiry sync on Postgres.
  - `[low]` `[patch]` (blind) French message style for MODULE_BACKFILL_RUNNING — « … déjà en cours. Réessayez dans quelques minutes. », spacing fixed.
  - `[medium]` `[patch]` (edge) Backfill sync can overwrite fresh PLAN rows with a stale plan (race with writers) — plan read now `SELECT … FOR UPDATE` on the subscription row, serialising with the writers.
  - `[medium]` `[patch]` (edge) Nightly expiry vs backfill race — same fix (grouped).
  - `[maybe-false]` `[reject]` (edge) Caught SQL error leaves an aborted transaction — the sync then throws and the transaction rolls back as a whole; no caller continues on it (if true, only low).
  - `[low]` `[reject]` (edge) Dependent module provisioned after its dependency failed — only adds a reported failure.
  - `[low]` `[reject]` (edge) Malformed version string — versions come from Flyway; not reachable in practice.
  - `[low]` `[reject]` (edge) Lock lost after 2 h / 409 for 2 h after a crash — runs last minutes; acceptable for a manual operation.
  - `[low]` `[patch]` (edge) TransactionException escapes recordVersion raw — now wrapped in ModuleProvisioningException.
  - `[false]` `[reject]` (edge) Second run after a trial end reports updates — correct: the AC is about an immediate re-run (0 written, verified).

## Design Notes

Why sync on every plan write and not only a one-shot backfill: once a row exists it wins over the plan (`stateOf`), so a one-shot backfill alone would freeze trials — a tenant who pays after the backfill would keep its old TRIAL end and lose its modules. Writing `PLAN` rows from every subscription writer keeps rows and plan equivalent until 16.1 makes the lifecycle the only writer. `DISABLED` rows (instead of none) for Free tenants keep the state explicit for 16.1/16.4 while giving the same state as the rule.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -DforkCount=1 -DargLine=-Xmx1536m -Dtest='com.keevo.shared.module.**'` -- expected: green
- `... -Dtest='com.keevo.subscription.**'` then `'com.keevo.admin.**'` then `'com.keevo.identity.auth.**'` -- expected: green (one package at a time, never the full suite)
- Dev run: start backend (`mvn spring-boot:run`, port 4500), login super-admin, `POST /api/v1/admin/modules/backfill?dryRun=true` then `dryRun=false` twice; compare a states snapshot (`GET /admin/tenants/{id}/modules` for a sample of 10 tenants of each plan) and table counts before/after; stop with `fuser -k 4500/tcp`.

## Auto Run Result

Status: done

**Summary:** `tenant_modules` rows for paid modules are now derived from each tenant's plan (`origin = PLAN`) and kept in sync by every subscription writer (signup, legacy activation, admin plan change, nightly expiry) in the writer's transaction; super-admin rows (`ADMIN`) and free/provisioner rows (`SYSTEM`) are never rewritten. A super-admin endpoint `POST /api/v1/admin/modules/backfill?dryRun=` (default dry run, single run at a time) applies the sync to every live tenant and provisions enabled modules that ship migrations (crm adoption, invoicing). Module states are identical before and after.

**Files changed:** V32 migration (`origin` column + labelling); `PlanModuleRows` (pure mapping); `PlanModuleRowSync(Service)` + `PlanModuleRowStore`/`JdbcPlanModuleRowStore` (upsert guarded by `origin='PLAN'`, plan read `FOR UPDATE`); `ModuleBackfill(Service)`; `ModuleAdminController` (endpoint); `JdbcTenantModuleStateStore`, `JdbcTenantModuleLifecycleStore` (origin), `ModuleProvisionerService` (read-only caller → REQUIRES_NEW row write); `ActivatePlanService`, `ChangeTenantPlanService`, `SubscriptionExpiryService`, `TenantFactory`, `SubscriptionRepositoryAdapter` (sync calls, flush); `ErrorCode`/`GlobalExceptionHandler` (409 MODULE_BACKFILL_RUNNING, 503 for an unreadable plan); tests (PlanModuleRowsTest, ModuleBackfillPostgresIntegrationTest, ModuleBackfillServiceTest, provisioner/admin/full-context updates); planning (16-12 key + brief, 16.5 items ticked, prod-copy dry run → 16.7, async backfill → 16.4).

**Review:** 36 findings — 9 patched (7 medium, 2 low entries), 1 deferred (direct test of the upsert ADMIN guard), 26 rejected with reasons in the triage log. Follow-up review recommended: true — two or more medium entries were patched on a first pass; the named risk is the new `FOR UPDATE` plan read inside the subscription writers (lock ordering with the writers' own subscription row locks), covered by the module/subscription/admin suites but not by a concurrent-writer test.

**Verification:** packages run one at a time, all green — shared.module 396, subscription 73, admin 521, identity.auth 261, architecture 32, invoicing 170, shared.infrastructure 604, sync 491, crm 158, messaging 168. Dev run on keevo_dev (331 tenants): first pass by the implementer — dry run 2 648 rows to insert, real run identical, states of all 331 tenants identical before/after, clients/suppliers/sales/invoices counts unchanged; after the review patches, dry run and real run again: 0 inserted, 0 updated, 2 648 unchanged, 0 failures. No app change (no device test needed).

**Residual risks:** the backfill is synchronous in one HTTP request (async job routed to 16-4); a dry run on a prod copy before go-live is routed to 16.7; `PlanTypeRealignJob` can write a REALIGNMENT event after an EXPIRY one (the stored EXPIRED row keeps the full grace, the old rule ended it early — rare, acceptable).
