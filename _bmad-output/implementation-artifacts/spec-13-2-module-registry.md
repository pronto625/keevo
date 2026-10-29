---
title: '13.2 — ModuleRegistry: installed modules + per-tenant active state'
type: 'feature'
created: '2026-10-29'
status: 'done'
baseline_revision: '266570bae6a4145a4eca6ebe48a338533f562637'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/spec-socle-modulaire.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-1-registre-modules-manifest-seed.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 13.1 seeds the module catalogue, but nothing can ask "which modules are installed?" or "is module X active for tenant T?" (MOD-FR1, MOD-NFR10). 12.7 (sync guard), 13.3 (resolver), 13.5 (entitlement) all need that query.

**Approach:** A `ModuleRegistry` port in the core (spec-socle §5.1), backed by a single validated manifest set loaded once at boot (`ModuleCatalog`) and by `public.tenant_modules`, with a per-request read cache.

**Decisions (autonomous run, user delegated all choices 2026-10-28):**
- **Tenant key** = the tenant schema string (`kv_xxxxxx`), the tenant identity carried by the JWT and `TenantContext` everywhere in the code; validated through `TenantSchema`. The implementation resolves `public.tenants.id` (UUID) from it inside the same query (join), never asking callers for the UUID.
- **API** (published, core): `installed()` → the manifests on the classpath (from `ModuleCatalog`); `definition(moduleId)` → `Optional<ModuleManifest>` (the spec's `module(id) → KeevoModule` waits for 13.9 — do not create `KeevoModule`); `activeModules(tenantSchema)` → `Set<String>`; `isActive(tenantSchema, moduleId)`.
- **Effective state rule** (V1 parity, no data backfill — prod data must not be changed by the update):
  1. a module id not in `installed()` (unknown, or a `module_definitions` row whose manifest left the classpath — 13.1 orphan) is **never** active;
  2. `core` is **always** active (spec §2.2: Core never enters the lifecycle), whatever `tenant_modules` says;
  3. if a `tenant_modules` row exists for (tenant, module): active ⇔ `status ∈ {TRIAL, ACTIVE}`;
  4. if no row exists: active ⇔ the manifest has `free: true` (today all 10 modules → every existing tenant keeps every feature; future paid modules are off until a row is created by 13.8/16.1).
  An unknown tenant schema (no `public.tenants` row) → rule 4 does not apply: only `core` is active; log at DEBUG.
- **Cache:** per HTTP request — results stored in the current request's attributes (`RequestContextHolder`) when a request is bound; outside a request (schedulers, async listeners, boot) every call reads the DB (no stale cache, no `@RequestScope` proxy that would fail outside requests). One DB query per tenant per request at most (`activeModules` loads all rows of the tenant once; `isActive` reuses it).
- **`ModuleCatalog`** (routed from 13.1): a singleton bean holding the validated manifest set, loaded once at startup through the existing loader and the context `ResourcePatternResolver`; `ModuleCatalogSeeder` consumes it instead of scanning itself. Invalid manifests keep failing the boot (13.1 behaviour unchanged).
- **Prod jar (routed from 12.6b):** exclude `spring-modulith-core` (and with it ArchUnit) from `spring-modulith-starter-jdbc` if the full suite, `dependency:tree` and a packaged-jar boot (events registry working: publications completed) prove it unused at runtime; otherwise keep it and record why. Tests keep modulith-core through `spring-modulith-starter-test`.
- Read-only: no write API on `tenant_modules` (activation = 13.8), no gating (13.5), no expiry logic (`expires_at`/grace = 16.1).

## Boundaries & Constraints

**Always:** no behaviour change for existing endpoints; no write to `tenant_modules`/`module_definitions` beyond the existing 13.1 seed; schema-qualified SQL (`public.`) with bind parameters only; all test packages green; Postgres Testcontainers for the registry queries; architecture suite green.

**Never:** no `KeevoModule`, no activation/deactivation, no entitlement aspect, no Redis, no Flutter, no deploy.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Existing tenant, no rows | 10 free modules | `activeModules` = all 10 | — |
| Row DISABLED | `pos` DISABLED | `isActive(pos)` false, others true | — |
| Row EXPIRED / AVAILABLE | `crm` EXPIRED | false | — |
| Row TRIAL / ACTIVE | `reporting` TRIAL | true | — |
| Core row DISABLED | `core` DISABLED | still active | — |
| Orphan row ACTIVE | `legacy` ACTIVE, no manifest | not active, not in `installed()` | — |
| Unknown module id | `isActive(t, "ghost")` | false | — |
| Unknown tenant | no `public.tenants` row | only `core` | DEBUG log |
| Invalid schema string | `"public"`, `"KV_1"` | — | `IllegalArgumentException` (via `TenantSchema`) |
| Same request, 5 calls | one tenant | 1 DB query | — |
| Scheduler thread | no request | reads DB each call | — |

</frozen-after-approval>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/module/` — 13.1 model, loader, seeder, `application/port/ModuleDefinitionPort`, `adapter/out/persistence/JdbcModuleDefinitionStore`.
- `shared/infrastructure/persistence/TenantSchema.java`, `TenantContext.java`.
- `public.tenants` (id UUID, schema name column — check the entity/migration for the exact name).
- `keevo/backend/pom.xml` — modulith starters.

## Tasks & Acceptance

**Execution:**
- [x] `ModuleCatalog` bean; seeder consumes it.
- [x] `ModuleRegistry` port + JDBC adapter + request cache; unit tests for the state rules; Postgres IT for every matrix row; cache test (1 query per request; none outside requests).
- [x] Prod-jar decision for `spring-modulith-core` with evidence (dependency:tree before/after, packaged-jar boot, registry IT green).
- [x] Tick the routed items of Story 13.2 in the epic with evidence.

**Acceptance Criteria:**
- Given a tenant with a subset of modules active in `tenant_modules`, when `isActive`/`activeModules` is called, then the result follows the effective state rule, and within one HTTP request the tenant's rows are read once.
- Given all test packages run one at a time, then all pass.

## Implementation Notes

**Progress — task 1 coded (2026-10-29).** `shared.module.application.ModuleCatalog` (`@Component`, loads + validates once via `ModuleManifestLoader.loadFromClasspath(ResourcePatternResolver)`, invalid set throws in the constructor → boot fails; `loaded()`, `manifests()`, `find(id)`, `contains(id)`). `ModuleCatalogSeeder` now takes `ModuleCatalog` instead of the resolver; seeder tests updated (Postgres IT, context test with `ModuleCatalog.class`, boot IT asserts the bean holds 10). Two constructors (resolver `@Autowired`, list for tests).

**Progress — task 2 done (2026-10-29).** Published `shared.module.ModuleRegistry` (`installed`, `definition`, `activeModules(tenantSchema)`, `isActive`) + `TenantModuleStatus` enum (`isActive()` = TRIAL/ACTIVE). `application.ModuleRegistryService` (validates the schema with `TenantSchema.validate` first, then cache → port → `effectiveActiveModules` pure rule, result immutable + sorted; DEBUG log for an unknown tenant without the schema value). Ports `application.port.TenantModuleStatePort` (`Optional<Map<moduleId,status>>`, empty = unknown tenant) and `application.port.ActiveModulesCache`. Adapter `adapter.out.persistence.JdbcTenantModuleStateStore`: one query `public.tenants LEFT JOIN public.tenant_modules … WHERE t.schema_name = ?` (no row = unknown tenant; one null row = tenant without rows), validates the schema too (rule (a)), unexpected status → DISABLED + WARN. Cache `shared.module.infrastructure.RequestScopedActiveModulesCache` (request attributes via `RequestContextHolder`; outside `..application..` because rule (c) forbids `org.springframework.web` there; no request bound → always a miss). Tests: `ModuleRegistryServiceTest` 24 (every rule, paid module without row off, invalid schemas incl. `public`/`KV_1` with 0 query, 5 calls = 1 query, other tenant = own query, new request re-reads, unknown tenant cached, no request = every call reads); `ModuleRegistryPostgresIntegrationTest` 11 (every matrix row on real Postgres + seeded catalogue + orphan `legacy` row, other tenant's row does not leak, 1 query per request, async thread reads each call); boot IT: registry bean wired, `kv_nobody` → `[core]`. `com.keevo.shared.module.**`: 62 tests, 0 failures. `com.keevo.architecture.**`: 28 tests, 0 failures.

**Progress — task 3 done (2026-10-29): `spring-modulith-core` excluded from the prod jar.** Evidence: (1) no class of `spring-modulith-{api,apt,moments,events-api,events-core,events-jdbc,events-jackson}` 1.4.14 references `org.springframework.modulith.core` (byte scan of the jars); no `src/main/java` reference to `modulith.core`/`modulith.runtime`/`com.tngtech`; modulith-core's only auto-config, `ApplicationModuleInitializerRuntimeVerification`, is a guard that throws when an `ApplicationModuleInitializer` bean exists without `spring-modulith-runtime` (we have neither). (2) `dependency:tree` before: `starter-core → spring-modulith-core:runtime → archunit:1.4.2:runtime`; after (exclusion on `spring-modulith-starter-jdbc`): no modulith-core/archunit at compile/runtime, `spring-modulith-test → spring-modulith-core:test → archunit:1.4.2:test`. (3) Packaged jar: `BOOT-INF/lib` has no `spring-modulith-core`/`archunit`; `java -jar … --spring.profiles.active=dev` on `keevo_dev`: "Module catalogue unchanged — 10 module(s) up to date", started in 57.7 s, `/actuator/health` 200, 0 ERROR; stopped with `fuser -k 4500/tcp`. (4) `EventPublicationRegistryIntegrationTest` (9 tests: publications completed, retry, incomplete resubmission…) green with the test classpath stripped of modulith-core, archunit, spring-modulith-test/docs/starter-test (`-Dmaven.test.dependency.excludes=…`, surefire classpath checked) = the prod runtime set.

**Progress — task 4 done (2026-10-29).** Epic Story 13.2: the three routed items (12.6b modulith-core, 13.1 orphan rows, 13.1 `ModuleCatalog`) ticked with evidence.

**Verification (2026-10-29).** 14 packages one at a time, all rc=0, 0 failures/errors/skipped: shared 706, catalog 247, crm 114, inventory 376, commerce 251, sync 496, identity 437, messaging 163, reporting 389, admin 504, store 60, subscription 70, feedback 52, architecture 28 (total 3,893). Nothing committed.

**Review fixes — part 1 done (2026-10-29).** Platform-admin schema `public` → `core` + installed `platform` modules, no query, not cached (other invalid strings still throw); DELETED tenants = unknown (`t.status <> 'DELETED'`); results = unmodifiable `LinkedHashSet` in sorted order (`contains(null)` false); `ModuleRegistry.evict` + `ActiveModulesCache.evict`; cache treats a completed request's attributes (`IllegalStateException`) as a miss on get/put/evict; `ModuleCatalog(List)` sorts by id and requires `core`; `@DependsOnDatabaseInitialization` on `JdbcTenantModuleStateStore`; `V30__tenant_modules_module_id_check.sql` (idempotent V13-style named CHECK, manifest id pattern). Tests on a test catalogue `TestModuleCatalogs` (core/platform/free business/paid premium); one shipped-set test (10 manifests, all free as explicit precondition). `com.keevo.shared.module.**`: 72 tests green.

**Review fixes — part 2 done (2026-10-29).** `pom.xml`: maven-enforcer `bannedDependencies` (validate phase) bans `spring-modulith-core` / `archunit` at compile/runtime scope — proven by removing the exclusion (`mvn validate` fails naming `starter-jdbc → starter-core → spring-modulith-core`), then restored (`validate` OK). Profile `prod-classpath`: surefire runs only `EventPublicationRegistryIntegrationTest` + `ModuleCatalogBootIntegrationTest` with `classpathDependencyExcludes` = modulith-core, archunit, modulith-test, modulith-docs, modulith-starter-test; local run 10/10 green, surefire classpath has none of them. CI: step "Prod classpath tests" (`mvn -Pprod-classpath test --batch-mode --no-transfer-progress`, same env) after "Build and test" in `.github/workflows/backend-ci.yml`; actionlint clean. Re-run: `com.keevo.architecture.**` 28 + `com.keevo.shared.module.**` 72, 0 failures.

**Final verification (orchestrator, 2026-10-29, after review patches).** 14 packages sequential: 3,903 tests, 0 failures/errors; Modulith `✓ No module violations detected.`; `mvn -Pprod-classpath test` green. Live (before review patches, same dependency set): slim packaged jar without modulith-core/archunit booted on keevo_dev, `POST /api/v1/products` 201 → `ProductCreatedEvent` publication completed, 0 ERROR.

## Spec Change Log

## Review Triage Log

### 2026-10-29 — Review pass (13-2)
- verdicts: 4 layers (Blind Hunter 9, Edge Case 8, Verification Gap 4, Intent 7; overlap) — high 0, medium 7, low 9 after dedup
- findings:
  - `[medium]` `[patch]` platform-admin schema `public` → IllegalArgumentException → generic 500 for future callers — explicit: core + platform modules, no query
  - `[medium]` `[patch]` DELETED tenants treated as existing (all free modules on) — filtered, = unknown tenant
  - `[medium]` `[patch]` no cache eviction after a write in the same request — `ModuleRegistry.evict(schema)`
  - `[medium]` `[patch]` cache throws on finished-request attributes — treated as a miss
  - `[medium]` `[patch]` `tenant_modules.module_id` without format guard → miscased row ignored, module fails open — V30 named CHECK (V29 untouched)
  - `[medium]` `[patch]` modulith-core exclusion only proven manually — maven-enforcer `bannedDependencies` (proven: fails without the exclusion) + `prod-classpath` profile + CI step; orchestrator also proved it live on the slim jar (`ProductCreatedEvent` publication completed)
  - `[medium]` `[patch]` rule tests tied to the shipped manifests — test catalogue; one shipped-set test with explicit "all free" precondition
  - `[low]` `[patch]` `contains(null)` NPE on the returned TreeSet — sorted LinkedHashSet
  - `[low]` `[patch]` `ModuleCatalog(List)` skipped invariants — core required, sorted, duplicates rejected
  - `[low]` `[patch]` store without `@DependsOnDatabaseInitialization`
  - `[low]` `[reject]` unknown-status WARN path untested — unreachable behind the CHECK constraint
  - `[low]` `[reject]` "loaded once" ArchUnit rule — single caller today; 13.3 adds the next consumer
  - `[low]` `[defer]` MockMvc full-context cache proof, `moduleOf(operationType)`, guard placement → routed → 12-7
  - `[low]` `[defer]` dependents from `activeModules` → routed → 13-3
  - `[low]` `[defer]` EXPIRED grace / expiry fields / single source with entitlement / SUSPENDED tenants → routed → 13-5
  - `[low]` `[defer]` deactivation upserts DISABLED + evict after writes → routed → 13-8

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -Dtest='com.keevo.shared.module.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: green
- `... -Dtest='com.keevo.architecture.**'` -- expected: green
- 14-package sequential loop -- expected: green
