---
baseline_commit: c7a4644
---
# Story 12.6: Resserrer RBAC owner-only + scope store employé — `/sales/pending`, `/day-closures`, `StoreController`, `StockController`/`InventorySessionController` storeId, `StockTransferController` initiate, `ProductController` (décision HF-2 B4)

Status: done

<!-- V1-stabilization track — tag A (patch V1 urgent, release-blocker).
     Branche : v1-stabilization (off `deploy` d766035). Refonte réabsorbe le fix (audit : « Refonte absorption: re-appliquer les @PreAuthorize + scope JWT dans la structure modulaire »).
     Validation optionnelle : lancer `validate-create-story` avant `dev-story`.

     ⚠️ NE PAS CONFONDRE avec l'AUTRE "Story 12.6" du sprint-status.yaml : `12-6-regles-archunit-complementaires`
     (epic refonte "12-1-declarer-modules-spring-modulith", branche `refractoring`, backlog) est une story
     DIFFÉRENTE, sans rapport avec le RBAC. `deferred-work.md` ligne 10 mentionne "Epic 12.2e / Story 12.6"
     pour l'enforcement ArchUnit — c'est aussi l'AUTRE story 12.6, pas celle-ci. Cette story-ci est
     `v1s-12-6-rbac-owner-only-scope-store` (préfixe v1s-, epic V1-stab "Stabilisation V1 — Sécurité & Hardening"). -->

## Story

**As a** Toor (opérateur de la plateforme),
**I want** les EMPLOYEEs restreints à leur boutique assignée et exclus des endpoints de gouvernance réservés à l'OWNER,
**so that** un EMPLOYEE ne puisse ni consulter/valider les ventes en attente d'un autre commerce, ni lire l'historique de clôture, ni créer/gérer des boutiques, ni manipuler le stock ou lancer un inventaire pour une boutique qui n'est pas la sienne, ni initier un transfert inter-boutiques (S6, S7, B-HIGH-9/10, FR36).

## Contexte V1-stabilization

- **Source backlog :** `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md:147-169` → Story 12.6 (Refs S6, S7, B-HIGH-9/10, FR36, AC6/AC9/AC10 de 4.3/4.4).
- **Tracker :** `sprint-status.yaml` clé `v1s-12-6-rbac-owner-only-scope-store` (ligne 330).
- **Index track :** `v1-stabilization-stories.md:20` — « RBAC owner-only + scope store EMPLOYEE · S6/S7/FR36 · + contrepartie mobile UI gating ».
- **Tag :** A — patch V1 maintenant. Refonte : ré-appliquer les mêmes `@PreAuthorize` + scope JWT dans la structure modulaire (pas de cherry-pick direct, la refonte a une arborescence différente).
- **FR36** (`prd.md:375`) : « Un employé est limité aux opérations de la boutique à laquelle il est assigné. »
- Cette story touche **7 controllers backend** + **1 fichier Flutter** (`app_router.dart` + 1 gate FAB). C'est la story la plus large de l'Epic 12 — procéder controller par controller, un commit/vérification à la fois si possible, pour limiter le risque de régression.

## Acceptance Criteria

> Les AC BDD proviennent textuellement de `epics-remediation-audit.md` Story 12.6. Le texte AC est délibérément non numéroté dans l'épic (une seule liste `Given/When/Then` à puces) — je le décompose ci-dessous en AC numérotées 1 à 8 pour la traçabilité TDD, dans l'ordre où elles apparaissent. **Important : deux clauses de l'épic (ProductController + commentaire `app_router.dart`) sont déjà satisfaites sur la branche `v1-stabilization` — voir AC7/AC8 pour le détail et la preuve.**
>
> **🚨 AC1/AC2 portent une DÉCISION PRODUIT NON TRANCHÉE — lire avant de coder.** Voir Dev Notes « Décision D1 — tension HF-2 vs Story 4.3/audit » pour les deux options complètes. **CORRECTIF (vérifié pendant la création de cette story) : `SaleControllerHF2Test` — cité dans `HF-2-....md:170` comme couvrant le RBAC store-scoped EMPLOYEE — N'EXISTE PAS sur cette branche** (`find keevo/backend/src/test -iname "*HF2*"` → aucun résultat ; `git log --all --diff-filter=A --name-only` → jamais committé). Le comportement HF-2 AC5 (`ValidateSaleService.enforceEmployeeStoreScope`, confirmé en lisant le code source) est réel et fonctionne, mais **AUCUN test automatisé ne le protège aujourd'hui**, ni au niveau controller (`PendingSaleControllerTest` = 100% OWNER, 9/9 tests) ni service (`ValidateSaleServiceTest` = aucune mention EMPLOYEE). **Conséquence directe sur la Décision D1 : le choix par défaut de cette story est maintenant INVERSÉ par rapport à une version antérieure de ce document — on PRÉSERVE HF-2 par défaut** (pas de revert vers OWNER-only) car (a) c'est un comportement produit shippé et documenté comme intentionnel, pas un bug, (b) l'absence totale de filet de test signifie qu'un revert serait un **régression silencieuse, indétectable par `mvn test`** — le risque d'un revert non confirmé est donc plus grave que celui de laisser B-HIGH-9 ouvert un cycle de plus. **Ne pas implémenter AC1/AC2 sans avoir lu la Décision D1 en entier.**

1. **AC1 (PendingSaleController — `GET /sales/pending` : préserver HF-2 par défaut, ajouter la couverture de test manquante — DÉCISION D1, voir Dev Notes)** — **Given** `GET /api/v1/sales/pending` est aujourd'hui `@PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")` **avec un scope storeId déjà appliqué pour EMPLOYEE** (`PendingSaleController.java:40-43`, `getPendingSalesByStore()` — implémenté par le hotfix HF-2 AC5, 2026-04-25, `done`, mais **jamais testé** — voir correctif ci-dessus), **When** on applique le comportement **par défaut** de cette story (préserver HF-2, ne PAS revenir au texte littéral de l'epic qui reflète l'AC originale — désormais obsolète — de Story 4.3 : « `GET /api/v1/sales/pending` ... OWNER-only, HTTP 403 for EMPLOYEE » — `epic-4-point-de-vente-pos.md:162`), **Then** l'endpoint **reste** `hasAnyRole('OWNER','EMPLOYEE')` avec le scope storeId existant **inchangé**, et cette story ajoute le test EMPLOYEE positif manquant (`PendingSaleControllerTest` : EMPLOYEE authentifié → `getPendingSalesByStore` appelé avec son storeId → 200). **⚠️ Si le code-review/PO décide plutôt de revenir à OWNER-only (option alternative), voir Décision D1 pour la procédure — mais ce serait un changement produit délibéré, pas l'exécution mécanique de cette story.**
2. **AC2 (PendingSaleController — `POST /sales/{id}/validate` : préserver HF-2 par défaut, ajouter la couverture de test manquante — DÉCISION D1) + `cancelSale` OWNER-only (non ambigu, indépendant de D1)** — **Given** `POST /api/v1/sales/{id}/validate` porte le même `hasAnyRole` + scope storeId HF-2 (`ValidateSaleService.enforceEmployeeStoreScope`, `ValidateSaleService.java:54,123,162-167`, non testé), **When** on applique le comportement par défaut (préserver HF-2, même réserve qu'AC1), **Then** `validateSale` reste `hasAnyRole('OWNER','EMPLOYEE')` inchangé, et cette story ajoute : un test positif (EMPLOYEE, `assignedStoreId == sale.storeId` → succès) et un test négatif (EMPLOYEE, `assignedStoreId != sale.storeId` → `DomainException(FORBIDDEN)`, `verify(saleRepository, never()).updateStatus(...)`) dans `ValidateSaleServiceTest.java`. **Distinct et NON ambigu, indépendant de la Décision D1 : `POST /sales/{id}/cancel` (ligne 70-80) doit devenir `hasRole('OWNER')`** — ceci ferme le defer **P2** du code-review HF-2 (2026-04-25, jamais implémenté, référencé dans `HF-2-....md:421` : « `cancelSale` autorise EMPLOYEE, non prévu par AC5 — l'annulation devrait rester OWNER-only »). Ce point-là ne dépend PAS du sort d'AC1/AC2 : que `getPendingSales`/`validateSale` restent store-scopés ou reviennent à OWNER-only, `cancelSale` doit de toute façon devenir OWNER-only — c'est un gap identifié indépendamment par l'équipe HF-2 elle-même, pas par l'audit.
3. **AC3 (DayClosureController — `GET /day-closures` OWNER-only)** — **Given** `GET /api/v1/day-closures` hérite aujourd'hui du niveau classe `@PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")` (`DayClosureController.java:30,102-103`, aucune restriction méthode), **When** un `@PreAuthorize("hasRole('OWNER')")` est ajouté **au niveau méthode** sur `getClosure()`, **Then** EMPLOYEE → 403 `FORBIDDEN`, OWNER → 200 inchangé. **`POST /day-closures` (`closeDay()`, ligne 53-54) n'est PAS dans le scope — reste accessible aux deux rôles** (Story 4.4 : c'est l'EMPLOYEE — Loïc — qui clôture sa propre journée ; seule la LECTURE de l'historique de clôture devient gouvernance OWNER).
4. **AC4 (StoreController — create/list/update/deactivate OWNER-only)** — **Given** `StoreController` n'a **aucune** annotation `@PreAuthorize` au niveau classe ni méthode aujourd'hui (`StoreController.java:33`, ouvert à tout utilisateur JWT authentifié), **When** `@PreAuthorize("hasRole('OWNER')")` est ajouté au niveau classe, **Then** les 4 endpoints (`POST /stores`, `GET /stores`, `PATCH /stores/{id}`, `PATCH /stores/{id}/deactivate`) → EMPLOYEE 403 `FORBIDDEN`, OWNER 200/201 inchangé. **Vérifié cohérent côté mobile :** `/stores` est déjà dans `_ownerOnlyPrefixes` de `app_router.dart:305` — le client bloque déjà la navigation EMPLOYEE vers cet écran ; cette AC ferme le trou côté API qui existait indépendamment de la garde UI.
5. **AC5 (StockController — scope storeId EMPLOYEE, FR36)** — **Given** `POST /products/{productId}/stock/entry` et `POST /products/{productId}/stock/adjust` acceptent un `storeId` dans le corps de requête (`RecordStockEntryRequestDto.storeId()`, `AdjustStockRequestDto.storeId()`) sans jamais le comparer à la boutique assignée de l'EMPLOYEE (`StockController.java:95-128`), **When** un EMPLOYEE appelle l'un de ces deux endpoints avec un `storeId` différent de sa boutique JWT, **Then** 403 `FORBIDDEN` (aucun mouvement de stock créé) ; **And** un EMPLOYEE avec `storeId` correspondant → 200/201 inchangé ; **And** OWNER (toujours `assignedStoreId == null`) n'est jamais scopé → inchangé.
6. **AC6 (InventorySessionController — scope storeId EMPLOYEE, FR36)** — **Given** `POST /inventory/sessions` (`createSession`) accepte un `storeId` (`CreateInventorySessionRequestDto.storeId()`) sans le comparer à la boutique assignée EMPLOYEE (`InventorySessionController.java:60-72`), **When** un EMPLOYEE lance une session d'inventaire avec un `storeId` différent du sien, **Then** 403 `FORBIDDEN` (aucune session créée) ; **And** un EMPLOYEE avec `storeId` correspondant → 201 inchangé. **`POST /inventory/sessions/{id}/validate` reste OWNER-only, déjà implémenté (`InventorySessionController.java:138-148`) — modèle de référence à réutiliser (voir Dev Notes).**
7. **AC7 (StockTransferController — initiate OWNER-only)** — **Given** `POST /api/v1/stock/transfers` (`transfer()`, initiation étape 1) hérite du niveau classe `@PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")` (`StockTransferController.java:40,63-64`), **When** un `@PreAuthorize("hasRole('OWNER')")` est ajouté au niveau méthode sur `transfer()`, **Then** EMPLOYEE → 403 `FORBIDDEN`, OWNER → 201 inchangé. **`POST /{id}/complete` (étape 2, réception) et `GET /transfers` (historique) restent `hasAnyRole('OWNER','EMPLOYEE')` inchangés** — un EMPLOYEE à la boutique destination doit pouvoir réceptionner un transfert initié par l'OWNER ; l'épique ne restreint que l'initiation. Côté mobile, la sheet `_EmployeeCta` (« Contacter le propriétaire pour initier un transfert ») existe déjà (`cross_store_availability_bottom_sheet.dart:437-472`) — **aucun changement UI requis pour cette AC**, seul le FAB « Nouveau transfert » de `transfer_history_page.dart:199-206` doit être masqué pour EMPLOYEE (voir AC9, Flutter).
8. **AC8 (ProductController — décision HF-2 B4 déjà résolue, documentation seule, AUCUN changement de code)** — **Given** l'épique demande de « confirmer owner-only OU documenter la décision HF-2 B4 (EMPLOYEE peut créer/éditer catalogue) — décision PO à confirmer », **When** on vérifie l'état actuel de la branche `v1-stabilization`, **Then** la décision est **déjà prise et déployée** : le commit `8a3f06e` (« HF-2: Stabilisation Online — RBAC Employé, Notifications Transferts & Ventes Brouillons ») a explicitement retiré `/products/new`, `/products/import` et la regex `/products/:id/edit` de `_ownerOnlyPrefixes` dans `app_router.dart`, avec le commentaire (déjà présent, lignes 296-300) : « le catalogue (création, import, édition produit) est désormais identique pour OWNER et EMPLOYEE (HF-2 B4 fix). Backend RBAC (403) enforce authorization at the API level. ». Le backend (`ProductController.java:34`, `hasAnyRole('OWNER','EMPLOYEE')` sur `POST`/`PATCH`/`PATCH .../archive`) est **déjà cohérent** avec cette décision. **Aucune modification de `ProductController.java` n'est requise par cette story** — cette AC est satisfaite en ajoutant une note de documentation dans le Dev Agent Record confirmant l'état vérifié (source : `HF-2-stabilisation-online-rbac-employe-notifications-transferts.md:391-393`, commit `8a3f06e`). **Ne pas restreindre `ProductController` à OWNER-only — ce serait une régression fonctionnelle contraire à une décision produit déjà shippée.**
9. **AC9 (Contrepartie mobile — Flutter, gating UI, FR36 layered enforcement)** — **Given** l'épique demande d'« ajouter `/pos/pending` à `_ownerOnlyPrefixes` » (car `GET /sales/pending` devient OWNER-only côté backend, AC1) et de « cacher les FABs/actions owner-only (initier transfert, clôture journée, gestion employés, dashboard, rentabilité) », **When** on audite `app_router.dart` et les pages concernées, **Then** :
   - `/pos/pending` (route existante, `app_router.dart:532,536`, pages `pending_sales_page.dart`/`pending_sale_detail_page.dart`) **n'est PAS** dans `_ownerOnlyPrefixes` (`app_router.dart:301-314`) → **à ajouter** (gap réel, seul vrai changement de routing requis).
   - Dashboard, gestion employés (`/settings/team`), rentabilité (`/reports/rentabilite`) sont **déjà** dans `_ownerOnlyPrefixes` (lignes 302, 307, 311) → **aucun changement**.
   - La clôture journée (`DayCloseButton`) est une action EMPLOYEE légitime (AC3 : seule la lecture `GET /day-closures` devient OWNER-only, pas `POST`) → **ne pas masquer** ce bouton pour EMPLOYEE, ce serait contraire à Story 4.4.
   - Le FAB « Nouveau transfert » de `transfer_history_page.dart:199-206` (ouvre le formulaire d'initiation) **n'est pas gaté par rôle** aujourd'hui → **à masquer pour EMPLOYEE**, en miroir du gate déjà existant sur la navigation vers cette page (`global_stock_overview_page.dart:107`, `if (ref.watch(currentUserRoleProvider) != 'EMPLOYEE')`).

**And** (transverse, toutes AC1/AC3/AC4/AC7) chaque endpoint resserré est couvert par un test `standaloneSetup` (pas `@WebMvcTest`) qui prouve le 403 EMPLOYEE — voir Dev Notes « Pourquoi `@PreAuthorize` seul ne suffit pas dans ces tests ».
**And** (AC5/AC6) le mécanisme d'extraction du `storeId` EMPLOYEE réutilise le pattern **déjà existant** `Authentication.getDetails()` peuplé par `JwtAuthFilter.java:221-223` pour **tous** les rôles EMPLOYEE sur **toutes** les requêtes — pas de nouveau parsing JWT à écrire.

## Tasks / Subtasks

- [x] **Task 1 — `PendingSaleController` : préserver HF-2 par défaut (AC1/AC2) + ajouter la couverture de test manquante + `cancelSale` → OWNER-only (non ambigu)**
  - [x] 1.0 **AVANT TOUTE CHOSE** : lire Dev Notes « Décision D1 ». Le défaut de cette story est de **préserver** `getPendingSales`/`validateSale` tels quels (`hasAnyRole('OWNER','EMPLOYEE')` + scope storeId HF-2 existant) et de fermer le vrai gap trouvé (aucun test EMPLOYEE) — sous-tâches 1.1-1.3. Ne basculer vers l'Option alternative (revert complet à `hasRole('OWNER')`, sous-tâches 1.1alt-1.3alt) que si le PO/code-review confirme explicitement vouloir revenir à la spec originale Story 4.3. Documenter le choix pris dans Completion Notes quoi qu'il arrive.
  - [x] 1.1 **(défaut)** `PendingSaleController.java:40-54` (`getPendingSales`) : **ne pas modifier** le code (`@PreAuthorize`, `getPendingSalesByStore()` restent tels quels). Ajouter un test dans `PendingSaleControllerTest.java` : `shouldReturn200ForEmployeeOnOwnStorePendingSales()` — `authenticateAsEmployee(storeId)` (nouveau helper, voir 1.3) → `GET /sales/pending` → `verify(getPendingSalesUseCase).getPendingSalesByStore(storeId)` (pas `getPendingSales()`), 200.
  - [x] 1.1alt *(uniquement si Décision D1 = revert confirmé)* Remplacer `@PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")` par `@PreAuthorize("hasRole('OWNER')")` sur `getPendingSales` (ligne 41) + ajouter un check programmatique (pattern `InventorySessionController.validateInventory():142-148` — `if (!isOwnerRole()) throw new DomainException(ErrorCode.FORBIDDEN, "...")`, nouvelle méthode privée `isOwnerRole()` miroir de `isEmployeeRole()` ligne 86-90). Importer `DomainException`/`ErrorCode` (absents aujourd'hui). Le test 1.1 devient alors `shouldReturn403ForEmployeeOnPendingSaleGet()` (403 au lieu de 200).
  - [x] 1.2 **(défaut)** `PendingSaleController.java:57-68` (`validateSale`) et `ValidateSaleService.enforceEmployeeStoreScope()` (`ValidateSaleService.java:162-167`) : **ne pas modifier** le code — le scope existe déjà et fonctionne. Ajouter DEUX tests dans `ValidateSaleServiceTest.java` (fichier existant, aucune mention EMPLOYEE actuellement) : (a) `validateSale_employeeOwnStore_succeeds()` — `ValidateSaleCommand` avec `assignedStoreId == sale.storeId` → `saleRepository.updateStatus` appelé, pas d'exception ; (b) `validateSale_employeeOtherStore_throwsForbidden()` — `assignedStoreId != sale.storeId` → `assertThatThrownBy(...).isInstanceOf(DomainException.class)` avec `ErrorCode.FORBIDDEN`, `verify(saleRepository, never()).updateStatus(any(), any())`. Répéter la même paire pour `cancelPendingSale()` (même méthode `enforceEmployeeStoreScope`, ligne 121-123).
  - [x] 1.2alt *(uniquement si Décision D1 = revert confirmé)* Remplacer `@PreAuthorize` sur `validateSale` (ligne 58) par `@PreAuthorize("hasRole('OWNER')")` + check programmatique (même pattern que 1.1alt). **Ceci rend `ValidateSaleService.enforceEmployeeStoreScope()` inatteignable pour ce chemin — ne pas le supprimer du service** (il reste utilisé par `cancelPendingSale`, voir 1.6). Le test 1.2 devient `shouldReturn403ForEmployeeOnPendingSaleValidate()` au niveau controller (403 au lieu de 200) plutôt que les tests service décrits ci-dessus.
  - [x] 1.3 **(défaut, prérequis pour 1.1/1.2)** Ajouter un helper `authenticateAsEmployee(UUID storeId)` dans `PendingSaleControllerTest.java` (le fichier n'a que `authenticateAs(role)` sans `.setDetails()`, ligne 63-68) : `new UsernamePasswordAuthenticationToken(actorId, null, List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))` + `.setDetails(storeId)`, en miroir de la vraie sortie de `JwtAuthFilter`.
  - [x] 1.6 **(Non ambigu, indépendant de la Décision D1)** `cancelSale()` (ligne 70-80) : remplacer `@PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")` par `@PreAuthorize("hasRole('OWNER')")` + garde programmatique (pattern 1.1alt, nouvelle méthode privée `isOwnerRole()` dans `PendingSaleController` — nécessaire même si D1 reste au défaut, car elle n'existe pas encore dans ce fichier). Importer `DomainException`/`ErrorCode`. Ferme le defer P2 HF-2. Ajouter `shouldReturn403ForEmployeeOnSaleCancel()`.

- [x] **Task 2 — `DayClosureController` : `GET /day-closures` → OWNER-only (AC3)**
  - [x] 2.1 `DayClosureController.java:102-103` : ajouter `@PreAuthorize("hasRole('OWNER')")` au niveau **méthode** sur `getClosure()`. **Attention** : en Spring Security, une annotation `@PreAuthorize` au niveau méthode **remplace** entièrement celle du niveau classe pour cette méthode (pas de cumul/ET logique) — donc `hasRole('OWNER')` sur `getClosure()` est suffisant, pas besoin de répéter `hasAnyRole`.
  - [x] 2.2 Ajouter un check programmatique explicite en tête de `getClosure()` (même raison qu'1.3) : `if (!isOwnerRole(...)) throw new DomainException(ErrorCode.FORBIDDEN, "...")`. `DayClosureController` n'a pas de méthode `isEmployeeRole()`/`isOwnerRole()` existante — en créer une utilisant `SecurityContextHolder` (import déjà présent indirectement via Spring Security, ajouter `org.springframework.security.core.context.SecurityContextHolder` si absent — vérifier les imports actuels du fichier).
  - [x] 2.3 **Ne pas toucher** `closeDay()` (ligne 53-54, reste accessible EMPLOYEE + OWNER — Story 4.4).

- [x] **Task 3 — `StoreController` : create/list/update/deactivate → OWNER-only (AC4)**
  - [x] 3.1 `StoreController.java:33` : ajouter `@PreAuthorize("hasRole('OWNER')")` au niveau **classe** (aucune annotation actuellement — le fichier n'importe même pas `org.springframework.security.access.prepost.PreAuthorize`, à ajouter).
  - [x] 3.2 Ajouter une méthode privée `requireOwner()` appelée en première ligne des 4 méthodes (`createStore`, `listStores`, `updateStore`, `deactivateStore`) : `if (!isOwnerRole()) throw new DomainException(ErrorCode.FORBIDDEN, "Only OWNER can manage stores");` — DRY, évite de dupliquer le bloc 4 fois. Importer `DomainException`/`ErrorCode` (`com.keevo.shared.domain.exception.*`) — pas encore importés dans ce fichier.
  - [x] 3.3 Vérifier que `GlobalExceptionHandler` est bien enregistré dans `StoreControllerTest` (déjà le cas, ligne 53 — `.setControllerAdvice(new GlobalExceptionHandler())`) pour que `DomainException(FORBIDDEN)` se traduise en 403 JSON dans les tests.

- [x] **Task 4 — `StockController` : scope storeId EMPLOYEE sur `entry`/`adjust` (AC5, FR36)**
  - [x] 4.1 Ajouter une méthode privée `extractAssignedStoreId()` dans `StockController` — copie exacte du pattern `PendingSaleController.java:96-99` : `Object details = SecurityContextHolder.getContext().getAuthentication().getDetails(); return (details instanceof UUID storeId) ? storeId : null;`. Ce `getDetails()` est peuplé pour **tout** rôle EMPLOYEE par `JwtAuthFilter.java:221-223`, quel que soit le controller — pas de nouveau plumbing requis.
  - [x] 4.2 Dans `recordStockEntry()` (ligne 96-107) : après extraction de `actorId`, ajouter `UUID assignedStoreId = extractAssignedStoreId(); if (assignedStoreId != null && !assignedStoreId.equals(request.storeId())) { throw new DomainException(ErrorCode.FORBIDDEN, "EMPLOYEE cannot record stock entry for another store"); }`.
  - [x] 4.3 Même bloc dans `adjustStock()` (ligne 117-128) avec message adapté.
  - [x] 4.4 Importer `DomainException`/`ErrorCode` dans `StockController.java` (absents actuellement). **Piège :** `StockControllerTest.setUp()` (ligne 53-55) construit `mockMvc` avec `MockMvcBuilders.standaloneSetup(stockController).build()` **sans** `.setControllerAdvice(new GlobalExceptionHandler())` — contrairement à `StoreControllerTest`/`InventorySessionControllerTest`/`PendingSaleControllerTest` qui l'ont déjà. Sans cet ajout, un `DomainException(FORBIDDEN)` lancé par le controller ne sera **pas** traduit en 403 JSON par MockMvc — le test échouera avec l'exception brute plutôt qu'un assert propre. **Ajouter `.setControllerAdvice(new GlobalExceptionHandler())` à la construction de `mockMvc` dans `StockControllerTest.setUp()`** (import `com.keevo.shared.infrastructure.web.GlobalExceptionHandler` à ajouter) avant d'écrire les tests 403 de la Task 9.4.
  - [x] 4.5 **Ne PAS scoper** `getCurrentStock()` (GET, pas de storeId en entrée — retourne tous les niveaux du produit, cross-store par design, Story 2.3), `setThreshold()` (PATCH, pas de storeId — seuil produit global), ni `getHistory()` (GET, `storeId` en query param **optionnel** de filtre) — hors scope littéral de l'AC (« reject `request.storeId != jwtStoreId` » vise les écritures). **Noter en Completion Notes** que `getHistory()` avec `storeId` cross-store reste consultable par un EMPLOYEE (lecture d'historique d'une autre boutique) — defer/à trancher en code review si jugé sensible, ne pas bloquer cette story dessus (scope creep).
  - [x] 4.6 Étendre `StockControllerTest.java` : le `setUp()` actuel (ligne 53-64) authentifie avec `List.of()` (aucune autorité, pas de `.setDetails()`). Ajouter un helper `authenticateAsEmployee(UUID storeId)` qui pose `new UsernamePasswordAuthenticationToken(actorId, null, List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))` + `.setDetails(storeId)`, en miroir de la vraie sortie de `JwtAuthFilter`.

- [x] **Task 5 — `InventorySessionController` : scope storeId EMPLOYEE sur `createSession` (AC6, FR36)**
  - [x] 5.1 Ajouter la même méthode privée `extractAssignedStoreId()` (copie du pattern Task 4.1) dans `InventorySessionController`.
  - [x] 5.2 Dans `createSession()` (ligne 60-72) : après extraction de `actorId`, ajouter `UUID assignedStoreId = extractAssignedStoreId(); if (assignedStoreId != null && !assignedStoreId.equals(request.storeId())) { throw new DomainException(ErrorCode.FORBIDDEN, "EMPLOYEE cannot start inventory session for another store"); }`. `DomainException`/`ErrorCode` déjà importés (utilisés par `getActiveSession`/`validateInventory`).
  - [x] 5.3 **Ne pas toucher** `validateInventory()` (déjà OWNER-only, ligne 138-154 — modèle de référence pour ce pattern), `cancelSession()` (contrôle d'ownership par acteur déjà géré en aval par `cancelSessionUseCase`, pas un contrôle storeId), `getActiveSession()`/`listSessions()` (GET, `storeId` query optionnel — même raisonnement que 4.5, hors scope littéral, noter en defer).

- [x] **Task 6 — `StockTransferController` : `transfer()` (initiate) → OWNER-only (AC7)**
  - [x] 6.1 `StockTransferController.java:63-64` : ajouter `@PreAuthorize("hasRole('OWNER')")` au niveau **méthode** sur `transfer()`.
  - [x] 6.2 Ajouter un check programmatique explicite en tête de `transfer()` (même raison qu'1.3/2.2) — ajouter une méthode privée `isOwnerRole()` dans `StockTransferController` (le fichier n'a actuellement aucune méthode de vérification de rôle, seulement `extractActorId()` ligne 138-143).
  - [x] 6.3 **Ne pas toucher** `complete()` (ligne 93-101, réception à la boutique destination — reste `hasAnyRole`) ni `getHistory()` (ligne 110-134, reste `hasAnyRole`).

- [x] **Task 7 — `ProductController` : documentation de la décision HF-2 B4 (AC8, AUCUN changement de code)**
  - [x] 7.1 **Vérifier** (déjà fait pendant la création de cette story, à re-confirmer par le dev avant de commencer) : `git log --oneline -- keevo/app/lib/core/router/app_router.dart` contient bien `8a3f06e` et le commentaire `app_router.dart:296-300` documente déjà HF-2 B4. Si une divergence est trouvée (peu probable), remonter avant de continuer.
  - [x] 7.2 **Ne modifier ni `ProductController.java` ni `app_router.dart:296-300`** — cette tâche est purement une note de confirmation à écrire dans Completion Notes List (voir AC8).

- [x] **Task 8 — Contrepartie mobile Flutter (AC9)**
  - [x] 8.1 `app_router.dart:301-314` (`_ownerOnlyPrefixes`) : ajouter `'/pos/pending'` à la liste (aligné avec AC1 — `GET /sales/pending` devient OWNER-only côté backend).
  - [x] 8.2 `transfer_history_page.dart:199-206` : gater le `FloatingActionButton.extended` (« Nouveau transfert ») avec `if (ref.watch(currentUserRoleProvider) != 'EMPLOYEE') FloatingActionButton.extended(...)` (retourner `null` sinon), en miroir exact du pattern déjà utilisé pour le bouton qui navigue vers cette page (`global_stock_overview_page.dart:107`).
  - [x] 8.3 **Ne pas toucher** : `_EmployeeCta` (`cross_store_availability_bottom_sheet.dart`, déjà correct), le commentaire `app_router.dart:296-300` (déjà correct, AC8), `DayCloseButton` (reste visible EMPLOYEE, AC3), `/dashboard`/`/settings/team`/`/reports/rentabilite` (déjà dans `_ownerOnlyPrefixes`).

- [x] **Task 9 — TDD (transverse, toutes AC)**
  - [x] 9.1 `PendingSaleControllerTest.java` : `shouldReturn403ForEmployeeOnSaleCancel()` (non ambigu, toujours requis, Task 1.6) — `authenticateAs("EMPLOYEE")` → `POST /sales/{id}/cancel` → 403 ; + `shouldReturn200ForEmployeeOnOwnStorePendingSales()` (défaut, Task 1.1) — `authenticateAsEmployee(storeId)` → `GET /sales/pending` → 200, `getPendingSalesByStore` appelé. `ValidateSaleServiceTest.java` : 4 tests `enforceEmployeeStoreScope` positif/négatif pour `validateSale`/`cancelPendingSale` (défaut, Task 1.2). **Si Décision D1 bascule vers revert** (confirmé par code-review/PO) : remplacer le test GET positif par `shouldReturn403ForEmployeeOnPendingSaleGet()` + ajouter `shouldReturn403ForEmployeeOnPendingSaleValidate()` au niveau controller, retirer les 2 tests positifs `ValidateSaleServiceTest` (garder les 2 négatifs).
  - [x] 9.2 `DayClosureControllerTest.java` : `shouldReturn403ForEmployeeOnDayClosureRead()` — `mockJwt("EMPLOYEE")` (ligne 85) → `GET /day-closures?storeId=...` → 403. Vérifier que `closeDay()` (POST) reste 201 pour EMPLOYEE dans un test existant (non-régression, pas un nouveau test si déjà couvert).
  - [x] 9.3 `StoreControllerTest.java` : `shouldReturn403ForEmployeeOnStoreCreate()` + idem pour list/update/deactivate (4 tests ou 1 test paramétré) — `authenticateAs("EMPLOYEE")` (pattern à créer, le fichier n'a que l'auth OWNER par défaut au `setUp()`, ligne 57-58) → chaque endpoint → 403.
  - [x] 9.4 `StockControllerTest.java` : `shouldEnforceEmployeeStoreIdOnStockAdjust()` + `shouldEnforceEmployeeStoreIdOnStockEntry()` — EMPLOYEE avec `storeId` JWT ≠ `storeId` requête → 403, aucun appel à `adjustStockUseCase`/`recordStockEntryUseCase` (`verify(..., never())`). **Test complémentaire** : EMPLOYEE avec `storeId` JWT == `storeId` requête → 200/201 (non-régression du cas nominal).
  - [x] 9.5 `InventorySessionControllerTest.java` : `shouldEnforceEmployeeStoreIdOnCreateSession()` — même schéma que 9.4 sur `POST /inventory/sessions`.
  - [x] 9.6 `StockTransferControllerTest.java` : `shouldReturn403ForEmployeeOnTransferInitiate()` — `authenticateAs("EMPLOYEE")` (pattern à créer, le fichier actuel authentifie avec `List.of()` sans autorité, ligne 62-63) → `POST /stock/transfers` → 403. Vérifier `complete()`/`getHistory()` restent 200 pour EMPLOYEE (non-régression, existant ou à ajouter si absent).
  - [x] 9.7 Non-régression complète : `mvn test -Dtest='PendingSaleControllerTest,ValidateSaleServiceTest,DayClosureControllerTest,StoreControllerTest,StockControllerTest,InventorySessionControllerTest,InventorySessionControllerValidateTest,StockTransferControllerTest,ProductControllerTest,MultiStoreStockControllerTest,QuickAddProductControllerTest'` — tous GREEN. **Ne PAS inclure `SaleControllerHF2Test` dans cette commande — ce fichier n'existe pas, `-Dtest` avec une classe introuvable fait échouer le build Maven** (voir Décision D1).
  - [x] 9.8 Sanity plus large : `mvn test` (suite complète) — 0 NOUVELLE régression vs baseline connue (voir Story 12-5 : ~1434 tests, 1 flaky pré-existant, ~40 erreurs `ApplicationContext` DB-dépendantes pré-existantes — confirmer que ces chiffres n'empirent pas).
  - [x] 9.9 `flutter analyze` (côté `keevo/app`) — 0 erreurs, 0 warnings nouveaux après Task 8.

- [x] **Task 10 — Dev Agent Record**
  - [x] 10.1 Renseigner Agent Model, Debug Log, Completion Notes (inclure explicitement la confirmation AC8 — décision HF-2 B4 déjà résolue, aucun code touché), File List.

## Dev Notes

### 🚨 Décision D1 — tension HF-2 (shippé) vs Story 4.3/audit (spec originale) sur `PendingSaleController`

**C'est la décision la plus importante de cette story — plus qu'aucun détail d'implémentation ci-dessous.** Deux sources indépendantes et également crédibles se contredisent :

**Camp « OWNER-only » (le texte littéral de l'epic — option alternative, PAS le défaut de cette story) :**
- `epics-remediation-audit.md` Story 12.6 dit explicitement : `/sales/pending` + `/sales/{id}/validate` → `hasRole('OWNER')`.
- L'AC **originale** de Story 4.3 (`epic-4-point-de-vente-pos.md:162,187`) dit littéralement : « `GET /api/v1/sales/pending` returns all `PENDING_VALIDATION` sales for the tenant **(OWNER-only, HTTP 403 for EMPLOYEE)** » et « this endpoint is `POST /api/v1/sales/{id}/validate` — **OWNER-only (HTTP 403 for EMPLOYEE)** ».
- L'audit `AUDIT_CONFORMITE_BMAD.md` (2026-07-20, donc **postérieur** à HF-2 d'environ 3 mois) flag toujours ce comportement comme B-HIGH-9 « sur-permission ».

**Camp « préserver HF-2 » (store-scoped EMPLOYEE access — DÉFAUT de cette story) :**
- Le hotfix `HF-2-stabilisation-online-rbac-employe-notifications-transferts.md` (`done`, 2026-04-24/25) a **délibérément** implémenté l'AC5 : « EMPLOYEE peut accéder et valider les ventes brouillons de sa boutique » — remontée terrain explicite (« l'employé doit pouvoir valider une vente brouillon, avec notification envoyée à l'owner »), pas un bug.
- Le code le prouve : `ValidateSaleService.enforceEmployeeStoreScope()` (`ValidateSaleService.java:54,123,162-167`) rejette 403 si `sale.storeId != jwt.storeId`, mais **autorise** si le storeId matche — c'est un vrai scope, pas un oubli. Vérifié en lisant le fichier source directement, pas seulement la doc HF-2.
- `spec-notif-owner-actions-employe.md:207` (rédigée en parallèle du même audit remédiation) confirme indépendamment : « FR36/B-HIGH-10 : le scope serveur employé reste à corriger séparément — **l'employé garde son storeId JWT pour les ventes**, mais les entry/adjust restent à scopifier en parallèle » — preuve textuelle qu'une autre équipe/agent, travaillant sur le MÊME audit à la MÊME période, considérait déjà le scope EMPLOYEE sur les ventes comme un choix produit assumé et non remis en cause.
- **⚠️ CORRECTIF :** `HF-2-....md:170` mentionne un test `SaleControllerHF2Test` censé couvrir « RBAC store-scoped EMPLOYEE access tests, AC5 positif + négatif 403 » — **ce fichier n'existe PAS** sur cette branche (`find keevo/backend/src/test -iname "*HF2*"` → vide ; jamais committé selon `git log --all --diff-filter=A`). Le comportement HF-2 AC5 est réel mais **totalement non couvert par des tests automatisés** aujourd'hui (`PendingSaleControllerTest` = 100% OWNER, `ValidateSaleServiceTest` = aucune mention EMPLOYEE).

**Pourquoi la contradiction ?** `epics-remediation-audit.md:32` dit explicitement : « L'audit a tourné sur `refractoring` ». Cette branche a divergé de `deploy` **avant** HF-2 (2026-04-24/25) n'y soit intégré (si jamais il l'a été). L'audit est donc probablement un scan mécanique du code sur une branche qui n'a jamais vu le hotfix — pas une remise en cause produit délibérée du choix HF-2. Mais ce n'est pas certain à 100% : il est aussi possible que l'équipe produit ait sciemment voulu revenir sur HF-2 en écrivant l'audit après coup (HF-2 était un correctif réactif terrain, pas une révision formelle de la Story 4.3 originale — un PO pourrait légitimement vouloir revenir à la spec originale).

**Recommandation de cette story : PRÉSERVER HF-2 par défaut** (AC1/AC2 tels qu'écrits ci-dessus — ne PAS toucher `getPendingSales`/`validateSale`, ajouter seulement la couverture de test manquante), **pour deux raisons qui l'emportent sur le texte littéral de l'epic :** (1) c'est un comportement produit shippé, documenté comme intentionnel et motivé par une remontée terrain réelle, pas un oubli à corriger ; (2) l'absence totale de test de régression sur ce comportement signifie qu'un revert vers OWNER-only serait **silencieux** — aucun `mvn test` ne le détecterait, contrairement à ce qu'on pourrait croire en lisant la mention (fausse) d'un `SaleControllerHF2Test` protecteur. Un revert non intentionnel serait donc le pire des scénarios : une régression fonctionnelle invisible en CI. **Mais s'assurer que le code-review (bmad-code-review) statue explicitement dessus** — exactement comme Story 12.5 a fait trancher son Option A/B en décision de code-review plutôt qu'en supposition silencieuse — au cas où le PO souhaite délibérément revenir à la spec originale Story 4.3 (option alternative 1.1alt/1.2alt). Ne PAS laisser cette question sans réponse explicite dans le Dev Agent Record — écrire noir sur blanc quelle option a été retenue et pourquoi.

### 🔬 Pourquoi `@PreAuthorize` seul ne suffit PAS dans ces tests — pattern obligatoire

**Découverte critique de cette story** : tous les tests de controller existants (`StockControllerTest`, `StoreControllerTest`, `PendingSaleControllerTest`, `DayClosureControllerTest`, `StockTransferControllerTest`, `InventorySessionControllerTest`) utilisent `MockMvcBuilders.standaloneSetup(controller)` — **PAS** `@WebMvcTest` + contexte Spring. `standaloneSetup` ne charge **aucun** aspect AOP `@EnableMethodSecurity` — un `@PreAuthorize("hasRole('OWNER')")` posé sur une méthode **ne sera jamais évalué** dans ces tests, quel que soit le rôle authentifié. Un test `authenticateAs("EMPLOYEE")` puis appel de l'endpoint retournerait 200 même avec l'annotation en place, **prouvant un faux GREEN**.

`InventorySessionController.validateInventory()` (ligne 138-148) a déjà résolu ce problème pour l'unique endpoint OWNER-only pré-existant du codebase, avec un commentaire explicite : *« Defense-in-depth: explicit role check (testable with standaloneSetup) »*. **C'est le modèle de référence à répliquer sur les 4 endpoints resserrés de cette story** (Task 1.3, 2.2, 3.2, 6.2) : poser `@PreAuthorize` **en plus** (défense-en-profondeur réelle si un jour la suite bascule en `@WebMvcTest`, comme `SecurityConfigAdminPathEnforcementTest` de la Story 12-3) **et** un check programmatique `if (!isOwnerRole()) throw new DomainException(ErrorCode.FORBIDDEN, "...")` qui, lui, est réellement exécuté et testable en `standaloneSetup`.

**Ne pas** essayer de convertir ces tests en `@WebMvcTest` pour « tester le vrai `@PreAuthorize` » — ce serait un refactor massif hors scope (7 fichiers de test + configuration Spring Security de test), contraire à la discipline V1-stab « chirurgical, pas de scope creep ».

### Pattern réutilisable — extraction `storeId` EMPLOYEE

`JwtAuthFilter.java:217-225` peuple **systématiquement** `Authentication.getDetails()` avec le `storeId` (UUID) du claim JWT pour **tout** rôle EMPLOYEE, sur **toute** requête, indépendamment du controller ciblé :

```java
var auth = new UsernamePasswordAuthenticationToken(
        userId, null,
        List.of(new SimpleGrantedAuthority("ROLE_" + role)));
if ("EMPLOYEE".equals(role)) {
    auth.setDetails(jwtTokenProvider.extractStoreId(claims));
}
SecurityContextHolder.getContext().setAuthentication(auth);
```

`PendingSaleController.extractAssignedStoreId()` (ligne 96-99) est le **seul** endroit du codebase qui consomme déjà ce détail :

```java
private UUID extractAssignedStoreId() {
    Object details = SecurityContextHolder.getContext().getAuthentication().getDetails();
    return (details instanceof UUID storeId) ? storeId : null;
}
```

**Copier ce pattern tel quel** dans `StockController` (Task 4.1) et `InventorySessionController` (Task 5.1) — pas de nouveau parsing JWT, pas de dépendance à `JwtTokenProvider` à ajouter dans ces deux controllers pour cette AC. `null` signifie OWNER (jamais scopé) — c'est la même sémantique que `PendingSaleController`.

**Ne PAS confondre** avec l'autre mécanisme d'extraction de rôle par re-parsing manuel du header `Authorization` via `JwtTokenProvider` (utilisé par `DayClosureController.extractTenantId()/extractActorId()` et `InventorySessionController.extractRole()` pour `cancelSession()`) — ce deuxième pattern est plus lourd (re-parse le token à chaque appel) et n'est PAS le modèle à suivre pour le scope storeId de cette story ; il reste inchangé là où il existe déjà.

### `ErrorCode.FORBIDDEN` — déjà défini, ne pas créer de nouveau code

`ErrorCode.java:121` définit déjà `FORBIDDEN` → HTTP 403 (« authenticated user does not have the required role »). `GlobalExceptionHandler.java:52,149-153` le mappe déjà vers un JSON `{"domainCode":"FORBIDDEN", "message":"Vous n'avez pas les droits nécessaires", ...}`. **Réutiliser `throw new DomainException(ErrorCode.FORBIDDEN, "<message anglais descriptif>")` partout** — c'est exactement le pattern de `InventorySessionController.java:147`. Ne créer ni nouveau `ErrorCode`, ni nouveau message FR_MESSAGES, ni nouveau handler.

### Table des fichiers backend à toucher

| Controller | Fichier | Changement | AC |
|---|---|---|---|
| PendingSaleController | `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/in/rest/PendingSaleController.java` | `getPendingSales`/`validateSale` : **AUCUN** changement de code par défaut (préserver HF-2, voir Décision D1) ; `cancelSale` : `hasAnyRole`→`hasRole('OWNER')` + check programmatique + nouvelle méthode `isOwnerRole()` (non ambigu) | AC1, AC2 |
| DayClosureController | `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/in/rest/DayClosureController.java` | `@PreAuthorize("hasRole('OWNER')")` méthode sur `getClosure()` + check programmatique | AC3 |
| StoreController | `keevo/backend/src/main/java/com/keevo/store/store/adapter/in/rest/StoreController.java` | `@PreAuthorize("hasRole('OWNER')")` classe (nouveau) + `requireOwner()` dans 4 méthodes | AC4 |
| StockController | `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/in/web/StockController.java` | `extractAssignedStoreId()` + check dans `recordStockEntry`/`adjustStock` | AC5 |
| InventorySessionController | `keevo/backend/src/main/java/com/keevo/inventory/counting/adapter/in/rest/InventorySessionController.java` | `extractAssignedStoreId()` + check dans `createSession` | AC6 |
| StockTransferController | `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/in/web/StockTransferController.java` | `@PreAuthorize("hasRole('OWNER')")` méthode sur `transfer()` + check programmatique + `isOwnerRole()` | AC7 |
| ProductController | `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/in/web/ProductController.java` | **AUCUN** — décision déjà résolue (HF-2 B4) | AC8 (doc seule) |

**Fichiers explicitement HORS scope** (vérifiés pendant la recherche, ne pas toucher) : `MultiStoreStockController.java` (endpoints `/stock/overview`, `/stock/stores/{storeId}/products`, `/stock/products/{productId}/availability` — non mentionnés dans l'AC de l'épique ; un EMPLOYEE garde une vue cross-store en lecture sur ces 3 endpoints ; c'est un gap FR36 potentiel mais **hors scope littéral** de cette story — à noter en Review Findings comme defer si le reviewer le juge pertinent, ne pas l'ajouter au scope d'implémentation).

### Table des fichiers de test backend à toucher

| Test | Fichier | Pattern existant à réutiliser |
|---|---|---|
| PendingSaleControllerTest | `.../commerce/sale/adapter/in/rest/PendingSaleControllerTest.java` | `authenticateAs(role)` ligne 63-68, `standaloneSetup` + `GlobalExceptionHandler` ; ajouter `authenticateAsEmployee(storeId)` (Task 1.3) + test EMPLOYEE positif GET (Task 1.1) + test `cancelSale` 403 (Task 1.6). **`SaleControllerHF2Test` cité dans `HF-2-....md:170` N'EXISTE PAS sur cette branche — ne pas le chercher, ne pas le référencer dans un `-Dtest`, voir Décision D1.** |
| ValidateSaleServiceTest | `.../commerce/sale/application/ValidateSaleServiceTest.java` (existe déjà, AUCUNE mention EMPLOYEE) | Ajouter les 4 tests `enforceEmployeeStoreScope` positif/négatif pour `validateSale`/`cancelPendingSale` (Task 1.2) — c'est ici, pas au niveau controller, que vit la logique de scope réelle |
| DayClosureControllerTest | `.../commerce/sale/adapter/in/rest/DayClosureControllerTest.java` | `mockJwt(role)` ligne 85-89 (mock `JwtTokenProvider` + `authenticateAs`) |
| StoreControllerTest | `.../store/store/adapter/in/rest/StoreControllerTest.java` | `setUp()` authentifie OWNER par défaut ligne 57-58 — ajouter `authenticateAs(role)` réutilisable pour EMPLOYEE |
| StockControllerTest | `.../catalog/stock/adapter/in/web/StockControllerTest.java` | `setUp()` authentifie sans autorité (`List.of()`) ligne 62-63 — ajouter `authenticateAsEmployee(storeId)` **et** `.setControllerAdvice(new GlobalExceptionHandler())` (absent aujourd'hui, seul fichier de test de cette story à ne pas l'avoir déjà) |
| InventorySessionControllerTest | `.../inventory/counting/adapter/in/rest/InventorySessionControllerTest.java` | `authenticateAs(role)` ligne 67-72 déjà présent — juste ajouter `.setDetails(storeId)` pour les nouveaux tests EMPLOYEE scope |
| InventorySessionControllerValidateTest | `.../inventory/counting/adapter/in/rest/InventorySessionControllerValidateTest.java` | **Modèle canonique** — `validateInventory_employeeForbidden_shouldReturn403()` ligne 118-125 = exact pattern à copier pour tous les nouveaux tests 403 |
| StockTransferControllerTest | `.../catalog/stock/adapter/in/web/StockTransferControllerTest.java` | `setUp()` authentifie sans autorité ligne 62-63 — ajouter `authenticateAs(role)` réutilisable |

### Fichiers Flutter à toucher

| Fichier | Changement | AC |
|---|---|---|
| `keevo/app/lib/core/router/app_router.dart` | Ajouter `'/pos/pending'` à `_ownerOnlyPrefixes` (ligne ~301-314) | AC9 |
| `keevo/app/lib/features/inventory/presentation/page/transfer_history_page.dart` | Gater le FAB « Nouveau transfert » (ligne 199-206) par `role != 'EMPLOYEE'` | AC9 |

**Fichiers Flutter vérifiés déjà corrects, ne pas toucher** : `app_router.dart:296-300` (commentaire HF-2 B4, AC8), `cross_store_availability_bottom_sheet.dart:437-472` (`_EmployeeCta`, AC7), `global_stock_overview_page.dart:107` (gate déjà présent), `_ownerOnlyPrefixes` entrées `/dashboard`, `/settings/team`, `/reports/rentabilite`, `/stores` (déjà présentes).

### Squelette TDD — modèle à copier (`InventorySessionControllerValidateTest.java:118-125`)

```java
@Test
void <method>_employeeForbidden_shouldReturn403() throws Exception {
    authenticateAs("EMPLOYEE");
    UUID id = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/<path>", id))
            .andExpect(status().isForbidden());
}
```

Pour AC5/AC6 (storeId mismatch), variante avec deux UUID distincts :

```java
@Test
void shouldEnforceEmployeeStoreIdOnStockAdjust() throws Exception {
    UUID myStoreId = UUID.randomUUID();
    UUID otherStoreId = UUID.randomUUID();
    authenticateAsEmployee(myStoreId);   // .setDetails(myStoreId)

    mockMvc.perform(post("/api/v1/products/{productId}/stock/adjust", productId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of(
                "storeId", otherStoreId, "newQuantity", 10, "notes", "test"))))
            .andExpect(status().isForbidden());

    verify(adjustStockUseCase, never()).execute(any(), any(), any(), anyInt(), any(), any());
}
```

## Architecture Compliance

- **S6, S7, B-HIGH-9, B-HIGH-10** : ferme les coutures de gouvernance owner-only et de scope EMPLOYEE identifiées par l'audit sur `PendingSaleController`, `DayClosureController`, `StoreController`, `StockController`, `InventorySessionController`, `StockTransferController`.
- **FR36** (`prd.md:375`) : « Un employé est limité aux opérations de la boutique à laquelle il est assigné » — AC5/AC6 implémentent ce contrat littéralement pour les deux endpoints d'écriture (`stock/entry`, `stock/adjust`, `inventory/sessions`) où un `storeId` explicite est fourni côté requête.
- **Défense-en-profondeur cohérente avec Story 12.3** (`SecurityConfig` filter-chain pour `/api/v1/admin/**`) : ici pas de règle path-level dans `SecurityConfig` — tous les endpoints de cette story restent gouvernés par annotations `@PreAuthorize` + check programmatique **au niveau controller**, cohérent avec l'architecture existante (`SecurityConfig.java:91-97` n'a de règle explicite QUE pour `/api/v1/admin/**` — le reste passe par `.anyRequest().authenticated()` + méthode-level).
- **Pas de nouveau contrat API** (les endpoints existent déjà, seule leur autorisation change), **pas de migration DB**, **pas de changement de DTO** (les `storeId` existent déjà dans les DTOs `AdjustStockRequestDto`/`RecordStockEntryRequestDto`/`CreateInventorySessionRequestDto`).
- **Refonte absorption** : l'audit note « re-appliquer les `@PreAuthorize` + scope JWT dans la structure modulaire » — la refonte (branche `refractoring`) devra ré-implémenter ces mêmes règles dans sa propre arborescence de controllers (pas de cherry-pick direct attendu, les chemins de fichiers divergent déjà entre les deux branches).

## Library / Framework Requirements

- **Spring Security 6.x / Spring Boot 3.5.0** (déjà en place) — `@PreAuthorize`, `SecurityContextHolder`, `UsernamePasswordAuthenticationToken`. Aucune nouvelle dépendance.
- **JUnit 5 + Mockito + AssertJ** (déjà en place) — `standaloneSetup`, `@MockBean`/`@Mock`, `verify(..., never())`. Aucune version à changer.
- Aucune dépendance externe ajoutée côté backend.
- **Flutter côté mobile** : aucune nouvelle dépendance — `ConsumerWidget`/`ref.watch(currentUserRoleProvider)` déjà utilisés partout dans le codebase (ex. `global_stock_overview_page.dart:107,341,398`).

## File Structure Requirements

Voir les deux tables « Fichiers backend à toucher » et « Fichiers Flutter à toucher » ci-dessus (Dev Notes). Résumé :
- 5 controllers backend **modifiés avec changement de `@PreAuthorize`** (`DayClosureController`, `StoreController`, `StockController`, `InventorySessionController`, `StockTransferController`).
- `PendingSaleController` : **`cancelSale` modifié** (OWNER-only, non ambigu) ; `getPendingSales`/`validateSale` **non modifiés par défaut** (préserver HF-2, voir Décision D1).
- 1 controller backend **non modifié**, documentation seule (`ProductController`).
- 7 fichiers de test backend **modifiés** (nouveaux tests 403/positifs + helpers `authenticateAs`/`authenticateAsEmployee`), y compris `ValidateSaleServiceTest.java` (4 nouveaux tests EMPLOYEE). **`SaleControllerHF2Test` n'existe pas — ne pas le chercher ni le modifier** (voir Décision D1).
- 2 fichiers Flutter **modifiés** (`app_router.dart`, `transfer_history_page.dart`).
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — **MODIFIED** (statut `v1s-12-6-rbac-owner-only-scope-store` → `ready-for-dev`).

## Testing Requirements

- **TDD obligatoire** (voir Tasks 9.1-9.6 et squelette ci-dessus) : chaque endpoint resserré (AC1/AC2/AC3/AC4/AC7) a un test `..._employeeForbidden_shouldReturn403()` ou équivalent ; chaque endpoint scope storeId (AC5/AC6) a un test mismatch→403 (avec `verify(useCase, never())`) **et** un test match→200/201 (non-régression cas nominal EMPLOYEE légitime).
- **Non-régression (Task 9.7/9.8)** : `mvn test -Dtest='PendingSaleControllerTest,ValidateSaleServiceTest,DayClosureControllerTest,StoreControllerTest,StockControllerTest,InventorySessionControllerTest,InventorySessionControllerValidateTest,StockTransferControllerTest,ProductControllerTest,MultiStoreStockControllerTest,QuickAddProductControllerTest'` GREEN, puis suite complète `mvn test` pour confirmer 0 nouvelle régression vs baseline Story 12-5 (~1434 tests).
- **Flutter (Task 9.9)** : `flutter analyze` — 0 erreurs/warnings nouveaux. Pas de test E2E automatisé attendu pour le gating UI (cohérent avec le niveau de couverture Flutter existant sur `app_router.dart` — pas de suite `integration_test` sur ce fichier à ce jour).
- Pas d'environnement runtime/DB requis pour les tests backend (tous `standaloneSetup`/Mockito, pas de `@SpringBootTest`/Testcontainers).

## Previous Story Intelligence

- **Story 12.5 (`v1s-12-5`, done)** : a établi/confirmé le pattern `TenantSchema.validate` + `catch (IllegalArgumentException)` scopé, et le principe **Option A (scope chirurgical)** plutôt qu'une validation globale — **directement applicable ici** : on ne touche QUE les endpoints listés par l'AC, pas de refactor de sécurité transverse. Code review 12.5 a validé ce choix (1 décision résolue en faveur du scope chirurgical). **Réutiliser cette discipline** : ne pas élargir aux endpoints GET non mentionnés (StockController.getHistory, InventorySessionController.getActiveSession/listSessions, MultiStoreStockController) même si FR36 pourrait théoriquement s'y appliquer — noter en defer, pas en scope.
- **Story 12.3 (`v1s-12-3`, done)** : a posé le modèle `@WebMvcTest` + `@Import(SecurityConfig.class)` (`SecurityConfigAdminPathEnforcementTest`) pour prouver l'enforcement au niveau filter-chain — **non applicable ici** (cette story reste au niveau controller/method, pas filter-chain SecurityConfig), mais le principe « défense-en-profondeur = annotation + check explicite » vient de cette lignée de stories.
- **Story 12.2 (`v1s-12-2`, done)** : a introduit `ErrorCode.FORBIDDEN`-adjacent `SESSION_REVOKED` avec le même mécanisme `GlobalExceptionHandler` — confirme que le pattern `DomainException(ErrorCode.X)` → JSON `domainCode` est LA convention du projet pour toute erreur métier/sécurité, pas de réinvention à faire.
- **Story 3.5 (EMPLOYEE runtime guards)** : a introduit le check `storeId` JWT vs DB dans `JwtAuthFilter` (ligne 182-215, `STORE_REASSIGNED`) — **différent** du scope de cette story (12.6 compare `storeId` de la **requête métier** au `storeId` du **JWT**, pas le JWT à la DB). Les deux mécanismes sont complémentaires et ne se recouvrent pas.
- **HF-2 (`8a3f06e`, commit historique hors tracker BMAD formel)** : a déjà résolu la question EMPLOYEE-catalogue (AC8) et a établi le pattern `_ownerOnlyPrefixes` + `_EmployeeCta` réutilisés par AC9. **Lire `HF-2-stabilisation-online-rbac-employe-notifications-transferts.md` en ENTIER avant de commencer Task 1** (pas seulement Task 7/8) — c'est la source de la Décision D1, le point le plus critique de cette story. HF-2 AC5 a scopé `PendingSaleController.getPendingSales`/`validateSale` par `storeId` EMPLOYEE via `ValidateSaleService.enforceEmployeeStoreScope()` — un comportement shippé, réel, **mais dont le test associé (`SaleControllerHF2Test`, cité dans le doc HF-2) n'a jamais été committé** (vérifié : absent du repo). Cette story **préserve** ce comportement par défaut et ajoute la couverture de test qui manquait depuis HF-2. Le code-review HF-2 (2ème passe, 2026-04-25) a aussi documenté le defer **P2** (`cancelSale` trop permissif) que cette story ferme sans ambiguïté (Task 1.6).

## Git Intelligence

- **Branche courante `v1-stabilization`** (off `deploy` d766035), HEAD `c7a4644` (Story 12.2 done). V1 reste déployable pendant la refonte (branche `refractoring`/`dev`, non concernée par cette story).
- L'audit `AUDIT_CONFORMITE_BMAD.md`/`epics-remediation-audit.md` a tourné sur la branche `refractoring` (« L'audit a tourné sur `refractoring` ; la refonte venant à peine de commencer, les 100% des findings s'appliquent à la ligne V1 (`deploy`) » — `epics-remediation-audit.md:32`). Le commit `8a3f06e` (HF-2) a été appliqué **après** l'audit sur `v1-stabilization`/`deploy` mais possiblement **avant** que `refractoring` ne le reçoive — c'est pourquoi l'AC de l'épique (rédigée sur la base de l'état `refractoring`) mentionne encore « commentaire trompeur » et « décision PO à confirmer » pour des points déjà résolus sur cette branche. **Ne pas re-corriger ce qui est déjà bon** — vérifier l'état réel du code avant d'agir, pas seulement le texte de l'épique (déjà fait pendant la création de cette story, à re-vérifier par le dev par prudence : `git log --oneline -- keevo/app/lib/core/router/app_router.dart | grep HF-2`).
- Aucun commit récent sur `v1-stabilization` n'a touché aux 6 controllers de cette story depuis leur création (Stories 2.3/3.1/3.3/4.3/4.4/6.x) — état stable, pas de conflit de merge attendu.

## Latest Technical Information

- **Spring Security 6.x `@PreAuthorize` niveau méthode vs classe** : une annotation méthode **remplace** (ne cumule pas avec) l'annotation classe pour cette méthode — comportement stable depuis Spring Security 5, inchangé en 6.x. Confirmé par le code existant (`InventorySessionController` a `hasAnyRole('OWNER','EMPLOYEE')` en classe et `hasRole('OWNER')` en méthode sur `validateInventory` — les deux coexistent déjà dans le même fichier, preuve vivante du comportement).
- **`MockMvcBuilders.standaloneSetup()`** ne charge pas `@EnableMethodSecurity` — comportement documenté de Spring Test depuis toujours, pas un changement de version récent. Confirmé empiriquement par lecture de tous les tests de controller existants (aucun n'utilise `@WebMvcTest`).
- Pas de breaking change de dépendances, pas de migration DB, pas de nouveau endpoint — changement d'autorisation pur.

## Project Context Reference

- [Source: `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md:147-169`] — AC BDD Story 12.6, refs S6/S7/B-HIGH-9/10/FR36/AC6/AC9/AC10, refonte absorption.
- [Source: `_bmad-output/planning-artifacts/prd.md:375`] — FR36 texte littéral.
- [Source: `_bmad-output/planning-artifacts/epics/epic-4-point-de-vente-pos.md:110-220`] — Story 4.3 (contexte `PENDING_VALIDATION`, `GET /sales/pending` OWNER-only, `POST /sales/{id}/validate` OWNER-only spécifiés en Story 4.3 littérale — cette référence fonde le camp « OWNER-only » de la Décision D1, en tension avec HF-2 AC5 qui a depuis élargi l'accès EMPLOYEE ; voir Dev Notes).
- [Source: `_bmad-output/planning-artifacts/epics/epic-4-point-de-vente-pos.md:222-276`] — Story 4.4 (clôture journalière EMPLOYEE-initiée — justifie de ne PAS restreindre `POST /day-closures`).
- [Source: `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/in/rest/PendingSaleController.java:26-100`] — état actuel, pattern `extractAssignedStoreId()`/`isEmployeeRole()` à réutiliser/inverser.
- [Source: `keevo/backend/src/main/java/com/keevo/commerce/sale/application/service/ValidateSaleService.java:54,123,162-165`] — `enforceEmployeeStoreScope()`, la preuve code que HF-2 AC5 est un scope réel et déjà implémenté, pas un oubli — fondement de la Décision D1.
- [Source: `_bmad-output/implementation-artifacts/spec-notif-owner-actions-employe.md:204-209`] — confirmation indépendante (même période que l'audit) que le scope EMPLOYEE sur les ventes est un choix produit assumé.
- [Source: `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/in/rest/DayClosureController.java:28-158`] — état actuel, class-level `@PreAuthorize`.
- [Source: `keevo/backend/src/main/java/com/keevo/store/store/adapter/in/rest/StoreController.java:1-107`] — état actuel, **aucun** `@PreAuthorize` présent.
- [Source: `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/in/web/StockController.java:1-185`] — état actuel, DTOs `storeId`.
- [Source: `keevo/backend/src/main/java/com/keevo/inventory/counting/adapter/in/rest/InventorySessionController.java:1-171`] — état actuel, pattern `validateInventory()` (modèle défense-en-profondeur).
- [Source: `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/in/web/StockTransferController.java:1-145`] — état actuel.
- [Source: `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/in/web/ProductController.java:1-215`] — état actuel, confirme cohérence avec HF-2 B4.
- [Source: `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtAuthFilter.java:182-225`] — mécanisme `Authentication.getDetails()` storeId EMPLOYEE (peuplé pour tous les controllers).
- [Source: `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/SecurityConfig.java:86-97`] — confirmation qu'aucune règle path-level n'existe pour les endpoints de cette story (tout passe par méthode-level).
- [Source: `keevo/backend/src/main/java/com/keevo/shared/domain/exception/ErrorCode.java:116-121`] — `FORBIDDEN` déjà défini.
- [Source: `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/GlobalExceptionHandler.java:52,94-95,149-153`] — mapping `DomainException(FORBIDDEN)` → 403 JSON.
- [Source: `keevo/backend/src/test/java/com/keevo/inventory/counting/adapter/in/rest/InventorySessionControllerValidateTest.java:118-125`] — modèle canonique de test 403 EMPLOYEE.
- [Source: `_bmad-output/implementation-artifacts/HF-2-stabilisation-online-rbac-employe-notifications-transferts.md:391-403`] — preuve décision HF-2 B4 (EMPLOYEE catalogue) déjà résolue.
- [Source: `keevo/app/lib/core/router/app_router.dart:296-314,532-536`] — `_ownerOnlyPrefixes`, commentaire HF-2 B4 déjà correct, routes `/pos/pending` absentes de la liste.
- [Source: `keevo/app/lib/features/catalog/presentation/widget/cross_store_availability_bottom_sheet.dart:348,437-472`] — `_EmployeeCta` déjà implémentée.
- [Source: `keevo/app/lib/features/inventory/presentation/page/transfer_history_page.dart:199-206`] — FAB « Nouveau transfert » non gaté.
- [Source: `keevo/app/lib/features/inventory/presentation/page/global_stock_overview_page.dart:106-107`] — pattern de gate `role != 'EMPLOYEE'` à répliquer.
- [Source: git log `8a3f06e` — « HF-2: Stabilisation Online — RBAC Employé, Notifications Transferts & Ventes Brouillons »] — commit qui a résolu AC8/une partie d'AC9 avant cette story.

## Dev Agent Record

### Agent Model Used

GitHub Copilot — DeepSeek V4 Pro (2026-07-21)

### Debug Log References

No debug logs — all tests run via standaloneSetup MockMvc (no Spring context required).

### Completion Notes List

**Décision D1 :** PRÉSERVÉ HF-2 (défaut). Les endpoints `GET /sales/pending` et `POST /sales/{id}/validate` restent `hasAnyRole('OWNER','EMPLOYEE')` avec scope storeId existant. Aucune modification de code — seuls les tests EMPLOYEE manquants ont été ajoutés (`PendingSaleControllerTest.GET_pending_employeeOwnStore_returns200`, `ValidateSaleServiceTest` ×4). **À trancher explicitement en code review** — l'option alternative (revert à OWNER-only, sous-tâches 1.1alt/1.2alt) est documentée et peut être appliquée si le PO le souhaite. Le `cancelSale` → OWNER-only est lui définitif et ferme le defer P2 HF-2.

**AC1 (PendingSaleController GET /sales/pending) :** PRÉSERVÉ HF-2. `getPendingSales` inchangé, test positif EMPLOYEE ajouté (11/11 PendingSaleControllerTest GREEN).

**AC2 (PendingSaleController validate + cancel) :** `validateSale` inchangé (préserver HF-2). 4 tests `ValidateSaleServiceTest` ajoutés (positif + négatif pour validate/cancel, 13/13 GREEN). `cancelSale` → `@PreAuthorize("hasRole('OWNER')")` + check programmatique, ferme defer P2 HF-2. Test 403 EMPLOYEE ajouté.

**AC3 (DayClosureController GET) :** `@PreAuthorize("hasRole('OWNER')")` méthode + check programmatique sur `getClosure()`. `closeDay()` (POST) inchangé. 7/7 DayClosureControllerTest GREEN incluant le nouveau test 403 EMPLOYEE.

**AC4 (StoreController) :** `@PreAuthorize("hasRole('OWNER')")` classe + `requireOwner()` dans les 4 méthodes. setUp() migré vers `authenticateAs("OWNER")` (était `List.of()`). 4 nouveaux tests 403 EMPLOYEE (create/list/update/deactivate), 1 test `withoutAuthContext` adapté (5xx → 403). 11/11 StoreControllerTest GREEN.

**AC5 (StockController scope storeId) :** `extractAssignedStoreId()` + check dans `recordStockEntry`/`adjustStock`. `GlobalExceptionHandler` ajouté à `StockControllerTest.setUp()` (seul fichier de test à ne pas l'avoir). 4 nouveaux tests (positif + négatif pour entry/adjust). 10/10 StockControllerTest GREEN.

**AC6 (InventorySessionController scope storeId) :** `extractAssignedStoreId()` + check dans `createSession`. 2 nouveaux tests (positif + négatif). 10/10 InventorySessionControllerTest GREEN.

**AC7 (StockTransferController initiate OWNER-only) :** `@PreAuthorize("hasRole('OWNER')")` méthode + check programmatique + `isOwnerRole()` sur `transfer()`. `complete()`/`getHistory()` inchangés (EMPLOYEE conservé). 1 test 403 initiate + 2 non-régression (complete/history pour EMPLOYEE). `CompleteTransferService` mock ajouté (manquait). 9/9 StockTransferControllerTest GREEN.

**AC8 (ProductController) :** Documentation seule. Décision HF-2 B4 (commit `8a3f06e`) déjà résolue — `ProductController` inchangé, commentaire `app_router.dart:296-300` déjà correct. Vérifié par lecture de code uniquement.

**AC9 (Flutter) :** `/pos/pending` ajouté à `_ownerOnlyPrefixes` dans `app_router.dart`. FAB « Nouveau transfert » gaté par `currentUserRoleProvider != 'EMPLOYEE'` dans `transfer_history_page.dart`. Import `core/di/providers.dart` ajouté.

**Non-régression :** 97/97 tests ciblés GREEN (PendingSaleControllerTest 11, ValidateSaleServiceTest 13, DayClosureControllerTest 7, StoreControllerTest 11, StockControllerTest 10, InventorySessionControllerTest 10, InventorySessionControllerValidateTest 5, StockTransferControllerTest 9, ProductControllerTest 8, MultiStoreStockControllerTest 8, QuickAddProductControllerTest 5). Suite complète : 1465 tests, 1 FAILURE (pré-existant flaky OnboardingServiceTest), 40 ERRORS (pré-existants DB-dépendants). **0 NEW régression.**

### File List

**Backend — modifiés (6 controllers) :**
- `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/in/rest/PendingSaleController.java` — ajout `isOwnerRole()`, `cancelSale` → `@PreAuthorize("hasRole('OWNER')")` + check programmatique, imports `DomainException`/`ErrorCode`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/in/rest/DayClosureController.java` — `@PreAuthorize("hasRole('OWNER')")` méthode sur `getClosure()` + check programmatique + `isOwnerRole()`, imports `DomainException`/`ErrorCode`/`Authentication`/`SecurityContextHolder`
- `keevo/backend/src/main/java/com/keevo/store/store/adapter/in/rest/StoreController.java` — `@PreAuthorize("hasRole('OWNER')")` classe + `requireOwner()` dans 4 méthodes, imports `DomainException`/`ErrorCode`/`PreAuthorize`/`Authentication`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/in/web/StockController.java` — `extractAssignedStoreId()` + check dans `recordStockEntry`/`adjustStock`, imports `DomainException`/`ErrorCode`
- `keevo/backend/src/main/java/com/keevo/inventory/counting/adapter/in/rest/InventorySessionController.java` — `extractAssignedStoreId()` + check dans `createSession()`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/in/web/StockTransferController.java` — `@PreAuthorize("hasRole('OWNER')")` méthode sur `transfer()` + check programmatique + `isOwnerRole()`, imports `DomainException`/`ErrorCode`/`Authentication`

**Backend — non modifié (documentation seule) :**
- `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/in/web/ProductController.java` — AUCUN changement (AC8, décision HF-2 B4 déjà résolue)

**Backend — tests modifiés (7 fichiers) :**
- `keevo/backend/src/test/java/com/keevo/commerce/sale/adapter/in/rest/PendingSaleControllerTest.java` — helper `authenticateAsEmployee(storeId)`, 2 nouveaux tests (GET EMPLOYEE positif, POST cancel 403)
- `keevo/backend/src/test/java/com/keevo/commerce/sale/application/ValidateSaleServiceTest.java` — 4 nouveaux tests EMPLOYEE scope (validate/cancel × positif/négatif)
- `keevo/backend/src/test/java/com/keevo/commerce/sale/adapter/in/rest/DayClosureControllerTest.java` — 1 nouveau test GET 403 EMPLOYEE
- `keevo/backend/src/test/java/com/keevo/store/store/adapter/in/rest/StoreControllerTest.java` — helper `authenticateAs(role)`, setUp → OWNER auth, 4 nouveaux tests 403 EMPLOYEE, 1 test adapté (5xx → 403), imports `SimpleGrantedAuthority`
- `keevo/backend/src/test/java/com/keevo/catalog/stock/adapter/in/web/StockControllerTest.java` — `.setControllerAdvice(new GlobalExceptionHandler())` dans setUp, helper `authenticateAsEmployee(storeId)`, 4 nouveaux tests (entry/adjust × positif/négatif), imports `GlobalExceptionHandler`/`SimpleGrantedAuthority`/`verify`/`never`
- `keevo/backend/src/test/java/com/keevo/inventory/counting/adapter/in/rest/InventorySessionControllerTest.java` — 2 nouveaux tests (createSession EMPLOYEE positif/négatif)
- `keevo/backend/src/test/java/com/keevo/catalog/stock/adapter/in/web/StockTransferControllerTest.java` — helper `authenticateAs(role)`, setUp → OWNER auth, `CompleteTransferService` mock ajouté, 1 test 403 initiate + 2 non-régression (complete/history EMPLOYEE), imports `SimpleGrantedAuthority`/`CompleteTransferService`/`verify`/`never`

**Flutter — modifiés (2 fichiers) :**
- `keevo/app/lib/core/router/app_router.dart` — ajout `'/pos/pending'` à `_ownerOnlyPrefixes`
- `keevo/app/lib/features/inventory/presentation/page/transfer_history_page.dart` — FAB « Nouveau transfert » gaté `role != 'EMPLOYEE'`, import `core/di/providers.dart`

**Sprint tracking :**
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — `v1s-12-6-rbac-owner-only-scope-store` : `ready-for-dev` → `in-progress` → `review`

### Review Findings

- [x] [Review][Patch] Retirer `/pos/pending` de `_ownerOnlyPrefixes` — `app_router.dart:314` (résolu en code review : décision D-router = option 1). L'ajout de `/pos/pending` (Task 8.1) supposait à tort que `GET /sales/pending` devenait OWNER-only côté backend ; la Décision D1 de cette story a en réalité préservé HF-2 (`getPendingSales` reste `hasAnyRole('OWNER','EMPLOYEE')` avec scope storeId). Retirer l'entrée pour que l'EMPLOYEE puisse accéder à ses ventes en attente scopées à sa boutique, cohérent avec ce que le backend sert et teste déjà (`PendingSaleControllerTest.GET_pending_employeeOwnStore_returns200`).

- [x] [Review][Patch] `extractAssignedStoreId()` fail-open sur claim `storeId` absente — `StockController.java` (méthode ajoutée par cette story) et `InventorySessionController.java` (idem) : `JwtTokenProvider.extractStoreId()` (`JwtTokenProvider.java:222-223`) retourne `null` si le claim JWT `storeId` est absent, y compris pour un rôle EMPLOYEE (ex. token malformé, employé temporairement désassigné). `extractAssignedStoreId()` traite alors ce `null` exactement comme un token OWNER (non scopé) — le check `assignedStoreId != null && !assignedStoreId.equals(request.storeId())` est silencieusement sauté et l'EMPLOYEE obtient un accès stock/inventaire non scopé, à l'opposé de l'intention AC5/AC6 (FR36). Ce pattern est copié tel quel depuis `PendingSaleController` (préexistant, HF-2) sur instruction explicite de la story — mais cette story l'étend à 2 nouvelles surfaces sensibles (mouvements de stock, sessions d'inventaire). Correctif recommandé (non ambigu) : dans les 2 nouvelles copies, si l'authentification porte `ROLE_EMPLOYEE` et que `extractAssignedStoreId()` ne résout pas de UUID, lever `DomainException(ErrorCode.FORBIDDEN, ...)` plutôt que de traiter comme non scopé.

- [x] [Review][Defer] `/api/v1/sync/push` contourne intégralement tout le durcissement RBAC de cette story [keevo/backend/.../sync/sync/application/handler/{CancelSaleSyncHandler,ValidateSaleSyncHandler,StockAdjustSyncHandler,TransferSyncHandler}.java] — deferred, pre-existing. `CancelSaleSyncHandler.apply()` appelle `cancelPendingSaleUseCase.cancelPendingSale(...)` avec `storeId=null` codé en dur et **aucun contrôle de rôle** — un EMPLOYEE peut annuler n'importe quelle vente de n'importe quelle boutique via un op `CANCEL_SALE` en sync, contournant entièrement le nouveau `@PreAuthorize("hasRole('OWNER')")` + `isOwnerRole()` posé sur `PendingSaleController.cancelSale()` (defer P2 HF-2 en réalité toujours ouvert via ce chemin). `ValidateSaleSyncHandler` passe aussi `null` comme `assignedStoreId` à `ValidateSaleCommand` — le scope HF-2 AC5 dont Décision D1 affirme qu'il « est réel et fonctionne » n'est PAS appliqué côté sync. `StockAdjustSyncHandler` (types `STOCK_ADJUST`/`RECORD_STOCK_ENTRY`) et `TransferSyncHandler` appellent leurs use cases directement avec le `storeId`/les IDs boutique fournis par le client, sans aucune comparaison au store assigné ni aucun `@PreAuthorize` — contournant respectivement AC5 et AC7. Ces 4 fichiers ne sont touchés par aucune tâche ni mentionnés dans le File List de cette story (pré-existants, hors diff) — d'où le classement en defer strict — **mais ce contournement est critique et annule une bonne partie de la valeur sécurité de Story 12.6** : tant que ce chemin n'est pas corrigé, un EMPLOYEE malveillant ou un client mobile compromis peut reproduire exactement les comportements que cette story visait à bloquer, simplement en passant par la synchronisation offline plutôt que par les endpoints REST directs. Recommandation forte : ouvrir une story de suivi immédiate (priorité équivalente à un release-blocker, dans la continuité de l'Epic 12) pour appliquer les mêmes contrôles de rôle/scope dans les 4 handlers de sync.

- [x] [Review][Defer] `StockTransferController.complete()` (réception, étape 2) sans aucun scope storeId [keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/in/web/StockTransferController.java:99-107] — deferred, pre-existing. Le texte de l'AC7 justifie de laisser `complete()` à `hasAnyRole('OWNER','EMPLOYEE')` par « l'employé à la boutique **destination** doit pouvoir réceptionner » — mais ni le controller ni `CompleteTransferService` ne comparent jamais la boutique assignée de l'EMPLOYEE à `transfer.getDestinationStoreId()`. En l'état, n'importe quel EMPLOYEE authentifié (de n'importe quelle boutique) peut compléter/réceptionner n'importe quel transfert `IN_TRANSIT`, y compris ceux destinés à une boutique qui n'est pas la sienne — contraire à FR36. Code préexistant (Story 3.3), explicitement non modifié par cette story (Task 6.3 « Ne pas toucher `complete()` ») — d'où le classement en defer — mais directement lié à FR36/B-HIGH-9/10, à traiter dans une story de suivi.

- [x] [Review][Defer] Duplication du helper de contrôle de rôle `isOwnerRole()`/`requireOwner()` [PendingSaleController.java, DayClosureController.java, StoreController.java, StockTransferController.java] — deferred, pre-existing pattern. Logique identique (`SecurityContextHolder` + `"ROLE_OWNER".equals(...)`) copiée-collée dans 4 controllers plutôt que factorisée. Cohérent avec le principe « scope chirurgical, pas de refactor transverse » déjà validé en code review Story 12.5 — pas bloquant, mais à regrouper dans une future story de nettoyage transverse si un 5ᵉ controller OWNER-only apparaît.
