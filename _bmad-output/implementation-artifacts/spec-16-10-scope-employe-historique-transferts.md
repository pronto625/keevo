---
title: '16-10 Employee store scope on transfer history (REST + sync pull)'
type: 'bugfix'
created: '2026-11-01'
status: 'done'
baseline_revision: 'fc1e431f424267892442ff4d15a80037fff262bd'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
warnings: []
deferred:
  - summary: >-
      Other stores' stock movements, data already pulled on employee devices, and the scope source are routed to story 16-13.
    evidence: |-
      Review 16-10, 2026-11-01: StockMovementDeltaProvider is unscoped; incremental pulls never delete rows already on devices; the provider reads the security context instead of the pull command.
    location: >-
      keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Security residue of v1s-12-9: `GET /api/v1/stock/transfers` returns the transfers of every store to an EMPLOYEE, and the sync pull (`StockTransferDeltaProvider`) sends every store's transfers to an employee's device, while creation (`POST`) and reception (`/complete`) are already limited to the employee's store (`CurrentActor.assignedStoreId()`).

**Approach:** When the caller has an assigned store (EMPLOYEE), force the history query and the transfers delta pull to transfers whose source **or** destination is that store; an explicit filter on another store returns an empty page (no 403, no information about other stores). OWNER (no assigned store) is unchanged.

## Boundaries & Constraints

**Always:**
- Scope source of truth: `CurrentActor.assignedStoreId()` (null for OWNER / super-admin → no scope).
- REST: with a scope S, the effective filter is `(source = S OR destination = S)` AND the caller's own filters; `source`/`destination`/`store` pointing to another store → empty page with the usual page shape (`content: []`, `totalElements: 0`).
- Sync pull: with a scope S, the transfers provider returns only rows with `source_store_id = S OR destination_store_id = S`, keeping the keyset cursor semantics (pagination and `next` position unchanged for the filtered set).
- Both paths covered by tests with an EMPLOYEE of store A, transfers A→B, B→A, B→C: employee sees the first two only; OWNER sees all three.

**Never:**
- No change to other delta providers, to transfer creation/completion, or to the app.
- No 403 for other-store filters (avoid revealing store existence).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Employee, no filter | A→B, B→A, B→C | A→B, B→A | — |
| Employee, `store=B` | same | only transfers involving A and B (A→B, B→A) | — |
| Employee, `source=C` | same | empty page | — |
| Owner, no filter | same | all three | — |
| Employee pull | same, since epoch | A→B, B→A; cursor continues correctly over pages | — |
| Owner pull | same | all three | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/inventory/transfer/adapter/in/web/StockTransferController.java:128-155` -- `getHistory` builds `GetTransferHistoryQuery(source, destination, from, to, page, size, store, status)`; POST (73-80) and `/complete` (110-116) show the `CurrentActor.assignedStoreId()` pattern.
- `GetTransferHistoryQuery` + its service/repository (`getTransferHistoryService.execute`) -- add the scope field and predicate.
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/StockTransferDeltaProvider.java:35-40` -- `DeltaPageQuery.fetch(em, columns, "stock_transfers", CURSOR, since, after, limit)`; `DeltaPageQuery` may need an optional extra predicate with bound parameters.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/CurrentActor.java:114-130` -- `assignedStoreId()`.
- Tests: existing transfer controller / history service tests, `StockTransferDeltaProvider` / sync pull integration tests (find with grep).

## Tasks & Acceptance

**Execution:**
- Query/service/repository -- scope predicate; controller passes `CurrentActor.assignedStoreId()`; tests per the matrix.
- `DeltaPageQuery` (optional predicate, parameters bound, never string-concatenated values) + `StockTransferDeltaProvider` scope from `CurrentActor`; Postgres test of the pull for employee vs owner incl. pagination.
- Epics doc -- tick the 16.10 item.

**Acceptance Criteria:**
- Given the matrix data, when an employee of A calls the history and pulls transfers, then B→C never appears in either; the owner sees it in both.

## Spec Change Log

## Review Triage Log

### 2026-11-01 — Review pass
- verdicts: 22 findings — high 0, medium 4, low 11, false 2, maybe-false 5
- findings:
  - `[medium]` `[defer]` (blind) Stock movements of other stores still pulled by employees — outside this story's surface; routed to new story 16-13 (key + brief).
  - `[medium]` `[defer]` (blind, edge, intent, verification-gap other) Data pulled before the fix stays on devices; store reassignment keeps old rows — app purge routed to 16-13.
  - `[medium]` `[patch]` (blind, edge, intent) Employee without a store stalled the whole pull — empty page, WARN, pull not failed; provider + `SyncPushService.pull` tests.
  - `[low]` `[defer]` (blind, intent) Scope read from the security context instead of the pull command — routed to 16-13.
  - `[low]` `[patch]` (blind) Unscoped overloads (repository default, query constructors) — removed.
  - `[low]` `[reject]` (blind) Controller test partly asserts its own stub — the captor assertions are the point; end-to-end added.
  - `[medium]` `[patch]` (blind) Keyset test did not cover out-of-scope rows between in-scope ones / cursor ties — added (sizes 1, 2, full page).
  - `[low]` `[patch]` (blind, edge ×2) `DeltaPageQuery.fetch` filter arguments unguarded — validated (null params, params without filter, reserved names); tests.
  - `[false]` `[reject]` (blind) Tracking files not updated — updated at finalization.
  - `[low]` `[patch]` (blind, intent) No end-to-end pull test — through `SyncPushService.pull` for employee, owner and storeless employee.
  - `[low]` `[patch]` (blind) Filter combinations / A→C, C→A untested — history data and cases extended (status + date window).
  - `[low]` `[reject]` (intent) `store=B` returns A↔B instead of empty (literal AC1) — spec decision: scope ANDed with filters, nothing outside A leaks.
  - `[low]` `[reject]` (intent) 403 for a storeless employee on REST — existing `CurrentActor` rule, same as POST/complete.
  - `[maybe-false]` `[reject]` (verification-gap other) Keyset index not checked for the employee predicate — performance only; data volume per tenant small.
  - `[false]` `[reject]` (verification-gap) No gaps — n/a.
  - `[maybe-false]` `[reject]` (intent) UI already filtered by active store — confirms the visible symptom was limited; server fix stands.
  - `[low]` `[reject]` (intent) `SyncKeysetIndexesMatchProvidersTest` needs an actor — now irrelevant (provider no longer throws).
  - `[maybe-false]` `[reject]` (edge duplicate of reassignment) — routed with 16-13.
  - `[maybe-false]` `[reject]` (edge duplicate of storeless employee) — patched above.
  - `[low]` `[reject]` (edge duplicate of DeltaPageQuery null params) — patched above.
  - `[maybe-false]` `[reject]` (blind) Async pull refactor would drop the scope — covered by the 16-13 item (scope via the pull command).
  - `[low]` `[reject]` (verification-gap other) — duplicate of the device-data entry.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -DforkCount=1 -DargLine=-Xmx1536m -Dtest='com.keevo.inventory.**'` then `'com.keevo.sync.**'`, `'com.keevo.architecture.**'` -- expected: green, one package at a time

## Auto Run Result

Status: done

**Summary:** an employee only gets transfers whose source or destination is their store, both from `GET /api/v1/stock/transfers` (scope ANDed with the caller's filters, other-store filters → empty page) and from the sync pull (bound-parameter predicate in `DeltaPageQuery`, keyset paging unchanged). The owner is unscoped. An employee without a store gets an empty transfers page instead of a failed pull. Unscoped overloads removed.

**Review:** 22 findings — 7 patched entries (2 medium, 5 low), 3 routed to the new story 16-13 (stock movements of other stores, purge of data already on devices / reassignment, scope via the pull command), 12 rejected with reasons. Follow-up review recommended: false.

**Verification:** inventory 387, sync 506, architecture 32 — all green (one package at a time).

## Mobile (React Native — dépôt AI/bref)

> Destinataire : agent IA qui implémente l'app Expo / React Native sur une branche du dépôt `/home/toor/Project/FreeLance/AI/bref` (base `develop`). **Ne pas reprendre** la synchro Bref (`lib/database/sync.ts`, `local-mutations.ts`, `mutation-defaults.ts`) : le protocole Keevo ci-dessous est différent (file d'opérations + pull delta par curseur). Conventions API : section « Mobile » de `spec-16-11-sessions-longues-et-sures.md`. Stories fusion correspondantes : 22.4 à 22.7 (`_bmad-output/planning-artifacts/epics-fusion-keevo-bref.md`). Cette section sert aussi de **référence du protocole de synchro** pour les autres specs.

**Statut** : API livrée (commit `05314200`) — mobile à faire.

**Clé de suivi mobile** : `m16-10-scope-employe-historique-transferts`

### Ce qui est fait côté API

- Un **employé** ne reçoit plus que les transferts dont la source **ou** la destination est sa boutique, dans l'historique REST comme dans le pull de synchro. Un filtre sur une autre boutique renvoie une page vide (jamais 403, pour ne pas révéler l'existence d'une boutique). Un employé sans boutique reçoit une liste vide. L'**owner** n'est pas limité.
- **À venir (16-13, API pas encore livrée)** : même confidentialité pour les mouvements de stock et les autres données propres à une boutique, **purge sur l'appareil** des données d'autres boutiques (à la mise à jour de l'app et à chaque changement de boutique de l'employé, suivie d'un pull complet de la nouvelle boutique), portée transmise par la commande de pull. Ne pas l'implémenter avant la livraison de 16-13 ; prévoir dans le schéma local de pouvoir supprimer les lignes par boutique.

### À utiliser

**Historique des transferts** : `GET /api/v1/stock/transfers?source=&destination=&store=&status=&from=&to=&page=0&size=25` (`keevo/backend/src/main/java/com/keevo/inventory/transfer/adapter/in/web/StockTransferController.java`) → `{"data": {"content": [...], "totalElements", "totalPages", "number", "size"}}` (pagination **dans** `data`, pas dans `meta`). Pour un employé, la portée de sa boutique est ajoutée côté serveur à ses propres filtres.

**Protocole de synchro** (`keevo/backend/src/main/java/com/keevo/sync/sync/adapter/in/rest/SyncController.java`, `.../application/service/SyncPushService.java`, DTO dans `.../rest/dto/`) :

- **Push** `POST /api/v1/sync/push` (OWNER/EMPLOYEE) `{"deviceId": "<uuid de l'installation>", "operations": [{"operationId": "<uuid unique>", "operationType": "CREATE_SALE", "entityId": "<uuid>", "payload": {…}, "clientTimestamp": "<ISO-8601 UTC>"}]}` → `{"data": {"processedAt", "results": [{"operationId", "status", "serverEntityId", "reason", "conflictData"}]}}` (`conflictData` : détails d'un conflit sur `APPLIED`/`CONFLICT` ; depuis 16-14 aussi les détails d'un rejet `PLAN_LIMIT_EXCEEDED` — `{entity, limit, current, plan}` ; `null` sinon ; source `.../rest/dto/SyncOperationResultDto.java`). Chaque opération est traitée seule (succès partiel possible) ; le serveur ordonne les créations référencées (catégories/boutiques/contacts, puis produits/employés, puis sessions d'inventaire) puis l'ordre `clientTimestamp`.
  - `status` : `APPLIED` (retirer de la file ; `reason` = `LWW_SUPERSEDED` si une version plus récente existait déjà : la version serveur reviendra par le pull), `DUPLICATE` (déjà traitée : retirer), `CONFLICT` (`reason` = `STOCK_NEGATIVE`, appliquée mais stock passé négatif : retirer, prévenir), `REJECTED` (voir politique ci-dessous).
  - Types d'opérations et module de chacune : `CREATE_SALE`, `VALIDATE_SALE`, `CANCEL_SALE`, `CREATE_DAY_CLOSURE` (`pos`) ; `CREATE_PRODUCT`, `CREATE_DRAFT_PRODUCT`, `UPDATE_PRODUCT`, `PROMOTE_PRODUCT`, `ARCHIVE_PRODUCT`, `UNARCHIVE_PRODUCT`, `CREATE_CATEGORY`, `RENAME_CATEGORY`, `TOGGLE_CATEGORY` (`catalog`) ; `STOCK_ADJUST`, `RECORD_STOCK_ENTRY` (`inventory`) ; `STOCK_TRANSFER` (`multi-store`) ; `CREATE_INVENTORY_SESSION`, `SAVE_INVENTORY_COUNT`, `VALIDATE_INVENTORY` (`counting`) ; `CREATE_CLIENT`, `UPDATE_CLIENT`, `ARCHIVE_CLIENT`, `UNARCHIVE_CLIENT`, `CREATE_SUPPLIER`, `UPDATE_SUPPLIER`, `ARCHIVE_SUPPLIER`, `UNARCHIVE_SUPPLIER` (`crm`) ; `CREATE_STORE`, `UPDATE_STORE`, `DEACTIVATE_STORE` (`store`) ; `CREATE_EMPLOYEE`, `DEACTIVATE_EMPLOYEE`, `REACTIVATE_EMPLOYEE`, `REASSIGN_EMPLOYEE`, `SUBMIT_FEEDBACK` (`core`) ; `CREATE_INVOICE`, `UPDATE_INVOICE`, `ISSUE_INVOICE`, `PAY_INVOICE`, `CANCEL_INVOICE` (`invoicing`, charges utiles : `keevo/contracts/invoice-sync-payloads.json`). Charges utiles des autres : handlers `keevo/backend/src/main/java/com/keevo/sync/sync/application/handler/` et l'émetteur Flutter (référence ci-dessous) — reproduire **à l'identique**.
  - Erreurs de requête : 423 `SYNC_REQUIRED` « Synchronisation requise — données trop anciennes » (`details`: `daysSinceLastSync`, `lastPushAt`) quand l'appareil n'a ni poussé ni tiré depuis plus de 7 jours ; 403 `DEVICE_ID_MISMATCH` « Ce device n'est pas associé à votre compte » (le `deviceId` appartient à un autre utilisateur).
- **Pull** `GET /api/v1/sync/pull?since=<ISO>&cursor=<opaque>` + en-tête `X-Device-Id` → `{"data": {"serverTimestamp", "entities": {"<clé>": [lignes]}, "counts": {"<clé>": n}, "hasMore", "nextCursor"}}`. Sans `since` : pull complet. Pages de 1 000 lignes par entité ; tant que `hasMore` est vrai, rappeler avec **le même `since`** et `cursor=nextCursor` ; n'enregistrer `serverTimestamp` comme prochain `since` qu'à la dernière page (il inclut une marge de 30 s : lignes re-reçues → upsert idempotent). Clés d'entités : `products`, `categories`, `stockLevels`, `stockMovements`, `stockTransfers`, `sales`, `dayClosures`, `stores`, `employees`, `clients`, `suppliers`, `inventorySessions`, `inventoryCounts`, `reports`, `invoices`, `auditEntries` (owner seulement). Les entités d'un module `DISABLED` reviennent vides mais leur curseur avance → pull complet quand le module redevient lisible (voir 16-3).

**Politique de rejet** (`REJECTED`, `reason` = nom d'`ErrorCode` ou texte) — à reproduire depuis la référence Flutter :
- *Transitoires* (garder en file, réessayer) : `OPTIMISTIC_LOCK`, `INTERNAL_ERROR`, `TRANSACTION_ROLLED_BACK`, `DATA_INTEGRITY_VIOLATION`, `UNAUTHORIZED`, `SUBSCRIPTION_NOT_FOUND`, `TENANT_NOT_FOUND`, `SERVICE_UNAVAILABLE`, `reason` absent.
- *Retenues* (module inactif ou en lecture seule) : `MODULE_INACTIVE`, `MODULE_READ_ONLY` → rien n'est annulé, l'opération reste et est renvoyée au démarrage puis au plus toutes les 6 h.
- *Définitives* : opérations d'argent / de stock (`CREATE_SALE`, `VALIDATE_SALE`, `CANCEL_SALE`, `CREATE_DAY_CLOSURE`, `STOCK_ADJUST`, `RECORD_STOCK_ENTRY`, `STOCK_TRANSFER`) → **quarantaine** (jamais supprimées ; écran « Opérations bloquées » : Renvoyer / Rattacher / Abandonner après export) ; créations de catalogue/contact/boutique refusées (ex. `PLAN_LIMIT_EXCEEDED`) → ligne locale fantôme annulée et suites retirées ; depuis 16-14, un rejet `PLAN_LIMIT_EXCEEDED` porte dans `conflictData` les mêmes `details` que le 403 REST (`{entity, limit, current, plan}`) — tout autre `REJECTED` garde `conflictData = null` ; autres → retirées après un avis.

### À implémenter côté mobile

1. Écriture locale + opération dans la **même transaction** SQLite ; `operationId` UUID unique par opération ; push par lots, toutes les 30 s et au retour du réseau, un seul push à la fois.
2. Pull : `X-Device-Id` = le même `deviceId` que le push et la session (16-11) ; pages enchaînées comme décrit ; une donnée locale encore en file n'est jamais écrasée par le pull ; un seul pull à la fois.
3. **Portée employé (16-10)** : l'app d'un employé ne demande et n'affiche que ce que le serveur renvoie ; ne pas compléter l'historique des transferts par des données d'autres boutiques ; filtre de boutique de l'écran historique limité à la boutique de l'employé (un filtre sur une autre boutique donne une liste vide, c'est normal : afficher « Aucun transfert »).
4. `SYNC_REQUIRED` (423) : bloquer les écritures, autoriser la lecture, inviter à synchroniser ; bandeaux d'avertissement à J5 / J6 (story fusion 22.7).
5. **Préparer 16-13** (à venir) : pouvoir supprimer localement toutes les lignes d'une boutique donnée et relancer un pull complet à la mise à jour de l'app ou quand le profil (16-11) indique une autre boutique pour l'employé.
6. Aucun `reason`, `operationType` ou identifiant brut à l'écran : libellés français (ex. « Vente bloquée — action requise »).

### Implémentation de référence (Flutter, `keevo/app`)

- `lib/core/sync/rest_sync_service.dart` (push, pull paginé, mergers, `X-Device-Id`), `lib/core/sync/sync_trigger_notifier.dart` (cadences), `lib/core/sync/sync_gate_guard.dart` (gate 7 j)
- `packages/keevo_core/lib/sync/sync_rejection_policy.dart` (classification des rejets), `lib/core/modules/module_sync_queue.dart`
- `lib/features/inventory/presentation/page/transfer_history_page.dart` (historique des transferts)

### Critères d'acceptation mobile / scénarios de test

Préparation : compte avec boutiques A, B, C (module `multi-store` actif), un employé de A connecté sur l'appareil, transferts A→B, B→A, B→C créés par l'owner.

```bash
curl -s "$BASE/api/v1/stock/transfers?page=0&size=25" -H "Authorization: Bearer $AT_EMPLOYE" | jq '.data.totalElements'   # 2
curl -s "$BASE/api/v1/stock/transfers?source=<id boutique C>" -H "Authorization: Bearer $AT_EMPLOYE" | jq '.data.content'  # []
curl -s "$BASE/api/v1/sync/pull" -H "Authorization: Bearer $AT_EMPLOYE" -H "X-Device-Id: curl-poste" | jq '.data.counts.stockTransfers'
```

1. **Historique employé** — Given l'employé de A, When il ouvre l'historique des transferts, Then il voit A→B et B→A seulement ; l'owner voit les trois.
2. **Filtre autre boutique** — Given l'employé, When le filtre vise C, Then « Aucun transfert », pas d'erreur.
3. **Pull employé** — Given une installation neuve de l'employé, When le pull complet se termine, Then la base locale ne contient que A→B et B→A.
4. **Pagination** — Given plus de 1 000 lignes d'une entité, Then l'app enchaîne les pages avec le même `since` et n'avance son `since` qu'à la fin.
5. **Opération retenue** — Given un module désactivé, When une opération de ce module est poussée, Then `REJECTED`/`MODULE_INACTIVE`, l'opération reste en file sans rollback et part après réactivation.
6. *(à venir, 16-13)* Given un employé dont l'appareil contient des transferts de B→C reçus avant 16-10, When l'app mise à jour synchronise, Then ces lignes disparaissent ; réaffecté de A vers B, l'historique de A disparaît et celui de B est téléchargé.
