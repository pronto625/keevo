---
baseline_commit: 6a0efea
---
# Story 9.7: Monitoring Activité Opérationnelle (Super Admin) — backend + frontend complets

Status: ready-for-dev

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

- [ ] **Task 1 — Backend : créer le module `admin.operations` (AC1-AC4)**
  - [ ] 1.1 Créer `com.keevo.admin.operations` (adapter/in/rest, adapter/in/rest/dto, application/service, domain/model, domain/port/in) — miroir exact de la structure `admin.sync_monitoring`.
  - [ ] 1.2 `AdminOperationsController` : 4 endpoints (AC1-AC4), `requireSuperAdmin()` copié tel quel depuis `AdminSyncMonitoringController`.
  - [ ] 1.3 `AdminOperationsService` : requêtes cross-schema via `JdbcTemplate`, en suivant le pattern `AdminTenantService` (`safeLoadSchemaName`, agrégation `SUM(total_amount) FROM <schema>.sales WHERE status='COMPLETED' AND occurred_at >= ?`, `MAX(occurred_at) FROM <schema>.audit_log` pour l'activité). **Ne pas dupliquer `AdminTenantService` — écrire les helpers JDBC nécessaires dans ce nouveau service** (le codebase ne factorise pas ces petits helpers entre services admin, cohérent avec le style existant).
  - [ ] 1.4 Tests : `AdminOperationsServiceTest` (agrégation correcte, tenant sans schema résolu → skip gracieux, cohérent avec `safeCount`/`safeMaxInstant` de `AdminTenantService`), `AdminOperationsControllerTest` (`standaloneSetup`, 403 non-SUPER_ADMIN, 200 SUPER_ADMIN sur les 4 endpoints).
- [ ] **Task 2 — Frontend : types + client API (AC5)**
  - [ ] 2.1 `types/operations.ts` : `AdminOperationsOverview`, `GmvTrendPoint`, `TenantActivityRow`, `AdminTenantActivityPage`, `InactiveTenant`, `Period = 'TODAY' | '7D' | '30D' | '90D' | 'CUSTOM'`.
  - [ ] 2.2 `lib/api/admin-operations.ts` : 4 fonctions, miroir exact de `lib/api/admin-catalog.ts` (structure `client.get<{data: T}>(...).then(r => r.data.data)`).
- [ ] **Task 3 — Frontend : hook (AC6)**
  - [ ] 3.1 `hooks/useOperations.ts` — miroir `useProducts.ts` (React Query + `useState` pour period/page/custom dates), `refresh()` = `queryClient.invalidateQueries` sur les 4 clés.
- [ ] **Task 4 — Frontend : 5 composants (AC7)**
  - [ ] 4.1 `components/operations/PeriodSelector.tsx`, `OperationsKpiCards.tsx`, `GmvTrendChart.tsx` (utiliser la lib de charts déjà présente dans le projet si une existe pour d'autres graphs admin — vérifier `package.json`/composants existants avant d'ajouter une nouvelle dépendance), `TenantActivityTable.tsx`, `InactiveTenantsSection.tsx` — style Tailwind miroir `components/catalog/*`.
- [ ] **Task 5 — Vérification build (AC8)**
  - [ ] 5.1 `npm run build` — 0 erreur `Module not found`. `npm run lint`/type-check si configuré.
- [ ] **Task 6 — Documentation rétroactive**
  - [ ] 6.1 Ajouter "Story 9.7" à `epic-9-super-admin-dashboard-toor.md` (elle n'y figure pas aujourd'hui) pour fermer le gap de traçabilité identifié par l'audit.

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

### Debug Log References

### Completion Notes List

### File List
