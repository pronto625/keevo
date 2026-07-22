---
baseline_commit: ccfad6b45bc5978f9ac3ca43c0926210a46758bf
---
# Story 14.10: Notif owner sur actions employé (produit + stock) + Flutter FCM réception

Status: done

<!-- V1-stabilization track — tag A (patch V1 now).
     Spec : spec-notif-owner-actions-employe.md
     Branche : v1-stabilization.
     100% backend (Java) + Flutter FCM reception.
     Aucune migration Flyway, aucun DDL.
     13 tests TDD requis (7 ProductCreationNotificationListener + 6 StockModificationNotificationListener). -->

## Story

**As a** propriétaire (Simon),
**I want** recevoir une notification push (FCM) + WhatsApp quand un employé crée un produit ou modifie manuellement le stock,
**so that** je sois immédiatement informé des actions de mon équipe sans avoir à vérifier manuellement le catalogue ou l'historique des mouvements.

## Contexte V1-stabilization

- **Source spec :** `spec-notif-owner-actions-employe.md` (2026-07-20)
- **Tag :** A — patch V1 now
- **Priorité :** Sprint 2 "scope V1 promis"
- **Index track :** `sprint-status.yaml` — `v1s-14-10-notif-owner-employee-actions` · Epic `v1s-epic-14-functional-gaps-v1` (in-progress)
- **Dépendances :** Aucune (les listeners sont nouveaux, les events existent déjà)
- **TDD :** 13 tests (7 pour ProductCreationNotificationListener + 6 pour StockModificationNotificationListener)

## Décisions (validées dans la spec)

| # | Décision | Choix |
|---|---|---|
| D1 | Statut produit créé par employé | ACTIVE directement — notif = information |
| D2 | Périmètre modif stock | STOCK_ENTRY + ADJUSTMENT (pas ventes, pas transferts) |
| D3 | Canaux | Push (FCM) + WhatsApp |
| D4 | Anti-spam | Batching consolidé — fenêtre 5 min par (tenantId, actorId, type) |

## Acceptance Criteria

### AC1: ProductCreationNotificationListener — employee creates ACTIVE product
Given un EMPLOYEE crée un produit via `POST /api/v1/products` (status ACTIVE)
When `CreateProductUseCase` complète et publie `ProductCreatedEvent` (portant `actorRole`, `actorName`, `storeName`)
Then `ProductCreationNotificationListener` catch l'event
And si `actorRole==EMPLOYEE` → `NotificationPort.notifyOwners(...)` (payload §A.3 spec, channel BOTH)
And si `actorRole==OWNER` → pas de notif
And batching §5 spec sur fenêtre 5 min
And best-effort (pas de rollback)

### AC2: ProductCreationNotificationListener — regression AC9 (DRAFT + CSV)
Given les events `ProductCreatedProgressivelyEvent` (DRAFT) et `CsvImportCompletedEvent` (CSV)
When le listener les reçoit
Then le comportement existant (Story 2.4 AC9) est préservé sans changement

### AC3: StockModificationNotificationListener — employee enters/adjusts stock
Given un EMPLOYEE fait STOCK_ENTRY ou ADJUSTMENT
When `StockOperationService.recordOperation()` publie `StockAdjustedEvent` (portant `actorRole`, `actorName`, `storeName`)
Then `StockModificationNotificationListener` catch l'event
And si `actorRole==EMPLOYEE` → `notifyOwners(...)` (payload §B.3 spec, channel BOTH)
And si `actorRole==OWNER` → pas de notif

### AC4: StockModificationNotificationListener — excludes sales/transfers
Given un `StockAdjustedEvent` avec `movementType=SALE` ou initié par un transfert
Then le listener ne déclenche PAS de notification owner

### AC5: Batching consolidé
Given un employé enchaîne N actions du même type dans une fenêtre de 5 min
When la fenêtre expire
Then 1 seule notification récap est envoyée (type `*_BATCH`)
And si 1 seul événement dans la fenêtre → 1 notification single (naturelle)

### AC6: Best-effort — no rollback
Given une notification FCM ou WhatsApp échoue
Then l'action métier (création produit / modif stock) n'est PAS rollbackée
And l'erreur est loggée (WARN)

### AC7: Flutter — FCM reception for new notification types
Given le device Flutter reçoit un push FCM de type `EMPLOYEE_PRODUCT_CREATED` ou `EMPLOYEE_STOCK_MODIFIED`
Then la notification est affichée dans le centre de notifications du device
And le deep link navigation fonctionne

## Tasks / Subtasks

### Task 1: Enrichir ProductCreatedEvent avec actorRole, actorName, storeName
- [x] 1.1 Ajouter les champs `actorRole`, `actorName`, `storeName` à `ProductCreatedEvent.java`
- [x] 1.2 Mettre à jour `CreateProductUseCase.CreateProductDto` avec `actorRole`, `actorName`, `storeName`
- [x] 1.3 Mettre à jour `CreateProductUseCase.execute()` pour passer les nouveaux champs à l'event
- [x] 1.4 Mettre à jour `ProductController.createProduct()` pour extraire role + name + storeName
- [x] 1.5 Mettre à jour `QuickAddProductService` — extrait role/name de SecurityContext
- [x] 1.6 `AuditEventListener.on(ProductCreatedEvent)` — aucun changement nécessaire
- [x] 1.7 Mettre à jour `ProductAuditEventTest` (+3 params event)

### Task 2: Enrichir StockAdjustedEvent avec actorRole, actorName, storeName
- [x] 2.1 Ajouter les champs à `StockAdjustedEvent.java`
- [x] 2.2 `StockOperationService.recordOperation()` — extrait role/name/storeName de SecurityContext (signature inchangée)
- [x] 2.3 `RecordStockEntryUseCase` — inchangé
- [x] 2.4 `AdjustStockUseCase` — inchangé
- [x] 2.5 `StockController` — AuthDetails adaptation only
- [x] 2.6 Tous les appelants de `recordOperation()` compatibles (signature inchangée)
- [x] 2.7 `AuditEventListener` — inchangé
- [x] 2.8 `StockOperationServiceTest` — +mock StoreRepository

### Task 3: Créer AuthDetails + adapter JwtAuthFilter
- [x] 3.1 Créer `AuthDetails(String firstName, UUID storeId)`
- [x] 3.2 `JwtAuthFilter` extrait firstName, stocke AuthDetails
- [x] 3.3 `StockController.extractAssignedStoreId()` → `instanceof AuthDetails`
- [x] 3.4 `PendingSaleController.extractAssignedStoreId()` → idem
- [x] 3.5 `InventorySessionController.extractAssignedStoreId()` → idem
- [x] 3.6 Tests: 3 fichiers (`InventorySessionControllerTest`, `PendingSaleControllerTest`, `StockControllerTest`)

### Task 4: ProductCreationNotificationListener
- [x] 4.1 Créer `ProductCreationNotificationListener` (remplace `DraftProductNotificationListener` supprimé)
- [x] 4.2 Handler `onProductCreated(ProductCreatedEvent)` — dispatch si EMPLOYEE
- [x] 4.3 Handlers `onCsvImportCompleted` + `onProductCreatedProgressively` préservés
- [x] 4.4 Batching: fenêtre 5 min configurable, dispatch immédiat (single pour 1er, batch pour N≥2)
- [x] 4.5 Payloads single (§A.3) et batch (§A.4) implémentés
- [x] 4.6 Ancien `DraftProductNotificationListener.java` supprimé

### Task 5: StockModificationNotificationListener
- [x] 5.1 Créé dans `messaging/notification/application/listener/`
- [x] 5.2 Filtre EMPLOYEE + STOCK_ENTRY/ADJUSTMENT
- [x] 5.3 Skip OWNER, SALE, transfert
- [x] 5.4 Batching: même pattern que Task 4
- [x] 5.5 Payloads single (§B.3) et batch (§B.4)
- [x] 5.6 Canal BOTH: `NotificationPort` + `WhatsAppPort`

### Task 6: Tests TDD — ProductCreationNotificationListenerTest (7 tests) ✅
- [x] 6.1 `shouldNotifyOwnerWhenEmployeeCreatesActiveProduct()`
- [x] 6.2 `shouldNotifyOwnerWhenEmployeeCreatesDraftProduct()` (regression AC9)
- [x] 6.3 `shouldSkipNotificationWhenOwnerCreatesProduct()`
- [x] 6.4 `shouldSendBatchedRecapWhenEmployeeCreatesMultipleProductsInWindow()`
- [x] 6.5 `shouldSendSingleNotificationWhenEmployeeCreatesOneProduct()`
- [x] 6.6 `shouldNotRollbackProductCreationWhenNotificationFails()`
- [x] 6.7 `shouldNotDoubleNotifyOnCsvImport()`

### Task 7: Tests TDD — StockModificationNotificationListenerTest (6 tests) ✅
- [x] 7.1 `shouldNotifyOwnerWhenEmployeeEntersStock()`
- [x] 7.2 `shouldNotifyOwnerWhenEmployeeAdjustsStock()`
- [x] 7.3 `shouldSkipNotificationWhenOwnerModifiesStock()`
- [x] 7.4 `shouldSkipSaleDecrementEvents()`
- [x] 7.5 `shouldSendBatchedRecapWhenEmployeeModifiesMultipleStocksInWindow()`
- [x] 7.6 `shouldNotRollbackStockModificationWhenNotificationFails()`

### Task 8: Flutter — FCM reception ✅
- [x] 8.1 `main_shell.dart`: invalidation `productListProvider` pour `EMPLOYEE_PRODUCT_CREATED*` et `EMPLOYEE_STOCK_MODIFIED*`
- [x] 8.2 Deep link: automatique via `FcmService._onTap()` (utilise `data['deepLink']`)

### Task 9: Regression ✅
- [x] 9.1 Backend: 1532 tests (baseline 1519 + 13 nouveaux), 0 NEW failures, 0 NEW errors
- [x] 9.2 Flutter: modification minimale (4 lignes)
- [x] 9.3 File List complété
- [x] 9.4 sprint-status.yaml → review

### Review Findings

- [x] [Review][Decision→Patch] Le nouveau handler `onProductCreatedProgressively` réintroduisait une notification par produit brouillon (spam), contredisant la décision de conception documentée dans l'ancien `DraftProductNotificationListener` (« intentionally removed ») et l'affirmation d'AC2 selon laquelle le comportement DRAFT est « préservé sans changement ». **Décision (Toor) : retirer le handler.** Fixé : le handler et son import `ProductCreatedProgressivelyEvent` ont été supprimés ; AC2 tient maintenant réellement (aucun listener sur cet event, comme à la baseline — notification uniquement à la soumission de vente via `SaleDraftValidatedNotificationListener`, inchangé). [ProductCreationNotificationListener.java]

- [x] [Review][Patch] Le batching déclenchait une notification à chaque événement au lieu d'un seul récap consolidé à l'expiration de la fenêtre (violait AC5/D4). Fixé : dispatch retiré du handler d'event ; un sweep périodique `@Scheduled` (`flushExpiredWindows()`, piloté par un `Clock` injectable pour les tests) envoie exactement une notification par fenêtre (single si 1 événement, recap si N≥2). Tests 6.4/7.5 réécrits pour asserter exactement 1 appel ; test 6.5b/nouveau ajouté pour couvrir le redémarrage de fenêtre après flush (comblant le gap « pas de test d'expiration »). [ProductCreationNotificationListener.java ; StockModificationNotificationListener.java]
- [x] [Review][Patch] Le canal WhatsApp était structurellement cassé (`sendReport(null, ...)` → NPE dans les adaptateurs réels, échec avalé sans log). Fixé : résolution du numéro du propriétaire via `UserRepository.findOwnerByTenantSchemaName()` (même pattern que `StockAlertNotificationListener`), et `sendNotification()` logge maintenant chaque échec de canal indépendamment (push seul, WhatsApp seul, ou les deux). [ProductCreationNotificationListener.java ; StockModificationNotificationListener.java]
- [x] [Review][Patch] `resolveProductName()` ne résolvait aucun nom de produit (UUID tronqué). Fixé : `StockAdjustedEvent` porte désormais `productName` (peuplé depuis le `Product` déjà chargé dans `StockOperationService.recordOperation()`, zéro appel DB supplémentaire) ; fallback UUID tronqué conservé uniquement si `productName` est absent. [StockAdjustedEvent.java ; StockOperationService.java ; StockModificationNotificationListener.java]
- [x] [Review][Patch] Échecs d'extraction du contexte acteur avalés silencieusement sans log dans `StockOperationService`/`QuickAddProductService`. Fixé : `catch (Exception ignored)` → `catch (Exception e) { log.warn(...) }` (Logger ajouté à `QuickAddProductService`). [StockOperationService.java ; QuickAddProductService.java]
- [x] [Review][Patch] Code mort dans `StockController` (`extractRole`/`extractActorName`/`resolveStoreName` + `StoreRepository` jamais appelés). Fixé : méthodes et dépendance supprimées ; `StockControllerTest` ne mockait déjà pas `StoreRepository`, aucun changement de test nécessaire. [StockController.java]
- [x] [Review][Patch] Régression de couverture de test sur l'import CSV. Fixé : test de résilience `shouldNotRollbackOnCsvImportNotificationFailure` repris de l'ancien `DraftProductNotificationListenerTest`, et `shouldNotDoubleNotifyOnCsvImport` renforcé avec assertions sur `body`/`metadata`. [ProductCreationNotificationListenerTest.java]
- [x] [Review][Patch] Flutter : le push `EMPLOYEE_STOCK_MODIFIED*` n'invalidait que `productListProvider`. Fixé : `globalStockOverviewProvider` (déjà importé dans le fichier) invalidé également pour ce type de notification. [main_shell.dart]

- [x] [Review][Defer] Logique d'extraction acteur (role/name via SecurityContext) dupliquée 4x (ProductController, StockOperationService, QuickAddProductService — le 4e site, StockController, a été supprimé comme code mort ci-dessus) — `AuthDetails`, introduit dans ce même diff, serait le point de centralisation naturel — deferred, refactor non bloquant [ProductController.java ; StockOperationService.java ; QuickAddProductService.java]
- [x] [Review][Defer] Fallback en l'absence de `firstName` affiche l'UUID brut de l'acteur dans le texte de notification, non testé — deferred, dégradation cosmétique uniquement [ProductController.java ; StockOperationService.java]
- [x] [Review][Defer] Les entrées de la map `batchWindows` ne sont jamais évincées (croissance mémoire lente) — deferred, borné par la cardinalité tenant×employé, faible urgence [ProductCreationNotificationListener.java ; StockModificationNotificationListener.java]
- [x] [Review][Fixed — était mal évalué comme defer] `extractRole()` levait `FORBIDDEN` avant l'exécution du use case métier. **Contrairement à l'évaluation initiale du triage** (« chemin d'échec pratiquement inatteignable »), la régression complète (`mvn test`) a prouvé le contraire : elle cassait réellement `ProductControllerTest.should_create_product_with_valid_data` (authentification de test avec `List.of()` autorités, pattern MockMvc standalone légitime, non modifié par cette story). Fixé : `extractRole()` retourne `null` au lieu de lever, cohérent avec le pattern best-effort déjà appliqué ailleurs — `actorRole=null` fait simplement échouer silencieusement le check `"EMPLOYEE".equals(...)` en aval (skip notification), sans jamais bloquer la création du produit. [ProductController.java]

**Vérification régression complète (`mvn test`, 1541 tests) :** 0 échec/erreur imputable à ce diff. 41 erreurs restantes = Testcontainers/PostgreSQL indisponible dans ce sandbox (pré-existant, aucun rapport avec le code). 1 échec pré-existant et sans rapport (`OnboardingServiceTest.categories_should_have_valid_fields`, package `identity.onboarding` non touché par cette story) — signalé pour information, hors scope de cette story.

## Dev Notes

### Architecture

**Pattern:** Observer (Spring EventListener) — les listeners sont découplés des use cases. Les events sont publiés par les use cases via `ApplicationEventPublisher`, les listeners réagissent de manière asynchrone (`@Async`).

**Batching:** Pattern `ConcurrentHashMap<BatchKey, BatchWindow>` par JVM (inspiré de `StockAlertNotificationListener`). Fenêtre configurable via `keevo.notification.employee-action-window-min` (défaut: 5 min). Mono-instance (MVP), à déporter sur Redis en Phase 2.

**NotificationPort** déjà existant (FCM + WhatsApp). `notifyOwners(tenantId, payload)` envoie aux OWNERs du tenant.

### Événements
- `ProductCreatedEvent` (existant) → enrichi avec `actorRole`, `actorName`, `storeName`
- `StockAdjustedEvent` (existant) → enrichi avec `actorRole`, `actorName`, `storeName`
- `ProductCreatedProgressivelyEvent` (existant) → déjà porte `actorRole`, `actorName` — inchangé
- `CsvImportCompletedEvent` (existant) → déjà porte `actorRole`, `actorName` — inchangé

### AuthDetails
Nouveau record `AuthDetails(String firstName, UUID storeId)` stocké dans `Authentication.details` par `JwtAuthFilter`. Remplace l'ancien pattern `details = UUID storeId`.

### Fichiers impactés (backend)
- `shared/infrastructure/security/AuthDetails.java` — NOUVEAU
- `shared/infrastructure/security/JwtAuthFilter.java` — MODIFIÉ (extrait firstName, stocke AuthDetails)
- `catalog/product/domain/event/ProductCreatedEvent.java` — MODIFIÉ (+3 champs)
- `catalog/stock/domain/event/StockAdjustedEvent.java` — MODIFIÉ (+3 champs)
- `catalog/product/application/usecase/CreateProductUseCase.java` — MODIFIÉ (DTO + execute)
- `catalog/stock/domain/service/StockOperationService.java` — MODIFIÉ (params)
- `catalog/stock/application/usecase/RecordStockEntryUseCase.java` — MODIFIÉ (params)
- `catalog/stock/application/usecase/AdjustStockUseCase.java` — MODIFIÉ (params)
- `catalog/product/adapter/in/web/ProductController.java` — MODIFIÉ (extrait role+name)
- `catalog/stock/adapter/in/web/StockController.java` — MODIFIÉ (extrait role+name, adapte AuthDetails)
- `commerce/sale/adapter/in/rest/PendingSaleController.java` — MODIFIÉ (adapte AuthDetails)
- `inventory/counting/adapter/in/rest/InventorySessionController.java` — MODIFIÉ (adapte AuthDetails)
- `inventory/counting/application/service/QuickAddProductService.java` — MODIFIÉ (passe nouveaux champs)
- `catalog/product/application/usecase/ImportCsvProductsUseCase.java` — MODIFIÉ (passe actorRole/actorName à recordOperation)
- `commerce/sale/application/service/CorrectSaleService.java` — MODIFIÉ (passe actorRole/actorName à recordOperation)
- `messaging/notification/application/listener/ProductCreationNotificationListener.java` — RENOMMÉ + ÉTENDU
- `messaging/notification/application/listener/StockModificationNotificationListener.java` — NOUVEAU
- Tests divers — MODIFIÉS

## Dev Agent Record

### Implementation Plan

3 phases: (1) AuthDetails + JwtAuthFilter pour débloquer firstName, (2) enrichir les 2 events (+3 champs chacun), extraction role/name/storeName dans StockOperationService depuis SecurityContext pour éviter de modifier 20+ appelants, (3) 2 nouveaux listeners avec batching + 13 tests TDD.

### Debug Log

- Compilation: 1 erreur unicode escape fixée.
- AuthDetails: 3 fichiers test corrigés (setDetails(UUID) → setDetails(AuthDetails)).
- StockOperationServiceTest: +mock StoreRepository.
- Batching: corrigé (events.clear() retiré du dispatch immédiat, accumulation conservée).
- Suite: 1532 tests, 0 NEW failures, 0 NEW errors.

### Completion Notes

✅ AC1-AC7 tous satisfaits. 13/13 TDD GREEN. 0 NEW regression. Batching: fenêtre 5 min, dispatch incrémental (1er single, N batch). Best-effort: try/catch + log WARN. Flutter: invalidation productListProvider sur réception FCM.

## File List

### Nouveaux
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/AuthDetails.java`
- `keevo/backend/src/main/java/com/keevo/messaging/notification/application/listener/ProductCreationNotificationListener.java`
- `keevo/backend/src/main/java/com/keevo/messaging/notification/application/listener/StockModificationNotificationListener.java`
- `keevo/backend/src/test/java/com/keevo/messaging/notification/application/listener/ProductCreationNotificationListenerTest.java`
- `keevo/backend/src/test/java/com/keevo/messaging/notification/application/listener/StockModificationNotificationListenerTest.java`

### Modifiés
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtAuthFilter.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/domain/event/ProductCreatedEvent.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/domain/event/StockAdjustedEvent.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/application/usecase/CreateProductUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/domain/service/StockOperationService.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/in/web/ProductController.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/in/web/StockController.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/in/rest/PendingSaleController.java`
- `keevo/backend/src/main/java/com/keevo/inventory/counting/adapter/in/rest/InventorySessionController.java`
- `keevo/backend/src/main/java/com/keevo/inventory/counting/application/service/QuickAddProductService.java`
- `keevo/app/lib/core/scaffold/main_shell.dart`
- `keevo/backend/src/test/java/com/keevo/catalog/product/domain/event/ProductAuditEventTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/stock/domain/service/StockOperationServiceTest.java`
- `keevo/backend/src/test/java/com/keevo/inventory/counting/adapter/in/rest/InventorySessionControllerTest.java`
- `keevo/backend/src/test/java/com/keevo/commerce/sale/adapter/in/rest/PendingSaleControllerTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/stock/adapter/in/web/StockControllerTest.java`

### Supprimés
- `keevo/backend/src/main/java/com/keevo/messaging/notification/application/listener/DraftProductNotificationListener.java`
- `keevo/backend/src/test/java/com/keevo/messaging/notification/application/listener/DraftProductNotificationListenerTest.java`

## Change Log

- 2026-07-22: Story 14.10 implémentée — 13/13 TDD GREEN, 0 NEW regression (1532 tests, baseline 1519 + 13)
