---
title: '11-4 — RBAC & exposure residual: CORS allow-list, schema-name validation everywhere'
type: 'bugfix'
created: '2026-10-13'
status: 'done'
baseline_revision: '2fb57429b23866ce53be98a6fee11e43ea6b7e6b'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-11-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/11-4-correctifs-rbac-exposition.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 11-4 is `in-progress`: its Task 1 (pending-sale cancel OWNER-only, `PendingSaleController.java:74-81`) and Task 2 (device-token delete scoped to its owner, `DeleteDeviceTokenUseCase.delete(userId, token)`) are already on this branch, but AC5/AC6 are not — `SecurityConfig.corsConfigurationSource()` accepts every origin (`setAllowedOriginPatterns("*")`) **with credentials** in every profile — and AC7 is partial: about 20 Java sites still interpolate a tenant schema name into SQL without the shared `TenantSchema` check at the point of use.

**Approach:** Finish the story file's Task 3 (CORS allow-list from `DASHBOARD_ORIGIN`, localhost only outside `prod`), Task 4 (call `TenantSchema.validate`/`isValid` at every interpolation site, keeping each site's current skip/throw semantics), Task 5 (document/settle the `CancelSaleSyncHandler` role path) and Task 6 (regression + notes), then mark 11-4 done.

## Boundaries & Constraints

**Always:**
- CORS: `prod` and `staging` profiles → allowed origins = comma-separated `DASHBOARD_ORIGIN` values (trimmed, empty entries ignored; none configured → no browser origin allowed, startup WARN); other profiles → those values plus `http://localhost:3000`. `allowCredentials` stays true only with explicit origins (never with `*`). Mobile app and the dashboard's server-side proxy are unaffected (no Origin header / server-to-server). `ProdSecretsValidator` warns (not fails) when `DASHBOARD_ORIGIN` is missing in prod. `.env.example`/deployment docs mention `DASHBOARD_ORIGIN`.
- Schema validation: every site that builds SQL with a schema name (`" + schema`, `"\"" + schema`, `String.format(…, schema)`) validates it with `TenantSchema` right before use, unless the value was validated in the same method a few lines above (then leave a one-line comment pointing to it); behaviour on invalid input mirrors the site's current failure mode (skip in loops / throw in request paths). `SchemaAwareMultiTenantConnectionProvider` `SET search_path` included. Add a guard test that scans `src/main/java` for interpolation patterns without a nearby `TenantSchema` call (allow-list file of reviewed exceptions) so a new unguarded site fails the build.
- `CancelSaleSyncHandler`: an EMPLOYEE cancel through sync must follow the same rule as REST (FR90 decision recorded in v1s-13-5: check what the code does today; if EMPLOYEE may cancel only their own PENDING_VALIDATION draft per v1s-13-5, keep it and document; otherwise reject with `FORBIDDEN` like REST) — record the outcome in the story notes with the test that pins it.

**Never:** No change to the RBAC of other endpoints; no wildcard origin in any profile with credentials.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| prod, origin = DASHBOARD_ORIGIN | preflight from https://admin.keevo.cm | allowed | — |
| prod, origin localhost:3000 | preflight | rejected (403 CORS) | — |
| dev, localhost:3000 | preflight | allowed | — |
| `DASHBOARD_ORIGIN="a, b,"` | — | origins a and b | — |
| Invalid schema `public` reaching a loop site | e.g. expiry job | skipped, logged | — |
| Invalid schema reaching a request site | — | exception, no SQL executed | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/SecurityConfig.java:126-143` -- CORS bean.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/config/ProdSecretsValidator.java` (or its actual package) -- prod warnings.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchema.java` -- `isValid`/`validate` helper.
- Interpolation sites (grep `" + schema\|"\\"" + \|String.format(.*schema`): `messaging/notification/adapter/out/persistence/impl/JdbcNotificationCooldownAdapter.java:99`, `messaging/notification/application/scheduler/SalesTrendDetectionScheduler.java:108`, `shared/infrastructure/persistence/SchemaAwareMultiTenantConnectionProvider.java:117`, `admin/sync_monitoring/application/service/AdminSyncMonitoringService.java`, `admin/tenant/application/service/AdminTenantService.java`, `subscription/plan/adapter/out/persistence/impl/{User,Employee,Store,Product}CountAdapter.java`, `identity/auth/application/service/SessionTokenIssuer.java:99-104`, `identity/onboarding/adapter/in/web/TenantPreferencesController.java:119`, `sync/sync/adapter/out/persistence/impl/SyncErrorLogRepositoryAdapter.java:55`, `sync/sync/application/service/SyncErrorLogCleanupJob.java:47`, `catalog/contact/adapter/out/persistence/ClientSalesStatsAdapter.java:42-57`, plus any found by the scan.
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/handler/CancelSaleSyncHandler.java` -- Task 5.

## Tasks & Acceptance

**Execution:**
- `SecurityConfig.java` + new `SecurityConfigCorsTest` (plain unit test with a mock `Environment`, story Task 3).
- Each interpolation site + `TenantSchemaInterpolationGuardTest` (source scan with reviewed allow-list).
- `CancelSaleSyncHandler` test pinning the role rule.
- `keevo/backend/.env.example` (or the env template in use) + deployment checklist line for `DASHBOARD_ORIGIN`.
- `11-4-correctifs-rbac-exposition.md` -- tick tasks, final site list, Status done.

**Acceptance Criteria:**
- Given the prod profile, when a browser page on an origin outside `DASHBOARD_ORIGIN` calls the API with credentials, then CORS refuses it.
- Given a new SQL string built with an unvalidated schema variable, when the test suite runs, then the guard test fails.

## Spec Change Log

## Review Triage Log

### 2026-10-13 — Review pass
- verdicts: 41 findings — high 1, medium 9, low 22, false 4, maybe-false 0 (+ intent rows descriptive)
- findings:
  - `[high]` `[patch]` (intent/edge/gap) untouched `CorsConfig` still registers wildcard origins with credentials on `/**` — deleted; full-chain MockMvc CORS test added.
  - `[medium]` `[patch]` (blind) credentials kept on for a bearer-token API — `allowCredentials(false)`.
  - `[medium]` `[patch]` (blind) hardening fails open on an unexpected profile — localhost only for default/dev/local/test.
  - `[low]` `[patch]` (blind/edge) `DASHBOARD_ORIGIN` values unchecked (placeholder, scheme, path, pattern) — strict origin format, dropped entries warned.
  - `[low]` `[patch]` (blind) two WARNs, misleading `[FailFast]` prefix — single WARN.
  - `[medium]` `[patch]` (blind) tenant-detail helpers throw instead of degrading — validation inside the fail-soft path.
  - `[low]` `[reject]` (blind) `IllegalArgumentException` → generic 500 — a corrupt stored schema is a server-side fault; the generic French 500 is the intended surface, the log carries the stack.
  - `[low]` `[patch]` (blind) silent loop skips — WARN with tenant id.
  - `[medium]` `[patch]` (blind/gap) comment satisfies the guard — comments ignored, pointer comments replaced by real checks, same-identifier rule, self-checks.
  - `[low]` `[patch]` (blind/edge) detector misses `append(...)` / split concatenation; stale allow-list; working directory; vacuous scan — patterns added, stale-entry and scanned-count assertions, robust root.
  - `[low]` `[patch]` (blind) connection leak when `applySearchPath` throws — connection closed.
  - `[low]` `[patch]` (blind) owner cancel test lacks `APPLIED`; old EMPLOYEE queued cancels — assertion added; queued ops are quarantined with the existing French « Action non autorisée » (rejected as extra work).
  - `[low]` `[patch]` (blind) v1s-12-8 artifact contradicts code — dated supersession note.
  - `[false]` `[reject]` (blind) statuses disagree — orchestrator finalizes.
  - `[low]` `[reject]` (blind) regression evidence incomplete — orchestrator runs the full chunked regression.
  - `[low]` `[patch]` (blind) device-token signature misstated in notes — corrected.
  - `[low]` `[patch]` (blind) CORS/validator tests too thin — WARN captured, trailing slash/case/profile cases.
  - `[medium]` `[patch]` (edge) count adapters now throw where they returned 0 — fallback 0 + WARN, no SQL.
  - `[low]` `[patch]` (edge) no rollback on RuntimeException in sync blocks — rollback added.
  - `[low]` `[patch]` (edge) shadowed import in AdminCatalogService — removed.
  - `[false]` `[reject]` (edge) employee offline cancel leaves local state cancelled — the app's cancel UI is OWNER-only (`pending_sale_detail_page`, `sale_detail_page` routes guarded), employees never queue CANCEL_SALE.
  - `[medium]` `[patch]` (gap) login with invalid schema untested — `SessionTokenIssuerTest` case.
  - `[false]` `[reject]` (intent) CancelSaleSyncHandler R-H1 vs R-H2 — FR90 « Un propriétaire peut annuler », v1s-13-5 AC4 EMPLOYEE → 403: R-H2 is the documented rule.
  - `[false]` `[reject]` (intent) env binding of DASHBOARD_ORIGIN untested — `Environment.getProperty` and `@Value` read the same relaxed-binding source; the chain test sets it as a property.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.shared.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.sync.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- then one package at a time for each touched package (`admin`, `messaging`, `subscription`, `identity`, `catalog`) -- expected: 0 failures

## Auto Run Result

Status: done

**Summary:** Story 11-4 closed. CORS: a single source (`SecurityConfig`; the legacy MVC `CorsConfig` wildcard is deleted), explicit origins from `DASHBOARD_ORIGIN` (strict `http(s)://host[:port]`, placeholder and wildcards dropped), `http://localhost:3000` only for default/dev/local/test profiles, no credentials (bearer API), one startup WARN when unset; proven through the real filter chain. Schema names: every SQL interpolation site validates the schema with `TenantSchema` (request paths throw before any SQL, loops skip with a WARN carrying the tenant id, fail-soft sites keep their fallback), enforced by a source guard test that requires a real check on the same identifier (comments no longer count, stale allow-list entries fail). `CancelSaleSyncHandler`: OWNER-only like REST (FR90, v1s-13-5), superseding v1s-12-8's employee case (note added there).

**Files changed:** backend `SecurityConfig`, `ProdSecretsValidator`, `CorsConfig` (deleted), ~40 interpolation sites across admin/sync/messaging/subscription/identity/catalog/shared, `SchemaAwareMultiTenantConnectionProvider`, `TenantSchemaSyncService`, `CancelSaleSyncHandler`; tests `SecurityConfigCorsTest`, `SecurityConfigCorsChainTest`, `ProdSecretsValidatorTest`, `TenantSchemaInterpolationGuardTest` + allow-list, adapter/handler/issuer tests; `.env.example`, both deployment checklists; story files 11-4 and v1s-12-8.

**Review:** 41 findings — patched 1 high (wildcard credentialed CORS still registered), 9 medium and 22 low entries (grouped); deferred 0; rejected 9 (false 4, low with reasons).

**Follow-up review recommended:** true — a high was patched; unverified risk: the source-scan guard is a heuristic (20-line window, same identifier), not data-flow analysis.

**Verification:** backend full chunked regression — admin 476, architecture 6, catalog 396, commerce 232, feedback 44, identity 374, inventory 165, messaging 131, reporting 375, shared 544, store 42, subscription 61, sync 441 — 0 failures/errors.

**Residual risks:** `DASHBOARD_ORIGIN` must be set in the prod `.env` before the next deploy for any browser page calling the API directly (the dashboard proxy and the mobile app are unaffected).

