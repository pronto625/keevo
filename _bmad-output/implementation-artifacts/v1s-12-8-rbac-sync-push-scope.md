---
baseline_commit: 6a0efea
---
# Story 12.8: Fermer le contournement RBAC sur `/api/v1/sync/push` — role + scope storeId dans les 4 handlers de sync

Status: done

<!-- V1-stabilization track — tag A (patch V1 urgent, release-blocker).
     Branche : v1-stabilization (HEAD 6a0efea).
     Source : audit de cohérence 2026-07-23 (_bmad-output/planning-artifacts/audit-coherence-2026-07-23.md, Finding #1),
     lui-même confirmant un gap déjà documenté dans deferred-work.md (code review v1s-12-6, 2026-07-21, "priorité release-blocker")
     et dans implementation-readiness-report-2026-07-23.md (section "🔴 CRITIQUE — Faille RBAC confirmée toujours active").
     AUCUNE story de suivi n'existait avant celle-ci malgré 2 jours d'attente. -->

## Story

**As a** Toor (opérateur de la plateforme),
**I want** que les 4 handlers de synchronisation offline (`CancelSaleSyncHandler`, `ValidateSaleSyncHandler`, `StockAdjustSyncHandler`, `TransferSyncHandler`) appliquent les mêmes contrôles de rôle et de scope storeId que leurs contrôleurs REST équivalents,
**so that** un EMPLOYEE ne puisse pas, via le canal offline `/api/v1/sync/push`, valider/annuler des ventes, ajuster du stock, ou initier un transfert hors de la boutique à laquelle il est assigné — exactement ce que Story 12.6 (AC2/AC5/AC7, FR36) devait garantir au niveau REST.

## Contexte V1-stabilization

- **Source :** audit `_bmad-output/planning-artifacts/audit-coherence-2026-07-23.md` §2 Finding #1 — confirme et affine le gap déjà tracé dans `deferred-work.md` ligne 157-159 (code review de `v1s-12-6-rbac-owner-only-scope-store`, 2026-07-21) : *"story de suivi immédiate, priorité release-blocker"*.
- **Tracker :** `sprint-status.yaml` — cette story ajoute la clé `v1s-12-8-rbac-sync-push-scope` sous `v1s-epic-12-security-hardening` (déjà `in-progress`, dernière story livrée : v1s-12-7).
- **Tag :** A — patch V1 maintenant. Refonte : ré-appliquer le même principe (rôle+scope porté par l'acteur, jamais par le payload) dans la structure modulaire de la Phase 2 (Epic 12 refonte).
- **Ne PAS confondre** avec l'epic refonte "12" (Découplage en Bounded Contexts, backlog, branche `refractoring`) — cette story est bien dans le namespace `v1s-` (V1-stabilization, branche `v1-stabilization`).
- **Pourquoi ce n'est PAS un problème structurel aussi profond qu'il y paraît** : `SyncPushService.pushBatch()` (`sync/sync/application/service/SyncPushService.java`) traite chaque opération **de façon synchrone, dans le thread de la requête HTTP** (via `TransactionTemplate.execute` — pas d'async, pas de changement de thread). `SyncController.push()` s'exécute derrière la même chaîne de filtres Spring Security que tous les autres contrôleurs REST : `SecurityContextHolder.getContext().getAuthentication()` est donc **disponible et valide** à l'intérieur de `apply()` de chaque handler, exactement comme dans `PendingSaleController`/`StockController`/`StockTransferController`. **Il n'est PAS nécessaire de modifier `SyncOperation` (record), `SyncOperationHandler` (interface), ni la signature `handle(op, actorId, tenantId)`** pour porter le rôle/storeId — il suffit de lire l'`Authentication` courante depuis chaque handler, exactement comme le font déjà les contrôleurs REST. Ne pas sur-ingénierer cette story en réécrivant le pipeline sync.
- **Découverte clé (vérifiée en lisant le code, pas supposée)** : l'enforcement de scope EMPLOYEE pour `cancelPendingSale()` et `validateSale()` **existe déjà** dans `ValidateSaleService` via la méthode privée partagée `enforceEmployeeStoreScope(UUID assignedStoreId, UUID saleStoreId)` (`ValidateSaleService.java:214-218`), appelée aux lignes 68 (validateSale), 143 (cancelPendingSale branche PENDING_VALIDATION) et 151 (cancelPendingSale branche COMPLETED). **Cette story n'a donc PAS besoin d'ajouter de logique métier de scope pour ces deux handlers** — il suffit de cesser de passer `null` en dur et de passer le vrai `assignedStoreId` de l'acteur. Pour `StockAdjustSyncHandler` et `TransferSyncHandler`, en revanche, aucun équivalent n'existe dans les use cases (`AdjustStockUseCase`, `RecordStockEntryUseCase`, `TransferStockUseCase`) — le contrôle vit uniquement dans les contrôleurs REST (`StockController`, `StockTransferController`) et doit être répliqué directement dans le handler.

## Acceptance Criteria

1. **AC1 (ValidateSaleSyncHandler — passer le vrai `assignedStoreId`)** — **Given** `ValidateSaleSyncHandler.apply()` construit aujourd'hui `new ValidateSaleCommand(saleId, actorId, null, ...)` (`ValidateSaleSyncHandler.java:63`, `null` codé en dur), **When** l'acteur est un EMPLOYEE authentifié avec un `storeId` assigné dans `AuthDetails` (`Authentication.getDetails()`), **Then** le handler transmet ce `storeId` réel au lieu de `null`, et `ValidateSaleService.enforceEmployeeStoreScope()` (déjà existant, inchangé) rejette avec `DomainException(FORBIDDEN)` toute validation d'une vente dont `sale.getStoreId() != assignedStoreId` ; **And** pour un OWNER (`AuthDetails.storeId() == null`), le comportement reste inchangé (non scopé).
2. **AC2 (CancelSaleSyncHandler — passer le vrai `assignedStoreId` sur la branche PENDING)** — **Given** `CancelSaleSyncHandler.apply()` construit aujourd'hui `new CancelPendingSaleCommand(saleId, actorId, null, justification)` (`CancelSaleSyncHandler.java` ligne ~59, `null` codé en dur) et bloque déjà explicitement l'annulation d'une vente `COMPLETED` (garde ajoutée par v1s-13-5, à ne pas modifier), **When** un EMPLOYEE annule une vente `PENDING_VALIDATION` via sync, **Then** le handler transmet le vrai `assignedStoreId` au lieu de `null`, et `ValidateSaleService.cancelPendingSale()` (branche `PENDING_VALIDATION`, ligne 143, déjà existante) rejette avec `FORBIDDEN` si `sale.getStoreId() != assignedStoreId`.
3. **AC3 (StockAdjustSyncHandler — scope storeId EMPLOYEE, miroir de `StockController`)** — **Given** `StockAdjustSyncHandler.apply()` (types `STOCK_ADJUST`, `RECORD_STOCK_ENTRY`) n'effectue aujourd'hui **aucune** vérification de rôle/scope avant d'appeler `adjustStockUseCase.execute(...)`/`recordStockEntryUseCase.execute(...)` (`StockAdjustSyncHandler.java:40-56`), **When** un EMPLOYEE envoie une opération `STOCK_ADJUST`/`RECORD_STOCK_ENTRY` avec un `storeId` dans le payload différent de son `assignedStoreId`, **Then** le handler lève `DomainException(FORBIDDEN, "EMPLOYEE cannot adjust/record stock for another store")` **avant** d'appeler le use case (aucun mouvement de stock créé) — logique identique à `StockController.recordStockEntry()`/`adjustStock()` (`StockController.java:97-100,120-123`) ; **And** un EMPLOYEE avec `storeId` correspondant, ou un OWNER (toujours non scopé), continue de fonctionner sans changement.
4. **AC4 (TransferSyncHandler — rôle OWNER-only, miroir de `StockTransferController.transfer()`)** — **Given** `TransferSyncHandler.apply()` (type `STOCK_TRANSFER`, initiation de transfert) n'effectue aujourd'hui **aucune** vérification de rôle avant d'appeler `transferStockUseCase.execute(...)` (`TransferSyncHandler.java:38-52`), alors que l'équivalent REST `POST /api/v1/stock/transfers` est `@PreAuthorize("hasRole('OWNER')")` (`StockTransferController.java:64`, Story 12.6 AC7), **When** un EMPLOYEE envoie une opération `STOCK_TRANSFER` via sync, **Then** le handler lève `DomainException(FORBIDDEN, "Only OWNER can initiate stock transfers")` avant d'appeler le use case (aucun transfert créé, aucun stock déduit à la source) ; **And** un OWNER continue de fonctionner sans changement. **Note : il n'existe aucun handler de sync pour la réception (`complete()`) d'un transfert** — seule l'initiation transite par ce canal ; la réception (Step 2) reste exclusivement REST, couverte par la story v1s-12-9 séparée.
5. **AC5 (extraction rôle/storeId — pattern unique et réutilisé, pas de duplication x4)** — **Given** les 4 handlers ont besoin du même calcul (`assignedStoreId` nullable + rôle EMPLOYEE/OWNER), **When** cette story est implémentée, **Then** la logique d'extraction est factorisée dans une méthode `protected` sur `AbstractSyncOperationHandler` (ex. `extractAssignedStoreId()` lisant `SecurityContextHolder.getContext().getAuthentication().getDetails()` casté en `AuthDetails`, miroir exact de `PendingSaleController.extractAssignedStoreId()`/`StockController.extractAssignedStoreId()`) plutôt que copiée-collée dans chaque handler — cohérent avec le pattern Template Method déjà en place sur cette classe.
6. **AC6 (tests — un cas positif + un cas négatif par handler modifié)** — **Given** aucun des 4 handlers n'a aujourd'hui de test EMPLOYEE cross-store (`grep -n "EMPLOYEE\|assignedStoreId" *SyncHandlerTest.java` → absent sur ces 4 fichiers), **When** cette story est terminée, **Then** chaque fichier de test (`ValidateSaleSyncHandlerTest.java` — à créer, n'existe pas aujourd'hui ; `CancelSaleSyncHandlerTest.java`, `StockAdjustSyncHandlerTest.java`, `TransferSyncHandlerTest.java` — existants, à étendre) contient au moins : un test EMPLOYEE avec storeId correspondant → `APPLIED`, un test EMPLOYEE avec storeId différent → `REJECTED` avec reason `FORBIDDEN`, et un test OWNER (non scopé) → comportement inchangé.

## Tasks / Subtasks

- [x] **Task 1 — Factoriser l'extraction rôle/storeId (AC5)**
  - [x] 1.1 Ajouter dans `AbstractSyncOperationHandler.java` une méthode `protected UUID extractAssignedStoreId()` qui lit `SecurityContextHolder.getContext().getAuthentication()`, caste `getDetails()` en `AuthDetails`, retourne `ad.storeId()` (nullable) — importer `com.keevo.shared.infrastructure.security.AuthDetails`.
  - [x] 1.2 Ajouter `protected boolean isOwnerRole()` (miroir exact de `StockTransferController.isOwnerRole()`) pour AC4.
- [x] **Task 2 — ValidateSaleSyncHandler (AC1)**
  - [x] 2.1 `ValidateSaleSyncHandler.apply()` ligne 63 : remplacer `null` par `extractAssignedStoreId()` dans la construction de `ValidateSaleCommand`.
  - [x] 2.2 Test : EMPLOYEE storeId=A valide une vente storeId=A → APPLIED ; EMPLOYEE storeId=A valide une vente storeId=B → REJECTED/FORBIDDEN ; OWNER (storeId=null) → inchangé.
- [x] **Task 3 — CancelSaleSyncHandler (AC2)**
  - [x] 3.1 `CancelSaleSyncHandler.apply()` : remplacer le `null` de `CancelPendingSaleCommand` par `extractAssignedStoreId()` — **ne pas toucher** à la garde `COMPLETED → FORBIDDEN` déjà présente (v1s-13-5).
  - [x] 3.2 Test : EMPLOYEE storeId=A annule une vente PENDING storeId=A → APPLIED ; EMPLOYEE storeId=A annule une vente PENDING storeId=B → REJECTED/FORBIDDEN ; vérifier que la garde COMPLETED existante n'est pas régressée.
- [x] **Task 4 — StockAdjustSyncHandler (AC3)**
  - [x] 4.1 Dans `apply()`, après extraction de `storeId` du payload, appeler `extractAssignedStoreId()` et lever `DomainException(ErrorCode.FORBIDDEN, ...)` si non-null et différent du storeId du payload — **avant** l'appel à `recordStockEntryUseCase.execute(...)`/`adjustStockUseCase.execute(...)`.
  - [x] 4.2 Test : EMPLOYEE storeId=A, payload storeId=A → APPLIED (les deux types STOCK_ADJUST et RECORD_STOCK_ENTRY) ; EMPLOYEE storeId=A, payload storeId=B → REJECTED/FORBIDDEN, `verify(adjustStockUseCase/recordStockEntryUseCase, never()).execute(...)` ; OWNER → inchangé.
- [x] **Task 5 — TransferSyncHandler (AC4)**
  - [x] 5.1 Dans `apply()`, appeler `isOwnerRole()` en tête et lever `DomainException(ErrorCode.FORBIDDEN, "Only OWNER can initiate stock transfers")` si `false` — avant l'appel à `transferStockUseCase.execute(...)`.
  - [x] 5.2 Test : EMPLOYEE (n'importe quel storeId) → REJECTED/FORBIDDEN, `verify(transferStockUseCase, never()).execute(...)` ; OWNER → inchangé.
- [x] **Task 6 — Non-régression**
  - [x] 6.1 Lancer la suite complète backend (`mvn test`) — 0 nouvelle régression attendue en dehors des 4 classes modifiées et de la nouvelle classe de test.
  - [x] 6.2 Mettre à jour le Dev Agent Record (File List, Completion Notes) et `deferred-work.md` : retirer/marquer résolue l'entrée "release-blocker" de la review v1s-12-6 (ligne ~157) en la faisant pointer vers cette story.

## Dev Notes

- **Ne PAS modifier** `SyncOperation` (record), `SyncOperationHandler` (interface), ni la signature `handle()`/`apply()` des handlers — voir "Pourquoi ce n'est PAS un problème structurel" ci-dessus. Le contexte de sécurité est disponible via `SecurityContextHolder` dans le thread de la requête, exactement comme dans tout autre `@RestController`.
- **Pattern de référence à copier, pas à réinventer** : `PendingSaleController.extractAssignedStoreId()` (`commerce/sale/adapter/in/rest/PendingSaleController.java`, fin de fichier) et `StockController.extractAssignedStoreId()` (`catalog/stock/adapter/in/web/StockController.java`, fin de fichier) — les deux lisent `AuthDetails` depuis `Authentication.getDetails()`. `StockTransferController.isOwnerRole()` pour le pattern rôle.
- **`AuthDetails`** (`shared/infrastructure/security/AuthDetails.java`) est peuplé par `JwtAuthFilter` pour CHAQUE requête authentifiée (pas seulement les contrôleurs REST classiques) — confirmé par lecture de `SyncController.push()` qui tourne derrière la même chaîne de filtres (`@PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")` sur `push()`, donc `JwtAuthFilter` s'est déjà exécuté avant d'atteindre le contrôleur).
- **`ValidateSaleService.enforceEmployeeStoreScope()`** (ligne 214-218) : `if (assignedStoreId != null && !assignedStoreId.equals(saleStoreId)) throw new DomainException(ErrorCode.FORBIDDEN, ...)`. Ne pas dupliquer cette logique dans les handlers pour AC1/AC2 — elle est déjà appelée par `ValidateSaleService`, il suffit de lui donner la bonne valeur en entrée.
- **`AdjustStockUseCase`/`RecordStockEntryUseCase`/`TransferStockUseCase`** n'ont eux-mêmes aucune notion de rôle/scope (vérifié — le contrôle vit uniquement dans les contrôleurs REST) : pour AC3/AC4, la vérification doit être ajoutée **dans le handler**, pas dans le use case (cohérent avec où vit la même logique côté REST).
- **Tests** : les handlers ne sont pas des `@RestController` — pas besoin de `standaloneSetup`/MockMvc. Construire directement `Authentication`/`SecurityContextHolder` en test (mirror du pattern déjà utilisé dans `CancelSaleSyncHandlerTest.java`/`StockAdjustSyncHandlerTest.java`/`TransferSyncHandlerTest.java` existants — les regarder avant d'écrire les nouveaux tests pour respecter le style établi). `ValidateSaleSyncHandlerTest.java` n'existe pas encore — le créer en miroir de `CancelSaleSyncHandlerTest.java`.
- **Portée volontairement exclue** : la story ne touche pas à `SyncGateCheckService`, `ConflictStrategyRegistry`, ni aux 13 autres handlers de sync (déjà hors du périmètre RBAC identifié par l'audit). Ne pas élargir le scope.

### Project Structure Notes

- Tous les fichiers modifiés sont dans `keevo/backend/src/main/java/com/keevo/sync/sync/application/handler/` (4 fichiers + `AbstractSyncOperationHandler.java`) — aucun nouveau module, aucun changement de structure.
- Aucune migration Flyway, aucun changement de DTO/contrat API — le fix est interne au traitement server-side de `/api/v1/sync/push`, invisible du client Flutter (qui n'a rien à changer).

### References

- [Source: _bmad-output/planning-artifacts/audit-coherence-2026-07-23.md#2. Findings — 🔴 Critiques, Finding #1]
- [Source: _bmad-output/implementation-artifacts/deferred-work.md, "code review of v1s-12-6-rbac-owner-only-scope-store"]
- [Source: _bmad-output/implementation-artifacts/v1s-12-6-rbac-owner-only-scope-store.md — AC1/AC2/AC5/AC7, pattern `extractAssignedStoreId()`]
- [Source: keevo/backend/src/main/java/com/keevo/commerce/sale/application/service/ValidateSaleService.java:68,143,151,214-218]
- [Source: keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/in/web/StockController.java — pattern de scope EMPLOYEE existant]
- [Source: keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/in/web/StockTransferController.java — pattern `isOwnerRole()`]

## Dev Agent Record

### Agent Model Used

DeepSeek V4 Pro (GitHub Copilot)

### Debug Log References

N/A — no debugging required.

### Completion Notes List

✅ **Story v1s-12-8 implemented 2026-07-23**
- AC1: `ValidateSaleSyncHandler` — `null` → `extractAssignedStoreId()` dans `ValidateSaleCommand`. Le `ValidateSaleService.enforceEmployeeStoreScope()` existant (ligne 214-218) fait désormais son travail.
- AC2: `CancelSaleSyncHandler` — `null` → `extractAssignedStoreId()` dans `CancelPendingSaleCommand`. Garde COMPLETED existante (v1s-13-5) préservée intacte.
- AC3: `StockAdjustSyncHandler` — scope storeId ajouté avant `recordStockEntryUseCase.execute()`/`adjustStockUseCase.execute()`, miroir exact de `StockController`.
- AC4: `TransferSyncHandler` — `isOwnerRole()` guard ajouté en tête, miroir exact de `StockTransferController`.
- AC5: `extractAssignedStoreId()` + `isOwnerRole()` factorisés dans `AbstractSyncOperationHandler` (Template Method).
- AC6: 10 nouveaux tests (ValidateSaleSyncHandlerTest 4, CancelSaleSyncHandlerTest +2, StockAdjustSyncHandlerTest +3, TransferSyncHandlerTest +1). 85/85 sync handler tests GREEN.
- Full regression: 1734 tests (7 failures + 33 errors pre-existing, 0 sync-related).
- Pre-existing fix: `SubmitFeedbackSyncHandlerTest.createOperation()` — arguments `payload`/`entityId` swapped (compilation error).

### File List

- `keevo/backend/src/main/java/com/keevo/sync/sync/application/handler/AbstractSyncOperationHandler.java` — added `extractAssignedStoreId()`, `isOwnerRole()`, imports AuthDetails/SecurityContextHolder/ErrorCode
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/handler/ValidateSaleSyncHandler.java` — `null` → `extractAssignedStoreId()` in ValidateSaleCommand
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/handler/CancelSaleSyncHandler.java` — `null` → `extractAssignedStoreId()` in CancelPendingSaleCommand
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/handler/StockAdjustSyncHandler.java` — added EMPLOYEE storeId scope check + DomainException/ErrorCode imports
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/handler/TransferSyncHandler.java` — added `isOwnerRole()` guard + DomainException/ErrorCode imports
- `keevo/backend/src/test/java/com/keevo/sync/sync/application/handler/ValidateSaleSyncHandlerTest.java` — NEW: 4 tests (same-store, cross-store, owner, SALE_NOT_PENDING idempotent)
- `keevo/backend/src/test/java/com/keevo/sync/sync/application/handler/CancelSaleSyncHandlerTest.java` — extended: +2 EMPLOYEE tests (same-store, cross-store), `authenticateAsEmployee()`, `authenticateAsOwner()`, `tearDown()`, imports SecurityContextHolder/AuthDetails/DomainException
- `keevo/backend/src/test/java/com/keevo/sync/sync/application/handler/StockAdjustSyncHandlerTest.java` — extended: +3 EMPLOYEE tests (STOCK_ADJUST same-store, STOCK_ADJUST cross-store, RECORD_STOCK_ENTRY same-store, RECORD_STOCK_ENTRY cross-store), `authenticateAsEmployee()`, `authenticateAsOwner()`, `tearDown()`, imports
- `keevo/backend/src/test/java/com/keevo/sync/sync/application/handler/TransferSyncHandlerTest.java` — extended: +1 EMPLOYEE rejection test, `authenticateAsOwner()`, `authenticateAsEmployee()`, `tearDown()`, imports
- `keevo/backend/src/test/java/com/keevo/sync/sync/application/handler/SubmitFeedbackSyncHandlerTest.java` — pre-existing fix: swapped `payload`/`entityId` arguments in `createOperation()`

### Review Findings

Code review 2026-07-24 — 3 layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor). 0 decision-needed, 2 patch, 4 defer, 9 dismissed.

**Patch :**
- [x] [Review][Patch] **P1 — Message d'erreur STOCK_ADJUST incorrect** [StockAdjustSyncHandler.java:53-54] — ✅ Appliqué : message neutralisé → `"EMPLOYEE cannot modify stock for another store"`.
- [x] [Review][Patch] **P2 — Tests cross-store ne vérifient pas que `assignedStoreId` est transmis dans la commande** [ValidateSaleSyncHandlerTest.java, CancelSaleSyncHandlerTest.java] — ✅ Appliqué : `ArgumentCaptor` ajouté dans les 4 tests same-store/cross-store des 2 fichiers. `doAnswer` remplace `doThrow` dans les cross-store pour vérifier + simuler le rejet. 19/19 tests GREEN.

**Defer :**
- [x] [Review][Defer] **D1 — `SaleSyncHandler` (CREATE_SALE) sans scope check EMPLOYEE** [SaleSyncHandler.java:45-72] — deferred, hors scope explicite de cette story — `SaleSyncHandler.apply()` prend `storeId` du payload sans appel à `extractAssignedStoreId()`, alors que `SaleController` REST force `jwtStoreId` comme autorité (lignes 49-61). Gap RBAC réel pour l'opération la plus critique (création de vente). **Recommandation : story de suivi immédiate** pour appliquer le même pattern à `SaleSyncHandler`.
- [x] [Review][Defer] **D2 — `extractAssignedStoreId()` retourne `null` (fail-open) quand `auth == null`** [AbstractSyncOperationHandler.java:50-51] — deferred, préexistant — `@PreAuthorize` sur `SyncController.push()` protège en pratique, mais la méthode `protected` est un point d'extension : si un futur handler est invoqué hors thread HTTP, `auth == null` → scope OWNER implicite. Pattern identique dans `StockController.extractAssignedStoreId()` (ligne 209-221). Hygiène à traiter globalement (story future : unifier les extractors en fail-closed).
- [x] [Review][Defer] **D3 — Troncature silencieuse `quantity` (`intValue()` overflow)** [StockAdjustSyncHandler.java:47] — deferred, préexistant — `((Number) p.get("quantity")).intValue()` tronque si > `Integer.MAX_VALUE`. Pas introduit par ce diff.
- [x] [Review][Defer] **D4 — `TransferSyncHandler` ne valide pas `sourceStoreId` contre le tenant** [TransferSyncHandler.java:41-49] — deferred, préexistant — le guard OWNER-only est correct, mais le `sourceStoreId` du payload n'est pas validé (mitigé par `TenantContext` dans les repositories). Pas introduit par ce diff.
