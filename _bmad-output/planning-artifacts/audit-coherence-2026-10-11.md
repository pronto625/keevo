---
type: audit-report
date: 2026-10-11
scope: Alignement stories/epics/PRD ↔ code (backend, app, dashboard) ↔ sprint-status.yaml
branche: deploy (HEAD 78dc9e6)
baseline: audit-coherence-2026-07-23.md + audit-complet-coherence-rapport-2026-07-23.md
status: complet — lecture seule, aucun fichier de code modifié
---

# Audit de Cohérence — Projet Keevo (2026-10-11)

## 0. Méthodologie et périmètre

Audit **en lecture seule** sur la branche `deploy` (HEAD `78dc9e6`). Aucun fichier source n'a été modifié et rien n'a été commité. `sprint-status.yaml` n'a pas été touché.

**Lectures de départ :**
- le brief `audit-complet-coherence-prompt.md` ;
- `sprint-status.yaml` (379 lignes, dernière modification le 2026-07-27, commit `4236ddf`) ;
- `deferred-work.md` (406 lignes) ;
- les deux audits du 2026-07-23, qui servent de base de comparaison ;
- les stories UI-2 et UI-3 ;
- le PRD (94 FR) et les fichiers d'epics.

**Vérifications dans le code, en 4 passes :**

| # | Passe | Contenu |
|---|---|---|
| A | Backend sécurité / sync / plans | RBAC des sync handlers, limites de plan, Epic 11, JWT, migrations Flyway |
| B | Flutter | Routes, findings des audits précédents, UI-2/UI-3, `flutter analyze` exécuté (0 erreur, 9 warnings, 616 infos) |
| C | Dashboard Next.js et infra | Résolution scriptée de tous les imports `@/…`, Epics 9 et 10, CI |
| D | Couverture des FR | PRD ↔ requirements-inventory ↔ epics ↔ sprint-status ↔ code |

**Ce qui n'a pas été fait :**
- aucun build complet (`next build`, `mvn`, `flutter build`) ;
- aucune exécution de tests ;
- aucun test manuel de l'application.

Les verdicts « build cassé » reposent donc sur la résolution statique des imports, pas sur une exécution.

**Principe suivi :** les entrées de `deferred-work.md` ne sont pas re-signalées, sauf quand une story `done` est contredite par le code.

---

## 1. Résumé

| Gravité | Nombre | dont reportés de l'audit du 2026-07-23 (non corrigés) |
|---|---|---|
| 🔴 Critique | 4 | 2 |
| 🟠 Importante | 7 | 2 |
| 🟡 Moyenne | 8 | 3 |
| 🔵 Mineure | 11 | 6 |
| **Total** | **30** | **13** |

### Ce qui a été corrigé depuis le 2026-07-23

Les findings les plus graves de l'audit précédent sont fermés dans le code. Les stories de suivi existent et le code les confirme :

| Finding du 2026-07-23 | Story qui l'a fermé |
|---|---|
| #1 Contournement RBAC de `/sync/push` | v1s-12-8 |
| #2 Scope de `complete()` sur les transferts | v1s-12-9 |
| #3 Suppression de catégorie fantôme | v1s-14-14 |
| #4 et #5 Limites du plan Free | v1s-14-13 (`FREE(1, 500, 3)`) |
| #8 `onAppStartup` jamais appelé | v1s-16-8 |
| #9 « Tout marquer comme lu » | v1s-16-7 |
| #13 `DayCloseButton` mort | v1s-16-10 |
| #18 Route `/reports` en double | v1s-16-9 |

S'y ajoutent les tests CSV de la story 2-4 (#12) et la résolution du nom client dans les ventes (I4 du 2026-07-23).

### Les décalages les plus importants aujourd'hui

1. **Statut `done` alors qu'une partie de l'UI manque (le même schéma revient) :**
   - **v1s-14-11** (FR66) : l'UI Flutter d'édition d'un employé n'existe toujours pas. C'est la 3e fois que ce point est signalé.
   - **v1s-14-5** (FR91 et FR92) : backend seul. Aucun écran de suppression de compte ni de feedback dans l'app.
2. **Epic 11 :** 11-1 est `done`, 11-2 et 11-4 sont `review`. Leur code n'existe que sur la branche `refractoring`, qui n'est pas ancêtre de `deploy`. Leurs fichiers de story et `epics-refonte-modulaire.md` sont absents de `deploy`.
3. **Le dashboard Super Admin ne compile toujours pas.** Les pages 9-7 et 9-8 importent 10 modules inexistants, et aucune CI ne couvre le dashboard.
4. **UI-2 et UI-3 ne sont pas suivies dans `sprint-status.yaml`.** UI-3 est un très gros lot (rotation des refresh tokens, moteur de sync, clôtures hors ligne, 13 bugs) qui contient des décisions produit contraires à des AC de stories `done` :
   - 3-5 AC6 : la réaffectation de boutique ne force plus la reconnexion ;
   - v1s-12-6 AC7 et v1s-12-8 AC4 : un EMPLOYEE peut désormais initier un transfert depuis n'importe quelle boutique.
5. **Statuts désalignés dans `sprint-status.yaml` :**
   - 8-3 et 11-6 sont livrés mais toujours en backlog / ready-for-dev ;
   - 7-4 est toujours en `review` ;
   - deux epics V1-stab ont toutes leurs stories `done` mais restent `in-progress` ;
   - `v1s-12-4` est devenu obsolète (Next.js 16 utilise `proxy.ts`).

---

## 2. Suivi des findings des audits du 2026-07-23

| Finding précédent | Statut sprint | Réalité au 2026-10-11 | Preuve |
|---|---|---|---|
| #1 RBAC `/sync/push` | v1s-12-8 done | ✅ **Corrigé pour l'essentiel**, mais il reste des trous (voir I1) | `sync/sync/application/handler/AbstractSyncOperationHandler.java:52,68` ; `ValidateSaleSyncHandler.java:60` |
| #2 Scope de `complete()` | v1s-12-9 done | ✅ Corrigé | `CompleteTransferService.java:60-62` ; `StockTransferController.java:107` |
| #3 Suppression de catégorie fantôme | v1s-14-14 done | ✅ Corrigé via `TOGGLE_CATEGORY` (pas de `DELETE_CATEGORY`, choix de la story). L'écart RBAC reste : voir I1 | `app/lib/features/catalog/data/repository/category_repository_impl.dart:141-157` ; `CategorySyncHandler.java:46` |
| #4 Limite produits sur le chemin standard | v1s-14-13 done | ✅ Corrigé sur tous les chemins (REST, CSV, brouillon, sync) | `CreateProductUseCase.java:149` ; `CreateDraftProductUseCase.java:115` ; `ImportCsvProductsUseCase.java:149` ; `ProductSyncHandler.java:70,117` |
| #5 `PlanType.FREE(3,500,5)` | v1s-14-13 done | ✅ Corrigé : `FREE(1, 500, 3)` | `identity/auth/domain/model/PlanType.java:11` |
| #6 Build dashboard 9-7/9-8 | ready-for-dev | ❌ **Toujours cassé** (voir C1) | `dashboard/src/app/monitoring/activity/page.tsx:5-10`, `monitoring/sync/page.tsx:4-7` |
| #7 Variantes FR22 | 2-1 done | ❌ Toujours absent, et le modèle reste mort (voir I7) | `app/lib/features/catalog/domain/model/product_variant.dart` (aucune référence) |
| #8 `onAppStartup` | v1s-16-8 done | ✅ Corrigé | `app/lib/core/router/app_router.dart:114` ; `auth_provider.dart:345,421` |
| #9 « Tout marquer comme lu » | v1s-16-7 done | ✅ Corrigé (`ref.refresh`) | `notifications_page.dart:60` |
| #10 11-2 OTel absent | review | ❌ Toujours absent de `deploy` (voir C2) | `pom.xml` (aucune dépendance otel/prometheus) |
| #11 `AppErrorWidget` sans retry | — | ❌ Toujours là : 4 usages sur 23 passent `onRetry` (voir M4) | `pos_page.dart:354,472`, `catalog_page.dart:878`, … |
| #12 Tests CSV 2-4 | done | ✅ Corrigé : 3 fichiers de test | `test/features/catalog/presentation/{provider/csv_import_notifier_test,provider/csv_import_parsing_test,page/csv_import_page_test}.dart` |
| #13 `DayCloseButton` mort | v1s-16-10 done | ✅ Corrigé | `day_closure_providers.dart:24` (il ne reste que l'enum) |
| #14 Clôture non restreinte par rôle | — | ⚠️ Toujours ouverte à l'EMPLOYEE, mais limitée à sa boutique côté backend. Aucune décision produit formalisée (voir M2) | `DayClosureController.java:35,65-67` ; `reports_page.dart:368-373` |
| #15 7-4 en `review` | review | ❌ Inchangé (voir I6) | `sprint-status.yaml:152` |
| #16 Stories sans fichier (9-7, 9-8, 11-x) | — | ⚠️ 9-7 et 9-8 ont maintenant un fichier. **Les 6 fichiers 11-x n'existent que sur `refractoring`** (voir C2) | `git ls-tree refractoring -- _bmad-output/implementation-artifacts` |
| #17 Doublon 11-6 / v1s-12-2 | 11-6 ready-for-dev | ❌ Non réconcilié (voir I5) | `sprint-status.yaml:219` |
| #18 `/reports` en double | v1s-16-9 done | ✅ Corrigé | `app_router.dart:342` (une seule occurrence) |
| #19 `TenantPickerPage` sans sortie | — | ❌ Toujours là, désormais documenté dans `deferred-work.md` (UI-3 L2-16) | `tenant_picker_page.dart:88` |
| #25 `GET /stores` 403 pour un EMPLOYEE | — | non revérifié | — |
| #26 `/debug/categories` | — | ❌ Toujours inatteignable et non conditionné à `kDebugMode` (voir m2) | `app_router.dart:594` |
| #27 Comptage de `Colors.white` | v1s-16-3 backlog | ⚠️ 95 occurrences aujourd'hui (265 avant) après le rebrand UI-3 (voir m7) | `lib/features` : 91, `lib/core` : 4 |
| Rapport C2 : `StubApiService` | — | ❌ Toujours le défaut, mais le provider n'a aucun consommateur (voir M3) | `app/lib/core/di/providers.dart:118-119` |
| Rapport C4 : UI d'édition employé (v1s-14-11) | done | ❌ **Toujours absente** (voir C3) | `app/lib/features/team/presentation/page/` |
| Rapport C5 : `PosPlaceholderPage` | — | ❌ Toujours présente, morte (voir m1) | `pos_placeholder_page.dart:22` |
| Rapport I3 : `IllegalArgumentException` → 500 (Category) | — | ❌ Toujours là (voir M6) | `JpaCategoryRepository.java:74,104,118` ; `GlobalExceptionHandler.java:185` |
| Rapport I4 : nom du client en TODO | — | ✅ Corrigé (`saleClientNameProvider`) | `sale_detail_page.dart:128` ; `sales_history_page.dart:444` |
| Rapport I5 : TODO « Upgrade plan » | — | ❌ Toujours là (voir m4) | `plan_limit_bottom_sheet.dart:69` |
| Rapport M1 : PowerSync dans le PRD | — | ❌ Toujours là (voir M7) | `prd.md:88,207,215,238,285,313,514,515` |

---

## 3. Tableau stories ↔ statut ↔ réalité

Les stories `done` vérifiées et conformes ne sont listées qu'en bloc, en fin de tableau.

| Story | Fichier | Statut sprint | Statut dans le fichier | Réalité dans le code | Verdict |
|---|---|---|---|---|---|
| UI-2-onboarding-soft-modern-rebrand | ✅ | **absente** | done | Implémentée (`AppTheme.softTint` `core/theme/app_theme.dart:134`, onboarding rebrandé) | ⚠️ Clé manquante |
| UI-3-rebrand-rollout-and-bug-hunt | ✅ | **absente** | done | Implémentée (`SessionRefresher`, `SessionClaimsApplier`, `SignOutService`, `clearSessionSecrets`, V8/V9, garde `/terms` public `app_router.dart:170`) | ⚠️ Clé manquante. Points encore ouverts : §5.5 |
| 1-4 onboarding | ✅ | done | done | Wizard OK. **FR7 (tutoriels vidéo) absent** | ⚠️ AC partiel |
| 2-1 CRUD produits | ✅ | done | done | **FR22 / AC4 variantes absent** | ⚠️ AC partiel |
| 2-4 import CSV | ✅ | done | **in-progress** | Implémenté, tests présents | Statut du fichier obsolète |
| 3-5 invitation employés | ✅ | done | **in-progress** | Création directe sans invitation WhatsApp (FR65 partiel). AC6 (re-login forcé à la réaffectation) contredit par UI-3 | ⚠️ |
| 4-4 clôture journalière | ✅ | done | **in-progress** | Implémenté. Les tests listés comme non cochés existent tous | Statut du fichier obsolète |
| 4-5 annulation/correction | ❌ | backlog | — | **FR90 livré** par v1s-13-5 (`PendingSaleController.java:79,95`) | ⚠️ Commentaire « superseded » manquant |
| 5-3 conflits delta | ✅ | done | **in-progress** | Implémenté | Statut du fichier obsolète |
| 7-3, 7-5, 7-6, 8-0 | ✅ | done | **review** | Implémentés. 7-3 : **FR58 SMS absent** | Statut du fichier obsolète |
| 7-4 rentabilité | ✅ | **review** | review | Implémenté de bout en bout (`ProfitabilityController.java:55,101`). 74 tâches non cochées, aucune revue | ⚠️ Revue jamais faite |
| 8-1 alertes | ✅ | done | done | FR61 et FR62 OK. **FR64 (statut colis) absent** | ⚠️ AC partiel (FR Growth) |
| 8-3 désactivation + révocation | ❌ | **backlog** | — | **Implémentée** : `EmployeeController.java:125,139` ; `DeactivateEmployeeService.java:59,69` | ❌ Statut faux |
| 8-4, 8-5 | ❌ | backlog (superseded) | — | Backend livré par v1s-14-5, pas d'UI | Commentaire à compléter |
| 9-7 monitoring activité | ✅ | ready-for-dev | ready-for-dev | Page `.tsx` commitée qui importe des modules inexistants. Aucun backend | ❌ Casse le build (C1) |
| 9-8 monitoring sync | ✅ | ready-for-dev | ready-for-dev | Backend complet (`AdminSyncMonitoringController.java:30-59`), page cassée | ❌ Casse le build (C1) |
| 10-4 backups | ✅ | review | review | Scripts présents. Le smoke test exigé n'est pas fait (`pg_restore --list` seulement, `BACKUP_RESTORE.md:278-288`) | ✅ Statut honnête |
| 10-5 staging | ✅ | review | review | Le workflow lance son propre Traefik, contrairement à la décision prise (`deploy-staging.yml:156-173`) | ✅ Statut honnête |
| 11-1 logs JSON / MDC | ❌ (sur `refractoring` seulement) | **done** | — | **Absent de `deploy`** : pas de logback, pas de `X-Request-Id`, pas de MDC | ❌ C2 |
| 11-2 OTel / Prometheus | ❌ (idem) | **review** | — | **Absent de `deploy`** | ❌ C2 |
| 11-3 audit append-only | ❌ (idem) | done | — | Couche applicative seulement (`AuditLogSpringRepository.java:16`). Pas de trigger en base, ce qui est conforme à la note de réconciliation | ✅ OK sur le fond |
| 11-4 correctifs RBAC exposition | ❌ (idem) | **review** | — | **Partiel sur `deploy`** : chemin admin, `TenantSchema.validate` et OWNER-only des ventes en attente sont présents (via v1s-12-3, 12-5, 12-6). **Ownership du device token absent** (`DeleteDeviceTokenService.java:18`) et N+1 admin non corrigé | ❌ C2 |
| 11-5 perf N+1 + bug d'index | ❌ (idem) | ready-for-dev | — | Bug d'index corrigé autrement (clé renommée, `DayClosureDeltaProvider.java:41`). N+1 toujours là (`AdminTenantService.java:220`) | ⚠️ Scope à réduire |
| 11-6 révocation 5 min | ❌ (idem) | **ready-for-dev** | — | **Livrée** par v1s-12-2 (`JwtAuthFilter.java:159-163`, cache TTL 300 s) | ❌ Statut faux |
| v1s-12-4 renommer `proxy.ts` en `middleware.ts` | ❌ | backlog | — | **Obsolète.** Next 16.2.4 a remplacé `middleware.ts` par `proxy.ts` ; le fichier actuel suit déjà la bonne convention | ❌ À fermer |
| v1s-12-6 / v1s-12-8 | ✅ | done | done | AC7 / AC4 « transfert OWNER-only » **inversés** par décision produit non tracée (`StockTransferController.java:66-69` ; `TransferSyncHandler.java:39-41`) | ⚠️ I2 |
| v1s-14-5 RGPD + feedback | ✅ | **done** | done | Backend complet (`AccountController.java:30,41,54` ; `FeedbackController.java:25`). **Flutter non implémenté** : la story le dit elle-même (`v1s-14-5-rgpd-feedback.md:263`) | ❌ C4 |
| v1s-14-11 édition employé | ✅ | **done** | done | Backend OK. **Aucune UI Flutter** : `team/presentation/page/` ne contient que create et team, et la ligne 354 de sprint-status dit « UI pages pending » | ❌ C3 |
| v1s-16-2 iOS Darwin | ✅ (backlog) | backlog | — | Non implémentée (`fcm_service.dart:141` : Android seulement) | ✅ Cohérent |
| v1s-16-3 `Colors.white` en mode sombre | ✅ (backlog) | backlog | — | Avancée en partie de fait par UI-3 (265 → 95) | ⚠️ Estimation à refaire |
| v1s-epic-13, v1s-epic-15 | — | in-progress | — | Toutes leurs stories sont `done` | ⚠️ Passer l'epic à `done` |
| Backlog 4-6, 8-2, 1-9, v1s-14-2, 14-6, 14-7, epics 12 à 17 | — | backlog | — | Rien d'implémenté en secret : pas de leaderboard vendeurs, pas de suivi d'activité, pas d'export PDF/Excel, pas de pinning, pas de refonte modulaire sur `deploy` | ✅ Cohérent |

**Stories `done` vérifiées conformes :**
- Epic 1 sauf FR7 ;
- Epics 2, 3, 5, 6 sauf les points signalés ;
- 7-1, 7-2 ;
- 8-6, 9-1, 9-6 ;
- 10-1, 10-2, 10-3, 10-6 ;
- HF-1, HF-2 sauf M1 ;
- v1s-12-1, 12-2, 12-3, 12-5, 12-7, 12-9 ;
- v1s-13-1 à 13-7 ;
- v1s-14-8, 14-9, 14-10, 14-12, 14-13, 14-14 ;
- v1s-15-3, 15-5 ;
- v1s-16-1, 16-4 à 16-10.

---

## 4. Findings — 🔴 Critiques

### C1 — Le dashboard Super Admin ne compile toujours pas (9-7 / 9-8)

| Champ | Valeur |
|---|---|
| **Story** | 9-7, 9-8 (ready-for-dev) |
| **Module** | Dashboard Next.js |
| **Description** | Les pages `monitoring/activity` et `monitoring/sync` (commit e1f91c6) importent 10 modules inexistants : `@/hooks/useOperations`, `@/hooks/useSyncMonitoring`, `@/components/operations/{OperationsKpiCards,PeriodSelector,GmvTrendChart,TenantActivityTable,InactiveTenantsSection}` et `@/components/sync/{SyncOverviewCards,SyncHealthTable,SyncTenantDetailDrawer}`. Une résolution scriptée de tous les imports `@/…` montre que ce sont les seuls manquants. Le flag `comingSoon: true` du menu (`Sidebar.tsx:24-25`) n'empêche pas leur compilation. |
| **Pourquoi** | L'audit du 2026-07-23 a seulement corrigé le statut (review → ready-for-dev). Le code n'a pas bougé. Aucune CI ne couvre le dashboard (`.github/workflows` : backend, flutter, deploy, staging). |
| **Impact** | `next build` échoue, donc aucun déploiement du dashboard n'est possible, y compris pour les pages qui marchent (tenants, catalogue). Le backend de 9-8 (`AdminSyncMonitoringController.java:30,40,48,59`) n'a aucun consommateur. |
| **Preuves** | `dashboard/src/app/monitoring/activity/page.tsx:5-10` ; `dashboard/src/app/monitoring/sync/page.tsx:4-7` ; `components/operations/` et `components/sync/` n'existent pas. |
| **Recommandation** | Retirer les deux pages du dépôt en attendant l'implémentation, ou livrer 9-8 (son backend est prêt). Ajouter un job CI `dashboard` (lint + build). |

### C2 — Epic 11 : statuts `done`/`review` pour du code absent de la branche livrée

| Champ | Valeur |
|---|---|
| **Story** | 11-1 (done), 11-2 (review), 11-4 (review), 11-5 et 11-6 (ready-for-dev) |
| **Module** | Backend / Documentation |
| **Description** | Ces stories ont été développées sur la branche `refractoring` (commits d8d61d0 « refractoring start » et ae81a6c), qui **n'est pas ancêtre de `deploy`** (seules `dev` et `refractoring` la contiennent). Sur `deploy` : pas de `logback*.xml`, pas d'encodeur JSON, pas de `X-Request-Id`, pas de MDC (11-1) ; aucune dépendance OTel ou Prometheus, pas de `KEEVO_OTLP_ENDPOINT` (11-2) ; ownership du device token absent et N+1 admin non corrigé (11-4). Les 6 fichiers `11-*.md` et `epics-refonte-modulaire.md` (cité comme source des epics 10 à 17 par `sprint-status.yaml:189-200` et par les stories 10-x) n'existent que sur `refractoring`. |
| **Pourquoi** | `sprint-status.yaml` décrit l'état d'une autre branche que celle déployée. Les épics refonte 10 à 17 ont été planifiés sur `refractoring`, puis la piste V1-stab est repartie de `deploy`. |
| **Impact** | Un lecteur croit l'observabilité livrée (logs JSON, traçage) alors que la production n'en a pas. La faille device-token (11-4) reste ouverte en prod : `DELETE` d'un token FCM sans contrôle de propriétaire. |
| **Preuves** | `git branch --contains d8d61d0` → `dev`, `refractoring` ; `application.yml:120` (niveaux de log uniquement) ; `messaging/notification/application/service/DeleteDeviceTokenService.java:18` (`deviceTokenRepository.deleteByToken(token)` sans vérifier le propriétaire) ; `admin/tenant/application/service/AdminTenantService.java:220` (`safeLoadSchemaName` appelé pour chaque ligne). |
| **Recommandation** | Requalifier les statuts (voir §8). Décider explicitement : merger le travail Epic 11 de `refractoring` dans `deploy`, ou le considérer comme non livré. Ramener les fichiers 11-x et `epics-refonte-modulaire.md` sur `deploy`, ou corriger les références. |

### C3 — v1s-14-11 `done` sans UI Flutter d'édition employé (FR66), 3e signalement

| Champ | Valeur |
|---|---|
| **Story** | v1s-14-11 |
| **Module** | Flutter / Équipe |
| **Description** | Le backend est complet : `PATCH /employees/{id}`, `/role`, `POST /password`. Côté Flutter, le repository a `updateEmployee` (`employee_repository_impl.dart:162`), mais aucun écran ne l'utilise. `team/presentation/page/` ne contient que `create_employee_page.dart` et `team_page.dart`. Le menu d'actions de `EmployeeCard` propose seulement : réaffecter, regénérer le mot de passe, désactiver, réactiver (`employee_card.dart:183-241`). Le provider généré `updateEmployeeProvider` n'est utilisé nulle part hors `.g.dart`. |
| **Pourquoi** | La story est passée `done` à la fin du backend. Son propre commentaire dans sprint-status (l.354) dit « Flutter: … UI pages pending ». Les tâches 9.1 à 9.7 sont non cochées (`v1s-14-11-employee-full-edit.md:166-173`). |
| **Impact** | Le propriétaire ne peut pas modifier le nom, le téléphone, le rôle ou le mot de passe d'un employé. FR66 n'est pas utilisable. |
| **Recommandation** | Repasser v1s-14-11 en `in-progress` (§8) et livrer la tâche 9 (`edit_employee_page.dart` et ses actions). |

### C4 — v1s-14-5 `done` sans UI Flutter : RGPD (FR91) et feedback (FR92) inaccessibles

| Champ | Valeur |
|---|---|
| **Story** | v1s-14-5 |
| **Module** | Flutter / Paramètres |
| **Description** | Le backend est livré : `AccountController` (`/api/v1/account/delete` et `/delete/cancel`, `AccountController.java:30,41,54`), `AccountDeletionScheduler`, `FeedbackController.java:25`, `AdminFeedbackController`, et le handler sync `SubmitFeedback`. Dans l'app, aucun appel à `/api/v1/account` ni à `/feedback`, aucun `DeletionPendingBanner`, aucun `FeedbackFormPage`. `account_page.dart:14` ne charge que le profil. |
| **Pourquoi** | La story dit elle-même « Flutter tasks (AC7-9, AC14-15) — NOT YET IMPLEMENTED » (`v1s-14-5-rgpd-feedback.md:263`), mais elle a été passée `done` (sprint-status l.348 : « Flutter non implémenté (Tasks 7-8) »). |
| **Impact** | Un propriétaire ne peut pas demander la suppression de son compte depuis l'app : c'est une obligation RGPD, et le flux reste réservé à l'admin. Les retours utilisateurs n'arrivent jamais à Toor. Les commentaires des stories 8-4 et 8-5 (« superseded ») laissent croire que FR91 et FR92 sont couverts. |
| **Recommandation** | Repasser v1s-14-5 en `in-progress` (§8) et livrer les tâches 7 et 8 Flutter. |

---

## 5. Findings — 🟠 Importantes

### I1 — RBAC de la sync : 3 trous restants après v1s-12-8

| Champ | Valeur |
|---|---|
| **Story** | v1s-12-8, v1s-14-14, 2-4 |
| **Module** | Backend / Sync |
| **Description** | (a) **`PROMOTE_PRODUCT` et `UPDATE_PRODUCT` par la sync** : `ProductSyncHandler` utilise le constructeur à 10 arguments de `UpdateProductDto`, qui met `actorRole` à `"OWNER"` par défaut (`UpdateProductUseCase.java:67-72`). Le garde « promotion brouillon → actif réservée à l'OWNER » (`:88`) est donc contourné par un EMPLOYEE hors ligne. `CREATE_DRAFT_PRODUCT` code aussi `"OWNER"` en dur (`ProductSyncHandler.java:126`). (b) **`TOGGLE_CATEGORY`** n'a aucun contrôle de rôle (`CategorySyncHandler.java:104-120`). Depuis v1s-14-14, la suppression d'une catégorie passe par ce type d'opération, alors que le `DELETE` REST est OWNER-only (`CategoryController.java:147`). Ce point est déjà dans `deferred-work.md:332`. (c) **`InventorySessionSyncHandler`** n'applique pas le scope boutique de son équivalent REST (`InventorySessionController.java:66-69`) et écrit directement par `repository.save` (`:68`). |
| **Impact** | Un EMPLOYEE peut promouvoir un brouillon, désactiver ou supprimer une catégorie, et ouvrir une session d'inventaire dans une autre boutique, simplement en passant par la file hors ligne. |
| **Recommandation** | Petite story de suivi : propager `isOwnerRole()` et `extractAssignedStoreId()` d'`AbstractSyncOperationHandler` dans ces 3 handlers, et passer le vrai rôle à `UpdateProductDto`. |

### I2 — Initiation de transfert ouverte à tout EMPLOYEE (n'importe quelle boutique), contrairement à des AC `done`

| Champ | Valeur |
|---|---|
| **Story** | v1s-12-6 AC7, v1s-12-8 AC4, FR36 |
| **Module** | Backend / Stock |
| **Description** | Les commits 4302eba et suivants ont retiré le garde OWNER-only de `POST /stock/transfers` et de `STOCK_TRANSFER` en sync. Le code le dit lui-même : « OWNER and EMPLOYEE (any store, not scoped to their assigned store — product decision superseding Story 12.6 » (`StockTransferController.java:66-69`), et « superseding the OWNER-only guard from Story v1s-12-8 AC4 / Story 12.6 AC7 » (`TransferSyncHandler.java:39-41`). |
| **Pourquoi** | Une décision produit a été prise en correctif, sans spec ni story, et sans mise à jour des AC, de l'epic 3 ni de FR36 (« employé limité à sa boutique »). |
| **Impact** | Un employé peut sortir du stock de n'importe quelle boutique. La documentation (stories `done`) décrit l'inverse. |
| **Recommandation** | Faire valider cette décision par le PO. Si elle est confirmée, l'écrire dans le PRD (FR32/FR36) et en note sur v1s-12-6 et v1s-12-8. Sinon, au minimum, limiter la boutique **source** à l'affectation de l'employé. |

### I3 — Durée de vie de l'access token : 60 jours au lieu des 24 h du PRD et de l'architecture

| Champ | Valeur |
|---|---|
| **Story** | 1-3, NFR9, UI-3 |
| **Description** | `access-token-expiry-hours: 1440  # 60 days` et `refresh-token-expiry-days: 360` (`backend/src/main/resources/application.yml:83-84`). Le PRD (NFR9, l.482) et `architecture.md:47` disent 24 h. UI-3 §5.5 note que la rotation des refresh tokens permet maintenant de raccourcir ce délai, mais rien n'a changé. |
| **Impact** | Un access token volé reste valide 60 jours. Les révocations reposent uniquement sur `tokens_valid_after` (cache de 300 s). |
| **Recommandation** | Ramener l'access token à 24 h, maintenant que le refresh silencieux est en place (`SessionRefresher`), ou amender NFR9. |

### I4 — UI-2 et UI-3 non suivies, et décisions de UI-3 non répercutées dans les stories et le PRD

| Champ | Valeur |
|---|---|
| **Story** | UI-2, UI-3 (fichiers `done`, absents de sprint-status) |
| **Description** | UI-3 est le plus gros lot depuis juillet : 287 fichiers app, 155 fichiers backend, Flyway V8 et V9. Plusieurs de ses décisions contredisent des AC de stories `done` : **3-5 AC6** (re-login forcé à la réaffectation, remplacé par `STORE_REASSIGNED` + refresh, `ReassignStoreService.java:43-45`), règles de clôture (une par boutique et par jour, fenêtre arrivée/occurrence, refus au-delà de 7 jours), stock négatif autorisé uniquement par la sync tardive, signature du rapport d'écarts au prénom. UI-3 a aussi une checklist de release (backend avant app) qui n'est reprise nulle part dans les docs de déploiement. Points encore ouverts (§5.5) : `'default'` comme storeId en mode « toutes boutiques » (`reports_page.dart:32`, `pos_speed_dial.dart:101`) et la durée du token (I3). |
| **Impact** | `sprint-status` ne montre pas le travail récent. Les AC de 3-5 et de 4-4 / 5-3 décrivent un comportement qui n'est plus le bon. |
| **Recommandation** | Ajouter les clés UI-2 et UI-3 (§8). Reporter les décisions §5.4 de UI-3 dans les stories concernées (3-5, 4-4, 5-3, 6-4) ou dans le PRD. Créer un item pour le storeId `'default'`. |

### I5 — Statuts faux pour du travail livré : 8-3, 11-6, 4-5

| Champ | Valeur |
|---|---|
| **Description** | **8-3** (backlog) est livrée : `PATCH /employees/{id}/deactivate` et `/reactivate` (`EmployeeController.java:125,139`), avec révocation des refresh tokens et des sessions (`DeactivateEmployeeService.java:59,69`). **11-6** (ready-for-dev) est livrée par v1s-12-2 (`JwtAuthFilter.java:159-163`, migration V4). C'était déjà le finding #17, toujours pas réconcilié. **4-5** (backlog) : FR90 est livré en V1 par v1s-13-5 (`PendingSaleController.java:79,95` ; `sale_detail_page.dart:216`), mais aucun commentaire ne le dit, alors que 8-5 et 9-2 en ont un. |
| **Recommandation** | Voir §8. |

### I6 — 7-4 toujours en `review` après 3 mois

| Champ | Valeur |
|---|---|
| **Description** | FR54 et FR56 sont implémentés (`ProfitabilityController.java:55,101`) et UI-3 les a modifiés (`local_profitability_datasource.dart`). 74 tâches restent non cochées et la story n'a pas de section « Review Findings ». Cela maintient l'epic 7 en `in-progress`. |
| **Recommandation** | Faire la revue de code, ou passer en `done` avec une note explicite. |

### I7 — Stories `done` avec un FR / AC non livré

| FR | Story `done` | Réalité | Preuve |
|---|---|---|---|
| FR7 tutoriels vidéo | 1-4 | Absent : seulement une SnackBar POS | `app/lib/core/storage/app_constants.dart:31` ; AC de l'epic 1 l.187 |
| FR22 variantes | 2-1 (AC4) | Absent : modèle mort, `catalog/variant/` vide | `product_variant.dart` sans référence ; TODO dans `MultiStoreStockRepositoryAdapter.java:141` |
| FR58 hebdo par SMS | 7-3 | WhatsApp seulement, aucun code SMS | AC de l'epic 7 l.122 |
| FR64 statut colis | 8-1 | Absent (FR Growth) | aucune occurrence de « colis » / « parcel » |
| FR65 invitation WhatsApp | 3-5 | Création directe, pas d'invitation | `EmployeeController.java:72` |

**Remarque :** v1s-14-1 (FR22) et v1s-14-3 / 14-4 (FR79, FR82, FR83) sont tag B, donc exclus de V1-stab. Ils n'ont **aucune clé** dans sprint-status, et la refonte qui devait les reprendre n'existe pas sur `deploy` (C2). FR22 n'a donc plus aucun porteur actif.

**Recommandation :** pour chaque ligne, soit retirer l'AC de la story (et différer le FR explicitement dans le PRD), soit créer une story backlog porteuse.

---

## 6. Findings — 🟡 Moyennes

| # | Story | Module | Description | Preuves | Recommandation |
|---|---|---|---|---|---|
| M1 | HF-2 | Flutter / Catalogue | Le commit 1464d34 (« autoriser les employés à créer des produits ») annule la règle « Catalogue (read-only) » de HF-2 pour l'EMPLOYEE, sans spec ni mise à jour de HF-2. UI-3 en a seulement pris acte en corrigeant le test. | `HF-2-…md:60,178` ; `catalog_page.dart` (FAB sans condition) ; `UI-3…md` §« Stale tests » | Documenter la décision dans HF-2 et dans le PRD (FR29). |
| M2 | 4-4, HF-2 | Backend + Flutter | Clôture journalière ouverte à l'EMPLOYEE (sur sa boutique) côté REST, sync et UI. C'est cohérent entre couches, mais la question ouverte de `spec-hf2-ghost-badge` n'a jamais été tranchée. Le storeId `'default'` en mode « toutes boutiques » reste non décidé (UI-3 §5.5). | `DayClosureController.java:35,65-67` ; `DayClosureSyncHandler.java:44` ; `reports_page.dart:32,368-373` | Décision PO à écrire dans 4-4. |
| M3 | — | Flutter / DI | `apiServiceProvider` renvoie toujours `StubApiService()`, mais n'a plus aucun consommateur. `tenant_preferences_provider.dart:15-18` utilise `DioApiService` et ne retombe sur le stub qu'en cas d'exception, ce qui donne des préférences vides sans erreur. Gravité plus basse qu'en juillet. | `app/lib/core/di/providers.dart:118-119` | Supprimer le provider mort et faire remonter l'erreur au lieu du repli silencieux. |
| M4 | transverse | Flutter / UX | `AppErrorWidget` : 4 usages sur 23 passent `onRetry`. Les 19 autres affichent l'erreur sans bouton Réessayer. | `sync_settings_page.dart:260,338,456`, `pos_page.dart:354,472`, `sales_history_page.dart:85,116`, `catalog_page.dart:878`, `categories_page.dart:209`, `notifications_page.dart:67`, `sale_detail_page.dart:50`, `report_detail_page.dart:51`, `stores_list_page.dart:234` | Story d'hygiène UX. |
| M5 | 2-4, 3-5, 4-4, 5-3, 7-3, 7-5, 7-6, 8-0, v1s-14-11, v1s-14-5 | Documentation | 8 fichiers de story ont un `Status:` différent de sprint-status (fichier `in-progress` ou `review`, sprint `done`). Des stories `done` gardent des dizaines de tâches non cochées (2-5 : 76, 2-3 : 71, 3-1 et 3-3 : 65, 5-2 : 61, v1s-14-11 : 45). Pour 4-4, les tests listés comme non faits existent tous. | `grep -n '^Status' implementation-artifacts/*.md` | Aligner l'en-tête `Status` des fichiers. Ne pas se fier aux cases à cocher. |
| M6 | 2-1 / v1s-15-3 | Backend / Catalogue | `IllegalArgumentException` → HTTP 500 sur les catégories : aucun handler dédié, on tombe dans `@ExceptionHandler(Exception.class)`. | `JpaCategoryRepository.java:74,104,118` ; `RenameCategoryUseCase.java:40` ; `CreateCategoryUseCase.java:42` ; `GlobalExceptionHandler.java:185` | Les convertir en `DomainException` (déjà dans `deferred-work`, mais toujours là). |
| M7 | PRD / epics | Documentation | (a) PowerSync encore cité dans le PRD (l.88, 207, 215, 238, 285, 313, NFR29 l.514, NFR30 l.515). (b) **FR3b** (« mot de passe oublié ») est cité par sprint-status l.355 mais n'existe ni dans le PRD ni dans `requirements-inventory`, alors que le code existe (`AuthController.java:322,349`). (c) `epics/index.md` et les fichiers d'epic ne listent pas 5-6, 6-2a, 7-6, 8-0, 8-6, 9-6, 9-7, 9-8, UI-1/2/3, HF-1/2. `index.md:29` nomme encore la 1.7 « Audit Trail ». (d) `epics-remediation-audit.md` ne contient pas v1s-12-8, 12-9, 14-13, 14-14, 16-7 à 16-10. | — | Passe documentaire unique. |
| M8 | 9-2 / FR78 | Backend + Dashboard | Il existe un endpoint d'activation de plan (`AdminSubscriptionController.java:51`, story 1.6) sans aucune UI dashboard, et sans historique des paiements. FR78 est partiellement livré alors que 9-2 est en backlog « superseded ». | — | Le noter dans le commentaire de 9-2. |

---

## 7. Findings — 🔵 Mineures

| # | Story | Description | Preuves |
|---|---|---|---|
| m1 | 4-1 | `PosPlaceholderPage` est morte : elle n'est référencée que par son test. | `app/lib/features/pos/presentation/page/pos_placeholder_page.dart:22` ; `test/features/pos/pos_placeholder_page_test.dart` |
| m2 | — | `/debug/categories` : aucune navigation n'y mène et la route n'est pas conditionnée à `kDebugMode`. | `app_router.dart:594` |
| m3 | 1-7 | `TenantPickerPage` et `PasswordChangePage` forcée : pas de retour ni de déconnexion. Documenté depuis dans `deferred-work` (UI-3). | `tenant_picker_page.dart:88` |
| m4 | 1-6 | Le TODO « Upgrade plan » est toujours là : aucun parcours d'upgrade (FR17 partiel). | `plan_limit_bottom_sheet.dart:69` |
| m5 | v1s-12-4 | La story est obsolète : `dashboard/src/proxy.ts` suit déjà la convention de Next 16.2.4. Le proxy vérifie seulement la présence du cookie `ks_admin_auth`, pas sa validité. | `dashboard/src/proxy.ts` ; `dashboard/package.json` |
| m6 | 11-6 / v1s-12-2 | Le cache de révocation a un TTL de 300 s, contre 60 s dans la story 11-6. Cela reste conforme à « < 5 min ». | `CachedTokenRevocationAdapter.java:35` |
| m7 | v1s-16-3 | 95 `Colors.white` (91 dans features, 4 dans core) contre 265 en juillet. L'estimation de la story est périmée. | `product_form_page.dart` (13), `cart_pill.dart` (7), … |
| m8 | 10-x | Trou dans la numérotation Flyway : V3 n'existe que sur `refractoring`. Sans effet tant que `outOfOrder` est faux, mais un merge depuis `refractoring` créerait une migration V3 hors ordre. | `backend/src/main/resources/db/migration/` (V1, V2, V4 à V9) |
| m9 | — | `flutter analyze` : 0 erreur, 9 warnings (imports ou champs inutilisés, par exemple `providers.dart:4`, `stock_repository_impl.dart:25,316`, `cart_bottom_sheet.dart:17`). | exécution du 2026-10-11 |
| m10 | HF-2 | Commentaire périmé : « EMPLOYEE sees only Caisse + Plus » alors que le shell employé a 4 onglets (`main_shell.dart:44`). | `app_router.dart:199` |
| m11 | sprint-status | `v1s-epic-13-concurrency-integrity` et `v1s-epic-15-tests-debt-v1` restent `in-progress` alors que toutes leurs stories V1 sont `done`. `epic-4` reste `in-progress` alors que ses seules stories ouvertes (4-5, 4-6) sont remplacées par 17-1 et 17-2. | `sprint-status.yaml:113,336,362` |

---

## 8. Recommandations

### 8.1 Corrections précises proposées pour `sprint-status.yaml`

Ces changements ne sont **pas appliqués**. Ils sont à faire par le SM / `bmad-sprint-planning`.

```yaml
# Epic 1 — ajouter après UI-1 (l.50) :
  UI-2-onboarding-soft-modern-rebrand: done   # 2026-09-12/24 (3a0377d) — rebrand soft/modern onboarding
  UI-3-rebrand-rollout-and-bug-hunt: done     # 2026-10-11 (4c188fd, 1041827, 78dc9e6) — rollout rebrand + 13 bugs + rotation refresh token (V8) + clôtures hors ligne (V9) ; décisions §5.4 ; ouverts §5.5 (storeId 'default', TTL JWT 60 j)

# Epic 4
  4-5-annulation-correction-de-vente: backlog   # ⤳ superseded par 17-1 ; V1 livrée via v1s-13-5 (FR90)
  # epic-4: in-progress → done (optionnel : 4-5/4-6 superseded par 17-1/17-2, comme pour epic-9)

# Epic 7
  7-4-dashboard-rentabilite-analyse-par-produit-boutique: review → done   # uniquement après une passe bmad-code-review

# Epic 8
  8-3-desactivation-employe-revocation-de-sessions: backlog → done   # livrée : EmployeeController /deactivate,/reactivate + DeactivateEmployeeService (révocation) via 3-5/v1s-12-2
  8-4-…: backlog   # compléter : backend V1 via v1s-14-5, UI Flutter manquante
  8-5-…: backlog   # compléter : backend V1 via v1s-14-5, UI Flutter manquante

# Epic 9
  9-2-activation-plan-payant: backlog   # compléter : endpoint AdminSubscriptionController /activate existe (1.6), pas d'UI
  9-7-…: ready-for-dev   # ajouter : page.tsx commitée avec imports manquants → casse next build
  9-8-…: ready-for-dev   # idem ; backend AdminSyncMonitoringController prêt

# Epic 11 — le code n'est pas sur deploy (branche refractoring)
  11-1-logs-json-structures-mdc: done → in-progress   # code uniquement sur refractoring (d8d61d0), absent de deploy
  11-2-tracing-otel-metriques-prometheus: review → in-progress   # idem
  11-4-correctifs-rbac-exposition: review → in-progress   # deploy : admin path/TenantSchema/PendingSale OK via v1s-12-x ; device-token ownership + N+1 admin absents
  11-5-correctifs-performance-n1-bug-index: ready-for-dev   # ajouter : bug d'index déjà corrigé (clé totalRevenue) ; reste N+1
  11-6-revocation-session-5min: ready-for-dev → done   # livrée par v1s-12-2 (JwtAuthFilter tokens_valid_after, cache 300 s)

# V1 stabilization
  v1s-12-4-dashboard-middleware-rename: backlog → done   # obsolète : Next 16 a remplacé middleware.ts par proxy.ts (wontfix)
  v1s-12-8-rbac-sync-push-scope: done   # ajouter : AC4 transfert OWNER-only inversé par décision produit (4302eba) ; trous TOGGLE_CATEGORY/PROMOTE_PRODUCT/InventorySession (audit 2026-10-11 I1)
  v1s-14-5-rgpd-feedback: done → in-progress   # Flutter Tasks 7-8 (FR91/FR92) non livrées
  v1s-14-11-employee-full-edit: done → in-progress   # UI Flutter (Task 9) non livrée — FR66
  v1s-16-3-dark-mode-colors-white-banner: backlog   # mettre à jour : 95 Colors.white après UI-3 (au lieu de 41/265)
  v1s-epic-13-concurrency-integrity: in-progress → done
  v1s-epic-15-tests-debt-v1: in-progress → done
```

**Nouvelles clés backlog proposées (suivi des findings) :**
- `v1s-12-10-sync-rbac-residual` (I1) ;
- `v1s-14-15-employee-edit-ui` (si l'on préfère ne pas rouvrir v1s-14-11) ;
- `v1s-14-16-rgpd-feedback-ui` (même logique pour v1s-14-5) ;
- `v1s-17-1-dashboard-build-ci` (C1) ;
- `v1s-16-11-app-error-retry` (M4).

### 8.2 Priorisation

1. **Avant la prochaine release du dashboard :** C1, c'est-à-dire retirer ou réparer les pages 9-7 et 9-8 et ajouter une CI dashboard.
2. **Vérité des statuts (½ journée, documentaire) :**
   - C2 (Epic 11 et branche `refractoring`) ;
   - I4 (clés UI-2 et UI-3) ;
   - I5 (8-3, 11-6, 4-5) ;
   - m11 ;
   - toutes les corrections du §8.1.
3. **Sécurité :** I1 (3 handlers sync), C2 / 11-4 (ownership du device token), I3 (durée du JWT), et décision PO sur I2 (transferts EMPLOYEE).
4. **Fonctionnel incomplet derrière des statuts `done` :** C3 (édition employé), C4 (RGPD et feedback Flutter), I7 (FR7, FR22, FR58, FR64, FR65 : retirer l'AC ou créer une story porteuse).
5. **Documentation :** M7 (PowerSync, FR3b, index des epics), report des décisions de UI-3 (§5.4) dans les stories et le PRD (I4, M1, M2).
6. **Hygiène :** M3, M4, M6 et les mineures m1 à m10, à traiter quand on retouche les écrans concernés.

---

*Rapport produit le 2026-10-11 en lecture seule sur `deploy@78dc9e6`. Aucun fichier de code ni `sprint-status.yaml` n'a été modifié.*
