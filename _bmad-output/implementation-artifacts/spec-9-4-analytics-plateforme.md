---
title: '9-4 — Platform analytics (super admin): DAU/MAU, cohorts, top stores, feature adoption, growth, geography'
type: 'feature'
created: '2026-10-13'
status: 'done'
baseline_revision: '59cc78e211808089905c41edcb068b6141de765d'
review_loop_iteration: 0
followup_review_recommended: true
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

### 2026-10-13 — Review pass
- verdicts: 34 findings — high 0, medium 7, low 20, false 3, maybe-false 0 (+ intent rows descriptive)
- findings:
  - `[medium]` `[patch]` (blind) purge (400 d) shorter than 24-month growth — retention 760 days.
  - `[medium]` `[patch]` (blind) survivorship bias in growth/retention — history built from all tenants ever created (KV-ADMIN excluded).
  - `[low]` `[patch]` (blind) DAU/MAU include the system tenant — platform join.
  - `[low]` `[patch]` (blind) two meanings of MAU — distinct French labels (30 derniers jours vs du mois).
  - `[low]` `[patch]` (blind) in-progress week counted — null until the week ends, legend.
  - `[low]` `[reject]` (blind) background/sync calls counted as activity — spec definition: « a user who made ≥ 1 authenticated API call »; a sync push is the user's app in use.
  - `[low]` `[reject]` (blind) partial results unreported — broken schemas are rare (provisioning gate); WARN logs; same decision as 9-7.
  - `[low]` `[reject]` (blind) no cost control on cross-schema scans — Phase 1 (< 200 tenants) accepted by the spec; React Query staleTime 60 s.
  - `[low]` `[patch]` (blind) rethrow coverage misses retention/adoption — added.
  - `[low]` `[reject]` (blind) adoption tenant dropped from denominator when its query fails — same rarity reasoning; logged.
  - `[low]` `[patch]` (blind) phone formats without `+` — `00`/bare international normalized.
  - `[low]` `[patch]` (blind) warehouses in top stores — `type = 'STORE'`.
  - `[low]` `[patch]` (blind) `#` rank after client sort — server GMV rank kept.
  - `[medium]` `[patch]` (blind) English labels « Analytics », « Ratio DAU / MAU » — French labels (« Statistiques », « Taux d'engagement »…).
  - `[low]` `[patch]` (blind) stale DateRangeFilter docs — generic.
  - `[low]` `[patch]` (blind) tabs a11y — ids, aria-controls/labelledby, arrow keys; URL tab state rejected (not required).
  - `[medium]` `[patch]` (blind) test-only 5-arg JwtAuthFilter constructor can silently disable tracking — removed; tests pass a recorder.
  - `[medium]` `[patch]` (edge) purge vs growth — same as blind row 1.
  - `[low]` `[patch]` (edge) in-progress week — same as blind row 5.
  - `[low]` `[reject]` (edge) one feature table failing drops the tenant — same as blind row 10.
  - `[low]` `[patch]` (edge) future-dated sales counted — upper bound start of tomorrow.
  - `[low]` `[reject]` (edge) totalTenants includes skipped tenants — total is a count of public tenants; a skipped schema still is a tenant.
  - `[false]` `[reject]` (edge, claim) totals inconsistent with 9-7 — spec defines analytics over non-DELETED tenants, 9-7 over ACTIVE tenants; different, documented scopes (the AC says « consistent », not equal).
  - `[medium]` `[patch]` (gap) DISTINCT users never exercised on real SQL — multi-day/multi-tenant user seeded.
  - `[low]` `[patch]` (gap) null contract asserted with doesNotExist — key present and null.
  - `[low]` `[patch]` (gap) « Actualiser » untested — page test.
  - `[low]` `[patch]` (gap other) client year bounds — added.
  - `[false]` `[reject]` (intent) recording tested in pieces — filter → recorder wiring now forced by the single constructor; real insert covered by `UserActivityRecorderPostgresIntegrationTest`.
  - `[false]` `[reject]` (intent) readings a–f — retention `weeks` = columns (12 rows) keeps the spec's « up to 12 rows »; WhatsApp recipient phone is the owner phone resolver; SENT reports are WhatsApp/email deliveries (channel column absent) — documented choices.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.admin.**,com.keevo.shared.infrastructure.security.**,com.keevo.shared.infrastructure.activity.**,com.keevo.architecture.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/dashboard && npm run lint && npx tsc --noEmit && npm test && npm run build` -- expected: all succeed

## Auto Run Result

Status: done

**Summary:** Story 9-4 delivered. Authenticated API activity is recorded once per (user, tenant, WAT day) in `public.user_daily_activity` (V18) by an async, deduplicated `UserActivityRecorder` wired into `JwtAuthFilter` (SUPER_ADMIN skipped, failures isolated, purge after 760 days under ShedLock). Module `com.keevo.admin.analytics` serves overview (active users today / 30 days, engagement rate, tenants, stores, active stores, products, sales, GMV), growth (up to 24 months, history including since-deleted tenants), geography (owner phone country code, normalized), weekly retention cohorts (finished weeks only), top 20 stores (STORE type, growth vs previous period, « Nouveau ») and feature adoption. Dashboard « Statistiques » page with 4 accessible tabs, French labels, recharts, sortable top-stores table keeping the GMV rank.

**Files changed:** backend `V18__user_daily_activity.sql`, `shared/infrastructure/activity/{UserActivityRecorder,UserActivityPurgeJob}.java`, `JwtAuthFilter` (single constructor with recorder), `TenantSchemaSyncService` global tables, `admin/analytics/**`; tests (unit, controller, 2 Postgres ITs, filter, purge, `SchedulerLockCoverageTest`); dashboard `types/analytics.ts`, `lib/api/admin-analytics.ts`, `hooks/useAnalytics.ts`, `components/analytics/*`, `app/analytics/page.tsx`, `lib/{labels,period}.ts`, `components/revenue/DateRangeFilter.tsx`, `Sidebar.tsx` + tests.

**Review:** 34 findings — patched 7 medium entries and 15 low (grouped); deferred 0; rejected 12 (false 3, low with reasons).

**Follow-up review recommended:** true — 7 medium entries patched; unverified risk: history-vs-current tenant scoping (growth/retention include DELETED, current KPIs exclude them) is unit-tested and covered by one IT seed, not on production-like data.

**Verification:** dashboard lint, tsc, `npm test` (31 files, 162 tests), build — green. Backend: full chunked regression after the filter change (13 packages, 3 060 tests, 0 failures) and after the review patches shared 525, admin 299, identity 373, architecture 6 — 0 failures/errors.

**Residual risks:** cross-schema loops are O(tenants) per request (Phase 1); activity includes background sync calls (spec definition).

