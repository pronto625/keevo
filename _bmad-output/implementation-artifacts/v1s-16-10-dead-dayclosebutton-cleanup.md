---
baseline_commit: 6a0efea
---
# Story 16.10: Retirer le widget mort `DayCloseButton` (jamais instancié — `_ClosureButton` est la version réelle)

Status: done

<!-- V1-stabilization track — tag A. Branche : v1-stabilization (HEAD 6a0efea).
     Source : audit de cohérence 2026-07-23 (Finding #13). -->

## Story

**As a** Toor (mainteneur du code),
**I want** qu'une seule implémentation du bouton de clôture de journée existe dans le code,
**so that** un futur correctif appliqué au mauvais widget ne soit pas silencieusement sans effet en production, et que le fichier de test associé ne donne pas une fausse confiance sur du code jamais exécuté.

## Contexte V1-stabilization

- **Source :** audit `_bmad-output/planning-artifacts/audit-coherence-2026-07-23.md` §4 Finding #13.
- **Tracker :** `sprint-status.yaml` — clé `v1s-16-10-dead-dayclosebutton-cleanup` sous `v1s-epic-16-mobile-hardening-parity`.
- **Constat (vérifié)** : deux implémentations Flutter quasi-identiques du bouton de clôture de journée coexistent :
  - `DayCloseButton` (classe publique, `features/pos/presentation/widget/day_close_button.dart`, 172 lignes) — gère ses propres états async (`available`/`noSales`/etc. via `dayClosureStateProvider`), affiche un **badge du nombre de ventes du jour** (`todaySalesCountProvider`). **`grep -rn "DayCloseButton(" .` ne retourne qu'un seul résultat : sa propre déclaration** (`day_close_button.dart:24`) — jamais instanciée ailleurs dans l'app.
  - `_ClosureButton` (classe privée, `features/reports/presentation/page/reports_page.dart:535-598+`) — la version **réellement utilisée** dans `ReportsPage`, réimplémente le même flux (`DaySummaryBottomSheet.show` + `DayCloseSuccessOverlay.show`) mais **sans** le badge de compteur de ventes, recevant `isClosed` en paramètre plutôt que de gérer elle-même l'état async.
- **Impact** : aucun impact utilisateur actuel (le flux fonctionne via `_ClosureButton`). Le risque est la maintenance : un futur correctif appliqué à `DayCloseButton` (le fichier "évident" à trouver par son nom public) n'aurait aucun effet visible en production. Le fichier de test associé (`day_close_button_test.dart`) teste du code mort, donnant une fausse impression de couverture.

## Acceptance Criteria

1. **AC1 (décision : `_ClosureButton` est la version cible — pas `DayCloseButton`)** — **Given** `_ClosureButton` est la seule version réellement utilisée en production, **Then** cette story retient `_ClosureButton`/`reports_page.dart` comme référence unique et retire `DayCloseButton` — ne pas inverser ce choix (remplacer `_ClosureButton` par `DayCloseButton` demanderait de re-brancher tous ses providers d'état dans `ReportsPage`, effort disproportionné pour un simple nettoyage de code mort).
2. **AC2 (suppression du widget mort et de son test)** — **Given** `DayCloseButton` (`day_close_button.dart`) n'a aucun site d'appel, **When** cette story est implémentée, **Then** le fichier `day_close_button.dart` et son fichier de test `day_close_button_test.dart` sont supprimés ; **And** tout provider exclusivement utilisé par ce widget (`dayClosureStateProvider` si non partagé ailleurs — vérifier avant suppression) est également retiré s'il devient orphelin.
3. **AC3 (option — porter le badge de compteur de ventes dans `_ClosureButton`, DÉCISION PRODUIT NON BLOQUANTE)** — **Given** `DayCloseButton` affichait un badge "nombre de ventes du jour" que `_ClosureButton` n'a pas, **When** le PO juge cette information utile dans l'écran Rapports actuel, **Then** porter cette fonctionnalité (badge alimenté par `todaySalesCountProvider`) dans `_ClosureButton` avant suppression du reste ; **sinon**, documenter dans Completion Notes que le badge est délibérément non porté (fonctionnalité jamais vue en production, aucune demande utilisateur connue). **Ne pas bloquer AC2 sur cette décision** — la suppression du code mort est valable indépendamment.
4. **AC4 (non-régression)** — **Given** `_ClosureButton`/`ReportsPage` fonctionnent aujourd'hui sans dépendre de `DayCloseButton`, **When** cette story est terminée, **Then** le flux de clôture de journée (bouton → `DaySummaryBottomSheet` → `DayCloseSuccessOverlay`) reste strictement inchangé pour l'utilisateur final.

## Tasks / Subtasks

- [x] **Task 1 — Vérifier l'absence d'autres sites d'appel avant suppression (AC2)**
  - [x] 1.1 Re-grep `DayCloseButton(` et `dayClosureStateProvider`/`todaySalesCountProvider` dans tout `lib/` pour confirmer qu'ils ne sont utilisés nulle part ailleurs qu'entre eux (l'audit ne couvre que HEAD 6a0efea — re-vérifier au moment de l'implémentation).
- [x] **Task 2 — Décision badge (AC3)** — si porté : ajouter l'affichage du badge (compteur `todaySalesCountProvider`) dans `_ClosureButton.build()`, miroir de la présentation déjà existante dans `DayCloseButton._buildAvailableButton()` (lignes 56-90) mais adapté au style de `_ClosureButton`.
- [x] **Task 3 — Suppression (AC2)**
  - [x] 3.1 Supprimer `keevo/app/lib/features/pos/presentation/widget/day_close_button.dart`.
  - [x] 3.2 Supprimer `keevo/app/test/features/pos/presentation/widget/day_close_button_test.dart`.
  - [x] 3.3 Si `dayClosureStateProvider` (ou tout autre provider défini dans le fichier supprimé) n'est référencé nulle part ailleurs, le retirer aussi ; s'il est partagé avec `_ClosureButton`/`ReportsPage`, le déplacer/conserver selon les besoins réels.
- [x] **Task 4 — Non-régression (AC4)**
  - [x] 4.1 `flutter analyze` — 0 nouveau warning (imports orphelins).
  - [x] 4.2 `flutter test` (module `reports`/`pos`) — 0 nouvelle régression.
  - [x] 4.3 Test manuel : clôturer une journée depuis `ReportsPage`, vérifier le flux inchangé.

### Review Findings

_Code review bmad-code-review (3 couches adversariales : Blind Hunter + Edge Case Hunter + Acceptance Auditor). Acceptance Auditor : AC1-AC4 tous conformes byte-for-byte, verdict READY TO MERGE._

- [x] [Review][Patch] `todaySalesCountProvider` orphelin — 3 sites d'invalidation, 0 watch [day_closure_providers.dart:72-85, sync_trigger_notifier.dart:252, record_sale_notifier.dart:118, day_closure_providers.dart:169] — le provider n'est plus consommé nulle part (DayCloseButton était son unique watcher via `ref.watch(todaySalesCountProvider(storeId))`). Les 3 sites d'invalidation (sync, record_sale, closeDay) sont du code mort : `ref.invalidate` sans watch = bookkeeping Riverpod no-op. Les Completion Notes affirmaient à tort qu'il était « partagé » avec reports_page.dart / pos_speed_dial.dart / product_card.dart — ces fichiers n'ont aucun `ref.watch(todaySalesCountProvider)` (vérifié par grep). Supprimer le provider (lignes 72-85) + les 3 invalidation callsites. Cohérent avec l'esprit de la story (nettoyage de code mort, Dev Notes : « ne pas garder au cas où »). **Appliqué 2026-07-27** : provider + doc comment supprimés, 3 invalidation callsites retirés. 0 référence restante (`grep -rn todaySalesCountProvider` = vide). Méthodes repository `getTodaySalesCount`/`getSalesCountAfter` conservées — toujours utilisées par `dayClosureStateProvider`, `main_shell.dart`, `local_dashboard_datasource.dart`.
- [x] [Review][Patch] Doc comment `DayCloseButtonState` désynchronisé [day_closure_providers.dart:17] — le commentaire « visual state of the day close button » référence une classe supprimée. Mettre à jour pour mentionner que l'enum pilote `_ClosureButton` (reports_page.dart) et le gating de `pos_speed_dial.dart`. **Appliqué 2026-07-27** : doc comment étendu (4 lignes) pour documenter les consommateurs réels et mentionner explicitement la suppression de `DayCloseButton` (v1s-16-10).
- [x] [Review][Defer] ~11 docs planning/audit/spec référencent encore `DayCloseButton` — deferred, pre-existing — liste non exhaustive : `spec-hf2-ghost-badge-sales-scoping.md:53,72,135` ; `ux-design-specification.md:326,739` ; `requirements-inventory.md:261` ; `epic-4-point-de-vente-pos.md:232,251` ; `v1s-12-6-rbac-owner-only-scope-store.md:50,102` ; `4-4-cloture-journaliere-historique-des-ventes.md` (~15 refs) ; `sprint-status.yaml:117` ; `audit-coherence-2026-07-23.md:69` ; `AUDIT_CONFORMITE_BMAD.md:257` ; `archive/epics.md:282,1323,1342` ; `deferred-work.md:260`. Nettoyage à faire dans un chore dédié.
- [x] [Review][Defer] Renommer `DayCloseButtonState` → `DayClosureState` — deferred, pre-existing — 7 sites dans 3 fichiers (day_closure_providers.dart, reports_page.dart, pos_speed_dial.dart). Améliore la cohérence du naming post-suppression, mais cascade non-bloquante. À traiter dans un chore de renommage si voulu.

## Dev Notes

- **Ne pas restaurer `DayCloseButton` comme composant partagé "pour plus tard"** — s'il devient utile ailleurs un jour, on le réécrira alors avec le contexte du besoin réel ; le garder "au cas où" est le genre de dette que cette story vise justement à nettoyer.
- **Fichiers de référence** : `keevo/app/lib/features/pos/presentation/widget/day_close_button.dart` (à supprimer), `keevo/app/lib/features/reports/presentation/page/reports_page.dart:535+` (`_ClosureButton`, référence conservée).

### Project Structure Notes

- Suppression pure — aucun nouveau fichier. Vérifier les imports de `day_close_button.dart` dans d'autres fichiers (barrel exports, etc.) avant suppression finale.

### References

- [Source: _bmad-output/planning-artifacts/audit-coherence-2026-07-23.md#4. Findings — 🟡 Moyennes, Finding #13]
- [Source: keevo/app/lib/features/pos/presentation/widget/day_close_button.dart (fichier entier, 172 lignes)]
- [Source: keevo/app/lib/features/reports/presentation/page/reports_page.dart:535-598]

## Dev Agent Record

### Agent Model Used

GitHub Copilot (DeepSeek V4 Pro)

### Debug Log References

N/A — suppression simple, aucun debugging.

### Completion Notes List

- **AC1** : `_ClosureButton` retenu comme version unique — pas d'inversion (DayCloseButton supprimé).
- **AC2** : `day_close_button.dart` et `day_close_button_test.dart` supprimés. `dayClosureStateProvider`/`DayCloseButtonState` conservés dans `day_closure_providers.dart` car partagés avec `reports_page.dart` (`_ClosureButton`) et `pos_speed_dial.dart`. **Correction code review** : `todaySalesCountProvider` initialement conservé à tort (Completion Notes affirmant qu'il était « partagé » — en réalité 0 `ref.watch` nulle part, seulement 3 `ref.invalidate` orphelins). Supprimé lors du code review + 3 invalidation callsites (sync_trigger_notifier, record_sale_notifier, CloseDayNotifier).
- **AC3 (badge)** : DÉLIBÉRÉMENT NON PORTÉ — fonctionnalité jamais visible en production, aucune demande utilisateur connue. Documenté ici pour traçabilité.
- **AC4** : `flutter analyze` 0 warning (615 info pré-existants inchangés). `flutter test test/features/reports/ test/features/pos/` → 169/169 GREEN, 0 régression.

### File List

- **[DELETED]** `keevo/app/lib/features/pos/presentation/widget/day_close_button.dart`
- **[DELETED]** `keevo/app/test/features/pos/presentation/widget/day_close_button_test.dart`
- **[MODIFIED]** `keevo/app/lib/features/pos/presentation/provider/day_closure_providers.dart` — suppression `todaySalesCountProvider` (orphan) + son invalidation dans `CloseDayNotifier.closeDay()` ; doc comment `DayCloseButtonState` étendu (4 lignes) pour documenter les consommateurs réels (`_ClosureButton` reports_page.dart + `pos_speed_dial.dart`) et mentionner explicitement la suppression de `DayCloseButton`.
- **[MODIFIED]** `keevo/app/lib/core/sync/sync_trigger_notifier.dart` — retrait `ref.invalidate(todaySalesCountProvider)` (provider supprimé).
- **[MODIFIED]** `keevo/app/lib/features/pos/presentation/provider/record_sale_notifier.dart` — retrait `ref.invalidate(todaySalesCountProvider)` (provider supprimé).

### Change Log

- 2026-07-27: Suppression du widget mort `DayCloseButton` et de son test (jamais instancié — `_ClosureButton` dans `reports_page.dart` est la version réelle). Providers conservés (partagés). Badge compteur de ventes délibérément non porté (AC3).
- 2026-07-27 (code review bmad-code-review) : 3 couches adversariales (Blind Hunter + Edge Case Hunter + Acceptance Auditor). Acceptance Auditor : AC1-AC4 tous conformes byte-for-byte, verdict READY TO MERGE. 2 patches appliqués — (1) suppression `todaySalesCountProvider` orphelin (0 `ref.watch` dans tout `lib/` ; les 3 `ref.invalidate` étaient du code mort, bookkeeping Riverpod no-op) + 3 invalidation callsites retirés ; (2) doc comment `DayCloseButtonState` étendu pour documenter les consommateurs réels. 2 defers (~11 docs planning à nettoyer, renommage enum `DayClosureState`). 3 dismiss (fausse régression couverture tests, pas de CHANGELOG, binary deletion délibéré).
