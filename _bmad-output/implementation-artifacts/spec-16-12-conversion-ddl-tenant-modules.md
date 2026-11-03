---
title: '16-12 Tenant DDL owned by module migrations — one author for every tenant table, public→tenant copy removed'
type: 'refactor'
created: '2026-11-03'
status: 'in-progress'
baseline_revision: '4a7ed684ed4aa656602a8d5d392530b23f61368f'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Tenant tables have three authors: the programmatic DDL of `TenantSchemaProvisioner` (29 tables, 1312 lines), the runtime `public → tenant` copy of `TenantSchemaSyncService` (`CREATE TABLE … (LIKE public.t INCLUDING ALL)` + column/index top-ups, run at boot and on the first request per JVM), and module Flyway migrations (`db/modules/<id>`, only crm — baselined at 1, never executed on existing tenants — and invoicing). The copy produced 157/331 « drifted » dev schemas (no FKs, missing indexes/defaults) and mirrors global tables that were never meant for tenants (`plan_profiles`, `profile_modules`, `bundle_definitions`, `bundle_modules`, `admin_jobs` are missing from `GLOBAL_ONLY_TABLES`). The fusion (epics 19–21) adds many tenant tables: they must come from module migrations only.

**Approach:** Every tenant table moves into the V1 migration of the free module that owns it (`db/modules/<id>/V1__<id>_baseline.sql`), written **idempotent forever**. These SQL files become the single source of tenant DDL: at registration `TenantSchemaProvisioner` executes them inside the registration transaction (in module order), then the after-commit `provisionActiveModules` runs Flyway, which re-applies them harmlessly and records history. Existing tenants are brought to the same shape by the same scripts (they only add what is missing; no data change). `TenantSchemaSyncService` and its per-request call are removed; `TenantSchemaMigrationRunner` becomes a startup job that provisions only tenants whose module versions are behind.

## Boundaries & Constraints

**Always:**
- Table → module (V1 owner): **core** = users, subscriptions, roles, user_roles, tenant_preferences, audit_log (with its enrichment columns, CHECK, indexes, immutable function, triggers, REVOKE), employees ; **store** = stores ; **catalog** = categories, products ; **inventory** = stock_levels, stock_movements, stock_transfers, inventory_sessions, inventory_counts (counting stays the paid licence; its tables keep existing for every tenant as today) ; **crm** = clients, suppliers, product_suppliers (existing V1 kept unchanged, see crm below) ; **pos** = sales, sale_items, day_closures ; **sync** = sync_operations_log, sync_conflicts_log, sync_error_log ; **reporting** = reports, export_jobs (exports stays the paid licence) ; **messaging** = draft_notifications, device_tokens, notification_cooldowns. The 29 tables, columns, types, defaults, CHECKs, indexes and FKs are exactly those a fresh tenant gets today (proved by the shape test below).
- Module order (registration and provisioning): `core, store, catalog, inventory, crm, pos, sync, reporting, messaging`, then the paid modules (invoicing…) as today. Derived, not hard-coded twice: topological on `requires`, ties broken so that a module listed in another's `enhances` comes first (crm before pos), stable by manifest id; a unit test pins the resulting order.
- Idempotent scripts: `CREATE TABLE IF NOT EXISTS`, `ADD COLUMN IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`, guarded `DO $$ … $$` blocks for constraints/CHECK changes/triggers; defaults added with `ALTER COLUMN … SET DEFAULT` (metadata only); `SET LOCAL lock_timeout = '10s'` at the top. Same policy as crm V1: **a foreign key is created only together with its table**, never added to an existing table (drifted tenants keep their missing FKs — decision recorded, verified again on the prod copy dry-run of 16.7). Never DROP, UPDATE, DELETE, never rewrite data. Every legacy incremental fix of the provisioner/runner (users lockout columns, stores address/phone/type, products transport_cost/minimum_threshold/track_stock + guarded unique name, tenant_preferences report/trend/leaderboard columns, stock_levels min_threshold/version, stock_movements SALE_CANCELLED type CHECK, stock_transfers IN_TRANSIT/updated_at/version, sales client_id/status/occurred_at/CHECK v2/discount/updated_at/version/mobile_money_ref + history indexes, sale_items catalogue_price/updated_at, day_closures received_at + backfill, reports columns + updated_at backfill, export_jobs criteria, sync_operations_log device_id, runner M1–M6) is folded into these V1 scripts as guarded idempotent statements so an old tenant converges.
- Backfills that exist today (`day_closures.received_at`, `reports.updated_at`) stay in the scripts only as `UPDATE … WHERE col IS NULL` of a newly added column, exactly as today; no other UPDATE.
- Cross-module FKs: `sales.client_id → clients(id)` is created in pos V1 with the `sales` table only when `clients` exists (it always does, crm runs first); `product_suppliers.product_id → products` stays in crm V1; `inventory_sessions.store_id → stores`, `stock_*.product_id → products` in inventory V1 (store/catalog run first). No other new FK.
- Registration (`TenantFactory` → `TenantSchemaProvisioner.provision`): `CREATE SCHEMA`, then execute the foundation scripts (classpath `db/modules/<id>/V*.sql` of the 9 modules, version order) on the registration connection, then the seeds (roles, subscription from `public.plan_profiles`, « Ma Boutique ») — seeds stay in Java, registration-only. One transaction: any failure drops the schema as today. The after-commit `provisionActiveModules` runs Flyway for all active modules (foundation re-applied idempotently, history recorded, `tenant_modules.provisioned_version` written as today). The Java DDL constants are deleted (only seeds and `dropSchema*` stay).
- crm: `CrmModule.existingSchemaBaseline()` is removed (V1 is idempotent and now runs on existing tenants: it adds missing indexes/defaults, never FKs); the « no lazy adoption » skip in `ModuleProvisionerService.ensureProvisioned` for baselined modules is removed with it. `ExistingSchemaBaseline` stays as a mechanism (unused).
- Existing tenants: `TenantSchemaMigrationRunner` (keep the `SmartLifecycle` phase-0 slot, failures counted, never abort startup) becomes: one set-based query lists tenants (status ≠ DELETED, schema present) whose `tenant_modules.provisioned_version` is missing or older than the latest version of any foundation module; only those are provisioned, with bounded parallelism (configurable, default 4) and one tenant failure isolated. An up-to-date dev DB starts without any Flyway run. Log one summary line (tenants checked, provisioned, failed, duration).
- `JwtAuthFilter`: the `syncIfNeeded` call is replaced by `moduleProvisioner.ensureFoundation(schema)` — cached per JVM per schema+version, no-op when up to date (no query on the hot path once cached), provisions synchronously when behind (a tenant created before the deploy and reached before the runner finished).
- `TenantSchemaSyncService` is deleted, with its `GLOBAL_ONLY_TABLES` and tests; global tables are never copied into tenants again. Stray copies already created stay (no DROP); `GlobalTableStrayCopiesReporter` keeps reporting them.
- `SyncKeysetIndexJob` and `SYNC_KEYSET_INDEXES` are kept (they own the keyset indexes, best effort and nightly); the list moves to a home that no longer depends on the deleted DDL constants.
- FUS-NFR11: set-based detection of tenants to upgrade (one query, no per-tenant probe loop), no per-request DB work once cached, scripts executed once per tenant per version.
- Tests (packages one at a time): (1) **shape test** — before deleting the Java DDL, capture the full shape of a fresh tenant created by the current code (`information_schema.columns` incl. defaults/nullability/types, `pg_indexes`, `pg_constraint` defs, triggers, function) into a committed test resource; after the change a fresh tenant must match it exactly (ignoring Flyway history tables); (2) idempotency — run every foundation script twice on a fresh tenant and once on that resource-shaped tenant: no error, no diff; (3) drifted tenant (built with `LIKE` copies without FKs/indexes/defaults) → provisioning adds indexes/defaults, adds no FK, row counts unchanged; (4) a tenant with data from the old provisioner → runner provisions it, every table's row count and a checksum of rows unchanged; (5) registration failure after the scripts drops the schema; (6) runner skips up-to-date tenants (no Flyway call) and isolates a failing tenant; (7) module order test; (8) `ensureFoundation` cached no-op. Existing Postgres tests that build tenants through `TenantSchemaProvisioner` stay green unchanged.
- Live check on `keevo_dev` (344 tenants, many drifted): before/after row counts per tenant table (set-based query over all schemas) identical; every tenant has the 9 foundation history tables at the latest version; boot duration of the first and of a second start recorded in the spec; register a new owner through the API and run a sale + sync pull with the Flutter test client on the Redmi (« En ligne », fresh `last_pull_at`).

**Never:** no change to public Flyway (`db/migration`); no data modification beyond the two existing null backfills; no FK added to an existing table; no DROP of any table, column, stray copy or schema (except the existing registration-failure compensation); no change to API responses (no mobile/dashboard section); no change to `module.json` `requires`; no edit of applied scripts (crm V1, invoicing V1–V7).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| New tenant | registration | schema identical to the captured shape; 9 foundation + active paid histories after commit; seeds present | failure → schema dropped, registration fails as today |
| Up-to-date tenant at boot | versions current | not provisioned, no Flyway call | — |
| Old tenant (current shape) | no foundation history | scripts run, nothing changes structurally, histories recorded | per-tenant failure logged, others continue |
| Drifted tenant | LIKE copy, no FKs/indexes/defaults | missing indexes/defaults/columns added, FKs not added, data untouched | — |
| Tenant missing an old column (e.g. pre-lockout users) | old schema | column added with today's default | — |
| crm on existing tenant | baselined at 1 earlier | history kept; nothing re-run; on never-baselined tenants V1 runs idempotently | — |
| Request on a behind tenant before runner finished | first request | `ensureFoundation` provisions synchronously, then the request proceeds | provisioning failure → 503 French message, retried next request |
| Lock contention | table locked by app | `lock_timeout` 10s → tenant fails this run, retried next start/request | WARN |
| Script edited after release | checksum mismatch | provisioning fails loudly for that module (validateOnMigrate) | ERROR, other modules continue |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java` (1312 l.) -- DDL constants :34-1007 (table list and owners in the intent), `createTables` :1111-1262 (order; clients before sales :1170), `guardedUniqueIndex` :859-870, keyset `SyncKeysetIndex`/`SYNC_KEYSET_INDEXES` :687-750 + `createSyncKeysetIndexes` :1272-1293, seeds `seedData` :1295-1304 (`SEED_ROLES` :616, `SEED_SUBSCRIPTION`/`_DEFAULTS`/`PLAN_PROFILES_PRESENT` :999-1007, `SEED_STORE` :1016), `provision` :1042, `dropSchemaIfExists` :1066, `dropSchemaForDeletion` :1090. Not executed by `createTables`: `DDL_DAY_CLOSURES_MIGRATE_RECEIVED_AT`+BACKFILL, `DDL_REPORTS_BACKFILL_UPDATED_AT` (sync only) — fold them in.
- `.../shared/infrastructure/persistence/TenantSchemaSyncService.java` (530 l.) -- delete: `doSync` :198-252, `GLOBAL_ONLY_TABLES` :64-87 (misses plan_profiles, profile_modules, bundle_*, admin_jobs), `REQUIRED_TENANT_TABLES_DDL` :101-124, `ensureRequiredIndexes` :282-371 (≈60 idempotent constants → into V1 scripts), stale Javadoc :12-51.
- `.../shared/infrastructure/persistence/TenantSchemaMigrationRunner.java` (229 l.) -- `SmartLifecycle` phase 0, `LOAD_TENANTS_SQL` :42-45, `runAll` :83-125, `migrateSchema` M1–M6 :152 (fold into scripts), calls `syncNow`.
- `.../shared/infrastructure/security/JwtAuthFilter.java:257` -- `tenantSchemaSyncService.syncIfNeeded(tenantId)` → `ensureFoundation`.
- `.../identity/auth/application/service/TenantFactory.java:97-120` -- provision in registration tx, compensation :113, after-commit `provisionActiveModules` :120; stale comment :97-98.
- `.../shared/module/ModuleProvisioner.java` (port, add `ensureFoundation`), `.../shared/module/application/ModuleProvisionerService.java` -- `provision` :104, `recordVersion` :142, `ensureProvisioned` :174 (baseline skip :182-185 to remove), `provisionAll` :223, `provisionActiveModules` :300 (dependency order: reuse for the derived order).
- `.../shared/module/adapter/out/persistence/FlywayModuleMigrations.java` -- root `db/modules` :69, history `flyway_history_<id>` :70/:109, `migrate` :217-245 (own DriverManagerDataSource → cannot see the uncommitted registration schema: hence in-transaction script execution at registration), `baselineVersion` :252-265, `hasMigrations`, `latestVersion`.
- `.../crm/module/CrmModule.java:47` -- `EXISTING_SCHEMA` baseline to remove; `.../shared/module/ExistingSchemaBaseline.java`, `KeevoModule.existingSchemaBaseline()` :42-46.
- `keevo/backend/src/main/resources/db/modules/crm/V1__crm_baseline.sql` -- template for policy/comments (do not edit). `db/modules/invoicing/V1–V7` untouched. New: `db/modules/{core,store,catalog,inventory,pos,sync,reporting,messaging}/V1__<id>_baseline.sql`.
- `keevo/backend/src/main/resources/keevo/modules/<id>/module.json` -- `requires`/`enhances` (read for order, unchanged).
- `.../shared/infrastructure/persistence/SyncKeysetIndexJob.java` (:121-129 ApplicationReady + cron, :210 uses provisioner constants, :247 missing-table skip), `GlobalTableStrayCopiesReporter.java`.
- Tests to rewrite/remove: `TenantSchemaProvisioner{Contact,Product,SaleVersion,SaleCancelled,Stores,UsersLockout}DDLTest`, `TenantSchemaSyncServiceTest`, `TenantSchemaMigrationRunnerTest`, `JwtAuthFilterTest` (sync caller), `TenantFactoryTest`; keep green: `TenantMigrationIntegrationTest` (fix stale « 7 tables » comment), `SchemaProvisioningHygienePostgresIntegrationTest`, `CrmMigrationPostgresIntegrationTest` (baseline tests change meaning), `InvoicingMigrationPostgresIntegrationTest`, `ModuleProvisionerPostgresIntegrationTest`, `SyncKeysetIndexJobPostgresIntegrationTest`, `SyncKeysetIndexesMatchProvidersTest`, `ModuleCatalogBootIntegrationTest:51`, `TenantSchemaInterpolationGuardTest` + `src/test/resources/tenant-schema-interpolation-allowlist.txt`, every `*PostgresIntegrationTest` that creates tenants. Infra: `SharedPostgresContainer`, `PostgresIntegrationTest`.
- Docs: `docs/DEPLOYMENT_CHECKLIST.md` (mentions these classes).
- Sprint: `_bmad-output/implementation-artifacts/sprint-status.yaml:326`; brief + 4 deferred items `_bmad-output/planning-artifacts/epics-refonte-modulaire.md:1789-1811` (tick them with the outcome).

## Tasks & Acceptance

**Execution:**
1. Shape capture test resource from the current code (before any deletion), committed under `src/test/resources/tenant-shape/fresh-tenant-shape.json` (or `.sql`), with the generator test.
2. `db/modules/{core,store,catalog,inventory,pos,sync,reporting,messaging}/V1__*_baseline.sql` -- idempotent DDL per the intent, header comment like crm V1.
3. `ModuleProvisioner` + `ModuleProvisionerService` -- derived module order, `ensureFoundation`, remove baseline skip; `FlywayModuleMigrations` -- expose ordered script resources for in-transaction execution.
4. `TenantSchemaProvisioner` -- execute foundation scripts on the registration connection, keep seeds and drop methods, delete DDL constants; move `SyncKeysetIndex` list out.
5. `TenantSchemaMigrationRunner` -- set-based behind-tenant detection + bounded parallel provisioning; delete `TenantSchemaSyncService`; `JwtAuthFilter` → `ensureFoundation`; `CrmModule` baseline removal; `TenantFactory` comment.
6. Tests per the intent; update/remove obsolete unit tests; docs/DEPLOYMENT_CHECKLIST.md.
7. Live check on keevo_dev + Redmi; record measures in Design Notes; tick the 4 deferred items in the epics brief; sprint-status.

**Acceptance Criteria:**
- Given a fresh tenant, when it is registered, then its schema equals the captured shape and every foundation module has a Flyway history at its latest version.
- Given keevo_dev with 344 tenants (drifted and current), when the backend starts, then every tenant reaches the latest foundation versions, per-table row counts are unchanged, no FK was added to an existing table, and a second start provisions nothing.
- Given the codebase, when grepped, then no tenant `CREATE TABLE` remains in Java and `TenantSchemaSyncService` no longer exists.

## Spec Change Log

## Review Triage Log

## Design Notes

- Why scripts executed in-transaction at registration instead of Flyway: Flyway uses its own connection and cannot see the uncommitted `CREATE SCHEMA`; making registration non-atomic (commit the schema then provision) would leave half-built tenants on failure. Executing the same files in the transaction keeps one author (the SQL files) and atomicity; Flyway then records the history after commit. This is why foundation scripts must stay idempotent forever (enforced by the idempotency test; stated in each header).
- Why paid-feature tables stay in a free module: sync providers and screens read `inventory_sessions`/`inventory_counts`/`export_jobs` for every tenant today; moving them to counting/exports would make the tables appear only on licence activation and break pulls for others.
- Patterns: Template-free SQL migration per module (single source), Strategy for module ordering, cache-aside for `ensureFoundation`. Complexity: one query to find behind tenants (join `tenant_modules` against the latest versions), O(tenants behind × modules) Flyway runs once per version, O(1) per request after warm-up.
