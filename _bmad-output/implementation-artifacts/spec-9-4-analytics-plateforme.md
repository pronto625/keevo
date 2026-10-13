---
title: '9-4 — Platform analytics (super admin): DAU/MAU, cohorts, top stores, feature adoption, growth, geography'
type: 'feature'
created: '2026-10-13'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-9-context.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** Toor cannot measure usage (FR80: total sales volume, active stores, aggregated product base, geographic distribution, growth curves; epic 9 story 9.4: DAU/MAU, weekly cohort retention, top stores, feature adoption). The backend does not even record when a user is active.

**Approach:** Record one activity row per (user, tenant, WAT day) on authenticated API calls, then expose `/api/v1/admin/analytics/*` endpoints and an « Analytics » dashboard page with tabs: Vue d'ensemble (KPI cards + growth curves + geography), Rétention (weekly tenant cohorts), Top boutiques, Adoption.

## Boundaries & Constraints

**Always:**
- Activity tracking: new Flyway table `public.user_daily_activity (user_id uuid, tenant_id uuid, activity_date date, first_seen_at timestamptz default now(), primary key (user_id, tenant_id, activity_date))`. Written after successful JWT authentication in `JwtAuthFilter` for non-SUPER_ADMIN users through a small `UserActivityRecorder` (in-memory `ConcurrentHashMap` of keys already written today, cleared on date change; `INSERT … ON CONFLICT DO NOTHING` executed asynchronously on a bounded executor); a recorder failure is logged at debug and never affects the request. Rows older than 400 days purged by a daily ShedLock job.
- "Active user" = a row in `user_daily_activity`; DAU = distinct users today (WAT), MAU = distinct users last 30 days, ratio DAU/MAU (%). Active tenant = ≥ 1 active user in the window.
- KPI (`GET /api/v1/admin/analytics/overview`): DAU, MAU, DAU/MAU, total tenants (non-DELETED, excluding `KV-ADMIN`), total stores (active, type STORE), active stores (≥ 1 completed sale in last 30 days), total products (non-archived, summed across schemas), total completed sales (count, all time), total GMV (XAF, all time).
- Growth (`GET /analytics/growth?months=12`): per calendar month: new tenants, cumulative tenants, completed sales count, GMV, MAU of that month.
- Geography (`GET /analytics/geography`): tenants per country derived from the owner phone country calling code (`+237` Cameroun, `+225` Côte d'Ivoire, `+221` Sénégal, `+241` Gabon, `+242` Congo, `+243` RD Congo, `+229` Bénin, `+228` Togo, `+226` Burkina Faso, `+223` Mali, `+227` Niger, `+235` Tchad, `+236` Centrafrique, `+240` Guinée équatoriale, `+33` France, else « Autre »). Documented as the V1 proxy (no structured city in stores).
- Retention (`GET /analytics/retention?weeks=8`): rows = tenant signup weeks (ISO weeks, Monday WAT) of the last 8 weeks plus earlier ones up to 12 rows; columns Semaine 1…8 = % of the cohort's tenants with ≥ 1 active user during week k after signup week; future cells null (« — »); colour green > 60 %, yellow 30–60 %, red < 30 %.
- Top stores (`GET /analytics/top-stores?from&to`, default last 30 days, max 366 days): top 20 stores by GMV: store name, tenant name, GMV, transactions, average basket, growth % vs the previous period of equal length (null when previous GMV is 0 → « Nouveau »). Sortable client-side by any column (asc/desc toggle).
- Adoption (`GET /analytics/adoption`): % of active tenants (last 30 days) using each feature in that window: Ventes (completed sale), Inventaires (inventory session created), Transferts (stock transfer created), Rapports WhatsApp (report delivered via WhatsApp), Alertes stock (stock alert enabled in preferences and a stock-alert notification sent if such a log exists, else preference enabled), Synchronisation hors ligne (`sync_operations_log` row). Horizontal bar chart.
- Cross-schema reads via JdbcTemplate with `TenantSchema.isValid` gating, schema failures skip the tenant; `CurrentActor.requireSuperAdmin()` on every method; the overview may take a few seconds for < 200 tenants (accepted Phase 1), no caching needed beyond React Query `staleTime` 60 s.
- Dashboard `/analytics` page with the 4 tabs, recharts, skeletons, error + « Réessayer », French labels only, no ids, sidebar entry « Analytics ».

**Never:** No third-party analytics SDK; no tracking in the Flutter app; no per-request synchronous DB write in the auth filter.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Same user 50 calls today | authenticated requests | one row for (user, tenant, today) | — |
| Recorder DB failure | insert throws | request served normally | logged debug |
| SUPER_ADMIN call | admin JWT | nothing recorded | — |
| DAU/MAU | 3 users today, 10 in 30 d | DAU 3, MAU 10, ratio 30 % | MAU 0 → « — » |
| Cohort | 4 tenants signed up week W, 3 active in W+1, 1 in W+2 | 75 %, 25 % | future weeks null |
| Top stores growth | previous period GMV 0 | growth null → « Nouveau » | — |
| Phone without known code | +44… | « Autre » | null phone → « Inconnu » |
| Bad range | from > to | 422 `VALIDATION_ERROR` | — |
| Non-admin | OWNER | 403 `FORBIDDEN` | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtAuthFilter.java` -- after tenant/user resolution (around the `TenantContext.setCurrentTenant(tenantId)` block) call the recorder; tenant uuid resolution from schema already cached somewhere (`TenantSchemaSyncService` / tenant lookup) — reuse, don't add a query per request.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/scheduling/` -- ShedLock scheduler pattern for the purge.
- `keevo/backend/src/main/java/com/keevo/admin/operations/**` (9-7) -- cross-schema GMV helpers/structure to mirror.
- `keevo/backend/src/main/java/com/keevo/identity/auth/domain/port/out/OwnerRecipientResolver.java` -- owner phone per schema (geography).
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java` -- tables `sales`, `stores`, `products`, `inventory_sessions`, `stock_transfers`, `reports(delivery_status, report_type)`, `tenant_preferences`, `sync_operations_log`.
- `keevo/dashboard/src/app/monitoring/activity/page.tsx` + `components/operations/*` (9-7), `components/revenue/*` (9-3) -- page/chart patterns; `lib/labels.ts`, `lib/format.ts`.

## Tasks & Acceptance

**Execution:**
- `db/migration/V18__user_daily_activity.sql (next free Flyway version after V17)` -- table + index on `activity_date`.
- `shared/infrastructure/activity/UserActivityRecorder.java` (+ wiring in `JwtAuthFilter`, purge scheduler) -- tracking.
- `admin/analytics/**` (new) -- controller (6 GET endpoints), DTOs, `AdminAnalyticsService`, ports, domain records, `Clock` WAT.
- Tests: recorder (dedupe, async, failure isolation, admin skip), filter test asserting the recorder call, service tests per matrix row, controller tests (403/422 + `jsonPath`), purge test.
- `keevo/dashboard/src/{types/analytics.ts,lib/api/admin-analytics.ts,hooks/useAnalytics.ts,components/analytics/*.tsx,app/analytics/page.tsx}`, `Sidebar.tsx` -- UI + component tests (cohort colours, sort toggle, « Nouveau », no UUID).

**Acceptance Criteria:**
- Given owners and employees using the app today, when Toor opens « Analytics », then DAU counts each of them once and the overview cards show non-zero platform totals consistent with 9-7.
- Given the app in normal use, when authenticated requests flow, then response latency is unaffected (no synchronous insert on the request thread).

## Spec Change Log

## Review Triage Log

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.admin.**,com.keevo.shared.infrastructure.security.**,com.keevo.shared.infrastructure.activity.**,com.keevo.architecture.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/dashboard && npm run lint && npx tsc --noEmit && npm test && npm run build` -- expected: all succeed
