---
title: 'Fix pending-sales ghost badge and cross-device sales scoping'
type: 'bugfix'
created: '2026-04-28'
status: 'done'
baseline_commit: '8a3f06e0aaebfe8bbd884ee05ece1de010d46d9a'
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Après validation d'une vente par un employé, le badge "ventes en attente" sur le compte owner affiche un fantôme (+1 résidu) car `pendingSalesProvider` / `pendingSalesCountProvider` ne sont pas invalidés après la synchronisation. De plus, le rapport "Jour" de l'employé utilise `todaySummaryProvider` sans filtre `employeeId` et montrerait toutes les ventes du magasin une fois le problème de sync corrigé — alors que l'employé doit voir uniquement ses propres métriques.

**Approach:** (1) Ajouter `pendingSalesProvider` et `pendingSalesCountProvider` à `_invalidateAllProviders()` dans le `SyncTriggerNotifier`, afin que ces providers soient rafraîchis après chaque cycle de sync. (2) Changer la signature de `todaySummaryProvider` pour accepter `({String storeId, String? employeeId})` et mettre à jour les sites d'appel pour passer l'`employeeId` de l'employé connecté (null pour l'owner → toutes les ventes du magasin).

## Boundaries & Constraints

**Always:**
- Architecture offline-first inchangée — aucune requête réseau directe dans les providers
- `CloseDayUseCase.execute()` garde `computeTodaySummary(storeId, null)` — la clôture enregistre TOUJOURS les données du magasin entier (toutes les ventes, tous les employés)
- La bottom sheet de clôture (`DaySummaryBottomSheet`) reçoit toujours `employeeId: null` → elle affiche les totaux du magasin

**Ask First:**
- Si la décision est prise que les employés ne peuvent PAS déclencher la clôture de journée (bouton à masquer) — HALT avant de toucher la logique de fermeture

**Never:**
- Modifier `CloseDayUseCase` pour filtrer par employé
- Créer de nouvelles datasources ou services


## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Owner voit badge correct après sync | Employé valide sa vente sur son appareil; owner fait un sync | Badge `pendingSalesCountProvider` sur l'appareil owner = 0 (ou le nombre réel en attente, sans fantôme) | — |
| Rapport Jour employé scopé | Employé connecté, `currentUserRoleProvider` = 'EMPLOYEE' | `todaySummaryProvider` appelé avec `employeeId = userId` → métriques de l'employé uniquement | Si userId null (chargement) : employeeId = null temporairement (affiche store-wide, sans crash) |
| Rapport Jour owner toutes ventes | Owner connecté, rôle = 'OWNER' | `todaySummaryProvider` appelé avec `employeeId = null` → toutes les ventes du magasin | — |
| Clôture journée (owner ou employé) | Taper "Clôturer" | `DaySummaryBottomSheet` utilise `employeeId: null` → résumé complet du magasin | — |
| Invalidation famille après vente | `record_sale_notifier` ou `product_card` invalide le provider | `ref.invalidate(todaySummaryProvider)` (sans clé) invalide toutes les instances de la famille | — |

</frozen-after-approval>

## Code Map

- `lib/core/sync/sync_trigger_notifier.dart` -- `_invalidateAllProviders()` : liste à compléter avec `pendingSalesProvider` + `pendingSalesCountProvider`
- `lib/features/pos/presentation/provider/pos_providers.dart` -- définit `pendingSalesProvider` et `pendingSalesCountProvider` (famille `String?`)
- `lib/features/pos/presentation/provider/day_closure_providers.dart` -- `todaySummaryProvider` : signature à changer en `({String storeId, String? employeeId})`
- `lib/features/reports/presentation/page/reports_page.dart` -- consommateur de `todaySummaryProvider` + lit `currentUserRoleProvider` (L118) et `currentUserIdProvider` (L34)
- `lib/features/pos/presentation/widget/day_summary_bottom_sheet.dart` -- consommateur de `todaySummaryProvider(widget.storeId)` ; recevoir un `employeeId` optionnel
- `lib/features/catalog/presentation/widget/product_card.dart` -- L645 : invalide `todaySummaryProvider(storeId)` → changer en famille entière
- `test/features/pos/presentation/widget/day_close_button_test.dart` -- override `todaySummaryProvider(testStoreId)` → à adapter
- `test/features/pos/presentation/widget/day_summary_bottom_sheet_test.dart` -- L29, L54 : même adaptation

## Tasks & Acceptance

**Execution:**

- [ ] `lib/core/sync/sync_trigger_notifier.dart` -- Ajouter l'import de `pos_providers.dart` et ajouter dans `_invalidateAllProviders()` : `ref.invalidate(pendingSalesProvider)` + `ref.invalidate(pendingSalesCountProvider)` (section `// ── Sales & Pending ─────────────────────────`) -- Les deux providers ne sont pas dans la liste actuelle → badge fantôme après sync

- [ ] `lib/features/pos/presentation/provider/day_closure_providers.dart` -- Changer la signature de `todaySummaryProvider` : `FutureProvider.family<DayClosureSummary, ({String storeId, String? employeeId})>` ; corp : `repository.computeTodaySummary(args.storeId, args.employeeId)` -- Permet le scope par employé sans dupliquer le provider

- [ ] `lib/features/reports/presentation/page/reports_page.dart` -- Remplacer l'appel `todaySummaryProvider(storeId)` par `todaySummaryProvider((storeId: storeId, employeeId: isEmployee ? ref.watch(currentUserIdProvider).valueOrNull : null))` -- L'employé voit ses propres ventes ; l'owner voit toutes les ventes

- [ ] `lib/features/pos/presentation/widget/day_summary_bottom_sheet.dart` -- Ajouter `final String? employeeId` au constructor + à la méthode `show()` ; remplacer `todaySummaryProvider(widget.storeId)` par `todaySummaryProvider((storeId: widget.storeId, employeeId: widget.employeeId))` -- Rend la bottom sheet cohérente avec le provider renommé

- [ ] `lib/features/reports/presentation/page/reports_page.dart` (`_showDayCloseSummary`) -- Passer `employeeId: null` à `DaySummaryBottomSheet.show(...)` — la clôture affiche toujours les totaux du magasin entier

- [ ] `lib/features/catalog/presentation/widget/product_card.dart` -- L645 : remplacer `container.invalidate(todaySummaryProvider(storeId))` par `container.invalidate(todaySummaryProvider)` (toute la famille) -- Après changement de signature, l'ancienne clé `String` ne compilerait plus ; invalider toute la famille est plus correct

- [ ] `test/features/pos/presentation/widget/day_close_button_test.dart` -- Adapter l'override `todaySummaryProvider(testStoreId)` en `todaySummaryProvider((storeId: testStoreId, employeeId: null))` -- Compilation après changement de signature

- [ ] `test/features/pos/presentation/widget/day_summary_bottom_sheet_test.dart` -- Adapter les overrides aux lignes ~L29 et ~L54 : même traitement que ci-dessus + ajouter le param `employeeId: null` à `DaySummaryBottomSheet.show(...)` dans les tests -- Compilation après changement de signature

**Acceptance Criteria:**

- Given l'employé valide une vente en ligne et l'owner effectue un sync cycle (périodique 5 min ou déclenché par une écriture), when `pendingSalesCountProvider(storeId)` est relu, then le compte ne comprend plus la vente de l'employé (correctement COMPLETED en DB)
- Given l'owner crée une nouvelle vente brouillon après le sync, when le badge `_PendingSalesBanner` s'affiche, then il affiche exactement 1 (uniquement la nouvelle vente de l'owner)
- Given un EMPLOYEE est connecté et navigue vers Rapports → Jour, when le résumé s'affiche, then `totalSales` / `totalRevenue` reflètent uniquement ses propres ventes COMPLETED
- Given un OWNER est connecté et navigue vers Rapports → Jour, when le résumé s'affiche, then `totalSales` / `totalRevenue` incluent les ventes de tous les employés et la sienne
- Given n'importe quel utilisateur ouvre la `DaySummaryBottomSheet`, when la feuille s'affiche, then les totaux sont ceux du magasin entier (employeeId = null)
- Given `flutter analyze --no-pub`, then 0 erreurs en prod (tests exclus)

## Design Notes

**Invalidation famille entière** : `ref.invalidate(todaySummaryProvider)` (sans clé) invalide toutes les instances Riverpod de la famille. C'est le comportement voulu dans `record_sale_notifier.dart` (déjà correct) et `product_card.dart` (à corriger). `sync_trigger_notifier.dart` utilise déjà ce pattern pour `todaySummaryProvider`.

**Record type Dart 3** : `({String storeId, String? employeeId})` est un record anonyme. L'égalité structurelle garantit que Riverpod traite `(storeId: 'X', employeeId: null)` et `(storeId: 'X', employeeId: 'Y')` comme deux instances distinctes (cache séparé). Pas besoin d'une `Equatable` class.

**userId temporairement null** : `currentUserIdProvider` est un `FutureProvider<String?>`. Pendant le chargement, `.valueOrNull` retourne `null` → `employeeId = null` → summary store-wide affichée brièvement. Ce flash est acceptable; aucun crash.

## Verification

**Commands:**
- `flutter analyze --no-pub 2>&1 | grep -E "^  error" | grep -v "test/"` -- expected: no output (0 erreurs prod)
- `flutter test test/features/pos/presentation/widget/ --no-pub 2>&1 | tail -5` -- expected: `All tests passed` (les 2 fichiers de test modifiés)

## Suggested Review Order

**Provider contract — la décision de conception centrale**

- Nouvelle clé record `({String storeId, String? employeeId})` — dicte tous les call sites.
  [`day_closure_providers.dart:125`](../../keevo/app/lib/features/pos/presentation/provider/day_closure_providers.dart#L125)

**Fix badge fantôme — invalidation après sync**

- Deux lignes + import corrigent le stale count après chaque cycle de sync.
  [`sync_trigger_notifier.dart:256`](../../keevo/app/lib/core/sync/sync_trigger_notifier.dart#L256)

**Scoping des ventes par rôle — Reports page**

- Ternaire `isEmployee`: EMPLOYEE → userId, OWNER → null; passe la clé correcte.
  [`reports_page.dart:31`](../../keevo/app/lib/features/reports/presentation/page/reports_page.dart#L31)

- Clôture always `employeeId: null` — invariant store-wide préservé.
  [`reports_page.dart:585`](../../keevo/app/lib/features/reports/presentation/page/reports_page.dart#L585)

**Propagation du paramètre — Bottom sheet**

- Nouveau champ `employeeId` + constructeur; passe au provider watch.
  [`day_summary_bottom_sheet.dart:17`](../../keevo/app/lib/features/pos/presentation/widget/day_summary_bottom_sheet.dart#L17)

- Provider watch mis à jour avec la clé record.
  [`day_summary_bottom_sheet.dart:136`](../../keevo/app/lib/features/pos/presentation/widget/day_summary_bottom_sheet.dart#L136)

**Invalidation famille entière — product_card**

- Clé `String` supprimée; whole-family invalidation après changement de signature.
  [`product_card.dart:645`](../../keevo/app/lib/features/catalog/presentation/widget/product_card.dart#L645)

**Tests — adaptation compilation**

- Override adapté à la clé record `(storeId, employeeId: null)`.
  [`day_close_button_test.dart:113`](../../keevo/app/test/features/pos/presentation/widget/day_close_button_test.dart#L113)

- Idem, deux overrides adaptés.
  [`day_summary_bottom_sheet_test.dart:29`](../../keevo/app/test/features/pos/presentation/widget/day_summary_bottom_sheet_test.dart#L29)
