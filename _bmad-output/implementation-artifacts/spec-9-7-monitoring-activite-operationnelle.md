---
title: '9-7 — Operational activity monitoring (super admin): GMV, trend, tenant activity, inactive tenants'
type: 'feature'
created: '2026-10-13'
status: 'done'
baseline_revision: '7668dba2ad7fd6ac0bbaee9247c2c37295e5c0d5'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-9-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/9-7-monitoring-activite-operationnelle.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** The super-admin "Activité" page is a « bientôt disponible » placeholder and no `/api/v1/admin/operations/*` endpoint exists: Toor cannot see platform GMV, its trend, per-tenant activity or which tenants went dormant (churn risk) without querying the database.

**Approach:** Implement story file `9-7-monitoring-activite-operationnelle.md` as written (AC1–AC8, its Dev Notes and its routed items): new backend module `com.keevo.admin.operations` (4 SUPER_ADMIN endpoints, cross-schema JdbcTemplate), and the dashboard page restored from `e1f91c6` with its hook, API client, types and 5 components; sidebar entry enabled.

## Boundaries & Constraints

**Always:** Definitions (PO defaults written in the story, kept): GMV = `SUM(total_amount)` of `sales` with `status = 'COMPLETED'`; order count = count of those sales; the period applies to `COALESCE(occurred_at, created_at)`; periods `TODAY | 7D | 30D | 90D | CUSTOM` computed in `Africa/Douala` (WAT) day boundaries; `CUSTOM` passes `from`/`to` (ISO dates, inclusive, max 366 days, `from <= to`, else 422 `VALIDATION_ERROR`). Last activity = MAX(latest sale time, latest `audit_log.occurred_at`); inactive = last activity null or older than 14 days. Active tenants = tenants with ≥ 1 completed sale in the period; new tenants = tenants created in the period. Only `public.tenants` with `status = 'ACTIVE'` and not the `KV-ADMIN` system tenant (`id 00000000-0000-0000-0000-000000000000`), schema names gated by `TenantSchema.isValid` before interpolation; a tenant whose schema query fails is skipped. Each method guarded by `CurrentActor.requireSuperAdmin()` (non-admin → 403 `FORBIDDEN`). Tenant table: 25 rows/page sorted by GMV desc then name. No technical data rendered: plan via `planLabel` from `keevo/dashboard/src/lib/labels.ts` (created by 9-8; create it if absent), tenant shown by name (« Sans nom » if null), no tenant id/schema visible; amounts formatted XAF. Fix the setState-during-render in the restored page. Dashboard `npm run lint`, `npx tsc --noEmit`, `npm test`, `npm run build` green.

**Never:** No new chart library (use recharts); no write endpoint; no change to tenant-side reporting code.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Overview 7D | 2 tenants, sales COMPLETED 1000+500 and CANCELLED 300 in window, one sale 10 days ago | totalGmv 1500, totalOrders 2, activeTenantsCount = tenants with a completed sale in window | — |
| PENDING_VALIDATION sale | draft sale in window | not counted | — |
| Trend 7D | sales on 2 of 7 days | 7 points, one per WAT day, missing days gmv 0 / orderCount 0 | — |
| CUSTOM invalid | from > to, or > 366 days, or missing | 422 `VALIDATION_ERROR` | dashboard shows inline error, no crash |
| Tenant without schema / broken schema | schema query throws | tenant skipped, others returned | — |
| Inactive | tenant last sale 20 days ago, last audit 15 days ago | listed with daysSinceLastActivity 15 | — |
| Never active | no sale, no audit | listed, lastActivityAt null → « Jamais » | — |
| Non-admin | OWNER JWT | 403 `FORBIDDEN` on all 4 endpoints | — |

</intent-contract>

## Code Map

- `keevo/dashboard/src/app/monitoring/activity/page.tsx` -- placeholder now; restore from `git show e1f91c6:keevo/dashboard/src/app/monitoring/activity/page.tsx` (props contract in story Dev Notes); move `lastUpdated` to an effect / `dataUpdatedAt`.
- `keevo/dashboard/src/components/layout/Sidebar.tsx:24` -- `comingSoon: true` → false.
- `keevo/backend/src/main/java/com/keevo/admin/sync_monitoring/**` -- structure to mirror (controller, dto, service, domain model, ports); `loadActiveTenantSchemas()` pattern with `TenantSchema.isValid`.
- `keevo/backend/src/main/java/com/keevo/admin/tenant/application/service/AdminTenantService.java` -- `lastActivityAt` definition used by 9-1.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java:481-545` -- `sales` columns (`status`, `occurred_at`, `created_at`, `total_amount`); `audit_log.occurred_at`.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/SecurityConfig.java:110` -- `/api/v1/admin/**` already SUPER_ADMIN.
- `keevo/dashboard/src/lib/api/admin-catalog.ts`, `hooks/useProducts.ts`, `components/catalog/*` -- frontend patterns; `lib/format.ts` (`formatXAF`, `formatXAFFull`, `relativeTime`, `formatDate`).
- `keevo/dashboard/vitest.config.ts` -- test harness from 9-8.

## Tasks & Acceptance

**Execution:**
- `keevo/backend/src/main/java/com/keevo/admin/operations/**` (new) -- controller (4 GET endpoints of story AC1–AC4, `period`, `from`, `to`, `page`, `pageSize` params), DTO records, `AdminOperationsService` (JdbcTemplate, one aggregated query per tenant schema per endpoint, WAT boundaries via a `Clock`), domain records, use-case ports -- AC1–AC4.
- `keevo/backend/src/test/java/com/keevo/admin/operations/**` (new) -- service test with mocked JdbcTemplate per matrix rows (period bounds, CUSTOM validation, skip on failure, inactivity threshold, sorting/pagination) + controller test (`standaloneSetup`, 403 / 200, `jsonPath` on every field the dashboard reads).
- `keevo/dashboard/src/types/operations.ts`, `lib/api/admin-operations.ts`, `hooks/useOperations.ts`, `components/operations/{PeriodSelector,OperationsKpiCards,GmvTrendChart,TenantActivityTable,InactiveTenantsSection}.tsx` (new) -- story AC5–AC7.
- `keevo/dashboard/src/app/monitoring/activity/page.tsx`, `components/layout/Sidebar.tsx` -- restore page, enable menu entry.
- `keevo/dashboard/src/components/operations/__tests__/*.test.tsx` (new) -- PeriodSelector (custom dates shown only for CUSTOM), TenantActivityTable (pagination, « Sans nom », plan label, no UUID), InactiveTenantsSection (« Jamais », days), KPI skeleton.
- `_bmad-output/implementation-artifacts/9-7-monitoring-activite-operationnelle.md` -- tick tasks and routed items, Status `done`, completion notes stating the defaults kept.

**Acceptance Criteria:**
- Given the dashboard logged in as super admin, when « Activité » is opened from the sidebar, then the 4 sections load real data from the 4 endpoints and the period selector re-queries them.
- Given any data, when the page renders, then no UUID, schema name or raw plan code is visible.

## Spec Change Log

## Review Triage Log

### 2026-10-13 — Review pass (follow-up)
- verdicts: 30 findings — high 0, medium 0, low 9, false 11, maybe-false 0 (+ intent rows descriptive)
- findings:
  - `[false]` `[reject]` (blind/edge) new tenants listed as dormant — carried spec decision.
  - `[false]` `[reject]` (blind/edge) plan may be an expired subscription — carried (one row per tenant).
  - `[low]` `[reject]` (blind) partial totals without a flag — carried.
  - `[low]` `[reject]` (blind) per-request loops, no cache — carried Phase 1.
  - `[low]` `[reject]` (blind) use-case ports unused by the controller — matches the existing admin modules' style; refactor out of scope.
  - `[low]` `[patch]` (blind/gap/edge/intent) client misses the 2000..9999 year bound — `yearBoundError` in `customRangeError`; test.
  - `[false]` `[reject]` (blind) GMV ignores refunds — refunds are sale cancellations (status CANCELLED, excluded).
  - `[low]` `[reject]` (blind) page clamp inside queryFn — lint forbids setState in effects here; documented.
  - `[low]` `[reject]` (blind) inactive list unbounded — Phase 1 volumes.
  - `[low]` `[reject]` (blind) weak tests (WAT boundary, exclusions, roles) — carried.
  - `[low]` `[patch]` (edge) skipped tenant still counted as new — counted only when its row was read; unit test + IT assertion updated.
  - `[low]` `[patch]` (edge) dates shown in browser time zone — Africa/Douala formatting; test.
  - `[low]` `[patch]` (gap) API client untested — `admin-operations.test.ts`.
  - `[low]` `[patch]` (gap) sidebar entry not asserted — Sidebar test.
  - `[false]` `[reject]` (intent) readings — descriptive.


### 2026-10-13 — Review pass
- verdicts: 41 findings — high 0, medium 5, low 18, false 9, maybe-false 2 (+ intent rows descriptive)
- findings:
  - `[low]` `[reject]` (blind) tenants whose per-schema query fails vanish without a partial flag — provisioning gate (v1s-13-12) makes broken schemas rare; resource failures now fail the request (edge row 1), a `partial` field would add API surface for a rare case.
  - `[low]` `[reject]` (blind) broken schema dropped from the inactive list — same rarity; listing it as « Jamais active » would be wrong too; WARN log added for filtered schemas.
  - `[false]` `[reject]` (blind) brand-new tenants listed as inactive — spec matrix row « Never active → listed, Jamais »; a never-activated signup is the onboarding risk Toor wants to see.
  - `[false]` `[reject]` (blind) plan can be an expired/cancelled subscription — one subscription row per tenant (`Subscription` javadoc), expiry downgrades it nightly.
  - `[low]` `[reject]` (blind) last activity counts non-completed sales — any recorded sale is usage activity; GMV stays COMPLETED-only as specified.
  - `[low]` `[reject]` (blind) 4×N queries, in-memory pagination, no cache — accepted Phase 1 (< 200 tenants) in the epic context.
  - `[medium]` `[patch]` (blind) incomplete CUSTOM range reads as « no activity » — hook exposes a range-incomplete flag; sections show « Choisissez une période valide… ».
  - `[medium]` `[patch]` (blind) failed refresh unnoticed in chart/table/inactive — `StaleDataWarning` when data && isError.
  - `[low]` `[patch]` (blind) page never clamped — clamped to totalPages − 1 on data.
  - `[low]` `[patch]` (blind) tooltip dead branch, orderCount hidden — tooltip shows GMV and number of sales.
  - `[medium]` `[patch]` (blind) missing tests chart/hook/page — `GmvTrendChart.test.tsx`, `activity/__tests__/page.test.tsx`.
  - `[maybe-false]` `[reject]` (blind) integration test time-dependent — seeds are « yesterday » and « 10 days ago » against a 7D window: both stay on their side at any hour; would need a 23:30 UTC seed to matter (low if true).
  - `[low]` `[reject]` (blind) 14 days / 366 days duplicated in UI text — constants change only by PO decision; text update is a one-liner then.
  - `[false]` `[reject]` (blind) story/spec/sprint status disagree — orchestrator finalizes them in this commit.
  - `[false]` `[reject]` (blind) PO decisions ticked without sign-off — the story file states the defaults; the spec records them as decided from the docs (user instruction: decide from the docs).
  - `[maybe-false]` `[reject]` (blind) GMV gross vs net of discount — `sales.total_amount` is the amount after discount (`Sale` computes total − discount); low if true.
  - `[medium]` `[patch]` (edge) DB outage answered 200 with zeros — only per-tenant SQL/schema errors are skipped; resource/connection failures rethrown; tests.
  - `[low]` `[reject]` (edge) broken tenant hidden from dormant list — same as blind row 2.
  - `[low]` `[reject]` (edge) auxiliary subquery failure drops a tenant row while overview counts it — same rarity reasoning.
  - `[low]` `[patch]` (edge) warehouses counted as stores — `type = 'STORE'`.
  - `[false]` `[reject]` (edge) lapsed subscription shown — same as blind row 4.
  - `[false]` `[reject]` (edge) new tenant flagged inactive — same as blind row 3.
  - `[low]` `[patch]` (edge) out-of-range CUSTOM year returns zeros — years outside 2000–9999 → 422.
  - `[low]` `[reject]` (edge) backend page ≥ totalPages returns empty — UI clamps; an empty page past the end is a valid answer.
  - `[low]` `[patch]` (edge) invalid schema filtered silently — WARN log with tenant id.
  - `[false]` `[reject]` (edge) suspended tenants not counted as new — spec: only ACTIVE tenants are considered.
  - `[low]` `[patch]` (edge) UI page past totalPages — same as blind row 9.
  - `[medium]` `[patch]` (edge) disabled queries read as empty data — same as blind row 7.
  - `[medium]` `[patch]` (edge) stale table/chart/inactive without warning — same as blind row 8 (3 rows).
  - `[low]` `[patch]` (edge) `2026-02-31` accepted client-side — ISO round-trip check.
  - `[low]` `[patch]` (gap) plan subquery not pinned on real SQL — exact `PREMIUM_TRIAL` assertion.
  - `[medium]` `[patch]` (gap) page/hook wiring untested — page test.
  - `[low]` `[patch]` (gap) GmvTrendChart states untested — chart test.
  - `[false]` `[reject]` (intent) R1–R4 readings and surfaces — descriptive; 403 at filter chain is pinned by existing `SecurityConfig` admin-path tests (v1s-12-3); page wiring gap patched; server 422 never reaches the UI because the client validates the same rules.

## Verification

**Commands:**
- `cd keevo/dashboard && npm run lint && npx tsc --noEmit && npm test && npm run build` -- expected: all succeed
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='AdminOperations*Test' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.admin.**,com.keevo.architecture.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures

## Auto Run Result

Status: done

**Summary:** Story 9-7 delivered. Backend module `com.keevo.admin.operations` — 4 SUPER_ADMIN GET endpoints (overview, trend, tenants, inactive-tenants) with WAT day windows, `TODAY|7D|30D|90D|CUSTOM` (422 on invalid ranges), COMPLETED-sales GMV, 14-day inactivity, per-tenant SQL errors skipped but DB/connection failures surfaced, plan read from the tenant subscription. Dashboard « Activité » page restored and enabled: KPI cards, recharts GMV trend (tooltip GMV + ventes), paginated tenant activity, dormant tenants, period selector with client-side range validation, « Choisissez une période valide… » state, stale-data warnings with retry, page clamping.

**Files changed:** `keevo/backend/src/main/java/com/keevo/admin/operations/**` (new), `keevo/backend/src/test/java/com/keevo/admin/operations/**` (unit, controller, Testcontainers integration); dashboard `types/operations.ts`, `lib/api/admin-operations.ts`, `lib/period.ts`, `lib/labels.ts` (`periodLabel`), `hooks/useOperations.ts`, `components/operations/*` (+ `PeriodRequired.tsx`), `app/monitoring/activity/page.tsx`, `components/layout/Sidebar.tsx`, tests; story file and epic 9.7 status.

**Review:** 41 findings — patched 5 medium entries (DB outage as zeros, incomplete range as empty data, missing stale warnings, page/hook wiring test, missing chart tests) and 9 low; deferred 0; rejected 22 (false 9, maybe-false 2, low with reasons).

**Follow-up review recommended:** true — 5 medium entries patched; unverified risk: the classification of which `DataAccessException` subtypes skip a tenant vs fail the request was only unit-tested with mocks, not against a real connection loss.

**Verification:** dashboard lint, tsc, `npm test` (13 files, 71 tests), build — green. Backend `com.keevo.admin.**,com.keevo.architecture.**` — 112 tests, 0 failures/errors (incl. `AdminOperationsServiceIntegrationTest`).

**Residual risks:** per-tenant loop is O(tenants) per request (accepted Phase 1); components import error states from `components/sync`.

### Follow-up pass 2026-10-13

**Summary:** Client range check now enforces the backend's year bounds, tenants skipped for their own SQL error are no longer counted as new, dates shown in Douala time, API client and sidebar entry pinned by tests.

**Review:** 30 findings — patched 5 low; rejected 25 (carried or false).

**Follow-up review recommended:** false — follow-up pass, no high patched.

**Verification:** dashboard green (see 9-8 follow-up); `AdminOperationsServiceTest` 41 and `AdminOperationsServiceIntegrationTest` green (assertion updated to the new count rule).
