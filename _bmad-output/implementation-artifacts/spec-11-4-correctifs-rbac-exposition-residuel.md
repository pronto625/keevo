---
title: '11-4 — RBAC & exposure residual: CORS allow-list, schema-name validation everywhere'
type: 'bugfix'
created: '2026-10-13'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
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

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.shared.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.sync.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- then one package at a time for each touched package (`admin`, `messaging`, `subscription`, `identity`, `catalog`) -- expected: 0 failures
