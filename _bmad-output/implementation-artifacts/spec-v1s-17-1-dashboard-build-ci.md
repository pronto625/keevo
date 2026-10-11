---
title: 'v1s-17-1 — Restore the Super Admin dashboard build (9-8 sync page delivered, 9-7 page masked) + dashboard CI'
type: 'bugfix'
created: '2026-10-11'
status: 'done'
baseline_revision: '5bd2b2d2dc264ad8391e5e01319cb1297afd411f'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/planning-artifacts/audit-coherence-2026-10-11.md'
  - '{project-root}/_bmad-output/implementation-artifacts/9-8-monitoring-sync-sante-devices.md'
  - '{project-root}/_bmad-output/implementation-artifacts/9-7-monitoring-activite-operationnelle.md'
warnings: [oversized]
deferred:
  - summary: >-
      Dashboard has no test runner; sync UI states and token restore on refresh are untested.
    evidence: |-
      No `test` script or test deps in keevo/dashboard/package.json; routed to 9-8 '### Deferred items routed 2026-10-11'.
    location: >-
      keevo/dashboard/package.json
    severity: medium
  - summary: >-
      Sync JSON contract not pinned by backend tests (only 3 jsonPath assertions).
    evidence: |-
      AdminSyncMonitoringControllerTest.java:58,85,102; routed to 9-8.
    location: >-
      keevo/backend/src/test/java/com/keevo/admin/sync_monitoring/adapter/in/rest/AdminSyncMonitoringControllerTest.java
    severity: medium
  - summary: >-
      Refetch failures after a success are silent and paused-offline pending shows the error state, because the frozen page.tsx passes neither isError nor isFetching.
    evidence: |-
      sync/page.tsx passes only data/isLoading; routed to 9-8.
    location: >-
      keevo/dashboard/src/components/sync/SyncOverviewCards.tsx
    severity: low
  - summary: >-
      GitHub Actions runs end in startup_failure on origin (Dalois-30/keevo-app); Dashboard CI only effectively runs on origin2.
    evidence: |-
      gh run list -R Dalois-30/keevo-app shows every run startup_failure since 2026-09-17 (pre-existing, repo-wide, ops-owned; known in project memory). Local npm ci/lint/tsc/build remains the real gate.
    severity: medium
---

<intent-contract>

## Intent

**Problem:** `keevo/dashboard` (Next.js 16) fails `next build`, `tsc` and `eslint` (audit C1): `app/monitoring/sync/page.tsx` (9-8) and `app/monitoring/activity/page.tsx` (9-7) import 10 modules that were never written, and `lib/auth/auth-context.tsx` trips `react-hooks/set-state-in-effect`. No CI covers the dashboard, so the breakage went unnoticed and nothing in the dashboard can be deployed.

**Approach:** Repair rather than remove. 9-8's backend (`AdminSyncMonitoringController`, 3 SUPER_ADMIN endpoints) exists, so deliver its frontend against the frozen `sync/page.tsx` contract and un-mark it "coming soon". 9-7 has no backend (`/api/v1/admin/operations/*` does not exist), so replace its page with a clean "coming soon" placeholder (sidebar entry stays disabled) and route the full feature back to story 9-7. Fix the lint error and add a `Dashboard CI` GitHub Actions workflow (npm ci + lint + typecheck + build).

## Boundaries & Constraints

**Always:** consume the 9-8 DTOs field-for-field (`AdminSyncOverviewDto`, `AdminTenantSyncHealthDto`, `AdminSyncTenantDetailDto` + device/failedOp/conflict); go through the shared axios `client` (baseURL `/api/backend`, unwrap `res.data.data`); follow existing styling (`CatalogSummaryCards` cards + `animate-pulse` skeletons, `TenantDetailDrawer` drawer); French UI copy; status badge values are exactly `OK | WARN | ERROR` (`AdminSyncMonitoringService.computeStatus`).

**Never:** no backend change; do not modify `app/monitoring/sync/page.tsx`; no new npm dependency; no fake/mock data for 9-7; do not create story files.

</intent-contract>

## Code Map

- `keevo/dashboard/src/app/monitoring/sync/page.tsx` -- frozen contract: `useSyncMonitoring()` → `{overviewQuery, healthQuery, selectedTenantId, setSelectedTenantId, refresh}`; props `SyncOverviewCards({data,isLoading,onRefresh})`, `SyncHealthTable({data,isLoading,onRowClick,selectedTenantId})`, `SyncTenantDetailDrawer({tenantId,onClose})`.
- `keevo/dashboard/src/app/monitoring/activity/page.tsx` -- 9-7 stub importing 6 missing modules; original contract preserved in 9-7 story Dev Notes and git history (commit e1f91c6).
- `keevo/backend/.../admin/sync_monitoring/adapter/in/rest/AdminSyncMonitoringController.java` + `dto/*.java` -- read-only contract (`/admin/sync/overview`, `/admin/sync/tenants`, `/admin/sync/tenants/{id}/detail`); `ApiResponseWrapper{data}`; Instants serialize as ISO strings.
- `.../sync_monitoring/application/service/AdminSyncMonitoringService.java:160` -- status values `OK|WARN|ERROR` (ERROR: failed>10 or conflicts>5; WARN: failed>0 or push stale >48h).
- `keevo/dashboard/src/lib/api/admin-catalog.ts`, `hooks/useProducts.ts` -- API + React Query pattern to mirror.
- `keevo/dashboard/src/components/catalog/CatalogSummaryCards.tsx`, `components/tenants/TenantDetailDrawer.tsx` -- card + drawer styling to mirror.
- `keevo/dashboard/src/lib/format.ts` -- reuse `relativeTime`, `formatDate`.
- `keevo/dashboard/src/components/layout/Sidebar.tsx:22-26` -- `comingSoon` flags.
- `keevo/dashboard/src/lib/auth/auth-context.tsx:33-39` -- lint error (setState in effect).
- `.github/workflows/backend-ci.yml`, `flutter-ci.yml` -- CI style (push main/develop, PR main/develop, actions/checkout@v4, setup-* with cache).

## Tasks & Acceptance

**Execution:**
- `keevo/dashboard/src/types/sync-monitoring.ts` -- create 6 interfaces mirroring the DTOs + `SyncHealthStatus = "OK" | "WARN" | "ERROR"` -- typed contract.
- `keevo/dashboard/src/lib/api/admin-sync.ts` -- `getSyncOverview`, `getTenantSyncHealth`, `getTenantSyncDetail(tenantId)` -- mirror admin-catalog.
- `keevo/dashboard/src/hooks/useSyncMonitoring.ts` -- two `useQuery` (`staleTime: 30_000`), `selectedTenantId` state, `refresh` invalidating overview, health and detail keys -- frozen page contract.
- `keevo/dashboard/src/components/sync/SyncOverviewCards.tsx` -- 5 KPI cards + "Actualiser" button, skeleton while loading.
- `keevo/dashboard/src/components/sync/SyncHealthTable.tsx` -- table with status badge (green/amber/red), clickable + keyboard-accessible rows, selected row highlight, loading/empty/error states.
- `keevo/dashboard/src/components/sync/SyncTenantDetailDrawer.tsx` -- renders nothing when `tenantId === null`; otherwise conditional `useQuery(["admin-sync-tenant-detail", tenantId], enabled)` and 3 sections.
- `keevo/dashboard/src/app/monitoring/activity/page.tsx` -- replace with a placeholder page inside `AdminLayout` stating the feature is coming (story 9-7).
- `keevo/dashboard/src/components/layout/Sidebar.tsx` -- `Synchronisation` → `comingSoon: false`; `Activité` stays `true`.
- `keevo/dashboard/src/lib/auth/auth-context.tsx` -- read sessionStorage token via lazy `useState` initializer (client-only guard) and set `_token` synchronously, removing the effect -- fixes lint and the first-render unauthenticated request race.
- `.github/workflows/dashboard-ci.yml` -- `Dashboard CI`: triggers push/PR main+develop filtered on `keevo/dashboard/**` and the workflow file; Node 24 with npm cache on `keevo/dashboard/package-lock.json`; `npm ci`, `npm run lint`, `npx tsc --noEmit`, `npm run build`.
- `_bmad-output/implementation-artifacts/9-7-*.md` / `9-8-*.md` + `sprint-status.yaml` -- route remaining 9-7 work; record 9-8 frontend delivery.

**Acceptance Criteria:**
- Given a clean checkout, when running `npm ci && npm run lint && npx tsc --noEmit && npm run build` in `keevo/dashboard`, then all four succeed with 0 errors.
- Given a push/PR touching `keevo/dashboard/**`, when GitHub Actions runs, then the `Dashboard CI` job executes lint, typecheck and build.
- Given the sidebar, when viewed, then "Synchronisation" is a live link and "Activité" remains marked "bientôt".
- Given `/monitoring/sync` with a SUPER_ADMIN session, when queries are pending, then skeleton cards/rows render; when they resolve, then 5 KPI cards and the health table (backend order, status badge OK/WARN/ERROR in green/amber/red) render; null instants render as "—"; an empty list renders "Aucun tenant"; a failed query renders an inline red error without breaking the page.
- Given a health-table row, when clicked (or Enter/Space), then the row is highlighted and the drawer fetches `/admin/sync/tenants/{id}/detail` and shows devices, recent failed ops and recent conflicts (each with an empty state); when × or the backdrop is clicked, then the drawer closes.
- Given `/monitoring/activity`, when opened, then a "bientôt disponible" placeholder renders and no API call is made.

## Spec Change Log

## Review Triage Log

### 2026-10-11 — Review pass
- verdicts: 31 findings — high 0, medium 7, low 18, false 6, maybe-false 0
- findings:
  - Edge Case Hunter:
  - `[low]` `[defer]` Overview error shown while query paused-pending offline (isLoading false, data undefined) — real only offline; fix needs isError from frozen page.tsx → routed 9-8.
  - `[low]` `[defer]` Same for SyncHealthTable — same root cause, routed 9-8.
  - `[low]` `[defer]` Failed refresh with cached overview data silent — same root cause, routed 9-8.
  - `[low]` `[defer]` Failed refresh with cached health data silent — same root cause, routed 9-8.
  - `[medium]` `[patch]` tenantName null renders empty cell (backend nullsLast confirms nullable) — typed `string | null`, fallback "Sans nom".
  - `[medium]` `[patch]` tenantName/tenantPlan typed non-null — now `string | null` in both health and detail types.
  - `[low]` `[patch]` Drawer header "Plan " with null plan — rendered only when tenantPlan truthy.
  - `[low]` `[patch]` Drawer shows error + stale sections together — error gated on `!data`.
  - `[medium]` `[patch]` Drawer not keyboard-dismissable — Escape closes it; role=dialog, aria-modal, aria-labelledby added.
  - `[low]` `[patch]` sessionStorage.getItem may throw in lazy initializer and crash the tree — wrapped in try/catch.
  - `[low]` `[reject]` relativeTime future timestamps show "à l'instant" — pre-existing format.ts helper, clock skew cosmetic.
  - `[low]` `[defer]` Claim mismatch isLoading vs isPending/isError — same root cause as first row, routed 9-8.
  - Verification Gap:
  - `[medium]` `[defer]` Dashboard CI does not start on origin (startup_failure repo-wide) — pre-existing ops issue; recorded in spec deferred, local gate kept.
  - `[medium]` `[defer]` No test for token restore on refresh — added to 9-8 test-harness deferred item.
  - `[medium]` `[defer]` No contract check of sync DTO field names — routed 9-8 (controller jsonPath assertions).
  - `[low]` `[patch]` tenantName nullability (Other finding) — same fix as above.
  - Blind Hunter:
  - `[medium]` `[patch]` tenantName/tenantPlan nullable — same patch.
  - `[low]` `[patch]` Drawer error + stale data — same patch.
  - `[low]` `[patch]` Drawer modal basics — Escape + dialog roles (focus trap not added: low value vs complexity).
  - `[low]` `[patch]` auth-context change untraced — recorded in this spec's Auto Run Result.
  - `[false]` `[reject]` v1s-17-1 bookkeeping missing — done at finalization (sprint-status done + epic brief).
  - `[false]` `[reject]` CI never run/"must stay green" — same as Verification Gap CI row; the 9-7 item is a requirement on future work, not a claim.
  - `[low]` `[patch]` Workflow hardening — added `permissions: contents: read`, `concurrency` cancel-in-progress, `timeout-minutes: 15`; branch triggers kept identical to backend-ci (main/develop) by design.
  - `[false]` `[reject]` 9-8 at review with open work — `review` is accurate: tasks 1-5 done, 6.1 and harness explicitly routed; test harness owned by 9-8 as first consumer.
  - `[low]` `[reject]` Error states without retry/detail — "Actualiser" is always visible above cards; richer errors need page.tsx changes (frozen).
  - `[low]` `[reject]` Refresh button without spinner/lastUpdated — cosmetic; needs isFetching from frozen page.
  - `[low]` `[patch]` "(7 derniers jours)" header misleading for devices/last push — header now "Vue d'ensemble", 7j qualifier moved to failed ops and conflicts cards.
  - `[false]` `[reject]` e1f91c6 vs d5a1dc9 mismatch — `git rev-parse e1f91c6:…/activity/page.tsx` = d5a1dc9, same blob.
  - Intent Alignment:
  - `[low]` `[patch]` Deferred headings not using `### Deferred items routed 2026-10-11` — both story files rewritten to the convention and item format.
  - `[false]` `[reject]` No runtime evidence for 9-8 — intent ACs are lint/tsc/build (all pass); runtime/contract tests routed to 9-8.
  - `[false]` `[reject]` auth-context touched beyond scope — required by the intent's "npm run lint passes" AC (react-hooks/set-state-in-effect error), documented in spec.

## Design Notes

- 9-7 masked (not deleted from history): its route keeps resolving to a placeholder so the sidebar/links never 404; the original stub contract lives in the 9-7 story file. Implementing 9-7 needs a new backend module + product decisions (GMV definition, inactivity threshold) — out of this story's scope.
- Node 24 in CI matches the local toolchain used to verify (v24.13.0); Next 16 requires Node >= 20.9.
- No dashboard test runner exists (no `test` script, no testing deps) and adding one would require new devDependencies (out of scope, tight disk); UI behaviors are therefore expressed as ACs verified by typecheck/build + code review instead of an I/O matrix. Setting up a dashboard test harness is routed to story 9-8's deferred list.

## Verification

**Commands:**
- `cd keevo/dashboard && npm run lint` -- expected: 0 problems
- `cd keevo/dashboard && npx tsc --noEmit` -- expected: exit 0
- `cd keevo/dashboard && npm run build` -- expected: success, routes `/monitoring/sync` and `/monitoring/activity` listed

## Auto Run Result

Status: done

**Summary:** Dashboard builds again. 9-8 sync monitoring frontend delivered against the existing `AdminSyncMonitoringController`; 9-7 activity page replaced by a "bientôt disponible" placeholder (remaining work routed to 9-7); lint error in `auth-context.tsx` fixed (lazy initializer, also removes the first-request-without-token race on refresh); `Dashboard CI` workflow added.

**Files:**
- `.github/workflows/dashboard-ci.yml` -- new: npm ci, lint, tsc --noEmit, build (Node 24, npm cache, read-only perms, concurrency, 15 min timeout).
- `keevo/dashboard/src/types/sync-monitoring.ts` -- new: DTO mirrors + `OK|WARN|ERROR`.
- `keevo/dashboard/src/lib/api/admin-sync.ts` -- new: 3 API calls.
- `keevo/dashboard/src/hooks/useSyncMonitoring.ts` -- new: frozen page contract.
- `keevo/dashboard/src/components/sync/{SyncOverviewCards,SyncHealthTable,SyncTenantDetailDrawer}.tsx` -- new components.
- `keevo/dashboard/src/app/monitoring/activity/page.tsx` -- placeholder.
- `keevo/dashboard/src/components/layout/Sidebar.tsx` -- Synchronisation live.
- `keevo/dashboard/src/lib/auth/auth-context.tsx` -- lint fix + guarded sessionStorage read.
- 9-7 / 9-8 story files, sprint-status.yaml, epics-remediation-audit.md -- routing and bookkeeping.

**Review:** 31 findings; 14 rows patched (8 distinct fixes), 8 rows deferred (routed to 9-8 or recorded as pre-existing ops issue), 9 rows rejected with reasons above (6 false). Follow-up review: true (rule: two medium entries patched on a first pass) — unverified risk: the nullable tenant name/plan fallbacks and the drawer Escape/dialog behaviour were only checked by tsc/build, never in a browser against a live backend. Patched entries by verdict: medium 2, low 6.

**Verification:** `npm run lint` 0 problems; `npx tsc --noEmit` exit 0; `npm run build` success (routes /monitoring/activity, /monitoring/sync, /monitoring/products, /tenants, /login); workflow YAML parses. Implementation agent also ran `npm ci` + all three on a clean copy. No dashboard test suite exists.

**Residual risks:** no runtime check against a live backend; Actions startup_failure on origin means CI only runs on origin2; no dashboard tests until 9-8 harness item lands.
