---
title: 'Fix: pull-sync écrase silencieusement les mouvements de stock offline non confirmés'
type: 'bugfix'
created: '2026-08-02'
status: 'done'
review_loop_iteration: 0
context: []
baseline_commit: 'ec84d5ee24e2c357e2f34e20376c6f7decf1109f'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `RestSyncService._upsertStockLevels()` (`keevo/app/lib/core/sync/rest_sync_service.dart:332`) est le seul upsert du pull-merge sans protection contre les opérations locales en attente : il écrase toujours `stock_levels.quantity` avec la valeur serveur, alors que `_upsertProducts`/`_upsertCategories`/`_upsertClients`/`_upsertSuppliers`/`_upsertStores`/`_upsertEmployees` sautent les lignes présentes dans `pendingIds` (AC7), et `_upsertInventorySessions`/`_upsertInventoryCounts` sautent les lignes `synced = 0`. Comme `stock_levels` est une ressource pré-existante (pas créée par l'opération), le pull la retrouve toujours côté serveur — contrairement à un produit ou une vente offline qui n'existe pas encore côté serveur tant qu'il n'est pas poussé. Résultat : si le push échoue (ou reste partiel) pendant que `triggerSync()` enchaîne quand même sur le pull (comportement voulu — "pull is NOT gated", `sync_trigger_notifier.dart:164`), toute entrée de stock, vente, transfert ou validation d'inventaire faite offline est silencieusement écrasée par l'ancienne valeur serveur, alors que l'opération reste dans `sync_queue` pour un retry plus tard.

**Approach:** Calculer, comme `_getPendingEntityIds()` le fait déjà pour les autres tables, l'ensemble des paires `(productId, storeId)` affectées par les opérations `sync_queue` non synchronisées (`STOCK_ADJUST`, `RECORD_STOCK_ENTRY`, `CREATE_SALE`, `STOCK_TRANSFER`), et sauter dans `_upsertStockLevels` toute ligne serveur dont la clé `(productId, storeId)` est dans cet ensemble. Étendre par cohérence la protection `pendingIds` existante à `_upsertStockTransfers`.

## Boundaries & Constraints

**Always:** Ne pas modifier la clé de conflit SQL existante `(product_id, store_id)` (le schéma actuel ne distingue pas par `variant_id` — hors périmètre). Réutiliser le pattern `pendingIds`/`synced=0` déjà en place, pas une nouvelle colonne ni migration Drift. Chaque type d'opération protégé doit avoir un test RED→GREEN dédié dans `rest_sync_service_pull_test.dart`.

**Ask First:** Si l'extraction du payload `CREATE_SALE.items[]` ou `STOCK_TRANSFER` révèle un champ manquant/renommé empêchant l'extraction fiable de `productId`/`storeId`.

**Never:** Ne pas toucher au backend Java (Story 5.3, in-progress, gère un problème différent : détection/logging de conflits après application côté serveur — pas la fusion locale Flutter). Ne pas introduire de colonne `synced`/`dirty` sur `stock_levels`. Ne pas modifier `VALIDATE_INVENTORY`/`SAVE_INVENTORY_COUNT` (payload sans productId/storeId direct — hors périmètre de cette spec, les lignes `inventory_sessions`/`inventory_counts` sont déjà protégées ; seule la conséquence sur `stock_levels` via `STOCK_ADJUST`/`RECORD_STOCK_ENTRY` déjà couverte compte).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| STOCK_ADJUST pending | `sync_queue` a un `STOCK_ADJUST` non synced (productId=P, storeId=S) ; pull renvoie `stockLevels` incluant (P,S) | Ligne (P,S) NON écrasée ; quantité locale conservée | N/A |
| RECORD_STOCK_ENTRY pending | idem avec `RECORD_STOCK_ENTRY` | Ligne (P,S) NON écrasée | N/A |
| CREATE_SALE pending (multi-items) | `sync_queue` CREATE_SALE payload.storeId=S, items=[{productId:P1},{productId:P2}] | (P1,S) et (P2,S) NON écrasées | N/A |
| STOCK_TRANSFER pending | payload sourceStoreId=S1, destinationStoreId=S2, productId=P | (P,S1) ET (P,S2) NON écrasées | N/A |
| Aucune opération pending pour (P,S) | Pas d'entrée sync_queue pour ce couple | Ligne (P,S) upsert normalement (comportement actuel inchangé) | N/A |
| STOCK_TRANSFER confirmé (entityId dans pendingIds n'existe plus) | Opération déjà retirée de sync_queue après ACK serveur | `_upsertStockTransfers` upsert normalement | N/A |

</frozen-after-approval>

## Code Map

- `keevo/app/lib/core/sync/rest_sync_service.dart` -- `_upsertStockLevels` (L332) sans protection ; `_getPendingEntityIds` (L268) pattern à répliquer ; `_upsertStockTransfers` (L638) à protéger par `pendingIds` existant.
- `keevo/app/lib/features/catalog/data/repository/stock_repository_impl.dart:278` -- `STOCK_ADJUST` payload: `productId`, `storeId`, `variantId?`.
- `keevo/app/lib/features/inventory/presentation/provider/quick_add_product_provider.dart:245` / `keevo/app/lib/features/pos/data/repository/sale_repository_impl.dart:51` -- `RECORD_STOCK_ENTRY` payload: `productId`, `storeId`.
- `keevo/app/lib/features/pos/data/repository/sale_repository_impl.dart:441` -- `CREATE_SALE` payload: `storeId`, `items[].productId`.
- `keevo/app/lib/features/inventory/data/repository/stock_transfer_repository_impl.dart:93` -- `STOCK_TRANSFER` payload: `sourceStoreId`, `destinationStoreId`, `productId`; `entityId` = transfer id.
- `keevo/app/test/core/sync/rest_sync_service_pull_test.dart:197` -- test existant `pull_upsertsStockLevels` (happy path, à conserver) ; `pull_upsertsStockTransfers` (L286).

## Tasks & Acceptance

**Execution:**
- [x] `keevo/app/test/core/sync/rest_sync_service_pull_test.dart` -- Ajouter les tests RED (avant le fix) couvrant les 6 scénarios de l'I/O Matrix : `pull_pendingStockAdjust_doesNotOverwriteStockLevel`, `pull_pendingRecordStockEntry_doesNotOverwriteStockLevel`, `pull_pendingCreateSale_doesNotOverwriteAnyItemStockLevel`, `pull_pendingStockTransfer_doesNotOverwriteSourceOrDestination`, `pull_noPendingOps_stockLevelUpsertsNormally` (regression du test existant), `pull_confirmedTransfer_upsertsNormally` -- garantit la couverture avant l'implémentation (TDD).
- [x] `keevo/app/lib/core/sync/rest_sync_service.dart` -- Ajouter une méthode privée `_getPendingStockKeys()` qui lit les `sync_queue` non-synced, filtre par `operation IN (STOCK_ADJUST, RECORD_STOCK_ENTRY, CREATE_SALE, STOCK_TRANSFER)`, décode le payload JSON, et retourne un `Set<String>` de clés `"productId|storeId"` (pour STOCK_TRANSFER : les deux clés source+destination ; pour CREATE_SALE : une clé par item) -- centralise l'extraction, miroir de `_getPendingEntityIds`.
- [x] `keevo/app/lib/core/sync/rest_sync_service.dart` -- Appeler `_getPendingStockKeys()` dans `pull()` à côté de `pendingIds` (L204), passer le résultat à `_upsertStockLevels`, et sauter (`continue`) toute ligne dont `"${map['productId']}|${map['storeId']}"` est dans l'ensemble -- corrige le bug racine.
- [x] `keevo/app/lib/core/sync/rest_sync_service.dart` -- Dans `_upsertStockTransfers` (L638), sauter les lignes dont `id` est dans `pendingIds` (même pattern que `_upsertCategories`/`_upsertClients`) -- défense en profondeur, coût marginal nul (le set est déjà calculé).

**Acceptance Criteria:**
- Given une opération `STOCK_ADJUST`, `RECORD_STOCK_ENTRY`, `CREATE_SALE` ou `STOCK_TRANSFER` non synchronisée dans `sync_queue` pour un couple (productId, storeId), when un pull s'exécute et que le serveur renvoie une ligne `stockLevels` pour ce même couple, then la quantité locale n'est pas modifiée par le pull.
- Given aucune opération pending pour un couple (productId, storeId), when un pull s'exécute, then la ligne `stock_levels` correspondante est mise à jour normalement (comportement actuel préservé, test `pull_upsertsStockLevels` toujours GREEN).
- Given un `STOCK_TRANSFER` déjà confirmé (retiré de `sync_queue`), when un pull renvoie ce transfert, then `_upsertStockTransfers` le met à jour normalement (statut/notes).

## Design Notes

`_getPendingStockKeys()` doit tolérer un payload malformé ou un champ manquant (`productId`/`storeId` null) sans lever d'exception — dans ce cas, ignorer l'entrée plutôt que planter le pull (cohérent avec `extractProductId` côté backend qui retourne `null` défensivement, cf. Story 5.3 AC12).

## Verification

**Commands:**
- `cd keevo/app && flutter test test/core/sync/rest_sync_service_pull_test.dart` -- expected: tous les tests GREEN, y compris les 6 nouveaux.
- `cd keevo/app && flutter analyze lib/core/sync/rest_sync_service.dart` -- expected: aucune nouvelle erreur/warning.

**Manual checks (if no CLI):**
- Scénario terrain : créer un produit + entrée de stock + vente + transfert + inventaire en mode avion sur un compte employé, couper le réseau juste avant la reconnexion (simuler l'échec partiel du push), puis réactiver — vérifier que les quantités de stock affichées reflètent bien les actions offline après le cycle de sync suivant (pas de régression vers l'état serveur pré-offline).
