---
title: '13.4 — ModuleProvisioner: per-module Flyway migrations inside the tenant schema'
type: 'feature'
created: '2026-10-29'
status: 'done'
baseline_revision: '2f93be53f7a1e039b5ab8c97ae75acf4d4ee4e8b'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/spec-socle-modulaire.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-2-module-registry.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-3-dependency-resolver.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A module's tables must be created and evolved inside each tenant schema by versioned migrations owned by the module (MOD-FR11, MOD-NFR1/7, spec-socle §2.3). Today tenant tables come from programmatic DDL (`TenantSchemaProvisioner`) plus the runtime `public → tenant` copy (`TenantSchemaSyncService`), which also mirrors global public tables into every tenant schema (routed from 13.1).

**Approach:** A core `ModuleProvisioner` that runs a dedicated programmatic Flyway per (tenant schema, module) — history table `flyway_history_<id>`, locations `classpath:db/modules/<id>`, `baselineOnMigrate` — records `tenant_modules.provisioned_version`, and is idempotent. Existing modules are not converted here (13.9); the mechanism is proven with a test-only module. Stop the sync service from mirroring global tables into tenants.

**Decisions (autonomous run, user delegated all choices 2026-10-28):**
- **API** (published, `com.keevo.shared.module`): `ProvisioningResult provision(String tenantSchema, String moduleId)` → `{moduleId, fromVersion, toVersion, migrationsApplied}`; `boolean hasMigrations(String moduleId)`. Schema validated with `TenantSchema`; platform schema / unknown module → `IllegalArgumentException` (no raw value echoed).
- **Flyway config** exactly as spec-socle §2.3 (`schemas`/`defaultSchema` = tenant schema, `table("flyway_history_" + id with '-' → '_')`, `locations("classpath:db/modules/" + id)`, `baselineOnMigrate(true)`, `createSchemas(false)`), same `DataSource` as the app, no placeholders beyond Flyway defaults. A module with **no migrations** → no-op result (`migrationsApplied = 0`, version unchanged), no history table created.
- **`provisioned_version`**: written after a successful migrate, in the same call:
  - row exists for (tenant, module) → update `provisioned_version` only (status untouched);
  - no row and the module is currently active through the free rule (13.2) → insert a row `status ACTIVE`, `tier FREE`, `activated_at now()` — equivalent to the implicit state, so behaviour does not change;
  - no row and the module is not active → `IllegalStateException` (13.8 writes the row first, then provisions — order routed in 13.8).
  Version = the highest applied version of the module's Flyway history (`null` when no migrations).
- **Concurrency/idempotence:** Flyway's own schema-history lock serialises concurrent runs on the same (schema, module); a second run with nothing pending is a no-op (`migrationsApplied = 0`). A migration failure → exception (`ModuleProvisioningException` with schema length + module id, no SQL text in the message), `provisioned_version` unchanged.
- **Batch:** `ProvisioningReport provisionAll(String moduleId)` over every live tenant (`public.tenants.status <> 'DELETED'`), one schema at a time, a failure on one tenant is recorded and the loop continues (report lists failures by tenant code, never by schema value in logs). No endpoint here (13.8 / super-admin console 16.4).
- **Global tables (routed from 13.1):** audit every `public` table and extend `TenantSchemaSyncService.GLOBAL_ONLY_TABLES` with the ones that are global only (at least `shedlock`, `event_publication`, `event_listener_receipt`, `flyway_schema_history`, and every public table that has no tenant counterpart in `TenantSchemaProvisioner` / tenant DDL — the implementer lists each with its reason in Implementation Notes; tables that DO exist per tenant, e.g. `subscriptions`, stay mirrored). **Existing stray copies are NOT dropped** (prod data must not be altered by an update): log once at boot, at INFO, the count of stray copies per table (no schema names), and record the cleanup decision as a routed item for the user.
- **Not done here:** converting existing modules to `db/modules/<id>` (→ 13.9), removing `TenantSchemaSyncService` (→ after 13.9/13.10, routed), Flutter, endpoints.

## Boundaries & Constraints

**Always:** schema-qualified SQL with bind parameters; no change to existing tenant tables or data; all test packages green; Testcontainers Postgres for provisioning; architecture suite green (rule (a) schema validation).

**Never:** no `DROP`, no edits of applied public migrations, no production module migrations under `db/modules/` yet (test resources only), no deploy.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| First provision | test module `provtest` V1+V2, schema A | tables in A only, `flyway_history_provtest` in A, version 2, row updated | — |
| Re-provision | same | `migrationsApplied 0`, no change | — |
| New version shipped | V3 added | applies V3 only, version 3 | — |
| Isolation | provision A only | schema B has no `provtest` tables / history | — |
| No migrations | module without `db/modules/<id>` | no-op, no history table | — |
| Free module, no row | active by free rule | row inserted ACTIVE/FREE + version | — |
| Inactive module, no row | paid, no row | — | IllegalStateException |
| Broken migration | V2 invalid SQL | version stays 1 | ModuleProvisioningException |
| Batch | 3 tenants, one fails | 2 provisioned, 1 failure reported | loop continues |
| Global table | `shedlock` in public | not mirrored into a new tenant schema | — |

</frozen-after-approval>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/module/` (13.1–13.3): registry, catalogue, `TenantModuleStatePort` / `JdbcTenantModuleStateStore`.
- `shared/infrastructure/persistence/TenantSchemaSyncService.java` (`GLOBAL_ONLY_TABLES`), `TenantSchemaProvisioner.java` (tenant DDL), `TenantSchema.java`.
- Postgres IT style: `ModuleRegistryPostgresIntegrationTest`, `GlobalNotificationPostgresIntegrationTest.globalOnlyTables_neverMirroredIntoATenantSchema`.
- Test-only module: `src/test/resources/keevo/modules/provtest/module.json` is NOT allowed (13.1 forbids manifests in test resources) — build the test catalogue in code (`TestModuleCatalogs`) and put migrations under `src/test/resources/db/modules/provtest/`.

## Tasks & Acceptance

**Execution:**
- [x] `ModuleProvisioner` port + records + Flyway-based service + `provisioned_version` write (port method on the 13.2 state store).
- [x] Postgres ITs for every matrix row (two tenant schemas).
- [x] `GLOBAL_ONLY_TABLES` audit + extension + boot INFO count of stray copies; extend the explicit list in `GlobalNotificationPostgresIntegrationTest`.
- [x] Tick Story 13.4 routed item; route the stray-copies cleanup decision and the `TenantSchemaSyncService` removal.

**Acceptance Criteria:**
- Given a module with migrations under `db/modules/<id>/`, when it is provisioned for a tenant schema, then its tables are created there with an isolated `flyway_history_<id>` and `provisioned_version` is updated; re-provisioning is a no-op.
- Given all test packages run one at a time, then all pass.

## Implementation Notes

**Progress — task 1 done (2026-10-29).** Published in `shared.module`: `ModuleProvisioner` (`provision`, `hasMigrations`, `provisionAll`), records `ProvisioningResult(moduleId, fromVersion, toVersion, migrationsApplied)` and `ProvisioningReport(moduleId, provisioned, skipped, failures[Failure(tenantCode, reason)])`, `ModuleProvisioningException` (module id + schema length, Flyway failure as cause). Ports `application.port.ModuleMigrationPort` (Flyway adapter `adapter.out.persistence.FlywayModuleMigrations`) and `application.port.TenantModuleProvisioningPort` implemented by the 13.2 `JdbcTenantModuleStateStore` (`updateProvisionedVersion`, `insertActiveFree` = INSERT…SELECT from live tenant ON CONFLICT set version, `liveTenants`). A separate port (not new methods on `TenantModuleStatePort`) because tests use that one as a lambda. Service `application.ModuleProvisionerService`: schema validated (platform `public` fails the format → IAE), unknown/null module → IAE without raw value, no migrations → no-op (no DB access), unknown/DELETED tenant → ISE, no row + not active per `ModuleRegistry.isActive` → ISE **before** migrating (no tables created for a non-entitled module), then migrate, then update row / insert ACTIVE/FREE (never a row for `core`), then `registry.evict`. Batch: ISE → `skipped`, other failures → `failures` (our messages only; logs name tenant code + exception class). Decisions beyond the spec: (1) **`baselineVersion("0")`** — tenant schemas are never empty, so Flyway's default baseline 1 would silently skip every module's V1 (same choice as the public Flyway); the baseline row is excluded from the version. (2) A history table name > 63 chars → IAE (manifest ids may be 50 chars). (3) A row in any status (even DISABLED) gets provisioned (the row decides; tables are kept for life). (4) `skipped` list added to the report so a paid-module batch does not report non-entitled tenants as failures.

**Progress — task 2 done (2026-10-29).** Test resources `src/test/resources/db/modules/{provtest V1+V2, provbroken V1, provpaid V1, provbatch V1}` and `db/modules-next/{provtest V3, provbroken V2 invalid}` (second root passed through the `FlywayModuleMigrations(DataSource, roots)` seam = "new version shipped"). `TestModuleCatalogs.provisioningCatalog()` (+`provempty` without migrations). `ModuleProvisionerPostgresIntegrationTest` (fresh DB, public Flyway, 3 non-empty tenant schemas + 1 DELETED tenant) 15 tests: first provision (V1+V2 in A only, `flyway_history_provtest` in A with baseline 0 + 1 + 2, row ACTIVE/FREE v2, existing data untouched), existing row (status/tier untouched), re-provision no-op, V3 only, isolation (B untouched), no migrations, inactive → ISE with nothing created, paid with row, DISABLED row, broken V2 (exception without schema/SQL, version stays 1, script rolled back), batch 3 tenants/1 failure, batch paid skip, concurrent same (schema,module) — one baseline row, migrations once (run 3× green), invalid input, unknown/DELETED tenant. All green.

**Progress — task 3 done (2026-10-29). Global-table audit** (45 public tables in keevo_dev; tenant DDL = `TenantSchemaProvisioner` + `REQUIRED_TENANT_TABLES_DDL`). Added to `GLOBAL_ONLY_TABLES`, each with no tenant DDL and every access schema-qualified `public.*` (grep of main sources: no unqualified SQL, JPA `schema = "public"`):
- `shedlock` — ShedLock provider `withTableName("public.shedlock")` (V11);
- `flyway_schema_history` — public Flyway history (`spring.flyway.schemas=public`);
- `event_publication`, `event_listener_receipt` — Spring Modulith outbox / delivery receipts, `public.*` (V28, `EventListenerReceipts`, `EventPublicationMaintenance`);
- `refresh_tokens`, `password_reset_tokens` — `@Table(schema = "public")`, native SQL `public.*`;
- `user_tenant_memberships` — `@Table(schema = "public")`, JDBC `public.*`;
- `user_sync_state` — `UserSyncStateRepositoryAdapter`/admin/metrics all `public.user_sync_state` (tenant_id = schema name);
- `feedback` — `FeedbackJpaEntity @Table(schema = "public")`, super-admin inbox.
Kept mirrored/provisioned (they exist per tenant): `users`, `subscriptions`, `stores`, `categories`, `products`, `sales`, `sale_items`, `tenant_preferences`, `audit_log`, `clients`, `suppliers`, stock/sync/inventory/report tables, etc. (all have tenant DDL). Dev evidence before the change: each of the 9 tables had a stray copy in 331 of 333 tenant schemas. Copies are **not dropped**: `shared.infrastructure.persistence.GlobalTableStrayCopiesReporter` (`@EventListener(ApplicationReadyEvent)`) logs one INFO line `table=count, …` (no schema names; WARN with exception class only on failure). `GlobalNotificationPostgresIntegrationTest`: explicit list extended + tenant tables still present (`users`, `subscriptions`, `stores`, `products`, `sales`, `audit_log`) + new `strayCopiesOfGlobalTables_areCountedAndNeverDropped`; 14 tests green.

**Progress — task 4 done (2026-10-29).** `epics-refonte-modulaire.md`: Story 13.4 routed item (13-1 review, global tables) ticked with evidence; routed: 13.8 (call `provision` after writing the row, error mapping, Flyway INFO logs name the schema, `provisionAll` for 16.4), 13.9 (baseline 0 ⇒ converted modules' V1 must be idempotent on tenants that already have the tables; remove their DDL from `TenantSchemaProvisioner`), 13.10 (remove `TenantSchemaSyncService`; **user decision** on stray copies cleanup).

**Verification (2026-10-29).** 14 packages one at a time, all rc=0, 0 failures/errors: shared (incl. 15 new provisioner ITs + reporter test), architecture 28, admin, catalog 247, crm 114, inventory 376, commerce 251, sync 525, identity 437, messaging 163, reporting 389, store 60, subscription 70, feedback 52 (shared/admin counts not reliably isolated: reports dir accumulated). Live: `mvn spring-boot:run` dev on keevo_dev → started in 47.6 s, `/actuator/health` 200, 0 ERROR lines, INFO `Global-only tables: stray copies in tenant schemas (kept, …) — event_listener_receipt=331, event_publication=331, feedback=331, flyway_schema_history=331, password_reset_tokens=331, refresh_tokens=331, shedlock=331, user_sync_state=331, user_tenant_memberships=331`; tenant table count 12,590 before and after, no "created missing table"/"added column" in the boot log, no `flyway_history_*` table anywhere (no production module migrations). Stopped with `fuser -k 4500/tcp`. Nothing committed.

**Review fixes (2026-10-29, coordinator items 1–8):**
- *Item 1 done — dedicated connections.* `FlywayModuleMigrations.dedicatedDataSource()`: a Hikari pool (direct or wrapped, `unwrap`) → new non-pooled `DriverManagerDataSource(jdbcUrl, username, password)` per migrate (physical connections closed by Flyway); an already driver-based non-pooled DataSource is used as is; `SingleConnectionDataSource` (SmartDataSource) and any other type → `IllegalStateException`. IT `pooledConnectionSearchPathUnchanged` (Hikari pool of 1, provision + a failing migrate, `SHOW search_path` unchanged, still 1 connection); unit test `dedicatedDataSource`.
- *Item 2 done — provisioned_version.* (a) `ModuleProvisioningException.highestAppliedVersion()` (Flyway `info()` re-read after the failure); the service records it, then rethrows — IT `partialFailureRecordsReachedVersion` (V1+V2 commit, V3 fails → row v2). (b) UPDATE and the insert's `ON CONFLICT DO UPDATE` only write when stored is NULL or `string_to_array(stored,'.')::numeric[] <= string_to_array(new,'.')::numeric[]` (equal = idempotent rewrite, so re-provision is not a 0-row write). (c) 0 rows written → WARN (module id + version, no schema). A null version (no versioned script applied) writes nothing — never a row without a version. IT `monotonicVersion` (stored 5 kept + WARN captured; 10 > 9, 2.10 > 2.9, lower → 0 rows, insert path monotonic).
- *Item 3 done — discovery.* `validateMigrationNaming(true)`; `hasMigrations` counts only `V<ver>__*.sql` / `R__*.sql` (`FLYWAY_SCRIPT`), recursively under the same location on every classpath entry (what Flyway's `classpath:` scans). Documented behaviour: a folder with only misnamed files = no migrations (no-op, no row, no history); a misnamed file beside valid ones → `ModuleProvisioningException`, nothing applied, no row. IT `misnamedScripts` (`provmisnamed`, `provmixed`); unit test `namingFilter`.
- *Item 4 done — id hygiene.* `FlywayModuleMigrations.validateModuleId` (manifest `ID_PATTERN` + `ID_MAX`, message length only) before any interpolation in `hasMigrations`, `migrate`, `historyTable`. `FlywayModuleMigrationsTest`: `a-b` → `flyway_history_a_b`, 48-char id → 63-char name OK, 49 → IAE, invalid ids (`A`, `a_b`, `../etc`, `a--b`, 51 chars…) refused without echo.
- *Item 5 done — batch classification.* `ModuleNotActiveException` and `TenantNotFoundException` (both extend `IllegalStateException`, contract kept) are the only skips (DEBUG with tenant code + reason); everything else is a failure. `LiveTenant.schemaPresent` (`EXISTS pg_namespace`) → tenants without a schema yet are skipped before provisioning. IT: live tenant `KV-PRVE05` without schema skipped in both batch tests; unknown/DELETED tenant → `TenantNotFoundException`, paid without row → `ModuleNotActiveException`.
- *Item 6 done — exception hygiene.* `ModuleProvisioningException(moduleId, schema, failure, highestAppliedVersion)`: **no cause**; message = module id + schema length + `failure` (Flyway error code and/or SQL state, else exception class). `DataAccessException` from the row write → `ModuleProvisioningException("ROW_WRITE/<class>")`. IT `brokenMigration` (no cause, failure contains `42601`, no SQL/schema) and `rowWriteFailureWrapped`.
- *Item 7 done — stray-copies reporter.* `pg_class ⨝ pg_namespace` (`relkind = 'r'`, `nspname <> 'public'`), tenant schemas filtered in Java with `TenantSchema.isValid` (no duplicated regex); per table `StrayCopies(copies, nonEmpty = reltuples > 0, notAnalysed = reltuples < 0)`; JDBC query timeout 10 s; `ApplicationReadyEvent` → `startReport()` on a daemon thread (never delays readiness, never throws). `GlobalNotificationPostgresIntegrationTest.strayCopiesOfGlobalTables_areCountedAndNeverDropped`: +2 copies, +1 non-empty after ANALYZE, a non-tenant schema not counted, copy kept after sync, background report completes.
- *Item 8 done — docs.* `RefreshTokenRepository` Javadoc (global `public.refresh_tokens`, never mirrored); spec-socle-modulaire.md §2.3 note on `baselineVersion("0")` and its 13.9 consequence.
- Targeted run: `ModuleProvisionerPostgresIntegrationTest` 20 + `FlywayModuleMigrationsTest` 6 green.
- Verification after the fixes (clean `target/surefire-reports` per package): `com.keevo.shared.**` 772 / `com.keevo.architecture.**` 28 / `com.keevo.admin.**` 505 — all rc=0, 0 failures/errors/skipped. Routed items (13.9, 16.4, 16.5, 16.7) left to the coordinator as instructed. Nothing committed.

**Final verification (orchestrator, 2026-10-29, after review patches).** Before patches: 14 packages green. After (changes confined to `shared` + one identity Javadoc): `shared` 772, `architecture` 28, `admin` 505 green (clean surefire reports). Live dev boot on keevo_dev: started 50.4 s, health 200, 0 ERROR; background report `event_listener_receipt=331/0/331, event_publication=331/0/331, feedback=331/0/331, flyway_schema_history=331/0/331, password_reset_tokens=331/0/331, refresh_tokens=331/0/331, shedlock=331/0/331, user_sync_state=331/0/331, user_tenant_memberships=331/0/331` (copies / non-empty estimate / never analysed) — nothing dropped.

## Spec Change Log

## Review Triage Log

### 2026-10-29 — Review pass (13-4)
- verdicts: 3 layers (Blind Hunter 15, Edge+Gap 12, Intent 14; overlap) — high 1, medium 6, low 9 after dedup
- findings:
  - `[high]` `[patch]` programmatic Flyway on a pooled connection could return it to the pool with a tenant `search_path` (stray copies then read silently) — dedicated unpooled DataSource per migrate; IT on a 1-connection pool
  - `[medium]` `[patch]` `provisioned_version` stale after a partial failure / regressed by a concurrent older node / compared as text — highest applied recorded, monotonic numeric conditional write, WARN on 0 rows
  - `[medium]` `[patch]` misnamed scripts silently ignored → ACTIVE row without tables — `validateMigrationNaming(true)`, Flyway-named discovery only
  - `[medium]` `[patch]` batch counted any IllegalStateException as skipped; tenants without schema — dedicated exceptions, `pg_namespace` check
  - `[medium]` `[patch]` FlywayException cause / DataAccessException carried SQL + schema — no cause, error code only, row write wrapped
  - `[medium]` `[patch]` stray-copies reporter on `information_schema` (slow, blocks readiness) — `pg_class`, async, timeout, non-empty estimate
  - `[medium]` `[patch]` re-homed: `TenantSchemaSyncService` removal → 16-5 (no story converted the remaining modules), stray-copies cleanup decision → 16-7 (asked to the user)
  - `[low]` `[patch]` module id validated in the adapter (hyphen, 63-char history name); `RefreshTokenRepository` Javadoc; spec-socle §2.3 `baselineVersion 0` note
  - `[low]` `[reject]` the 9 new global-only tables — every access verified `public.`-qualified (JPA schema, ShedLock table name, Modulith schema)
  - `[low]` `[reject]` provisioning DISABLED rows (spec rule; tables kept current for reactivation), check-before-migrate order, `skipped` list
  - `[low]` `[defer]` id cap 48 at manifest load, core version tracking, validateOnMigrate/outOfOrder, stop mirroring converted modules → routed → 13-9
  - `[low]` `[defer]` batch performance → routed → 16-4; materialised FREE rows → routed → 16-5

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -Dtest='com.keevo.shared.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: green
- `... -Dtest='com.keevo.architecture.**'` / `com.keevo.admin.**` -- expected: green
- 14-package sequential loop -- expected: green
- Live: dev backend boots on keevo_dev with the extended global list; INFO line with stray-copy counts; existing tenants unchanged.
