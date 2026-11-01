---
title: '17-11 Optional initial stock at product creation — pin the sync contract (CREATE_PRODUCT + RECORD_STOCK_ENTRY) and hand over to mobile'
type: 'feature'
created: '2026-11-01'
status: 'done'
baseline_revision: 'c56ffb11604666759273474ba01c2a88a2b0b728'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-17-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 17.11 (`epics-refonte-modulaire.md`, from v1s-16-26, user request 2026-10-29, precision 2026-11-01): the owner wants to optionally enter an initial quantity in « Nouveau produit », even for a tracked product; left empty the product is created with 0 stock and no movement, and creation is never blocked by this field. The form lives in the React Native app (other repo); this repo must guarantee the API side and tell the mobile agent exactly how to send it.

**Approach:** The offline-first app already creates products through the sync op `CREATE_PRODUCT` (client id kept) and can record stock through `RECORD_STOCK_ENTRY` referencing that id in the same batch. Pin this contract with real-wiring tests (quantity given / empty / replayed / product merged by name), close any server gap found, and write the Mobile section (field rules, op order, ids, offline display, error handling).

## Boundaries & Constraints

**Always:**
- Contract: a batch `[CREATE_PRODUCT {id: X, …}, RECORD_STOCK_ENTRY {productId: X, storeId: S, quantity: Q, movement id …}]` → product X exists, stock level of X in S = Q, exactly one `STOCK_ENTRY` movement (the client's movement id kept so the pull does not duplicate the local one). Without the second op → stock 0, no movement.
- Replaying the same batch (same operation ids) → no second product, no second movement, stock still Q.
- `CREATE_PRODUCT` merged into an existing product with the same name (`ProductSyncHandler.mergeIntoExistingByName`): the following `RECORD_STOCK_ENTRY` that references the client id X must land on the merged product (or be rejected with a clear reason the client can handle) — never applied to a non-existent product, never silently lost; behaviour pinned by a test and documented.
- `CREATE_PRODUCT.stockQuantity` (legacy field): document what it does today; it must not create stock a second time alongside `RECORD_STOCK_ENTRY` (test).
- Quantity rules for `RECORD_STOCK_ENTRY` at creation: integer > 0; 0 or empty → the client sends no stock op; negative → rejected with the existing code (French message per 16-14).
- Spec ends with « ## Mobile (React Native — dépôt AI/bref) » and `m17-11-stock-initial-creation-produit` in the MOBILE block of `sprint-status.yaml`; the field is hidden for an untracked product once 17-12 exists (mention as « à venir »).

**Never:**
- No new endpoint or response shape change unless a test shows the existing ops cannot meet the contract (then additive only); no Flutter feature work in `keevo/app`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Quantity given | batch CREATE_PRODUCT X + RECORD_STOCK_ENTRY X/S/5 | both APPLIED; stock(X,S)=5; 1 STOCK_ENTRY with the client movement id | — |
| Empty quantity | CREATE_PRODUCT X only | APPLIED; no stock row or 0; no movement | — |
| Replay | same batch pushed twice | no duplicate product/movement; stock 5 | — |
| Merged by name | product « Riz » exists (id E); CREATE_PRODUCT X « Riz » + RECORD_STOCK_ENTRY X | stock lands on E (or a documented REJECTED reason), never lost silently | — |
| Legacy stockQuantity | CREATE_PRODUCT X with stockQuantity 5 + RECORD_STOCK_ENTRY X/S/5 | stock 5, not 10 | — |
| Negative quantity | RECORD_STOCK_ENTRY quantity -2 | REJECTED with existing code | French message |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/sync/sync/application/handler/ProductSyncHandler.java:50-73` -- `CREATE_PRODUCT` (client id kept, `mergeIntoExistingByName` `:160-170`, `stockQuantity` passed to `CreateProductInput`).
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/handler/StockAdjustSyncHandler.java:27,66` -- `RECORD_STOCK_ENTRY` / `STOCK_ADJUST`.
- `keevo/backend/src/main/java/com/keevo/catalog/product/application/usecase/CreateProductUseCase.java:60-100` -- `stockQuantity` parameter (legacy column?).
- `keevo/backend/src/main/java/com/keevo/inventory/stock/application/usecase/RecordStockEntryUseCase.java`, `inventory/stock/adapter/in/api/CatalogInitialStockAdapter.java` (CSV import initial stock via `InitialStockPort`).
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/service/SyncPushService.java` -- batch order, idempotency by operation id, rollback handling.
- Existing tests to extend: `ProductSyncHandlerTest`, `StockAdjustSyncHandler` tests, full-context sync tests (e.g. `SubscriptionUsageFullContextTest` helpers, `ResidualRacesPostgresIntegrationTest`).
- Flutter reference (behaviour only): `keevo/app/lib/features/catalog/presentation/page/product_form_page.dart`, `local_product_datasource.dart` (CREATE_PRODUCT payload), stock entry op building.
- Conventions: Mobile sections of `spec-16-11-*` (API), `spec-16-10-*` (sync protocol), `spec-16-14-*` (errors), `spec-17-10-*` (product limit at creation).

## Tasks & Acceptance

**Execution:**
- Full-context PostgreSQL test pushing the matrix batches through `/api/v1/sync/push`, asserting product, stock level, movement rows (ids) and pull output; fix the server only where a row fails.
- Mobile section + `m17-11` key; tick 17.11 in the epics doc.

**Acceptance Criteria:**
- Given a local backend (WhatsApp noop, FCM off) and an owner signed in through `/auth/login` + `/auth/select-tenant`, when the « quantity given » batch is pushed and then pulled, then the pull returns the product, one `STOCK_ENTRY` movement with the client's id and the stock level Q.
- Given the Flutter test client on the Redmi, when it syncs, then nothing regresses (basic check).

## Design Notes

**Contract met by the existing operations; one server gap closed after review.** The full-context test `InitialStockAtCreationFullContextTest` (real `/api/v1/sync/push` + `/api/v1/sync/pull`, production session issuer, PostgreSQL) passed the I/O matrix against the unchanged server. Review found one gap: a device that re-queues the same entry under a **new** `operationId` but the same movement id M hit the `stock_movements` primary key → `REJECTED` / `INTERNAL_ERROR`, which the client cannot act on. `StockAdjustSyncHandler` (`RECORD_STOCK_ENTRY` only) now looks the movement id up first through the inventory public API (`StockQueryPort.findMovement` → `RecordedMovement`, additive): same product, variant, store, type `STOCK_ENTRY` and quantity → `DUPLICATE` (no second entry); any other content → `REJECTED` / `MOVEMENT_ID_ALREADY_USED` (stable reason, `conflictData = null`). No response shape changed (V1 2.0.1 already treats `DUPLICATE` as « remove from queue »).

- **Order**: `SyncPushService.dependencyOrder` hoists `CREATE_PRODUCT` ahead of every non-creation op, so the stock entry finds the product even when the device clock put it first. Each op runs in its own transaction; the `CREATE_PRODUCT` log row is committed before the next op.
- **Movement id**: `StockAdjustSyncHandler` uses the op's `entityId` (the device's local movement id) as the `stock_movements.id`; the pull returns that id, so the device updates its local movement rather than adding a second one.
- **Replay**: same operation ids → `DUPLICATE` (operation log), nothing re-applied. Same movement id under a new operation id → `DUPLICATE` / `MOVEMENT_ID_ALREADY_USED` (see above).
- **Merge by name**: `ProductSyncHandler.mergeIntoExistingByName` answers `APPLIED` with `serverEntityId = E`, `reason = MERGED_INTO:E`; the log row keeps `entityId = X`, and `SyncPushService.redirectMergedProductIds` rewrites every later `productId: X` (same batch or later batches) to E. The stock entry is therefore **added** to E's stock (it is an entry, not an absolute set). This relies on `CREATE_PRODUCT.entityId == payload.id` (documented for the client). The name lookup (`ProductRepository.findByName`, `LOWER(name)`) does **not** filter archived products: a creation named like an **archived** product E merges into E, the stock entry lands on E and E **stays archived** (hidden from the catalogue) — pinned by `mergedByName_ontoArchivedProduct_pinsTodaysBehaviour`; merge semantics deliberately unchanged in this story, the app is told how to react (Mobile §5).
- **Variants**: the catalogue has no variant model (no variant table or API; `variant_id` is only a nullable column of the stock tables) and `CREATE_PRODUCT` carries no variant: a product created this way is simple, its initial entry is sent without `variantId` and lands on the `variant_id IS NULL` level.
- **Creation refused** (e.g. `PLAN_LIMIT_EXCEEDED`): the following entry is `REJECTED` / `PRODUCT_NOT_FOUND`, nothing written.
- **Legacy `CREATE_PRODUCT.stockQuantity`**: only stored in the legacy column `products.stock_quantity` (returned as `stockQuantity` in the products delta); it creates neither a `stock_levels` row nor a movement, so it never doubles a `RECORD_STOCK_ENTRY` (5 + entry 5 → stock 5).
- **Quantity ≤ 0**: `RecordStockEntryUseCase` refuses it → `REJECTED` with the existing reason text `Entry quantity must be positive` (not an `ErrorCode`; kept as is for V1 2.0.1, `conflictData = null`). The client never shows it: French label from the 16-10 policy.
- **REST**: `POST /api/v1/products` with `stockQuantity` also only fills the legacy column; online-only clients chain `POST /api/v1/products/{id}/stock/entry` (not atomic) — the sync path is the one to use.

## Spec Change Log

## Review Triage Log

### 2026-11-01 — Review pass
- verdicts: 26 findings — high 0, medium 3, low 19, false 3, maybe-false 1
- findings:
  - `[false]` `[reject]` (verification-gap) No gaps — n/a.
  - `[low]` `[reject]` (intent) Feature tested at the API surface, form only described — API-only repo; the form is the mobile deliverable (`m17-11`).
  - `[low]` `[reject]` (intent) Rejection for quantity ≤ 0 is free English text, not an ErrorCode — existing sync reason kept for V1 2.0.1; sync reasons are never shown (16-10 rejection policy), the client validates the field first.
  - `[low]` `[patch]` (intent) Merge gives previous stock + Q, not « = Q » — test now proves addition (10 → 14); documented.
  - `[low]` `[reject]` (intent) Legacy `products.stock_quantity` column still shows 5 in the pull — legacy display column, not a stock level; documented in the Mobile section.
  - `[low]` `[reject]` (intent) Integer rule only documented — covered by `StockAdjustSyncHandlerTest.handle_fractionalQuantity_isRejected_useCaseNeverCalled`.
  - `[false]` `[reject]` (intent) sprint-status / epics edits outside the patch — excluded from the review diff on purpose; present in the working tree.
  - `[false]` `[reject]` (blind) Missing `m17-11` key and epics tick — same as above.
  - `[low]` `[reject]` (blind) Boundary says « existing code (French) » vs English free-text reason — see intent row; fix would edit this build's intent.
  - `[low]` `[reject]` (blind) FORBIDDEN / MODULE_INACTIVE rows not tested here — existing behaviours with their own tests (store scope, module guard).
  - `[low]` `[patch]` (blind) Plan-limit test does not check `conflictData` — asserted.
  - `[medium]` `[patch]` (blind) Merge test does not prove addition; pull of X unchecked — E seeded with 10, 14 asserted; pull has no X.
  - `[medium]` `[patch]` (blind) Same movement id re-sent under a new operation id — handler answers DUPLICATE (same content) or a stable REJECTED; test + Mobile line.
  - `[low]` `[patch]` (blind) `quantityBefore` of the pulled movement unchecked — asserted.
  - `[low]` `[reject]` (blind) No upper bound for the initial quantity — integer range enforced by `requireIntegralInt`; a business cap is a product decision.
  - `[low]` `[patch]` (blind) Products with variants not addressed — Mobile section states the verified behaviour.
  - `[low]` `[patch]` (blind) Plan-limit fixture fragile (null limit, unique columns, template counted) — hardened.
  - `[low]` `[patch]` (blind) curl recipe: no paging, fresh device id, no way to get S — fixed in the Mobile section.
  - `[low]` `[reject]` (blind) Flutter AC vague — basic non-regression check only; Flutter is a test client.
  - `[low]` `[patch]` (edge) Missing result for an operation id gives an NPE — explicit assertion.
  - `[low]` `[patch]` (edge) `pullAll` can loop forever — page cap + cursor advance assertion.
  - `[low]` `[patch]` (edge) FREE `max_products` null or 1 — asserted before use.
  - `[low]` `[patch]` (edge) Cloned filler rows may copy unique columns — unique columns excluded.
  - `[low]` `[patch]` (edge) Absolute product counts — before/after counts.
  - `[medium]` `[patch]` (edge) Retried entry with the same movement id → INTERNAL_ERROR — same root as the blind movement-id row.
  - `[maybe-false]` `[patch]` (edge claim) Merge may target an archived product — test pins today's behaviour; documented for the mobile agent; merge semantics unchanged in this story.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -DforkCount=1 -DargLine=-Xmx1536m -Dtest='com.keevo.sync.**'` then `'com.keevo.catalog.**'`, `'com.keevo.inventory.**'`, `'com.keevo.architecture.**'` -- expected: green, one at a time

**Manual checks (if no CLI):**
- AC with curl; backend stopped with `fuser -k 4500/tcp`; never forge tokens.

**After review (2026-11-01):** `InitialStockAtCreationFullContextTest` 12 green (adds: merge onto E with 10 → 14 and nothing for X in the pull, merge onto an archived product pinned, same movement id under a new operation id → `DUPLICATE`, different content → `MOVEMENT_ID_ALREADY_USED`; `conflictData` asserted on the plan-limit rejection; hardened helpers), `StockAdjustSyncHandlerTest` 17, `StockQueryAdapterTest` 5, `StockMovementRepositoryAdapterTest` 6 green. Wider suites left to the coordinator.

**Results (2026-11-01, before review):** new `sync/sync/adapter/in/rest/InitialStockAtCreationFullContextTest` — 9 tests green on the unchanged server (quantity given + pull, stock entry queued before the product, empty quantity, replay → `DUPLICATE`, merge by name incl. a later batch, legacy `stockQuantity` with and without an entry, quantity −2 / 0 rejected, creation refused at the Free limit → entry `PRODUCT_NOT_FOUND`). Chunked suites: `com.keevo.sync.**` 576, `com.keevo.catalog.**` 256, `com.keevo.inventory.**` 387, `com.keevo.architecture.**` 32 — 0 failures. Live (local backend, WhatsApp noop, FCM off): register → `/auth/login` → `/auth/select-tenant` → push `CREATE_PRODUCT` + `RECORD_STOCK_ENTRY` 5 → both `APPLIED`; replay → both `DUPLICATE`; pull → product, one `STOCK_ENTRY` with the client movement id (`quantityChange` 5, `quantityAfter` 5), `stockLevels` 5 for the store; product without entry → no movement, no level. Redmi (Flutter test client `com.example.keevo.dev`, `adb reverse tcp:4500`): app relaunched, 3 pulls served at 22:38 UTC, no backend error. Backend stopped with `fuser -k 4500/tcp`.

## Mobile (React Native — dépôt AI/bref)

> Destinataire : agent IA qui implémente l'app Expo / React Native sur une branche du dépôt `/home/toor/Project/FreeLance/AI/bref` (base `develop`). Conventions API : section « Mobile » de `spec-16-11-sessions-longues-et-sures.md` ; protocole de synchro et politique de rejet : section « Mobile » de `spec-16-10-scope-employe-historique-transferts.md` ; messages d'erreur : `spec-16-14-api-prete-client-mobile.md` ; plafond produits : `spec-17-10-compteur-produits-ecran-abonnement.md`. Aucun nouvel endpoint, aucun changement de forme de réponse (compatible V1 2.0.1).

**Statut** : API vérifiée — le contrat est tenu par les opérations de synchro existantes ; seul ajout serveur (compatible) : une entrée remise en file avec un id de mouvement déjà enregistré répond `DUPLICATE` / `MOVEMENT_ID_ALREADY_USED` au lieu de `INTERNAL_ERROR` (test `sync/sync/adapter/in/rest/InitialStockAtCreationFullContextTest.java`) — mobile à faire.

**Clé de suivi mobile** : `m17-11-stock-initial-creation-produit`

### Ce qui est fait côté API

- `CREATE_PRODUCT` garde l'identifiant généré par l'appareil (`payload.id`) : le produit serveur a le même id que le produit local.
- `RECORD_STOCK_ENTRY` qui référence ce produit, poussé dans le **même lot** (ou plus tard), crée **un** mouvement `STOCK_ENTRY` dont l'id est l'`entityId` de l'opération (l'id du mouvement local) et met le niveau de stock de la boutique à Q. Le serveur traite toujours les créations de produits avant les autres opérations du lot (même si l'horloge de l'appareil les a mises après).
- Sans opération de stock : produit créé, **aucune ligne de stock, aucun mouvement** (stock 0).
- Relecture du même lot (mêmes `operationId`) : `DUPLICATE`, rien n'est rejoué (un seul produit, un seul mouvement).
- Même entrée remise en file sous un **nouvel** `operationId` mais avec le même id de mouvement M : `DUPLICATE` (aucune seconde entrée) si M existe déjà avec le même produit, la même boutique et la même quantité ; si M est déjà pris par un autre mouvement : `REJECTED` / `MOVEMENT_ID_ALREADY_USED` (rien n'est écrit).
- Nom d'un produit **archivé** : la fusion vise aussi ce produit archivé E (`MERGED_INTO:<E>`) ; la quantité s'ajoute au stock de E, qui **reste archivé** (invisible dans le catalogue).
- Variantes : le catalogue n'a pas de variantes (aucune API de variantes, `CREATE_PRODUCT` n'en porte pas) : le produit créé est simple, l'entrée part **sans `variantId`**.
- Nom déjà existant (même nom, casse ignorée) : `CREATE_PRODUCT` → `APPLIED`, `serverEntityId` = id du produit existant E, `reason` = `MERGED_INTO:<E>` ; toutes les opérations qui portent `productId: X` (même lot ou lots suivants) sont redirigées vers E : la quantité initiale **s'ajoute** au stock de E (c'est une entrée, pas une valeur absolue).
- Création refusée (ex. `PLAN_LIMIT_EXCEEDED`, 16-14/17-10) : l'entrée de stock qui suit est `REJECTED` / `PRODUCT_NOT_FOUND`, rien n'est écrit.
- Champ historique `CREATE_PRODUCT.stockQuantity` : rangé seulement dans la colonne historique `products.stock_quantity` (renvoyée comme `stockQuantity` dans le delta `products`) ; **ne crée ni stock ni mouvement**. Ne pas l'envoyer.

### À utiliser

| Cas | Opérations poussées | Réponse / effet | Source |
|---|---|---|---|
| Quantité saisie Q > 0 | `CREATE_PRODUCT` (`entityId` = X, `payload.id` = X) puis `RECORD_STOCK_ENTRY` (`entityId` = M, `payload` = `{productId: X, storeId: S, quantity: Q, notes?}`) | les deux `APPLIED` ; pull : `products` X, `stockMovements` `{id: M, movementType: "STOCK_ENTRY", quantityChange: Q, quantityAfter: Q}`, `stockLevels` `{productId: X, storeId: S, quantity: Q}` | `sync/.../handler/ProductSyncHandler.java`, `sync/.../handler/StockAdjustSyncHandler.java` |
| Champ vide ou 0 | `CREATE_PRODUCT` seul | `APPLIED` ; aucun `stockLevels` ni `stockMovements` pour X | idem |
| Lot renvoyé (coupure réseau) | mêmes `operationId` | `DUPLICATE` pour chaque opération : retirer de la file | `sync/.../service/SyncPushService.java` |
| Entrée remise en file (nouvel `operationId`, même M) | `RECORD_STOCK_ENTRY` `entityId` = M | même contenu → `DUPLICATE` ; contenu différent → `REJECTED` / `MOVEMENT_ID_ALREADY_USED` | `StockAdjustSyncHandler` |
| Nom d'un produit archivé E | idem « quantité saisie » | `MERGED_INTO:<E>`, entrée `APPLIED` sur E, E reste archivé | `ProductSyncHandler.mergeIntoExistingByName` |
| Nom déjà pris par E | idem « quantité saisie » | `CREATE_PRODUCT` `APPLIED`, `serverEntityId` = E, `reason` = `MERGED_INTO:<E>` ; entrée `APPLIED` sur E (stock de E + Q) | `ProductSyncHandler.mergeIntoExistingByName`, `SyncPushService.redirectMergedProductIds` |
| Création refusée | idem | `CREATE_PRODUCT` `REJECTED` (`PLAN_LIMIT_EXCEEDED` + `conflictData`) ; entrée `REJECTED` / `PRODUCT_NOT_FOUND` | `CreateProductUseCase`, `StockOperationService` |
| Quantité ≤ 0 (ne doit pas arriver) | `RECORD_STOCK_ENTRY` `quantity: -2` | `REJECTED`, `reason` = `Entry quantity must be positive` (texte, pas un code), `conflictData` = `null` | `RecordStockEntryUseCase` |
| Quantité non entière | `quantity: 1.5` | `REJECTED`, `reason` commençant par `Invalid quantity` | `AbstractSyncOperationHandler.requireIntegralInt` |

### À implémenter côté mobile

1. **Champ « Quantité initiale »** dans « Nouveau produit », **facultatif**, sous les prix, clavier numérique, entier ≥ 0 (pas de décimales, pas de signe moins) ; aide : « Laissez vide si vous n'avez pas encore de stock ». Vide ou 0 → aucune opération de stock. Ce champ ne bloque jamais l'enregistrement ; une saisie invalide affiche « Entrez un nombre entier positif » sous le champ. Le champ ne concerne que la **boutique active** (libellé « Quantité initiale — <nom de la boutique> »). Masqué pour un produit dont le suivi du stock est désactivé — **à venir avec 17-12** (`track_stock`) : d'ici là, toujours affiché.
2. **Une seule transaction locale** : insérer le produit X ; si Q > 0, insérer le mouvement local M (`STOCK_ENTRY`, `quantityBefore` 0, `quantityChange` Q, `quantityAfter` Q, note « Stock initial ») et le niveau de stock (X, S) = Q ; mettre en file `CREATE_PRODUCT` **puis** `RECORD_STOCK_ENTRY` (`clientTimestamp` croissants). Un échec annule le tout (pas de produit sans son stock ni l'inverse).
3. **Identifiants** : `CREATE_PRODUCT.entityId` **doit être égal** à `payload.id` (la redirection après fusion par nom s'appuie dessus) ; `RECORD_STOCK_ENTRY.entityId` = id du mouvement local M (jamais l'id du produit), `payload.productId` = X, `payload.storeId` = boutique active (pour un employé : sa boutique, sinon `FORBIDDEN`). Ne pas envoyer `stockQuantity` dans `CREATE_PRODUCT`, ne pas utiliser `STOCK_ADJUST` pour le stock initial.
4. **Affichage hors ligne** : le catalogue et la fiche produit montrent Q tout de suite (stock local) ; l'historique montre « Entrée de stock — Stock initial ». Après synchro, le pull renvoie le mouvement avec l'id M : **mettre à jour** la ligne locale M (pas de doublon) et le niveau (X, S).
5. **Fusion par nom** (`APPLIED` + `reason` `MERGED_INTO:<E>`) : remplacer localement X par E partout (produit, niveaux, mouvements, opérations encore en file), supprimer la copie X, garder l'entrée sur E ; avis : « Un produit du même nom existait déjà : la quantité a été ajoutée à son stock. » Le stock affiché de E vient ensuite du pull. **Si E est archivé** (connu localement ou `archived: true` au pull suivant) : avis « Un produit archivé porte déjà ce nom : la quantité a été ajoutée à son stock. Restaurez-le pour le revoir dans le catalogue. » avec l'action « Restaurer » (`UNARCHIVE_PRODUCT`, soumis au plafond 17-10) ; ne pas laisser l'owner croire que le produit a disparu. Pour éviter le cas, le formulaire peut vérifier hors ligne le nom contre les produits locaux, archivés compris, et proposer la restauration avant d'enregistrer.
6. **Création refusée** (`CREATE_PRODUCT` `REJECTED`) : politique 16-10 « création de catalogue refusée » — supprimer le produit local X **et ses suites** (mouvement M, niveau (X, S), opération `RECORD_STOCK_ENTRY` en file ou revenue `PRODUCT_NOT_FOUND`) sans les mettre en quarantaine ; `PLAN_LIMIT_EXCEEDED` → feuille « plafond atteint » avec `conflictData` (16-14/17-10).
7. **Remise en file** : une entrée renvoyée sous un nouvel `operationId` doit garder son `entityId` M ; `DUPLICATE` → retirer de la file (le mouvement serveur M est celui du pull). `REJECTED` / `MOVEMENT_ID_ALREADY_USED` (M déjà pris par un autre mouvement : bug client) → quarantaine comme ci-dessous, ne jamais régénérer M en silence.
8. **Entrée refusée seule** (produit `APPLIED`, entrée `REJECTED`, ex. `Entry quantity must be positive`, `FORBIDDEN`) : opération de stock → **quarantaine** (16-10, « Entrée de stock bloquée — action requise ») ; le produit reste. `MODULE_INACTIVE` (module `inventory` coupé) → reste en file. Jamais de `reason`, de code ni d'identifiant à l'écran.
9. **Variantes** : aucune — le champ s'affiche pour tout produit (suivi), l'entrée n'envoie pas de `variantId`. Si des variantes arrivent un jour, cette règle sera revue avec leur story.
10. Le plafond produits (17-10) s'applique à la création comme avant ; le stock initial ne change rien au compteur.

### Critères d'acceptation mobile / scénarios de test

Préparation : backend local (`--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false`, arrêt `fuser -k 4500/tcp`), connexion par l'API comme en 16-11 (`BASE`, `AT`) avec un `deviceId` fixe, `S` = id de la boutique active (voir le script).

```bash
u(){ cat /proc/sys/kernel/random/uuid; }
DEV=$(u)   # un id d'appareil FIXE : le même pour select-tenant, le push et le renvoi
# connexion (16-11) avec ce DEV : .../auth/select-tenant -d "{\"loginToken\":\"$LT\",\"tenantCode\":\"KV-XXXXXX\",\"deviceId\":\"$DEV\",\"deviceLabel\":\"Poste de test\"}" → AT
# S = boutique active : owner → la boutique choisie dans l'app parmi GET /api/v1/stores ; employé → sa boutique (`scope.storeId` du pull)
S=$(curl -s $BASE/api/v1/stores -H "Authorization: Bearer $AT" | jq -r '.data[0].id')
X=$(u); M=$(u); T=$(date -u +%Y-%m-%dT%H:%M:%SZ)
BATCH='{"deviceId":"'$DEV'","operations":[
 {"operationId":"'$(u)'","operationType":"CREATE_PRODUCT","entityId":"'$X'","payload":{"id":"'$X'","name":"Savon Azur","price":1500},"clientTimestamp":"'$T'"},
 {"operationId":"'$(u)'","operationType":"RECORD_STOCK_ENTRY","entityId":"'$M'","payload":{"productId":"'$X'","storeId":"'$S'","quantity":5,"notes":"Stock initial"},"clientTimestamp":"'$T'"}]}'
curl -s -X POST $BASE/api/v1/sync/push -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -d "$BATCH" | jq -c '.data.results[] | {status, serverEntityId, reason}'
# renvoi du MÊME lot (mêmes operationId, même DEV) → DUPLICATE ×2
curl -s -X POST $BASE/api/v1/sync/push -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -d "$BATCH" | jq -c '.data.results[] | .status'
# pull complet : suivre hasMore / nextCursor
CURSOR=""; : > pull.jsonl
while :; do
  P=$(curl -s "$BASE/api/v1/sync/pull${CURSOR:+?cursor=$CURSOR}" -H "Authorization: Bearer $AT")
  echo "$P" | jq -c '.data.entities' >> pull.jsonl
  [ "$(echo "$P" | jq -r .data.hasMore)" = "true" ] || break
  CURSOR=$(echo "$P" | jq -r .data.nextCursor)
done
jq -s -c --arg x $X '{m: [.[].stockMovements[]? | select(.productId==$x)], l: [.[].stockLevels[]? | select(.productId==$x)]}' pull.jsonl
```

1. **Quantité saisie** — Given l'owner hors ligne, When il crée « Savon Azur » avec 5, Then le catalogue affiche 5 tout de suite ; après synchro, le serveur a 5 pour la boutique active et l'historique montre une seule entrée (pas de doublon après le pull).
2. **Champ vide** — Given le champ laissé vide, When il enregistre, Then aucun message d'erreur, stock 0, aucun mouvement local ni serveur.
3. **Saisie invalide** — Given « -2 » ou « 1,5 », Then message sous le champ ; vider le champ permet d'enregistrer.
4. **Renvoi** — Given la synchro coupée après l'envoi, When elle reprend et renvoie le lot (même appareil, mêmes `operationId`), Then `DUPLICATE`, toujours 5, une seule entrée. Idem si l'app remet l'entrée en file sous un nouvel `operationId` avec le même M.
5. **Nom existant** — Given « Riz » existe avec 10, When l'owner crée « riz » avec 4 hors ligne puis synchronise, Then un seul « Riz » avec 14, avis de fusion, aucun produit en double.
5b. **Nom d'un produit archivé** — Given « Riz » archivé, When l'owner crée « Riz » avec 4 puis synchronise, Then avis « produit archivé » avec « Restaurer » ; après restauration, « Riz » réapparaît avec 4.
6. **Plafond** — Given un compte Free à son plafond, When l'owner crée un produit avec 5 hors ligne puis synchronise, Then produit et stock local retirés, feuille « plafond atteint », rien en quarantaine.
7. **Produit non suivi** — (après 17-12) Given un produit non suivi, Then le champ n'est pas affiché.

### Final verification (orchestrator)

- **Review:** 26 findings — patched entries: medium 2 (same movement id under a new operation id → DUPLICATE / `MOVEMENT_ID_ALREADY_USED` instead of INTERNAL_ERROR; merge test proving addition and the pull), low 11 (test hardening, plan-limit `conflictData`, `quantityBefore`, variants and curl recipe in the Mobile section, archived-merge behaviour pinned); 1 routed: creating a product named like an archived one merges into the hidden product → new story `17-13-creation-produit-nom-archive` (key + brief, unique `lower(name)` index needs a product decision); rejected rows with reasons in the triage log.
- **Follow-up review recommended: false** — no high patched, one medium server change (additive lookup before recording).
- **Backend:** every package one at a time (sync, inventory, catalog, shared, identity, admin, subscription, commerce, store, crm, invoicing, reporting, messaging, feedback, acceptance, architecture) — 4791 tests, 0 failures.
- **Redmi (Flutter test client `com.example.keevo`):** app online, pull at 23:05:16 UTC, no backend error.
