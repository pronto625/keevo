---
baseline_commit: 8b6c3889c4c5ae3b7727f9bab3ad60243eef062b
---
# Story 13.6: Statut ventes brouillon (PENDING_VALIDATION) — F-CRIT-2

Status: done

<!-- V1-stabilization track — tag A (patch V1 urgent, release-blocker).
     Branche : v1-stabilization.
     Refonte absorption : « re-appliquer Flutter ».
     100% Flutter, aucun DDL, aucun changement backend.
     Validation optionnelle : lancer validate-create-story avant dev-story. -->

## Story

**As a** proprietor (Simon, OWNER),
**I want** the POS checkout button to visually flag a cart containing draft (not-yet-catalogued) products as "🔶 Vente brouillon",
**so that** Loïc (EMPLOYEE) gets a clear visual cue that he's about to record a sale with items whose catalogue entry was just created on the fly — even though, per the current (confirmed) design, the sale still completes immediately once he's entered the initial stock quantity.

## Contexte V1-stabilization

- **Source backlog :** `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md:272-284` → Story 13.6 (Refs F-CRIT-2, Story 4.3 AC1-4).
- **Finding audit — F-CRIT-2 :** *"`record_sale_notifier.dart:96-111` hardcodes `status:'COMPLETED'`. When cart has DRAFT items, sale should be submitted as `PENDING_VALIDATION`, stock NOT decremented, `CREATE_SALE`(pending) queued. POS button shows '🔶 Vente brouillon' for draft carts; non-draft carts keep COMPLETED."*
- **Priorité :** Sprint 1 bloquant V1 (`epics-remediation-audit.md:46`), tag **A**.
- **Index track :** `sprint-status.yaml` — `v1s-13-6-draft-sale-pending-status` · Position 6/7 dans Epic 13 (13.1→13.5 done — aucune dépendance technique directe).

## ⚠️ Décision produit confirmée (2026-07-22) — LIRE AVANT D'IMPLÉMENTER

**Le texte AC de `epics-remediation-audit.md` décrit littéralement le design ORIGINAL de la Story 4.3 (`status: PENDING_VALIDATION`, pas de décrément de stock, validation différée par le OWNER). Ce design a été intentionnellement REMPLACÉ le 2026-05-03 (commit `30bdd07`, "fix: categories loading, draft sale flow, layout & controller lifecycle bugs") par un flux différent, qui est CELUI QUI DOIT ÊTRE CONSERVÉ.**

Le PO a tranché explicitement (2026-07-22, en amont de cette story) : **conserver le flux inline actuel**, ne PAS restaurer `PENDING_VALIDATION`. Voir "Décisions prises" ci-dessous pour l'analyse complète. Cette story est donc volontairement étroite : elle **confirme/documente** le comportement actuel plutôt que de le changer, ajoute la couverture de test manquante, ajoute l'indicateur visuel bouton manquant, et **acte le code mort** (pages/services de validation OWNER) comme un defer de nettoyage — **ne PAS le supprimer dans cette story**.

## Ce qui existe déjà (ne pas réinventer)

Le flux inline (2026-05-03, actuel, à conserver) fonctionne ainsi — **déjà implémenté et fonctionnel, ne pas retoucher sa logique** :

1. `CartBottomSheet._handleEncaisser()` (`cart_bottom_sheet.dart:66-86`) : quand `ref.read(cartProvider).any((i) => i.isDraft)` est vrai, affiche un `_InitialStockDialog` par produit DRAFT du panier (bloquant, annulable), collecte les quantités saisies dans un `Map<String, int>`, les écrit dans `draftInitialStocksProvider` (StateProvider, `cart_provider.dart:81-82`), puis appelle `widget.onEncaisser()` qui navigue vers `/pos/checkout` (`pos_page.dart:120-131`).
2. `CheckoutPage._submit()` (`checkout_page.dart:330-358`) appelle `RecordSaleNotifier.submit()`.
3. `RecordSaleNotifier.submit()` (`record_sale_notifier.dart:46-127`) :
   - lit `draftItems = cart.where((c) => c.isDraft)` et `initialStockEntries` depuis `draftInitialStocksProvider` ;
   - si `draftItems.isNotEmpty` : appelle `LocalSaleDataSource.applyInitialStockEntries(...)` (crédite le stock local pour chaque produit brouillon) puis `LocalProductDataSource.promoteToActive(productId)` pour chacun (passe le produit `DRAFT → ACTIVE` localement) ;
   - vérifie ensuite le stock disponible pour **tous** les items (les produits ex-brouillon ont désormais du stock réel) ;
   - construit la vente via `RecordSaleUseCase.execute(..., status: 'COMPLETED', originalDraftProductIds: ..., initialStockEntries: ...)` — **ce `'COMPLETED'` en dur est CORRECT dans le design actuel** : au moment où `recordSale()` est appelée, le produit n'est plus vraiment "en brouillon" (l'employé vient de fournir un stock réel et le produit est promu ACTIVE) — il n'y a donc plus rien à faire valider par le OWNER.
4. `LocalSaleDataSource.insertAll()` (`local_sale_datasource.dart:24-96`) décrémente bien le stock (ligne 60 : `if (sale.status != 'PENDING_VALIDATION')`) — comportement correct et inchangé, cette story ne touche QUE `PENDING_VALIDATION` à travers son absence d'usage réel.
5. `SaleRepositoryImpl._buildPayload()` (`sale_repository_impl.dart:441-462`) envoie déjà `'requestedStatus': sale.status` au backend — la plomberie de transport pour un futur statut différent de `COMPLETED` existe déjà et n'a besoin d'aucun changement.
6. `RemoteSaleDataSource`/backend `SaleController`/`RecordSaleService` acceptent déjà `PENDING_VALIDATION` en entrée (Story 4.3, jamais retiré) — **mais plus aucun chemin du client Flutter n'envoie ce statut**. C'est un choix délibéré confirmé par cette story, pas un oubli.

**Ce qui MANQUE réellement (le seul vrai gap, objet de cette story) :**

- Aucun test ne couvre le flux inline actuel (`applyInitialStockEntries` + `promoteToActive` + statut `COMPLETED` final) — `record_sale_notifier_test.dart` n'a aucun cas avec un item `isDraft: true`.
- Le bouton de soumission visible par l'employé (`CheckoutPage`, le "POS button" de l'AC — **PAS** `CartPill`/`CartBottomSheet` dont le bouton "Encaisser" ne fait qu'ouvrir le dialogue de stock initial puis naviguer, voir ci-dessus) affiche toujours "Valider la vente" en dur (`checkout_page.dart:308-315`), même quand le panier contient un produit brouillon — aucun indicateur visuel "🔶 Vente brouillon" nulle part dans le flux actuel (`CartPill`/`CartBottomSheet` non plus — leurs tests, `cart_pill_test.dart`/`cart_bottom_sheet_test.dart`, ne testent aucune variante brouillon ; **hors scope de cette story**, voir Décision D2).

**Code mort confirmé, à NE PAS supprimer dans cette story (voir Décision D3) :** `PendingSalesPage`, `PendingSaleDetailPage`, `ValidateSaleService` (le cas OWNER "valider/annuler une vente en attente"), `SaleValidationCascadeService`, le badge `pendingSalesCountProvider`, la variante ambre de `SaleSuccessPage` (`saleStatus == 'PENDING_VALIDATION'`) — tous fonctionnels si un jour une vente `PENDING_VALIDATION` existait, mais **plus jamais atteints** depuis le 2026-05-03 car aucun code client ne produit ce statut.

## Acceptance Criteria

### AC1 — Le bouton de checkout affiche "🔶 Vente brouillon" pour un panier contenant un produit brouillon

**Given** `CheckoutPage` est affichée avec un panier (`cartProvider`) contenant au moins un `CartItem` avec `isDraft == true`,
**When** la page est construite,
**Then** le texte du bouton de soumission (actuellement "Valider la vente" en dur, `checkout_page.dart:308-315`) affiche **"🔶 Vente brouillon"** au lieu de "Valider la vente".
**And** ce texte suit l'état de chargement existant (le spinner `RecordSaleLoading` reste inchangé et prioritaire sur le texte, comme aujourd'hui).

### AC2 — Le bouton garde "Valider la vente" pour un panier sans produit brouillon (non-régression)

**Given** `CheckoutPage` est affichée avec un panier ne contenant que des `CartItem` avec `isDraft == false` (défaut `productStatus: 'ACTIVE'`),
**When** la page est construite,
**Then** le texte du bouton reste **"Valider la vente"** (comportement actuel inchangé).

### AC3 — Couverture de test du flux inline actuel (draft → stock initial → promotion → COMPLETED)

**Given** `RecordSaleNotifier.submit()` est appelée avec un panier contenant un `CartItem` `isDraft: true` et `draftInitialStocksProvider` pré-rempli avec la quantité correspondante,
**When** la soumission réussit,
**Then** un test vérifie que :
- `LocalSaleDataSource.applyInitialStockEntries(initialStockEntries, storeId, employeeId)` est appelé avec la map exacte issue de `draftInitialStocksProvider` ;
- `LocalProductDataSource.promoteToActive(productId)` est appelé pour **chaque** produit brouillon du panier ;
- la `Sale` transmise à `SaleRepository.recordSale(...)` porte `status == 'COMPLETED'` et `originalDraftProductIds` contient l'id du produit brouillon ;
- `draftInitialStocksProvider` est vidé (`{}`) après soumission (comportement déjà en place, `record_sale_notifier.dart:82`, à couvrir par régression).

### AC4 — Non-régression : panier sans brouillon ne déclenche ni `applyInitialStockEntries` ni `promoteToActive`

**Given** un panier sans item brouillon,
**When** `submit()` est appelé,
**Then** un test vérifie que `applyInitialStockEntries`/`promoteToActive` ne sont **jamais** appelés (`verifyNever`) — garde-fou contre une régression future qui les invoquerait inconditionnellement.

## Décisions prises (documentées pour le code review — PO peut trancher différemment)

### D1 — Conserver le flux inline (2026-05-03), NE PAS restaurer `PENDING_VALIDATION`

**Constat :** le texte AC de `epics-remediation-audit.md:272-284` décrit le design ORIGINAL de la Story 4.3 (voir `4-3-vente-brouillon-produits-draft-validation-admin.md` AC3 : *"stock levels are NOT decremented... a `SalePendingValidationEvent` is emitted"*), qui a été intentionnellement remplacé par le commit `30bdd07` (2026-05-03) — l'employé saisit le stock initial inline à l'encaissement, le produit est promu ACTIVE immédiatement, la vente se complète sans intervention du OWNER.
**Décision (confirmée par le PO, 2026-07-22) :** garder le flux inline actuel. L'audit F-CRIT-2 est stale par rapport à ce pivot produit — il décrit fidèlement le code (`status:'COMPLETED'` en dur) mais pas un bug : dans le design actuel, marquer la vente `PENDING_VALIDATION` serait FAUX (le stock est déjà connu et le produit déjà promu au moment de `recordSale()`).
**Conséquence :** cette story ne change **aucune logique métier** de `record_sale_notifier.dart`/`sale_repository_impl.dart`/`local_sale_datasource.dart`. Elle ajoute uniquement : l'indicateur visuel bouton (AC1/AC2) + la couverture de test manquante (AC3/AC4).
**Alternative rejetée :** restaurer littéralement `PENDING_VALIDATION` (option "Restore PENDING_VALIDATION" proposée au PO) — rejetée, aurait nécessité de retirer le dialogue de stock initial + la promotion auto, cassé le flux actuel testé en production sans bénéfice business identifié.

### D2 — Le "POS button" de l'AC = le bouton de `CheckoutPage`, PAS celui de `CartPill`/`CartBottomSheet`

**Constat :** `CartPill`/`CartBottomSheet` affichent un bouton "Encaisser" générique qui ne fait qu'ouvrir/collecter le stock initial puis **naviguer** vers `/pos/checkout` — ce n'est plus le bouton qui déclenche réellement `RecordSaleNotifier.submit()` depuis l'introduction de `CheckoutPage` (commit `416fb78`, 2026-05-15, postérieur au design brouillon). Le bouton qui appelle réellement `submit()` et affiche l'état de soumission (`RecordSaleLoading`) est celui de `CheckoutPage` (`checkout_page.dart:260-321`).
**Décision :** appliquer l'indicateur "🔶 Vente brouillon" uniquement sur le bouton `CheckoutPage` (AC1/AC2). Ne PAS toucher `CartPill`/`CartBottomSheet` — leur label "Encaisser" reste générique (il s'agit d'une étape intermédiaire, pas de la confirmation finale de la vente).
**Alternative rejetée :** appliquer aussi le badge sur `CartPill` (mirror de l'ancien Task 17 de la Story 4.3, jamais réellement livré) — rejetée pour cette story : `cart_pill_test.dart` n'a aucun test de ce type aujourd'hui et l'ajouter gonflerait le scope sans être demandé explicitement par le texte AC F-CRIT-2 ("POS button" au singulier). Candidat d'amélioration UX si le PO le demande en review.

### D3 — Ne PAS supprimer le code mort de validation OWNER (`PendingSalesPage` et consorts) dans cette story

**Constat :** depuis le 2026-05-03, plus aucun chemin client ne produit de vente `PENDING_VALIDATION` — `PendingSalesPage`, `PendingSaleDetailPage`, `ValidateSaleService` (branche OWNER manuel), `SaleValidationCascadeService`, `pendingSalesCountProvider`, la variante ambre de `SaleSuccessPage`, sont tous fonctionnels mais **plus jamais exercés** en pratique par ce client.
**Décision :** ne pas supprimer ce code dans cette story (scope-cut — le retirer proprement toucherait potentiellement le backend `PendingSaleController`/`ValidateSaleService` qui pourrait encore servir un futur besoin d'administration manuelle, et gonflerait considérablement le diff d'une story censée être un simple ajout d'indicateur visuel + tests). Ajouter une entrée `deferred-work.md` documentant ce code mort comme candidat pour Epic 15 (Story 15.5, "Réconcilier deferred-work + code mort"), avec deux options pour le PO : (a) supprimer entièrement cette UI/ces services OWNER de validation, ou (b) les repositionner comme un outil de secours manuel (ex. si un import CSV ou une future API crée un jour une vente `PENDING_VALIDATION`).
**Alternative rejetée :** supprimer immédiatement — rejetée, hors du scope étroit F-CRIT-2 (bouton + tests) et risque de régression sur du code fonctionnel non directement lié à ce finding.

## Tasks / Subtasks

- [x] **Task 1 — Tests RED : `RecordSaleNotifier` flux inline draft (AC3-AC4)** [flutter/test]
  - [x] 1.1 `test/features/pos/presentation/provider/record_sale_notifier_test.dart` — ajouter un mock `MockLocalProductDataSource extends Mock implements LocalProductDataSource` (import `package:keevo/features/catalog/data/datasource/local_product_datasource.dart`) et l'ajouter aux overrides de `createContainer()` via `localProductDataSourceProvider.overrideWithValue(mockProductDs)` (import `package:keevo/features/catalog/presentation/provider/product_provider.dart`).
  - [x] 1.2 Nouveau test `submit_withDraftItem_appliesInitialStockAndPromotesProduct()` : panier avec 1 `CartItem(productStatus: 'DRAFT')`, pré-remplir `draftInitialStocksProvider` via `container.read(draftInitialStocksProvider.notifier).state = {'p1': 5}` avant `submit()`, stub `mockLocalDs.applyInitialStockEntries(any(), any(), any())` et `mockProductDs.promoteToActive(any())` à `(_) async {}`, stub `getAvailableStock` → 5. Vérifier : `verify(() => mockLocalDs.applyInitialStockEntries({'p1': 5}, 'store-1', 'emp-1')).called(1)`, `verify(() => mockProductDs.promoteToActive('p1')).called(1)`.
  - [x] 1.3 Capturer la `Sale` passée à `mockRepo.recordSale(...)` (`verify(() => mockRepo.recordSale(captureAny())).captured.single as Sale`) et asserter `sale.status == 'COMPLETED'` et `sale.originalDraftProductIds == ['p1']`.
  - [x] 1.4 Vérifier `container.read(draftInitialStocksProvider)` est `{}` après succès.
  - [x] 1.5 Nouveau test `submit_withoutDraftItems_neverAppliesInitialStockOrPromotes()` : panier 100% ACTIVE (défaut), `verifyNever(() => mockLocalDs.applyInitialStockEntries(any(), any(), any()))`, `verifyNever(() => mockProductDs.promoteToActive(any()))` — garde-fou de non-régression explicite (AC4).

- [x] **Task 2 — Tests RED : bouton `CheckoutPage` (AC1-AC2)** [flutter/test]
  - [x] 2.1 `test/features/pos/presentation/page/checkout_page_test.dart` — étendre `_item()` avec un paramètre `String productStatus = 'ACTIVE'` propagé au `CartItem`.
  - [x] 2.2 Nouveau test `'shows Vente brouillon label when cart has a draft item'` : `_buildApp(items: [_item(productStatus: 'DRAFT')])`, `expect(find.text('🔶 Vente brouillon'), findsOneWidget); expect(find.text('Valider la vente'), findsNothing);`.
  - [x] 2.3 Nouveau test `'keeps Valider la vente label when cart has no draft item'` : `_buildApp(items: [_item()])` (ACTIVE par défaut), `expect(find.text('Valider la vente'), findsOneWidget); expect(find.text('🔶 Vente brouillon'), findsNothing);` — régression explicite des tests existants qui cherchent déjà `'Valider la vente'` (lignes 102, 120).

- [x] **Task 3 — Implémenter l'indicateur bouton (AC1-AC2)** [flutter]
  - [x] 3.1 `checkout_page.dart` build() : après `final cart = ref.watch(cartProvider);` (ligne 52), ajouter `final hasDraftItems = cart.any((c) => c.isDraft);`.
  - [x] 3.2 Remplacer le `Text('Valider la vente', ...)` littéral (lignes 308-315) par `Text(hasDraftItems ? '🔶 Vente brouillon' : 'Valider la vente', style: ...)` — garder le style existant inchangé (couleur/poids/taille), seul le contenu texte est conditionnel.

- [x] **Task 4 — Documenter le code mort de validation OWNER (Décision D3)** [docs]
  - [x] 4.1 Ajouter une entrée à `_bmad-output/implementation-artifacts/deferred-work.md` (ou `_bmad-output/deferred-work.md`, suivre la convention du fichier le plus récemment utilisé par v1s-13-5) sous un nouveau bloc "Deferred from: code review of v1s-13-6" : documenter que `PendingSalesPage`/`PendingSaleDetailPage`/`ValidateSaleService`/`SaleValidationCascadeService`/`pendingSalesCountProvider`/variante ambre `SaleSuccessPage` sont du code fonctionnel mais inatteignable depuis le 2026-05-03 (aucun chemin client ne produit `PENDING_VALIDATION`), candidat Epic 15 Story 15.5, décision PO à trancher (suppression vs conservation comme outil de secours).

- [x] **Task 5 — Régression complète** [testing]
  - [x] 5.1 `flutter test` → 810 passed, 12 pre-existing failures (none in changed files), 0 NEW regression.
  - [x] 5.2 `flutter analyze` → 756 issues (identical to baseline), 0 new warning/error.

## Dev Notes

### Patterns architecturaux à respecter

- **Ne touche à AUCUNE logique métier** — `record_sale_notifier.dart`, `sale_repository_impl.dart`, `local_sale_datasource.dart`, `cart_bottom_sheet.dart` restent identiques au bit près. Cette story est strictement additive : 1 ligne de condition dans `checkout_page.dart` + tests.
- **Mocktail `captureAny()`** pour inspecter la `Sale` construite par `RecordSaleUseCase.execute()` — pattern déjà utilisé ailleurs dans le repo pour vérifier des objets de valeur passés à un mock (chercher `captureAny()` dans `test/` pour un exemple si besoin).
- **`CartItem.isDraft`/`productStatus`** (`cart_item.dart:14,32`) — champ déjà présent, défaut `'ACTIVE'`, aucun changement de modèle nécessaire.

### Ce qu'il NE FAUT PAS faire

- Ne pas ajouter de branche `status: hasDraftProducts ? 'PENDING_VALIDATION' : 'COMPLETED'` dans `record_sale_notifier.dart` — ce serait revenir sur la Décision D1, et casserait le flux inline actuel (le produit est déjà promu ACTIVE et le stock déjà crédité au moment de l'appel, un statut `PENDING_VALIDATION` à ce stade n'aurait plus de sens et laisserait la vente bloquée indéfiniment puisque plus rien ne la validera jamais — `SaleValidationCascadeService` ne se déclenche que sur promotion DRAFT→ACTIVE, déjà faite avant `recordSale()`).
- Ne pas toucher `CartPill`/`CartBottomSheet` (Décision D2) — hors scope.
- Ne pas supprimer `PendingSalesPage` et consorts (Décision D3) — documenter seulement.

### References

- [Source: `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md:272-284`] Story 13.6 AC originales.
- [Source: `_bmad-output/implementation-artifacts/4-3-vente-brouillon-produits-draft-validation-admin.md`] Design original Story 4.3 (remplacé le 2026-05-03).
- [Source: commit `30bdd07`, 2026-05-03] Redesign du flux brouillon (inline stock + auto-promotion).
- [Source: commit `416fb78`, 2026-05-15] Introduction de `CheckoutPage` (le bouton de soumission réel).

## Dev Agent Record

### Agent Model Used

GitHub Copilot (DeepSeek V4 Pro)

### Debug Log References

N/A (no debugging required — implementation was a single conditional + tests)

### Completion Notes List

**2026-07-22 — Story v1s-13-6 implemented:**

- **AC1-AC2 (bouton indicateur)** : `CheckoutPage` affiche désormais "🔶 Vente brouillon" quand le panier contient ≥1 produit DRAFT, "Valider la vente" sinon. Changement : 1 ligne conditionnelle dans `checkout_page.dart` (ajout `hasDraftItems` + `Text()` conditionnel). Style inchangé.
- **AC3-AC4 (tests flux inline draft)** : 2 nouveaux tests dans `record_sale_notifier_test.dart` couvrent `applyInitialStockEntries` → `promoteToActive` → `status: COMPLETED` → `originalDraftProductIds` → vidage `draftInitialStocksProvider`, plus garde-fou `verifyNever` pour panier sans draft. 6/6 tests POS Notifier passent.
- **Tests CheckoutPage** : 2 nouveaux tests widget (draft → "🔶 Vente brouillon", non-draft → "Valider la vente"). 8/8 tests CheckoutPage passent.
- **Regression** : 810/822 Flutter tests passent (12 pre-existing failures hors scope), 756 flutter analyze issues (baseline inchangée). 0 NEW regression.
- **Deferred-work** : Code mort validation OWNER documenté dans `_bmad-output/implementation-artifacts/deferred-work.md` (D3), candidat Epic 15 Story 15.5.
- **Aucune logique métier modifiée** — `record_sale_notifier.dart`, `sale_repository_impl.dart`, `local_sale_datasource.dart`, `cart_bottom_sheet.dart` inchangés.

### File List

- `keevo/app/lib/features/pos/presentation/page/checkout_page.dart` — modifié (ajout indicateur bouton "🔶 Vente brouillon" conditionnel)
- `keevo/app/test/features/pos/presentation/provider/record_sale_notifier_test.dart` — modifié (2 nouveaux tests AC3-AC4 : draft inline flow + non-régression)
- `keevo/app/test/features/pos/presentation/page/checkout_page_test.dart` — modifié (2 nouveaux tests AC1-AC2 : indicateur bouton draft/non-draft)
- `_bmad-output/implementation-artifacts/deferred-work.md` — modifié (entrée D3 : code mort validation OWNER)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — modifié (v1s-13-6 → in-progress → review)

### Review Findings

Code review `bmad-code-review` (2026-07-22) — 3 layers (Blind Hunter / Edge Case Hunter / Acceptance Auditor).

**Matrice AC :** ✅ AC1 PASS · ✅ AC2 PASS · ✅ AC3 PASS · ✅ AC4 PASS. D1/D2/D3 + "NE FAUT PAS" list respectés.

- [x] [Review][Defer] **Test mixed cart `[DRAFT, ACTIVE]`** [`checkout_page_test.dart`] — deferred, pre-existing coverage gap. `cart.any()` trivialement correct mais un test mixte renforcerait la régression.
- [x] [Review][Defer] **Failure-path draft flow** [`record_sale_notifier_test.dart`] — deferred, hors scope. `applyInitialStockEntries`/`promoteToActive` throw scenarios non couverts.
- [x] [Review][Defer] **Magic strings `'ACTIVE'`/`'DRAFT'`/`'COMPLETED'`** [`*_test.dart`] — deferred, pre-existing. Refactor modèle → enum hors scope.
- [x] [Review][Defer] **`addTearDown(container.dispose)` manquant** [`record_sale_notifier_test.dart:182,235`] — deferred, pre-existing. Pattern inconsistent dans le fichier (3 tests existants ne le font pas non plus).
- [x] [Review][Defer] **Test empty cart rendering** [`checkout_page_test.dart`] — deferred, edge case. Bouton désactivé par d'autres mécanismes.
- [x] [Review][Defer] **Stale `draftInitialStocksProvider` pour produits retirés** [`record_sale_notifier.dart:66-67`] — deferred, pre-existing behavior.
- [x] [Review][Defer] **Sémantique accessibilité emoji 🔶** [`checkout_page.dart:311`] — deferred, cosmétique. `Semantics`/`ExcludeSemantics`.

**Dismissed (14) :** hardcoded FR (convention), SRP test AC3 (délibéré), hasDraftItems recompute (O(n) n<20), deferred-work scope (spec-mandaté), test helper default (pattern), label flicker async (bouton disabled), AC4 verify sale (positif couvert ailleurs), @visibleForTesting (pattern), null cartProvider (faux positif — type non-nullable), case sensitivity (modèle), duplicate productId (merge par id), emoji glyph (Unicode 6.0), list ordering (single element), stale stocks (redondant D6).
