---
title: '16-3 Active modules in the app without a JWT claim: refresh on resume / reconnection / session change, offline snapshot'
type: 'feature'
created: '2026-11-01'
status: 'done'
baseline_revision: '165a3501f94e69fa4cdb466a85dd180dc9b351a2'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Decision of 2026-11-01: the access token must stay minimal, so no `modules` claim will ever be emitted. The app (15.1) still seeds module states from a `modules` claim and otherwise from its stored snapshot, and only asks the server when the provider is rebuilt (new token / account): with today's long-lived tokens, a module disabled or enabled by the super-admin is not seen for weeks (observed on the Redmi in 16.1: the app kept its old states until a token was issued) (MOD-FR15).

**Approach:** Remove the claim path from the app (seed = stored snapshot of this account, else unknown); ask `GET /api/v1/tenants/me/modules` again when the app comes back to the foreground, when the network comes back, and on every session change (token renewal, tenant selection, sign-in) — keeping the last answer on failure and offline. Pin on the backend that tokens carry no module list and that the answer lists every catalogue module with its state (closes the two items routed to 16.3).

## Boundaries & Constraints

**Always:**
- Seed: the persisted snapshot of the current account (`kLocalDataOwnerKey`), else `Entitlements.unknown` (today's semantics for unknown kept). No token parsing for modules.
- Refresh triggers: (a) `AppLifecycleState.resumed`, at most once per 60 s; (b) network back online (reuse the connectivity signal the sync trigger already listens to); (c) provider rebuild after a token / account change (already there). A failed or offline request keeps the known states and is not persisted; a successful answer is persisted (existing behaviour).
- When the answer changes a module's readability, the router redirect runs again (existing `ModuleGateRefresh` ping) so an open screen of a now-disabled module leaves, and a newly enabled module appears in menus without restart.
- Backend: no `modules` (nor any module list) claim in any token (`JwtTokenProvider`) — a test decodes access tokens of an owner and an employee and asserts it; `GET /tenants/me/modules` returns every catalogue module (free and paid, core included) with `state` ∈ ENABLED/READ_ONLY/DISABLED — test (tenant with a disabled paid module, a read-only one and free modules).
- App version bump in `keevo/app/pubspec.yaml`.

**Never:**
- Do not add any claim to tokens (16.11 will remove claims, not this story).
- No polling timer; no change to the `/tenants/me/modules` response shape.
- Do not touch `keevo_core`'s other claims (`role`, `storeId`, …) beyond removing the `modules` getter if nothing else uses it.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Resume after server change | super-admin disables `invoicing`; owner brings the app back to foreground | answer fetched; Factures entry disappears; an open Factures screen leaves to « module non activé » | — |
| Resume twice quickly | two resumes within 60 s | one request | — |
| Network back | offline → online | one request; states updated | — |
| Offline start | no network, snapshot exists | snapshot states used, no error shown | request failure ignored |
| No snapshot | fresh install, first sign-in | unknown until the answer (existing behaviour) | — |
| Token with legacy claim | an old token still carrying `modules` | claim ignored; snapshot/server used | — |
| Backend token | owner/employee access token | no module list in its claims | — |

</intent-contract>

## Code Map

- `keevo/app/lib/core/entitlements/entitlements_provider.dart:40-170` -- `build` (seed then `_startFetch`), `ensureServerState`, `refresh`, `loadSeed` (claim branch at 137-153 to remove), `_fetch` (persists the answer), `readSnapshot`.
- `keevo/app/packages/keevo_core/lib/auth/jwt_claims.dart:66-75` -- `modules` getter (remove if unused after the change); `Entitlements.fromClaim` in the entitlements model (remove).
- `keevo/app/lib/core/sync/sync_trigger_notifier.dart`, `lib/core/sync/riverpod_sync_trigger_dispatcher.dart` -- existing connectivity / resume listeners to mirror or reuse; `lib/core/scaffold/main_shell.dart` -- app lifecycle hook candidate.
- `keevo/app/lib/core/router/app_router.dart:272-277` -- `ModuleGateRefresh` ping fed by `KeevoApp` when states change.
- `keevo/app/test/core/entitlements/entitlements_provider_test.dart` -- existing provider tests to adapt (claim cases → removed/ignored).
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtTokenProvider.java:55-150` -- token claims (no modules today).
- `keevo/backend/src/main/java/com/keevo/shared/module/adapter/in/rest/TenantModulesController.java:60-85` + `ModuleLifecycleService.tenantModules(String)` (527-539, every catalogue module with registry state).
- `_bmad-output/planning-artifacts/epics-refonte-modulaire.md` -- Story 16.3 items to tick.

## Tasks & Acceptance

**Execution:**
- `entitlements_provider.dart` -- remove the claim seed; add `refreshIfStale()` (60 s throttle) used by resume and network-back triggers; tests: seed without claim, legacy claim ignored, resume throttle, network-back refresh, failure keeps states.
- App lifecycle / connectivity wiring (main shell or a small `EntitlementsRefreshObserver`) -- call `refreshIfStale()` on resume and on connectivity regained; widget/unit test with a fake lifecycle and a fake connectivity stream.
- `jwt_claims.dart` + entitlements model -- remove `modules` / `fromClaim` and their tests if unused.
- Backend tests -- `JwtTokenProviderTest` (or existing token test): no module list claim; `TenantModulesController` / lifecycle Postgres or slice test: every catalogue module listed with ENABLED/READ_ONLY/DISABLED.
- `pubspec.yaml` -- bump.
- Epics doc -- tick the two 16.3 items (answer already `{id: state}` for every module; app no longer needs the claim).

**Acceptance Criteria:**
- Given the Redmi signed in as owner of KV-ZGGT7G on a local backend, when `invoicing` is set DISABLED on the server (DB or super-admin API) and the app is sent to background then reopened, then the « Factures » entry disappears from « Plus » without signing out; after restoring the row and reopening the app again, it comes back.

## Spec Change Log

## Review Triage Log

### 2026-11-01 — Review pass
- verdicts: 27 findings — high 0, medium 4, low 15, false 4, maybe-false 4
- findings:
  - `[medium]` `[patch]` (verification-gap, intent) `KeevoApp` mount of the trigger untested — `listenEntitlements(ref)` helper (module gate + trigger) called by `KeevoApp`; widget test: resume → refresh, state change → gate ping.
  - `[low]` `[reject]` (intent) Renewal / tenant selection triggers not re-tested — unchanged pre-existing `SessionClaimsApplier` path, already tested.
  - `[low]` `[reject]` (intent) Resume trigger beyond the epic — spec decision (observed need on the Redmi in 16.1).
  - `[low]` `[patch]` (intent, blind, edge) Backend "no module claim" test too narrow/too broad — every `generateAccessToken` overload, super-admin and login token decoded; key-based assertion, collection ban dropped.
  - `[low]` `[reject]` (intent, blind) Response shape tested at service level — `TenantModulesControllerTest` covers the HTTP body; TRIAL is a `status`, not a `state`.
  - `[false]` `[reject]` (intent) Device check not evidenced — run by the implementer (recorded below).
  - `[medium]` `[patch]` (blind) `refreshIfStale` asked nothing after a failed build — fetch started directly; test.
  - `[medium]` `[patch]` (blind, edge) Throttle only after a success → a request per resume while offline/5xx — throttle on the last attempt; network-back forces one attempt; tests.
  - `[low]` `[patch]` (blind, edge) Throttle on the wall clock — monotonic elapsed time; test with the wall clock moved back.
  - `[low]` `[patch]` (blind, edge) Online predicate duplicated / vpn-other treated as offline — shared `isOnline` (any result but none) in keevo_core, used by both triggers.
  - `[medium]` `[patch]` (blind) No router-level proof that a refresh reaches the gate — covered by the new `listenEntitlements` widget test (gate pinged on a disabling answer); redirect itself is the existing tested guard.
  - `[low]` `[patch]` (blind, edge) `JwtClaims.issuedAt` dead — removed.
  - `[low]` `[patch]` (blind) Legacy-claim tests did not prove the token is unread — counting token storage test.
  - `[low]` `[reject]` (blind) Two requests on resume after token expiry — the first answer is dropped by the generation check; negligible.
  - `[false]` `[reject]` (blind) Spec record incomplete — finalized now.
  - `[low]` `[reject]` (blind) Code Map line ranges drift — informative only.
  - `[low]` `[reject]` (edge) Startup event online after offline missed — the provider build fetches anyway at startup.
  - `[maybe-false]` `[reject]` (edge) Old `_isConnected` in sync status display keeps the old rule — display only; outside the triggers.
  - `[false]` `[reject]` (intent) Free modules absent handling — the answer lists every catalogue module (pinned by test).
  - `[maybe-false]` `[reject]` (intent) Open Factures screen leaving not device-checked — guard redirect pre-existing and tested; gate ping now pinned.
  - `[false]` `[reject]` (blind) Response test allMatch meaningless — `ModuleState` has exactly three values.
  - `[low]` `[reject]` (intent) Backend changes are tests only — by design (no claim ever emitted).
  - `[maybe-false]` `[reject]` (verification-gap) Option to defer wiring — patched instead.
  - `[low]` `[reject]` (intent) my_modules test re-seeded with a snapshot — consequence of removing the claim seed.
  - `[low]` `[reject]` (edge) Wall-clock duplicate — grouped above.
  - `[maybe-false]` `[reject]` (edge) Throttle duplicate — grouped above.
  - `[low]` `[reject]` (blind) Format churn risk — implementer restored untouched files; diff checked.

## Verification

**Commands:**
- `cd keevo/app && flutter analyze && flutter test --concurrency=4 test/core` then `test/features/settings`, and `packages/keevo_core` tests -- expected: green
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -DforkCount=1 -DargLine=-Xmx1536m -Dtest='com.keevo.shared.infrastructure.security.**'` then `'com.keevo.shared.module.**'` -- expected: green

**Manual checks:**
- Device AC above: debug build installs as `com.example.keevo` (not the old `com.example.keevo.dev`); local backend started ONLY with `-Dspring-boot.run.arguments="--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false"`; `adb reverse tcp:4500 tcp:4500`; never open share sheets / wa.me; restore the `invoicing` row of KV-ZGGT7G (TRIAL/PLAN, expires 2027-04-29 11:34:20.985882+00, grace 2027-05-06 11:34:20.985882+00) and stop the backend with `fuser -k 4500/tcp` afterwards.

## Auto Run Result

Status: done

**Summary:** the app no longer reads modules from the token (seed = stored snapshot of the account, else unknown; `JwtClaims.modules`, `issuedAt`, `Entitlements.fromClaim` removed). It asks `GET /tenants/me/modules` again on app resume (at most once per 60 s, monotonic clock, failed attempts count), on every offline→online edge (forced), and on session changes (existing rebuild); offline the last answer is kept. A shared `isOnline` predicate serves both the sync and the entitlements triggers. Backend tests pin that no token carries a module claim and that the answer lists every catalogue module with ENABLED/READ_ONLY/DISABLED. App 2.0.20+92.

**Review:** 27 findings — 11 patched entries (4 medium, 7 low), 16 rejected with reasons. Follow-up review recommended: true — several medium entries patched on a first pass; named risk: the forced network-back refresh combined with the session-rebuild fetch (possible duplicate request), covered by unit tests only.

**Verification:** app `flutter analyze` 0 errors/warnings; `test/core` 534, every `test/features/*` folder (run one by one) green, keevo_core 334. Backend: shared.infrastructure.security 96, shared.module 430 green. Redmi (implementer, `com.example.keevo` debug, local backend WhatsApp noop / FCM off, owner KV-ZGGT7G): `invoicing` set DISABLED in DB → reopening within 60 s sent no request (throttle), reopening after 60 s → « Factures » gone from « Plus » without sign-out; row restored → reopening → « Factures » back. Row restored to TRIAL/PLAN with its original dates; backend stopped.

## Mobile (React Native — dépôt AI/bref)

> Destinataire : agent IA qui implémente l'app Expo / React Native sur une branche du dépôt `/home/toor/Project/FreeLance/AI/bref` (base `develop`). Conventions API (enveloppes, erreurs, renouvellement) : section « Mobile » de `spec-16-11-sessions-longues-et-sures.md`. Correspond à la story fusion 22.8 (`_bmad-output/planning-artifacts/epics-fusion-keevo-bref.md`).

**Statut** : API livrée (commit `8325d05c`) — mobile à faire.

**Clé de suivi mobile** : `m16-3-modules-actifs-app-sans-claim-jwt`

### Ce qui est fait côté API

- Aucun jeton ne porte la liste des modules (et n'en portera jamais) : l'état des modules ne s'obtient que par `GET /api/v1/tenants/me/modules`.
- Cette route renvoie **tous** les modules du catalogue (gratuits, payants, socle) avec leur état calculé en temps réel à partir des dates d'abonnement.

### À utiliser

`GET /api/v1/tenants/me/modules` (OWNER ou EMPLOYEE ; contrôleur `keevo/backend/src/main/java/com/keevo/shared/module/adapter/in/rest/TenantModulesController.java`) → `{"data": [ … ], "timestamp"}`, un élément par module :

| Champ | Valeurs | Remarque |
|---|---|---|
| `moduleId` | `core`, `sync`, `catalog`, `inventory`, `pos`, `crm`, `store`, `reporting`, `messaging`, `admin`, `invoicing`, `counting`, `exports`, `profitability`, `alerts`, `report-config`, `multi-store`, `advanced-reports` | clé technique, **jamais affichée** |
| `displayName` | ex. « Facturation », « Multi-boutiques et transferts » | seul libellé à afficher |
| `state` | `ENABLED` \| `READ_ONLY` \| `DISABLED` | **la seule valeur qui décide** de l'accès |
| `status` | `AVAILABLE` \| `TRIAL` \| `ACTIVE` \| `EXPIRED` \| `DISABLED` \| null | statut d'abonnement stocké, **owner seulement** (null pour un employé) ; informatif (« Essai », « Actif »…) |
| `expiresAt`, `graceUntil` | ISO-8601 UTC ou null | owner seulement |
| `free` | booléen | module inclus dans l'offre gratuite |
| `category` | `core` \| `business` \| `premium` \| `platform` | |

Sémantique : `ENABLED` = lecture + écriture ; `READ_ONLY` = abonnement échu, 7 jours de grâce : lectures acceptées, écritures refusées (voir 16-1) ; `DISABLED` = aucun accès (données conservées à vie, réapparaissent à la réactivation).

Appel REST sur un module non actif — liste des routes et de leur module : `keevo/backend/docs/entitlements/endpoints.md` ; table écran → module : `keevo/contracts/module-routes.json` (routes Flutter, à transposer aux routes Expo) :

| `domainCode` | HTTP | Message serveur | Réaction |
|---|---|---|---|
| `MODULE_NOT_ENTITLED` | 403 | Ce module n'est pas inclus dans votre abonnement | Quitter l'écran vers « module non activé » / « Mes modules » ; relancer `GET /tenants/me/modules` |
| `MODULE_READ_ONLY` | 403 | Ce module est en lecture seule : abonnement expiré | Voir 16-1 (bandeau, action désactivée) |

Points d'extension (ex. action « Facturer » au paiement) : `GET /api/v1/tenants/me/extensions/{pointId}` → `{"data": [{"id", "label", "order", "action"}]}` — seules les contributions des modules actifs et permises au rôle ; point inconnu → 404 « Point d'extension inconnu ».

### À implémenter côté mobile

1. **Graine au démarrage** : le dernier instantané persistant de la réponse **pour ce compte** (clé liée à l'utilisateur + entreprise), sinon état « inconnu ». Ne jamais lire le jeton pour les modules.
2. **État inconnu** (première ouverture hors ligne, aucun instantané) : ne pas traiter le compte comme « sans modules » ; garder le parcours gratuit / par défaut jusqu'à la réponse.
3. **Rafraîchissements** (pas de minuterie de sondage) : (a) retour au premier plan (`AppState` → `active`), au plus une fois par 60 s, mesurées sur une horloge monotone, une tentative échouée compte aussi ; (b) passage hors ligne → en ligne : une requête forcée (sans le délai de 60 s) ; (c) chaque changement de session : connexion, choix d'entreprise, renouvellement de jeton. Une seule requête en vol ; une réponse plus ancienne n'écrase jamais une plus récente ; une réponse d'une session précédente est ignorée.
4. **Échec / hors ligne** : garder les états connus, ne rien persister, aucune erreur affichée. **Succès** : persister la réponse (instantané du compte).
5. **Garde de routes** : chaque écran appartient à un module ; route d'un module non lisible (`DISABLED`) → écran « Module non activé » nommant le `displayName`, avec accès à « Mes modules » ; les menus (onglets, « Plus ») masquent les entrées des modules `DISABLED`. Quand une réponse change la lisibilité, réévaluer la garde **immédiatement** : un écran ouvert d'un module désactivé se ferme, un module réactivé réapparaît sans redémarrer.
6. **Synchro** : quand un module redevient lisible (`DISABLED` → `ENABLED`/`READ_ONLY`) par rapport à l'instantané précédent du même compte, demander un **pull complet** au prochain pull (le pull delta n'envoie rien d'un module `DISABLED` mais avance son curseur ; voir 16-10 / protocole de synchro). Les opérations locales en attente ne sont pas touchées.
7. Aucun identifiant de module, statut brut ou date ISO à l'écran : `displayName` et dates formatées en français.

### Implémentation de référence (Flutter, `keevo/app`)

- `lib/core/entitlements/entitlements_provider.dart` (graine, `refreshIfStale`, persistance, demande de pull complet), `lib/core/entitlements/entitlements_refresh_trigger.dart` (premier plan / réseau)
- `packages/keevo_core/lib/entitlements/entitlements.dart` (états, `isReadable`, état inconnu)
- `lib/core/modules/feature_modules.dart`, `lib/core/router/module_routes.dart`, `lib/core/router/module_unavailable_page.dart`, `lib/core/router/app_router.dart` (garde, `ModuleGateRefresh`)
- `lib/features/settings/presentation/page/my_modules_page.dart` (« Mes modules »)

### Critères d'acceptation mobile / scénarios de test

Préparation (backend local WhatsApp noop / FCM coupé ; owner connecté sur l'app) — changer l'état d'un module en base locale, puis remettre la ligne d'origine après le test :

```bash
psql "$KEEVO_DB_URL" -c "SELECT module_id,status,expires_at,grace_until,origin FROM public.tenant_modules WHERE tenant_id=(SELECT id FROM public.tenants WHERE code='KV-XXXXXX') AND module_id='invoicing';"
psql "$KEEVO_DB_URL" -c "UPDATE public.tenant_modules SET status='DISABLED', disabled_at=now() WHERE tenant_id=(SELECT id FROM public.tenants WHERE code='KV-XXXXXX') AND module_id='invoicing';"
curl -s $BASE/api/v1/tenants/me/modules -H "Authorization: Bearer $AT" | jq '.data[] | {moduleId,state}'
```
(Alternative super-admin : `POST /api/v1/admin/tenants/{tenantUuid}/modules/invoicing/deactivate` puis `…/activate` avec `{"status":"TRIAL"}`.)

1. **Désactivation vue au retour** — Given `invoicing` passé `DISABLED` côté serveur, When l'app passe en arrière-plan puis revient après plus de 60 s, Then l'entrée « Factures » disparaît des menus sans déconnexion, et un écran Factures ouvert est remplacé par « Module non activé ».
2. **Délai de 60 s** — Given deux retours au premier plan à moins de 60 s, Then une seule requête `GET /tenants/me/modules`.
3. **Réactivation** — Given la ligne restaurée (`TRIAL`/`ACTIVE` non échue), When l'app revient au premier plan (> 60 s), Then « Factures » revient, et le pull suivant est complet (les factures créées entre-temps apparaissent).
4. **Retour du réseau** — Given l'app hors ligne, When le réseau revient, Then une requête immédiate et les états à jour.
5. **Démarrage hors ligne** — Given un instantané existant, When l'app démarre sans réseau, Then les menus suivent l'instantané, aucune erreur affichée.
6. **Première installation** — Given aucun instantané, Then état inconnu jusqu'à la réponse (pas d'écran « module non activé » à tort).
7. **Employé** — Given un employé connecté, Then la liste reçue n'a ni `status` ni dates, et la garde fonctionne de la même façon.
