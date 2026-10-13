---
title: 'v1s-13-12 — Tenant schema provisioning hygiene'
type: 'refactor'
created: '2026-10-11'
status: 'done'
baseline_revision: 'a20736708388bad7d505365a325faf1edd902120'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-13-context.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** Tenant-schema provisioning is inconsistent: the `^kv_[a-z0-9]{6}$` gate is re-implemented in ~10 places instead of `TenantSchema`; `DDL_USERS` lacks `failed_attempts`/`locked_until` (only added later by the request-time sync); per-tenant columns such as `version` (V5/V12) only reach existing tenants on their first authenticated request, so a failed or never-run sync surfaces later as `column "version" does not exist` (schedulers never trigger the sync); and V1's inline CHECK constraints were skipped on tables Hibernate had already created (`CREATE TABLE IF NOT EXISTS`).

**Approach:** Route every Java schema-name check through `TenantSchema.isValid/validate`; complete `DDL_USERS` (+ idempotent ALTERs for existing tenants); make `TenantSchemaMigrationRunner` run the full `TenantSchemaSyncService` sync for every tenant at boot, logging per-tenant failures at ERROR with a failure summary; add Flyway `V13__idempotent_check_constraints.sql` that (re)creates the V1 CHECK constraints by name with `DO $$` blocks.

## Boundaries & Constraints

**Always:** Keep each caller's existing failure contract (throw vs no-op vs `continue`, same exception type/ErrorCode). Every DDL stays idempotent (`IF NOT EXISTS`, `DO $$` guards). Boot sync never aborts application startup (one broken tenant must not take every tenant down) — failures are ERROR-logged per tenant and summarized. Request-path `syncIfNeeded` stays non-blocking. V13 must succeed on a fresh DB (V1 already created the constraints) and on a Hibernate-era DB (constraints missing), and must not fail on legacy rows violating a constraint (fall back to `NOT VALID` + `RAISE WARNING`).

**Never:** Edit an applied migration (V1..V12). Touch the SQL regex in `SyncErrorLogCleanupJob` (database-side filter, not a Java helper). Change tenant-schema CHECK constraints (tenant DDL already carries them). Add new dependencies.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Fresh DB | Flyway V1..V13 on empty PG | 6 named CHECK constraints present on public tables; `stock_movements_movement_type_check` includes `SALE_CANCELLED` | none |
| Hibernate-era table | public table exists without the constraint | V13 adds it | none |
| Legacy bad row | row violates new CHECK | constraint added `NOT VALID`, WARNING raised, migration succeeds | no failure |
| V13 re-run | constraints already present | no-op (no duplicate/exception) | none |
| New tenant | `provision()` | tenant `users` has `failed_attempts INTEGER NOT NULL DEFAULT 0`, `locked_until TIMESTAMPTZ` | — |
| Boot, tenant lacking `version` | existing tenant schema, sales/stock tables without `version` | runner syncs it at boot → column added before any request | per-tenant failure: ERROR log + `failed` count, startup continues |
| Invalid schema name | `foo` / null to any helper | same behavior as before (throw / no-op / skip) | unchanged contracts |

</intent-contract>

## Code Map

Backend `keevo/backend/src/main/java/com/keevo/`:
- `shared/infrastructure/persistence/TenantSchema.java` -- single gate (`isValid`, `validate` throws IAE without echoing value). Update javadoc list of callers.
- `shared/infrastructure/persistence/SchemaAwareMultiTenantConnectionProvider.java:115-120` -- private `isTenantSchema` → `TenantSchema.isValid` (keep `!DEFAULT_SCHEMA` check implicit: "public" fails the regex).
- `shared/infrastructure/persistence/TenantSchemaSyncService.java:90,116` -- drop `SCHEMA_PATTERN`; `syncIfNeeded` uses `TenantSchema.isValid`; new public `syncNow(String)` (validate, doSync, cache, propagate `SQLException`); `ensureRequiredIndexes` (~line 186) also runs the two `DDL_USERS_MIGRATE_*`.
- `shared/infrastructure/persistence/TenantSchemaProvisioner.java:33-42` (`DDL_USERS`), `:743-746,765-766,789-792` -- add columns to `DDL_USERS`; new package-visible `DDL_USERS_MIGRATE_FAILED_ATTEMPTS`, `DDL_USERS_MIGRATE_LOCKED_UNTIL` (ALTER … ADD COLUMN IF NOT EXISTS) run right after `DDL_USERS` in `createTables`; three regex checks → `TenantSchema.isValid` keeping DomainException(TENANT_PROVISION_FAILED) / silent return / IAE.
- `shared/infrastructure/persistence/TenantSchemaMigrationRunner.java` -- inject `TenantSchemaSyncService`; per schema: existing M1–M6 then `syncService.syncNow(schema)`; invalid schema name → `skipped++` (was silent `return` counted as ok); failures `log.error(..., e)`; summary at ERROR when `failed>0`; tenant list load failure → ERROR.
- Other Java duplicates → `TenantSchema.isValid`: `messaging/notification/adapter/out/persistence/impl/JdbcNotificationCooldownAdapter.java:92`, `identity/auth/adapter/out/persistence/impl/TenantSchemaAdapter.java:39`, `catalog/stock/adapter/out/persistence/MultiStoreStockRepositoryAdapter.java:99`, `sync/sync/application/strategy/DeltaStockStrategy.java:27,41`, `messaging/notification/application/scheduler/SalesTrendDetectionScheduler.java:73`, `subscription/plan/application/service/SubscriptionExpiryService.java:69`.
- `src/main/resources/db/migration/V13__idempotent_check_constraints.sql` -- new. Constraints (names/values from V1:140,141,167,256,271,284 + enums `ProductStatus`, `StoreType`, `InventoryScope`, `InventorySessionStatus`, `StockTransfer.TransferStatus`, `MovementType`).
- Read-only: `application.yml` flyway (`schemas: public`, baseline v0); test pattern `src/test/java/com/keevo/shared/infrastructure/persistence/ConcurrencyPrimitivesPostgresIntegrationTest.java` (`@DataJpaTest` + Testcontainers PG16 tmpfs + `@ServiceConnection`).

## Tasks & Acceptance

**Execution:**
- `TenantSchemaProvisioner.java` -- complete `DDL_USERS`, add the two migrate constants, converge 3 checks.
- `TenantSchemaSyncService.java` -- converge check, add `syncNow`, run users migrations in `ensureRequiredIndexes`.
- `TenantSchemaMigrationRunner.java` -- boot-time full sync + ERROR visibility as mapped.
- `SchemaAwareMultiTenantConnectionProvider.java`, `TenantSchema.java` javadoc, and the 6 other callers -- converge on `TenantSchema.isValid`.
- `V13__idempotent_check_constraints.sql` -- one `DO $$` block per constraint: skip if table missing (`to_regclass`), skip if constraint with that name exists on that table, else `ADD CONSTRAINT`; on `check_violation` re-add `NOT VALID` + `RAISE WARNING`. `stock_movements_movement_type_check`: drop-if-present then re-add with `SALE_CANCELLED` (superset).
- Tests: unit — provisioner users DDL contains both columns; sync `syncNow` propagates failure / `syncIfNeeded` swallows; runner calls `syncNow` per valid tenant and counts failures without throwing; connection provider no `search_path` for `public`/invalid. Integration (Testcontainers PG16) `SchemaProvisioningHygienePostgresIntegrationTest` — after Flyway: 6 constraints present; drop two + re-execute V13 script → recreated; re-execute again → no error; bad row → `NOT VALID` path; `provision()` on real PG → users lockout columns exist; tenant missing `sales.version` → runner run → column present.

**Acceptance Criteria:**
- Given the backend codebase, when grepping Java sources for `"^kv_[a-z0-9]{6}$"`, then only `TenantSchema.java` defines it.
- Given an existing tenant whose schema lacks a per-tenant column, when the application boots, then the column exists before any authenticated request, and a tenant whose sync fails produces an ERROR log naming the schema while startup completes.
- Given all four items, when the story closes, then each box in epics-remediation-audit.md Story 13.12 is ticked with commit/decision.

## Spec Change Log

### 2026-10-11 — Review pass 1 amendments (applied as patches)
- Trigger: review findings "V13 skips stale same-name constraints" and "ApplicationRunner runs after the web server / scheduler start".
- Amended (outside intent-contract, recorded here instead of re-deriving): V13 now drops-if-exists and re-adds all 6 constraints (not "skip if present"); `TenantSchemaMigrationRunner` is a `SmartLifecycle` (phase 0, work in `runAll()`), loads non-DELETED tenants with an existing schema, runs M1–M6 and `syncNow` in separate try blocks; `doSync` takes `pg_advisory_xact_lock(hashtext('tenant-schema-sync:'||schema))` + `SET LOCAL lock_timeout='10s'`; the v1s-13-5 SALE_CANCELLED movement-type migration also runs in the sync for existing tenants.
- Known-bad state avoided: a Hibernate-era `stock_transfers_status_check` without IN_TRANSIT kept forever; requests/schedulers hitting a tenant before the boot sync; old tenants rejecting SALE_CANCELLED movements.
- KEEP: TenantSchema single gate with per-caller contracts; NOT VALID + WARNING fallback; no startup abort.

## Review Triage Log

### 2026-10-11 — Review pass
- verdicts: 33 findings — high 1, medium 17, low 11, false 4, maybe-false 0
- findings:
  - `[medium]` `[patch]` (intent) "column exists before any request/scheduler" not true for an ApplicationRunner — runner made a SmartLifecycle phase 0 (before web server, before @Scheduled registration on ContextRefreshedEvent).
  - `[medium]` `[patch]` (intent) ERROR-log visibility untested — TenantSchemaMigrationRunnerTest uses OutputCaptureExtension (ERROR line naming schema, ERROR summary failed=1, skipped count).
  - `[low]` `[reject]` (intent) use-site diagnosis of `column version does not exist` not addressed — root cause removed (boot sync before traffic + request sync); extra use-site handling adds branches for a now-unreachable path.
  - `[low]` `[reject]` (intent) six mechanically converted callers have no new tests — pure `isValid` substitution, their existing tests ran green in the full suite.
  - `[low]` `[patch]` (intent) existing-tenant lockout columns only checked with mocks — real-PG test drops them and asserts `runAll()` restores them.
  - `[false]` `[reject]` (intent) Hibernate-era equivalent CHECK under another name duplicated — Hibernate 6 emits unnamed inline enum checks, which PostgreSQL names `<table>_<col>_check`, exactly the names V13 targets.
  - `[false]` `[reject]` (intent) provision() atomicity (half-provisioned) not addressed — TenantFactory already drops the schema on provisioning failure (compensation), unchanged.
  - `[low]` `[patch]` (intent) epic boxes lack commit hash — added at finalize.
  - `[medium]` `[patch]` (blind) boot sync walks DELETED tenants with dropped schemas → ERROR noise — load `status <> 'DELETED'` and skip schemas absent from information_schema.schemata (WARN, skipped).
  - `[medium]` `[patch]` (blind) M1–M6 failure skips syncNow — separate try blocks, each failure ERROR + failed.
  - `[medium]` `[patch]` (blind) boot vs request sync race, unbounded lock waits — per-schema advisory xact lock + `SET LOCAL lock_timeout='10s'` in doSync.
  - `[low]` `[patch]` (blind) boot time linear in tenants, unmeasured — elapsedMs in summary; linear cost accepted at V1 scale.
  - `[false]` `[reject]` (blind) users ALTERs unguarded can fail sync — doSync step 1 creates every public table missing in the tenant (public.users exists since V1) before ensureRequiredIndexes.
  - `[medium]` `[patch]` (blind) V13 checks name only, stale value lists kept — every block now drops and re-adds; test replaces a stale stock_transfers check and verifies restore.
  - `[medium]` `[patch]` (blind) test gaps (log, M1–M6 failure, missing schema, real-PG lockout) — added in runner unit test and integration test; trivially-true verify removed.
  - `[low]` `[patch]` (blind) brittle `max(version)==13`; test schemas not dropped — asserts V13 success in history; leak rejected (per-class container).
  - `[low]` `[patch]` (blind) AdminTenantService/AdminCatalogService interpolate schema_name ungated + stale javadoc — gated with TenantSchema (skip in lists, validate on single path), javadoc fixed.
  - `[low]` `[patch]` (blind) messages echo raw schema name (12.12 D5) — now length only via TenantSchema.validate message.
  - `[low]` `[reject]` (blind) null now skipped/DomainException instead of NPE — strictly safer, no caller relies on the NPE.
  - `[low]` `[patch]` (blind) housekeeping (import order, double blank line, duplicated exception message) — fixed.
  - `[medium]` `[patch]` (verification-gap) no test asserts ERROR log — same fix as row 2.
  - `[medium]` `[patch]` (verification-gap) V13 stale constraints — same fix as row 14.
  - `[medium]` `[patch]` (verification-gap) M1–M6 failure blocks sync — same fix as row 10.
  - `[medium]` `[patch]` (edge) DELETED tenants — same fix as row 9.
  - `[medium]` `[patch]` (edge) M1–M6 shared try — same fix as row 10.
  - `[medium]` `[patch]` (edge) concurrent boot/request DDL — same fix as row 11.
  - `[medium]` `[patch]` (edge) no lock_timeout — same fix as row 11.
  - `[medium]` `[patch]` (edge) V13 stale same-name definition — same fix as row 14.
  - `[low]` `[reject]` (edge) V13 undefined_column on a Hibernate-era table lacking the column — the columns are mapped entity fields; such a DB already fails ddl-auto=validate, adding guards is unwarranted complexity.
  - `[false]` `[reject]` (edge) tenant without users table rolls back sync — refuted as row 13.
  - `[medium]` `[patch]` (edge) boot-ordering claim — same fix as row 1.
  - `[medium]` `[patch]` (edge) V13 "succeeds on Hibernate-era DB" claim — same fix as row 14.
  - `[high]` `[patch]` (orchestrator, from implementer risk) v1s-13-5 SALE_CANCELLED movement-type migration ran only at provisioning, so existing tenants (and tenants with the inherited public constraint) reject SALE_CANCELLED movements — migration rewritten as a definition-aware DO block and executed in the sync; real-PG test inserts SALE_CANCELLED before (rejected) / after (accepted).

### 2026-10-13 — Review pass (follow-up)
- verdicts: 38 findings — high 0, medium 3, low 25, false 10, maybe-false 0
- findings:
  - `[low]` `[reject]` (intent) "before any request" proven by the SmartLifecycle phase, not by a booted app — Spring's lifecycle contract (phase 0 < web server / scheduler phases); a full boot test is disproportionate.
  - `[medium]` `[patch]` (intent) request-path `syncIfNeeded` could block on the advisory lock (lock_timeout was set after it) — `SET LOCAL lock_timeout` now runs BEFORE `pg_advisory_xact_lock`, so the 10 s bound covers the advisory wait too; unit order test updated, real-PG runner tests exercise the SQL.
  - `[low]` `[reject]` (intent) V13 re-run drops and re-adds instead of a literal no-op — same end state, Flyway applies it once; drop/re-add is what fixes stale Hibernate-era value lists (Design Notes).
  - `[false]` `[reject]` (intent) tenant `stock_movements` CHECK rewritten by the sync vs "never change tenant-schema CHECK constraints" — the Never targets V13 (public only); the SALE_CANCELLED widening is the first-review high fix (cancelling a sale failed on old tenants).
  - `[false]` `[reject]` (intent) new gates where none existed — the intent routes every Java schema-name check through `TenantSchema`.
  - `[false]` `[reject]` (intent) exception messages changed — Story 12.12 D5 (length only); types/codes kept.
  - `[low]` `[patch]` (intent) gate call sites untested on invalid names — `TenantSchemaTest` (message contract), `AdminCatalogServiceTest` / `AdminTenantServiceTest` invalid-schema cases.
  - `[false]` `[reject]` (intent) foreign scope (15-9 / 18-x) — follow-up diff = baseline→current code; covered by their own tests (AuditLogEnrichment / Export / AccountDeletion ITs).
  - `[false]` `[reject]` (blind) V13 after V14–V16 breaks Flyway validation — V13 was committed (fb880b6, 2026-10-11 17:19) before V14 (15ff3ca, 2026-10-12); no database can hold V14 without V13.
  - `[low]` `[patch]` (blind) `AdminSyncMonitoringService` interpolates `schema_name` ungated — active-tenant list filtered by `TenantSchema.isValid`, `loadTenantById` answers "Tenant not found" for an invalid name.
  - `[medium]` `[patch]` (blind) advisory wait unbounded — grouped with the intent row.
  - `[low]` `[reject]` (blind) a deterministically failing sync is retried on every request — the intent keeps "ERROR + retry next request"; the wait is now bounded at 10 s.
  - `[low]` `[patch]` (blind) M1–M6 run outside the lock and without a lock bound — `SET lock_timeout = '10s'` around M1–M6, reset (with search_path) in `finally` on the pooled connection; concurrent DO blocks are idempotent (not locked further).
  - `[low]` `[reject]` (blind) boot time grows with tenant count — accepted decision (NFR10: columns before any request); each lock wait bounded, summary log with elapsedMs.
  - `[low]` `[reject]` (blind) SALE_CANCELLED migration drops the inherited constraint twice / LIKE check — idempotent and covered on real PG (`runnerWidensOldTenantMovementTypeCheck`).
  - `[false]` `[reject]` (blind) unrelated changes — same as the scope row.
  - `[low]` `[reject]` (blind) `cancelDeletion` merges 404 cases — v1s-15-9 contract, out of this story.
  - `[low]` `[reject]` (blind) `forceDelete` side effects / no audit — v1s-15-9 decisions (documented there).
  - `[low]` `[patch]` (blind) invalid tenants vanish silently from admin views — WARN logs (tenant id only, raw value never echoed) in the catalog filter and the tenant detail.
  - `[false]` `[reject]` (blind) audit/export DDL not verified on PG — `AuditLogEnrichmentPostgresIntegrationTest` / `ExportPostgresIntegrationTest` cover provision + `syncNow`.
  - `[low]` `[reject]` (blind) V13 test restores constraints without `finally` — only matters after a failure; isolated Testcontainers DB.
  - `[low]` `[reject]` (blind) lifecycle test checks only the phase — same as the boot-ordering row.
  - `[low]` `[patch]` (verification) admin catalog invalid-schema filter untested — `listProducts_invalidSchemaRow_isSkipped` (no SQL ever contains the bad name).
  - `[low]` `[patch]` (verification) admin tenant gates untested — `getTenantDetail_invalidSchema_notFound`.
  - `[low]` `[patch]` (verification) "length only" message contract not asserted — `TenantSchemaTest.messageNeverEchoesValue`.
  - `[low]` `[patch]` (verification) runner "schema missing → skip" only mocked — real-PG `runnerSkipsTenantWhoseSchemaIsMissing` (WARN, no FAILED, schema not created).
  - `[low]` `[patch]` (verification other) `getTenantDetail` IAE → 500 — invalid stored name now `DomainException(TENANT_NOT_FOUND)` (404) with a WARN.
  - `[medium]` `[patch]` (edge) advisory lock before lock_timeout — grouped with the intent row.
  - `[low]` `[reject]` (edge) boot time / probes — same as the blind row.
  - `[low]` `[patch]` (edge) M1–M6 without lock_timeout — grouped with the blind M1–M6 row.
  - `[low]` `[reject]` (edge) `ADD COLUMN IF NOT EXISTS` / `CREATE OR REPLACE TRIGGER` take ACCESS EXCLUSIVE on each first sync — brief, bounded by lock_timeout; a catalog pre-check per statement is new complexity.
  - `[false]` `[reject]` (edge) `audit_log_source_check` fails on legacy rows — the `source` column is new (v1s-18-6): legacy rows hold NULL, which a CHECK accepts.
  - `[false]` `[reject]` (edge) SALE_CANCELLED DO block on a schema without `stock_movements` — the sync creates missing tables from public before `ensureRequiredIndexes`; provisioning creates it first.
  - `[low]` `[patch]` (edge) invalid schema → 500 on detail — grouped with the verification row.
  - `[low]` `[reject]` (edge) `forceDelete` overwrites a SUSPENDED status — v1s-15-9 (also rejected in the v1s-12-12 follow-up).
  - `[low]` `[reject]` (edge) `forceDelete` leaves an expired DELETION_PENDING after a DROP failure — documented v1s-15-9 design (retried by the daily run).
  - `[low]` `[reject]` (edge claim) V13 "skip if exists" vs drop/re-add — same as the intent row.
  - `[false]` `[reject]` (edge claim) `SyncErrorLogCleanupJob` keeps the regex in SQL — deliberate, recorded in the epic tick (SQL predicate, not a Java check).

## Design Notes

- Item 3 "fail visibly": ERROR at boot per tenant + summary, not a startup abort (availability for all other tenants, NFR10 isolation of failures). The request path keeps its existing ERROR + retry-next-request behavior (no cache on failure).
- Reusing the full sync at boot (rather than copying the `version` ALTERs into the runner) keeps one migration list (`ensureRequiredIndexes`) and covers every future per-tenant column, closing the root cause (dependence on first request).
- V13 uses `NOT VALID` fallback so a legacy bad row in a public template table cannot block deploy; new writes are still checked.

## Verification

**Commands:**
- `cd keevo/backend && mvn -q test -Dtest='TenantSchema*Test,SchemaAware*Test,SchemaProvisioningHygienePostgresIntegrationTest,ConcurrencyPrimitivesPostgresIntegrationTest'` -- expected: green
- `cd keevo/backend && mvn test` -- expected: no failure beyond the known baseline (7 failures + 29 errors in 10 classes)

## Auto Run Result

Status: done

**Summary:** Tenant schema-name validation converged on `TenantSchema` (all Java callers, incl. admin services); `DDL_USERS` complete + idempotent ALTERs for existing tenants; `TenantSchemaMigrationRunner` became a phase-0 `SmartLifecycle` that syncs every live tenant (`TenantSchemaSyncService.syncNow`, advisory lock + lock_timeout) before the web server and schedulers start, with ERROR per failed tenant and an ERROR summary (no startup abort); Flyway `V13__idempotent_check_constraints.sql` drops/re-adds the 6 V1 CHECKs (NOT VALID + WARNING fallback); the v1s-13-5 SALE_CANCELLED tenant migration now also reaches existing tenants.

**Files:** TenantSchema (javadoc), SchemaAwareMultiTenantConnectionProvider, TenantSchemaSyncService (syncNow, lock, users + SALE_CANCELLED migrations), TenantSchemaProvisioner (DDL_USERS, migrate constants, gate), TenantSchemaMigrationRunner (SmartLifecycle, filters, logging), TenantSchemaAdapter, JdbcNotificationCooldownAdapter, MultiStoreStockRepositoryAdapter, DeltaStockStrategy, SalesTrendDetectionScheduler, SubscriptionExpiryService, AdminTenantService, AdminCatalogService (gate), V13 migration; tests: TenantSchemaProvisionerUsersLockoutDDLTest, SchemaAwareMultiTenantConnectionProviderTest, TenantSchemaMigrationRunnerTest, SchemaProvisioningHygienePostgresIntegrationTest (new), TenantSchemaSyncServiceTest (extended).

**Review:** 33 findings — patched 1 high, 17 medium, 7 low; rejected 4 low + 4 false (reasons in triage log); deferred 0.

**Follow-up review recommended:** true — a high was patched: the SALE_CANCELLED tenant migration and the boot-time SmartLifecycle sync change runtime startup behaviour on every existing tenant; worth a second look against a prod-like dump.

**Verification:** targeted tests (98, incl. 7 PG16 Testcontainers) green; `mvn test` 2197 tests, 7 failures + 29 errors in the 10 known baseline classes (no new failure).

**Residual risks:** startup time grows linearly with tenant count (sync before traffic); V13 scans public template tables under lock once at deploy; boot sync per instance on multi-instance deploys (serialized by the advisory lock).

### Follow-up review pass — 2026-10-13

**Summary:** fresh 4-layer review of the boot sync for every tenant and of the SALE_CANCELLED fix. The boot/request sync now sets `lock_timeout` before taking the per-schema advisory lock, so no request thread (nor boot) waits unboundedly behind another sync; M1–M6 get the same 10 s bound and always restore the pooled connection's session settings. Remaining Java interpolation of `schema_name` in `AdminSyncMonitoringService` is gated; an invalid stored name answers 404 on the tenant detail (was 500) and is logged instead of vanishing silently. New tests: `TenantSchemaTest`, admin catalog/tenant invalid-schema cases, real-PG "missing schema skipped".

**Files changed:** `TenantSchemaSyncService.java`, `TenantSchemaMigrationRunner.java`, `AdminTenantService.java`, `AdminCatalogService.java`, `AdminSyncMonitoringService.java`; tests `TenantSchemaSyncServiceTest`, new `TenantSchemaTest`, `AdminCatalogServiceTest`, `AdminTenantServiceTest`, `SchemaProvisioningHygienePostgresIntegrationTest`.

**Review findings:** 38 (0 high, 3 medium, 25 low, 10 false). Patched: 1 medium entry (advisory wait bound) + 8 low entries. Deferred: none. Rejected: see the 2026-10-13 triage entry.

**Follow-up review recommended: false** — follow-up pass, no high patched (patched: 0 high, 1 medium, 8 low).

**Verification:** targeted classes (TenantSchema, sync, runner, admin catalog/tenant/sync-monitoring, hygiene IT on Testcontainers PostgreSQL 16) 60 tests green; batches `shared` 476, `admin` 48 — 0 failure.

**Residual risks:** boot time still grows with the tenant count (bounded per lock wait); a tenant whose sync fails deterministically is retried on each request (≤10 s wait each).
