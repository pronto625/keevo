---
title: '16-14 API ready for the React Native and dashboard clients — additive, V1-compatible fixes of error messages and missing data'
type: 'feature'
created: '2026-11-01'
status: 'done'
baseline_revision: '2d2964ffd5fd1f9ea67fbba7e1918ff6107c982f'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
warnings: ['multiple-goals', 'oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 16.14 (`epics-refonte-modulaire.md`): writing the « Mobile » and « Dashboard » sections of the 16-x specs showed that the API makes the new clients (React Native app, super-admin dashboard, both built in other repos) guess or carry texts the server should give: auth-filter errors carry only `{"domainCode"}`, some codes have no French message, `VALIDATION_ERROR` says "Validation failed" with English field messages, employees get no grace date for a read-only module, a sync `PLAN_LIMIT_EXCEEDED` rejection has no limit, and the admin API has small inconsistencies.

**Approach:** Additive, V1-2.0.1-compatible corrections only — add fields, fill messages, never remove or rename an existing field or change an existing success shape — then update the Mobile/Dashboard sections of the affected specs.

## Boundaries & Constraints

**Always:**
- Auth-filter errors (`JwtAuthFilter.writeError` / `writeErrorWithStatus`): body becomes `{"domainCode": "...", "error": "<French message>"}` using the same French catalogue as `GlobalExceptionHandler.FR_MESSAGES` (one shared source, no duplicated strings).
- Every `ErrorCode` constant has a French message (guard test iterating `ErrorCode.values()`); fill the missing ones (incl. `ACCOUNT_SUSPENDED`, `MEMBERSHIP_NOT_FOUND`).
- `VALIDATION_ERROR` from bean validation: `error` = the first field's French message (or « Données invalides » when none); `details` keeps `field → message` with French messages (French default-message bundle `ValidationMessages_fr.properties` / default locale fr for validation, or explicit messages — whichever keeps all existing custom messages). Admin `VALIDATION_ERROR` raised by services with a reason: that reason in `error`.
- `CORE_MODULE_REQUIRED`: the response keeps the service's message naming the module (the generic catalogue text only when the service gave none).
- `GET /api/v1/tenants/me/modules`: an EMPLOYEE also receives, for a module in grace / read-only, `status` and `graceUntil` (no other owner-only fields).
- Sync push rejection `PLAN_LIMIT_EXCEEDED`: the rejection carries the same details as the REST 403 (limit name, limit, current usage) in its existing details/metadata field (additive).
- Admin API: plan-profile module references expose `moduleId` in addition to `id` (both, same value); `GET /api/v1/admin/tenants?search=` also matches the tenant code; unknown plan type on plan-profile routes → 404 (as unknown pack / job); `GET /api/v1/admin/tenants/{id}/modules/history` takes optional `limit` (default 100, max 500) and `before` (ISO instant, keyset on the entry time) with the same response shape.
- Update the « Mobile » sections (16-1, 16-8, 16-10, 16-11) and « Dashboard » sections (16-2, 16-4, 16-5, 16-8) whose « incohérences » notes are fixed here; this spec ends with its own Mobile + Dashboard sections and adds `m16-14-api-prete-client-mobile` / `d16-14-api-prete-client-mobile` to sprint-status.

**Never:**
- No change of existing success response shapes (bare `/auth/*` vs `{data}`, `meta` vs `data.content` pagination stay — documented), no removal of `ErrorCode` constants still used (`MODULE_BACKFILL_RUNNING` is still used internally by `ModuleJobsService` → keep), no status-code change on routes used by V1 2.0.1 apps.
- No work in `keevo/app` or `keevo/dashboard` beyond keeping their tests green.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Expired access token | any protected route | 401 `{"domainCode":"TOKEN_EXPIRED","error":"<fr>"}` | — |
| Bean validation | `POST /api/v1/auth/login` with a malformed phone | 422, `error` = French field message, `details.phoneNumber` French | — |
| Every code | each `ErrorCode` | has a French message | guard test |
| Core module deactivation | admin deactivates `pos` | 409/422 `CORE_MODULE_REQUIRED`, `error` names the module | — |
| Employee module list | module READ_ONLY with grace | employee sees `status`, `graceUntil` | — |
| Sync over plan limit | push CREATE_PRODUCT at the Free cap | rejection `PLAN_LIMIT_EXCEEDED` + limit/usage | — |
| Admin search by code | `search=KV-ABC123` | that tenant | — |
| Unknown plan profile | `GET /admin/plan-profiles/NOPE` | 404 | — |
| History paging | 250 entries, `limit=100&before=<t>` | 100 entries older than t, newest first | `limit>500` → 400 |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtAuthFilter.java:326-338` -- `writeErrorWithStatus` / `writeError` write `Map.of("domainCode", ...)`.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/GlobalExceptionHandler.java:51-125` (`FR_MESSAGES`, 112 entries vs 133 `ErrorCode` constants), `:199-213` (`handleValidation`, "Validation failed"), `:400-420` (status mapping incl. `CORE_MODULE_REQUIRED`, `MODULE_BACKFILL_RUNNING`); extract the catalogue to a shared component both the handler and the filter use.
- `keevo/backend/src/main/java/com/keevo/shared/domain/exception/ErrorCode.java` -- constants.
- `keevo/backend/src/main/java/com/keevo/shared/module/adapter/in/rest/TenantModulesController.java:66` -- `GET /tenants/me/modules` (owner-only fields today).
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/service/SyncPushService.java:~208` -- DomainException → rejection mapping; `shared/capability/entitlement/PlanLimitCheck.java`, `subscription/plan/application/service/PlanLimitGuard.java` -- REST 403 details to mirror.
- `keevo/backend/src/main/java/com/keevo/shared/module/PlanProfileAdmin.java:62` (`ModuleRef(id, displayName)`), `admin/plan/adapter/in/rest/AdminPlanProfileController.java:135` (unknown plan → `PLAN_PROFILE_INVALID`).
- `keevo/backend/src/main/java/com/keevo/admin/tenant/adapter/in/rest/AdminTenantController.java:52-65` (`search`) + its query.
- `keevo/backend/src/main/java/com/keevo/admin/module/adapter/in/rest/ModuleAdminController.java:103-104` (history, last 100).
- Spec sections to update: `_bmad-output/implementation-artifacts/spec-16-{1,2,4,5,8,10,11}-*.md` (« Mobile » / « Dashboard » sections, « incohérences » lists).

## Tasks & Acceptance

**Execution:**
- Shared French error catalogue -- used by `GlobalExceptionHandler` and `JwtAuthFilter`; filter bodies gain `error`; guard test over `ErrorCode.values()`.
- `GlobalExceptionHandler` -- validation `error` = first French field message; French validation messages; service reasons kept for admin `VALIDATION_ERROR` and `CORE_MODULE_REQUIRED`.
- `TenantModulesController` -- employee sees `status` / `graceUntil`.
- `SyncPushService` -- `PLAN_LIMIT_EXCEEDED` rejection details.
- Admin: `PlanProfileAdmin.ModuleRef` + DTO `moduleId`; tenant search by code; unknown plan 404; history `limit`/`before`/`beforeId` (compound `(occurredAt, id)` keyset over the audit cursor, label filter before the cut), rate-limit body serialised by Jackson, code search escapes `%`/`_`, `ModuleRef.moduleId` a derived JSON property.
- Tests for every matrix row (controller / full-context tests per existing patterns), existing tests updated only where they asserted the old English text.
- Specs' Mobile/Dashboard sections updated; this spec's own sections; `m16-14` / `d16-14` keys in the MOBILE / DASHBOARD blocks of `sprint-status.yaml`; tick 16.14 in the epics doc.

**Acceptance Criteria:**
- Given a local backend (WhatsApp noop, FCM off) and real sign-ins, when the matrix requests are sent over HTTP, then the responses match the matrix (checked with curl and recorded in the Auto Run Result).
- Given the Flutter test client 2.0.21 on the Redmi, when it signs in and syncs, then it behaves as before (basic check) — additive changes only.

## Spec Change Log

## Review Triage Log

### 2026-11-01 — Review pass
- verdicts: 36 findings — high 7, medium 10, low 18, false 1, maybe-false 0
- findings:
  - `[high]` `[patch]` (blind) Service reasons for VALIDATION_ERROR/NOT_FOUND now reach `error` app-wide; ~39 English/technical sites remain (UUIDs included), V1 routes lose « Données invalides » — passthrough made opt-in: only an explicit French user message set at the throw site reaches `error`, everything else keeps the catalogue text; translated sites switched to it; tests.
  - `[medium]` `[patch]` (blind) History keyset on `occurredAt` alone skips entries tied at a page boundary (pack writes several rows) — optional `beforeId`, compound (occurredAt, id) keyset; test.
  - `[low]` `[reject]` (blind) Client may truncate `occurredAt` to ms — Jackson serialises the full ISO instant; the dashboard echoes the string; documented in the Dashboard section.
  - `[low]` `[patch]` (blind) Short page indistinguishable from the end (null labels filtered after the cut) — filter applied before the limit; test.
  - `[low]` `[reject]` (blind) 400 for bad query parameters vs 422 for bodies — existing project convention (`InvalidRequestParameterException`/type mismatch = 400, bean validation = 422).
  - `[low]` `[patch]` (blind) `error` depends on unordered validator / map order — deterministic sort; test with two invalid fields.
  - `[medium]` `[patch]` (blind) Fixed French locale never exercised; validation outside HTTP stays English — full-context test with `Accept-Language: en`; non-HTTP validation reasons never reach `error` under the opt-in rule.
  - `[low]` `[patch]` (blind) `RateLimitFilter` builds JSON by concatenation — ObjectMapper + constant fallback; test asserts French text and UTF-8.
  - `[low]` `[patch]` (blind) `ModuleRef` compact constructor overwrites `moduleId` — derived `@JsonProperty` accessor.
  - `[medium]` `[patch]` (blind) Rejection details on the rollback path untested — `SyncPushServiceTest` cases for both rollback branches.
  - `[low]` `[patch]` (blind) `conflictData` gains a second meaning, undocumented — DTO doc + Mobile sections 16-10/16-8.
  - `[low]` `[patch]` (blind) Technical words in French messages (device, endpoint, raw parameter names, enum tokens) — reworded.
  - `[low]` `[reject]` (blind) Long Javadoc line in `TenantModulesController` — cosmetic.
  - `[low]` `[reject]` (blind) Missing notification body reports only the title — first error shown by design; dashboard validates the form.
  - `[low]` `[patch]` (blind) `ILIKE` search does not escape `%`/`_` — escaped.
  - `[false]` `[reject]` (intent) Spec/sprint-status/docs not in the reviewed diff — excluded from the review diff on purpose; present in the working tree.
  - `[medium]` `[patch]` (intent) Bean validation tested with hand-built French messages, not on a real route; nondeterministic first field — same roots as the locale and ordering rows.
  - `[high]` `[patch]` (intent) Passthrough broader than « admin services » and reaches V1 routes — same root as the first row.
  - `[medium]` `[patch]` (intent) Sync limit details tested at handler level only — same root as the rollback-test row.
  - `[low]` `[reject]` (intent) CORE_MODULE_REQUIRED not tested through the admin route — checked over HTTP by the implementation run (409, module named); handler + service tests cover both halves.
  - `[low]` `[reject]` (intent) Guard test covers `ErrorCode` constants only, not string codes — filter-only strings come from the same catalogue with a French fallback; sync reasons are enum names.
  - `[high]` `[patch]` (edge) English/technical reasons reach users — same root as the first row.
  - `[high]` `[patch]` (edge) NOT_FOUND reasons with ids/schema names — same root as the first row.
  - `[medium]` `[patch]` (edge) `DomainException(code, null)` yields « …: null » — covered by the opt-in rule (null never passes).
  - `[low]` `[patch]` (edge) Nondeterministic field error — same root as the ordering row.
  - `[medium]` `[patch]` (edge) History tie skip — same root as the keyset row.
  - `[low]` `[reject]` (edge) Millisecond truncation — same as the blind row.
  - `[low]` `[patch]` (edge) Rate-limit JSON escaping — same root as the RateLimitFilter row.
  - `[low]` `[reject]` (edge) PUT unknown plan 422 → 404 — explicitly required by the intent (admin route, not used by V1 apps).
  - `[high]` `[patch]` (edge claim) Passthrough exceeds « admin VALIDATION_ERROR » — same root as the first row.
  - `[medium]` `[patch]` (edge claim) V1 shows English where it showed catalogue French — same root as the first row.
  - `[medium]` `[patch]` (verification-gap) Rollback path details untested — same root as the rollback-test row.
  - `[high]` `[patch]` (verification-gap) No check that passed-through reasons are French — same root as the first row (opt-in + tests).
  - `[low]` `[patch]` (verification-gap) 429 body text/encoding unchecked — same root as the RateLimitFilter row.
  - `[medium]` `[patch]` (verification-gap) French default validation messages untested — same root as the locale row.
  - `[high]` `[patch]` (verification-gap other) Raw throw-site text with UUIDs reaches users today — same root as the first row.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -DforkCount=1 -DargLine=-Xmx1536m -Dtest='com.keevo.<pkg>.**'` for each package (shared, identity, admin, sync, subscription, catalog, inventory, commerce, store, crm, invoicing, reporting, messaging, feedback, acceptance, architecture), one at a time, never two Maven builds in parallel -- expected: green
- `cd keevo/dashboard && npm test` -- expected: green (basic test client must not break)

**Manual checks (if no CLI):**
- Matrix requests with curl against the local backend (`--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false`), sign in through `/auth/login` + `/auth/select-tenant` (dev super-admin `+237600000000` / `Admin@1234!`, tenant `KV-ADMIN`; never forge tokens); stop with `fuser -k 4500/tcp`.

## Auto Run Result

**Summary:** one shared French catalogue (`shared/infrastructure/web/ErrorMessages.java`) used by `GlobalExceptionHandler`, `JwtAuthFilter` (bodies `{domainCode, error}`, UTF-8) and `RateLimitFilter`; every `ErrorCode` has a message (guard test), new `PLAN_PROFILE_NOT_FOUND` (404). an explicit French user message set at the throw site (`DomainException.userFacing`, opt-in) becomes `error`, every other domain error keeps the catalogue text (English/technical reasons never reach the user); admin VALIDATION_ERROR / NOT_FOUND sites, `CORE_MODULE_REQUIRED`, device token and inventory inputs switched to it with plain French (no parameter names, enum tokens or ids); bean-validation field errors sorted by field then constraint so the first message is deterministic; `spring.web.locale: fr` + `locale-resolver: fixed` so Hibernate Validator defaults are French. Employee sees `status`/`graceUntil` of a READ_ONLY module. Sync `PLAN_LIMIT_EXCEEDED` rejections carry the REST details in `conflictData` (also after a rolled-back transaction). Admin: `ModuleRef{id, moduleId, displayName}`, `GET /admin/plan-profiles/{planType}`, unknown plan → 404, tenant search by code, history `limit`/`before`. `MODULE_BACKFILL_RUNNING` kept (still used internally). `docs/entitlements/endpoints.md` regenerated (new GET).

**Verification:** Maven one package at a time — shared 1144 (1 guard failure on `RateLimitFilter` `.formatted(...)`, fixed, then `shared.infrastructure` + `architecture` re-run 645/0), admin 561, sync 565, identity 456, subscription 83, catalog 251, inventory 387, commerce 260, store 60, crm 158, invoicing 170, reporting 404, messaging 176, feedback 52, acceptance 4, architecture 32 (endpoints table regenerated, then green). `keevo/dashboard` `npm test` 379/379.

**HTTP matrix (local backend, WhatsApp noop, FCM off, real sign-ins; test account KV-XULCGF registered for the run):**
- Expired access token (backend restarted with a 1-min token) → 401 `{"domainCode":"TOKEN_EXPIRED","error":"Session expirée, veuillez vous reconnecter"}`, `charset=UTF-8`; garbage token → `UNAUTHORIZED` « Accès non autorisé » ; forced password change → 403 `PASSWORD_CHANGE_REQUIRED` with its French text.
- `POST /auth/login {"phoneNumber":"6990","password":"x"}` → 422, `error` = a French field message, `details.phoneNumber` « Numéro de téléphone invalide (format international, ex. +237612345678) » ; with `Accept-Language: en` still French ; `POST /employees {}` → HV French defaults (« ne doit pas être vide », « ne doit pas être nul »).
- `POST /admin/tenants/{id}/modules/core/deactivate` → 409 « Le module « Socle » fait partie du socle et ne peut pas être désactivé ».
- Employee, `invoicing` READ_ONLY → `status` + `graceUntil` present, `expiresAt` null; ENABLED modules keep both null.
- Sync push `CREATE_STORE` on a Free account at its cap → `REJECTED` / `PLAN_LIMIT_EXCEEDED`, `conflictData {"limit":1,"current":2,"plan":"FREE","entity":"stores"}` = REST 403 `details`.
- `search=KV-1D0VLM` (and lower case) → that tenant only; `GET /admin/plan-profiles/NOPE` → 404 `PLAN_PROFILE_NOT_FOUND` « Offre introuvable » ; profile modules `{"id":"alerts","moduleId":"alerts",…}` ; history `limit=501` → 400, `before=hier` → 400, `limit=2` then `before=<last occurredAt>` → next 2 older entries, no overlap.
- Redmi, Flutter debug 2.0.21 (`adb reverse tcp:4500`): app opens signed in, renews its session, pulls (4 `/sync/pull`, 1 `/sync/push`) and shows the Caisse — no backend ERROR in the log.

**Review fixes (2026-11-01):** opt-in user messages, plain-French wording (`DEVICE_ID_MISMATCH`, `CANNOT_SET_OWN_PASSWORD`, analytics/alerts), deterministic first field, compound history keyset, Jackson rate-limit body, LIKE escaping, derived `moduleId`, new tests (`SyncPushServiceTest` rollback/runtime branches keep `conflictData`, `FrenchValidationFullContextTest` with `Accept-Language: en`, keyset ties and filtered rows), docs (16-4 no longer promises the tenant `code` in the list; 16-8/16-10 and `SyncOperationResultDto` describe `conflictData` on `PLAN_LIMIT_EXCEEDED`). The HTTP matrix above was run before these fixes.

**Left / risks:** HV default messages do not name the field (the field is in `details`). The admin tenant list item has no `code` field (deliberate since 9-1); search by code works. Local dev DB keeps the test account KV-XULCGF (+ its employee); its `invoicing` row was restored, its plan left FREE.

## Mobile (React Native — dépôt AI/bref)

> Destinataire : agent IA qui implémente l'app Expo / React Native sur une branche du dépôt `/home/toor/Project/FreeLance/AI/bref` (base `develop`). Conventions API : section « Mobile » de `spec-16-11-sessions-longues-et-sures.md` ; protocole de synchro : section « Mobile » de `spec-16-10-scope-employe-historique-transferts.md`. Cette story ne fait qu'**ajouter** des champs et des textes (compatible V1 2.0.1).

**Statut** : API livrée (commit `525cbdaa`) — mobile à faire.

**Clé de suivi mobile** : `m16-14-api-prete-client-mobile`

### Ce qui est fait côté API

- Erreurs du filtre d'authentification (401/403 écrits avant tout contrôleur : `TOKEN_EXPIRED`, `TOKEN_INVALID`, `UNAUTHORIZED`, `SESSION_REVOKED`, `ACCOUNT_INACTIVE`, `STORE_REASSIGNED`, `ACCOUNT_SUSPENDED`, `ACCOUNT_DELETION_PENDING`, `PASSWORD_CHANGE_REQUIRED`) : corps `{"domainCode": "…", "error": "<message français>"}` (avant : `domainCode` seul), en UTF-8. `RATE_LIMIT_EXCEEDED` (429) porte aussi son message français.
- **Tout** code d'erreur (`ErrorCode`) a un message français (« appareil » et non « device »…), dans un catalogue unique (`keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/ErrorMessages.java`) partagé par le filtre et les contrôleurs ; un test de garde l'impose.
- `VALIDATION_ERROR` (422, corps de requête invalide) : `error` = message français du premier champ invalide (par ordre alphabétique du nom de champ ; « Données invalides » à défaut), `details` = champ → message français (messages des annotations traduits, messages par défaut en français quel que soit `Accept-Language`). Une erreur métier ne montre son motif que si le serveur l'a écrit pour l'utilisateur ; sinon le texte du catalogue (« Données invalides », « Ressource introuvable »…).
- `GET /api/v1/tenants/me/modules` : pour un **employé**, un module `READ_ONLY` porte désormais `graceUntil` et `status` (seulement ce cas ; `expiresAt` reste réservé au propriétaire).
- Synchro : un rejet `REJECTED` / `PLAN_LIMIT_EXCEEDED` porte dans `conflictData` les mêmes `details` que le 403 REST : `{"entity": "stores"|"products"|"employees", "limit": n, "current": n, "plan": "FREE"|…}`. Tout autre rejet garde `conflictData = null`.

### À utiliser

| Cas | Réponse | Source |
|---|---|---|
| Jeton expiré sur une route protégée | 401 `{"domainCode": "TOKEN_EXPIRED", "error": "Session expirée, veuillez vous reconnecter"}` | `shared/infrastructure/security/JwtAuthFilter.java` (`errorBody`) |
| Compte suspendu, écriture | 403 `{"domainCode": "ACCOUNT_SUSPENDED", "error": "Compte suspendu : consultation seule"}` | idem |
| Choix d'une entreprise sans appartenance | 404 `MEMBERSHIP_NOT_FOUND` « Vous n'avez pas accès à cette entreprise » | `ErrorMessages.java` |
| `POST /api/v1/auth/login` avec un téléphone mal formé | 422 `{"error": "Numéro de téléphone invalide (format international, ex. +237612345678)", "domainCode": "VALIDATION_ERROR", "details": {"phoneNumber": "…"}}` | `identity/auth/adapter/in/rest/dto/LoginRequest.java`, `GlobalExceptionHandler.handleValidation` |
| Module en grâce, employé | `data[i]` = `{…, "state": "READ_ONLY", "status": "EXPIRED"\|"ACTIVE", "graceUntil": "<ISO UTC>", "expiresAt": null}` | `shared/module/adapter/in/rest/TenantModulesController.java` |
| Création poussée au-delà du plafond | `results[i]` = `{"status": "REJECTED", "reason": "PLAN_LIMIT_EXCEEDED", "conflictData": {"entity", "limit", "current", "plan"}}` | `shared/application/port/sync/AbstractSyncOperationHandler.java` (`rejectionDetails`), `sync/sync/application/service/SyncPushService.java` |

Toujours décider sur `domainCode` ; afficher `error`. Les formes de succès (`/auth/*` brut vs `{data}`) et de pagination (`meta` vs `data.content`) ne changent pas.

### À implémenter côté mobile

1. Intercepteur d'erreurs : afficher `error` reçu pour toute erreur, y compris celles du filtre ; garder un repli local (tableau de 16-11) seulement si `error` manque (serveur plus ancien).
2. Formulaires : sur 422 `VALIDATION_ERROR`, afficher `details.<champ>` sous le champ concerné, sinon `error` ; continuer à valider côté app.
3. Bandeau lecture seule (16-1) : pour l'employé aussi, « Module en lecture seule jusqu'au JJ/MM/AAAA » avec `graceUntil` (heure de Douala) ; toujours sans bouton « Réactiver » pour l'employé.
4. Rejet de synchro `PLAN_LIMIT_EXCEEDED` : feuille « plafond atteint » (16-8) avec `conflictData.limit` et `conflictData.entity` ; repli sur `GET /api/v1/subscription/me` si `conflictData` est absent.
5. Jamais de `domainCode`, `reason`, UUID ni texte brut du serveur autre que `error` / `details` à l'écran.

### Critères d'acceptation mobile / scénarios de test

Préparation : backend local (`--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false`, arrêt `fuser -k 4500/tcp`), connexion par l'API comme en 16-11 (`BASE`, `AT`, `AT_EMPLOYE`).

```bash
curl -s $BASE/api/v1/products -H "Authorization: Bearer <jeton expiré>"            # {"domainCode":"TOKEN_EXPIRED","error":"Session expirée, …"}
curl -s -X POST $BASE/api/v1/auth/login -H 'Content-Type: application/json' -d '{"phoneNumber":"6990","password":"x"}' | jq '{error, details}'
curl -s $BASE/api/v1/tenants/me/modules -H "Authorization: Bearer $AT_EMPLOYE" | jq '.data[] | select(.state=="READ_ONLY")'
```

1. **Session expirée** — Given un jeton d'accès expiré et un renouvellement impossible, When l'app appelle une route, Then le message affiché est celui du serveur (« Session expirée, veuillez vous reconnecter »).
2. **Formulaire de connexion** — Given un numéro mal formé envoyé au serveur, Then le message français du champ s'affiche sous « Téléphone » (aucun texte anglais).
3. **Employé en grâce** — Given `invoicing` en grâce (requête SQL de 16-1), When un employé ouvre Factures, Then bandeau « Module en lecture seule jusqu'au JJ/MM/AAAA » sans bouton.
4. **Plafond en synchro** — Given un compte Free à son plafond de boutiques et une boutique créée hors ligne, When la synchro la refuse, Then la feuille dit « 1 boutique » (valeur de `conflictData.limit`) sans appel supplémentaire, et la boutique fantôme disparaît.

## Dashboard super-admin (autre dépôt)

> Destinataire : agent IA qui construit le dashboard super-admin dans un **autre dépôt**. Session : section « Dashboard super-admin » de `spec-16-11-sessions-longues-et-sures.md` ; conventions admin : section « Dashboard super-admin » de `spec-16-4-console-super-admin-modules.md` (mise à jour par cette story).

**Statut** : API livrée (commit `525cbdaa`) — dashboard à faire.

**Clé de suivi dashboard** : `d16-14-api-prete-client-mobile`

### Ce qui est fait côté API

- `VALIDATION_ERROR` des routes admin : `error` porte le vrai message français au lieu de « Données invalides » quand le service en donne un explicitement (modules, offres, paiements, alertes, annonces, statistiques, revenus, tâches) ou pour le premier champ invalide d'un corps (ordre alphabétique) ; sinon le texte du catalogue. `details.<champ>` est conservé.
- `CORE_MODULE_REQUIRED` (409) : `error` nomme le module (« Le module « Socle » fait partie du socle et ne peut pas être désactivé »).
- Profils d'offre : chaque module est `{id, moduleId, displayName}` (`moduleId` = `id`, ajouté pour s'aligner sur les autres routes ; `id` conservé).
- Offre inconnue dans `/api/v1/admin/plan-profiles/{planType}` (`GET` nouveau, `PUT`) → **404** `PLAN_PROFILE_NOT_FOUND` « Offre introuvable » (avant : 422 `PLAN_PROFILE_INVALID` « offre inconnue »).
- `GET /api/v1/admin/tenants?search=` : cherche aussi dans le **code du compte** (`KV-XXXXXX`, partiel, sans casse), en plus du nom et du téléphone du propriétaire.
- `GET /api/v1/admin/tenants/{tenantId}/modules/history` : paramètres facultatifs `limit` (1–500, défaut 100), `before` + `beforeId` (curseur composé `(occurredAt, id)` ; `before` seul = strictement plus ancien) ; chaque entrée porte en plus `id` (curseur, jamais affiché).
- `MODULE_BACKFILL_RUNNING` reste dans le catalogue (utilisé en interne par la tâche de migration) ; la route ne le renvoie toujours pas.

### À utiliser

| Méthode | Route | Paramètres | Réponse |
|---|---|---|---|
| GET | `/api/v1/admin/plan-profiles/{planType}` | — | 200 `data` = un profil (forme de la liste) ; 404 `PLAN_PROFILE_NOT_FOUND` |
| GET | `/api/v1/admin/tenants` | `search` = nom, téléphone ou code | inchangée (`data.items[]`) |
| GET | `/api/v1/admin/tenants/{tenantId}/modules/history` | `limit` (1–500), `before` (ISO), `beforeId` (`id` de la dernière entrée) | `data` = entrées `{occurredAt, action, subject, detail, actorName, actorPhone, id}`, la plus récente d'abord ; 400 `VALIDATION_ERROR` « Le nombre d'entrées doit être compris entre 1 et 500 » / « Date de référence invalide » / « Date de référence manquante » |

Sources : `keevo/backend/src/main/java/com/keevo/admin/plan/adapter/in/rest/AdminPlanProfileController.java`, `.../shared/module/PlanProfileAdmin.java` (`ModuleRef`), `.../admin/tenant/application/service/AdminTenantService.java` (`buildWhereClause`), `.../admin/module/adapter/in/rest/ModuleAdminController.java` (`history`), `.../shared/infrastructure/web/ErrorMessages.java`.

### À implémenter côté dashboard

1. Erreurs : afficher `error` tel quel pour **tous** les codes, `VALIDATION_ERROR` compris ; `details.<champ>` sous le champ quand il existe.
2. Profils d'offre : lire `moduleId` (plus `id`) dans `modules[]`.
3. Recherche de comptes : libellé du champ « Nom, téléphone ou code » ; le code tapé (`KV-…`) trouve le compte.
4. Historique des modules : bouton « Voir plus » (`before` = `occurredAt`, `beforeId` = `id` de la dernière ligne, `limit` = 100), masqué quand une page revient avec moins de `limit` lignes.
5. 404 `PLAN_PROFILE_NOT_FOUND` : message « Offre introuvable » et rechargement de la page des profils.

### Critères d'acceptation / scénarios de test

Préparation : backend local (`--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false`), connexion super-admin par l'API (`+237600000000`, `KV-ADMIN`, variables `BASE`, `H` de 16-4).

```bash
curl -s "$BASE/api/v1/admin/tenants?search=KV-ABC123" -H "$H" | jq '.data.items[] | {code, name}'
curl -s $BASE/api/v1/admin/plan-profiles/NOPE -H "$H" -w '\n%{http_code}\n'                          # 404 PLAN_PROFILE_NOT_FOUND
curl -s "$BASE/api/v1/admin/tenants/$T/modules/history?limit=501" -H "$H" -w '\n%{http_code}\n'      # 400
curl -s -X POST "$BASE/api/v1/admin/tenants/$T/modules/core/deactivate" -H "$H" | jq .error           # nom du module
```

1. **Recherche par code** — When on tape le code d'un compte dans la recherche, Then ce compte seul apparaît.
2. **Socle** — When on tente de désactiver le socle (appel direct), Then le message nomme le module.
3. **Validation** — When on active un module payant en « Actif » sans date, Then le message affiché est « Une date de fin est requise pour un module payant (« … ») ».
4. **Historique paginé** — Given un compte avec plus de 100 entrées, When on clique « Voir plus », Then les 100 suivantes s'ajoutent, plus anciennes, sans doublon.

### Final verification (orchestrator, after review fixes)

- **Review:** 36 findings — 7 high, 10 medium, 18 low, 1 false; patched entries (by root): the service-message leak (high: opt-in French `userFacing` messages, catalogue text otherwise), the history keyset tie (medium: `(occurredAt, id)` with `beforeId`), validation-message locale and ordering (medium: full-context test, deterministic order), and the rollback-path sync details (medium: tests); low patches: short-page end, rate-limit JSON, derived `moduleId`, `ILIKE` escape, plain-French wording, `conflictData` doc, 16-4 doc. 0 deferred; rejected rows with reasons in the triage log.
- **Follow-up review recommended: true** — a high finding (user-facing message passthrough) was patched by a new opt-in mechanism on `DomainException`; a second look at the throw sites switched to `userFacing` is the unverified risk.
- **Backend:** every package one at a time (shared, identity, admin, sync, subscription, catalog, inventory, commerce, store, crm, invoicing, reporting, messaging, feedback, acceptance, architecture) — 4766 tests, 0 failures. **Dashboard:** `npm test` 379/379.
- **HTTP (local backend, WhatsApp noop, FCM off, real super-admin sign-in):** login with a bad phone and `Accept-Language: en` → 422 with French `error` and `details`; no token → `{"domainCode":"UNAUTHORIZED","error":"Accès non autorisé"}`; unknown feedback id (English reason in code) → « Ressource introuvable »; search `KV-ZGGT7G` → that tenant, `KV_ZGGT7G` → none (escaped); history `limit=501` → 400 French; unknown plan profile → 404.
- **Redmi (Flutter test client 2.0.21):** app opens signed in, « En ligne », pull recorded at 21:42:05 UTC, no backend error.
