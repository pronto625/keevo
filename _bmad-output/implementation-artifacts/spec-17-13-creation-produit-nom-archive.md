---
title: '17-13 Creating a product named like an archived one restores the archived product (limit-checked) instead of hiding the creation'
type: 'bugfix'
created: '2026-11-02'
status: 'done'
baseline_revision: '9f44f97d9fc36884a7274d45a4f6c4aa1bc5176d'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-17-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 17.13 (`epics-refonte-modulaire.md`, routed from the 17-11 review): a `CREATE_PRODUCT` whose name matches an **archived** product (case-insensitive; `uq_products_name` on `lower(name)` includes archived rows) is merged into that product (`MERGED_INTO:<E>`) which stays archived, so the new product and its initial stock become invisible. REST `POST /api/v1/products` answers 409 `PRODUCT_NAME_ALREADY_EXISTS` and CSV import skips the row.

**Approach (user decision 2026-11-02: « Restaurer l'archivé »):** when the name matches an archived product, the server restores that product (unarchive, subject to the 17-10 product-limit check under the same lock), keeps its existing data (price, category…), lets the following stock entry land on it, and tells the client it was restored. Same rule on sync, REST and CSV.

## Boundaries & Constraints

**Always:**
- Sync `CREATE_PRODUCT` matching an archived product E: restore E (limit-checked, same `product-limit:<tenant>` lock as 17-10) and answer `APPLIED` with the existing `MERGED_INTO:<E>` alias mechanics plus an additive marker that it was restored (e.g. `conflictData: {"restored": true}`), so queued ops on the client id land on E. At the limit: `REJECTED PLAN_LIMIT_EXCEEDED` with the 16-14 details, E stays archived, and the queued stock entry on the client id is not applied (existing behaviour for a refused creation: `PRODUCT_NOT_FOUND`).
- Matching an **active/draft** product: unchanged (merge, no restore).
- REST `POST /api/v1/products` with an archived name: restore and return the existing product (2xx, same response shape, additive `restored: true`); at the limit the usual 403 `PLAN_LIMIT_EXCEEDED`. Active/draft name: unchanged 409 `PRODUCT_NAME_ALREADY_EXISTS`.
- CSV import row with an archived name: restore (limit-checked) and count it as imported with a French row warning « Produit archivé restauré : <nom> »; its initial quantity follows 17-11/17-12 rules (tracked → entry, untracked → ignored with warning).
- Restoration emits the existing unarchive event/audit (« Produit restauré »), no raw ids on screen.
- Spec ends with « ## Mobile (React Native — dépôt AI/bref) » (what the app shows: « Produit restauré », local product replaced by E, local stock entry kept on E) and `m17-13-creation-produit-nom-archive` in the MOBILE block; update the 17-11 Mobile section (rule 5 / scenario 5b) to the new behaviour.

**Never:**
- No second product with the same name; no change of E's existing fields from the creation payload; no response shape removal (V1 2.0.1); no Flutter feature work.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Sync, archived match | E « Riz » archived; CREATE_PRODUCT X « riz » + RECORD_STOCK_ENTRY X 4 | E restored, `MERGED_INTO:E` + restored marker, stock(E)+=4, pull shows E active | — |
| Sync, at limit | same, Free at its limit | REJECTED PLAN_LIMIT_EXCEEDED (+details), E archived, entry not applied | — |
| Sync, active match | E active | unchanged merge (no restore marker) | — |
| REST, archived match | POST « riz » | 2xx, product E, `restored: true`, E active | at limit → 403 PLAN_LIMIT_EXCEEDED |
| REST, active match | POST « Riz » | 409 PRODUCT_NAME_ALREADY_EXISTS (unchanged) | — |
| CSV, archived match | row « riz » qty 3 (tracked) | E restored, +3, row counted imported with French warning | at limit → row failed with the limit message |
| Archived draft | E archived DRAFT | restored as draft (no limit check, 17-10 rule) | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/sync/sync/application/handler/ProductSyncHandler.java:160-175` -- `mergeIntoExistingByName` (`MERGED_INTO:` alias, called before the @Transactional use cases to avoid rollback-only).
- `keevo/backend/src/main/java/com/keevo/catalog/product/application/usecase/UnarchiveProductUseCase.java` -- 17-10 restore with limit check + lock (reuse).
- `keevo/backend/src/main/java/com/keevo/catalog/product/application/usecase/CreateProductUseCase.java:144-146` -- `existsByName` → 409.
- `keevo/backend/src/main/java/com/keevo/catalog/product/application/usecase/ImportCsvProductsUseCase.java:201` -- name exists → skip; `warnings[]` from 17-12.
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/service/SyncPushService.java` -- `MERGED_INTO` alias handling, rollback paths keeping `conflictData` (16-14).
- Tests: `InitialStockAtCreationFullContextTest` (`mergedByName_ontoArchivedProduct_pinsTodaysBehaviour` → becomes the new behaviour), `SubscriptionUsageFullContextTest` helpers, `ProductSyncHandlerTest`, `CreateProductUseCaseTest`, `ImportCsvProductsUseCaseTest`.
- Handover conventions: Mobile sections of `spec-16-11-*`, `spec-16-10-*`, `spec-16-14-*`, `spec-17-10-*`, `spec-17-11-*`.

## Tasks & Acceptance

**Execution:**
- Sync / REST / CSV restore paths per the matrix (reuse the 17-10 unarchive use case and lock), additive markers.
- Tests per matrix row (full-context PostgreSQL for sync and REST, unit for CSV).
- Mobile section (+ 17-11 rule 5 / scenario 5b updated), `m17-13` key, tick 17.13 in the epics doc.

**Acceptance Criteria:**
- Given a local backend (WhatsApp noop, FCM off) and an owner signed in through the API, when the sync and REST archived-match scenarios run over HTTP, then the product is restored, visible in the pull/list, and the initial stock is on it.

## Spec Change Log

## Review Triage Log

### 2026-11-02 — Review pass
- verdicts: 29 findings — high 0, medium 7, low 21, false 1, maybe-false 0
- findings:
  - `[low]` `[patch]` (verification-gap) CSV capacity after restore (draft vs active) untested — two tests at remaining=1.
  - `[medium]` `[patch]` (intent) POS `CREATE_DRAFT_PRODUCT` keeps the original bug — restore when possible, fallback merge when refused (sale never lost), same on REST draft.
  - `[low]` `[patch]` (intent) Archived DRAFT row untested on every surface — tests + `status` in the restore marker.
  - `[low]` `[patch]` (intent) Lookup outside the lock on sync and CSV — unarchive no-ops when already restored; marker only on a real restore.
  - `[low]` `[reject]` (intent) Audit label « Produit désarchivé » vs « Produit restauré » — existing label reused as the intent says (« existing unarchive event/audit »); French and user-readable.
  - `[false]` `[reject]` (intent) Spec deliverables outside the diff — excluded on purpose; present in the working tree.
  - `[low]` `[reject]` (intent) CSV path only mock-tested — the restore itself is the 17-10 unarchive, covered end to end; CSV wiring unit-tested incl. capacity.
  - `[medium]` `[patch]` (edge) POS draft on an archived name stays invisible — same root as the intent draft row.
  - `[low]` `[patch]` (edge) REST restore drops `stockQuantity` silently — REST never creates stock (legacy column, 17-11); documented in Javadoc and Mobile section.
  - `[low]` `[patch]` (edge) Stale object if `findById` empty after unarchive — `orElseThrow`.
  - `[low]` `[patch]` (edge) Concurrent restore in CSV gives duplicate audit and false warning — unarchive no-op when not archived.
  - `[low]` `[patch]` (edge) Concurrent restore in sync gives duplicate audit and false marker — same root.
  - `[low]` `[patch]` (edge) Archived draft skipped once the CSV quota is used — lookup before the quota skip.
  - `[medium]` `[patch]` (edge) `IllegalArgumentException` aborts the whole CSV import — PRODUCT_NOT_FOUND DomainException, row error.
  - `[medium]` `[patch]` (edge claim) « Same rule on sync, REST, CSV » not true for drafts — same root as the draft row.
  - `[low]` `[patch]` (edge claim) AC « initial stock on it » for REST — REST has no initial stock (documented).
  - `[medium]` `[patch]` (blind) REST DRAFT POST restores while sync draft does not — same root as the draft row.
  - `[medium]` `[patch]` (blind) Restore skips creation guards (employee resurrects an untracked product) — owner required when E is untracked; draft path falls back.
  - `[low]` `[patch]` (blind) REST `stockQuantity` dropped without signal — documented.
  - `[low]` `[patch]` (blind) CREATE_PRODUCT matching an archived DRAFT unspecified — test + marker status.
  - `[low]` `[patch]` (blind) Archived-draft rows skipped at quota — same root as the edge row.
  - `[low]` `[patch]` (blind) Two name queries in the import — single `findByName`.
  - `[medium]` `[patch]` (blind) Unarchive not-found aborts the import — same root as the edge row.
  - `[low]` `[patch]` (blind) Restore with leftover stock untested — test (additive).
  - `[low]` `[patch]` (blind) `clientId` equal to the archived id returns it still archived — restore on that branch; test.
  - `[low]` `[patch]` (blind) Replay after restore loses the marker — documented per the stored-result behaviour.
  - `[low]` `[patch]` (blind) Handler Javadoc contradicts the code — fixed.
  - `[low]` `[patch]` (blind) Stale names in controller/test — renamed.
  - `[low]` `[patch]` (blind) Import result does not count restores — additive `restored` counter.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -DforkCount=1 -DargLine=-Xmx1536m -Dtest='com.keevo.catalog.**'` then `'com.keevo.sync.**'`, `'com.keevo.inventory.**'`, `'com.keevo.architecture.**'` -- expected: green, one at a time

**Manual checks (if no CLI):**
- AC with curl; backend stopped with `fuser -k 4500/tcp`; never forge tokens.

**Results (2026-11-02, before review):** chunked suites `com.keevo.catalog.**` 285, `com.keevo.sync.**` 624, `com.keevo.inventory.**` 410, `com.keevo.architecture.**` 32 — 0 failures. New/changed tests: `InitialStockAtCreationFullContextTest` 17 green (archived match restored + pull + audit, active match without marker, at limit rejected with details / E archived / entry `PRODUCT_NOT_FOUND`, REST 200 + `restored`, REST 409 unchanged and 201 without `restored`, REST at limit 403), `CreateOrRestoreProductUseCaseTest` 4, `ImportCsvProductsUseCaseTest` 19 (+4 archived-name rows), `ProductSyncHandlerTest` 29 (+3), `ProductControllerTest` 16 (+2). Live (local backend, WhatsApp noop, FCM off, fresh owner via `/auth/register` → `/auth/login` → `/auth/select-tenant`): `CREATE_PRODUCT` « Riz » → archive → `CREATE_PRODUCT` X « riz » + `RECORD_STOCK_ENTRY` X 4 → `APPLIED`, `MERGED_INTO:E`, `conflictData {"restored":true}`, entry `APPLIED`; pull → E « Riz » price 1500 `archived: false`, stock level 4, nothing for X; re-archive → `POST /api/v1/products {"name":"RIZ","price":7777}` → 200, E (price 1500 kept), `restored: true`, listed; « riz » again → 409; « Haricot » → 201 without `restored`. Backend stopped with `fuser -k 4500/tcp`.

**Implementation notes:** the existing unarchive audit label is « Produit désarchivé » (`AuditLabels`), kept as is. Drafts (sync `CREATE_DRAFT_PRODUCT`, REST `status: DRAFT`) try the restore through `UnarchiveProductUseCase.tryExecute` (no throw at the limit, so the sync transaction is not marked rollback-only) and fall back to the plain merge. `UnarchiveProductUseCase` is a no-op (no event/audit, `false`) on a product that is no longer archived and throws `PRODUCT_NOT_FOUND` (DomainException) for an unknown id; callers set the `restored` marker only on `true`. CSV restored rows publish no `ProductImportedFromCsvEvent` (the unarchive event is their audit), are counted in `restored`, and a restored draft consumes no quota (looked up before the limit skip).

**Review fixes (2026-11-02):** drafts restore-or-merge (sync + REST), 17.12 owner rule on restore (FORBIDDEN / draft fallback), unarchive no-op + `PRODUCT_NOT_FOUND`, `CreateOrRestoreProductUseCase` reload `orElseThrow`, client id equal to the archived id restored, single `findByName` in the CSV import with archived drafts outside the quota, `restored` import counter, archived draft restored as a draft (`status: DRAFT` in the marker), leftover stock 6 + 4 = 10, replay → `DUPLICATE` (marker only on the first answer). Tests: `InitialStockAtCreationFullContextTest` 23, `CreateOrRestoreProductUseCaseTest` 13, `ImportCsvProductsUseCaseTest` 23, `ProductSyncHandlerTest` 34, `UnarchiveProductUseCaseTest` 7, `ProductControllerTest` 17, `ProductImportControllerTest` 7 — green.

## Mobile (React Native — dépôt AI/bref)

> Destinataire : agent IA qui implémente l'app Expo / React Native sur une branche du dépôt `/home/toor/Project/FreeLance/AI/bref` (base `develop`). Conventions API : section « Mobile » de `spec-16-11-sessions-longues-et-sures.md` ; protocole de synchro et politique de rejet : section « Mobile » de `spec-16-10-scope-employe-historique-transferts.md` ; messages d'erreur : `spec-16-14-api-prete-client-mobile.md` ; plafond produits : `spec-17-10-compteur-produits-ecran-abonnement.md` ; stock initial et fusion par nom : `spec-17-11-stock-initial-creation-produit.md` (règle 5 / scénario 5b mis à jour par cette story) ; produit non suivi : `spec-17-12-suivi-stock-par-produit.md`. Tous les ajouts sont **additifs** (compatibles V1 2.0.1) : aucun champ retiré, aucun code d'erreur ni raison de synchro changés.

**Statut** : API livrée (tests `sync/sync/adapter/in/rest/InitialStockAtCreationFullContextTest.java`, `catalog/.../CreateOrRestoreProductUseCaseTest.java`, `catalog/.../ImportCsvProductsUseCaseTest.java`) — mobile à faire.

**Clé de suivi mobile** : `m17-13-creation-produit-nom-archive`

### Ce qui est fait côté API

- Deux produits ne portent jamais le même nom (casse et espaces ignorés), archivés compris. Créer un produit dont le nom est celui d'un produit **archivé** E **restaure E** au lieu de cacher la création : E redevient visible, garde **ses** données (nom, prix, catégorie, suivi du stock…) — rien du formulaire de création ne les remplace.
- La restauration suit la règle 17-10 : E actif → soumis au plafond produits (même verrou que la création) ; E brouillon → restauré **en brouillon**, sans contrôle de plafond (même par un `CREATE_PRODUCT`). Elle est auditée comme une restauration manuelle (« Produit désarchivé », jamais d'identifiant à l'écran) ; si E a déjà été restauré entre-temps (par un autre appareil), rien n'est restauré ni audité une seconde fois et la réponse ne porte pas de marqueur.
- E **non suivi** (`trackStock: false`, 17-12) et l'auteur n'est pas l'owner : `CREATE_PRODUCT` / `POST /api/v1/products` → refusé `FORBIDDEN` (« Seul le propriétaire peut créer un produit sans suivi de stock ») ; création de brouillon → fusion simple sans restauration (voir ci-dessous).
- Nom d'un produit **visible** (actif ou brouillon) : comportement inchangé (fusion en synchro, 409 en REST, ligne refusée en import).
- **Synchro** `CREATE_PRODUCT` (`payload.id` = X) au nom de E archivé : `APPLIED`, `serverEntityId` = E, `reason` = `MERGED_INTO:<E>` (comme une fusion 17-11) **et** `conflictData` = `{"restored": true}` — `{"restored": true, "status": "DRAFT"}` si E était un brouillon archivé (restauré en brouillon). Les opérations en file sur X (dont `RECORD_STOCK_ENTRY`) sont redirigées vers E : la quantité saisie **s'ajoute** au stock que E avait gardé (ex. 6 restants + 4 = 10). Une fusion sur un produit visible garde `conflictData: null`.
- **Renvoi** du même `CREATE_PRODUCT` (même `operationId`) : `DUPLICATE`, sans `serverEntityId` ni `conflictData` — le marqueur `restored` n'est donné **qu'à la première réponse** (le serveur ne recalcule pas une opération déjà réglée) : l'app doit appliquer la fusion/restauration dès cette première réponse.
- Au plafond (Free) : `CREATE_PRODUCT` `REJECTED`, `reason` = `PLAN_LIMIT_EXCEEDED`, `conflictData` = `{entity: "products", limit, current, plan}` (16-14) ; E **reste archivé** ; l'entrée de stock sur X répond `REJECTED` / `PRODUCT_NOT_FOUND` (rien n'est écrit).
- `CREATE_DRAFT_PRODUCT` (brouillon créé au POS) : E est restauré **si c'est permis** (même marqueur) ; refusé (plafond atteint, ou E non suivi et auteur non owner) → **fusion simple** `MERGED_INTO:<E>` sans marqueur, E **reste archivé**, et la vente en file sur X est enregistrée sur E (jamais perdue).
- **REST** `POST /api/v1/products` au nom de E archivé : **HTTP 200** (au lieu de 201) avec le produit E (même forme de réponse, `status` dit s'il est brouillon) plus `"restored": true` ; au plafond : 403 `PLAN_LIMIT_EXCEEDED` (inchangé). Avec `"status": "DRAFT"` même règle que la synchro : restauration refusée → 200 avec E tel quel (`archived: true`, pas de `restored`). Rien de la requête n'est appliqué à E — `stockQuantity` en particulier est ignoré (en REST il ne crée jamais de stock, colonne historique seulement, 17-11). Une création ordinaire répond 201 **sans** champ `restored`.
- **Import CSV** : une ligne au nom de E archivé restaure E (plafond respecté), compte comme importée, avec l'avertissement de ligne « Produit archivé restauré : <nom> » et un nouveau compteur `restored` dans la réponse (additif, inclus dans `imported`) ; sa quantité initiale suit 17-11/17-12 (E suivi → entrée de stock sur E ; E non suivi → quantité ignorée avec son avertissement). Au plafond : ligne en erreur avec le message français du plafond. Un brouillon archivé est restauré même plafond atteint (les brouillons ne comptent pas).

### À utiliser

| Cas | Requête | Réponse / effet | Source |
|---|---|---|---|
| Création hors ligne au nom de E archivé | `CREATE_PRODUCT` X (+ `RECORD_STOCK_ENTRY` X/S/Q) | `APPLIED`, `serverEntityId` E, `reason` `MERGED_INTO:<E>`, `conflictData.restored` `true` (+ `status: "DRAFT"` si E brouillon) ; entrée `APPLIED` sur E | `ProductSyncHandler.mergeIntoExistingByName` |
| Brouillon POS au nom de E archivé | `CREATE_DRAFT_PRODUCT` X (+ vente) | restauré si permis (marqueur) ; sinon `MERGED_INTO:<E>` sans marqueur, E archivé, vente sur E | idem |
| Employé, E archivé non suivi | `CREATE_PRODUCT` X | `REJECTED` `FORBIDDEN` | idem |
| Idem, au plafond | idem | `REJECTED` `PLAN_LIMIT_EXCEEDED` + `conflictData` ; entrée `PRODUCT_NOT_FOUND` | `UnarchiveProductUseCase` |
| Nom d'un produit visible | idem | fusion 17-11 inchangée, `conflictData: null` | idem |
| Création en ligne au nom de E archivé | `POST /api/v1/products` | 200, `data` = E, `data.restored` = `true`, `data.archived` = `false` | `CreateOrRestoreProductUseCase`, `ProductController#createProduct` |
| Idem, au plafond | idem | 403 `PLAN_LIMIT_EXCEEDED` (détails 16-14) | idem |
| Nom d'un produit visible (en ligne) | idem | 409 `PRODUCT_NAME_ALREADY_EXISTS` (inchangé) | `CreateProductUseCase` |

### À implémenter côté mobile

1. **Réponse de synchro restaurée** (`APPLIED` + `MERGED_INTO:<E>` + `conflictData.restored == true`) : appliquer la fusion 17-11 (remplacer X par E partout : produit, niveaux, mouvements, opérations encore en file ; supprimer la copie X ; garder l'entrée de stock locale, désormais sur E), **et** marquer E non archivé localement ; si `conflictData.status == "DRAFT"`, E est un **brouillon** (le produit local devient un brouillon à valider par l'owner). Le marqueur n'existe que dans la première réponse (un renvoi répond `DUPLICATE`) : traiter la fusion au premier `APPLIED`. Une fusion sans marqueur sur un E archivé connu localement (brouillon POS refusé au plafond) : E reste archivé, la vente reste sur E. Avis (une fois) : « Produit restauré : <nom de E>. » suivi, si une quantité a été saisie, de « La quantité a été ajoutée à son stock. » Le stock et les champs affichés de E viennent ensuite du pull.
2. Ne jamais traiter `conflictData.restored` comme un conflit de stock (aucune bannière de conflit) ; toute autre clé inconnue de `conflictData` sur `APPLIED` reste ignorée.
3. **Refus au plafond** : politique 16-10 « création de catalogue refusée » comme 17-11 règle 6 (supprimer X et ses suites sans quarantaine, feuille « plafond atteint » avec `conflictData`). E reste archivé : la feuille peut proposer « Voir les produits archivés ».
4. **Création en ligne** : HTTP 200 + `restored: true` → même avis « Produit restauré : <nom> », ouvrir/afficher E (pas de nouveau produit, `status` dit s'il est brouillon) ; 200 sans `restored` (brouillon refusé) → E reste archivé ; 201 → création normale ; 409 → « Un produit avec ce nom existe déjà » (inchangé) ; 403 `FORBIDDEN` (employé, produit archivé non suivi) → « Seul le propriétaire peut restaurer ce produit ». Ne jamais compter sur `stockQuantity` : la quantité initiale passe par une entrée de stock. La quantité initiale saisie part ensuite sur l'id renvoyé (E) comme une création normale (`RECORD_STOCK_ENTRY` ou entrée REST), seulement si E est suivi (`trackStock`).
5. **Formulaire hors ligne (facultatif, recommandé)** : si le nom saisi correspond (casse ignorée) à un produit local archivé, prévenir avant d'enregistrer : « Un produit archivé porte ce nom : il sera restauré avec ses informations actuelles. » — l'enregistrement reste possible ; le prix ou la catégorie saisis ne remplacent pas ceux du produit restauré.
6. **Import CSV** : afficher les avertissements de ligne tels quels (« Produit archivé restauré : <nom> ») ; jamais d'identifiant ni de code.
7. Compteur « Produits » (17-10) : une restauration d'un produit actif compte comme un produit actif de plus — relire `GET /api/v1/subscription/me` à l'ouverture de l'écran comme d'habitude.

### Critères d'acceptation mobile / scénarios de test

Préparation : backend local (`--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false`, arrêt `fuser -k 4500/tcp`), connexion par l'API comme en 16-11 (`BASE`, `AT`, `DEV` fixe), `S` = boutique active (voir 17-11).

```bash
u(){ cat /proc/sys/kernel/random/uuid; }
E=$(u); T=$(date -u +%Y-%m-%dT%H:%M:%SZ)
push(){ curl -s -X POST $BASE/api/v1/sync/push -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -d "$1" | jq -c '.data.results[] | {status, serverEntityId, reason, conflictData}'; }
push '{"deviceId":"'$DEV'","operations":[{"operationId":"'$(u)'","operationType":"CREATE_PRODUCT","entityId":"'$E'","payload":{"id":"'$E'","name":"Riz","price":1500},"clientTimestamp":"'$T'"}]}'
push '{"deviceId":"'$DEV'","operations":[{"operationId":"'$(u)'","operationType":"ARCHIVE_PRODUCT","entityId":"'$E'","payload":{"productId":"'$E'"},"clientTimestamp":"'$T'"}]}'
X=$(u); M=$(u)
push '{"deviceId":"'$DEV'","operations":[
 {"operationId":"'$(u)'","operationType":"CREATE_PRODUCT","entityId":"'$X'","payload":{"id":"'$X'","name":"riz","price":9999},"clientTimestamp":"'$T'"},
 {"operationId":"'$(u)'","operationType":"RECORD_STOCK_ENTRY","entityId":"'$M'","payload":{"productId":"'$X'","storeId":"'$S'","quantity":4,"notes":"Stock initial"},"clientTimestamp":"'$T'"}]}'
# → APPLIED, serverEntityId E, reason MERGED_INTO:E, conflictData {"restored":true} ; puis APPLIED
# en ligne : archiver E à nouveau, puis
curl -s -X POST $BASE/api/v1/products -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -d '{"name":"RIZ"}' -w '\n%{http_code}\n'
# → 200, data.id = E, data.restored = true, data.archived = false
```

1. **Restauration hors ligne** — Given « Riz » archivé, When l'owner crée « riz » avec 4 hors ligne puis synchronise, Then avis « Produit restauré : Riz », un seul « Riz » visible dans le catalogue avec son prix d'origine et un stock augmenté de 4, aucun « riz » en double.
2. **Plafond** — Given un compte Free à son plafond et « Riz » archivé, When l'owner crée « Riz » avec 4 puis synchronise, Then produit local et stock retirés, feuille « plafond atteint », « Riz » reste dans les archivés, rien en quarantaine.
3. **Produit visible** — Given « Riz » actif, When l'owner crée « riz », Then fusion 17-11 (avis de fusion), aucun avis « restauré ».
4. **En ligne** — Given « Riz » archivé, When l'owner crée « RIZ » en ligne, Then avis « Produit restauré : Riz », la fiche affichée est « Riz » (prix d'origine) ; au plafond : feuille « plafond atteint ».
5. **Brouillon archivé** — Given un brouillon « Riz » archivé, When l'owner crée « Riz », Then « Riz » réapparaît en brouillon (réponse `status: "DRAFT"`), sans contrôle de plafond.
5b. **Brouillon POS au plafond** — Given « Riz » archivé et un compte Free au plafond, When un vendeur vend un nouveau « Riz » (brouillon) hors ligne puis synchronise, Then la vente est enregistrée (sur Riz), aucun avis « restauré », « Riz » reste archivé.
5c. **Stock restant** — Given « Riz » archivé avec 6 en stock, When l'owner crée « riz » avec 4, Then « Riz » restauré avec 10.
6. **Import CSV** — Given « Riz » archivé (suivi), When l'owner importe une ligne « riz ; 3 », Then « 1 importé », avertissement « Produit archivé restauré : Riz », stock de Riz + 3.

### Final verification (orchestrator)

- **Review:** 29 findings — medium 7, low 21, false 1; patched entries: POS / REST drafts restore when possible and fall back to the plain merge when refused (sale never lost), owner required to resurrect an untracked product, unarchive no-op without audit when already visible and `PRODUCT_NOT_FOUND` instead of `IllegalArgumentException`, client id equal to the archived id restores, CSV single lookup + archived drafts before the quota skip + `restored` counter, archived DRAFT marker `status: DRAFT`, leftover-stock and replay behaviour pinned, Javadoc and names. 0 deferred; rejected rows with reasons in the triage log (audit label « Produit désarchivé » kept).
- **Follow-up review recommended: false** — no high finding; mediums patched with full-context coverage.
- **Backend:** every package one at a time (catalog, sync, inventory, commerce, shared, reporting, admin, identity, subscription, store, crm, invoicing, messaging, feedback, acceptance, architecture) — 4915 tests, 0 failures (first run stopped by host memory pressure after 4 packages, resumed for the remaining 12).
- **Redmi (Flutter test client 2.0.22):** app online, pull at 04:13:31 UTC, no backend error.
