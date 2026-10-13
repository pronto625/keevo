---
title: '9-8 — Sync monitoring page completion (human labels, refresh errors, dashboard test harness)'
type: 'feature'
created: '2026-10-13'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-9-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/9-8-monitoring-sync-sante-devices.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 9-8 (super-admin sync monitoring page) is in `review` with open items: the tenant drawer shows device/user UUIDs and raw codes (`CREATE_SALE`, `LAST_WRITE_WINS`, `PRODUCT_NOT_FOUND`, plan `PREMIUM`), a failed refresh keeps stale data without any signal, the JSON contract between backend DTOs and `types/sync-monitoring.ts` is not locked by tests, the dashboard has no test harness at all, and the epic document has no Story 9.8 section.

**Approach:** Close every routed item of the 9-8 story file: add the user's display name to the device rows (backend), map every code to a French label in one dashboard label module, surface refresh errors and in-flight refresh on the page, lock the JSON contract with `jsonPath` assertions, install Vitest + Testing Library in the dashboard with component tests, and add Story 9.8 (and 9.6/9.7 briefs) to the epic document. Then 9-8 is `done`.

## Boundaries & Constraints

**Always:** No UUID, schema name or raw enum code rendered anywhere on the sync page (rule "no technical data in UI", French labels only); unknown codes are humanized (e.g. `SOME_CODE` → « Some code »), never printed raw. Device column = « Appareil 1 », « Appareil 2 »… numbered per user in list order; user column = employee full name, else « Propriétaire » for the tenant OWNER, else « Utilisateur inconnu ». The hook still returns `{ overviewQuery, healthQuery, selectedTenantId, setSelectedTenantId, refresh }`; `page.tsx` may now pass `isError`/`isFetching`/`refetch` to the components. `npm run lint`, `npx tsc --noEmit`, `npm run build`, `npm test` all green; the dashboard CI workflow runs `npm test`. Backend stays SUPER_ADMIN-only, schema names validated with `TenantSchema.isValid` before interpolation (existing pattern).

**Never:** No new admin endpoint; no change to sync engine tables; no client-side sorting change of the health table; no E2E browser framework (Playwright/Cypress) — unit/component tests with jsdom only.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Device of an employee | `user_sync_state.user_id` = an `employees.user_id` of the tenant schema | detail DTO device has `userName` = "Awa Diallo"; drawer shows « Awa Diallo » and « Appareil 1 » | — |
| Device of the owner | user is OWNER in `user_tenant_memberships`, not in `employees` | `userName` = null, `userRole` = "OWNER" → drawer « Propriétaire » | — |
| Unknown user | no employee row, no membership | `userName` null, `userRole` null → « Utilisateur inconnu » | — |
| Two devices of one user | same userId twice | « Appareil 1 », « Appareil 2 » | — |
| Known codes | `CREATE_SALE`, `PRODUCT_NOT_FOUND`, `LAST_WRITE_WINS`, plan `PREMIUM_TRIAL` | « Enregistrement de vente », « Produit introuvable », « Dernière modification gagnante », « Essai Premium » | — |
| Unknown code / free-text reason | `NEW_THING` / "Product not found: …" | « New thing » / « Données refusées par le serveur » (free text containing an id is never shown raw) | — |
| Refresh fails with data cached | refetch rejects | stale data stays, inline « Échec de l'actualisation — données possiblement obsolètes » + « Réessayer » button calling refetch | — |
| First load fails | no data, error | « Erreur de chargement » + « Réessayer » | — |
| Refresh in flight | isFetching true | « Actualiser » button disabled with « Actualisation… » | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/admin/sync_monitoring/application/service/AdminSyncMonitoringService.java` -- `execute(GetSyncTenantDetailQuery)` loads devices from `public.user_sync_state` (deviceMapper); add a LEFT JOIN on `<schema>.employees e ON e.user_id = s.user_id` (first_name, last_name) and `public.user_tenant_memberships m ON m.user_id = s.user_id AND m.tenant_id = <tenant uuid>` (role). Schema already validated in `loadTenantById`.
- `.../admin/sync_monitoring/domain/model/AdminDeviceInfo.java`, `.../adapter/in/rest/dto/AdminDeviceInfoDto.java` -- add `userName` (String, nullable) and `userRole` (String, nullable).
- `keevo/backend/src/test/java/com/keevo/admin/sync_monitoring/adapter/in/rest/AdminSyncMonitoringControllerTest.java:58,85,102` -- add `jsonPath` on every field read by the dashboard types (overview, tenants, detail incl. devices[0].userName/userRole, recentFailedOps[0].*, recentConflicts[0].*).
- `.../application/service/AdminSyncMonitoringServiceTest.java` -- extend for the device name/role mapping.
- `keevo/dashboard/src/types/sync-monitoring.ts` -- add `userName`, `userRole` to `AdminDeviceInfo`.
- `keevo/dashboard/src/components/sync/SyncTenantDetailDrawer.tsx` -- lines rendering `d.deviceId`, `d.userId`, `op.operationType`, `op.errorReason`, `c.entityType`, `c.conflictType`, `c.strategy`, `data.tenantPlan` → labels.
- `keevo/dashboard/src/components/sync/SyncHealthTable.tsx`, `SyncOverviewCards.tsx` -- plan column / error state; add `isError`, `isFetching`, `onRetry` props.
- `keevo/dashboard/src/app/monitoring/sync/page.tsx` -- pass the new props.
- `keevo/dashboard/src/hooks/useSyncMonitoring.ts` -- unchanged return shape.
- `keevo/app/lib/core/sync/sync_rejection_messages.dart:12-105` -- read-only reference for French operation and reason labels (copy wording).
- `keevo/dashboard/package.json`, `.github/workflows/dashboard-ci.yml` -- add test script and CI step.
- `_bmad-output/planning-artifacts/epics/epic-9-super-admin-dashboard-toor.md` -- append Story 9.6/9.7/9.8 sections (from their story files).

## Tasks & Acceptance

**Execution:**
- `AdminDeviceInfo.java` + `AdminDeviceInfoDto.java` + `AdminSyncMonitoringService.java` -- add `userName`/`userRole` via the joins above (name = trimmed "first last", null when no employee row) -- human label without exposing ids.
- `AdminSyncMonitoringServiceTest.java`, `AdminSyncMonitoringControllerTest.java` -- mapping test + `jsonPath` contract on all fields -- a DTO rename breaks a test.
- `keevo/dashboard/src/lib/labels.ts` (new) -- `operationLabel`, `rejectionReasonLabel`, `entityTypeLabel`, `conflictTypeLabel`, `strategyLabel`, `planLabel` (`FREE`→« Gratuit », `PREMIUM`→« Premium », `PREMIUM_TRIAL`→« Essai Premium »), `userRoleLabel`, `humanizeCode`; reason labels follow the app's mapping, free text never rendered raw -- single source for French labels, reused by later epic-9 pages.
- `SyncTenantDetailDrawer.tsx`, `SyncHealthTable.tsx`, `SyncOverviewCards.tsx`, `page.tsx`, `types/sync-monitoring.ts` -- labels, device numbering, error/retry/fetching states per matrix.
- `keevo/dashboard` -- add devDependencies `vitest`, `@vitejs/plugin-react`, `jsdom`, `@testing-library/react`, `@testing-library/jest-dom`, `@testing-library/user-event`; `vitest.config.ts` (jsdom, `@/` alias to `src/`), `vitest.setup.ts`; script `"test": "vitest run"`.
- `keevo/dashboard/src/**/__tests__/*.test.ts(x)` (new) -- `labels.test.ts` (known/unknown/free text), `SyncHealthTable.test.tsx` (skeleton, empty, error+retry, row click and Enter key, selected row), `SyncTenantDetailDrawer.test.tsx` (labels, no UUID in DOM text, Escape closes, error states), `auth-context.test.tsx` (a token in `sessionStorage.ks_admin_token` makes the first request carry `Authorization: Bearer`) -- covers the 9-8 deferred harness item.
- `.github/workflows/dashboard-ci.yml` -- run `npm test` after lint/tsc.
- `epic-9-super-admin-dashboard-toor.md` -- add Story 9.6, 9.7, 9.8 sections (user story + ACs summarised from story files) -- closes task 6.1.
- `9-8-monitoring-sync-sante-devices.md` -- tick the routed deferred items and task 6.1, Status `done`.

**Acceptance Criteria:**
- Given the sync page with any backend data, when it renders (list and drawer), then no visible text matches a UUID pattern or an `UPPER_SNAKE_CASE` code.
- Given the dashboard, when `npm test` runs locally and in CI, then all component and label tests pass.
- Given a backend DTO field rename, when the controller test runs, then it fails.

## Spec Change Log

## Review Triage Log

## Verification

**Commands:**
- `cd keevo/dashboard && npm ci && npm run lint && npx tsc --noEmit && npm test && npm run build` -- expected: all succeed
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='AdminSyncMonitoring*Test' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.admin.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
