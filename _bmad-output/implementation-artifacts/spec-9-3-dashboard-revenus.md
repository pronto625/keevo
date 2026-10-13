---
title: '9-3 — Revenue dashboard (super admin): MRR/ARR, paying tenants, conversion, churn, CSV'
type: 'feature'
created: '2026-10-13'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-9-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-9-2-activation-plan-payant.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Toor has no view of Keevo's revenue (FR79: MRR, paying vs free tenants, conversion rate, monthly churn; epic 9 story 9.3). Since 9-2, every manual Premium activation records a payment (`public.tenant_payments`) and every plan change an event (`public.tenant_plan_events`), but nothing aggregates them.

**Approach:** A « Revenus » dashboard page backed by `/api/v1/admin/revenue/*` endpoints computed from payments and plan events: KPI cards (MRR, ARR, paying tenants, free tenants, trial tenants, trial→Premium conversion rate, churn rate of the month, new paying tenants of the month), a 12-month MRR bar chart with tooltip, a churn table for the selected month with CSV export, a date-range filter defaulting to the current month.

## Boundaries & Constraints

**Always:**
- Definitions (derived from FR79 and the 9-2 data model; XAF integers, WAT calendar):
  - Monthly value of a payment = `amount_xaf × 30 / days(period_start..period_end inclusive)`, rounded to the XAF.
  - MRR at instant T = sum of monthly values of payments whose period covers T's WAT date, one payment per tenant (the latest `paid_at` among those covering T), only for tenants not `DELETED`. MRR of a past month = MRR at its last day; of the current month = MRR today. ARR = MRR × 12.
  - Paying tenants at T = tenants counted in MRR at T; free / trial tenants = current plan counts read from tenant schemas (ACTIVE/SUSPENDED/DELETION_PENDING tenants, not the `KV-ADMIN` system tenant).
  - New paying tenants of a month = tenants whose first-ever payment `paid_at` is in that month. Churned tenants of a month = tenants with a `tenant_plan_events` row `PREMIUM → FREE` (cause EXPIRY or ADMIN_DOWNGRADE) in that month and no later return to PREMIUM within the same month. Churn rate = churned / paying tenants at the first day of the month (0 when denominator 0, shown « — »).
  - Trial→Premium conversion rate (selected range) = tenants whose first PREMIUM activation falls in the range and who had a REGISTRATION/PREMIUM_TRIAL history / tenants whose trial ended (trial expiry or first activation) in the range.
  - Range filter `from`/`to` (dates, inclusive, max 24 months, default current month) drives: new paying, churn list and rate, conversion, revenue collected (sum of `amount_xaf` paid in range). KPI MRR/ARR are always « aujourd'hui »; trend vs previous month shown as ▲ +X % (green) / ▼ −X % (red) / « — ».
  - 12-month chart: last 12 calendar months ending with the current one; each bar: MRR, paying tenants, new conversions, churned.
- Endpoints (all `CurrentActor.requireSuperAdmin()`): `GET /api/v1/admin/revenue/summary?from&to`, `GET /revenue/trend` (12 months), `GET /revenue/churn?month=YYYY-MM`, `GET /revenue/churn/export?month=YYYY-MM` (CSV, `text/csv; charset=UTF-8` with BOM, filename `keevo-churn-YYYY-MM.csv`, columns « Entreprise;Fin du plan;Ancienneté payante (mois);CA des 30 jours avant la fin (XAF) », French labels, `;` separator like the 9-6 catalog export, no ids).
- Churn row: tenant name (« Sans nom » if null), plan end date (event date), tenure = months between first payment `period_start` and churn date (1 decimal), last CA = completed-sales GMV of the 30 days before churn from the tenant schema (skip/0 if schema unavailable).
- Dashboard page `/revenus` (`app/revenus/page.tsx`), sidebar entry « Revenus » under the main group, recharts `BarChart`, manual « Actualiser » button (no auto refresh), skeletons, error + « Réessayer », labels from `lib/labels.ts`, amounts `formatXAFFull`. No technical data on screen or in CSV.

**Never:** No currency other than XAF; no write endpoint; no estimation from plan prices (there is no price list — payments are the source of truth).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| One payment 30 days | 15 000 XAF, period 30 days covering today | MRR 15 000, ARR 180 000, paying 1 | — |
| 3-month payment | 45 000 XAF over 91 days | monthly value 14 835 | — |
| Two payments covering today | renewal paid early | only the latest paid_at counted | — |
| Expired payment | period ended yesterday | not in MRR | — |
| Churn | PREMIUM→FREE EXPIRY on 2026-09-12, paying at 2026-09-01 = 4 | churned 1, rate 25 % | — |
| Churn then return same month | FREE then PREMIUM again in month | not churned | — |
| No data | empty tables | all zeros, chart 12 empty months, churn table « Aucun tenant perdu ce mois-ci » | — |
| Bad range | from > to, > 24 months, bad month format | 422 `VALIDATION_ERROR` | inline error |
| Non-admin | OWNER | 403 `FORBIDDEN` | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/resources/db/migration/V17__tenant_payments_plan_reminders.sql` -- tables from 9-2 (read-only here).
- `keevo/backend/src/main/java/com/keevo/admin/operations/**` (9-7) and `admin/sync_monitoring/**` -- module structure and cross-schema GMV helper to mirror.
- `keevo/backend/src/main/java/com/keevo/admin/catalog/adapter/in/rest/AdminCatalogController.java` -- CSV export pattern (BOM, separator, escaping `escapeCsv`).
- `keevo/dashboard/src/app/monitoring/activity/page.tsx`, `components/operations/*` (9-7) -- page/hook/chart patterns; `components/layout/Sidebar.tsx`; `lib/labels.ts`; `lib/format.ts`.

## Tasks & Acceptance

**Execution:**
- `keevo/backend/src/main/java/com/keevo/admin/revenue/**` (new) -- controller, DTOs, `AdminRevenueService` (JdbcTemplate over `public.tenant_payments` / `public.tenant_plan_events` / `public.tenants` + per-schema plan counts and GMV), domain records, ports, `Clock` injected (WAT).
- `keevo/backend/src/test/java/com/keevo/admin/revenue/**` (new) -- service tests per matrix row (monthly value rounding, latest payment, churn return, rates, range validation), controller tests (403/422, `jsonPath` contract, CSV headers/filename/BOM/no UUID), one Testcontainers integration test seeding payments + events and asserting summary/trend.
- `keevo/dashboard/src/{types/revenue.ts,lib/api/admin-revenue.ts,hooks/useRevenue.ts,components/revenue/{RevenueKpiCards,MrrTrendChart,ChurnTable,DateRangeFilter}.tsx,app/revenus/page.tsx}` (new), `components/layout/Sidebar.tsx` -- UI.
- `keevo/dashboard/src/components/revenue/__tests__/*.test.tsx` -- KPI trend arrow/colour, churn empty state, CSV link/filename, no UUID.

**Acceptance Criteria:**
- Given payments recorded through 9-2, when Toor opens « Revenus », then the MRR card equals the sum of monthly values of payments covering today and the chart shows 12 months.
- Given a month with churn, when Toor clicks « Exporter CSV », then `keevo-churn-YYYY-MM.csv` downloads with French columns and no identifier.

## Spec Change Log

## Review Triage Log

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.admin.**,com.keevo.architecture.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='AdminRevenue*IntegrationTest' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures (Docker)
- `cd keevo/dashboard && npm run lint && npx tsc --noEmit && npm test && npm run build` -- expected: all succeed
