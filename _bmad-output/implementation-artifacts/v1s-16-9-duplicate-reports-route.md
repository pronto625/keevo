---
baseline_commit: 6a0efea
---
# Story 16.9: Retirer la route `/reports` dupliquée (orpheline) dans `app_router.dart`

Status: done

<!-- V1-stabilization track — tag A. Branche : v1-stabilization (HEAD 6a0efea).
     Source : audit de cohérence 2026-07-23 (Finding #18). -->

## Story

**As a** Toor (mainteneur du code),
**I want** qu'il n'existe qu'une seule déclaration de la route `/reports`,
**so that** un futur réordonnancement accidentel des routes ne fasse pas silencieusement basculer l'app vers un écran vide au lieu de l'écran Rapports réel.

## Contexte V1-stabilization

- **Source :** audit `_bmad-output/planning-artifacts/audit-coherence-2026-07-23.md` §4 Finding #18.
- **Tracker :** `sprint-status.yaml` — clé `v1s-16-9-duplicate-reports-route` sous `v1s-epic-16-mobile-hardening-parity`.
- **Constat (vérifié par lecture directe, lignes confirmées sur HEAD 6a0efea)** : `app_router.dart` déclare `/reports` deux fois :
  - Ligne 501-508 (dans le `ShellRoute`) : la route **réelle**, résout vers `OwnerReportsPage`/`EmployeeReportsPage` selon le rôle — celle effectivement utilisée par l'app (accessible depuis le tab de navigation "Rapports").
  - Ligne 686-688 (route top-level, hors `ShellRoute`) : une déclaration **orpheline** pointant vers `_PlaceholderPage(title: 'Reports')`, un reliquat probable d'un stub créé avant l'implémentation réelle d'Epic 7. GoRouter résout par ordre de déclaration dans la liste `routes: [...]` — la première correspondance (ligne 501, le `ShellRoute`) gagne systématiquement, donc la déclaration ligne 686 n'est **jamais atteignable**, quel que soit le chemin de navigation emprunté.
- **Pourquoi corriger un bug sans impact utilisateur actuel** : c'est du code mort silencieux aujourd'hui, mais un risque latent — si le `ShellRoute` est un jour réordonné, retiré, ou restructuré (probable lors de la refonte modulaire Epic 15, routing dynamique GoRouter), l'app basculerait silencieusement vers un écran placeholder vide au lieu de lever une erreur explicite de route dupliquée.

## Acceptance Criteria

1. **AC1 (suppression de la déclaration orpheline)** — **Given** la déclaration `GoRoute(path: '/reports', builder: (_, __) => const _PlaceholderPage(title: 'Reports'))` (lignes 686-688) n'est jamais atteignable, **When** cette story est implémentée, **Then** cette déclaration est retirée de `app_router.dart`.
2. **AC2 (nettoyage de `_PlaceholderPage` si devenue totalement inutilisée)** — **Given** `_PlaceholderPage` (classe définie ligne 182-186) n'a, avant cette story, qu'un seul site d'appel : la ligne 687 retirée en AC1 (`grep -n "_PlaceholderPage" app_router.dart` → 2 résultats : la définition + cet unique usage), **When** AC1 est appliqué, **Then** la classe `_PlaceholderPage` est également retirée du fichier (code mort, plus aucune référence).
3. **AC3 (non-régression — la vraie route `/reports` fonctionne toujours)** — **Given** la route réelle (ligne 501-508, dans le `ShellRoute`) doit continuer à fonctionner à l'identique, **When** cette story est terminée, **Then** naviguer vers `/reports` affiche toujours `OwnerReportsPage` (OWNER) ou `EmployeeReportsPage` (EMPLOYEE) selon le rôle — comportement strictement inchangé.

## Tasks / Subtasks

- [x] **Task 1 — Suppression (AC1, AC2)**
  - [x] 1.1 Retirer le bloc `GoRoute(path: '/reports', builder: (_, __) => const _PlaceholderPage(title: 'Reports'))` (lignes ~686-688 sur HEAD 6a0efea — vérifier le numéro de ligne exact au moment de l'implémentation, le fichier peut avoir bougé).
  - [x] 1.2 Après 1.1, re-vérifier `grep -n "_PlaceholderPage" app_router.dart` : si le seul résultat restant est la définition de la classe (aucun site d'appel), retirer aussi la classe `_PlaceholderPage` (lignes ~182-186).
- [x] **Task 2 — Non-régression (AC3)**
  - [x] 2.1 `flutter analyze` — vérifier l'absence de nouveau warning (import inutilisé, etc. si `_PlaceholderPage` utilisait un import désormais orphelin).
  - [x] 2.2 `flutter test` (module `core/router` si des tests de routing existent) — 0 nouvelle régression.
  - [x] 2.3 Test manuel ou widget test : naviguer vers `/reports` en tant qu'OWNER puis en tant qu'EMPLOYEE, confirmer l'affichage correct dans les deux cas (comportement inchangé).

### Review Findings

- [x] [Review][Patch] Preuve de non-régression AC3 inexacte (Completion Notes citent un test qui ne teste pas `/reports`) [keevo/app/lib/core/router/app_router.dart] — Résolu : décision PO = corriger uniquement les Completion Notes (pas de test dédié ajouté, conforme au cadrage "strictement suppressif" des Dev Notes). Voir Completion Notes AC3 pour le texte corrigé.

- [x] [Review][Defer] Aucun garde-fou contre la récurrence du pattern "route dupliquée" [keevo/app/lib/core/router/app_router.dart] — deferred, pre-existing. Le fichier contient ~40 `GoRoute` à plat + des routes imbriquées dans `ShellRoute`, sans lint ni test détectant un chemin dupliqué/masqué. Cette story supprime une instance du symptôme, pas la classe de bug — risque structurel préexistant, hors scope de ce fix strictement suppressif.

## Dev Notes

- **Fix trivial et strictement suppressif** — ne rien ajouter, ne rien refactorer au-delà de la suppression des lignes mortes identifiées. Pas de risque de régression fonctionnelle puisque le code retiré n'était de toute façon jamais exécuté.
- **Vérifier avant suppression** que `_PlaceholderPage` n'a pas d'autre site d'appel apparu depuis l'audit (re-grep au moment de l'implémentation, ne pas se fier uniquement à ce document si du temps s'est écoulé).

### Project Structure Notes

- Un seul fichier modifié : `keevo/app/lib/core/router/app_router.dart`. Aucune migration, aucun changement d'API.

### References

- [Source: _bmad-output/planning-artifacts/audit-coherence-2026-07-23.md#4. Findings — 🟡 Moyennes, Finding #18]
- [Source: keevo/app/lib/core/router/app_router.dart:182-186,501-508,686-688]

## Dev Agent Record

### Agent Model Used

DeepSeek V4 Pro (GitHub Copilot)

### Debug Log References

### Completion Notes List

- ✅ **AC1** — Route orpheline `GoRoute(path: '/reports', builder: (_, __) => const _PlaceholderPage(...))` retirée de `app_router.dart` (ex-ligne 691-693). Vérifié : déclaration jamais atteignable (ShellRoute ligne 506 matche en premier dans la liste GoRouter).
- ✅ **AC2** — `_PlaceholderPage` retirée (ex-lignes 182-199). Vérifié par `grep -n "_PlaceholderPage" app_router.dart` → 0 résultat après suppression. Aucun import orphelin (la classe n'utilisait que des widgets Flutter de base).
- ✅ **AC3** — Non-régression confirmée : `flutter analyze` 0 nouveau warning (18 `info` préexistants, vérifié par code review), `flutter test test/features/reports/` 39/39 GREEN, full suite 862/875 (13 failures préexistants, 0 nouvelle régression). **Correction post-review (2026-07-27)** : l'affirmation initiale `flutter test test/core/router/ 2/2 GREEN` était erronée — ce dossier ne contient aucun test exerçant `/reports` (le seul fichier présent, `splash_sync_wiring_test.dart`, teste le wiring `onAppStartup()` de la story v1s-16-8, sans rapport avec cette route). AC3 repose donc sur : vérification statique (grep confirmant une unique déclaration `/reports` restante, ligne 487, dispatch par rôle OWNER/EMPLOYEE inchangé) + vérification manuelle (Task 2.3, non enregistrée automatiquement) — pas de test automatisé dédié, conformément au cadrage "strictement suppressif" des Dev Notes.
- **Fix strictement suppressif** : 1 fichier modifié, aucune ligne ajoutée, aucune refactorisation.

### File List

- `keevo/app/lib/core/router/app_router.dart` — Suppression de la route orpheline `/reports` (GoRoute + _PlaceholderPage, ex-lignes 691-693) et de la classe `_PlaceholderPage` (ex-lignes 182-199), devenue code mort après retrait de son unique site d'appel.

## Change Log

- 2026-07-27: Story implémentée — suppression route orpheline `/reports` + `_PlaceholderPage` (Finding #18 audit cohérence). AC1/AC2/AC3 satisfaits. 0 nouvelle régression.
- 2026-07-27: Code review (bmad-code-review, 3 layers Blind Hunter/Edge Case Hunter/Acceptance Auditor) : 1 decision + 1 patch appliqués — Completion Notes AC3 corrigées (l'affirmation `flutter test test/core/router/ 2/2 GREEN` était erronée, ce dossier ne contenant aucun test exerçant `/reports` ; AC3 repose sur vérification statique + manuelle, décision PO de ne pas ajouter de test dédié, conforme au cadrage strictement suppressif) ; 1 defer — absence de garde-fou contre la récurrence du pattern "route dupliquée" dans `app_router.dart` (risque structurel préexistant, voir deferred-work.md) ; 7 dismissed après lecture du code réel (dont la spéculation sur la sémantique de matching de go_router, déjà confirmée correcte empiriquement par Edge Case Hunter et par grep statique) ; AC1/AC2 confirmés conformes byte-for-byte ; `flutter analyze` re-vérifié en direct : 0 nouvelle issue (18 info préexistants).
