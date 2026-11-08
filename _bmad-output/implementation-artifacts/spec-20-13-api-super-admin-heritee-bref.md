---
title: '20-13 API super-admin héritée de Bref — vue plateforme, listes inter-comptes, utilisateurs'
type: 'feature'
created: '2026-11-08'
status: 'in-review'
baseline_revision: 'f9ce63f0ddf86f6e49549a8ceab33a41047bfc0b'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-20-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-9-reporting-multi-source.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-10-module-expenses.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-16-4-console-super-admin-modules.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-16-11-sessions-longues-et-sures.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-19-10-versions-minimales-app.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** the Bref super-admin dashboard (delivered from another repo) reads platform figures, cross-account lists and users from api-bref (`dashboard`, `customer-admin`, `product-admin`, `expense-admin`, `users` admin routes…). After the merge it must call the Keevo API only (FUS-FR38), and the platform figures must cover Keevo tenants and migrated Bref owners without double counting (R4). The 20.9 review also routed the platform GMV (`AdminAnalyticsService`, `admin/operations`) to the single revenue source, and the 20.10 review routed the cross-account expense listing here.

**Approach:** a route-by-route mapping (table below). Existing Keevo admin endpoints are reused; the gaps are new, read-only, super-admin endpoints under `/api/v1/admin/**`: a Bref-shaped platform dashboard (`/admin/dashboard/*`), cross-account customers / products / expenses lists with detail (`/admin/customers`, `/admin/products`, `/admin/expenses`, detail under `/admin/tenants/{tenantId}/…`) and a platform users read model (`/admin/users`, stats, detail, devices). Every platform GMV now reads the revenue relations of 20.9 through one admin adapter. Cross-tenant reads are **one bounded `UNION ALL` statement over the tenant schemas**, never one query per tenant.

## Boundaries & Constraints

**Always:**
- Secured exactly like the existing admin endpoints: path under `/api/v1/admin/**` (`SecurityConfig` → role `SUPER_ADMIN`), `CurrentActor.requireSuperAdmin()` in every handler, `@EntitlementExempt("super-admin")` on the controller. Non-super-admin → 403.
- Read-only. Additive only: no change of an existing response field's meaning other than the routed GMV (same field names, now from the revenue source).
- Revenue = the 20.9 source (`RevenueRelations`, consumed only by `admin/platformrevenue/adapter/out/RevenueSourcePlatformRevenueAdapter`): COMPLETED sales ∪ counted invoices (`source_sale_id IS NULL`) − credit notes; a POS sale bridged to an invoice counts once. Invoicing branches only for schemas whose catalogue holds the invoicing tables (same predicate as `ProvisionedRevenueSource`).
- No double counting: users = distinct `public.users` (a person member of several accounts counts once); accounts = `public.tenants` (a migrated Bref owner becomes one tenant, 24.2/24.7 merge identities); revenue per tenant from the single source.
- KV-ADMIN system tenant and `DELETED` tenants excluded; super-admin users excluded from platform user figures (same rule as 9-4 analytics).
- Lists: keyset pagination (`cursor` + `limit`, default 20, max 100), cursor bound to the filters (other filters → 422), each `UNION ALL` branch ordered and limited on an existing index; tie on equal keys broken by tenant id.
- French labels for every code shown (roles, statuses, categories, events); no technical code used as a label.
- Errors: `VALIDATION_ERROR` 422 (the platform mapping of a domain validation error) with a French message for bad params; `TENANT_NOT_FOUND`, `CLIENT_NOT_FOUND`, `PRODUCT_NOT_FOUND`, `EXPENSE_NOT_FOUND`, `USER_NOT_FOUND` 404.

**Never:** no write on tenant data (no super-admin create/update of customers, users, expenses), no per-tenant query loop on the new paths, no OFFSET, no new tenant module migration, no connection to production, no change to `keevo/app` or `keevo/dashboard`, no campaign / app-version / plan-catalogue re-implementation (other stories).

## Bref → Keevo route mapping

Bref reference: `api-bref` develop, file:line of the handler. Keevo paths are under `/api/v1`. Statuses: **existing** (controller:line), **extended** (this story), **new** (this story), **abandon?** (proposed abandonment, user decision), **other story**.

| # | Bref route (verb path — purpose) | Keevo equivalent | Status |
|---|---|---|---|
| D1 | `GET /dashboard/owner-summary` (`dashboard.controller.ts:29`) — owner KPIs over a period | `GET /dashboard/summary` (`reporting/dashboard/adapter/in/rest/DashboardController.java:40`, revenue source since 20.9) for the owner; the super-admin uses D2–D5 | existing |
| D2 | `GET /dashboard/overview` (`:47`) — users, shops, invoices, revenue with trends | `GET /admin/dashboard/overview` — users, accounts, stores, documents, revenue (revenue source) | new |
| D3 | `GET /dashboard/growth?months=7` (`:55`) — per month users, shops, invoices | `GET /admin/dashboard/growth?months=` — per month new users, accounts, stores, invoices issued, revenue | new |
| D4 | `GET /dashboard/top-shops?limit=5` (`:67`) — best shops by revenue | `GET /admin/dashboard/top-accounts?limit=&days=` — best accounts by revenue (documents carry no store yet, 20-14 / 24.4); per-store sales ranking stays `GET /admin/analytics/top-stores` (`AdminAnalyticsController.java:71`) | new |
| D5 | `GET /dashboard/activity?limit=10` (`:79`) — recent platform events | `GET /admin/dashboard/activity?limit=` — accounts, users, stores, invoices, quotes created | new (payment events: abandon?, see A6) |
| D6 | (Keevo) platform GMV in `GET /admin/analytics/overview`, `/growth` (`AdminAnalyticsController.java:38,45`), `GET /admin/operations/overview`, `/trend`, `/tenants` (`AdminOperationsController.java:43,55,70`) | same endpoints, GMV and order counts from the revenue source (routed by 20.9) | extended |
| C1 | `GET /customers` (`customer-admin.controller.ts:88`) — cross-shop customers, `ownerId`/`shopId`/`q`/sort | `GET /admin/customers?tenantId=&storeId=&q=&cursor=&limit=` | new |
| C2 | `GET /customers/:id` (`:142`) | `GET /admin/tenants/{tenantId}/customers/{customerId}` | new |
| C3 | `POST /customers` (`:166`) — manual customer creation | owner/team: `POST /clients` (`crm/contact/adapter/in/web/ClientController.java:71`) | existing (owner); super-admin: abandon? (A1) |
| C4 | `PATCH /customers/:id` (`:191`) | owner/team: `PATCH /clients/{id}` (`ClientController.java:161`) | existing (owner); super-admin: abandon? (A1) |
| C5 | `PATCH /customers/:id/marketing-consent` (`:213`) | owner/team: `PUT /clients/{id}/marketing-consent` (`ClientController.java:178`) | existing (owner); super-admin: abandon? (A1) |
| P1 | `GET /products` (`product-admin.controller.ts:68`) — cross-shop catalogue | `GET /admin/products?tenantId=&q=&status=&cursor=&limit=` (keyset) + `GET /admin/tenants/{tenantId}/products/{productId}`; the Keevo console list `GET /admin/catalog/products` (`AdminCatalogController.java:43`) stays as is | new |
| E1 | `GET /expenses` (`expense-admin.controller.ts:62`) — cross-account expenses + `totalAmount` | `GET /admin/expenses?tenantId=&storeId=&category=&from=&to=&q=&cursor=&limit=` (+ `totalAmount`) and `GET /admin/tenants/{tenantId}/expenses/{expenseId}` | new |
| U1 | `POST /users` (`users.controller.ts:148`) — create a user | owner: `POST /employees` (`identity/employee/adapter/in/rest/EmployeeController.java:99`); accounts are created by sign-up | existing (owner); super-admin: abandon? (A2) |
| U2 | `GET /users` (`:248`) — all users + device summary | `GET /admin/users?role=&active=&tenantId=&q=&cursor=&limit=` | new |
| U3 | `GET /users/:id/devices` (`:297`) | `GET /admin/users/{userId}/devices` (live device sessions of 16.11); per-account sync devices stay `GET /admin/sync/tenants/{tenantId}/detail` (`AdminSyncMonitoringController.java:59`) | new |
| U4 | `GET /users/stats` (`:338`) — totals, by role, top sellers | `GET /admin/users/stats?from=&to=&role=&active=` — totals and by role | new (seller fields: abandon?, A5) |
| U5 | `GET /users/:id` (`:371`) | `GET /admin/users/{userId}` | new |
| U6 | `PATCH /users/:id/status` (`:415`) — (de)activate | account: `POST /admin/tenants/{tenantId}/suspend` / `/reactivate` (`AdminTenantPlanController.java:124,138`); employee: owner `PATCH /employees/{id}/deactivate` / `/reactivate` (`EmployeeController.java:156,170`) | existing; super-admin on one employee: abandon? (A3) |
| U7 | `PATCH /users/:id` (`:459`) — edit a user | owner: `PATCH /employees/{employeeId}` (`EmployeeController.java:209`) | existing (owner); super-admin: abandon? (A2) |
| U8 | `GET /users/me/plan/downgrade-check`, `PATCH /users/me/plan`, `POST /users/me/plan/checkout`, `POST …/checkout/:requestId/confirm` (`:568,600,860,967`) — owner self-service | not super-admin routes | other story: **21.1** (payments) / **21.10** (offer) |
| U9 | `PATCH /users/:id/plan` (`:704`) — grant a plan | `POST /admin/tenants/{tenantId}/plan/premium` (plan parameter, 19.7b) / `/plan/free` (`AdminTenantPlanController.java:94,112`), `POST /admin/subscriptions/{tenantId}/activate` (`AdminSubscriptionController.java:51`) | existing |
| U10 | `DELETE /users/:id` (`:1120`) | account: `POST /admin/tenants/{tenantId}/force-delete` (`AdminTenantController.java:121`), owner self-delete `POST /account/delete` (`AccountController.java:40`) | existing; super-admin on one employee: abandon? (A4) |
| V1 | `GET /app-version/check` (`app-version.controller.ts:35`) | `GET /app-version/check` (`identity/appversion/adapter/in/rest/AppVersionCheckController.java:53`) | other story: **19.10** (done) |
| V2 | `PUT /app-version` (`:49`), `GET /app-version` (`:63`) | `PUT`/`GET`/`DELETE /admin/app-versions…` (`AppVersionAdminController.java:52,68,104`) | other story: **19.10** (done) |
| L1 | `GET /plans` (`plans.controller.ts:12`) — public offer | public offer endpoint → **21.10**; super-admin plan profiles `GET/PUT /admin/plan-profiles…` (`AdminPlanProfileController.java:57,65,91`, 19.7) | other story: **21.10** / 19.7 (done) |
| K1 | `POST/GET/GET :id/PATCH/DELETE /campaigns`, `POST :id/send`, `POST preview-targeting` (`campaign.controller.ts:47,59,101,109,125,133,145`) | — | other story: **21.6** (backlog) |

**Proposed for abandonment (decision for the user):**
- **A1** — super-admin create / edit / marketing consent of a merchant's customer (C3–C5): the merchant's team owns its CRM data (owner routes exist); a support agent editing a customer bypasses the store scope (ADR-08) and the consent author trail.
- **A2** — super-admin create / edit of a user (U1, U7): accounts come from sign-up, members from the owner (`/employees`); support acts on the account (suspend, plan, delete).
- **A3** — super-admin (de)activation of one employee (U6): the owner manages members; the super-admin suspends the account.
- **A4** — super-admin deletion of one employee (U10): same reason; account deletion exists.
- **A5** — `averageSalesPerSeller` / `topPerformers` in user stats (U4): seller ranking is per account (`reporting` leaderboard); a platform-wide seller ranking mixes unrelated businesses.
- **A6** — « paiement reçu » events in the activity feed (D5): `invoice_payments` has no index on `paid_at`; adding one is a tenant module migration in the invoicing module that 20-14 is also changing. Keep out unless the user wants it (then a `paid_at` partial index in the invoicing module).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Owner token on any new route | `GET /admin/users` as OWNER | refused | 403 |
| POS sale bridged to an invoice | sale 5 000 COMPLETED + automatic invoice `source_sale_id` | platform revenue +5 000 once | — |
| Document-only tenant | invoice ISSUED 7 000, credit note 2 000 | revenue 5 000, documents 1 | — |
| Person in two accounts | one user, OWNER of two tenants | users total +1 | — |
| Tenant without expenses table | expenses module never used | not a branch, no error | — |
| Keyset | 3 customers, `limit=2` | page 1: 2 + `nextCursor`; page 2: 1, no cursor; no duplicate | — |
| Cursor with other filters | cursor of `q=a` reused with `q=b` | refused « Curseur invalide » | 422 |
| Bad params | `limit=0`, `months=30`, `category=food`, `from>to`, bad UUID | refused with a French message | 422 |
| Unknown ids | tenant / customer / product / expense / user | not found | 404 |
| Too many tenants for one statement | branches > `keevo.admin.cross-tenant.max-branches` | refused « Trop de comptes à parcourir : filtrez la liste par compte » | 422 |

</intent-contract>

## Code Map

Paths under `keevo/backend/src/main/java/com/keevo/` unless stated.
- `admin/analytics/application/service/AdminAnalyticsService.java`, `admin/operations/application/service/AdminOperationsService.java` -- platform GMV (per-tenant loops, 9-4 / 9-7) switched to the revenue port.
- `reporting/revenue/adapter/out/persistence/RevenueRelations.java`, `ProvisionedRevenueSource.java`, `reporting/revenue/domain/port/out/RevenueQueryPort.java` -- the single revenue source (20.9).
- `admin/catalog/application/service/AdminCatalogService.java`, `admin/tenant/**`, `admin/subscription/**`, `admin/sync_monitoring/**`, `identity/auth/adapter/in/rest/DeviceSessionController.java` -- existing admin / session endpoints mapped above.
- `shared/infrastructure/persistence/TenantSchema.java`, `shared/infrastructure/security/CurrentActor.java`, `shared/module/EntitlementExempt.java` -- schema gate and admin security.
- `resources/db/modules/{crm,catalog,expenses,invoicing}/*`, `resources/db/migration/V36__device_sessions.sql`, `V41__users_multiple_identifiers.sql` -- columns and indexes read.
- Bref (read-only): `api-bref/src/dashboard/**`, `customer/customer-admin.controller.ts`, `product/product-admin.controller.ts`, `expense/expense-admin.controller.ts`, `users/users.controller.ts`, `app-version/*`, `plans/*`, `campaign/*`.

## Tasks & Acceptance

**Execution:**
- `admin/crosstenant/*` -- `TenantBranch`, `CrossTenantSql` (one catalogue query to pick the tenant schemas holding a table + columns, bounded `UNION ALL` builder), `KeysetCursor` (filter-bound opaque cursor), `CursorPage`.
- `admin/platformrevenue/**` -- `PlatformRevenuePort` + `RevenueSourcePlatformRevenueAdapter` (qualified 20.9 relations, one statement, grouped by tenant / day / month).
- `admin/analytics`, `admin/operations` -- GMV and order counts from the port; tests updated.
- `admin/dashboard/**` -- overview, growth, top accounts, activity (controller, use case, service, JDBC reader).
- `admin/crossaccount/**` -- customers, products, expenses list + detail.
- `admin/user/**` -- users list, stats, detail, devices.
- `admin/package-info.java` + `reporting/revenue/adapter/out/persistence/package-info.java` (`@NamedInterface("revenue")`), `expenses/api/ExpenseLabels.java` (`@NamedInterface("api")`) -- module boundaries.
- `resources/db/migration/V60__users_created_at_keyset_index.sql` -- `public.users (created_at DESC, id DESC)` for the users keyset.
- Tests: unit (services, cursor, union builder, qualification) and full context per endpoint incl. 403; `docs/entitlements/endpoints.md` regenerated.

**Acceptance Criteria:**
- Given the mapping table, when it is compared with `api-bref/src/**/*.controller.ts` (develop), then every Bref admin route has a delivered and tested Keevo equivalent, is covered by another story, or is listed for an abandonment decision.
- Given Keevo tenants with POS sales, document-only tenants and bridged invoices, when the platform figures are read, then revenue and accounts are counted once (R4).
- Given a non-super-admin token, when any new route is called, then 403.

## Spec Change Log

## Review Triage Log

## Design Notes

**Pattern.** Hexagonal slices per area (REST adapter → use case → service → read port → JDBC adapter), as the other admin areas. Cross-tenant reads use a **Composite query**: `CrossTenantSql` builds one `UNION ALL` of per-tenant branches (each branch a schema-qualified, index-ordered, `LIMIT`ed query), the outer query merges, re-orders and limits. The branch set comes from **one catalogue query** (`pg_attribute` on `to_regclass(schema.table)`, required columns) joined to `public.tenants`, so a tenant whose module was never used is simply not a branch, and a missing column never fails the statement. Revenue goes through an **Adapter** over the 20.9 relations: the only class importing reporting (`RevenueSourcePlatformRevenueAdapter`) qualifies `sales`, `sale_items`, `invoices`, `invoice_items`, `credit_notes`, `credit_note_items` with the tenant schema (a test fails if a table of the relation is left unqualified). When 20-14 moves the source to `shared.capability.revenue`, only that adapter's import (and the `reporting :: revenue` entry of `admin/package-info.java`) changes.

**What Keevo used for platform KPIs before:** one query per tenant schema per endpoint (`AdminAnalyticsService`, `AdminOperationsService`, `AdminCatalogService` loops). There is no aggregated public table, materialised counter or snapshot. This story replaces the GMV part of those loops by the single-statement port and builds all new paths on the bounded union; the remaining per-tenant loops of 9-4 / 9-7 (store / product counts, last activity) are untouched.

**Cost (FUS-NFR11), T = tenant schemas holding the table, n = page size.**
- Every new endpoint: 1 catalogue query (`public.tenants` × `pg_attribute`, O(T)) + 1 data statement (+1 for `totalAmount` on expenses, +1 membership batch on users). Never a query per tenant.
- Lists: each branch is an index range scan in sort order with `LIMIT n+1` — customers / products on the sync keyset index `(updated_at, CAST(id AS text))`, expenses on `idx_expenses_date (expense_date, created_at, id) WHERE archived_at IS NULL` — so the statement reads at most T·(n+1) index entries and sorts T·(n+1) rows (O(T·n·log(T·n))). The keyset predicate per branch is a row comparison on the same index columns (`<` or `<=` depending on the tenant tie-break). Filters (`q`, store, category, dates) are evaluated in the branch; `q` on customer names uses the crm trigram index when the planner prefers it.
- Revenue: one statement; each branch is the 20.9 relation with the window pushed into every `UNION ALL` leg (sales `idx_sales_completed_occurred_at`, invoices `idx_invoices_revenue_day`, credit notes `idx_credit_notes_issued_at`), grouped by tenant or by WAT day / month. All-time totals (overview) scan the counted rows once per tenant (same cost as the former per-tenant `SUM`, one round trip instead of T).
- Users: public tables only — list keyset on the new `public.users (created_at DESC, id DESC)` index, memberships by one `user_id = ANY(?)` query per page, devices on `idx_refresh_tokens_live_by_user`; stats one aggregate statement.
- Bound: a statement holds at most `keevo.admin.cross-tenant.max-branches` (default 1000) branches; beyond, lists without `tenantId` answer 422 (filter by account). Planning cost grows linearly with T; at today's 25 tenants it is negligible.
- Failure policy: a branch can only fail on a real schema / data error (the catalogue filter removes missing tables / columns); then the request fails (500, logged with the tenant-free message) instead of answering 200 with partial figures.

**Deviations from Bref (documented, not routes):** « recent » sorts by last update (`updated_at`, the indexed key) instead of creation; Bref's other sorts (`top_spender`, `name`, `price_*`, amount) are not offered on the cross-account lists (a cross-tenant sort needs a matching index in every schema); `page`/`limit` become `cursor`/`limit`; the owner scope of Bref's shared routes stays on the owner routes; overview revenue has no pending / overdue amounts (counts of unpaid / overdue invoices are given — amounts need the credit-note-aware amount due of 20.4 per invoice).

## Verification

**Commands:**
- `kmvn.sh -o -q test -Dtest='com/keevo/admin/**/*Test*'` -- expected: pass
- same for `com/keevo/architecture/**`, `com/keevo/shared/**`, `com/keevo/reporting/**`, `com/keevo/expenses/**` -- expected: pass

## Auto Run Result

**Result:** implemented, all touched packages green; no 5-layer review pass run yet (status `in-review`, follow-up review recommended).

**Delivered:**
- New read-only super-admin endpoints (14 handlers, `@EntitlementExempt("super-admin")` + `CurrentActor.requireSuperAdmin()`): `/admin/dashboard/{overview,growth,top-accounts,activity}`, `/admin/customers`, `/admin/products`, `/admin/expenses` and their details under `/admin/tenants/{tenantId}/…`, `/admin/users`, `/admin/users/stats`, `/admin/users/{userId}`, `/admin/users/{userId}/devices`; `docs/entitlements/endpoints.md` regenerated (271 handlers).
- `admin/crosstenant` (catalogue-filtered bounded `UNION ALL`, filter-bound keyset cursor with tenant tie-break) and `admin/platformrevenue` (single adapter over the 20.9 relations, qualified per schema, one statement).
- Routed 20.9 item closed: `AdminAnalyticsService` (overview, growth) and `AdminOperationsService` (overview, trend, tenants) read GMV / orders from the revenue port — one statement instead of one sales query per tenant. Behaviour change: a sale without `occurred_at` (legacy) is outside every window, as on the owner dashboard; operations « new tenants » no longer skips a tenant whose own schema query fails.
- Routed 20.10 item closed: cross-account expense list (+ `totalAmount`) and detail.
- Boundaries: `admin` may use `reporting :: revenue` (new named interface on `reporting.revenue.adapter.out.persistence`) and `expenses :: api` (new `ExpenseLabels`). Public Flyway `V60__users_created_at_keyset_index.sql`.
- Validation errors answer **422** `VALIDATION_ERROR` (platform mapping of a domain validation error, same as the other admin routes) — the spec text says 422 everywhere.

**Tests (via `kmvn.sh`, one package at a time):** admin 638 / 0 failures (new: `CrossTenantKeysetTest`, `RevenueSourcePlatformRevenueAdapterTest`, `PlatformRevenuePostgresIntegrationTest`, `PlatformDashboardServiceTest`, `CrossAccountServiceTest`, `AdminUserServiceTest`, `BrefSuperAdminApiFullContextTest` incl. 403 on all 14 routes; analytics / operations unit and Postgres tests adapted); architecture 125 / 0 (Modulith boundaries, ArchUnit rules, schema-interpolation guard, `UserGlobalRoleReadArchTest`, `EndpointEntitlementCoverageTest`); shared 1 320 / 0; reporting 451 / 0; expenses 39 / 0.

**Decisions for the user:** abandonment proposals A1–A6 (table above).

**Out of scope, owning stories:** campaigns → 21.6; public offer endpoint (`GET /plans`) → 21.10; owner self-service plan / checkout routes → 21.1 / 21.10; documents per store (per-store revenue incl. invoices, top stores with documents) → 20-14 / 24.4; move of the revenue source to `shared.capability.revenue` (one import + the `reporting :: revenue` entry) → 20-14; the remaining per-tenant loops of 9-4 / 9-7 (store / product counts, last activity, adoption, geography, admin catalogue) are unchanged and not covered by this story — no owning story yet (decision for the user whether to route them, e.g. to 16-7).

## Dashboard super-admin (autre dépôt)

> Destinataire : agent IA qui construit le dashboard super-admin dans un **autre dépôt** (ce dépôt ne livre que l'API). Conventions admin (Bearer d'une session super-admin, enveloppe `{"data", "timestamp"}`, erreurs `{"error", "code", "domainCode", "details", "timestamp"}`, afficher `error` tel quel, décider sur `domainCode`, dates ISO-8601 UTC à afficher en heure de Douala) : section « Dashboard super-admin » de `spec-16-4-console-super-admin-modules.md`. Session : `spec-16-11-sessions-longues-et-sures.md`.

### Statut

API livrée (branche de la story 20.13, en attente de fusion) — dashboard à faire. Toutes les routes sont en **lecture seule**.

### Ce qui est fait côté API

- Tableau de bord plateforme repris de Bref (`/dashboard/overview|growth|top-shops|activity`) : `/api/v1/admin/dashboard/*`. Comptes = entreprises Keevo (un propriétaire Bref migré = une entreprise) ; utilisateurs = personnes distinctes (une personne membre de deux entreprises compte une fois), super-administrateurs exclus ; chiffre d'affaires = source unique de 20.9 (ventes caisse terminées + factures émises/payées hors factures automatiques de caisse − avoirs) : **aucun double comptage**.
- Le CA des écrans existants `/api/v1/admin/analytics/overview|growth` (`totalGmv`, `totalCompletedSales`, `gmv`, `salesCount`) et `/api/v1/admin/operations/overview|trend|tenants` (`totalGmv`, `totalOrders`, `gmv`, `orderCount`) vient désormais de cette même source : mêmes champs ; les factures « documents » y sont comptées ; une vente sans date de vente n'est plus comptée (comme sur le tableau de bord du propriétaire). `top-stores` reste par boutique sur les ventes caisse (les factures n'ont pas encore de boutique).
- Listes inter-comptes reprises de Bref (`/customers`, `/products`, `/expenses`) avec fiche détail, pagination par curseur.
- Utilisateurs de la plateforme (`/users`, `/users/stats`, `/users/:id`, `/users/:id/devices`).
- Statut, offre et suppression d'un utilisateur Bref → routes **existantes** au niveau du compte (voir « À utiliser »).

### À utiliser

**Pagination par curseur (toutes les listes de cette section)** : paramètres `limit` (1–100, défaut 20) et `cursor` ; réponse `data = {items: [...], nextCursor: string|null, hasMore: boolean}`. Page suivante : renvoyer **les mêmes filtres** + `cursor=nextCursor` ; `nextCursor` null = fin. Un curseur réutilisé avec d'autres filtres, modifié ou trop long → 422 `VALIDATION_ERROR` « Curseur invalide : relancez la liste depuis le début » (repartir de la première page). Les `id` / `tenantId` / `storeId` servent aux URL, jamais à l'écran.

**Tableau de bord** (`keevo/backend/src/main/java/com/keevo/admin/dashboard/adapter/in/rest/AdminPlatformDashboardController.java`, modèles `.../dashboard/domain/model/PlatformDashboardViews.java`) :

| Méthode | Route | Paramètres | Réponse `data` |
|---|---|---|---|
| GET | `/api/v1/admin/dashboard/overview` | — | `{users: {total, active, newThisMonth, newLastMonth, trendPercent}, accounts: {total, active, suspended, newThisMonth, newLastMonth, trendPercent}, stores: {total, active, newThisMonth}, documents: {invoices, unpaid, partiallyPaid, paid, overdue, quotes}, revenue: {total, thisMonth, lastMonth, trendPercent}}` — `trendPercent` = évolution en % (1 décimale) du mois en cours vs le précédent, **null** quand le mois précédent vaut 0 (afficher « Nouveau » ou « — ») ; montants en FCFA entiers ; `quotes` = devis ouverts |
| GET | `/api/v1/admin/dashboard/growth` | `months` 1–24 (défaut 7) | `[{month: "AAAA-MM", label: "nov. 2026", year, newUsers, newAccounts, newStores, invoicesIssued, revenue, documents}]`, du plus ancien au mois en cours, mois vides à 0 |
| GET | `/api/v1/admin/dashboard/top-accounts` | `limit` 1–20 (défaut 5), `days` 1–366 (défaut 30, jours de Douala jusqu'à aujourd'hui inclus) | `[{tenantId, name, ownerName ("Propriétaire" si sans nom), status, statusLabel ("Actif", "Suspendu", "Suppression programmée"), storeCount, documents, revenue}]` par CA décroissant ; comptes sans CA absents |
| GET | `/api/v1/admin/dashboard/activity` | `limit` 1–50 (défaut 10) | `[{type, label, detail, tenantId, tenantName, occurredAt}]`, le plus récent d'abord ; `label` : « Nouveau compte », « Nouvel utilisateur », « Nouvelle boutique », « Facture créée », « Devis créé » ; `detail` : nom du compte / de la personne / de la boutique, ou « Facture de 12 000 FCFA » ; `tenantId`/`tenantName` null pour « Nouvel utilisateur » |

Paramètre hors bornes → 422 `VALIDATION_ERROR`, ex. « Le nombre de mois doit être compris entre 1 et 24 », « Le nombre de comptes doit être compris entre 1 et 20 », « Le nombre de jours doit être compris entre 1 et 366 », « Le nombre d'événements doit être compris entre 1 et 50 » (valeur non entière : « … doit être un nombre entier »).

**Listes inter-comptes** (`keevo/backend/src/main/java/com/keevo/admin/crossaccount/adapter/in/rest/AdminCrossAccountController.java`, modèles `.../crossaccount/domain/model/CrossAccountViews.java`) :

| Méthode | Route | Paramètres | Réponse `data` |
|---|---|---|---|
| GET | `/api/v1/admin/customers` | `tenantId`, `storeId`, `q` (nom ou téléphone, ≤ 100 car.), `cursor`, `limit` | page de `{tenantId, tenantName, id, name, phone, email, storeId, storeName, marketingConsent, createdAt, updatedAt}`, dernière modification d'abord ; clients archivés exclus |
| GET | `/api/v1/admin/tenants/{tenantId}/customers/{customerId}` | — | `{tenantId, tenantName, id, name, phone, email, storeId, storeName, clientType, clientTypeLabel ("Particulier" \| "Entreprise" \| "Autre" \| null), niu, rccm, address, contactName, website, birthDate, notes, archived, marketingConsent, marketingConsentAt, marketingOptOutAt, createdAt, updatedAt}` |
| GET | `/api/v1/admin/products` | `tenantId`, `q` (nom, ou code-barres exact), `status` = `active` (défaut) \| `archived`, `cursor`, `limit` | page de `{tenantId, tenantName, id, name, price, stockQuantity, trackStock, status, statusLabel ("Actif" \| "Brouillon" \| "Archivé"), categoryName, barcode, updatedAt}` ; produits fusionnés exclus des actifs |
| GET | `/api/v1/admin/tenants/{tenantId}/products/{productId}` | — | `{tenantId, tenantName, id, name, description, price, buyPrice, stockQuantity, minimumThreshold, trackStock, status, statusLabel, categoryName, barcode, createdAt, updatedAt}` |
| GET | `/api/v1/admin/expenses` | `tenantId`, `storeId`, `category` (clé Bref : `goods_purchase`, `rent`, `salaries`, `transport`, `utilities`, `telecom`, `marketing`, `taxes`, `maintenance`, `other`), `from` / `to` (AAAA-MM-JJ inclus), `q` (libellé ou note), `cursor`, `limit` | `{page: {items: [{tenantId, tenantName, id, storeId, storeName, category, categoryLabel, amount, expenseDate, label, paymentMethod, paymentMethodLabel, createdAt}], nextCursor, hasMore}, totalAmount}` — date de dépense la plus récente d'abord ; `totalAmount` = somme de **toutes** les dépenses des filtres (pas seulement la page) ; dépenses supprimées exclues |
| GET | `/api/v1/admin/tenants/{tenantId}/expenses/{expenseId}` | — | `{tenantId, tenantName, id, storeId, storeName, category, categoryLabel, amount, expenseDate, label, note, paymentMethod, paymentMethodLabel, createdByName (null si inconnu → afficher « — »), createdAt, updatedAt}` |

Un compte qui n'a jamais utilisé le module (dépenses…) donne une liste vide, jamais une erreur. Erreurs (422 `VALIDATION_ERROR`) : `tenantId` mal formé « Compte invalide » ; `storeId` « Boutique invalide » ; identifiant de fiche mal formé « Identifiant invalide » ; `status` « Statut inconnu : choisissez active ou archived » ; `category` « Catégorie de dépense inconnue » ; dates « La date de début est invalide (format AAAA-MM-JJ) », « La date de début doit précéder la date de fin » ; `q` « La recherche ne peut pas dépasser 100 caractères » ; plus de 1 000 comptes à parcourir en une requête « Trop de comptes à parcourir : filtrez la liste par compte ». Détail : compte inconnu → 404 `TENANT_NOT_FOUND` « Compte introuvable » ; fiche inconnue ou d'un autre compte → 404 `CLIENT_NOT_FOUND` « Client introuvable », `PRODUCT_NOT_FOUND` « Produit introuvable », `EXPENSE_NOT_FOUND` « Dépense introuvable ».

**Utilisateurs** (`keevo/backend/src/main/java/com/keevo/admin/user/adapter/in/rest/AdminUserController.java`, modèles `.../user/domain/model/AdminUserViews.java`) :

| Méthode | Route | Paramètres | Réponse `data` |
|---|---|---|---|
| GET | `/api/v1/admin/users` | `role` (`OWNER`, `MANAGER`, `EMPLOYEE`, `VIEWER`, `SUPER_ADMIN`, sans casse), `active` (`true` \| `false`), `tenantId`, `q` (nom, téléphone, e-mail, identifiant), `cursor`, `limit` | page de `{id, displayName ("Utilisateur sans nom" si aucun nom), phoneNumber, email, active, superAdmin, memberships: [{tenantId, tenantName, role, roleLabel ("Propriétaire", "Gérant", "Employé", "Lecteur"), active}], deviceCount, lastSeenAt, createdAt}`, inscription la plus récente d'abord |
| GET | `/api/v1/admin/users/stats` | `from` / `to` (AAAA-MM-JJ ; défaut du 1er du mois à aujourd'hui ; ≤ 366 jours), `role` (sauf `SUPER_ADMIN`), `active` | `{totalUsers, activeUsers, newUsers, from, to, byRole: [{role, label, count}]}` — super-administrateurs exclus ; `newUsers` = inscrits entre `from` et `to` |
| GET | `/api/v1/admin/users/{userId}` | — | un élément de la liste |
| GET | `/api/v1/admin/users/{userId}/devices` | — | `[{sessionId, deviceLabel ("Appareil inconnu" si absent), tenantId, tenantName ("Console super-admin" pour une session sans compte), lastUsedAt}]` — sessions ouvertes (16.11), la plus récente d'abord |

Erreurs : `role` → 422 « Rôle inconnu : choisissez OWNER, MANAGER, EMPLOYEE, VIEWER ou SUPER_ADMIN » ; `active` → 422 « Le filtre « actif » doit valoir true ou false » ; `role=SUPER_ADMIN` sur les stats → 422 « Les statistiques portent sur les utilisateurs des comptes, pas sur les super-administrateurs » ; période > 366 jours → 422 « La période ne peut pas dépasser 366 jours » ; `userId` mal formé → 422 « Utilisateur invalide » ; inconnu → 404 `USER_NOT_FOUND` « Utilisateur introuvable ».

**Actions Bref sur un utilisateur → routes existantes du compte** (prendre l'entreprise dans `memberships[]` avec `role = OWNER`) :
- Désactiver / réactiver (`PATCH /users/:id/status`) → `POST /api/v1/admin/tenants/{tenantId}/suspend` / `/reactivate` (`AdminTenantPlanController.java`).
- Changer l'offre (`PATCH /users/:id/plan`) → `POST /api/v1/admin/tenants/{tenantId}/plan/premium` (offre en paramètre, 19.7b) / `/plan/free`, ou `POST /api/v1/admin/subscriptions/{tenantId}/activate`.
- Supprimer (`DELETE /users/:id`) → `POST /api/v1/admin/tenants/{tenantId}/force-delete`.
- Appareils de synchronisation d'un compte → `GET /api/v1/admin/sync/tenants/{tenantId}/detail`.
- Versions minimales de l'app → `/api/v1/admin/app-versions` (19.10) ; offres → `/api/v1/admin/plan-profiles` (19.7).

Toute autre session qu'une session super-admin → 403 `FORBIDDEN` sur toutes ces routes.

### À implémenter côté dashboard

1. Remplacer les appels api-bref `GET /v1/dashboard/overview|growth|top-shops|activity` par `/api/v1/admin/dashboard/overview|growth|top-accounts|activity` (cartes utilisateurs, comptes, boutiques, factures, CA avec tendance ; courbe mensuelle ; classement des comptes ; fil d'activité `label` + `detail`).
2. Écrans Clients / Produits / Dépenses inter-comptes : filtres compte (sélection depuis `GET /api/v1/admin/tenants`), boutique, recherche, statut, catégorie, période ; bouton « Voir plus » sur `nextCursor` (plus de numéros de page) ; total des dépenses depuis `totalAmount` ; fiche via `/api/v1/admin/tenants/{tenantId}/…/{id}`.
3. Écran Utilisateurs : filtres rôle / actif / compte / recherche, rôles par compte (`memberships[].roleLabel`), statistiques, fiche et appareils ; actions statut / offre / suppression via les routes du compte ci-dessus.
4. Ne plus appeler en super-admin `POST/PATCH /customers…`, `POST/PATCH /users…` (abandon proposé A1–A4) ; campagnes → 21.6.
5. Afficher `error` pour tout 422 / 404 ; aucun identifiant à l'écran.

### Critères d'acceptation

- Given une session super-admin, when on ouvre le tableau de bord, then cartes, courbe, classement et fil d'activité s'affichent depuis l'API Keevo uniquement, en français, montants en FCFA.
- Given les listes clients / produits / dépenses, when on filtre par compte puis on clique « Voir plus », then les éléments suivants s'ajoutent sans doublon ; changer un filtre repart de la première page.
- Given la liste des utilisateurs, when une personne est membre de deux comptes, then elle apparaît une seule fois avec ses deux rôles.
- Given une erreur 422 ou 404, then le message `error` est affiché tel quel.
- Given une session non super-admin, then aucune de ces pages n'est accessible (403).

### Clé de suivi

`d20-13-api-super-admin-heritee-bref`
