---
title: '9-1 — Tenant management completion: readable data, real plans, employee names and activity, feedback inbox'
type: 'feature'
created: '2026-10-13'
status: 'done'
baseline_revision: '07844d5c1492cbfff807d48e32a1a3a39985b00a'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/9-6-monitoring-catalogue-produits.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-9-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/9-1-gestion-des-tenants.md'
warnings: [multiple-goals]
deferred: []
---

<intent-contract>

## Intent

**Problem:** The Tenants page (story 9-1, the first back-office page) breaks the rule « no technical data in UI » and under-delivers its ACs: the table and drawer show the tenant code, raw plan `PAID`/`FREE` (trial merged into PAID), raw statuses, audit lines `eventType` + `entity_type #<uuid>`; employees are listed by phone number with no name and `lastLoginAt` always null; the plan filter cannot tell Trial from Premium. Separately, user feedback (FR92, story 8-4: « les retours arrivent à Toor ») has a backend list endpoint but no dashboard page, so Toor never reads it.

**Approach:** Make the tenant list/drawer readable and complete (real plan values with French labels, status labels, owner and employee names, last activity per employee from `public.user_daily_activity` (9-4), audit lines from the enriched audit log (v1s-18-6) rendered with French action labels and `entity_label`, actor names) and add a « Retours utilisateurs » page over `GET /api/v1/admin/feedback` with tenant names, French type/priority labels, filters and a « Traité » flag.

## Boundaries & Constraints

**Always:**
- Backend tenant list/detail: `plan` returns the real `PlanType` (`FREE`, `PREMIUM_TRIAL`, `PREMIUM`; `public.tenants.plan_type` kept in sync by 9-2); filter `plan` accepts `ALL|FREE|PREMIUM_TRIAL|PREMIUM` and still accepts legacy `PAID` (= PREMIUM_TRIAL+PREMIUM). Owner name = employees/owner first+last name if stored for the owner, else « Propriétaire » (phone stays in its own column — a phone number is business contact data, allowed). Employee rows: `name` = trimmed first + last name from `<schema>.employees` (owner: « Propriétaire »), `role`, `lastActivityAt` = MAX(`activity_date`) from `public.user_daily_activity` for (user, tenant) (null → « Jamais »). Audit rows: `action` mapped server-side to nothing — the DTO returns `action`, `entityType`, `entityLabel`, `actorName` (employee name / « Propriétaire » / « Système » for SYSTEM source), `storeName`, `occurredAt`; no `entity_id`, no `actorId` in the DTO.
- Dashboard: remove the « Code » column/row; plan, status, audit action, entity type, role and feedback type/priority/platform rendered through `lib/labels.ts` (add `tenantStatusLabel`, `auditActionLabel` mirroring the app's audit labels from v1s-18-7 `audit` feature, `feedbackTypeLabel`, `priorityLabel`); filter chips and selects use labels; row of a `DELETION_PENDING` tenant highlighted orange with « Suppression dans N jours » (keep existing behaviour if present); no UUID or UPPER_SNAKE code in DOM text (test helper from 9-8 tests).
- Feedback inbox: `public.feedback` gets `handled_at timestamptz null`, `handled_by uuid null` (Flyway); `GET /api/v1/admin/feedback` DTO adds `tenantName` (join `public.tenants` on schema name), `userLabel` (employee name / « Propriétaire »), drops nothing else but the UI never shows `id`/`tenantId`/`userId`; new filters `type`, `handled` (default false); `POST /api/v1/admin/feedback/{id}/handle` and `/unhandle` (SUPER_ADMIN). Page `/retours`: table (date, entreprise, utilisateur, type, priorité, écran, version de l'app, description), filters (type, priorité, traité), pagination 20, « Marquer comme traité ». HIGH priority rows highlighted. Sidebar entry « Retours utilisateurs » with unhandled HIGH count badge.
- Routed from 11-5 review (2026-10-13): the tenant list still runs 2 queries per tenant (`storeCount`, `lastActivityAt`) after the schema-name batching — compute both with one query per schema (single SELECT returning both values) so the list costs 1 + N queries, and pin the count in `AdminTenantServiceTest`.
- Routed to story 9-6 (2026-10-13) and fixed here: the catalog CSV export (`keevo/dashboard/src/lib/api/admin-catalog.ts:40`) is a plain link without the admin JWT (401) — download it through the authenticated axios client as a blob, like the 9-3 churn export, with a component test; close the 9-6 routed item.
- Wording rule « Entreprise(s) », never « tenant », in every user-visible dashboard text: sidebar entry « Tenants » → « Entreprises », Tenants page title, table headers, filters, chips, drawer, empty states, counts (« Affichage 1–25 sur N entreprises »); the route `/tenants` may stay (URL, not shown text).
- All admin methods keep `CurrentActor.requireSuperAdmin()`.

**Never:** No change to the Flutter feedback submission; no deletion of feedback; no new tenant actions here (plan/suspend actions belong to 9-2).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Trial tenant | plan_type PREMIUM_TRIAL | badge « Essai Premium »; filter « Essai Premium » finds it; legacy `PAID` filter too | — |
| Employee activity | user active yesterday | « hier » / date; never active → « Jamais » | — |
| Audit with label | action PRICE_CHANGED, entity_label "Savon" | « Prix modifié — Savon » by « Awa Diallo » | unknown action → humanized |
| Audit without label | entity_label null | action label only, never the id | — |
| Feedback handled | POST handle | hidden from default list, shown with filter « Traités » | unknown id → 404 `NOT_FOUND` |
| Tenant name missing | feedback tenant deleted | « Entreprise supprimée » | — |
| Non-admin | OWNER | 403 `FORBIDDEN` | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/admin/tenant/application/service/AdminTenantService.java:225-240,338-357,371-390,395-410` -- plan filter, employees (phone as name, null last login), audit query (`entity_type || ' #' || entity_id`), PAID mapping.
- `keevo/backend/src/main/java/com/keevo/admin/tenant/adapter/in/rest/dto/{AdminTenantDetailDto,AdminTenantListItemDto}.java`, `domain/model/*` -- DTO changes.
- `keevo/backend/src/main/resources/db/migration/V14__audit_log_enrichment.sql` -- `audit_log` enrichment columns (`entity_label`, `store_id`, `source`, `device_id`, `metadata`).
- `keevo/app/lib/features/audit/**` -- French audit action labels to mirror (v1s-18-7).
- `keevo/backend/src/main/java/com/keevo/admin/feedback/**`, `keevo/backend/src/main/resources/db/migration/V7__feedback.sql` -- feedback list.
- `keevo/dashboard/src/{types/tenant.ts,components/tenants/*,hooks/useTenants.ts,lib/api/admin-tenants.ts,lib/labels.ts,components/layout/Sidebar.tsx}`.

## Tasks & Acceptance

**Execution:**
- Backend tenant: service queries/mapping, DTOs, tests (plan values + legacy filter, employee names/activity, audit DTO without ids, `jsonPath` contract).
- Backend feedback: migration `V19__feedback_handled.sql` (next free Flyway version), use cases handle/unhandle, list filters + tenant/user labels, tests.
- Dashboard tenants: table, filters, chips, drawer per Boundaries; component tests (no ids/codes, labels, orange row).
- Dashboard feedback: `types/feedback.ts`, `lib/api/admin-feedback.ts`, `hooks/useFeedback.ts`, `components/feedback/*`, `app/retours/page.tsx`, sidebar; component tests.
- `9-1-gestion-des-tenants.md` / `8-4-…` statuses handled by the orchestrator.

**Acceptance Criteria:**
- Given any tenant, when Toor opens its drawer, then every line is readable French business data (names, labels, amounts, dates) and no identifier or code appears.
- Given a feedback submitted from the app, when Toor opens « Retours utilisateurs », then it is listed with the merchant's name and can be marked handled.

## Spec Change Log

## Review Triage Log

### 2026-10-13 — Review pass
- verdicts: 33 findings — high 1, medium 9, low 18, false 1, maybe-false 0 (+ intent rows descriptive)
- findings:
  - `[medium]` `[patch]` (blind) catalog CSV now reachable but full of UUID / raw codes / English headers — French columns, labels, no UUID, dd/MM/yyyy, `;` + BOM (routed 9-6 item extended).
  - `[low]` `[patch]` (blind) feedback DTO sends schema name and user UUID — fields removed.
  - `[medium]` `[patch]` (blind) one broken table wipes all list metrics of the tenant — owner name in a separate statement.
  - `[medium]` `[patch]` (blind) detail member list depends on `<schema>.employees` — members from public, names fail-soft.
  - `[low]` `[patch]` (blind) sidebar badge uses the full labelled list every 60 s, index lacks priority — dedicated count endpoint, partial index on priority.
  - `[low]` `[patch]` (blind) handle/unhandle leave no trace, re-handle overwrites — first handler kept, `[ADMIN AUDIT]` log, « Traité le … » shown.
  - `[low]` `[patch]` (blind) inbox lands on an empty page — page clamped.
  - `[low]` `[patch]` (blind) pendingId / stale action error — Set of pending ids, mutation resets.
  - `[low]` `[patch]` (blind) `type`/`screenContext` unvalidated — validated / capped.
  - `[low]` `[patch]` (blind) owner name not searchable — placeholder clarified (cross-schema search not cheap).
  - `[low]` `[reject]` (blind) catalog plan filter two-way — catalog DTO groups trial with Premium (« Premium et essai »), consistent label; a three-way catalog filter is a 9-6 enhancement, not a defect.
  - `[false]` `[reject]` (blind) spec bookkeeping unfinished — orchestrator finalizes in this commit.
  - `[low]` `[patch]` (blind) fallback labels duplicated — one constant per side.
  - `[low]` `[patch]` (blind) CSV filename date in browser zone — Africa/Douala.
  - `[high]` `[patch]` (edge) readOnly transaction poisoned by one failing tenant (25P02) silently blanks later tenants — root cause shared with `AdminFeedbackLabelService`, `AdminPlanService.listAlerts` (9-2), `AdminCatalogService` (9-6), `AdminSyncMonitoringService` (9-8): transactions removed from those per-tenant read paths; Postgres tests prove isolation.
  - `[medium]` `[patch]` (edge) feedback labels poisoned the same way — same root.
  - `[medium]` `[patch]` (edge) combined query loses metrics on a broken employees table — same as blind row 3.
  - `[medium]` `[patch]` (edge) LATERAL join empties members — same as blind row 4.
  - `[low]` `[patch]` (edge) label failure after commit returns 500 — fallback labels on handle/unhandle.
  - `[low]` `[patch]` (edge) empty page after handle — same as blind row 7.
  - `[low]` `[patch]` (edge) error banner after later success — same as blind row 8.
  - `[low]` `[patch]` (edge) double submit via re-enabled button — same as blind row 8.
  - `[low]` `[patch]` (edge) overdue deletion shown as « aujourd'hui » — « Suppression imminente ».
  - `[low]` `[patch]` (edge) unknown feedback type humanized in English — « Autre ».
  - `[low]` `[patch]` (edge, deletion) removed catch-alls let mapper NPEs 500 the drawer — null-guards.
  - `[medium]` `[patch]` (edge, claim) « per-schema errors only lose that company's names » false inside one transaction — same root as edge row 1.
  - `[medium]` `[patch]` (edge, claim) « tenantRows skips only that tenant » false inside one transaction — same root.
  - `[medium]` `[patch]` (gap) feedback label SQL never run — Postgres IT.
  - `[low]` `[patch]` (gap) `admin-feedback.ts` untested — client-mocked tests.
  - `[low]` `[patch]` (gap) badge refresh after handle untested — page test assertion.
  - `[low]` `[patch]` (gap) catalog wording untested — `ProductTable` no-codes test.
  - `[low]` `[patch]` (implementer note) `lastActivityFrom/To` filters ignored although part of 9-1 ACs — implemented on the « last activity » definition.
  - `[false]` `[reject]` (intent) readings 1–3 — descriptive; « 1 + N » now 2 statements per tenant by design (fail isolation), documented in the pinned test.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.admin.**,com.keevo.feedback.**,com.keevo.architecture.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/dashboard && npm run lint && npx tsc --noEmit && npm test && npm run build` -- expected: all succeed

## Auto Run Result

Status: done

**Summary:** Story 9-1 completed and story 9-6's routed item closed. Tenant list/detail are readable business data only: real plans (FREE / Essai Premium / Premium, legacy PAID filter kept), status labels, owner name, employees with names and last activity day (from `user_daily_activity`), audit lines « action — libellé » by actor in store, no code/UUID anywhere; last-activity date filters now applied; « Entreprises » wording everywhere. New « Retours utilisateurs » inbox (`/retours`): tenant/user labels, type/priority/handled filters, handle/unhandle (first handler kept, audited), count endpoint for the sidebar badge (V19). Catalog CSV export downloads with the admin JWT and is French (no UUID, labels, dd/MM/yyyy, `;` + BOM). Root-cause fix across admin read paths (tenant, feedback labels, catalog, sync monitoring): no read-only transaction around per-tenant loops, so one broken schema no longer blanks the others.

**Files changed:** backend `admin/tenant/**`, `admin/feedback/**`, `admin/catalog/**` (`ProductsCsvWriter`), `admin/sync_monitoring/...Service`, `admin/AdminLabels.java`, `feedback/**` (handled fields, criteria search, count, handle service), `V19__feedback_handled.sql` + tests (incl. 3 Postgres ITs); dashboard `lib/labels.ts`, `lib/api/{admin-feedback,admin-catalog,admin-tenants}.ts`, `hooks/{useFeedback,useTenants}.ts`, `components/{tenants,feedback,catalog,layout}/*`, `app/retours/page.tsx`, `app/tenants/page.tsx` + tests; story files 9-1, 9-6.

**Review:** 33 findings — patched 1 high (poisoned read-only transaction, shared root across 4 admin services), 9 medium entries and 18 low; deferred 0; rejected 3.

**Follow-up review recommended:** true — a high was patched; unverified risk: the transaction removal in `AdminCatalogService` and `AdminSyncMonitoringService` is covered by their existing unit tests only (the isolation is proven by Postgres tests on the tenant list and feedback labels).

**Verification:** dashboard lint, tsc, `npm test` (40 files, 193 tests), build — green. Backend `com.keevo.admin.**` 334, `com.keevo.feedback.**` 44, `com.keevo.architecture.**` 6 — 0 failures/errors.

**Residual risks:** the last-activity filter scans one statement per candidate tenant (Phase 1 volumes); two admins handling the same feedback at the same instant — last write wins.

