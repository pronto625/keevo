# Epic 9 Context: Super Admin Dashboard (Toor)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Give Toor (founder, sole platform operator in V1) a dedicated web dashboard to run the whole Keevo platform: browse and act on tenants, manually activate paid plans after off-platform payment (Mobile Money/cash), follow revenue and usage analytics, watch system health and proactive alerts, broadcast global push notifications, and monitor cross-tenant catalog, operational activity and offline-sync health. It replaces manual database queries with a single control surface so Toor can detect churn risk and incidents before merchants complain.

## Stories

- Story 9.1: Gestion des Tenants (list/search/filter, detail drawer, deletion-pending actions)
- Story 9.2: Activation Plan Payant (manual activate/expire, suspension)
- Story 9.3: Dashboard Revenus (MRR/ARR, churn, CSV export)
- Story 9.4: Analytics Plateforme (DAU/MAU, cohorts, top stores, feature adoption)
- Story 9.5: Santé Système, Alertes & Notifications Push Globales
- Story 9.6: Monitoring Catalogue Produits cross-tenant (KPI cards, filtered product table, CSV export) — done
- Story 9.7: Monitoring Activité Opérationnelle (GMV global/per tenant, daily trend, tenant activity table, inactive tenants) — done (backend module `com.keevo.admin.operations`, dashboard page « Activité » at `monitoring/activity`)
- Story 9.8: Monitoring Synchronisation & Santé Devices (sync overview, per-tenant health table, tenant detail drawer, error/refresh states) — done

## Requirements & Constraints

- Super admin capabilities: tenant list with status/plan/registration/last sync; manual paid-plan activation/deactivation with payment history; revenue dashboard (MRR, paid vs free, conversion, monthly churn); platform analytics (sales volume, active stores, aggregated product base size, growth curves); proactive WhatsApp/email alerts (tenant 5 days without sync, subscription expiring < 7 days); global push notifications; system health (servers, tenants late on sync, critical errors).
- Plan model: tenants start on `PREMIUM_TRIAL` (6 months), then `PREMIUM` (paid) or `FREE`; expiry auto-downgrades to FREE with data retained (never deleted). Free limits: 1 store, 500 products, 3 employees. Epic stories say "PAID" — map to the real subscription values, don't invent a new enum.
- Alerts feed includes HIGH-priority user feedback (in-app feedback/bug reports exist and are listed via the admin feedback endpoint), DELETION_PENDING tenants (account deletion requests), expired-but-still-paid integrity issues, sync conflicts unresolved > 24h.
- Tenant list must stay < 2s up to 10 000 tenants (server-side pagination, 25 rows/page); search debounced 300ms; filters compose with AND and show as dismissible chips.
- Currency is XAF only.
- **No technical data in UI:** no UUIDs, tenant/schema codes, or raw enum codes on screens, CSV exports or audit details — French human labels only (map statuses/plans/event types to French labels; tenant shown by business name).
- Activity definitions: GMV = sum of `total_amount` of `COMPLETED` sales (XAF) over the period; a tenant is inactive when its last activity (latest sale or `audit_log` entry) is missing or older than 14 days. Period = `TODAY | 7D | 30D | 90D | CUSTOM`; `CUSTOM` needs inclusive ISO `from`/`to`, `from ≤ to`, ≤ 366 days.
- Every page needs loading, first-load error (« Erreur de chargement » + « Réessayer ») and failed-refresh-with-stale-data states; refresh buttons are disabled while in flight.
- Cross-tenant aggregation over per-schema loops is acceptable for Phase 1 (< 200 tenants); exclude the `KV-ADMIN` system tenant from all platform stats.

## Technical Decisions

- **Frontend:** `keevo/dashboard` — Next.js 16 (app router), React 19, React Query, axios, recharts, Tailwind 4. Pages under `src/app/` (`tenants`, `monitoring/products|activity|sync`, `login`). Pattern: `types/<x>.ts` mirroring backend DTOs (dates as ISO strings) → `lib/api/admin-<x>.ts` using the shared axios `client` (baseURL `/api/backend`, JWT interceptor; unwrap `res.data.data`) → `hooks/use<X>.ts` (useQuery + useState for filters/page, `placeholderData` to avoid pagination flash, `refresh` = invalidate queries) → components. Existing committed `page.tsx` files for activity/sync fix the hook return shape and component props — do not change them. Cards `bg-white rounded-lg shadow p-4`, `animate-pulse` skeletons.
- **French labels:** `src/lib/labels.ts` is the single source of French labels (plans, statuses, event/op types, humanizing unknown codes). Extend it for new codes; never print raw codes or define ad-hoc label maps in components.
- **Shared dashboard pieces (reuse, don't duplicate):** `src/lib/labels.ts` (French labels), `src/lib/period.ts` (period selection/params), `src/components/sync/SyncErrorStates.tsx` exporting `LoadError` (first-load error + « Réessayer ») and `StaleDataWarning` (failed refresh with stale data).
- **Dashboard tests:** Vitest + Testing Library harness (`npm test` = `vitest run`), tests live in `__tests__` folders next to the code (page-level tests under `src/app/**/__tests__`, e.g. `monitoring/sync` and `monitoring/activity`), and run in Dashboard CI alongside build, lint and type-check. New pages/components get component tests.
- **Backend:** `com.keevo.admin.*` hexagonal modules (adapter/in/rest + dto, application/service, domain/model, domain/port/in); subscription logic in `com.keevo.subscription`. Cross-tenant reads use `JdbcTemplate` with schema-qualified SQL (`<schema>.products`, `.sales`, `.audit_log`), skipping tenants whose schema can't be resolved (pattern of `AdminTenantService`). Schema-per-tenant is non-negotiable.
- **Per-tenant loop error handling:** admin services looping over tenant schemas skip a tenant only on its own SQL/schema errors (e.g. missing table/schema); connection or resource failures (pool exhaustion, DB unreachable) must be rethrown, never swallowed as "skip tenant".
- **Batched schema lookup:** the admin tenant list loads all tenant schema names in one batched query (no per-row lookup / N+1); follow the same approach in new cross-tenant lists.
- **Security:** `/api/v1/admin/**` requires `SUPER_ADMIN` at the filter chain, plus an explicit `CurrentActor.requireSuperAdmin()` in each controller method (non-admin → 403 `FORBIDDEN`). Admin identity: fixed user/tenant `KV-ADMIN` with schema `public`, created idempotently at boot. Tenant always resolved from JWT, never URL/body (except admin paths targeting a tenant id).
- **API conventions:** responses wrapped in `ApiResponseWrapper`; errors `{ error, code, domainCode, details, timestamp }`; every exception carries a `domainCode` (e.g. suspended tenant → 403 `TENANT_SUSPENDED`). Validation errors (bad params/body) are HTTP 422 `VALIDATION_ERROR`, following the `GlobalExceptionHandler` convention. Domain events emitted for admin actions with `actorId` (e.g. plan activated/expired, tenant suspended, global push sent).
- **Plan storage:** the source of truth is the tenant-schema `subscriptions` table. `public.tenants.plan_type` exists but is not kept in sync with it today; story 9.2 fixes this, so until then don't rely on `plan_type` for cross-tenant plan reads.
- **Existing admin endpoints:**
  - `GET /api/v1/admin/tenants` (paged list/filters), `GET /tenants/{id}/detail`, `POST /tenants/{id}/cancel-deletion`, `POST /tenants/{id}/force-delete`
  - `POST /api/v1/admin/subscriptions/{tenantId}/activate` (manual plan activation)
  - `GET /api/v1/admin/feedback` (feedback list)
  - `GET /api/v1/admin/catalog/summary`, `GET /catalog/products`, `GET /catalog/products/export` (CSV)
  - `GET /api/v1/admin/sync/overview`, `GET /sync/tenants`, `GET /sync/tenants/{id}/detail`
  - `GET /api/v1/admin/operations/overview`, `/operations/trend`, `/operations/tenants`, `/operations/inactive-tenants` (period params)
  - Tenant-side: `GET /api/v1/subscription/me`
  - Not yet present: revenue, analytics, health, alerts, global push endpoints. `admin/platform` holds only an empty MCP adapter placeholder.

## UX & Interaction Patterns

- Daily flow: overview → Tenants → Revenus → Analytics → Alertes → Santé système; actions: activate paid plan, suspend, global push.
- Sidebar has a collapsible "Monitoring" group (Catalogue Produits, Activité, Synchronisation).
- Tenant detail and sync detail open as right-side drawers; destructive admin actions (force delete, suspend) require a confirmation dialog; suspension requires a free-text reason.
- Threshold breaches turn cards red and show a sticky incident banner on all admin pages; status badges green/orange/red using Tailwind colors already in use.
- CSV exports named `keevo-<subject>-YYYY-MM(-DD).csv`, with French column content and no internal ids.

## Cross-Story Dependencies

- 9.2 builds on the subscription module (plan types, daily expiry downgrade job) and WhatsApp notifications; suspension must be enforced on all tenant API calls and the Flutter app (full-screen block).
- 9.3 churn and 9.5 alerts depend on plan expiry events from 9.2; 9.5 alerts consume feedback (Epic 8 feedback) and tenant deletion requests (account deletion flow), and global push relies on FCM tokens and in-app notification history (Epic 8).
- 9.7 (done) reused the 9.6/9.8 frontend patterns (`admin-catalog.ts`, `useProducts.ts`, `CatalogSummaryCards`, the sync page's error/refresh states and tests, `labels.ts`); 9.7's "last activity" reuses the 9.1 definition (latest sale / audit_log entry). Remaining stories (9.3–9.5) should reuse `labels.ts`, `period.ts`, `LoadError`/`StaleDataWarning` and the page-test harness. 9.8 consumes sync data produced by the offline sync engine (failed ops, conflicts, device push/pull timestamps).
