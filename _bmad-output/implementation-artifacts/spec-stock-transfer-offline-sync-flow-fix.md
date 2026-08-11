---
title: 'Fix: transfert offline ne préserve pas son id — doublon fantôme après sync'
type: 'bugfix'
created: '2026-08-11'
status: 'done'
review_loop_iteration: 0
context: []
baseline_commit: '47250eb'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Un transfert créé hors-ligne garde un id local, mais `ExecuteTransferService.execute()` (`ExecuteTransferService.java:109`) génère toujours un `UUID.randomUUID()` côté serveur au push, sans lire l'id transmis. Le pull-merge (`_upsertStockTransfers`, `rest_sync_service.dart:722-753`) fait un `INSERT ... ON CONFLICT(id)` : avec deux ids différents il insère une nouvelle ligne au lieu de mettre à jour l'existante — l'enregistrement local d'origine reste orphelin en `PENDING_SYNC` à vie, visible en double dans l'historique. Même bug de classe que CREATE_PRODUCT (fixé au commit 95eaa19). Le flow de notification/réception côté boutique destination fonctionne déjà correctement (id serveur correct dans la notif) — seul le device émetteur est affecté.

**Approach:** Répliquer le pattern `CreateProductUseCase` (idempotency lookup + `clientId` optionnel), en utilisant `SyncOperation.entityId()` — déjà transmis par le client (`rest_sync_service.dart:68`) — sans changement de payload Flutter.

## Boundaries & Constraints

**Always:** Ne pas changer le payload `STOCK_TRANSFER` (l'id est déjà dans `entityId`). Lookup idempotent par `clientId` avant toute décrémentation de stock, pour couvrir un retry réseau (mirror `CreateProductUseCase.java:104-109`). Test backend dédié par comportement nouveau.

**Ask First:** Si `findById(clientId)` trouve un transfert dont les champs diffèrent du payload actuel — collision d'id plutôt que retry ; ne pas retourner l'existant sans vérifier.

**Never:** Pas de support offline pour la réception (`completeTransfer`) — choix de design assumé dans la spec 3-3, hors scope. Ne pas toucher `StockTransferController`/`TransferStockRequestDto` (chemin REST online, non concerné). Ne pas toucher `TransferValidationStrategy`/`StockOperationService` (pas de double décrément — le serveur applique son propre `TRANSFER_OUT` indépendamment de l'état local).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Création offline puis sync | Transfert créé hors-ligne (id local X), push réussi, pull suivant | Une seule ligne dans `stock_transfers` locale, id X, status mis à jour (plus de doublon PENDING_SYNC orphelin) | N/A |
| Retry réseau du push | Même `entityId`/opération rejouée deux fois côté backend | 2e appel retourne le transfert existant sans nouvelle décrémentation du stock source | Idempotent, pas de `DomainException` |
| Réception après sync (non-régression) | Transfert synchronisé, boutique destination en ligne | Notification `STOCK_TRANSFER_CREATED` reçue avec l'id serveur = id client, réception via flow normal fonctionne sans changement | N/A (déjà correct, à couvrir par non-régression) |

</frozen-after-approval>

## Code Map

- `.../stock/domain/port/in/TransferStockCommand.java` -- ajouter `UUID clientId`
- `.../stock/application/usecase/ExecuteTransferService.java` -- idempotency lookup + honorer `clientId` au lieu de `UUID.randomUUID()` (ligne 109)
- `.../sync/sync/application/handler/TransferSyncHandler.java` -- lire `operation.entityId()`, le transmettre comme `clientId`
- `.../stock/domain/port/out/StockTransferRepository.java` -- `findById` déjà présent (ligne 22), aucun changement
- `.../catalog/stock/application/usecase/ExecuteTransferServiceTest.java` -- nouveau test idempotence
- `.../sync/sync/application/handler/TransferSyncHandlerTest.java` -- nouveau test propagation `entityId` → `clientId`
- `keevo/app/test/core/sync/rest_sync_service_pull_test.dart` -- étoffer `pull_confirmedTransfer_upsertsNormally` (ligne 527), test actuel masque le bug (même id des deux côtés sans assertion anti-doublon)

## Tasks & Acceptance

**Execution:**
- [x] `TransferStockCommand.java` -- ajouter `UUID clientId` en dernier paramètre du record -- mirror `CreateProductDto.clientId()`
- [x] `ExecuteTransferService.java` -- si `command.clientId() != null`, `transferRepository.findById(clientId)` → retourner l'existant si présent ; sinon construire le `StockTransfer` avec `clientId` ou `UUID.randomUUID()` -- préserve l'id local, idempotent sur retry
- [x] `TransferSyncHandler.java` -- dans `apply()`, parser `operation.entityId()` en `UUID`, le passer comme `clientId` -- relie l'opération à son id d'origine
- [x] `ExecuteTransferServiceTest.java` -- test : 2e appel même `clientId` → transfert existant retourné, `recordOperation` pas rappelé -- couvre retry/double décrément
- [x] `TransferSyncHandlerTest.java` -- test : `entityId` propagé comme `clientId` au `TransferStockCommand` capturé -- couvre régression handler
- [x] `rest_sync_service_pull_test.dart` -- ajouter assertion `hasLength(1)` sur `stock_transfers` (ligne 527) -- garantit l'absence de doublon fantôme

**Acceptance Criteria:**
- Given un transfert créé hors-ligne (id local X) poussé avec succès, when le pull suivant ramène le transfert, then une seule ligne existe dans `stock_transfers` locale (id X), aucune ligne orpheline `PENDING_SYNC`
- Given la même opération STOCK_TRANSFER rejouée deux fois (retry réseau), when le second appel arrive, then le stock source n'est décrémenté qu'une fois, transfert existant retourné tel quel
- Given un transfert synchronisé, when la boutique destination est en ligne, then notification `STOCK_TRANSFER_CREATED` reçue, réception via `completeTransfer` inchangée (non-régression)

## Design Notes

Pattern déjà en prod pour `CREATE_PRODUCT` (`CreateProductUseCase.java:104-109,127`) : `if (dto.clientId() != null) { existing = repo.findById(dto.clientId()); if present return it; }` puis `dto.clientId() != null ? dto.clientId() : UUID.randomUUID()`. Différence ici : l'id local est déjà transmis via `SyncOperation.entityId()`, pas besoin de l'ajouter au `payload` Flutter comme pour les produits (`local_product_datasource.dart:154`) — `TransferSyncHandler.apply()` lit `operation.entityId()` au lieu de `operation.payload()`.

## Verification

**Commands:**
- `cd keevo/backend && mvn test -Dtest=ExecuteTransferServiceTest,TransferSyncHandlerTest` -- expected: tous verts
- `cd keevo/app && flutter test test/core/sync/rest_sync_service_pull_test.dart test/features/inventory/data/repository/stock_transfer_repository_offline_first_test.dart` -- expected: tous verts

**Manual checks (if no CLI):**
- Curl (mirror commit 95eaa19) : POST `/api/v1/sync/push` avec `entityId=X` pour un `STOCK_TRANSFER`, vérifier `GET /api/v1/stock-transfers` → `id=X`, puis pull côté client → une seule ligne locale avec `id=X`.

## Suggested Review Order

**Préservation de l'id (cœur du fix)**

- Idempotency lookup par `clientId` puis id honoré au lieu de `UUID.randomUUID()` — cœur de la correction.
  [`ExecuteTransferService.java:74`](../../keevo/backend/src/main/java/com/keevo/catalog/stock/application/usecase/ExecuteTransferService.java#L74)
  [`ExecuteTransferService.java:120`](../../keevo/backend/src/main/java/com/keevo/catalog/stock/application/usecase/ExecuteTransferService.java#L120)

- Nouveau champ `clientId` + constructeur rétro-compatible pour ne pas casser le chemin REST online.
  [`TransferStockCommand.java:17`](../../keevo/backend/src/main/java/com/keevo/catalog/stock/domain/port/in/TransferStockCommand.java#L17)

- Propagation de `operation.entityId()` (déjà transmis par le client) comme `clientId`, sans changement de payload Flutter.
  [`TransferSyncHandler.java:51`](../../keevo/backend/src/main/java/com/keevo/sync/sync/application/handler/TransferSyncHandler.java#L51)

**Tests — garantissent la non-régression**

- Id préservé + idempotence sur retry (pas de double décrément).
  [`ExecuteTransferServiceTest.java:228`](../../keevo/backend/src/test/java/com/keevo/catalog/stock/application/usecase/ExecuteTransferServiceTest.java#L228)
  [`ExecuteTransferServiceTest.java:248`](../../keevo/backend/src/test/java/com/keevo/catalog/stock/application/usecase/ExecuteTransferServiceTest.java#L248)

- `entityId` bien capturé comme `clientId` par le handler.
  [`TransferSyncHandlerTest.java:121`](../../keevo/backend/src/test/java/com/keevo/sync/sync/application/handler/TransferSyncHandlerTest.java#L121)

- Pull-merge upsert en place (même id) au lieu d'insérer un doublon — limité à la logique de merge locale, la préservation d'id serveur est couverte côté backend ci-dessus.
  [`rest_sync_service_pull_test.dart:568`](../../keevo/app/test/core/sync/rest_sync_service_pull_test.dart#L568)

**Backlog différé (non bloquant, décisions prises pendant la revue)**

- TOCTOU sur l'idempotency check (mirror du même risque déjà accepté dans `CreateProductUseCase`) et absence de test d'intégration bout-en-bout — voir `deferred-work.md` (section 2026-08-11).
- Collision `clientId` avec payload différent : décision explicite de garder le comportement `CreateProductUseCase` tel quel (pas de vérification de champs), risque jugé négligeable en pratique.
