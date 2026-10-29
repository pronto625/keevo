---
title: '13.1 — Module registry data model + module.json manifests + boot seed'
type: 'feature'
created: '2026-10-29'
status: 'done'
baseline_revision: '3545fd208e80055b286ef7f8b9e5e31abf4107a9'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/spec-socle-modulaire.md'
  - '{project-root}/plan-refonte-modulaire.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-12-6b-archunit-verify-bloquant.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The platform has 13 Spring Modulith modules but no runtime notion of "installed module": nothing records which modules exist, their version, category, hard/soft dependencies, nor (per tenant) their state. Epic 13 (activate/deactivate per tenant) needs this catalogue first (MOD-FR1).

**Approach:** Add the `public.module_definitions` / `public.tenant_modules` tables (spec-socle §2.1/§2.2), one `module.json` manifest per functional module (spec-socle §3, plan §5), a validated `ModuleManifest` model, and an idempotent boot seed that fails fast on an invalid manifest set. Enable the `requires`-graph acyclicity test prepared in 12.6b.

**Decisions (autonomous run, user delegated all choices 2026-10-28):**
- Code lives in the core = `com.keevo.shared.module` (the spec's `com.keevo.core.module`; `shared` is the core until a later rename). Model/ports in a published API sub-package so 13.2+ (ModuleRegistry, DependencyResolver) can build on it; JPA/JDBC adapter under `..adapter.out.persistence..` (12.6b rule b).
- Manifest location: `src/main/resources/modules/<id>/module.json` (single jar — one root `module.json` is impossible), loaded with `classpath*:modules/*/module.json`; the directory name must equal the manifest `id`.
- Functional modules and manifests (ids, category, `requires` from plan §5, keeping only ids that exist; `enhances` likewise; all `free: true` — every current feature is in V1's free offer, premium modules come with Epic 14+; `version` `1.0.0`):
  - `core` (category `core`; covers shared, identity, subscription, feedback) — requires `[]`
  - `catalog` (business) — requires `[core]`, enhances `[reporting]`
  - `crm` (business) — requires `[core]`, enhances `[pos]`
  - `inventory` (business; stock, transfers, counting — the plan's split into `transfers`/`counting` is NOT done here) — requires `[core, catalog]`, enhances `[reporting]`
  - `store` (business) — requires `[core]`
  - `pos` (business; Modulith module `commerce`) — requires `[core, catalog, inventory]`, enhances `[crm, reporting]`
  - `reporting` (business) — requires `[core]`, enhances `[pos, inventory, catalog]`
  - `messaging` (business) — requires `[core]`, enhances `[reporting, pos, inventory]`
  - `sync` (category `core`) — requires `[core]`
  - `admin` (category `platform`) — requires `[core]`
  Each manifest also carries `displayName` (French), `entitlement` (`module.<id>`), and empty arrays for `publishes/consumes/provides/needs/permissions/routes` + `extensionPoints {declares:[], fills:[]}` unless obvious from code (not required here).
- Manifest validation (fail-fast at boot, clear message naming file + field): malformed JSON; missing/blank required fields (`id`, `version`, `displayName`, `category`); `id` must match `^[a-z][a-z0-9-]{1,49}$` and its directory; `version` semver `MAJOR.MINOR.PATCH`; `category` ∈ `core|business|premium|platform`; duplicate ids; `requires` entry unknown → fail; cycle in `requires` → fail (message lists the cycle); exactly one module with id `core`, and every non-core module requires `core`. Unknown `enhances` ids are allowed (soft deps may target uninstalled modules) → WARN log only. `requires`/`enhances` must be JSON arrays of strings.
- Seed: an idempotent runner at startup (after Flyway, before the web server accepts traffic) upserts each manifest into `module_definitions` (insert if absent; update version/display_name/category/requires/enhances/is_free/manifest if changed, keep `installed_at`); a second boot with identical manifests = no row change (`updated_at`-style column not required; log "unchanged"). Rows for modules no longer on the classpath are kept (never deleted) and logged at WARN.
- `tenant_modules` is created (columns, `UNIQUE(tenant_id, module_id)`, CHECK on `status` ∈ `AVAILABLE|TRIAL|ACTIVE|EXPIRED|DISABLED` and `tier` ∈ `FREE|STANDARD|PREMIUM|ENTERPRISE`, index on `tenant_id`) but NOT populated — per-tenant state is 13.2/16.1.
- Flyway: next versioned public migration (`V29__module_registry.sql`); never touch applied migrations.
- 12.6b routed item: enable `RequiresGraphAcyclicityTest` on the real manifests with the chosen glob, asserting unique non-blank ids and `requires` being an array (reuse the production validator where possible rather than a second parser).

## Boundaries & Constraints

**Always:** no behaviour change for existing endpoints; boot of current prod data must succeed (seed only writes `module_definitions`); all test packages green; Testcontainers (real Postgres) for the migration + seed; architecture suite stays green (`verify()` + ArchUnit rules).

**Never:** no gating/entitlement checks (13.5), no ModuleRegistry API/service beyond what the seed needs (13.2), no per-tenant provisioning (13.4), no `KeevoModule` beans/lifecycle (13.9), no Flutter change, no deploy.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| First boot | empty `module_definitions` | 10 rows inserted | — |
| Second boot | same manifests | 0 rows changed | — |
| Version bump | `catalog` 1.0.0→1.1.0 | row updated, `installed_at` kept | — |
| Module removed | row exists, no manifest | row kept, WARN | — |
| Malformed JSON | broken `module.json` | boot fails | message names the file |
| Unknown requires | `requires: ["ghost"]` | boot fails | names module + unknown id |
| Cycle | a→b→a | boot fails | lists the cycle |
| Unknown enhances | `enhances: ["invoicing"]` | boot OK | WARN |
| Dir/id mismatch | `modules/pos/module.json` with id `sale` | boot fails | names both |

</frozen-after-approval>

## Code Map

- `keevo/backend/src/main/resources/db/migration/` — last is `V28__event_publication.sql`.
- `keevo/backend/src/main/java/com/keevo/shared/` — core; `shared/capability/*` shows the published-contract style; `shared/package-info.java` named interfaces.
- `keevo/backend/src/test/java/com/keevo/architecture/RequiresGraphAcyclicityTest.java` — `@Disabled` manifest test to enable.
- Boot ordering reference: `TenantSchemaMigrationRunner` (SmartLifecycle phase 0).
- Postgres IT style: `EventPublicationRegistryIntegrationTest` / `FullContextIntegrationTest`.

## Tasks & Acceptance

**Execution:**
- [x] `V29__module_registry.sql` (both tables, constraints, index).
- [x] `ModuleManifest` record (+ nested types) + `ModuleManifestLoader`/validator (unit tests for every matrix error row).
- [x] 10 `module.json` files.
- [x] Idempotent seed runner + persistence adapter; Testcontainers IT: first boot inserts 10, second boot no-op, version bump updates and keeps `installed_at`, removed module kept.
- [x] Enable `RequiresGraphAcyclicityTest` on the real manifests; tick the 12.6b routed item in the epic (Story 13.1) with evidence.

**Acceptance Criteria:**
- Given valid manifests, when the app starts, then `module_definitions` holds the 10 modules with id, version, category, requires, enhances, is_free, manifest JSONB — and a restart changes nothing.
- Given any invalid manifest of the matrix, when the app starts, then boot fails with a message naming the file and the problem.
- Given all test packages run one at a time, then all pass.

## Implementation Notes

**Progress — task 1 done (2026-10-29).** `V29__module_registry.sql`: `public.module_definitions` (PK id, NOT NULL version/display_name/category/manifest, `requires`/`enhances` JSONB default `[]`, `is_free` default false, `installed_at` default NOW(), CHECK category) + `public.tenant_modules` (UUID PK, UNIQUE(tenant_id, module_id), CHECK status/tier, index `tenant_modules_tenant_id_idx`). Both added to `TenantSchemaSyncService.GLOBAL_ONLY_TABLES` (otherwise the boot sync would mirror them into every `kv_xxxxxx` schema).

**Progress — task 2 done (2026-10-29).** Published API `com.keevo.shared.module`: `ModuleManifest` (record + nested `ExtensionPoints`, immutable lists, `CORE_ID`), `ModuleCategory`, `ModuleManifestSource`, `LoadedModuleManifest` (location + model + normalised JSON for the cache), `InvalidModuleManifestException` (all problems, one per line, each naming file + field), `ModuleDefinitionStore` port, `ModuleManifestLoader` (`CLASSPATH_PATTERN = classpath*:modules/*/module.json`, strict Jackson: duplicate keys + trailing tokens rejected; all-or-nothing validation of every rule of the spec, plus: core must require nothing, `displayName` ≤ 100, `free` boolean, `entitlement` non-blank (default `module.<id>`), unknown top-level fields ignored but kept in the JSON cache; public `findRequiresCycle`). `ModuleManifestLoaderTest`: 14 tests green (every matrix error row + field rules).

**Progress — task 3 done (2026-10-29).** 10 manifests `src/main/resources/modules/<id>/module.json` exactly as decided (ids/categories/requires/enhances, `version 1.0.0`, `free: true`, `entitlement module.<id>`, French `displayName`, empty publishes/consumes/provides/needs/permissions/routes/extensionPoints). `ClasspathModuleManifestsTest` loads them through the production loader and pins the graph.

**Progress — task 4 done (2026-10-29).** `shared.module.application.ModuleCatalogSeeder` (`@Component @DependsOnDatabaseInitialization InitializingBean` = after Flyway, during bean init, so before the web server — same pattern as `EventPublicationSchemaGuard`; load+validate first, then upsert; `SeedReport` inserted/updated/unchanged/notOnClasspath; WARN per orphan row; INFO "Module catalogue unchanged" on a no-op). `shared.module.adapter.out.persistence.JdbcModuleDefinitionStore`: one `INSERT … ON CONFLICT (id) DO UPDATE … WHERE (cols) IS DISTINCT FROM (EXCLUDED.cols) RETURNING (xmax = 0)` per module (multi-instance safe, no-op rewrites nothing, `installed_at` never updated). `ModuleCatalogSeederPostgresIntegrationTest` (fresh DB on the shared container + real Flyway): first seed 10 inserted with every column; second seed 0 changed and identical rows incl. `xmin`; catalog 1.0.0→1.1.0 updated, `installed_at` kept; orphan `legacy` row kept + reported; `tenant_modules` empty, UNIQUE / status / tier CHECKs + index, category CHECK. `ModuleCatalogBootIntegrationTest` (full context): boot seeded the 10 rows, re-seed changes nothing. `com.keevo.shared.module.**`: 20 tests, 0 failures.

**Progress — task 5 done (2026-10-29).** `RequiresGraphAcyclicityTest` enabled on the real manifests through `ModuleManifestLoader.loadFromClasspath()` (same glob/validator as the boot; cycle finder = production `findRequiresCycle`, the test's private copy removed) + `noManifestOutsideTheModulesDirectory`. Epic Story 13.1 routed 12.6b item ticked with evidence. `com.keevo.architecture.**`: 28 tests, 0 failures, 0 skipped; Modulith `✓ No module violations detected.`

**Verification (2026-10-29).** 14 packages one at a time, all rc=0, 0 failures/errors/skipped: shared 664, catalog 247, crm 114, inventory 376, commerce 251, sync 496, identity 437, messaging 163, reporting 389, admin 504, store 60, subscription 70, feedback 52, architecture 28 (total 3,851). Live dev boot (keevo_dev, existing data): boot 1 applied V29, "Module catalogue seeded — inserted=[admin, catalog, core, crm, inventory, messaging, pos, reporting, store, sync]", started in 61 s, `/actuator/health` 200, 0 ERROR; `select id, version, category …` → 10 rows (categories as decided), `tenant_modules` empty, neither table in any tenant schema. Boot 2: "Module catalogue unchanged — 10 module(s) up to date", the same `xmin` on all 10 rows (no row rewritten), started in 57 s, 0 ERROR. Backend stopped (`fuser -k 4500/tcp`).
**Review fixes (2026-10-29), done.** Location: the manifests moved to `src/main/resources/keevo/modules/<id>/module.json`, pattern `classpath*:keevo/modules/*/module.json`, so a dependency jar that ships its own `modules/` directory is not picked up. All the notes above that say `modules/<id>/` now mean `keevo/modules/<id>/`.
- Seed/persistence:
  - The write port is now `shared.module.application.port.ModuleDefinitionPort` (`upsert`, `findVersions`); `ModuleDefinitionStore` is removed and the adapter stays where it was.
  - The seeder takes the context's `ResourcePatternResolver` and a `PlatformTransactionManager`, and runs the whole seed in one `TransactionTemplate`.
  - No downgrade: when the manifest version is lower than the stored one, the module is skipped with a WARN naming both versions (`SeedReport.skippedOlder`). The SQL upsert has a guard too, `string_to_array(EXCLUDED.version,'.')::int[] >= string_to_array(d.version,'.')::int[]`. The same version with changed content still updates.
  - The `manifest` JSONB now stores the validated manifest serialised with every default applied, in a stable key order. Unknown keys, including those inside `extensionPoints`, get one WARN each and are dropped.
- Validation:
  - Graph checks always run on the manifests that parsed. A dependency on a manifest that failed to parse is reported as "whose manifest is invalid".
  - Every cycle is reported, one per strongly connected component (Tarjan), through the public `findRequiresCycles`.
  - Duplicate entries in any list fail; so do a module enhancing itself, the same id in both `requires` and `enhances`, and a `requires`/`enhances` entry that is not a module id.
  - `core` must have category `core`.
  - `entitlement` must match `^module\.[a-z0-9-]+$` and be unique across manifests.
  - The id pattern is now `^[a-z][a-z0-9]*(-[a-z0-9]+)*$`, at most 50 characters.
- V29 (edited in place; it had only been applied to local dev):
  - Plain `CREATE TABLE`.
  - `tenant_modules.id DEFAULT gen_random_uuid()`.
  - **The `tenant_id` index is dropped**: the `UNIQUE (tenant_id, module_id)` index already serves lookups by `tenant_id`.
  - Added a `module_definitions_version_check` (strict MAJOR.MINOR.PATCH), so the `int[]` cast in the guard can never fail.
  - Local `keevo_dev` repaired: both tables dropped and the V29 history row deleted, then the dev boot re-applied V29 and seeded 10 rows (health 200, 0 ERROR).
- Tests:
  - `ModuleManifestLoaderTest`: 17 tests (new: graph checked despite an invalid file, several cycles, list rules, entitlement rules, core category, stored JSON with defaults, unknown-key WARN, Boot 3.5 `jar:nested:` URL).
  - `ModuleCatalogSeederPostgresIntegrationTest`: 7 tests (new: manifest cache holds defaults, numeric no-downgrade 1.9.0 < 1.10.0 with the seeder WARN and the SQL guard on its own, same version with new content, removed-module WARN, rollback of the whole seed on a mid-loop failure, generated `tenant_modules.id`, version CHECK).
  - New `ModuleCatalogSeederContextTest`: `ApplicationContextRunner` with a test classloader adding `keevo/modules/ghosty/module.json`; the context fails with root cause `InvalidModuleManifestException` naming the file and `field 'requires'`, and the port and transaction manager are never called.
  - `GlobalNotificationPostgresIntegrationTest`: explicit list now includes `module_definitions`, `tenant_modules`.
  - `RequiresGraphAcyclicityTest`: expects exactly 10; the base dir is resolved from the class location; `src/main/resources` and `src/test/resources` are both scanned.
- Runs: `com.keevo.shared.module.**` 27 tests, `com.keevo.architecture.**` 28 tests (0 skipped, Modulith clean), `com.keevo.admin.**` 504 tests, all 0 failures/errors.
- Rejected / routed by the orchestrator: `xmax = 0` kept; jar-discovery CI smoke (proven manually); orphan rows marked "not installed" → 13.2; validated-manifest singleton bean → 13.2; public `ModuleManifest` constructor → 13.9; `needs` `?` format → 13.6; `routes` format → 15.2; other global tables mirrored into tenants by `TenantSchemaSyncService` → 13.4.

**Known gap (closed):** fail-fast at context level is now covered by `ModuleCatalogSeederContextTest`. The orchestrator also proved it live: a repackaged jar whose `pos` requires `ghost` exits 1 naming `modules/pos/module.json`, field `requires` (before the move to `keevo/modules/`).

**Final verification (orchestrator, 2026-10-29, after review patches).** 14 packages sequential: 3,858 tests, 0 failures/errors; Modulith `✓ No module violations detected.` Packaged jar: 10 `BOOT-INF/classes/keevo/modules/*/module.json`; two boots on `keevo_dev` (dev profile) → started, "10 module(s) up to date", same `xmin` on all 10 rows across boots, `tenant_modules` empty, 0 ERROR.

## Spec Change Log

## Review Triage Log

### 2026-10-29 — Review pass (13-1)
- verdicts: 4 layers (Blind Hunter 11, Edge Case 13, Verification Gap 5, Intent 8; overlap) — high 0, medium 6, low 14 after dedup
- findings:
  - `[medium]` `[patch]` upsert lets an older build downgrade the catalogue (rolling deploy/rollback) — numeric semver guard in Java + SQL, WARN, version CHECK
  - `[medium]` `[patch]` seed in auto-commit → half-seeded catalogue on mid-loop failure — one TransactionTemplate
  - `[medium]` `[patch]` `manifest` JSONB = raw document without defaults — validated manifest serialised; unknown keys WARN + dropped
  - `[medium]` `[patch]` `classpath*:modules/*/module.json` matches foreign jars — moved to `keevo/modules/<id>/`
  - `[medium]` `[patch]` graph checks skipped when any file fails; only first cycle reported — always run, one cycle per SCC
  - `[medium]` `[patch]` boot fail-fast not proven at context level — `ModuleCatalogSeederContextTest` (injected ResourcePatternResolver); orchestrator also proved it live on the packaged jar (`pos` requires `ghost` → exit 1, file + field named)
  - `[low]` `[patch]` list duplicates, self-enhance, requires∩enhances, entries not matching id pattern, core category, entitlement format/uniqueness, id hyphen rules
  - `[low]` `[patch]` V29: plain CREATE TABLE, `gen_random_uuid()` default, redundant index dropped (V29 edited in place: never applied outside local dev; keevo_dev repaired)
  - `[low]` `[patch]` write port renamed/moved to `shared.module.application.port.ModuleDefinitionPort`
  - `[low]` `[patch]` tests: removed-module WARN asserted, GlobalNotification explicit table list, `jar:nested:` URL fixture, manifest location check from module base dir + test resources, count = 10
  - `[low]` `[reject]` `xmax = 0` insert detection — documented Postgres idiom, logs/report only
  - `[low]` `[reject]` jar-discovery CI smoke — proven manually on the repackaged jar (10 manifests seeded "unchanged")
  - `[low]` `[reject]` lazy-init / custom Flyway ordering — app uses Boot's Flyway, same pattern as `EventPublicationSchemaGuard`
  - `[low]` `[defer]` orphan rows treated as not installed → routed → 13-2
  - `[low]` `[defer]` validated manifest set as a single bean → routed → 13-2
  - `[low]` `[defer]` pre-existing: tenant schema sync copies other global tables into tenants → routed → 13-4
  - `[low]` `[defer]` `needs` format / `provides` uniqueness → routed → 13-6
  - `[low]` `[defer]` public `ModuleManifest` constructor bypasses invariants → routed → 13-9
  - `[low]` `[defer]` `routes` format → routed → 15-2

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -Dtest='com.keevo.shared.module.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: green
- `... -Dtest='com.keevo.architecture.**'` -- expected: green, acyclicity test enabled
- 14-package sequential loop -- expected: green
- Live: boot dev backend twice; `select id, version, category from public.module_definitions` → 10 rows, unchanged on second boot.
