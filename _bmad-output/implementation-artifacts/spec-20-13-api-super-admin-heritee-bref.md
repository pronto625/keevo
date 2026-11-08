---
title: '20-13 API super-admin héritée de Bref — vue plateforme, listes inter-comptes, utilisateurs'
type: 'feature'
created: '2026-11-08'
status: 'in-review'
baseline_revision: 'f9ce63f0ddf86f6e49549a8ceab33a41047bfc0b'
review_loop_iteration: 1
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

**Approach:** a route-by-route mapping (table below). Existing Keevo admin endpoints are reused; the gaps are new super-admin endpoints under `/api/v1/admin/**`: a Bref-shaped platform dashboard (`/admin/dashboard/*`), cross-account customers / products / expenses lists with detail (`/admin/customers`, `/admin/products`, `/admin/expenses`, detail under `/admin/tenants/{tenantId}/…`), a platform users read model (`/admin/users`, stats, detail, devices) and — by the user's decision of 2026-11-08 (A1–A6) — the Bref super-admin writes on one account's customers and members (`/admin/tenants/{tenantId}/customers…`, `/members…`) through the crm and identity api ports. Every platform GMV reads the revenue relations of 20.9 through one admin adapter. Cross-tenant reads are **`UNION ALL` statements over the tenant schemas run in chunks of 100**, never one query per tenant.

## Boundaries & Constraints

**Always:**
- Secured exactly like the existing admin endpoints: path under `/api/v1/admin/**` (`SecurityConfig` → role `SUPER_ADMIN`), `CurrentActor.requireSuperAdmin()` in every handler, `@EntitlementExempt("super-admin")` on the controller. Non-super-admin → 403.
- Reads are additive: no change of an existing response field's name; the routed GMV keeps its fields, now from the revenue source (see the note on « ventes » below).
- Writes (decisions A1–A4) go through the owning module's api port only — crm `ClientCommandPort` (create / update / marketing consent), identity `EmployeeCommandPort` (create / update / deactivate / reactivate / remove) — inside the account's tenant scope (`TenantScope`: tenant context + new transaction). Same validation, French messages and audit as the owner flows; the audit actor is the super-admin. No SQL on module tables from `admin`.
- Revenue = the 20.9 source (`RevenueRelations`, consumed only by `admin/platformrevenue/adapter/out/RevenueSourcePlatformRevenueAdapter`): COMPLETED sales ∪ counted invoices (`source_sale_id IS NULL`) − credit notes; a POS sale bridged to an invoice counts once. Branches: tenants holding `sales` OR the invoicing tables (`documents(hasSales, hasInvoicing)` per tenant). The revenue instant is `COALESCE(occurred_at, created_at)` (legacy sales without `occurred_at` keep their creation time, as in 9-4 / 9-7), so the all-time total equals the sum of the months.
- No double counting: users = distinct `public.users` (a person member of several accounts counts once); accounts = `public.tenants` (a migrated Bref owner becomes one tenant, 24.2/24.7 merge identities); revenue per tenant from the single source; sellers (A5) summed per person across accounts.
- KV-ADMIN system tenant and `DELETED` tenants excluded everywhere (lists, memberships, devices, filters); super-admin users excluded from platform user figures (same rule as 9-4 analytics).
- Cross-tenant statements run in chunks of `keevo.admin.cross-tenant.chunk-size` (default 100) tenants; a chunk failing on a schema / data error is retried tenant by tenant and only the failing tenant is skipped (WARN with the tenant id); connection / resource failures fail the request.
- Lists: keyset pagination (`cursor` + `limit`, default 20, max 100), cursor bound to the filters and type-checked (other filters or a malformed key → 422 « Curseur invalide »), each branch ordered and limited on an existing index; tie on equal keys broken by tenant id; chunk pages merged in Java on (keys, tenant id).
- French labels for every code shown (roles, statuses, categories, events); no technical code used as a label.
- Errors: `VALIDATION_ERROR` 422 (the platform mapping of a domain validation error) with a French message; `TENANT_NOT_FOUND`, `CLIENT_NOT_FOUND`, `PRODUCT_NOT_FOUND`, `EXPENSE_NOT_FOUND`, `USER_NOT_FOUND`, `EMPLOYEE_NOT_FOUND` 404; removing an owner → 403.

**Never:** no write on module tables from `admin` (ports only), no per-tenant query loop on the new paths, no OFFSET, no connection to production, no change to `keevo/app` or `keevo/dashboard`, no campaign / app-version / plan-catalogue re-implementation (other stories), no deletion of an owner or of an employee record (history kept).

## Bref → Keevo route mapping

Bref reference: `api-bref` develop, file:line of the handler. Keevo paths are under `/api/v1`. Statuses: **existing** (controller:line), **extended** (this story), **new** (this story), **other story**.

| # | Bref route (verb path — purpose) | Keevo equivalent | Status |
|---|---|---|---|
| D1 | `GET /dashboard/owner-summary` (`dashboard.controller.ts:29`) — owner KPIs over a period | `GET /dashboard/summary` (`reporting/dashboard/adapter/in/rest/DashboardController.java:40`, revenue source since 20.9) for the owner; the super-admin uses D2–D5 | existing |
| D2 | `GET /dashboard/overview` (`:47`) — users, shops, invoices, revenue with trends | `GET /admin/dashboard/overview` — users, accounts, stores, documents, revenue (revenue source), cached 120 s | new |
| D3 | `GET /dashboard/growth?months=7` (`:55`) — per month users, shops, invoices | `GET /admin/dashboard/growth?months=` — per month new users, accounts, stores, invoices issued, revenue, cached 120 s | new |
| D4 | `GET /dashboard/top-shops?limit=5` (`:67`) — best shops by revenue | `GET /admin/dashboard/top-accounts?limit=&days=` — best accounts by revenue (documents carry no store yet, 20-14 / 24.4); per-store sales ranking stays `GET /admin/analytics/top-stores` (`AdminAnalyticsController.java:71`) | new |
| D5 | `GET /dashboard/activity?limit=10` (`:79`) — recent platform events | `GET /admin/dashboard/activity?limit=` — accounts, users, stores, issued invoices, open quotes and **received payments** (A6, invoicing V17 index) | new |
| D6 | (Keevo) platform GMV in `GET /admin/analytics/overview`, `/growth` (`AdminAnalyticsController.java:38,45`), `GET /admin/operations/overview`, `/trend`, `/tenants` (`AdminOperationsController.java:43,55,70`) | same endpoints, GMV and order counts from the revenue source (routed by 20.9) | extended |
| C1 | `GET /customers` (`customer-admin.controller.ts:88`) — cross-shop customers, `ownerId`/`shopId`/`q`/sort | `GET /admin/customers?tenantId=&storeId=&q=&cursor=&limit=` | new |
| C2 | `GET /customers/:id` (`:142`) | `GET /admin/tenants/{tenantId}/customers/{customerId}` | new |
| C3 | `POST /customers` (`:166`) — manual customer creation | `POST /admin/tenants/{tenantId}/customers` (crm `ClientCommandPort.create`) — A1 | new |
| C4 | `PATCH /customers/:id` (`:191`) | `PATCH /admin/tenants/{tenantId}/customers/{customerId}` (crm `ClientCommandPort.update`) — A1 | new |
| C5 | `PATCH /customers/:id/marketing-consent` (`:213`) | `PUT /admin/tenants/{tenantId}/customers/{customerId}/marketing-consent` (crm `ClientCommandPort.setMarketingConsent`) — A1 | new |
| P1 | `GET /products` (`product-admin.controller.ts:68`) — cross-shop catalogue | `GET /admin/products?tenantId=&q=&status=&cursor=&limit=` (keyset) + `GET /admin/tenants/{tenantId}/products/{productId}` — **canonical** list for the Bref dashboard; the Keevo console list `GET /admin/catalog/products` (`AdminCatalogController.java:43`, OFFSET, in-memory, CSV export, stock filters) overlaps it and stays for the current console until it moves to the keyset list (see Design Notes) | new |
| E1 | `GET /expenses` (`expense-admin.controller.ts:62`) — cross-account expenses + `totalAmount` | `GET /admin/expenses?tenantId=&storeId=&category=&from=&to=&q=&cursor=&limit=` (+ `totalAmount`, first page) and `GET /admin/tenants/{tenantId}/expenses/{expenseId}` | new |
| U1 | `POST /users` (`users.controller.ts:148`) — create a user | `POST /admin/tenants/{tenantId}/members` (identity `EmployeeCommandPort.createMember`: owner creation rules) — A2; accounts themselves come from sign-up | new |
| U2 | `GET /users` (`:248`) — all users + device summary | `GET /admin/users?role=&active=&tenantId=&q=&cursor=&limit=` | new |
| U3 | `GET /users/:id/devices` (`:297`) | `GET /admin/users/{userId}/devices` (live device sessions of 16.11); per-account sync devices stay `GET /admin/sync/tenants/{tenantId}/detail` (`AdminSyncMonitoringController.java:59`) | new |
| U4 | `GET /users/stats` (`:338`) — totals, by role, top sellers | `GET /admin/users/stats?from=&to=&role=&active=` — totals, by role, `averageSalesPerSeller`, `topPerformers` (A5, revenue source) | new |
| U5 | `GET /users/:id` (`:371`) | `GET /admin/users/{userId}` | new |
| U6 | `PATCH /users/:id/status` (`:415`) — (de)activate | one member: `POST /admin/tenants/{tenantId}/members/{userId}/deactivate` / `/reactivate` (identity rules, sessions revoked) — A3; whole account: `POST /admin/tenants/{tenantId}/suspend` / `/reactivate` (`AdminTenantPlanController.java:124,138`) | new + existing |
| U7 | `PATCH /users/:id` (`:459`) — edit a user | `PATCH /admin/tenants/{tenantId}/members/{userId}` (identity update + role change rules) — A2 | new |
| U8 | `GET /users/me/plan/downgrade-check`, `PATCH /users/me/plan`, `POST /users/me/plan/checkout`, `POST …/checkout/:requestId/confirm` (`:568,600,860,967`) — owner self-service | not super-admin routes | other story: **21.1** (payments) / **21.10** (offer) |
| U9 | `PATCH /users/:id/plan` (`:704`) — grant a plan | `POST /admin/tenants/{tenantId}/plan/premium` (plan parameter, 19.7b) / `/plan/free` (`AdminTenantPlanController.java:94,112`), `POST /admin/subscriptions/{tenantId}/activate` (`AdminSubscriptionController.java:51`) | existing |
| U10 | `DELETE /users/:id` (`:1120`) | one member: `DELETE /admin/tenants/{tenantId}/members/{userId}` (deactivation rules + membership deleted, never the owner) — A4; whole account: `POST /admin/tenants/{tenantId}/force-delete` (`AdminTenantController.java:121`) | new + existing |
| V1 | `GET /app-version/check` (`app-version.controller.ts:35`) | `GET /app-version/check` (`identity/appversion/adapter/in/rest/AppVersionCheckController.java:53`) | other story: **19.10** (done) |
| V2 | `PUT /app-version` (`:49`), `GET /app-version` (`:63`) | `PUT`/`GET`/`DELETE /admin/app-versions…` (`AppVersionAdminController.java:52,68,104`) | other story: **19.10** (done) |
| L1 | `GET /plans` (`plans.controller.ts:12`) — public offer | public offer endpoint → **21.10**; super-admin plan profiles `GET/PUT /admin/plan-profiles…` (`AdminPlanProfileController.java:57,65,91`, 19.7) | other story: **21.10** / 19.7 (done) |
| K1 | `POST/GET/GET :id/PATCH/DELETE /campaigns`, `POST :id/send`, `POST preview-targeting` (`campaign.controller.ts:47,59,101,109,125,133,145`) | — | other story: **21.6** (backlog) |

**Décisions:**
- 2026-11-08 — l'utilisateur demande de tout développer : A1 (création, modification, consentement d'un client par le super-admin), A2 (création, modification d'un membre), A3 (activation / désactivation d'un membre, sessions révoquées), A4 (suppression d'un membre, jamais le propriétaire), A5 (`averageSalesPerSeller`, `topPerformers`), A6 (événements « paiement reçu »). Aucun abandon.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Owner token on any new route | `GET /admin/users`, `POST /admin/tenants/{id}/members` as OWNER | refused | 403 |
| POS sale bridged to an invoice | sale 5 000 COMPLETED + automatic invoice `source_sale_id` | platform revenue +5 000 once | — |
| Document-only tenant | invoicing tables, no `sales` table; invoice 7 000, credit note 2 000 | revenue 5 000, documents 1 | — |
| Legacy sale | `occurred_at` NULL, `created_at` in October | counted in October and all time | — |
| One broken schema | a branch fails at run time | that tenant skipped (WARN), the others answered | — |
| Person in two accounts | one user, OWNER of two tenants | users total +1 | — |
| Tenant without expenses table | expenses module never used | not a branch, empty list | — |
| Keyset | equal sort keys in two accounts at a page boundary | each row once, in order | — |
| Cursor | other filters, wrong key type, forged tenant | refused « Curseur invalide : relancez la liste depuis le début » | 422 |
| Expenses pages | 3 expenses, `limit=2` | `totalAmount` on page 1, null on page 2 | — |
| Older schema | a detail column missing | detail 404, list still works | 404 |
| Super-admin creates a customer | name + phone | 201, audit `CLIENT_CREATED` by the super-admin, duplicate phone = warning count | 422 « Le nom du client est obligatoire » |
| Super-admin removes the owner | owner's employee row | refused « Le propriétaire du compte ne peut pas être supprimé » | 403 |
| Deactivate a member | live sessions | member inactive, refresh tokens revoked | — |
| Unknown ids | tenant / customer / product / expense / user / member | not found | 404 |

</intent-contract>

## Code Map

Paths under `keevo/backend/src/main/java/com/keevo/` unless stated.
- `admin/analytics/application/service/AdminAnalyticsService.java`, `admin/operations/application/service/AdminOperationsService.java` -- platform GMV (per-tenant loops, 9-4 / 9-7) switched to the revenue port.
- `reporting/revenue/adapter/out/persistence/RevenueRelations.java`, `ProvisionedRevenueSource.java`, `reporting/revenue/domain/port/out/RevenueQueryPort.java` -- the single revenue source (20.9).
- `crm/api/ClientCommandPort.java`, `crm/contact/adapter/in/api/ClientApiAdapter.java`, `crm/contact/application/usecase/{CreateClient,UpdateClient,SetClientMarketingConsent}UseCase.java` -- customer writes (A1).
- `identity/api/EmployeeCommandPort.java`, `identity/employee/adapter/in/api/EmployeeCommandAdapter.java`, `identity/employee/application/service/{CreateEmployee,UpdateEmployee,ChangeEmployeeRole,DeactivateEmployee,ReactivateEmployee}Service.java` -- member writes (A2–A4).
- `admin/catalog/application/service/AdminCatalogService.java`, `admin/tenant/**`, `admin/subscription/**`, `admin/sync_monitoring/**`, `identity/auth/adapter/in/rest/DeviceSessionController.java` -- existing admin / session endpoints mapped above.
- `shared/infrastructure/persistence/{TenantSchema,TenantContext}.java`, `shared/infrastructure/security/CurrentActor.java`, `shared/module/EntitlementExempt.java` -- schema gate, tenant scope and admin security.
- `resources/db/modules/{crm,catalog,expenses,invoicing}/*`, `resources/db/migration/V36__device_sessions.sql`, `V41__users_multiple_identifiers.sql` -- columns and indexes read.
- Bref (read-only): `api-bref/src/dashboard/**`, `customer/customer-admin.controller.ts`, `product/product-admin.controller.ts`, `expense/expense-admin.controller.ts`, `users/users.controller.ts`, `users/infrastructure/persistence/relational/repositories/user.repository.ts` (stats), `app-version/*`, `plans/*`, `campaign/*`.

## Tasks & Acceptance

**Execution:**
- `admin/crosstenant/*` -- `TenantTable` (table + columns specs), `TenantBranch` (with table presence), `CrossTenantSql` (live tenants + presence in one catalogue query; chunked `UNION ALL` with per-tenant retry), `KeysetSort` / `KeysetCursor` (typed keys, filter-bound, validated), `CrossTenantLists` (chunk pages merged), `TenantScope` (tenant context + new transaction for writes), `AdminParams`, `CursorPage`.
- `admin/platformrevenue/**` -- `PlatformRevenuePort` (+ `SELLER` bucket) + `RevenueSourcePlatformRevenueAdapter` (qualified 20.9 relations, sales OR invoicing branches, chunked).
- `admin/analytics`, `admin/operations` -- GMV and order counts from the port; tests updated.
- `admin/dashboard/**` -- overview, growth (cached), top accounts, activity (+ payments).
- `admin/crossaccount/**` -- customers, products, expenses list + detail.
- `admin/user/**` -- users list, stats (+ sellers), detail, devices.
- `admin/tenantwrite/**` -- customer and member writes (A1–A4); `crm/api` (`CreateClientInput`, `CreatedClient`, port methods), `identity/api` (`CreateMemberInput`, `CreatedMember`, `UpdateMemberInput`, port methods), `identity/employee` (`RemoveEmployeeUseCase` / `RemoveEmployeeService`, `UserMembershipRepository.remove`).
- `admin/package-info.java` (`crm :: api`, `expenses :: api`, `reporting :: revenue`), `reporting/revenue/adapter/out/persistence/package-info.java` (`@NamedInterface("revenue")`), `expenses/api/ExpenseLabels.java` -- module boundaries.
- `resources/db/migration/V59__users_created_at_keyset_index.sql` -- `public.users (created_at DESC, id DESC)`; `resources/db/modules/invoicing/V17__invoice_payments_paid_at_index.sql` -- `invoice_payments (paid_at DESC) WHERE paid_at IS NOT NULL` (A6).
- Tests: unit (services, cursor, chunking, qualification, removal) and PostgreSQL (revenue adapter, dashboard reader) and full context per endpoint incl. 403; `docs/entitlements/endpoints.md` regenerated.

**Acceptance Criteria:**
- Given the mapping table, when it is compared with `api-bref/src/**/*.controller.ts` (develop), then every Bref admin route has a delivered and tested Keevo equivalent, is covered by another story, or carries the user's written decision.
- Given Keevo tenants with POS sales, document-only tenants and bridged invoices, when the platform figures are read, then revenue and accounts are counted once (R4).
- Given a non-super-admin token, when any new route is called, then 403.
- Given a super-admin write (A1–A4), then the owning module's rules and audit apply, with the super-admin as actor.

## Spec Change Log

- 2026-11-08 — review 1 (coordinator): the single `UNION ALL` over every tenant could exhaust `max_locks_per_transaction` (~10–25 AccessShareLocks per branch, 64 × max_connections slots) around 250–300 tenants → chunks of 100, chunk pages merged in Java, no 422 « trop de comptes » any more; one bad schema no longer fails a request (chunk retried tenant by tenant); revenue branches « sales OR invoicing » and the `COALESCE(occurred_at, created_at)` instant restored; document figures on the payment-state index expression, activity legs aligned on the document rules, union of tenants with stores or invoices; table presence resolved once per request; overview in one revenue pass + 120 s cache; `totalAmount` on the first page only; detail endpoints checked against the catalogue (404 on an older schema) with explicit archived / merged states; users filters and devices restricted to live accounts; typed cursor validation; qualifier case-insensitive, refuses LATERAL / comma joins. Public migration renamed V59 (V57 = 20-7, V58 = 20-11). Decisions A1–A6: the user asked to build all six (no abandonment). KEEP: the catalogue-driven branch set, the tenant tie-break of the keyset, the single revenue adapter.

## Review Triage Log

### 2026-11-08 — Review pass (coordinator, 13 items)
- `[high]` `[patch]` Lock-table limit of a single union — chunked statements (`keevo.admin.cross-tenant.chunk-size`), Java merge, guard removed.
- `[medium]` `[patch]` One bad schema broke every figure — per-chunk retry tenant by tenant, WARN, others kept (unit + PostgreSQL tests).
- `[medium]` `[patch]` Revenue branches / legacy sales — « sales OR invoicing », `COALESCE(occurred_at, created_at)`; all time = Σ months (test).
- `[medium]` `[patch]` `documentFigures` NULL payment state, activity legs, null amounts, tenant set — fixed, PostgreSQL test.
- `[low]` `[patch]` Repeated catalogue queries — presence carried by `TenantBranch`.
- `[low]` `[patch]` Overview cost — one revenue pass, Caffeine 120 s on overview / growth, `totalAmount` page 1 only.
- `[medium]` `[patch]` Detail endpoints on older schemas / visibility — catalogue check (404), `statusLabel` archived / merged.
- `[medium]` `[patch]` Users filter / devices on deleted accounts, device predicates — live accounts only, same predicate.
- `[medium]` `[patch]` Tampered cursor could reach SQL — typed validation → 422; qualifier hardening + tests.
- `[low]` `[patch]` Documentation — « ventes » note, top-shops → top-accounts, products overlap.
- `[medium]` `[patch]` Tests — dashboard reader PostgreSQL, keyset tie at a page boundary, multi-page expenses, older schema, chunk isolation, documents-only tenant, 422 display names.
- `[n/a]` Decisions A1–A6 — built (user decision 2026-11-08).
- `[low]` `[route]` Remaining per-tenant loops — listed in Design Notes for routing (not small changes).

## Design Notes

**Pattern.** Hexagonal slices per area (REST adapter → use case → service → port → adapter), as the other admin areas. Cross-tenant reads use a **Composite query run in chunks**: `CrossTenantSql.query` builds, per chunk of tenants, one `UNION ALL` of per-tenant branches (schema-qualified, index-ordered, `LIMIT`ed), the outer query aggregates or re-orders and limits the chunk, Java adds the chunks up or merges the chunk pages on (keys, tenant id). The branch set comes from **one catalogue query per request** (`public.tenants` × `pg_attribute` on `to_regclass(schema.table)` for every `TenantTable`), carried by `TenantBranch`, so a module never used by a tenant is simply not a branch and a missing column never fails a statement. Revenue goes through an **Adapter** over the 20.9 relations (the only class importing reporting) that qualifies `sales`, `sale_items`, `invoices`, `invoice_items`, `credit_notes`, `credit_note_items` (tests fail on an unknown table, a LATERAL or a comma join). When 20-14 moves the source to `shared.capability.revenue`, only that adapter's import and the `reporting :: revenue` entry change. Writes use a **Unit of work per account** (`TenantScope`) around the owning module's api port.

**Lock budget (prod assumption).** A branch takes ~10–25 AccessShareLocks (tables + their indexes) held until the statement's transaction ends. PostgreSQL's shared lock table holds `max_locks_per_transaction × (max_connections + max_prepared_transactions)` entries; with the defaults (64 × 100) a single statement over every tenant fails around 250–300 tenants. The API runs each chunk (default 100 tenants, ≤ ~2 500 locks) in its own autocommitted statement, so the locks are released between chunks. Assumption for prod (`keevo.ad2s.cm`): PostgreSQL defaults or higher (`max_locks_per_transaction` ≥ 64); with a lower value, lower `keevo.admin.cross-tenant.chunk-size`.

**What Keevo used for platform KPIs before:** one query per tenant schema per endpoint (`AdminAnalyticsService`, `AdminOperationsService`, `AdminCatalogService` loops). No aggregated public table, materialised counter or snapshot. This story moves the GMV part of those loops to the chunked revenue port and builds every new path on the chunked union.

**Remaining per-tenant loops (not moved — each is more than a small change; for routing):**
- `AdminAnalyticsService.getOverview` — per tenant: active STORE count, stores with a sale in 30 days, non-archived products (`kv_x.stores`, `kv_x.sales`, `kv_x.products`).
- `AdminAnalyticsService.getTopStores` — per tenant: GMV / transactions per store over the period and the previous one.
- `AdminAnalyticsService.getAdoption` — per active tenant: feature footprints (inventory sessions, transfers, reports, preferences, cooldowns, sync log).
- `AdminAnalyticsService.getGeography` — per tenant: owner WhatsApp phone through `OwnerRecipientResolver`.
- `AdminOperationsService.listTenantActivity` — per tenant: last activity (`TenantLastActivitySql`), STORE count, plan from `subscriptions`.
- `AdminOperationsService.listInactiveTenants` — per tenant: last activity and plan.
- `AdminCatalogService` (`/admin/catalog/products`, `/summary`, CSV export) — per tenant: products with category (in-memory OFFSET page) and six counts.
Each needs its own chunked SQL (several use `TenantLastActivitySql` or a Java resolver), a result-shape change for the paginated ones, and rewritten mock-based tests.

**Cost (FUS-NFR11), T = tenant schemas holding the table, c = chunk size, n = page size.**
- Every endpoint: 1 tenants + presence query (O(T) catalogue lookups) + ⌈T/c⌉ statements per figure (+ `totalAmount` on the first expenses page, + 1 membership batch on users). Never a query per tenant, except the per-tenant retry of a failing chunk.
- Lists: each branch is an index range scan in sort order with `LIMIT n+1` — customers / products on `(updated_at, CAST(id AS text))`, expenses on `idx_expenses_date (expense_date, created_at, id) WHERE archived_at IS NULL`; each chunk returns ≤ n+1 rows; Java merges ⌈T/c⌉·(n+1) rows.
- Revenue: each branch is the 20.9 relation with the window on `COALESCE(occurred_at, created_at)` (sales no longer use the `occurred_at` index on windows — same cost as the 9-4 / 9-7 queries it replaces; invoices keep `issue_date`'s day), grouped per tenant / day / month / seller inside the branch. Overview: one all-time pass by month (total = Σ months), cached 120 s.
- Activity: per tenant ≤ 4 legs each `LIMIT n` on an index (`stores` small, `idx_invoices_created`, invoicing V17 `paid_at`).
- Users: public tables only — list keyset on `idx_users_created_at_id` (V59), memberships by one `user_id = ANY(?)` per page, devices on `idx_refresh_tokens_live_by_user`; sellers (A5) one chunked revenue pass + one names query.
- Writes: 1 tenant query + the module's own use case in one transaction.

**Fields that now include invoices.** In `/admin/analytics/overview` (`totalCompletedSales`), `/admin/analytics/growth` (`salesCount`) and `/admin/operations/overview|trend|tenants` (`totalOrders`, `orderCount`) the count is the number of **revenue documents** (POS sales + counted invoices), no longer of sales only; clients must label them « Documents » / « Ventes et factures », never « Ventes ». Names kept for compatibility (documented in the services' javadoc and in the dashboard section).

**Products overlap.** `/admin/products` (keyset, all accounts, Bref fields) is the canonical cross-account product list; `/admin/catalog/products` (Keevo console, OFFSET in memory, stock filters, CSV) overlaps it and stays until the console moves to the keyset list (retirement = a decision with the console's CSV export, not in this story).

**Deviations from Bref (documented, not routes):** « recent » sorts by last update (`updated_at`, the indexed key); Bref's other sorts are not offered on the cross-account lists; `page`/`limit` become `cursor`/`limit`; overview revenue has no pending / overdue amounts (counts given); member creation keeps Keevo's rules (temporary password generated, change required at first login — Bref took a password); a removed member keeps an inactive employee record for the history (Bref soft-deletes the user); `averageSalesPerSeller` = revenue of the period / sellers with revenue (Bref divided by its top 5 only).

## Verification

**Commands:**
- `kmvn.sh -o -q test -Dtest='com/keevo/admin/**/*Test*'` -- expected: pass
- same for `com/keevo/reporting/**`, `com/keevo/architecture/**`, `com/keevo/shared/**`, `com/keevo/crm/**`, `com/keevo/identity/**`, `com/keevo/expenses/**`, `com/keevo/invoicing/**` -- expected: pass

## Auto Run Result

**Result:** implemented; review 1 (coordinator, 13 items) fixed and decisions A1–A6 built (user, 2026-11-08); all touched packages green. Status `in-review`, follow-up review recommended (new write paths).

**Delivered:**
- Read endpoints (14 handlers): `/admin/dashboard/{overview,growth,top-accounts,activity}`, `/admin/customers`, `/admin/products`, `/admin/expenses` and their details under `/admin/tenants/{tenantId}/…`, `/admin/users`, `/admin/users/stats`, `/admin/users/{userId}`, `/admin/users/{userId}/devices`.
- Write endpoints (8 handlers, A1–A4): `POST /admin/tenants/{tenantId}/customers`, `PATCH …/customers/{customerId}`, `PUT …/customers/{customerId}/marketing-consent`, `POST …/members`, `PATCH …/members/{userId}`, `POST …/members/{userId}/deactivate|reactivate`, `DELETE …/members/{userId}` — through crm `ClientCommandPort` and identity `EmployeeCommandPort` (new methods, new `RemoveEmployeeService`), audit actor = the super-admin. `docs/entitlements/endpoints.md` regenerated.
- A5: `averageSalesPerSeller` / `topPerformers` from the revenue source (`SELLER` bucket, chunked, per person). A6: « Paiement reçu » events, invoicing `V17__invoice_payments_paid_at_index.sql` (idempotent, `SET LOCAL lock_timeout`).
- `admin/crosstenant`: table presence in one catalogue query per request, chunked unions (100) with per-tenant retry, typed filter-bound cursors, Java merge of chunk pages; `TenantScope` for writes. `admin/platformrevenue`: « sales OR invoicing » branches, `COALESCE(occurred_at, created_at)`, hardened qualifier.
- Routed items closed: 20.9 (platform GMV on the single source) and 20.10 (cross-account expenses).
- Public migration `V59__users_created_at_keyset_index.sql` (renamed from V60: V57 = 20-7, V58 = 20-11).
- Validation errors answer **422** `VALIDATION_ERROR` (platform mapping), owner removal 403.

**Tests (via `kmvn.sh`, clean build, one package at a time):** admin 665 / 0 failures; reporting 451 / 0; architecture 125 / 0 (Modulith, ArchUnit, interpolation guard, `UserGlobalRoleReadArchTest`, `EndpointEntitlementCoverageTest`); shared 1 320 / 0; crm 239 / 0; identity 865 / 0; invoicing 324 / 0 (migration tests moved to V17); expenses 39 / 0. New: `CrossTenantSqlChunkTest`, `PlatformDashboardReaderPostgresIntegrationTest`, `TenantWriteServiceTest`, `RemoveEmployeeServiceTest`, plus revenue (documents-only tenant, broken branch skipped, legacy sale, all time = Σ months), keyset tie across tenants, typed cursor, multi-page expenses, older schema, A1–A4 full context incl. 403 on every write route. Note: `ModuleConsoleFullContextTest.backfillJob` failed once on a 429 of its own polling loop (backfill over ~80 test tenants of the shared database), green on the rerun — pre-existing timing sensitivity, not changed here.

**Decisions:** 2026-11-08 — l'utilisateur demande de tout développer (A1–A6).

**Out of scope, owning stories:** campaigns → 21.6; public offer endpoint (`GET /plans`) → 21.10; owner self-service plan / checkout routes → 21.1 / 21.10; documents per store (per-store revenue incl. invoices, top stores with documents) → 20-14 / 24.4; move of the revenue source to `shared.capability.revenue` (one import + the `reporting :: revenue` entry) → 20-14; remaining per-tenant loops (listed in Design Notes) → to be routed by the coordinator.

## Dashboard super-admin (autre dépôt)

> Destinataire : agent IA qui construit le dashboard super-admin dans un **autre dépôt** (ce dépôt ne livre que l'API). Conventions admin (Bearer d'une session super-admin, enveloppe `{"data", "timestamp"}`, erreurs `{"error", "code", "domainCode", "details", "timestamp"}`, afficher `error` tel quel, décider sur `domainCode`, dates ISO-8601 UTC à afficher en heure de Douala) : section « Dashboard super-admin » de `spec-16-4-console-super-admin-modules.md`. Session : `spec-16-11-sessions-longues-et-sures.md`.

### Statut

API livrée (branche de la story 20.13, en attente de fusion) — dashboard à faire. Lectures plateforme + écritures super-admin sur un compte (décision de l'utilisateur du 2026-11-08 : A1–A6 développés).

### Ce qui est fait côté API

- Tableau de bord plateforme repris de Bref (`/dashboard/overview|growth|top-shops|activity`) : `/api/v1/admin/dashboard/*` (`top-shops` devient `top-accounts`). Comptes = entreprises Keevo (un propriétaire Bref migré = une entreprise) ; utilisateurs = personnes distinctes (une personne membre de deux entreprises compte une fois), super-administrateurs exclus ; chiffre d'affaires = source unique de 20.9 (ventes caisse terminées + factures émises/payées hors factures automatiques de caisse − avoirs) : **aucun double comptage**. Overview et growth sont mis en cache 120 s (chiffres décalés d'au plus 2 minutes).
- Le CA des écrans existants `/api/v1/admin/analytics/overview|growth` et `/api/v1/admin/operations/overview|trend|tenants` vient de cette même source. **Attention aux libellés** : `totalCompletedSales` et `salesCount` (analytics), `totalOrders` et `orderCount` (operations) comptent désormais les **documents de CA** (ventes caisse + factures comptées), pas seulement les ventes : les afficher « Documents » ou « Ventes et factures », jamais « Ventes ». Une vente ancienne sans date de vente compte à sa date de création. `top-stores` reste par boutique sur les ventes caisse (les factures n'ont pas encore de boutique).
- Listes inter-comptes reprises de Bref (`/customers`, `/products`, `/expenses`) avec fiche détail, pagination par curseur. `/api/v1/admin/products` est la liste produits de référence pour ce dashboard ; `/api/v1/admin/catalog/products` (console Keevo actuelle, pages numérotées, export CSV) fait doublon et reste en place pour l'instant.
- Utilisateurs de la plateforme (`/users`, `/users/stats` avec vendeurs, `/users/:id`, `/users/:id/devices`).
- Écritures sur un compte (A1–A4) : création / modification / consentement d'un client ; ajout / modification / désactivation / réactivation / suppression d'un membre. Mêmes règles, messages et journal d'audit que dans l'app du propriétaire ; l'auteur journalisé est le super-administrateur.

### À utiliser

**Pagination par curseur (toutes les listes de cette section)** : paramètres `limit` (1–100, défaut 20) et `cursor` ; réponse `data = {items: [...], nextCursor: string|null, hasMore: boolean}`. Page suivante : renvoyer **les mêmes filtres** + `cursor=nextCursor` ; `nextCursor` null = fin. Un curseur réutilisé avec d'autres filtres, modifié ou trop long → 422 `VALIDATION_ERROR` « Curseur invalide : relancez la liste depuis le début » (repartir de la première page). Les `id` / `tenantId` / `storeId` / `employeeId` servent aux URL, jamais à l'écran.

**Tableau de bord** (`keevo/backend/src/main/java/com/keevo/admin/dashboard/adapter/in/rest/AdminPlatformDashboardController.java`, modèles `.../dashboard/domain/model/PlatformDashboardViews.java`) :

| Méthode | Route | Paramètres | Réponse `data` |
|---|---|---|---|
| GET | `/api/v1/admin/dashboard/overview` | — | `{users: {total, active, newThisMonth, newLastMonth, trendPercent}, accounts: {total, active, suspended, newThisMonth, newLastMonth, trendPercent}, stores: {total, active, newThisMonth}, documents: {invoices, unpaid, partiallyPaid, paid, overdue, quotes}, revenue: {total, thisMonth, lastMonth, trendPercent}}` — `trendPercent` = évolution en % (1 décimale) du mois en cours vs le précédent, **null** quand le mois précédent vaut 0 (afficher « Nouveau » ou « — ») ; montants en FCFA entiers ; `quotes` = devis ouverts ; une facture ancienne sans état de paiement compte « payée » si elle est payée, sinon « impayée » |
| GET | `/api/v1/admin/dashboard/growth` | `months` 1–24 (défaut 7) | `[{month: "AAAA-MM", label: "nov. 2026", year, newUsers, newAccounts, newStores, invoicesIssued, revenue, documents}]`, du plus ancien au mois en cours, mois vides à 0 |
| GET | `/api/v1/admin/dashboard/top-accounts` | `limit` 1–20 (défaut 5), `days` 1–366 (défaut 30, jours de Douala jusqu'à aujourd'hui inclus) | `[{tenantId, name, ownerName ("Propriétaire" si sans nom), status, statusLabel ("Actif", "Suspendu", "Suppression programmée"), storeCount, documents, revenue}]` par CA décroissant ; comptes sans CA absents |
| GET | `/api/v1/admin/dashboard/activity` | `limit` 1–50 (défaut 10) | `[{type, label, detail, tenantId, tenantName, occurredAt}]`, le plus récent d'abord ; `label` : « Nouveau compte », « Nouvel utilisateur », « Nouvelle boutique », « Facture créée » (factures émises ou payées), « Devis créé » (devis ouverts), « Paiement reçu » ; `detail` : nom du compte / de la personne / de la boutique, « Facture de 12 000 FCFA », « Devis de 900 FCFA », « Paiement de 2 500 FCFA » (le libellé seul quand il n'y a pas de montant) ; `tenantId`/`tenantName` null pour « Nouvel utilisateur » |

Paramètre hors bornes → 422 `VALIDATION_ERROR`, ex. « Le nombre de mois doit être compris entre 1 et 24 », « Le nombre de comptes doit être compris entre 1 et 20 », « Le nombre de jours doit être compris entre 1 et 366 », « Le nombre d'événements doit être compris entre 1 et 50 » (valeur non entière : « … doit être un nombre entier »).

**Listes inter-comptes** (`keevo/backend/src/main/java/com/keevo/admin/crossaccount/adapter/in/rest/AdminCrossAccountController.java`, modèles `.../crossaccount/domain/model/CrossAccountViews.java`) :

| Méthode | Route | Paramètres | Réponse `data` |
|---|---|---|---|
| GET | `/api/v1/admin/customers` | `tenantId`, `storeId`, `q` (nom ou téléphone, ≤ 100 car.), `cursor`, `limit` | page de `{tenantId, tenantName, id, name, phone, email, storeId, storeName, marketingConsent, createdAt, updatedAt}`, dernière modification d'abord ; clients archivés exclus |
| GET | `/api/v1/admin/tenants/{tenantId}/customers/{customerId}` | — | `{tenantId, tenantName, id, name, phone, email, storeId, storeName, clientType, clientTypeLabel ("Particulier" \| "Entreprise" \| "Autre" \| null), niu, rccm, address, contactName, website, birthDate, notes, archived, statusLabel ("Actif" \| "Archivé"), marketingConsent, marketingConsentAt, marketingOptOutAt, createdAt, updatedAt}` — un client archivé (absent de la liste) reste consultable, avec son état |
| GET | `/api/v1/admin/products` | `tenantId`, `q` (nom, ou code-barres exact), `status` = `active` (défaut) \| `archived`, `cursor`, `limit` | page de `{tenantId, tenantName, id, name, price, stockQuantity, trackStock, status, statusLabel ("Actif" \| "Brouillon" \| "Archivé" \| "Fusionné"), categoryName, barcode, updatedAt}` ; les produits fusionnés sont archivés (liste `archived`) |
| GET | `/api/v1/admin/tenants/{tenantId}/products/{productId}` | — | `{tenantId, tenantName, id, name, description, price, buyPrice, stockQuantity, minimumThreshold, trackStock, status, statusLabel, archived, merged, categoryName, barcode, createdAt, updatedAt}` |
| GET | `/api/v1/admin/expenses` | `tenantId`, `storeId`, `category` (clé Bref : `goods_purchase`, `rent`, `salaries`, `transport`, `utilities`, `telecom`, `marketing`, `taxes`, `maintenance`, `other`), `from` / `to` (AAAA-MM-JJ inclus), `q` (libellé ou note), `cursor`, `limit` | `{page: {items: [{tenantId, tenantName, id, storeId, storeName, category, categoryLabel, amount, expenseDate, label, paymentMethod, paymentMethodLabel, createdAt}], nextCursor, hasMore}, totalAmount}` — date de dépense la plus récente d'abord ; `totalAmount` = somme de **toutes** les dépenses des filtres, donnée **sur la première page seulement** (null ensuite : garder celle de la page 1) ; dépenses supprimées exclues (liste et fiche) |
| GET | `/api/v1/admin/tenants/{tenantId}/expenses/{expenseId}` | — | `{tenantId, tenantName, id, storeId, storeName, category, categoryLabel, amount, expenseDate, label, note, paymentMethod, paymentMethodLabel, createdByName (null si inconnu → afficher « — »), createdAt, updatedAt}` |

Un compte qui n'a jamais utilisé le module (dépenses…) donne une liste vide, jamais une erreur ; un compte dont la base est d'une ancienne version (colonne absente) donne 404 sur la fiche, jamais 500. Erreurs (422 `VALIDATION_ERROR`) : `tenantId` mal formé « Compte invalide » ; `storeId` « Boutique invalide » ; identifiant de fiche mal formé « Identifiant invalide » ; `status` « Statut inconnu : choisissez active ou archived » ; `category` « Catégorie de dépense inconnue » ; dates « La date de début est invalide (format AAAA-MM-JJ) », « La date de début doit précéder la date de fin » ; `q` « La recherche ne peut pas dépasser 100 caractères ». Détail : compte inconnu → 404 `TENANT_NOT_FOUND` « Compte introuvable » ; fiche inconnue ou d'un autre compte → 404 `CLIENT_NOT_FOUND` « Client introuvable », `PRODUCT_NOT_FOUND` « Produit introuvable », `EXPENSE_NOT_FOUND` « Dépense introuvable ».

**Utilisateurs** (`keevo/backend/src/main/java/com/keevo/admin/user/adapter/in/rest/AdminUserController.java`, modèles `.../user/domain/model/AdminUserViews.java`) :

| Méthode | Route | Paramètres | Réponse `data` |
|---|---|---|---|
| GET | `/api/v1/admin/users` | `role` (`OWNER`, `MANAGER`, `EMPLOYEE`, `VIEWER`, `SUPER_ADMIN`, sans casse), `active` (`true` \| `false`), `tenantId` (compte existant, sinon 404), `q` (nom, téléphone, e-mail, identifiant), `cursor`, `limit` | page de `{id, displayName ("Utilisateur sans nom" si aucun nom), phoneNumber, email, active, superAdmin, memberships: [{tenantId, tenantName, role, roleLabel ("Propriétaire", "Gérant", "Employé", "Lecteur"), active}], deviceCount, lastSeenAt, createdAt}`, inscription la plus récente d'abord ; comptes supprimés jamais listés |
| GET | `/api/v1/admin/users/stats` | `from` / `to` (AAAA-MM-JJ ; défaut du 1er du mois à aujourd'hui ; ≤ 366 jours), `role` (sauf `SUPER_ADMIN`), `active` | `{totalUsers, activeUsers, newUsers, from, to, byRole: [{role, label, count}], averageSalesPerSeller, topPerformers: [{userId, displayName, documentsCount, totalSales}]}` — super-administrateurs exclus ; `newUsers` = inscrits entre `from` et `to` ; `averageSalesPerSeller` = CA de la période / nombre de vendeurs ayant du CA (FCFA, 0 si aucun) ; `topPerformers` = les 5 meilleurs vendeurs de la période, tous comptes confondus, par CA (source unique 20.9) |
| GET | `/api/v1/admin/users/{userId}` | — | un élément de la liste |
| GET | `/api/v1/admin/users/{userId}/devices` | — | `[{sessionId, deviceLabel ("Appareil inconnu" si absent), tenantId, tenantName ("Console super-admin" pour une session sans compte, « Entreprise sans nom » sans nom), lastUsedAt}]` — sessions ouvertes (16.11) des comptes actifs, la plus récente d'abord |

Erreurs : `role` → 422 « Rôle inconnu : choisissez OWNER, MANAGER, EMPLOYEE, VIEWER ou SUPER_ADMIN » ; `active` → 422 « Le filtre « actif » doit valoir true ou false » ; `tenantId` mal formé → 422 « Compte invalide », inconnu → 404 « Compte introuvable » ; `role=SUPER_ADMIN` sur les stats → 422 « Les statistiques portent sur les utilisateurs des comptes, pas sur les super-administrateurs » ; période > 366 jours → 422 « La période ne peut pas dépasser 366 jours » ; `userId` mal formé → 422 « Utilisateur invalide » ; inconnu → 404 `USER_NOT_FOUND` « Utilisateur introuvable ».

**Écritures sur un compte (A1–A4)** (`keevo/backend/src/main/java/com/keevo/admin/tenantwrite/adapter/in/rest/AdminTenantWriteController.java`, modèles `.../tenantwrite/domain/model/TenantWriteViews.java`). Corps JSON ; champs absents = inchangés pour les `PATCH`.

| Méthode | Route | Corps | Réponse |
|---|---|---|---|
| POST | `/api/v1/admin/tenants/{tenantId}/customers` | `{name*, phone* (E.164, ex. "+237690001122"), email, notes, storeId (défaut : boutique par défaut du compte), clientType ("person" \| "company"), niu, rccm, address, contactName, website, birthDate ("AAAA-MM-JJ")}` | **201** `{customer: <fiche client ci-dessus>, duplicatePhoneCount}` — `duplicatePhoneCount` > 0 = d'autres clients de la boutique ont ce numéro (avertissement « numéro déjà utilisé », jamais un refus) |
| PATCH | `/api/v1/admin/tenants/{tenantId}/customers/{customerId}` | mêmes champs sauf `storeId` | 200 fiche client |
| PUT | `/api/v1/admin/tenants/{tenantId}/customers/{customerId}/marketing-consent` | `{consent*: true \| false}` | 200 fiche client (`marketingConsent`, `marketingConsentAt`) — l'auteur de l'accord est le super-administrateur |
| POST | `/api/v1/admin/tenants/{tenantId}/members` | `{phoneNumber* (E.164), firstName*, lastName, storeId*, role ("MANAGER" \| "EMPLOYEE" (défaut) \| "VIEWER")}` | **201** `{employeeId, userId, temporaryPassword, existingAccount, member: {employeeId, userId, storeId, firstName, lastName, active, statusLabel}}` — `temporaryPassword` à afficher **une seule fois** (le membre le change à la première connexion) ; null quand un compte existant a été rattaché (`existingAccount: true`) |
| PATCH | `/api/v1/admin/tenants/{tenantId}/members/{userId}` | `{phoneNumber, firstName, lastName, storeId, role ("OWNER" \| "MANAGER" \| "EMPLOYEE" \| "VIEWER")}` | 200 `member` |
| POST | `/api/v1/admin/tenants/{tenantId}/members/{userId}/deactivate` | — | 200 `member` (`active: false`, `statusLabel: "Désactivé"`) — ses sessions sur ce compte sont fermées (prochaine requête de ses appareils : 401) |
| POST | `/api/v1/admin/tenants/{tenantId}/members/{userId}/reactivate` | — | 200 `member` (`statusLabel: "Actif"`) |
| DELETE | `/api/v1/admin/tenants/{tenantId}/members/{userId}` | — | **204** — le membre est désactivé (sessions fermées) puis retiré du compte ; sa fiche reste dans l'historique (ventes, factures) |

`userId` = `id` d'un utilisateur de `GET /api/v1/admin/users` ayant une adhésion (`memberships[]`) dans ce compte ; l'API retrouve sa fiche employé dans le compte (aucune fiche → 404 « Employé introuvable »). Erreurs : 422 « Le nom du client est obligatoire », « Le téléphone du client est obligatoire », « Aucune modification demandée », « La boutique d'un client se change depuis l'application du propriétaire », « Indiquez l'accord du client : consent vaut true ou false », « Le numéro de téléphone est obligatoire », « Le prénom est obligatoire », « La boutique est obligatoire », « Rôle inconnu : choisissez MANAGER, EMPLOYEE ou VIEWER » (création), « Rôle inconnu : choisissez OWNER, MANAGER, EMPLOYEE ou VIEWER » (modification), « Boutique invalide », « Identifiant invalide », et les messages des règles du module (téléphone invalide, plafond d'employés de l'offre, dernier propriétaire…) ; 404 « Compte introuvable », « Client introuvable », `EMPLOYEE_NOT_FOUND` « Employé introuvable » ; 403 « Le propriétaire du compte ne peut pas être supprimé » ; 409 numéro déjà pris par un autre utilisateur (message du module).

**Autres actions Bref → routes existantes du compte** (prendre l'entreprise dans `memberships[]` avec `role = OWNER`) :
- Suspendre / réactiver tout le compte → `POST /api/v1/admin/tenants/{tenantId}/suspend` / `/reactivate` (`AdminTenantPlanController.java`).
- Changer l'offre (`PATCH /users/:id/plan`) → `POST /api/v1/admin/tenants/{tenantId}/plan/premium` (offre en paramètre, 19.7b) / `/plan/free`, ou `POST /api/v1/admin/subscriptions/{tenantId}/activate`.
- Supprimer tout le compte → `POST /api/v1/admin/tenants/{tenantId}/force-delete`.
- Appareils de synchronisation d'un compte → `GET /api/v1/admin/sync/tenants/{tenantId}/detail`.
- Versions minimales de l'app → `/api/v1/admin/app-versions` (19.10) ; offres → `/api/v1/admin/plan-profiles` (19.7).

Toute autre session qu'une session super-admin → 403 `FORBIDDEN` sur toutes ces routes (lectures et écritures).

### À implémenter côté dashboard

1. Remplacer les appels api-bref `GET /v1/dashboard/overview|growth|top-shops|activity` par `/api/v1/admin/dashboard/overview|growth|top-accounts|activity` (cartes utilisateurs, comptes, boutiques, factures, CA avec tendance ; courbe mensuelle ; classement des comptes ; fil d'activité `label` + `detail`, y compris « Paiement reçu »).
2. Écrans Clients / Produits / Dépenses inter-comptes : filtres compte (sélection depuis `GET /api/v1/admin/tenants`), boutique, recherche, statut, catégorie, période ; bouton « Voir plus » sur `nextCursor` (plus de numéros de page) ; total des dépenses depuis `totalAmount` de la première page ; fiche via `/api/v1/admin/tenants/{tenantId}/…/{id}` avec `statusLabel`.
3. Fiche client : boutons « Créer », « Modifier », « Accord marketing » sur les routes A1 ; afficher l'avertissement quand `duplicatePhoneCount` > 0.
4. Écran Utilisateurs : filtres rôle / actif / compte / recherche, rôles par compte (`memberships[].roleLabel`), statistiques avec moyenne par vendeur et 5 meilleurs vendeurs, fiche et appareils ; membres d'un compte : ajouter (montrer le mot de passe temporaire une fois), modifier, désactiver / réactiver, supprimer (routes A2–A4) ; suspension / offre / suppression du compte via les routes du compte.
5. Libellés « Documents » (pas « Ventes ») pour `totalCompletedSales`, `salesCount`, `totalOrders`, `orderCount` des écrans analytics / operations existants.
6. Afficher `error` pour tout 422 / 404 / 403 / 409 ; aucun identifiant à l'écran. Campagnes → 21.6.

### Critères d'acceptation

- Given une session super-admin, when on ouvre le tableau de bord, then cartes, courbe, classement et fil d'activité (paiements compris) s'affichent depuis l'API Keevo uniquement, en français, montants en FCFA.
- Given les listes clients / produits / dépenses, when on filtre par compte puis on clique « Voir plus », then les éléments suivants s'ajoutent sans doublon ; changer un filtre repart de la première page ; le total des dépenses reste celui de la page 1.
- Given la liste des utilisateurs, when une personne est membre de deux comptes, then elle apparaît une seule fois avec ses deux rôles.
- Given un super-administrateur qui crée un client ou un membre, then la fiche s'affiche avec les règles du compte (numéro E.164, boutique), le mot de passe temporaire est montré une seule fois, et le journal du compte indique le super-administrateur comme auteur.
- Given un membre désactivé ou supprimé, then ses appareils sont déconnectés à leur prochaine requête ; le propriétaire ne peut pas être supprimé (message affiché).
- Given une erreur 422, 404, 403 ou 409, then le message `error` est affiché tel quel.
- Given une session non super-admin, then aucune de ces pages ni actions n'est accessible (403).

### Clé de suivi

`d20-13-api-super-admin-heritee-bref`
