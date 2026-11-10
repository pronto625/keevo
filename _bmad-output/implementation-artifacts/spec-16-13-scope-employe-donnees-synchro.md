---
title: '16-13 Employee store scope on every store-specific sync dataset, scope through the pull command, server-side re-seed on store change'
type: 'bugfix'
created: '2026-11-01'
status: 'done'
baseline_revision: 'b3cc5cb377697c66ccd2a027a6c7a47e021aa775'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 16.13 (`epics-refonte-modulaire.md`, routed from the 16-10 review): the sync pull still sends an EMPLOYEE of store A the stock movements, sales, day closures, inventory sessions/counts, colleagues and reports of every store; the store scope (16-10 transfers) is read from the static security context inside the provider instead of the pull command; after a store reassignment the device keeps the old store's rows and never receives the new store's history (incremental `since`).

**Approach:** Resolve the employee scope once in `SyncController.pull` and carry it in `PullCommand`; every store-specific provider applies it as a bound-parameter filter; the pull response gains an additive `scope` object so the client can detect a change and purge; the server remembers the last pulled scope per device and, when it changes, answers that pull from EPOCH so the new store's history is re-seeded. Device purge itself is the mobile part (React Native, other repo) — documented in the spec's Mobile section; this repo delivers the API only.

## Boundaries & Constraints

**Always:**
- Scope source: `CurrentActor` read **only** in `SyncController.pull` → `PullCommand.scope` (store id + actor user id + `restricted` flag). OWNER / super-admin: unrestricted. EMPLOYEE with a store S: restricted to S. EMPLOYEE without a store: restricted with no store → every store-scoped dataset returns an empty page, never an exception (an exception freezes the cursor). Legacy `PullCommand` constructors fail closed (restricted, no store).
- Employee scope per dataset (OWNER unchanged): `stockMovements` `store_id = S`; `sales` (+ items) `store_id = S` (all sellers of S); `dayClosures` `store_id = S`; `inventorySessions` `store_id = S`; `inventoryCounts` via `session_id IN (SELECT id FROM inventory_sessions WHERE store_id = S)`; `employees` `store_id = S`; `reports` `actor_id = <actor user id>` (REST parity, `ReportController`); `stockTransfers` unchanged predicate (source or destination = S) but scope from the command. Unchanged for everyone: `stores`, `stockLevels` (inter-store availability is an employee feature, REST `MultiStoreStockController` allows it), `products`, `categories`, `clients`, `suppliers`, `invoices`; `auditEntries` stays ownerOnly.
- Filters go through `DeltaPageQuery.fetch(..., filter, params)` with bound parameters; keyset shape (`FROM t WHERE <CURSOR> >= :since ... ORDER BY <CURSOR>, CAST(id AS text)`) kept so `SyncKeysetIndexesMatchProvidersTest` holds; providers read no security context.
- Response (additive, V1-safe): `scope: {"storeId": "<uuid>|null", "restricted": true|false}` on every pull page, plus `"reseed": true` on the page(s) of a pull answered from EPOCH because the scope changed.
- Re-seed: V37 adds `last_scope_store_id` (+ a marker for "known") to `public.user_sync_state`; on the first page of a pull (no cursor), if the device's stored scope differs from the command's (known and different), the pull ignores `since` (EPOCH) and sets `reseed`; the stored scope is updated when that pull completes successfully. First pull of a device (unknown stored scope) is not a change.
- Spec ends with the « Mobile (React Native — dépôt AI/bref) » section and the `m16-13-scope-employe-donnees-synchro` key in the MOBILE block of sprint-status.

**Never:**
- No change to REST endpoints (multi-store stock, reports, sales history), to push handlers, or to product/client/supplier exposure (product decision, out of scope).
- No deliverable change in `keevo/app` (Flutter test client) — the device purge is specified for the RN app only.
- No response shape change other than the additive fields; V1 2.0.1 apps must keep pulling (they ignore unknown keys; they cannot purge — documented limit).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Employee of A pulls | movements/sales/closures/sessions/counts/employees in A and B, reports by owner and employee | only A rows; reports only the employee's own; stores, stock levels, products, clients all stores | — |
| Owner pulls | same data | everything (unchanged) | — |
| Employee without store | — | store-scoped datasets empty; others normal; `scope.restricted=true, storeId=null` | no failure, cursor advances |
| Reassigned A→B, next pull with `since=T` | device last pulled with scope A | pull from EPOCH, `reseed: true`, only B rows; stored scope becomes B after the last page | partial/failed pull → stored scope unchanged, next pull re-seeds again |
| Same scope, normal pull | stored scope A, command A | incremental from `since`, no `reseed` | — |
| First pull of a new device | no stored scope | normal pull (from client `since`), stored scope set | — |
| Paging | > 1000 rows, mixed stores | keyset continues correctly over filtered rows | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/sync/sync/adapter/in/web/SyncController.java:194-224` -- builds `PullCommand` with `CurrentActor.isOwner()`; resolve the scope here (`CurrentActor.assignedStoreIdOrNull()`, `isEmployee()`), set `scope` on the response DTO.
- `keevo/backend/src/main/java/com/keevo/sync/sync/domain/port/in/SyncUseCase.java:34` -- `PullCommand(actorId, tenantId, since, cursor, boolean owner)`; legacy constructors default owner=false (keep fail-closed for the scope too).
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/service/SyncPushService.java:493-632` -- pull loop (ownerOnly skip `:514-516`, module skip `:517-521`, failure keeps `since` `:530-534,547-549`), `serverTimestamp`, opaque `PullCursor` (`:581-632`; carry the reseed decision in it so later pages stay from EPOCH); `UserSyncState` update.
- `keevo/backend/src/main/java/com/keevo/shared/application/port/sync/DeltaEntityProvider.java:24-57` -- add `queryDeltaPage(since, after, limit, PullScope)` default delegating to the current method (InvoiceDeltaProvider + ~47 test call sites keep compiling).
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/` -- `StockMovementDeltaProvider`, `SaleDeltaProvider` (sale_items follow their sales), `DayClosureDeltaProvider`, `InventorySessionDeltaProvider`, `InventoryCountDeltaProvider`, `EmployeeDeltaProvider`, `ReportsDeltaProvider` (`actor_id` = user id, see `ReportHistoryService.reportActorId:63-68`), `StockTransferDeltaProvider:134,153-166` (drop the `CurrentActor` read, keep `STORE_SCOPE`); `DeltaPageQuery.java:26,50-99` (filter + params, reserved names).
- `UserSyncState` entity/repository (sync module) + `public.user_sync_state` (`V1__baseline_public.sql:372-379`, keyed by device_id/user_id) -- new columns via `V37__sync_scope.sql`.
- `SyncPullResult` / `SyncPullResponseDto` -- add `scope`, `reseed`.
- Tests: `*DeltaProviderTest` (none yet for InventorySession/InventoryCount), `StockTransferDeltaProviderTest`, `StockTransferStoreScopePostgresIntegrationTest` (moves from SecurityContext to command scope), `SyncKeysetIndexesMatchProvidersTest:58-102`, `SyncPullServiceTest`, `SyncPushServicePullOwnerOnlyTest`, `SyncPushServiceModuleGuardTest`, `SyncControllerPullGateTest`, `DeltaPageQueryTest`.
- Mobile reference (behaviour only): `keevo/app/lib/core/sync/rest_sync_service.dart:908-920` (per-store local purge pattern), `:1133-1139` (`kFullPullRequestedKey` full pull), `session_claims_applier.dart:91-97` (store switch from profile); sync protocol and conventions in the Mobile sections of `spec-16-10-*` and `spec-16-11-*`.

## Tasks & Acceptance

**Execution:**
- `SyncUseCase.PullCommand` + new `PullScope` record (sync domain) -- scope field, legacy constructors fail closed -- single source of the scope.
- `SyncController.pull` -- resolve scope from `CurrentActor`, pass it, expose `scope`/`reseed` in the DTO -- only place reading the actor.
- `DeltaEntityProvider` -- scoped overload with default -- compatibility.
- Providers listed in the Code Map -- bound-parameter filters per the Always table; `StockTransferDeltaProvider` takes the scope from the argument -- confidentiality.
- `SyncPushService.pull` -- pass scope to providers; re-seed decision on the first page (stored scope vs command), carried in the cursor; update stored scope only after a successful last page -- re-seed.
- `V37__sync_scope.sql` + `UserSyncState` -- `last_scope_store_id uuid`, `last_scope_known boolean default false` -- per-device scope memory.
- Tests -- unit test per provider (owner unscoped, employee scoped with bound param, employee without store empty), new tests for InventorySession/InventoryCount providers, Postgres integration of a full employee pull over A/B data incl. paging, reassignment re-seed (reseed flag, EPOCH, only B rows, stored scope updated; failed pull keeps the old scope), first-device pull, keyset shape test updated.
- `_bmad-output/planning-artifacts/epics-refonte-modulaire.md` -- tick the 16.13 items -- tracking.
- This spec -- final section « ## Mobile (React Native — dépôt AI/bref) » (purge on scope change using `scope`/`reseed` and the profile `storeId`, keep rows referenced by unsynced queued operations, full re-pull, V1 limit) + `m16-13-scope-employe-donnees-synchro` in the MOBILE block of `sprint-status.yaml` -- handover.

**Acceptance Criteria:**
- Given an employee of A and data in A and B, when the employee pulls through `POST /api/v1/sync/pull` (real HTTP, local backend), then no movement, sale, closure, inventory session/count, colleague of B nor other users' report is in the response, while stores, stock levels and the catalogue still cover all stores.
- Given the same employee reassigned to B through the owner API, when the device pulls again with its previous `since`, then the response has `reseed: true`, `scope.storeId` = B and B's full history, and the next pull is incremental again.
- Given the Flutter test client on the Redmi signed in as an employee, when it syncs, then sync completes without error (basic check; no purge expected from the Flutter client).

## Spec Change Log

## Review Triage Log

### 2026-11-01 — Review pass
- verdicts: 26 findings — high 0, medium 5, low 10, false 7, maybe-false 4
- findings:
  - `[medium]` `[patch]` (edge) Owner ↔ storeless employee both stored as null store, never a reseed — `last_scope_restricted` remembered, whole scope compared; tests both directions.
  - `[medium]` `[patch]` (edge) Reassignment between pages of one pull mixes A and B rows and remembers B without reseed — scope pinned in the cursor, a differing later page restarts the pull with `reseed`; test.
  - `[low]` `[reject]` (edge) Client ignoring `hasMore` or a provider failing forever reseeds on every pull — Flutter and V1 clients follow `hasMore` (V1 `rest_sync_service.dart` ~870-894); a permanently failing provider already blocks the pull; cost only.
  - `[low]` `[reject]` (edge) Rolling deploy: old instance misreads the new two-character flags line — single backend instance in production; deploys are stop/start.
  - `[low]` `[reject]` (edge) Legacy 5-arg `SyncPullResult` reports `restricted:true` — the only production construction (`SyncPushService.java:572`) passes the real scope; legacy constructor used by tests only.
  - `[low]` `[reject]` (edge) Concurrent pulls of one device with different scopes race on `rememberScope` — one device runs one pull at a time (client single-flight); worst case one extra reseed.
  - `[false]` `[reject]` (edge) `InvoiceDeltaProvider` not scoped — invoices are tenant-wide and explicitly unchanged by the intent.
  - `[maybe-false]` `[reject]` (intent) Tests split controller / service / serialisation, reassignment simulated by scope — the implementation agent ran the full HTTP flow on a local backend (real login, `PATCH /employees/{id}/store`, reseed observed); if-true low.
  - `[medium]` `[patch]` (intent) R3 vs R3' scope comparison — same root as the first edge row.
  - `[false]` `[reject]` (intent) Reseed returns only 30 days of stock movements — the same 30-day window applies to every first install (`StockMovementDeltaProvider` design); a reseed equals a fresh install.
  - `[low]` `[patch]` (intent) Scoped providers' 3-arg overloads fail open — delegate to `PullScope.NONE`; tests pass `UNRESTRICTED` explicitly (no production caller, `DeltaEntityProvider.java:58` only reaches unscoped providers).
  - `[maybe-false]` `[reject]` (intent) Reseed needs `X-Device-Id` — every client sends it (V1 included); documented in the Mobile section; if-true low.
  - `[false]` `[reject]` (intent) Spec / sprint-status / epics not in the patch — excluded from the review diff on purpose; present in the working tree.
  - `[false]` `[reject]` (intent) TOML rule edits unrelated — committed separately in 4509ed51 (inside the baseline range of this diff).
  - `[low]` `[patch]` (blind) 3-arg overloads fail open — same root as the intent row above.
  - `[medium]` `[patch]` (blind) `restricted` not remembered — same root as the first edge row.
  - `[medium]` `[patch]` (blind) Mid-pull scope change — same root as the second edge row.
  - `[false]` `[reject]` (blind) Devices installed before the release keep cross-store data — the RN app starts from an empty device; the Flutter refonte app is not shipped; V1 cannot purge whatever the server says (documented limit).
  - `[low]` `[reject]` (blind) Reseed re-downloads tenant-wide datasets too — reassignment is rare; per-dataset EPOCH would add state to the single-`since` cursor.
  - `[maybe-false]` `[reject]` (blind) No indexes for the new filters — residual filter on cursor-leading keyset indexes; tenant volumes small; would need EXPLAIN on a large tenant; if-true low.
  - `[false]` `[reject]` (blind) Fallback scope NONE could make an owner app purge — no production path builds the legacy result (see edge row).
  - `[low]` `[patch]` (blind) `rememberScope` writes on every pull — only when unknown or different.
  - `[maybe-false]` `[reject]` (blind) Missing tests list — covered by the patches above (mid-pull, null↔null, row mapper); super-admin branch unreachable (`@PreAuthorize OWNER/EMPLOYEE`).
  - `[false]` `[reject]` (blind) Patch mixes TOML edits and omits handover files — see intent rows.
  - `[low]` `[patch]` (blind) WARN noise for storeless employees, dead null checks, fully qualified UUID — log lowered/reworded, checks removed, import.
  - `[low]` `[patch]` (verification-gap) `findAllByTenantId` with the V37 columns never run on PostgreSQL — integration assertion added.

## Design Notes

Why server-side re-seed in addition to the `scope` field: the RN client purges on a scope change, but nothing tells any client to fetch the new store's older rows (the cursor is a single client-held `since`). Answering that one pull from EPOCH fixes it for every client, V1 included; merges are idempotent upserts, so re-sending the tenant-wide datasets is safe. The decision must survive paging (stored in the cursor) and must not be committed until the pull's last page succeeds, otherwise an interrupted pull would lose the re-seed.

Implementation decisions (2026-11-01): `PullScope` lives in `shared.application.port.sync` (next to the `DeltaEntityProvider` SPI it parameterises — module providers such as `InvoiceDeltaProvider` cannot depend on `sync`); `PullCommand` also carries the `deviceId` (re-seed memory); scoped providers keep the 3-arg `queryDeltaPage` as the unrestricted (owner) view for direct callers/tests — the pull always calls the scoped overload. The pull endpoint is `GET /api/v1/sync/pull` (the AC's « POST » is a typo). The pull cursor's flag line carries reseed, remember-at-end and the pinned scope (restricted + store); a later page whose scope differs restarts the pull from the beginning as a re-seed; one-character cursors from before still decode (no pinned scope). The device scope (restricted + store, V37 `last_scope_restricted`) is written only when unknown or different. The scoped providers' 3-arg `queryDeltaPage` fails closed (`PullScope.NONE`).

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -DforkCount=1 -DargLine=-Xmx1536m -Dtest='com.keevo.sync.**'` then `'com.keevo.shared.**'`, `'com.keevo.inventory.**'`, `'com.keevo.commerce.**'`, `'com.keevo.reporting.**'`, `'com.keevo.identity.**'`, `'com.keevo.architecture.**'` -- expected: green, one package at a time, never two Maven builds in parallel

**Manual checks (if no CLI):**
- Local backend with `--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false`; owner + employee via `/auth/login` + `/auth/select-tenant` (no forged tokens); employee pull before/after reassignment as in the AC; Redmi basic sync as employee (`adb reverse tcp:4500 tcp:4500`, never share sheets / wa.me); stop with `fuser -k 4500/tcp`.

## Mobile (React Native — dépôt AI/bref)

> Destinataire : agent IA qui implémente l'app Expo / React Native sur une branche du dépôt `/home/toor/Project/FreeLance/AI/bref` (base `develop`). Lire d'abord la section « Mobile » de `spec-16-11-sessions-longues-et-sures.md` (conventions d'API, profil) puis celle de `spec-16-10-scope-employe-historique-transferts.md` (**protocole de synchro**) : cette section les complète.

**Statut** : API livrée (commit `2339512d`) — mobile à faire.

**Clé de suivi mobile** : `m16-13-scope-employe-donnees-synchro`

### Ce qui est fait côté API

- Le pull d'un **employé** ne contient plus que les données de **sa boutique** pour : `stockMovements`, `sales` (toutes les ventes de la boutique, tous vendeurs ; `items` suivent leur vente), `dayClosures`, `inventorySessions`, `inventoryCounts` (comptages des sessions de sa boutique), `employees` (collègues de sa boutique), `stockTransfers` (source **ou** destination = sa boutique, 16-10). `reports` : seulement **ses propres** rapports (même règle que `GET /api/v1/reports`). Restent **pour toutes les boutiques** : `stores`, `stockLevels` (disponibilité inter-boutiques), `products`, `categories`, `clients`, `suppliers`, `invoices`. `auditEntries` : owner seulement (inchangé). L'**owner** reçoit tout.
- Employé **sans boutique** : toutes les entités propres à une boutique reviennent vides (jamais d'erreur ; le curseur avance).
- Chaque page de pull porte en plus (ajouts, ignorés par les apps V1) :
  - `scope` : `{"storeId": "<uuid>" | null, "restricted": true | false}` — la portée pour laquelle la page a été calculée (`restricted=false` = owner ; `restricted=true, storeId=null` = employé sans boutique).
  - `reseed` : `true` sur **toutes les pages** d'un pull que le serveur a recommencé depuis le début parce que la portée de l'appareil a changé depuis son dernier pull terminé (employé réaffecté). Sinon `false`.
- Le serveur mémorise par appareil (`X-Device-Id`) la portée (`restricted` + `storeId`) du dernier pull **terminé** (dernière page, sans échec). Au pull suivant, si elle diffère (changement de boutique, employé sans boutique devenu owner ou l'inverse), il ignore le `since` du client (pull depuis le début, `reseed: true`) : l'historique complet de la nouvelle portée est renvoyé. Un pull interrompu ou en échec ne mémorise rien → le suivant recommence le re-seed. Premier pull d'un appareil : pas de re-seed. **Sans `X-Device-Id`, aucun re-seed** : toujours l'envoyer.
- **Re-seed en cours de pull** : le curseur `nextCursor` fige la portée du pull. Si la portée change entre deux pages (réaffectation pendant un pull paginé), la page suivante **recommence le pull depuis le début** : `reseed: true`, `scope` = nouvelle portée, les lignes repartent de zéro pour toutes les entités. Le client doit donc vérifier `scope`/`reseed` **à chaque page**, pas seulement à la première.
- Limite V1 2.0.1 : les apps installées reçoivent le re-seed (nouvelles données) mais ne purgent pas les anciennes lignes ; seule l'app RN purge.

### À utiliser

`GET /api/v1/sync/pull?since=<ISO>&cursor=<opaque>` + `X-Device-Id` (protocole complet : spec 16-10) → `{"data": {"serverTimestamp", "entities", "counts", "hasMore", "nextCursor", "scope": {"storeId", "restricted"}, "reseed"}}`.
Profil : `GET /api/v1/auth/profile` (16-11) → rôle et boutique de l'employé (source d'identité de l'app).

### À implémenter côté mobile

1. **Mémoriser la portée locale** (`storeId` + `restricted`) avec le curseur de synchro, mise à jour à la fin d'un pull complet (dernière page).
2. **Changement de portée détecté** — par le profil (`storeId` ou rôle différent de la portée mémorisée, au démarrage / au renouvellement de session) **ou** par n'importe quelle page de pull, y compris une page suivante (`scope` différent de la portée mémorisée ou de celle de la page précédente, ou `reseed: true` alors que la page précédente ne l'avait pas) : avant d'appliquer les lignes de la page, **purger** localement les données propres à une boutique qui ne sont plus dans la portée : `stock_movements`, `sales` + lignes de vente, `day_closures`, `inventory_sessions` + `inventory_counts`, collègues (`employees`), `stock_transfers` dont ni la source ni la destination n'est la nouvelle boutique, `reports` d'autres auteurs. Employé sans boutique : purger toutes ces tables. Owner : rien à purger.
3. **Ne jamais purger** une ligne référencée par une opération encore en file (non poussée) ni en quarantaine : la garder jusqu'au push, puis elle sera purgée au pull suivant.
4. Après la purge : **pull complet** (sans `since`), pages enchaînées comme d'habitude ; enregistrer la nouvelle portée et le `serverTimestamp` seulement à la dernière page. Le serveur renvoie de toute façon l'historique (`reseed`) si l'app garde son `since`.
5. **Mise à jour de l'app** (première ouverture de la version RN qui embarque 16-13) : faire une fois la purge selon la portée du profil puis un pull complet (les données d'autres boutiques reçues avant 16-13 restent sinon sur l'appareil).
6. L'écran de l'employé n'affiche que ce que le serveur envoie ; aucun identifiant brut ni libellé technique (`reseed`, `scope`) à l'écran. Pendant le re-téléchargement : « Mise à jour des données de votre boutique… ».

### Implémentation de référence (Flutter, `keevo/app` — comportement seulement)

- `lib/core/sync/rest_sync_service.dart` : suppression des lignes d'une boutique (`CREATE_STORE` annulé, ~l. 908-920) ; pull complet forcé via `kFullPullRequestedKey` (~l. 1133-1139).
- `lib/features/auth/presentation/provider/session_claims_applier.dart` (~l. 91-97) : changement de boutique de l'employé lu au renouvellement.
- Le client Flutter ne purge pas (pas de livraison Flutter) : il reçoit seulement le re-seed.

### Critères d'acceptation mobile / scénarios de test

Préparation : compte avec boutiques A et B (module `multi-store`), ventes, mouvements, clôtures, inventaires et transferts dans A et B, un employé de A connecté sur l'appareil.

```bash
curl -s "$BASE/api/v1/sync/pull" -H "Authorization: Bearer $AT_EMPLOYE" -H "X-Device-Id: curl-poste" | jq '.data | {scope, reseed, counts}'
# owner : réaffecter l'employé vers B (API employés), puis le même appareil avec son since précédent :
curl -s "$BASE/api/v1/sync/pull?since=$SINCE" -H "Authorization: Bearer $AT_EMPLOYE" -H "X-Device-Id: curl-poste" | jq '.data | {scope, reseed}'   # reseed true, scope.storeId = B
```

1. **Pull employé** — Given une installation neuve de l'employé de A, When le pull complet se termine, Then la base locale ne contient aucune vente, mouvement, clôture, inventaire, transfert sans A, ni collègue de B, ni rapport d'un autre ; les boutiques, niveaux de stock et le catalogue couvrent A et B.
2. **Mise à jour de l'app** — Given un appareil d'employé contenant des données de B reçues avant 16-13, When la nouvelle version synchronise, Then ces lignes disparaissent.
3. **Réaffectation A → B** — Given l'employé réaffecté par l'owner, When l'app synchronise (profil ou `scope`/`reseed`), Then les données de A disparaissent et l'historique de B est téléchargé ; la synchro suivante est incrémentale (`reseed: false`).
4. **Opération en file** — Given une vente de A pas encore poussée, When la réaffectation est détectée, Then la vente reste en file et localement jusqu'à son push, puis est purgée.
5. **Pull interrompu** — Given un re-seed coupé après la première page, When l'app relance, Then le serveur recommence le re-seed (`reseed: true`) et l'app termine sans doublon.
6. **Employé sans boutique** — Then aucune donnée propre à une boutique sur l'appareil, pas d'erreur de synchro.
7. **Réaffectation pendant un pull paginé** — Given plus de 1 000 lignes et l'employé réaffecté entre deux pages, When l'app demande la page suivante avec `nextCursor`, Then la réponse a `reseed: true` et la nouvelle `scope` ; l'app purge et continue jusqu'à `hasMore: false` sans garder de ligne de l'ancienne boutique.

## Auto Run Result

Status: done

**Summary:** the employee pull scope is resolved once in `SyncController.pull` (`PullScope`: restricted, store, actor) and carried by `PullCommand` to every provider; an EMPLOYEE of S only receives S's stock movements, sales (+ items), day closures, inventory sessions/counts, colleagues, transfers involving S, and their own reports; stores, stock levels, catalogue, clients, suppliers and invoices stay tenant-wide; an employee without a store gets empty store datasets without failing the pull. The response gains `scope {storeId, restricted}` and `reseed`. V37 remembers each device's last completed scope (`last_scope_known`, `last_scope_restricted`, `last_scope_store_id`); a different scope makes that pull restart from EPOCH (`reseed: true`), including when the scope changes between two pages (scope pinned in the cursor); the scope is saved only after the last successful page and only when it changed.

**Files:** `shared/application/port/sync/PullScope.java`, `DeltaEntityProvider` (scoped overload), `DeltaPageQuery` (`fetchForStore`, `fetchForActor`), 8 providers in `sync/sync/application/provider/`, `SyncUseCase`, `SyncController`, `SyncPushService` (scope comparison, cursor flags + pinned scope, remember), `SyncPullResult` / `SyncPullResponseDto`, `UserSyncState` + adapter, `V37__sync_scope.sql`; tests (`StoreScopedDeltaProvidersTest`, `InventorySession/InventoryCountDeltaProviderTest`, `EmployeeScopePullPostgresIntegrationTest`, `SyncPullServiceTest`, `SyncControllerPullGateTest`, updated transfer/keyset tests); epics doc (16.13 ticked); Mobile section of this spec; `m16-13` key.

**Review:** 26 findings — patched entries: medium 2 (owner ↔ storeless employee scope change, scope change between pages), low 4 (3-arg overloads fail closed, scope written only on change, log/cleanup, PostgreSQL row-mapper assertion); 0 deferred; rejected with reasons in the triage log (invoices tenant-wide, 30-day movement window same as a fresh install, single-instance deploys, unreachable legacy constructors, pre-release devices: RN starts empty and V1 cannot purge).

**Follow-up review recommended: true** — two medium patches reworked the pull cursor (pinned scope, mid-pull restart, remember flag); a second look at `SyncPushService.pull` / `PullCursor` restart paths is the unverified risk.

**Verification:** backend, one package at a time: sync, shared, inventory, commerce, reporting, identity, invoicing, catalog, store, subscription, crm, admin, messaging, feedback, acceptance, architecture — 4748 tests, 0 failures. Live HTTP check by the implementation agent (real login, employee/owner pulls over two stores, reassignment through `PATCH /employees/{id}/store`, reseed then incremental). Redmi (Flutter test client) as employee: sync completes, « En ligne », device scope recorded (restricted, store) after the corrected V37 applied on boot.

**Residual risks:** V1 2.0.1 devices cannot purge rows already pulled (documented); store filters are residual on cursor-leading keyset indexes (fine at current volumes); test tenant `KV-1D0VLM` left in keevo_dev.

### Deferred items routed 2026-11-10
- [ ] [Deferred-from: 21.8 review, 2026-11-10] Portée boutique des lectures de stock multi-boutiques [keevo/backend/src/main/java/com/keevo/inventory/stock/adapter/in/web/MultiStoreStockController.java:55,67] — `GET /api/v1/stock/overview` et `GET /api/v1/stock/stores/{storeId}/products` n'appliquent aucune portée : un EMPLOYEE / MANAGER / VIEWER lit le stock de n'importe quelle boutique (l'outil IA de 21.8 est, lui, limité à la boutique du membre). Trancher : besoin métier (disponibilité inter-boutiques) ou écart à la règle 16-13 ; dans le second cas, même limitation qu'en 21.8 (404/403 français hors de sa boutique). Réglé par un test full-context employé boutique A → boutique B.
