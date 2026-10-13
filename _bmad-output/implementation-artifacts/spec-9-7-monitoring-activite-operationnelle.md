---
title: '9-7 — Operational activity monitoring (super admin): GMV, trend, tenant activity, inactive tenants'
type: 'feature'
created: '2026-10-13'
status: 'ready-for-dev'
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

## Verification

**Commands:**
- `cd keevo/dashboard && npm run lint && npx tsc --noEmit && npm test && npm run build` -- expected: all succeed
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='AdminOperations*Test' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.admin.**,com.keevo.architecture.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
