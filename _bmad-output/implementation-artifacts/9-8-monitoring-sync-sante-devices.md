---
baseline_commit: 6a0efea
---
# Story 9.8: Monitoring Synchronisation & Santé Devices (Super Admin) — frontend Next.js manquant

Status: review

<!-- V1-stabilization track — tag A (release-blocker pour le build dashboard).
     Branche : v1-stabilization (HEAD 6a0efea).
     Source : audit de cohérence 2026-07-23 (_bmad-output/planning-artifacts/audit-coherence-2026-07-23.md, Finding #6).
     Contrairement à 9-7, le BACKEND de cette story est déjà réel et complet (AdminSyncMonitoringController,
     3 endpoints SUPER_ADMIN-only, entièrement fonctionnels) — seul le frontend Next.js a été committé comme
     un stub référençant des hooks/composants jamais créés. Travail purement frontend. -->

## Story

**As a** Toor (super admin plateforme),
**I want** visualiser la santé de synchronisation et des devices par tenant (appareils actifs, opérations échouées, conflits),
**so that** je puisse détecter proactivement les tenants ayant des problèmes de synchronisation offline avant qu'ils ne se plaignent.

## Contexte

- **Backend déjà livré et fonctionnel — NE PAS LE MODIFIER** : `AdminSyncMonitoringController` (`com.keevo.admin.sync_monitoring.adapter.in.rest`) expose 3 endpoints, tous `SUPER_ADMIN`-only (`requireSuperAdmin()`) :
  - `GET /api/v1/admin/sync/overview` → `AdminSyncOverviewDto { totalDevices, totalFailedOps7d, totalConflicts7d, tenantsInAlert, latestPushGlobally }`
  - `GET /api/v1/admin/sync/tenants` → `List<AdminTenantSyncHealthDto> { tenantId, tenantName, tenantPlan, failedOps7d, conflicts7d, deviceCount, lastPushAt, lastPullAt, status }`
  - `GET /api/v1/admin/sync/tenants/{tenantId}/detail` → `AdminSyncTenantDetailDto { tenantId, tenantName, tenantPlan, devices: [{deviceId, userId, lastPushAt, lastPullAt, updatedAt}], recentFailedOps: [{operationType, errorReason, processedAt}], recentConflicts: [{entityType, conflictType, strategy, resolvedAt}] }`
- **Le contrat de `sync/page.tsx` est figé — ne PAS le modifier.** Le hook `useSyncMonitoring()` DOIT retourner exactement : `{ overviewQuery, healthQuery, selectedTenantId, setSelectedTenantId, refresh }`.
- **Aucune décision produit à prendre** — contrairement à 9-7, tout le contrat de données existe déjà côté backend, il suffit de le consommer fidèlement.

## Acceptance Criteria

1. **AC1 (`types/sync-monitoring.ts` — miroir exact des 5 DTOs backend)** — **Given** les DTOs backend `AdminSyncOverviewDto`/`AdminTenantSyncHealthDto`/`AdminSyncTenantDetailDto`/`AdminDeviceInfoDto`/`AdminSyncFailedOpDto`/`AdminSyncConflictDto` (voir Contexte pour les champs exacts), **When** cette story est terminée, **Then** un fichier `types/sync-monitoring.ts` définit les interfaces TypeScript correspondantes champ pour champ (dates en `string` ISO côté TS, cohérent avec la sérialisation Jackson `Instant`).
2. **AC2 (`lib/api/admin-sync.ts`)** — **Given** le pattern déjà établi (`lib/api/admin-catalog.ts`), **When** cette story est terminée, **Then** 3 fonctions async `getSyncOverview()`, `getTenantSyncHealth()`, `getTenantSyncDetail(tenantId: string)` appellent respectivement les 3 endpoints via le `client` axios partagé (`lib/api/client.ts`), déballant `res.data.data`.
3. **AC3 (`hooks/useSyncMonitoring.ts`)** — **Given** `sync/page.tsx` (déjà commité, ne pas modifier) déstructure `{ overviewQuery, healthQuery, selectedTenantId, setSelectedTenantId, refresh }`, **When** cette story est terminée, **Then** `useSyncMonitoring()` retourne : `overviewQuery`/`healthQuery` en `useQuery` React Query (staleTime raisonnable, ex. 30s — ces données sont quasi temps-réel), `selectedTenantId` en `useState<string | null>(null)`, `refresh()` invalidant les 2 queries. **Note** : `AdminSyncTenantDetailDto` (3ᵉ endpoint) est consommé séparément par `SyncTenantDetailDrawer` (voir AC4), pas par le hook principal — le drawer déclenche sa propre `useQuery` conditionnelle sur `tenantId` (activée seulement si `tenantId !== null`).
4. **AC4 (3 composants, props exactes déjà fixées par `page.tsx`)** — **Given** `page.tsx` passe déjà des props précises (voir Dev Notes pour l'extrait exact), **When** cette story est terminée, **Then** : `SyncOverviewCards({data, isLoading, onRefresh})` affiche les 5 KPIs de `AdminSyncOverviewDto` en cartes (miroir style `CatalogSummaryCards`) ; `SyncHealthTable({data, isLoading, onRowClick, selectedTenantId})` affiche `AdminTenantSyncHealthDto[]` en table triable, ligne cliquable appelant `onRowClick(tenantId)`, ligne sélectionnée visuellement distincte (`selectedTenantId`) ; `SyncTenantDetailDrawer({tenantId, onClose})` est un panneau latéral (drawer) qui, si `tenantId !== null`, fetch et affiche `AdminSyncTenantDetailDto` (devices + opérations échouées récentes + conflits récents en 3 sous-sections), avec un bouton de fermeture appelant `onClose`.
5. **AC5 (état visuel `status` de `AdminTenantSyncHealthDto`)** — **Given** le champ `status` (string, valeurs à déterminer par inspection du backend — probablement `HEALTHY | WARNING | CRITICAL` ou équivalent, vérifier `AdminSyncMonitoringService`/le modèle domaine `AdminTenantSyncHealth` pour les valeurs exactes avant de coder), **When** `SyncHealthTable` affiche une ligne, **Then** un badge coloré reflète ce statut (ex. vert/orange/rouge), cohérent avec la palette Indigo Sky du dashboard si elle est partagée avec le Flutter (`#51CF66`/`#FCC419`/`#FA5252`) — sinon utiliser les couleurs Tailwind déjà en usage ailleurs dans le dashboard (`text-green-600`, etc., cf. `CatalogSummaryCards`).
6. **AC6 (build vert)** — **Given** l'objectif premier de cette story est de restaurer un dashboard fonctionnel, **When** cette story est terminée, **Then** `npm run build` réussit sans erreur `Module not found` liée à cette page.

## Tasks / Subtasks

- [x] **Task 1 — Inspecter le domaine backend avant de coder (AC5)**
  - [x] 1.1 Lire `com.keevo.admin.sync_monitoring.domain.model.AdminTenantSyncHealth` (et le service qui calcule `status`) pour connaître les valeurs exactes possibles du champ `status` — ne pas deviner.
- [x] **Task 2 — Types + client API (AC1, AC2)**
  - [x] 2.1 `types/sync-monitoring.ts` : les 6 interfaces miroir des DTOs backend (voir Contexte).
  - [x] 2.2 `lib/api/admin-sync.ts` : 3 fonctions, miroir `lib/api/admin-catalog.ts`.
- [x] **Task 3 — Hook (AC3)**
  - [x] 3.1 `hooks/useSyncMonitoring.ts` — `overviewQuery`/`healthQuery` (`useQuery`, `staleTime: 30_000`), `selectedTenantId` (`useState`), `refresh` (`queryClient.invalidateQueries`).
- [x] **Task 4 — Composants (AC4, AC5)**
  - [x] 4.1 `components/sync/SyncOverviewCards.tsx` — 5 cartes KPI, squelette `animate-pulse` pendant `isLoading`, bouton refresh (miroir `OperationsKpiCards` de 9-7 si déjà écrite, sinon `CatalogSummaryCards`).
  - [x] 4.2 `components/sync/SyncHealthTable.tsx` — table avec badge `status` coloré (Task 1), ligne cliquable, surbrillance `selectedTenantId`.
  - [x] 4.3 `components/sync/SyncTenantDetailDrawer.tsx` — drawer conditionnel sur `tenantId`, `useQuery(['admin-sync-tenant-detail', tenantId], () => getTenantSyncDetail(tenantId!), {enabled: tenantId !== null})`, 3 sous-sections (devices/failedOps/conflicts), bouton fermeture.
- [x] **Task 5 — Vérification build (AC6)**
  - [x] 5.1 `npm run build` — 0 erreur `Module not found`.
- [ ] **Task 6 — Documentation rétroactive**
  - [ ] 6.1 Ajouter "Story 9.8" à `epic-9-super-admin-dashboard-toor.md` (absente aujourd'hui).

## Dev Notes

- **Contrat figé extrait de `sync/page.tsx` (ne pas modifier ce fichier)** :
  ```tsx
  const { overviewQuery, healthQuery, selectedTenantId, setSelectedTenantId, refresh } = useSyncMonitoring();
  <SyncOverviewCards data={overviewQuery.data} isLoading={overviewQuery.isLoading} onRefresh={refresh} />
  <SyncHealthTable data={healthQuery.data} isLoading={healthQuery.isLoading}
                    onRowClick={(id) => setSelectedTenantId(id)} selectedTenantId={selectedTenantId} />
  <SyncTenantDetailDrawer tenantId={selectedTenantId} onClose={() => setSelectedTenantId(null)} />
  ```
- **Backend déjà complet et testé — aucune modification attendue** dans `com.keevo.admin.sync_monitoring.**`. Si un bug backend est découvert pendant l'implémentation (peu probable), le traiter comme un defer séparé, pas dans le scope de cette story frontend.
- **Pattern de référence Next.js** : `hooks/useProducts.ts` (React Query), `lib/api/admin-catalog.ts` (client HTTP), `components/catalog/CatalogSummaryCards.tsx` (style cartes + squelette). Story 9.7 (si développée en parallèle) introduit des composants `operations/*` très similaires en forme — s'en inspirer pour la cohérence visuelle mais ne pas coupler les deux stories (modules Next.js indépendants `operations/` vs `sync/`).
- **`SyncTenantDetailDrawer` — pattern "drawer"** : vérifier si un composant de drawer/modal générique existe déjà dans `components/layout/` ou ailleurs dans le dashboard avant d'en écrire un nouveau à partir de zéro (éviter la réinvention).

### Project Structure Notes

- Frontend uniquement : `keevo/dashboard/src/{types/sync-monitoring.ts, lib/api/admin-sync.ts, hooks/useSyncMonitoring.ts, components/sync/*.tsx}` — `app/monitoring/sync/page.tsx` existe déjà, ne pas le modifier.
- Aucun changement backend.

### References

- [Source: _bmad-output/planning-artifacts/audit-coherence-2026-07-23.md#2. Findings — 🔴 Critiques, Finding #6]
- [Source: keevo/dashboard/src/app/monitoring/sync/page.tsx — contrat de props figé]
- [Source: keevo/backend/.../admin/sync_monitoring/adapter/in/rest/AdminSyncMonitoringController.java + dto/*.java — contrat backend complet et exact]
- [Source: keevo/dashboard/src/{hooks/useProducts.ts, lib/api/admin-catalog.ts, components/catalog/CatalogSummaryCards.tsx} — pattern Next.js/React Query de référence]

### Deferred items routed 2026-10-11

- [ ] [Deferred-from: v1s-17-1-dashboard-build-ci, 2026-10-11] Harnais de tests dashboard absent [keevo/dashboard/package.json] — aucun script `test` ni dépendance de test ; les comportements UI de 9-8 (squelettes, vide/erreur, sélection clavier, drawer + Échap) ne sont couverts que par lint/tsc/build + revue. Mettre en place Vitest + Testing Library et couvrir `SyncHealthTable`, `SyncTenantDetailDrawer`, ainsi que la restauration du token au refresh (`lib/auth/auth-context.tsx` : seed `sessionStorage.ks_admin_token`, la 1re requête doit porter `Authorization: Bearer`).
- [ ] [Deferred-from: v1s-17-1-dashboard-build-ci, 2026-10-11] Contrat JSON sync non verrouillé [AdminSyncMonitoringControllerTest.java:58,85,102] — ajouter des assertions `jsonPath` sur chaque champ lu par `keevo/dashboard/src/types/sync-monitoring.ts` (overview, tenants, detail) pour qu'un renommage de DTO casse un test.
- [ ] [Deferred-from: v1s-17-1-dashboard-build-ci, 2026-10-11] Erreurs de rafraîchissement silencieuses [components/sync/SyncOverviewCards.tsx, SyncHealthTable.tsx] — `page.tsx` (contrat figé) ne transmet ni `isError` ni `isFetching` : un refetch échoué garde les données périmées sans signal, une requête « paused » hors-ligne affiche l'erreur. Réglé si la page passe `error`/`isFetching` aux composants (retry + spinner sur « Actualiser »).
- [ ] Task 6.1 (ajout de "Story 9.8" à `epic-9-super-admin-dashboard-toor.md`) reste ouverte.

### Deferred items routed 2026-10-13

- [ ] [Deferred-from: follow-up review v1s-17-1, 2026-10-13] Identifiants et codes bruts dans le panneau de synchronisation [keevo/dashboard/src/components/sync/SyncTenantDetailDrawer.tsx:120-125, sections opérations/conflits] — la colonne Appareil/Utilisateur affiche des UUID et les opérations/conflits affichent `operationType`, `conflictType`, `strategy` bruts (ex. CREATE_SALE, LWW). Règle 2026-10-13 : libellés français compréhensibles (« Téléphone de Awa », nom de l'employé, « Création de vente »…), sinon omettre le champ. Nécessite d'ajouter `deviceName` / nom de l'utilisateur aux DTO `AdminDeviceInfo` (backend) et une table de libellés côté dashboard ; v1s-17-1 interdisait tout changement backend.

## Dev Agent Record

### Agent Model Used

Claude Opus 5.5 (via v1s-17-1-dashboard-build-ci)

### Debug Log References

### Completion Notes List

- 2026-10-11 — Frontend livré par la story v1s-17-1 (audit-coherence-2026-10-11 C1). `page.tsx` non modifié ; aucun changement backend.
- Valeurs de `status` vérifiées dans `AdminSyncMonitoringService.computeStatus` : `OK | WARN | ERROR` (badge vert/ambre/rouge).
- La table conserve l'ordre renvoyé par le backend (pas de tri côté client). Instants `null` affichés « — ».
- `refresh()` invalide aussi la clé du détail tenant (`admin-sync-tenant-detail`) en plus des 2 queries du hook.
- Comme `page.tsx` ne transmet pas l'erreur des queries, `SyncOverviewCards`/`SyncHealthTable` affichent une erreur inline quand `!isLoading && data === undefined`.
- Entrée sidebar « Synchronisation » passée à `comingSoon: false`.
- Vérifié : `npm ci && npm run lint && npx tsc --noEmit && npm run build` sur copie propre — 0 erreur.

### File List

- keevo/dashboard/src/types/sync-monitoring.ts (nouveau)
- keevo/dashboard/src/lib/api/admin-sync.ts (nouveau)
- keevo/dashboard/src/hooks/useSyncMonitoring.ts (nouveau)
- keevo/dashboard/src/components/sync/SyncOverviewCards.tsx (nouveau)
- keevo/dashboard/src/components/sync/SyncHealthTable.tsx (nouveau)
- keevo/dashboard/src/components/sync/SyncTenantDetailDrawer.tsx (nouveau)
- keevo/dashboard/src/components/layout/Sidebar.tsx (modifié)
