---
title: '17-10 Subscription « Produits » usage counter — pin the API contract, find the client root cause, hand over to mobile'
type: 'bugfix'
created: '2026-11-01'
status: 'done'
baseline_revision: '1e87a540e52758b0f9eb508398c1c9d0c81167df'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-17-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 17.10 (`epics-refonte-modulaire.md`, from v1s-16-25): the « Produits » usage counter on the app's subscription screen shows 0 while `GET /api/v1/subscription/me` returns `currentProducts = N`. Since 2026-11-01 this repo delivers the API only; the app is rebuilt in React Native elsewhere, so the fix here is to make the API contract certain and tell the mobile agent exactly how to display the counter (and what made the Flutter client show 0).

**Approach:** Prove over real HTTP that `/subscription/me` returns the right `currentProducts` (active products only; `null` = unknown, never 0), close any server-side gap found, diagnose why the Flutter reference client displays 0 (read-only diagnosis, no Flutter fix), and write the Mobile section with the display rules (online server value, offline local count, `null` → « — »).

## Boundaries & Constraints

**Always:**
- `currentProducts` = number of `ACTIVE` products of the tenant (same count as the product-limit guard), `null` only when the count cannot be read; documented as such.
- Full-context test on PostgreSQL: N active + drafts/archived products → `currentProducts = N`; unreadable count → `null` (existing unit behaviour kept); the plan limit (`maxProducts`) from the plan profile (16-8).
- Diagnosis of the Flutter client recorded in the spec (file:line of the wrong source / mapping) as reference for the mobile agent.
- Spec ends with « ## Mobile (React Native — dépôt AI/bref) » and `m17-10-compteur-produits-ecran-abonnement` in the MOBILE block of `sprint-status.yaml`.

**Never:**
- No change to the response shape (V1 2.0.1 compatibility) and no Flutter feature fix in `keevo/app` (test client).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Active products | 7 ACTIVE, 2 DRAFT, 1 ARCHIVED (or other non-active status) | `currentProducts: 7` | — |
| No product | new tenant | `currentProducts: 0` | — |
| Count unreadable | product count fails | `currentProducts: null`, other fields present | 200 |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/subscription/plan/application/service/GetSubscriptionService.java:50-85` -- builds `SubscriptionResponse`; `currentProducts()` returns `null` on failure.
- `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/out/persistence/ProductCountAdapter.java:44` -- `COUNT(*) ... WHERE status = 'ACTIVE'`.
- `keevo/backend/src/main/java/com/keevo/subscription/plan/adapter/in/rest/SubscriptionController.java` -- `GET /api/v1/subscription/me`.
- `keevo/backend/src/main/java/com/keevo/subscription/plan/application/service/PlanLimitGuard.java:45-80` -- same count for the limit.
- Flutter reference (diagnosis only): `keevo/app/lib/features/settings/presentation/page/subscription_page.dart` and its provider/repository/model parsing `currentProducts`.
- Conventions for the Mobile section: Mobile sections of `spec-16-11-*` (API conventions), `spec-16-8-*` (plan limits), `spec-16-14-*` (error messages).

## Tasks & Acceptance

**Execution:**
- Backend full-context test of `/subscription/me` per the matrix (real sign-in helper used by other full-context tests); fix the server only if the test shows a gap.
- Diagnose the Flutter display (read-only), record the root cause in Design Notes.
- Mobile section + `m17-10` key; tick 17.10 in the epics doc.

**Acceptance Criteria:**
- Given a local backend (WhatsApp noop, FCM off) and an owner signed in through `/auth/login` + `/auth/select-tenant`, when products are created through the API and `GET /api/v1/subscription/me` is called, then `currentProducts` equals the number of active products.

## Design Notes

**Server gap found (and closed).** The full-context test (7 active + 2 drafts + 1 archived, all through the API) returned `currentProducts = 8`: archiving (`PATCH /api/v1/products/{id}/archive`, sync `ARCHIVE_PRODUCT`) only sets `products.archived = true` and keeps `status = 'ACTIVE'`, while `ProductCountAdapter` counted `status = 'ACTIVE'` alone. The count is now `status = 'ACTIVE' AND archived = false` — the products the owner actually sees in the catalogue (`GET /api/v1/products` lists `archived = false`). The product-limit guard reads the same adapter, so archiving now frees a slot of the plan's product limit. To keep the limit closed, restoring an archived ACTIVE product (`UnarchiveProductUseCase`, reached only by sync `UNARCHIVE_PRODUCT`) now passes the same limit check as a creation (same `product-limit:<schema>` lock, fail closed; at the limit the sync operation is `REJECTED` / `PLAN_LIMIT_EXCEEDED` with `conflictData`, as for a creation since 16-14). Draft promotion (`UpdateProductUseCase`, REST `PATCH` of a draft by the owner and sync `PROMOTE_PRODUCT`) had no limit check at all; it now takes the same lock and check before DRAFT → ACTIVE (an archived draft is not checked: it stays out of the count until restored, which is then checked). The admin catalogue summary (`AdminCatalogService`) counts active products the same way (`AND archived = false`). Response shape unchanged.

**Flutter reference client — why « Produits » showed 0 (read-only diagnosis, nothing fixed in `keevo/app`).** The screen does call the API (the 2026-10-29 journal entry I15 « screen computes locally, no API call » is inaccurate): `subscription_page.dart:21` watches `subscriptionInfoProvider`, which parses `currentProducts` correctly (`subscription_info.dart:35`). The wrong figure comes from:
1. **Stale cached value (primary cause).** `keevo/app/lib/features/settings/presentation/provider/subscription_info_provider.dart:31` is a plain (non-`autoDispose`) `FutureProvider`: `/subscription/me` is fetched once and kept for the whole app session. It is first read early by `export_providers.dart:60` or a first visit to the screen; it is invalidated only on a new session (`session_claims_applier.dart:106-107`) or on the error-retry button (`subscription_page.dart:38`). Products created afterwards (online, or pushed by the sync) never refresh it — on a fresh tenant (the 12-2e live run) the screen keeps the 0 read before the products existed while a direct `curl` returns N.
2. **0 as default (V1 2.0.1 client).** `release/2.0.x` maps `json['currentProducts'] as int? ?? 0`: an absent or `null` value is displayed as 0; and before 16.8 the server's `ProductCountAdapter` itself answered 0 on any count failure. Both now say « unknown » (`null`, shown « — » by the refonte client, `subscription_page.dart:315`).
3. **No offline value.** Offline the provider errors out and the screen shows « Impossible de charger l'abonnement »; there is no local count.

The mobile rules below avoid all three.

## Spec Change Log

- 2026-11-01 — server gap found by the full-context test: archived products (status kept `ACTIVE`) were counted. Fixed in `ProductCountAdapter` (`AND archived = false`); consequence handled: restoring an archived product is now checked against the product limit (`UnarchiveProductUseCase`). No response-shape change.
- 2026-11-01 — review: draft promotion (`UpdateProductUseCase`) checked against the product limit (same lock, fail closed); admin catalogue « actifs » excludes archived products; full-context sync test of UNARCHIVE / PROMOTE at the limit; Mobile section: revert the local change on rejection.

## Review Triage Log

### 2026-11-01 — Review pass
- verdicts: 24 findings — high 0, medium 7, low 14, false 2, maybe-false 1
- findings:
  - `[false]` `[reject]` (intent) Documentation deliverables not in the diff — excluded from the review diff on purpose; Mobile section, Design Notes and `m17-10` present in the working tree.
  - `[low]` `[reject]` (intent) `null` case not tested at full-context level — covered by the existing `GetSubscriptionServiceTest`; forcing a failing count in a full context adds machinery for a mapping already pinned.
  - `[medium]` `[patch]` (intent) Shared count change also changes the limit; new refusal on unarchive only unit-tested — full-context sync test for UNARCHIVE at / below the limit.
  - `[medium]` `[patch]` (intent) Restored draft promoted to ACTIVE bypasses the limit — promotion checks the limit under the same lock.
  - `[false]` `[reject]` (intent) MockMvc instead of a socket — full filter chain exercised; curl live check done in the implementation run.
  - `[medium]` `[patch]` (blind) `PROMOTE_PRODUCT` skips the product limit — same root as the promotion row.
  - `[medium]` `[patch]` (blind) No sync-level test of a refused restore / `conflictData` — same root as the full-context sync test row.
  - `[medium]` `[patch]` (blind) No end-to-end test of archive-frees-a-slot / restore refused — same root.
  - `[low]` `[reject]` (blind) Matrix « null » row and login flow not in the full-context test — see intent rows; token issuance pattern shared by every full-context test.
  - `[low]` `[patch]` (blind) `AdminCatalogService` active count still includes archived — `archived = false` added.
  - `[low]` `[reject]` (blind) Boundaries text says « ACTIVE » — fix would edit this build's intent; Design Notes record the archived flag.
  - `[low]` `[reject]` (blind) Lock taken before the product is classified — lock-then-read is the correct order for the re-read; contention limited to one tenant's restores.
  - `[low]` `[reject]` (blind) Audit listener now inside the unarchive transaction — same as every other audited use case.
  - `[low]` `[reject]` (blind) `notCounted_noCheck` merges two scenarios — cosmetic test structure.
  - `[maybe-false]` `[reject]` (blind) CSV import reads the count without the lock — pre-existing; a concurrent import and restore by the same owner is unlikely; would need a race test; if-true low.
  - `[low]` `[patch]` (edge) Client shows the product restored after a REJECTED unarchive — Mobile section: revert locally and show the limit.
  - `[low]` `[reject]` (edge) Audit write failure rolls back the unarchive — see blind row.
  - `[medium]` `[patch]` (edge) Draft restore then promotion bypasses the limit — same root as the promotion row.
  - `[low]` `[reject]` (edge) Null tenant gives a global lock key — `JwtAuthFilter` always binds the tenant before the sync handler.
  - `[low]` `[reject]` (edge) Unclear failure if FREE `max_products` is null — test fixture: seeded profile always finite.
  - `[low]` `[reject]` (edge) Test tenants not cleaned up — same pattern as the other full-context tests.
  - `[low]` `[reject]` (edge claim) AC sign-in flow not exercised — see blind row.
  - `[low]` `[reject]` (edge claim) `null` contract not tested end to end — see intent row.
  - `[medium]` `[patch]` (verification-gap) Unarchive at the limit only verified with mocks — same root as the full-context sync test row.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -DforkCount=1 -DargLine=-Xmx1536m -Dtest='com.keevo.subscription.**'` then `'com.keevo.catalog.**'`, `'com.keevo.architecture.**'` -- expected: green, one at a time

**Results (2026-11-01):** `com.keevo.subscription.**` green (incl. new `SubscriptionUsageFullContextTest`, 3 tests; it failed with 8 ≠ 7 before the fix); `com.keevo.catalog.**` 254 tests green (incl. new `UnarchiveProductUseCaseTest`, 3 tests); `com.keevo.architecture.**` 32 green; `com.keevo.sync.**` + `ResidualRacesPostgresIntegrationTest` 578 green. After review: `SubscriptionUsageFullContextTest` (4, incl. sync UNARCHIVE / PROMOTE at the Free limit → REJECTED with `conflictData`, then APPLIED once a slot is freed), `UpdateProductUseCaseTest` (8), `UnarchiveProductUseCaseTest`, `AdminCatalogServiceTest`, `ProductSyncHandlerTest`, `ProductCommandAdapterTest` green. Live (local backend, WhatsApp noop, FCM off): register → `/auth/login` → `/auth/select-tenant` → `/subscription/me` `currentProducts: 0`; 5 products + 1 draft + 1 archived via the API → `currentProducts: 5`; backend stopped with `fuser -k 4500/tcp`.

**Manual checks (if no CLI):**
- AC above with curl; backend stopped with `fuser -k 4500/tcp`; never forge tokens.

## Mobile (React Native — dépôt AI/bref)

> Destinataire : agent IA qui implémente l'app Expo / React Native sur une branche du dépôt `/home/toor/Project/FreeLance/AI/bref` (base `develop`). Conventions API : section « Mobile » de `spec-16-11-sessions-longues-et-sures.md` ; plafonds de plan : section « Mobile » de `spec-16-8-profils-plan-plafonds-configurables.md` ; messages d'erreur : `spec-16-14-api-prete-client-mobile.md`. Aucun changement de forme de réponse (compatible V1 2.0.1).

**Statut** : API livrée — mobile à faire.

**Clé de suivi mobile** : `m17-10-compteur-produits-ecran-abonnement`

### Ce qui est fait côté API

- `GET /api/v1/subscription/me` (tout utilisateur connecté, pas de module requis) renvoie `{"data": {planType, status, expiresAt, maxStores, maxProducts, maxEmployees, currentStores, currentProducts, currentEmployees}}`.
- `currentProducts` = nombre de produits **actifs et non archivés** du compte (statut `ACTIVE`, `archived = false`) — exactement le comptage du contrôle de plafond produits. Les brouillons (`DRAFT`) et les produits archivés ne comptent pas. Nouveau compte → `0` (un nombre). `null` = le serveur n'a pas pu compter (jamais 0 dans ce cas).
- `maxProducts` = plafond du profil de plan (16-8), `null` = illimité (essai Premium, Premium).
- Archiver un produit libère une place du plafond ; le restaurer (`UNARCHIVE_PRODUCT` en synchro) ou valider un brouillon (`PROMOTE_PRODUCT` en synchro, `PATCH /api/v1/products/{id}` d'un brouillon par le propriétaire) repasse le contrôle du plafond : au plafond, l'opération est `REJECTED` / `PLAN_LIMIT_EXCEEDED` avec `conflictData` (16-14) — en REST, 403 `PLAN_LIMIT_EXCEEDED` avec les mêmes `details` — et le produit reste archivé / en brouillon côté serveur.

### À utiliser

| Cas | Réponse | Source |
|---|---|---|
| 7 actifs, 2 brouillons, 1 archivé | `"currentProducts": 7` | `catalog/product/adapter/out/persistence/ProductCountAdapter.java` |
| Nouveau compte | `"currentProducts": 0` | idem |
| Comptage impossible | `"currentProducts": null`, autres champs présents, 200 | `subscription/plan/application/service/GetSubscriptionService.java` (`currentProducts()`) |
| Plan Free | `"maxProducts": <plafond du profil>` (500 par défaut) | `PlanLimitGuard.profile` |
| Validation d'un brouillon au plafond (synchro) | idem, le produit reste `DRAFT` | `catalog/product/application/usecase/UpdateProductUseCase.java` |
| Restauration au plafond (synchro) | `{"status": "REJECTED", "reason": "PLAN_LIMIT_EXCEEDED", "conflictData": {"entity": "products", "limit", "current", "plan"}}` | `catalog/product/application/usecase/UnarchiveProductUseCase.java` |

### À implémenter côté mobile

1. **En ligne** : « Produits » = `currentProducts` de `/subscription/me`, **relu à chaque ouverture de l'écran Abonnement** et après tout retour de synchro qui crée / archive / restaure / valide un produit — jamais une valeur gardée en cache pour toute la session (cause du 0 dans le client Flutter, voir Design Notes).
2. **Hors ligne** (ou appel en échec réseau) : compter localement les produits `status = 'ACTIVE'` et `archived = false` (champs `status` / `archived` du delta `products` de la synchro), opérations en attente comprises ; limite = dernier `maxProducts` connu (sinon « ∞ » si le dernier plan connu est illimité). Indiquer discrètement que la valeur est locale (« hors ligne »).
3. **`currentProducts: null`** → afficher « — » (jamais 0), barre de progression vide ; ne pas bloquer l'écran.
4. Format : « N / max », « N / ∞ » si `maxProducts` est `null` ; couleur d'alerte à ≥ 80 % puis à 100 % du plafond.
5. Les brouillons d'un employé n'augmentent pas le compteur tant que le propriétaire ne les a pas validés.
6. `UNARCHIVE_PRODUCT` ou `PROMOTE_PRODUCT` revenu `REJECTED` / `PLAN_LIMIT_EXCEEDED` : **annuler le changement local** (le produit redevient archivé, resp. brouillon, dans la base locale et les listes ; le compteur local est recalculé) et afficher la feuille « plafond atteint » de 16-8 / 16-14 avec la limite lue dans `conflictData` (`entity` = `products`, `limit`, `current`, `plan`), avec l'invitation « Passer à Premium » (16-8/16-14). Ne pas renvoyer l'opération en boucle.

### Critères d'acceptation mobile / scénarios de test

Préparation : backend local (`--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false`, arrêt `fuser -k 4500/tcp`), connexion par l'API comme en 16-11 (`BASE`, `AT`).

```bash
curl -s $BASE/api/v1/subscription/me -H "Authorization: Bearer $AT" | jq '.data | {currentProducts, maxProducts}'
curl -s -X POST $BASE/api/v1/products -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -d '{"name":"Savon","price":500}'
curl -s -X PATCH $BASE/api/v1/products/<id>/archive -H "Authorization: Bearer $AT"
```

1. **Compte neuf** — Given un compte sans produit, When l'owner ouvre Abonnement, Then « Produits 0 / ∞ ».
2. **Création puis retour** — Given l'écran déjà ouvert une fois, When l'owner crée 3 produits puis revient sur Abonnement, Then « Produits 3 » sans redémarrer l'app.
3. **Brouillons et archivés** — Given 7 actifs, 2 brouillons, 1 archivé, Then « Produits 7 », égal à `currentProducts` du `curl`.
4. **Hors ligne** — Given le mode avion et 2 produits créés hors ligne, When l'owner ouvre Abonnement, Then le compteur local inclut ces 2 produits, mention « hors ligne » ; au retour du réseau et après synchro, la valeur serveur est identique.
5. **Inconnu** — Given `currentProducts: null` (serveur incapable de compter), Then « — » affiché, pas de 0, pas d'écran d'erreur.
6. **Restauration / validation au plafond** — Given un compte Free à son plafond, un produit archivé et un brouillon, When l'owner restaure le produit puis valide le brouillon hors ligne et synchronise, Then les deux opérations sont refusées, le produit redevient archivé et le brouillon reste brouillon dans l'app, la feuille affiche la limite de `conflictData`, le compteur est inchangé.

## Auto Run Result

Status: done

**Summary:** `currentProducts` of `GET /api/v1/subscription/me` now counts products that are ACTIVE and not archived (archived products used to be counted: 8 instead of 7); the same count drives the product limit, so archiving frees a slot, and every way back into the count is guarded under the `product-limit:<tenant>` lock: restore (`UNARCHIVE_PRODUCT`) and DRAFT→ACTIVE promotion (`PROMOTE_PRODUCT`, REST draft PATCH) are refused at the limit with `PLAN_LIMIT_EXCEEDED` and its details. The admin « active products » figure uses the same rule. Flutter root cause (session-long cached provider) diagnosed read-only and handed over in the Mobile section.

**Review:** 24 findings — patched entries: medium 3 (promotion bypassing the limit, real-wiring sync test for restore/promotion, archive-frees-a-slot end to end), low 2 (admin count, Mobile revert rule); 0 deferred; rejected with reasons in the triage log.

**Follow-up review recommended: true** — `UpdateProductUseCase.execute` became `@Transactional` with a lock on promotion (all product updates now run in a transaction); a second look at that path under sync batches is the unverified risk.

**Verification:** backend every package one at a time (catalog, subscription, sync, admin, shared, identity, inventory, commerce, store, crm, invoicing, reporting, messaging, feedback, acceptance, architecture) — 4775 tests, 0 failures, incl. `SubscriptionUsageFullContextTest` (count, 0, plan profile limit, sync restore/promotion at and below the limit). Live curl by the implementation run: new account 0, then 5 with 1 draft and 1 archived. Redmi (Flutter test client): app online, pull at 22:24:55 UTC, no backend error.
