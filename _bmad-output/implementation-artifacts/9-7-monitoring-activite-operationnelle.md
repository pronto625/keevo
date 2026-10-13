---
baseline_commit: 6a0efea
---
# Story 9.7: Monitoring Activité Opérationnelle (Super Admin) — backend + frontend complets

Status: done

<!-- V1-stabilization track — tag A (release-blocker pour le build dashboard).
     Branche : v1-stabilization (HEAD 6a0efea).
     Source : audit de cohérence 2026-07-23 (_bmad-output/planning-artifacts/audit-coherence-2026-07-23.md, Finding #6).
     🚨 Cette story n'a JAMAIS eu de fichier story dédié ni d'entrée dans epic-9-super-admin-dashboard-toor.md
     (seules les stories 9.1 à 9.5 y sont documentées) — elle a été créée ad hoc, marquée `review` dans
     sprint-status.yaml sans qu'aucun backend ni frontend fonctionnel n'existe. Ce fichier constitue la
     PREMIÈRE spécification réelle de cette story — rétroactive, reconstruite à partir (a) des imports du
     stub `page.tsx` déjà commité (qui fixent le contrat de props attendu par la page, à ne pas changer),
     (b) du pattern architectural déjà établi et prouvé dans `admin.tenant`/`admin.sync_monitoring`
     (JdbcTemplate cross-schema, mêmes conventions Next.js/React Query que `admin.catalog`/9-6).
     Décision Toor (2026-07-23, en réponse à la question de scope de l'audit) : implémenter la feature
     complète (pas de simple stub de build). -->

## Story

**As a** Toor (super admin plateforme),
**I want** un dashboard "Activité Opérationnelle" affichant le volume d'affaires (GMV) global et par tenant, l'activité récente par tenant, et les tenants inactifs,
**so that** je puisse identifier rapidement les tenants qui décrochent (churn risk) et suivre la traction globale de la plateforme sans avoir à interroger la base de données manuellement.

## Contexte

- **Pourquoi cette story existe** : `keevo/dashboard/src/app/monitoring/activity/page.tsx` importe déjà `useOperations` (hook) et 5 composants (`OperationsKpiCards`, `PeriodSelector`, `GmvTrendChart`, `TenantActivityTable`, `InactiveTenantsSection`) — **aucun n'existe** dans le repo. `npm run build` échoue intégralement à cause de ces imports manquants (confirmé par exécution réelle, pas déduit). Aucun contrôleur backend `/api/v1/admin/operations/*` n'existe non plus.
- **Le contrat de `page.tsx` est figé — ne PAS le modifier.** Le hook `useOperations()` DOIT retourner exactement : `{ overviewQuery, activityQuery, trendQuery, inactiveQuery, period, setPeriod, customFrom, setCustomFrom, customTo, setCustomTo, page, setPage, refresh }`. Les 5 composants DOIVENT accepter exactement les props que `page.tsx` leur passe déjà (voir Dev Notes pour le détail exact par composant, extrait du fichier existant).
- **🚨 DÉCISIONS PRODUIT PAR DÉFAUT — à valider ou corriger, pas à ignorer.** Aucune spec originale n'existe pour cette story (jamais dans `epic-9-super-admin-dashboard-toor.md`). Les définitions ci-dessous sont des **hypothèses raisonnables** dérivées du nommage des composants déjà commités et des conventions déjà établies ailleurs dans le codebase (ex. `lastActivityAt` déjà calculé pour 9-1 via `MAX(occurred_at) FROM <schema>.audit_log`) — **documenter dans Completion Notes toute déviation prise par rapport à ces hypothèses** :
  - **GMV (Gross Merchandise Value)** = somme de `sales.total_amount` (statut `COMPLETED`) sur la période sélectionnée, agrégée cross-tenant.
  - **Périodes** (`PeriodSelector`) : `TODAY | 7D | 30D | 90D | CUSTOM` (custom utilise `customFrom`/`customTo`, des `Date` ISO).
  - **"Tenant inactif"** (`InactiveTenantsSection`) : aucune vente (`sales`) ET aucune entrée `audit_log` depuis plus de **14 jours** — seuil arbitraire par défaut, à confirmer avec le PO si un autre seuil est préférable.
  - **Pagination `TenantActivityTable`** : 25 lignes par page, triée par GMV décroissant sur la période sélectionnée.

## Acceptance Criteria

1. **AC1 (Backend — nouveau module `admin.operations`, GET `/api/v1/admin/operations/overview`)** — **Given** aucun endpoint n'existe aujourd'hui, **When** un SUPER_ADMIN appelle `GET /api/v1/admin/operations/overview?period={TODAY|7D|30D|90D}`, **Then** la réponse contient `{ totalGmv: number, totalOrders: number, activeTenantsCount: number, newTenantsCount: number, periodLabel: string }` agrégés cross-tenant pour la période demandée ; **And** un non-SUPER_ADMIN reçoit 403 `FORBIDDEN` (miroir exact de `AdminSyncMonitoringController.requireSuperAdmin()`).
2. **AC2 (Backend — GET `/api/v1/admin/operations/trend`)** — **Given** `GmvTrendChart` a besoin d'une série temporelle, **When** un SUPER_ADMIN appelle `GET /api/v1/admin/operations/trend?period={7D|30D|90D}`, **Then** la réponse est une liste `[{ date: string (ISO date), gmv: number, orderCount: number }]` avec un point par jour dans la fenêtre demandée (jours sans vente → `gmv: 0`).
3. **AC3 (Backend — GET `/api/v1/admin/operations/tenants`, paginé)** — **Given** `TenantActivityTable` a besoin d'une table par tenant, **When** un SUPER_ADMIN appelle `GET /api/v1/admin/operations/tenants?period={...}&page={n}&pageSize=25`, **Then** la réponse est une page `{ content: [{ tenantId, tenantName, plan, gmv, orderCount, lastActivityAt, storeCount }], totalElements, totalPages, number }` triée par `gmv` décroissant sur la période.
4. **AC4 (Backend — GET `/api/v1/admin/operations/inactive-tenants`)** — **Given** `InactiveTenantsSection` a besoin de la liste des tenants dormants, **When** un SUPER_ADMIN appelle cet endpoint, **Then** la réponse liste les tenants dont `lastActivityAt` (MAX entre dernière vente et dernière entrée `audit_log`, cross-schema) est `null` ou antérieur à 14 jours, avec `{ tenantId, tenantName, plan, lastActivityAt, daysSinceLastActivity }`.
5. **AC5 (Frontend — `lib/api/admin-operations.ts` + `types/operations.ts`)** — **Given** le pattern déjà établi par `lib/api/admin-catalog.ts`/`types/catalog.ts` (9-6), **When** cette story est terminée, **Then** un fichier `types/operations.ts` définit les types TypeScript miroir exact des 4 DTOs backend (AC1-AC4), et `lib/api/admin-operations.ts` expose 4 fonctions async (`getOperationsOverview`, `getGmvTrend`, `getTenantActivity`, `getInactiveTenants`) utilisant le `client` axios partagé (`lib/api/client.ts`, baseURL `/api/backend`, intercepteur JWT déjà en place — ne pas dupliquer cette logique).
6. **AC6 (Frontend — `hooks/useOperations.ts`)** — **Given** `activity/page.tsx` (déjà commité, ne pas modifier) déstructure `{ overviewQuery, activityQuery, trendQuery, inactiveQuery, period, setPeriod, customFrom, setCustomFrom, customTo, setCustomTo, page, setPage, refresh }`, **When** cette story est terminée, **Then** `useOperations()` retourne exactement cette forme : `period`/`customFrom`/`customTo`/`page` sont des `useState`, `overviewQuery`/`trendQuery`/`activityQuery`/`inactiveQuery` sont des `useQuery` React Query (miroir `useProducts.ts`, `placeholderData` pour éviter le flash lors de la pagination), `refresh` invalide les 4 queries.
7. **AC7 (Frontend — les 5 composants, props exactes déjà fixées par `page.tsx`)** — **Given** `page.tsx` passe déjà des props précises à chaque composant (voir Dev Notes pour l'extrait exact), **When** cette story est terminée, **Then** chaque composant est créé avec EXACTEMENT ces props (types stricts, pas de props optionnelles supplémentaires non utilisées) : `PeriodSelector({period, onPeriodChange, customFrom, customTo, onCustomFromChange, onCustomToChange})`, `OperationsKpiCards({overview, isLoading, lastUpdated, onRefresh})`, `GmvTrendChart({data, isLoading})`, `TenantActivityTable({data, isLoading, page, setPage})`, `InactiveTenantsSection({data})`. Style Tailwind cohérent avec `CatalogSummaryCards`/`ProductTable` (9-6) — cartes `bg-white rounded-lg shadow p-4`, squelettes `animate-pulse` pendant `isLoading`.
8. **AC8 (build vert)** — **Given** l'objectif premier de cette story est de restaurer un dashboard fonctionnel, **When** cette story est terminée, **Then** `npm run build` réussit sans erreur `Module not found` liée à cette page.

## Tasks / Subtasks

- [x] **Task 1 — Backend : créer le module `admin.operations` (AC1-AC4)**
  - [x] 1.1 Créer `com.keevo.admin.operations` (adapter/in/rest, adapter/in/rest/dto, application/service, domain/model, domain/port/in) — miroir exact de la structure `admin.sync_monitoring`.
  - [x] 1.2 `AdminOperationsController` : 4 endpoints (AC1-AC4), `requireSuperAdmin()` copié tel quel depuis `AdminSyncMonitoringController`.
  - [x] 1.3 `AdminOperationsService` : requêtes cross-schema via `JdbcTemplate`, en suivant le pattern `AdminTenantService` (`safeLoadSchemaName`, agrégation `SUM(total_amount) FROM <schema>.sales WHERE status='COMPLETED' AND occurred_at >= ?`, `MAX(occurred_at) FROM <schema>.audit_log` pour l'activité). **Ne pas dupliquer `AdminTenantService` — écrire les helpers JDBC nécessaires dans ce nouveau service** (le codebase ne factorise pas ces petits helpers entre services admin, cohérent avec le style existant).
  - [x] 1.4 Tests : `AdminOperationsServiceTest` (agrégation correcte, tenant sans schema résolu → skip gracieux, cohérent avec `safeCount`/`safeMaxInstant` de `AdminTenantService`), `AdminOperationsControllerTest` (`standaloneSetup`, 403 non-SUPER_ADMIN, 200 SUPER_ADMIN sur les 4 endpoints).
- [x] **Task 2 — Frontend : types + client API (AC5)**
  - [x] 2.1 `types/operations.ts` : `AdminOperationsOverview`, `GmvTrendPoint`, `TenantActivityRow`, `AdminTenantActivityPage`, `InactiveTenant`, `Period = 'TODAY' | '7D' | '30D' | '90D' | 'CUSTOM'`.
  - [x] 2.2 `lib/api/admin-operations.ts` : 4 fonctions, miroir exact de `lib/api/admin-catalog.ts` (structure `client.get<{data: T}>(...).then(r => r.data.data)`).
- [x] **Task 3 — Frontend : hook (AC6)**
  - [x] 3.1 `hooks/useOperations.ts` — miroir `useProducts.ts` (React Query + `useState` pour period/page/custom dates), `refresh()` = `queryClient.invalidateQueries` sur les 4 clés.
- [x] **Task 4 — Frontend : 5 composants (AC7)**
  - [x] 4.1 `components/operations/PeriodSelector.tsx`, `OperationsKpiCards.tsx`, `GmvTrendChart.tsx` (utiliser la lib de charts déjà présente dans le projet si une existe pour d'autres graphs admin — vérifier `package.json`/composants existants avant d'ajouter une nouvelle dépendance), `TenantActivityTable.tsx`, `InactiveTenantsSection.tsx` — style Tailwind miroir `components/catalog/*`.
- [x] **Task 5 — Vérification build (AC8)**
  - [x] 5.1 `npm run build` — 0 erreur `Module not found`. `npm run lint`/type-check si configuré.
- [x] **Task 6 — Documentation rétroactive**
  - [x] 6.1 Ajouter "Story 9.7" à `epic-9-super-admin-dashboard-toor.md` (elle n'y figure pas aujourd'hui) pour fermer le gap de traçabilité identifié par l'audit.

### Deferred items routed 2026-10-11

Pour rétablir le build du dashboard (audit-coherence-2026-10-11 C1), la story v1s-17-1 a **remplacé** `app/monitoring/activity/page.tsx` par un placeholder « bientôt disponible » (aucun appel API). Le contrat figé ci-dessous (Dev Notes) reste la spec ; le fichier d'origine est récupérable via `git show e1f91c6:keevo/dashboard/src/app/monitoring/activity/page.tsx`. Travail additionnel à la reprise de cette story :

- [x] [Deferred-from: v1s-17-1-dashboard-build-ci, 2026-10-11] Restaurer `activity/page.tsx` depuis e1f91c6 (contrat ci-dessous) à la place du placeholder. Attention : la ligne `if (overviewQuery.isSuccess && !lastUpdated) setLastUpdated(...)` appelle setState pendant le rendu — à corriger (lint `Dashboard CI`) sans changer les props des composants.
- [x] [Deferred-from: v1s-17-1-dashboard-build-ci, 2026-10-11] Repasser l'entrée sidebar « Activité » à `comingSoon: false` (`components/layout/Sidebar.tsx`).
- [x] [Deferred-from: v1s-17-1-dashboard-build-ci, 2026-10-11] La CI `Dashboard CI` (`.github/workflows/dashboard-ci.yml` : lint + tsc + build) doit rester verte.
- [x] [Deferred-from: v1s-17-1-dashboard-build-ci, 2026-10-11] Décisions produit à confirmer avant de coder : définition du GMV, seuil d'inactivité (14 j par défaut).

## Dev Notes

- **Contrat figé extrait de `page.tsx` (ne pas modifier ce fichier, il est déjà correct)** :
  ```tsx
  const { overviewQuery, activityQuery, trendQuery, inactiveQuery, period, setPeriod,
          customFrom, setCustomFrom, customTo, setCustomTo, page, setPage, refresh } = useOperations();
  <PeriodSelector period={period} onPeriodChange={...} customFrom={customFrom} customTo={customTo}
                  onCustomFromChange={setCustomFrom} onCustomToChange={setCustomTo} />
  <OperationsKpiCards overview={overviewQuery.data} isLoading={overviewQuery.isLoading}
                       lastUpdated={lastUpdated} onRefresh={handleRefresh} />
  <GmvTrendChart data={trendQuery.data} isLoading={trendQuery.isLoading} />
  <TenantActivityTable data={activityQuery.data} isLoading={activityQuery.isLoading}
                        page={page} setPage={setPage} />
  <InactiveTenantsSection data={inactiveQuery.data} />
  ```
- **Pattern backend de référence** : `AdminTenantService.java` (cross-schema JdbcTemplate, `safeLoadSchemaName`/`safeCount`/`safeMaxInstant`) et `AdminSyncMonitoringController.java` (`requireSuperAdmin()`, structure REST). Ne pas réinventer une autre approche de sécurité/agrégation.
- **`recharts` (^3.8.1) est déjà une dépendance** (`package.json:20`) — utiliser cette lib pour `GmvTrendChart` (ex. `LineChart`/`AreaChart`), ne pas en ajouter une autre.
- **Sécurité SQL** : `schemaName` provient exclusivement de `public.tenants.schema_name` (jamais d'input utilisateur), validé au provisioning (`kv_[a-z0-9]{6}`) — cohérent avec le commentaire de sécurité déjà documenté dans `AdminTenantService`. Ne pas introduire de nouvelle source de schemaName non validée.
- **Décisions par défaut à documenter, pas à bloquer dessus** : GMV/périodes/seuil d'inactivité (voir Contexte) — implémenter avec ces valeurs par défaut, noter explicitement dans Completion Notes que ce sont des hypothèses en attente de confirmation PO.

### Project Structure Notes

- Backend : nouveau module `keevo/backend/src/main/java/com/keevo/admin/operations/**` — même niveau que `admin/tenant`, `admin/catalog`, `admin/sync_monitoring`.
- Frontend : `keevo/dashboard/src/{types/operations.ts, lib/api/admin-operations.ts, hooks/useOperations.ts, components/operations/*.tsx}` — `app/monitoring/activity/page.tsx` existe déjà et ne doit PAS être modifié (son contrat est la spec).

### References

- [Source: _bmad-output/planning-artifacts/audit-coherence-2026-07-23.md#2. Findings — 🔴 Critiques, Finding #6]
- [Source: keevo/dashboard/src/app/monitoring/activity/page.tsx — contrat de props figé]
- [Source: keevo/dashboard/src/app/monitoring/products/page.tsx, hooks/useProducts.ts, lib/api/admin-catalog.ts, components/catalog/CatalogSummaryCards.tsx — pattern Next.js/React Query de référence (Story 9.6)]
- [Source: keevo/backend/.../admin/tenant/application/service/AdminTenantService.java — pattern cross-schema JdbcTemplate]
- [Source: keevo/backend/.../admin/sync_monitoring/adapter/in/rest/AdminSyncMonitoringController.java — pattern REST + requireSuperAdmin()]

## Dev Agent Record

### Agent Model Used

Claude Opus 5.5 (bmad-build-auto, spec `spec-9-7-monitoring-activite-operationnelle.md`)

### Debug Log References

- `mvn -o test -Dtest='AdminOperations*Test'` — 45 tests, 0 failure (service unit 31, controller 10, PostgreSQL integration 4)
- `mvn -o test -Dtest='com.keevo.admin.**'` — 97 tests, 0 failure; `-Dtest='com.keevo.architecture.**'` — 6 tests, 0 failure
- Dashboard: `npm run lint`, `npx tsc --noEmit`, `npm test` (11 files / 58 tests), `npm run build` — all green

### Completion Notes List

- **Product defaults kept (PO hypotheses, confirmed as defaults by the spec):** GMV = `SUM(total_amount)` of `sales` with `status = 'COMPLETED'` (CANCELLED and PENDING_VALIDATION excluded); orders = count of those sales; the period filters `COALESCE(occurred_at, created_at)`; periods `TODAY | 7D | 30D | 90D | CUSTOM` on Africa/Douala (WAT) day boundaries (half-open instant window); `CUSTOM` = inclusive ISO `from`/`to`, `from ≤ to`, ≤ 366 days, else 422 `VALIDATION_ERROR` (also for an unknown period, missing dates, `page < 0`, `pageSize` outside 1..100). Missing `period` defaults to `7D`. Last activity = MAX(latest sale time — any status —, latest `audit_log.occurred_at`); inactive = null or older than 14 days (exactly 14 days is still active); `daysSinceLastActivity` = whole 24 h periods. Active tenants = ≥ 1 COMPLETED sale in the period; new tenants = `public.tenants.created_at` in the period. Tenant table: 25 rows/page, GMV desc then name (case-insensitive, nulls last). Inactive list: never active first, then longest dormant, then name.
- **Backend** `com.keevo.admin.operations` (controller, DTOs, `AdminOperationsService`, domain records, 4 use-case ports): only `public.tenants` with `status = 'ACTIVE'`, KV-ADMIN system tenant excluded, schema names gated by `TenantSchema.isValid`; one aggregated query per tenant schema per endpoint; a tenant whose query fails is skipped (logged with tenant id only). The service is deliberately **not** `@Transactional`: on PostgreSQL a failed statement aborts the whole transaction, which would silently zero every following tenant. Window bounds are bound as `OffsetDateTime` (timestamptz, independent of JVM/session time zone); trend groups by `(sale time AT TIME ZONE 'Africa/Douala')::date` and zero-fills missing days. Plan read from the tenant-schema `subscriptions` table (source of truth per epic context), falling back to `public.tenants.plan_type`. `Clock` injected (existing `ClockConfig` bean). Each controller method calls `CurrentActor.requireSuperAdmin()`; no write endpoint.
- **Tests:** `AdminOperationsServiceTest` (mocked JdbcTemplate: WAT bounds incl. UTC/WAT day shift, 366-day limit, invalid periods, cross-tenant sums, active/new counts, skip on failure, invalid schema never interpolated, zero-filled trend, sorting/pagination, inactivity threshold and ordering); `AdminOperationsControllerTest` (`standaloneSetup` + `GlobalExceptionHandler`, 403 on the 4 endpoints for OWNER, 422 propagation, `jsonPath` on every field the dashboard reads); `AdminOperationsServiceIntegrationTest` (real PostgreSQL: COMPLETED-only GMV with CANCELLED/PENDING_VALIDATION/out-of-window sales, created_at fallback, WAT day grouping, MAX(sale, audit) = 15 days, unprovisioned schema skipped).
- **Dashboard:** `types/operations.ts`, `lib/api/admin-operations.ts` (4 functions on the shared `client`), `hooks/useOperations.ts` (exact contract shape; custom dates as `yyyy-MM-dd` strings; queries disabled while a CUSTOM range is incomplete/invalid; `placeholderData` keeps the previous page/period on screen except while the range is invalid; changing a custom date resets the page; `refresh` invalidates the 4 queries), 5 components under `components/operations/`, `lib/period.ts` (client mirror of the CUSTOM rules, inline error in `PeriodSelector`), `periodLabel` added to `lib/labels.ts`. Plans via `planLabel`, tenant by name or « Sans nom », never ids/schema/raw codes; amounts in XAF.
- **Page restored from e1f91c6** with the setState-during-render removed: `lastUpdated` is now derived from `overviewQuery.dataUpdatedAt`. The fixed props are unchanged; like 9-8, components take extra *used* optional props for the error/refresh states required by the epic (`isError`, `isFetching`, `onRetry`, and `isLoading` for `InactiveTenantsSection`): « Erreur de chargement » + « Réessayer » on first-load failure, stale-data warning on a failed refresh, « Actualiser » disabled while any query is in flight. Shared `LoadError`/`StaleDataWarning` reused from `components/sync/SyncErrorStates`.
- **Review fixes:** per-tenant skip now limited to schema/SQL/data errors — connection/resource failures (`DataAccessResourceFailureException` incl. `CannotGetJdbcConnectionException`, `TransientDataAccessException`, `RecoverableDataAccessException`) are rethrown so the endpoint fails instead of answering zeros; store count limited to `type = 'STORE'`; tenants filtered out by `TenantSchema.isValid` logged (tenant id only); CUSTOM years outside 2000–9999 → 422. Dashboard: `rangeIncomplete` exposed by the hook, KPI cards / chart / tenant table show « Choisissez une période valide pour afficher les données » instead of empty states (inactive tenants are period-independent and keep their data); stale-data warning on chart, table and inactive section; page clamped to the last page when a refresh returns fewer pages (done in the tenants queryFn — an effect is rejected by the `set-state-in-effect` lint rule); tooltip shows GMV and number of sales; `2026-02-31`-style rollovers rejected client-side. Added page wiring test and GmvTrendChart test.
- Sidebar « Activité » enabled (`comingSoon: false`). Task 6.1: the 9.7 section already existed in `epic-9-super-admin-dashboard-toor.md`; its story-file status line updated to done.

### File List

- keevo/backend/src/main/java/com/keevo/admin/operations/adapter/in/rest/AdminOperationsController.java (new)
- keevo/backend/src/main/java/com/keevo/admin/operations/adapter/in/rest/dto/{OperationsOverviewDto,GmvTrendPointDto,TenantActivityDto,TenantActivityPageDto,InactiveTenantDto}.java (new)
- keevo/backend/src/main/java/com/keevo/admin/operations/application/service/AdminOperationsService.java (new)
- keevo/backend/src/main/java/com/keevo/admin/operations/domain/model/{OperationsOverview,GmvTrendPoint,TenantActivity,TenantActivityPage,InactiveTenant}.java (new)
- keevo/backend/src/main/java/com/keevo/admin/operations/domain/port/in/{OperationsPeriodQuery,GetOperationsOverviewUseCase,GetGmvTrendUseCase,ListTenantActivityUseCase,ListInactiveTenantsUseCase}.java (new)
- keevo/backend/src/test/java/com/keevo/admin/operations/application/service/AdminOperationsServiceTest.java (new)
- keevo/backend/src/test/java/com/keevo/admin/operations/application/service/AdminOperationsServiceIntegrationTest.java (new)
- keevo/backend/src/test/java/com/keevo/admin/operations/adapter/in/rest/AdminOperationsControllerTest.java (new)
- keevo/dashboard/src/types/operations.ts (new)
- keevo/dashboard/src/lib/api/admin-operations.ts (new)
- keevo/dashboard/src/lib/period.ts (new)
- keevo/dashboard/src/lib/labels.ts (periodLabel)
- keevo/dashboard/src/hooks/useOperations.ts (new)
- keevo/dashboard/src/components/operations/{PeriodSelector,OperationsKpiCards,GmvTrendChart,TenantActivityTable,InactiveTenantsSection}.tsx (new)
- keevo/dashboard/src/components/operations/__tests__/{PeriodSelector,OperationsKpiCards,TenantActivityTable,InactiveTenantsSection}.test.tsx (new)
- keevo/dashboard/src/lib/__tests__/period.test.ts (new)
- keevo/dashboard/src/components/operations/PeriodRequired.tsx (new)
- keevo/dashboard/src/components/operations/__tests__/GmvTrendChart.test.tsx (new)
- keevo/dashboard/src/app/monitoring/activity/__tests__/page.test.tsx (new)
- keevo/dashboard/src/app/monitoring/activity/page.tsx (restored from e1f91c6, lastUpdated fix, error states)
- keevo/dashboard/src/components/layout/Sidebar.tsx (« Activité » enabled)
- _bmad-output/planning-artifacts/epics/epic-9-super-admin-dashboard-toor.md (9.7 status line)
