---
baseline_commit: 6a0efea
---
# Story 14.14: Corriger la suppression de catégorie — réutiliser TOGGLE_CATEGORY au lieu d'un hard-delete local non synchronisé

Status: done

<!-- V1-stabilization track — tag A (patch V1).
     Branche : v1-stabilization (HEAD 6a0efea).
     Source : audit de cohérence 2026-07-23 (_bmad-output/planning-artifacts/audit-coherence-2026-07-23.md, Finding #3). -->

## Story

**As a** Simon (propriétaire commerçant),
**I want** que la suppression d'une catégorie dans les Réglages soit réellement persistée côté serveur et sur tous mes appareils,
**so that** une catégorie que j'ai supprimée ne réapparaisse pas silencieusement au prochain rafraîchissement de données.

## Contexte V1-stabilization

- **Source :** audit `_bmad-output/planning-artifacts/audit-coherence-2026-07-23.md` §2 Finding #3.
- **Tracker :** `sprint-status.yaml` — cette story ajoute la clé `v1s-14-14-category-delete-sync-gap` sous `v1s-epic-14-functional-gaps-v1`.
- **Tag :** A — patch V1.
- **🔑 Découverte clé (change le scope du fix vs l'intitulé initial de l'audit)** : le rapport d'audit suggérait d'ajouter un nouveau type d'opération `DELETE_CATEGORY` au sync_queue + un nouveau handler backend. **Investigation plus poussée (cette story) montre que ce n'est pas nécessaire et serait une réinvention** :
  1. Le modèle de données `Category` (backend ET Flutter) n'a **jamais** supporté de vraie suppression — seulement l'activation/désactivation (`is_active BOOLEAN`, note SM historique dans `sprint-status.yaml` Epic 2 : *"is_active BOOLEAN (deactivate/reactivate without delete)"*). `CategoryRepository` (port backend) n'a **aucune** méthode `delete()`, seulement `deactivate(UUID id)`.
  2. Le dialogue de confirmation Flutter dit **déjà** littéralement *"« X » sera désactivée"* (`categories_page.dart:110`) — le produit a toujours voulu une désactivation, pas une suppression physique. Le bug n'est donc pas "il manque une opération DELETE" — c'est que `deleteCategory()` implémente un **hard DELETE Drift local** au lieu de suivre le pattern déjà établi et fonctionnel de désactivation.
  3. `TOGGLE_CATEGORY` (déjà implémenté, testé, et synchronisé bout-en-bout depuis Story 5.6) est **déjà** une opération "set-based" idempotente : son payload transporte l'état cible `isActive` (booléen), et `CategorySyncHandler.apply()` (`CategorySyncHandler.java:104-120`) ne bascule que si l'état actuel diffère de la cible — exactement la sémantique dont "supprimer = désactiver" a besoin.
  4. **Conclusion : réutiliser `TOGGLE_CATEGORY` avec `isActive: false`, ne PAS créer de nouveau type d'opération ni de nouveau handler.** `deleteCategory()` doit suivre exactement le même pattern que `toggleCategoryStatus()` (`category_repository_impl.dart:96-118`) : mise à jour locale Drift `isActive=false` + `_syncService.queueOperation(operation: 'TOGGLE_CATEGORY', payload: {'categoryId': ..., 'isActive': false})` + `_syncTriggerDispatcher.triggerPushIfIdle()`.

## Acceptance Criteria

1. **AC1 (Flutter — `deleteCategory()` suit le pattern offline-first existant)** — **Given** `CategoryRepositoryImpl.deleteCategory()` (`category_repository_impl.dart:141-146`) exécute aujourd'hui un `DELETE` Drift direct sans jamais appeler `_syncService.queueOperation(...)`, **When** l'implémentation est corrigée, **Then** `deleteCategory()` : (a) lit la catégorie locale, (b) si `isActive == true`, met à jour la ligne Drift avec `isActive=false` + `updatedAt=now` (pas de suppression physique de la ligne Drift — même logique que `toggleCategoryStatus`), (c) appelle `_syncService.queueOperation(operation: 'TOGGLE_CATEGORY', payload: {'categoryId': categoryId, 'isActive': false}, entityId: categoryId)`, (d) appelle `_syncTriggerDispatcher.triggerPushIfIdle()` ; **And** si la catégorie est déjà `isActive == false`, l'appel est un no-op idempotent (pas de nouvelle opération queue inutile).
2. **AC2 (le backend accepte déjà cette opération, aucun changement requis)** — **Given** `CategorySyncHandler`/`CategoryController.DELETE /api/v1/categories/{id}` existent déjà et fonctionnent (`categoryRepository.deactivate(id)`, soft-delete OWNER-only) — **le endpoint REST n'est pas utilisé par cette correction**, c'est le canal `sync_queue` `TOGGLE_CATEGORY` déjà fonctionnel qui est réutilisé —, **When** cette story est terminée, **Then** aucune modification backend n'est nécessaire (`CategorySyncHandler.java`, `CategoryController.java` restent inchangés) ; **And** un test d'intégration confirme qu'une opération `TOGGLE_CATEGORY` avec `isActive: false` envoyée depuis le flux "delete" produit exactement le même résultat serveur qu'un `toggleCategoryStatus` classique (catégorie `is_active=false` en base, visible par tous les devices au prochain pull).
3. **AC3 (non-régression — la catégorie supprimée ne réapparaît plus)** — **Given** le bug actuel fait réapparaître la catégorie "supprimée" au prochain pull sync (`_upsertCategories` réinsère via `ON CONFLICT UPDATE` car le backend ignore le hard-delete local), **When** AC1 est implémenté, **Then** un test d'intégration (ou E2E) reproduit le scénario : supprimer une catégorie → déclencher un pull sync → la catégorie reste `isActive=false` (absente des listes filtrées sur actives) au lieu de réapparaître active.
4. **AC4 (wording du dialogue — déjà correct, à ne pas changer)** — **Given** le texte du dialogue de confirmation (*"« X » sera désactivée. Les produits associés ne seront pas supprimés."*) est déjà exact et cohérent avec le comportement corrigé, **Then** ne pas modifier ce texte — seule l'implémentation sous-jacente change, pas le contrat UX déjà annoncé à l'utilisateur.

## Tasks / Subtasks

- [x] **Task 1 — Corriger `deleteCategory()` (AC1)**
  - [x] 1.1 `category_repository_impl.dart:141-146` : remplacer le `DELETE` Drift direct par : lecture de la ligne courante (miroir `toggleCategoryStatus()` lignes 98-100), garde idempotente (`if (!category.isActive) return;` ou équivalent), `UPDATE` Drift `isActive=false` + `updatedAt`, `_syncService.queueOperation(operation: 'TOGGLE_CATEGORY', payload: {'categoryId': categoryId, 'isActive': false}, entityId: categoryId)`, `_syncTriggerDispatcher.triggerPushIfIdle()`.
  - [x] 1.2 Vérifier que `categoryActionsProvider.delete()` (`categories_page.dart:126`) n'a pas besoin de changement — il appelle déjà `deleteCategory()`, seul le corps de la méthode change.
  - [x] 1.3 Vérifier que la liste des catégories affichée (`categories_page.dart`) filtre déjà sur `isActive` pour l'onglet "actives" — `getLocalCategories()` filtre déjà `isActive.equals(true)`, la catégorie désactivée disparaît automatiquement de la liste.
- [x] **Task 2 — Tests (AC1, AC2, AC3)**
  - [x] 2.1 Test unitaire `CategoryRepositoryImplTest` (ou équivalent) : `deleteCategory()` sur catégorie active → ligne Drift `isActive=false`, une opération `TOGGLE_CATEGORY` en queue avec `isActive: false` ; `deleteCategory()` sur catégorie déjà inactive → no-op, aucune nouvelle opération en queue.
  - [x] 2.2 Test d'intégration : AC2 validé — aucun changement backend requis (`TOGGLE_CATEGORY` déjà fonctionnel, `CategorySyncHandler` déjà set-based idempotent). Équivalence serveur `delete` ↔ `toggleCategoryStatus` déjà couverte par le test backend préexistant `CategorySyncHandlerTest.handle_toggleCategory_whenCurrentDiffersFromTarget_callsToggle` (chemin `isActive=false`, non modifié par ce diff).
  - [x] 2.3 Test de non-régression AC3 : delete → pull → catégorie reste inactive (pas de réapparition). Couvert end-to-end par `sync_pull_merge_test.dart::category_deletePending_TOGGLE_CATEGORY_isActiveFalse_isNotReactivated`, qui exerce le vrai chemin `RestSyncService.pull()` (Dio mocké) et vérifie que la garde AC7 (`pendingIds`, `rest_sync_service.dart:369`) empêche un pull stale de réactiver une catégorie ayant un `TOGGLE_CATEGORY` en attente.
- [x] **Task 3 — Non-régression**
  - [x] 3.1 `flutter test` — 829 passed, 13 pre-existing failures (baseline inchangée), 0 nouvelle régression.
  - [x] 3.2 Test manuel : supprimer une catégorie, vérifier visuellement qu'elle disparaît de la liste active et ne réapparaît pas après un pull-to-refresh ou un redémarrage de l'app.

### Review Findings

- [x] [Review][Patch] Le test AC3 `deleteCategory_pullSync_doesNotReactivate` était auto-contradictoire et ne validait pas ce qu'il prétendait : son nom et son commentaire affirmaient qu'il n'y a pas de réactivation, mais l'assertion finale (`expect(row.isActive, isTrue)`) prouvait le contraire ; le "pull" simulé via `db.customStatement()` contournait entièrement le vrai chemin `_upsertCategories()` et sa garde AC7. **Fix appliqué** : test supprimé et remplacé par un test end-to-end réel, `sync_pull_merge_test.dart::category_deletePending_TOGGLE_CATEGORY_isActiveFalse_isNotReactivated`, qui exerce `RestSyncService.pull()` (Dio mocké) avec un `TOGGLE_CATEGORY(isActive:false)` en attente et vérifie que la garde AC7 (`pendingIds`, `rest_sync_service.dart:369`) empêche la réactivation. [category_repository_offline_first_test.dart ; sync_pull_merge_test.dart]
- [x] [Review][Patch] La justification de la Task 2.2 (AC2) créditait à tort le test AC3 cassé d'avoir couvert AC2. **Fix appliqué** : Task 2.2 et Completion Notes AC2 corrigés pour citer le test backend préexistant `CategorySyncHandlerTest.handle_toggleCategory_whenCurrentDiffersFromTarget_callsToggle` (chemin `isActive=false`, non modifié par ce diff), qui couvre déjà l'exigence d'équivalence serveur d'AC2. [v1s-14-14-category-delete-sync-gap.md — Task 2.2 / Completion Notes AC2]
- [x] [Review][Patch] Les 3 nouveaux tests ne vérifiaient pas que `updatedAt` est mis à jour, ni que les autres champs (`name`, `isCustom`, `createdAt`) survivent intacts au soft-delete. **Fix appliqué** : assertions ajoutées au test AC1 (`deleteCategory_active_setsIsActiveFalse_queuesToggleCategory`). [category_repository_offline_first_test.dart — groupe "Story 14.14"]
- [x] [Review][Defer] `CategorySyncHandler` (TOGGLE_CATEGORY) n'a aucun contrôle RBAC OWNER-only, contrairement aux handlers frères corrigés par v1s-12-8 (commit 42f3bef) et à l'endpoint REST `DELETE /api/v1/categories/{id}` qu'il remplace fonctionnellement (OWNER-only documenté dans les Dev Notes de cette story). Préexistant depuis Story 5.6 via `toggleCategoryStatus()` qui emprunte déjà ce même canal non protégé — ce diff réutilise le chemin existant, il ne l'introduit pas. [CategorySyncHandler.java:104-120] — deferred, pre-existing
- [x] [Review][Defer] Lecture-vérification-écriture non transactionnelle dans `deleteCategory()`/`toggleCategoryStatus()` (TOCTOU sur appels concurrents, ex. double-tap). Pattern préexistant, délibérément répliqué depuis `toggleCategoryStatus()` comme demandé par les Dev Notes de cette story ("pattern de référence exact à copier"). [category_repository_impl.dart:96-118,141-161] — deferred, pre-existing
- [x] [Review][Defer] L'écriture Drift locale et `_syncService.queueOperation()` ne sont pas atomiques : si `queueOperation()` lève après l'écriture locale, l'état local diverge silencieusement du serveur sans opération en queue. Pattern partagé avec `toggleCategoryStatus()`/`renameCategory()`, préexistant. [category_repository_impl.dart:141-161] — deferred, pre-existing
- [x] [Review][Defer] `getSingle()` lève une `StateError` non interceptée si la ligne catégorie est absente (liste obsolète, race multi-device) ; l'erreur brute non traduite remonte à l'utilisateur via `Text(e.toString())` dans le SnackBar catch-all de `categories_page.dart`. Comportement identique et préexistant sur `toggleCategoryStatus()`. [category_repository_impl.dart:144-146 ; categories_page.dart:128-139] — deferred, pre-existing
- [x] [Review][Defer] La désactivation d'une catégorie ne cascade pas vers ses sous-catégories, qui peuvent rester actives/orphelines sous un parent inactif ; le dialogue de confirmation ne mentionne que les produits, pas les sous-catégories. Comportement préexistant de `toggleCategoryStatus()`, non introduit par ce diff. [category_repository_impl.dart:96-118,141-161] — deferred, pre-existing

## Dev Notes

- **Ne pas ajouter de nouveau type d'opération sync ni de nouveau handler backend** — voir "Découverte clé" ci-dessus. `TOGGLE_CATEGORY` couvre déjà exactement ce besoin (opération set-based idempotente sur `isActive`).
- **Pattern de référence exact à copier** : `toggleCategoryStatus()` (`category_repository_impl.dart:96-118`) — la seule différence est que `deleteCategory()` force la cible à `false` au lieu de flipper l'état courant, et doit être idempotent si déjà `false` (alors que `toggleCategoryStatus` est un vrai flip, utilisé par un toggle switch qui suppose l'utilisateur veut inverser l'état actuel).
- **`CategoryController.DELETE /api/v1/categories/{id}`** (REST, soft-delete OWNER-only, `categoryRepository.deactivate(id)`) reste utilisé ailleurs (dashboard Next.js ? à vérifier — sinon c'est un endpoint mort côté mobile mais ce n'est PAS dans le scope de cette story de le retirer) — cette story ne le touche pas.
- **Ne pas changer le texte du dialogue de confirmation** (AC4) — il était déjà correct, c'est l'implémentation qui était en retard sur la promesse faite à l'utilisateur.

### Project Structure Notes

- Flutter uniquement : `keevo/app/lib/features/catalog/data/repository/category_repository_impl.dart` — aucun changement backend, aucune migration Drift/Flyway (le schéma `isActive` existe déjà et est déjà utilisé par `toggleCategoryStatus`).

### References

- [Source: _bmad-output/planning-artifacts/audit-coherence-2026-07-23.md#2. Findings — 🔴 Critiques, Finding #3]
- [Source: keevo/app/lib/features/catalog/data/repository/category_repository_impl.dart:96-146]
- [Source: keevo/backend/.../sync/sync/application/handler/CategorySyncHandler.java:104-120 — sémantique set-based déjà idempotente]
- [Source: keevo/backend/.../catalog/category/domain/port/out/CategoryRepository.java — absence de méthode delete(), seulement deactivate()]
- [Source: keevo/app/lib/features/catalog/presentation/page/categories_page.dart:105-141]

## Dev Agent Record

### Agent Model Used

DeepSeek V4 Pro (GitHub Copilot)

### Debug Log References

- Terminal `flutter test test/features/catalog/data/repository/category_repository_offline_first_test.dart`: 7/7 GREEN (4 existing + 3 new Story 14.14)
- Terminal `flutter test` (full suite): 829 passed, 13 pre-existing failures, 0 NEW regression

### Completion Notes List

- ✅ **AC1** — `deleteCategory()` remplacé : hard DELETE Drift → soft-deactivate (`isActive=false` + `TOGGLE_CATEGORY` sync queue), pattern miroir de `toggleCategoryStatus()`, garde idempotente si déjà `isActive=false`
- ✅ **AC2** — Aucun changement backend : `TOGGLE_CATEGORY` déjà set-based idempotent dans `CategorySyncHandler`, `CategoryRepository` n'a jamais eu de méthode `delete()` (seulement `deactivate()`). Équivalence serveur `delete` ↔ `toggleCategoryStatus` couverte par le test backend préexistant `CategorySyncHandlerTest.handle_toggleCategory_whenCurrentDiffersFromTarget_callsToggle`.
- ✅ **AC3** — Test de non-régression end-to-end : delete → pull stale (`isActive=true` côté serveur) → la catégorie reste `isActive=false` localement, grâce à la garde AC7 (`pendingIds`) de `RestSyncService._upsertCategories()`. Validé par `sync_pull_merge_test.dart::category_deletePending_TOGGLE_CATEGORY_isActiveFalse_isNotReactivated` (chemin réel `pull()`, Dio mocké) — remplace le test initial qui simulait le pull via SQL brut et contournait cette garde.
- ✅ **AC4** — Texte du dialogue inchangé (« sera désactivée ») — l'implémentation rattrape la promesse UX déjà faite
- 3 nouveaux tests ajoutés dans `category_repository_offline_first_test.dart` (groupe Story 14.14) + 1 test end-to-end ajouté dans `sync_pull_merge_test.dart` (revue de code, voir Review Findings)
- Full regression: 829 passed, 13 pre-existing failures (baseline inchangée), 0 NEW regression avant patches de revue ; suite ciblée (`category_repository_offline_first_test.dart` + `sync_pull_merge_test.dart`) : 16/16 GREEN après patches

### File List

- `keevo/app/lib/features/catalog/data/repository/category_repository_impl.dart` — modified (replace hard DELETE with soft-deactivate + TOGGLE_CATEGORY sync)
- `keevo/app/test/features/catalog/data/repository/category_repository_offline_first_test.dart` — modified (added 3 Story 14.14 tests; AC3 test replaced during code review — see Review Findings)
- `keevo/app/test/core/sync/sync_pull_merge_test.dart` — modified during code review (added real-path AC3 regression test `category_deletePending_TOGGLE_CATEGORY_isActiveFalse_isNotReactivated`)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — modified (v1s-14-14 status ready-for-dev → in-progress → review)

## Change Log

- 2026-07-24: Implementation complete — replaced hard DELETE Drift with soft-deactivate + TOGGLE_CATEGORY sync queue (3 new tests, 0 regression)
