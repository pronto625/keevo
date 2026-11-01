---
title: '16-11 Long and safe sessions: 30-min access token, 365-day sliding refresh, rights read server-side, per-device sessions'
type: 'feature'
created: '2026-11-01'
status: 'done'
baseline_revision: '05314200efde051010868cfc3b290de0eec286f1'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
warnings: ['oversized', 'multiple-goals']
deferred: []
---

<intent-contract>

## Intent

**Problem:** User decision of 2026-11-01 (Story 16.11 in `_bmad-output/planning-artifacts/epics-refonte-modulaire.md`, read it fully): a user must be able to stay signed in for up to a year without retyping a password, while staying secure. Today the access token lives 60 days (`access-token-expiry-hours: 1440`), so a stolen token works for two months; `JwtAuthFilter` takes the **role** and the **account status** from the token, so a removed right can survive until expiry; the token carries the first name; sessions are not per device and cannot be listed or closed.

**Approach:** Short access token (30 min) renewed silently by the existing refresh flow; refresh token valid 365 days from its last use (rotation and replay detection already exist); the access token proves identity only (`sub`, `tenantId`, `sid`, `iat`, `exp`) and `JwtAuthFilter` loads role, store, membership/employee status, password-change flag, account status and session validity from the server on every request (cached per user+tenant ≤ 30 s, invalidated on every change on all instances via PostgreSQL `LISTEN/NOTIFY`); one session per device with an « Appareils connectés » screen; the apps read role/store/first name from the profile. Legacy claims stay **indicative only** (never read by the server) behind a flag until 16.7 forces the minimum app version.

## Boundaries & Constraints

**Always:**
- Lifetimes (config): access 30 min; refresh 365 days sliding (each rotation resets it — already the case, change 360 → 365); super-admin sessions (dashboard) 12 h sliding. A refresh token unused for 365 days (12 h for super-admin) is refused → sign in again.
- Access token claims: `sub`, `tenantId`, `sid` (device session id), `iat`, `exp`. With `keevo.jwt.legacy-claims=true` (default until 16.7) the token also carries the legacy `role`, `tenantStatus`, `storeId`, `passwordChangeRequired` for installed V1 apps — **never read by any server code** (guard test); `firstName` is removed now in all cases.
- `JwtAuthFilter`: one server-side read of the « access state » (role, membership/employee status, store, password-change flag, tenant status, `tokens_valid_after`, session `sid` still open) → Spring authorities, `CurrentActor`, existing refusals (`ACCOUNT_SUSPENDED`, `ACCOUNT_DELETION_PENDING`, `ACCOUNT_INACTIVE`, `PASSWORD_CHANGE_REQUIRED`, `SESSION_REVOKED`). Cache per (user, tenant), TTL ≤ 30 s, evicted on every change (role, store, status, suspension, deletion, password, session closed) on all instances through `LISTEN/NOTIFY` (channel e.g. `keevo_access_state`); replaces `CachedTokenRevocationAdapter`'s 5-min cache. A guard test asserts no authorization code reads the role / store / status claims.
- `STORE_REASSIGNED`: kept only for tokens that carry a legacy `storeId` claim different from the DB (old apps renew); tokens without it simply follow the DB store.
- Sessions: `refresh_tokens` gains `session_id` (stable per device chain), `device_id`, `device_label` (model/OS, from the client), `last_used_at`; login / tenant selection / refresh take an optional device id and label from the app. Endpoints: `GET /api/v1/auth/sessions` (mine: label, last used, current flag), `DELETE /api/v1/auth/sessions/{sessionId}` (mine), `DELETE /api/v1/auth/sessions` (all others); owner: `GET`/`DELETE /api/v1/employees/{employeeId}/sessions[/{sessionId}]`. Closing a session revokes its refresh chain and marks `sid` closed → next request of that device → 401 `SESSION_REVOKED`. Responses never expose token hashes or raw ids other than the opaque session id.
- `/auth/refresh`: rate-limited per refresh token and per IP (French 429); replaying an already-rotated token revokes the device chain and is audited (existing behaviour, pinned by a test); refresh tokens never logged.
- Profile: `GET /api/v1/auth/profile` returns role, store (id + name), first name, password-change flag, tenant status; the Flutter app and the dashboard read these from the profile (cached for offline display), not from the token. App: « Appareils connectés » screen (Paramètres) listing my sessions with « Déconnecter », and for the owner an « Appareils » section in an employee's detail; silent renewal unchanged (`auth_interceptor.dart`); bump `keevo/app/pubspec.yaml`.
- Dashboard (super-admin): works with 30-min access + 12 h sliding refresh (renew before expiry / on 401), reads identity from the profile.
- French labels everywhere (« Appareil déconnecté », « Toutes les autres sessions fermées »), audit entries for closed sessions, no UUIDs on screen.

**Never:**
- No reading of rights from the token anywhere server-side; no new claim; no mobile offline dependency on a valid access token (sales, closure, sync queue keep working offline as today).
- Do not break installed V1 2.0.1 apps: they keep renewing on `TOKEN_EXPIRED` and keep their legacy claims while the flag is on.
- Never start the local backend without `--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false`; never forge tokens with the dev signing key — sign in through the API.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Silent renewal | access token 31 min old | 401 `TOKEN_EXPIRED` → app renews and replays, nothing shown | — |
| Year-long session | refresh used every 11 months | never asked for a password | — |
| Idle > 365 d | refresh unused 366 days | refresh refused → sign-in screen | French message |
| Demoted manager | role changed in DB, token still valid, 2 instances | next request with manager-only action → 403 on any instance | — |
| Suspended / deletion pending | status changed in DB | next write refused | existing codes |
| Store reassigned | employee moved A→B | next request works on B; legacy-claim token gets `STORE_REASSIGNED` once and renews | — |
| Session closed by owner | owner closes employee's phone session | that phone's next request → 401 `SESSION_REVOKED`, sign-in required | — |
| Replay | rotated refresh token presented again | whole device chain revoked, audited | — |
| Refresh flood | > N refresh calls/min | 429 French | — |
| Decode token | any access token | no first name; with flag off only sub/tenantId/sid/iat/exp | — |
| Offline 5 days | device offline | works offline; on return renews and syncs without password | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtTokenProvider.java` -- claims (`tenantId`, `role`, `tenantStatus`, `storeId`, `passwordChangeRequired`, `firstName`), lifetimes from `JwtProperties`; `JwtAuthFilter.java:137-272` -- role/tenantStatus from claims, `tokens_valid_after` check, EMPLOYEE DB guard (store, status, password flag), authorities.
- `.../identity/auth/application/service/SessionTokenIssuer.java:79-81` -- refresh creation/rotation (expiry from `refresh-token-expiry-days`); `.../identity/auth/domain/model/RefreshToken.java` -- rotation/replay model; `V10__auth_token_hygiene.sql` -- `refresh_tokens` indexes.
- `.../identity/auth/adapter/out/persistence/impl/CachedTokenRevocationAdapter.java` (5-min Caffeine) + `JdbcTokenRevocationAdapter.java`; `identity/api/TokenRevocationPort.java`.
- Rights changes to hook for invalidation: `ChangeEmployeeRoleService`, `ReassignStoreService`, `DeactivateEmployeeService`, `ChangePasswordService`, `SetEmployeePasswordService`, `RegeneratePasswordService`, `AdminTenantSuspensionService`, account deletion (16.6 / 14.5), session close.
- `.../identity/auth/adapter/in/rest/AuthController.java` (`/profile` ~273, login/select-tenant/refresh); `CurrentActor.java`, `AuthDetails.java`.
- `keevo/backend/src/main/resources/application.yml:124-138` -- token lifetimes.
- App: `keevo/app/lib/core/network/auth_interceptor.dart`, `lib/core/auth/session_refresher.dart`, `lib/features/auth/presentation/provider/session_claims_applier.dart` (reads role/storeId/passwordChangeRequired/firstName from claims), `packages/keevo_core/lib/auth/jwt_claims.dart`, `lib/features/auth/data/repository/secure_token_storage.dart`, settings page (`lib/features/settings/presentation/page/settings_page.dart`), team/employee detail pages.
- Dashboard: `keevo/dashboard/src/lib/api/client.ts`, `src/lib/api/auth.ts`, `src/components/auth/*`.
- Next migration: `V36__device_sessions.sql`.

## Tasks & Acceptance

**Execution:**
- Backend: V36 (session columns + `access_state` support if needed); `JwtTokenProvider` (minimal claims + legacy flag, `sid`); `AccessStateService` + cache + `LISTEN/NOTIFY` invalidation (+ fallback TTL) used by `JwtAuthFilter`; invalidation calls in every rights-changing service; lifetimes; refresh rate limit; session endpoints (mine + owner for employees) with audit; profile extension; guard tests (no claim-based authorization, no firstName, multi-instance invalidation simulated with two caches); tests for every matrix row.
- App: profile-based identity (role/store/first name/password flag cached), `session_claims_applier` stops reading claims, device id/label sent at login/refresh, « Appareils connectés » screen + owner section in employee detail, tests, pubspec bump.
- Dashboard: refresh before expiry / on 401 with the 12 h super-admin session, identity from profile, tests, build.
- Epics doc: tick 16.11; note in 16.7 that `keevo.jwt.legacy-claims` is switched off once the minimum app version is forced.

**Acceptance Criteria:**
- Given the Redmi on a local backend with the access lifetime temporarily set to 2 minutes (config override for the check only), when the owner uses the app across 5 minutes, then no sign-in is asked and requests keep working; in « Appareils connectés » the Redmi is listed as the current device; when the session is closed from another session (API as the same owner) the Redmi's next request leads to the sign-in screen.
- Given an employee token, when the employee is demoted/deactivated in the DB, then their next request is refused immediately (backend test with two filter instances sharing the DB and the NOTIFY channel).

## Spec Change Log

## Review Triage Log

### 2026-11-01 — Review pass
- verdicts: 46 findings — high 0, medium 13, low 20, false 5, maybe-false 8
- findings:
  - `[medium]` `[patch]` (blind) Pre-deploy access tokens without `sid` stay valid up to 60 days and closing the device does not cut them — filter treats a sid-less token older than the access lifetime as `TOKEN_EXPIRED` (V1 renews on it, verified on `release/2.0.x` `auth_interceptor.dart`); test.
  - `[medium]` `[patch]` (blind) Caller without `sid` closing « all others » closes its own session; no current row — « close all others » refused when the caller's session is unknown; test.
  - `[medium]` `[patch]` (blind) Forced password change: `GET /auth/profile` answered 403, app never learns the flag — `GET /api/v1/auth/profile` allowed during the forced change; test.
  - `[false]` `[reject]` (blind) `UserProfileService` defaults role OWNER / status ACTIVE — the filter refuses any request without a membership row and tenant before the profile runs; defaults unreachable (pre-existing code bf609e87).
  - `[maybe-false]` `[reject]` (blind) `X-Forwarded-For` forgeable / CDN shares an IP — right-most entry is the address seen by the single appending reverse proxy; only a directly reachable backend would allow forging; would need the prod proxy topology. If-true low (per-IP is the secondary limit after per-token).
  - `[low]` `[reject]` (blind) Limiter per instance, `/logout` unlimited, garbage tokens evict buckets — single prod instance; logout only revokes the presented token; 100k cap; guards would add complexity.
  - `[false]` `[reject]` (blind) Employee writes outside `EmployeeRepositoryAdapter` not broadcast — no other write to `employees` exists in main code (grep); future paths still bounded by the 30 s TTL.
  - `[low]` `[reject]` (blind) Eviction scans the whole cache — cache size bounded by active users per instance; ticket guard already correct; optimisation only.
  - `[low]` `[reject]` (blind) Closed `sid` reloads from DB on every request — the device gets 401 and the app signs out; only a hostile client loops; negative cache = new structure.
  - `[maybe-false]` `[reject]` (blind) Half-open LISTEN connection undetected — Postgres on the same host/network; falls back to the 30 s TTL; if-true low.
  - `[low]` `[reject]` (blind) Legacy claims drop `firstName`; V1 30-min renewal unproven — removal of firstName is the user decision of the intent; V1 2.0.x interceptor renews on `TOKEN_EXPIRED` (read on `release/2.0.x`); real-device V1 check owned by 16.7 (epics item).
  - `[low]` `[reject]` (blind) Replay audited with the user as actor — wording « vu sa session coupée par sécurité » already describes the user as subject; no wrong attribution shown.
  - `[low]` `[reject]` (blind) No absolute cap for sliding sessions — intent mandates sliding 365 d / 12 h.
  - `[low]` `[reject]` (blind) Duplicated dashboard tabs can trip replay detection — super-admin only, consequence is signing in again; cross-tab locking is new machinery.
  - `[low]` `[patch]` (blind) Refused dashboard login leaves the issued session open — `/auth/logout` called before throwing; test.
  - `[low]` `[reject]` (blind) iOS/desktop labels « Ios », substring `isPhone` — the app ships on Android only.
  - `[low]` `[patch]` (blind) Dead `HttpServletRequest` params in Sale/SaleHistory/InventorySession controllers — removed.
  - `[medium]` `[patch]` (verification-gap) Employee eviction through `EmployeeRepositoryAdapter` never exercised — broadcast test case through the Spring-wired adapter.
  - `[low]` `[patch]` (verification-gap) Password change keeping sid/label unverified — `ChangePasswordServiceTest` asserts sid and `DeviceInfo`.
  - `[low]` `[patch]` (verification-gap) Per-IP limit and `clientIp` untested — `RefreshRateLimiterTest` + `clientIp` case.
  - `[medium]` `[patch]` (verification-gap) App profile JSON mapping / fetcher untested — `AccountProfile.fromJson` + fetcher tests.
  - `[medium]` `[patch]` (verification-gap) Startup test fixture impossible in prod (forced password change) — seed-only case added after the routing fix.
  - `[medium]` `[patch]` (verification-gap other) Employee with forced change routed to `/onboarding/sector` on restart — password check moved before the onboarding decision (same root as the profile 403 row).
  - `[maybe-false]` `[reject]` (verification-gap other) Failed profile fetch wipes onboarding keys → onboarded owner sent to wizard after sign-out — `wizardDone` also falls back to `tenantStatus`/seen keys; the profile is fetched again at next start online; would need a device run with the profile endpoint failing; if-true low.
  - `[low]` `[patch]` (verification-gap other) Stale `ReassignStoreService` comment — updated.
  - `[medium]` `[patch]` (edge) Closing a session with a null label answers 404 — view found first, label read after; test.
  - `[low]` `[reject]` (edge) Audit failure rolls back the replay revocation — `AuditPort.record` runs REQUIRES_NEW on the same DB; failure there is unlikely and identical to every audited write in the codebase.
  - `[medium]` `[patch]` (edge) sid-less tokens not revoked — same root as the first blind row.
  - `[medium]` `[patch]` (edge) closeOthers with null keep — same root as the second blind row.
  - `[low]` `[patch]` (edge) Refresh overwrites a known device field with null — field-by-field merge; test.
  - `[medium]` `[patch]` (edge) Forced password change, profile 403 — same root as the third blind row.
  - `[maybe-false]` `[reject]` (edge) `clientIp` trust — duplicate of the blind X-Forwarded-For row.
  - `[low]` `[reject]` (edge) Uncached reload for closed sid — duplicate of the blind row.
  - `[low]` `[patch]` (edge) Fresh state reloaded by `isSessionOpen` not used by the filter — fresh state returned and used.
  - `[low]` `[patch]` (edge) Listener thread dies on non-Exception Throwable — Throwable caught, cache cleared, reconnect kept.
  - `[low]` `[patch]` (edge) Employee devices section shows every error as offline — mapped French error shown.
  - `[maybe-false]` `[reject]` (edge claim) Legacy `storeId` claim read by the filter — explicit carve-out of the intent (`STORE_REASSIGNED` kept for legacy-claim tokens), whitelisted by the guard test.
  - `[medium]` `[patch]` (edge claim) Session check skipped for sid-less tokens — same root as the first blind row.
  - `[maybe-false]` `[reject]` (intent) `STORE_REASSIGNED` kept vs epic « plus de STORE_REASSIGNED » — the intent contract keeps it for legacy-claim tokens until 16.7 switches the flag off (epics item).
  - `[false]` `[reject]` (intent) Legacy storeId read contradicts « never read » — the intent itself carves out this one read.
  - `[maybe-false]` `[reject]` (intent) Demotion → 403 tested at filter authorities, not at a manager-only endpoint — endpoint authorization is unchanged `@PreAuthorize` on the same authorities; the full-context session tests cover HTTP; if-true low.
  - `[false]` `[reject]` (intent) Employee eviction relies on the adapter — no other write path exists (grep); adapter case now tested.
  - `[maybe-false]` `[reject]` (intent) V1-shaped token renewal not checked on a real device — V1 interceptor code verified; real-device V1 check belongs to 16.7.
  - `[medium]` `[patch]` (intent) Owners lost their first name in `actorLabel` — `public.users` has no first name; the employee row (promoted owners) is now loaded for every user, so owners with a row get their name back, as before 16.11.
  - `[false]` `[reject]` (intent) Password-change enforcement still EMPLOYEE-only — unchanged pre-existing rule; owners set their own password.
  - `[low]` `[patch]` (intent) `ProductImportController` message mentions the token — reworded.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -DforkCount=1 -DargLine=-Xmx1536m -Dtest='com.keevo.shared.infrastructure.**'` then `'com.keevo.identity.**'`, `'com.keevo.admin.**'`, `'com.keevo.sync.**'`, `'com.keevo.shared.module.**'`, `'com.keevo.architecture.**'` and any other package touched -- expected: green, one package at a time
- `cd keevo/app && flutter analyze` then `flutter test --concurrency=4` per folder (`test/core`, each `test/features/*`, `packages/keevo_core`) -- expected: green
- `cd keevo/dashboard && npm run lint && npm test && npm run build` -- expected: green

**Manual checks:**
- Device AC above (debug build `com.example.keevo`, `adb reverse tcp:4500 tcp:4500`, backend with WhatsApp noop / FCM off, sign in through the app; restore the lifetime config afterwards; never open share sheets / wa.me; stop the backend with `fuser -k 4500/tcp`).

## Auto Run Result

Status: done

**Summary:** access token 30 min carrying only `sub`/`tenantId`/`sid`/`iat`/`exp` (legacy V1 claims behind `keevo.jwt.legacy-claims`, firstName removed); refresh 365 days sliding, super-admin 12 h; `JwtAuthFilter` takes role, membership, tenant status, revocation cutoff, open sessions and the employee row from one DB read (`AccessStateService`, cache ≤ 30 s, evicted on every instance through `LISTEN/NOTIFY keevo_access_state`, triggers in V36 + `EmployeeRepositoryAdapter`); one session per device (V36), `GET/DELETE /api/v1/auth/sessions[/{id}]` and owner `GET/DELETE /api/v1/employees/{id}/sessions[/{id}]`, audited; replay revokes the device chain only; refresh rate limit 10/min per token, 300/min per IP; profile extended; app reads identity from the profile with an « Appareils connectés » screen; dashboard renews before expiry and on `TOKEN_EXPIRED`.

**Files:** backend `shared/infrastructure/security/` (filter, token provider, `access/` package), `identity/auth/` (sessions service/controller, refresh, rate limiter, issuer, profile), `identity/employee/` (adapter eviction, owner session endpoints, change password), `V36__device_sessions.sql`; app `lib/core/auth`, `lib/features/auth`, `lib/features/devices`, settings and team pages, `keevo_core` `JwtClaims`, version 2.0.21+93; dashboard `src/lib/api/` renewal and profile-based role; epics doc (16.11 ticked, 16.7 note to switch the legacy flag off).

**Review:** 46 findings — 22 patch rows (13 entries: medium 8, low 5 at entry verdict) covering: unclosable sessions without label, sid-less pre-deploy tokens cut after the access lifetime (`TOKEN_EXPIRED`), « close others » refused without a current session, profile readable during a forced password change + startup route checks the password first, device fields merged on refresh, filter uses the fresh state after a session reload, listener survives any Throwable, owner first name restored from the employee row, dead params/messages/comments, missing tests (adapter eviction across instances, change-password session carry, per-IP limiter, `clientIp`, profile JSON/fetcher, seed-only startup); refused dashboard login closes its session; employee devices section shows real errors. 0 deferred. 24 rejected with reasons in the triage log (X-Forwarded-For trust depends on the single appending proxy, per-instance limiter, cache scan cost, negative session cache, listener liveness, super-admin absolute cap, multi-tab dashboard replay, iOS labels, legacy `storeId` carve-out, endpoint-level 403 already enforced by unchanged `@PreAuthorize`, V1 real-device check owned by 16.7).

**Follow-up review recommended: true** — several medium patches landed after the review on the security path (sid-less token cut-off, fresh-state reuse in `JwtAuthFilter`, password-change allow-list); a second pass on `JwtAuthFilter` / `AccessStateService` is the unverified risk.

**Verification:** backend every package one at a time (shared, identity, admin, sync, architecture, commerce, inventory, catalog, store, subscription, crm, invoicing, messaging, reporting, feedback, acceptance) — 4691 tests, 0 failures; `flutter analyze` no errors/warnings, `flutter test` green in all 26 folders + `keevo_core`; dashboard lint, 379 tests, build green. Redmi (before fixes, implementation agent): 2-min access token, two silent renewals over ~6 min, no password asked, Redmi listed as current device, closing it → sign-in screen. Redmi (after fixes): sign-in, home loads, Redmi listed as « Xiaomi 23106RN0DA · Android 15 » in the API session list, « close all others » from an API session → Redmi back to the sign-in screen at its next request.

**Residual risks:** each instance holds one DB connection for `LISTEN`; per-IP limit assumes a single appending reverse proxy; V1 2.0.1 renewal every 30 min verified in code only (real-device check in 16.7).

## Mobile (React Native — dépôt AI/bref)

> Destinataire : agent IA qui implémente l'app Expo / React Native sur une branche du dépôt `/home/toor/Project/FreeLance/AI/bref` (base `develop` : routes `app/`, métier `features/<module>/{api,hooks,screens,services,types}`, client HTTP `lib/sdk/api/api.client.ts` + `lib/sdk/api/api.interceptor.ts`, jetons `lib/sdk/storage/` sur `expo-secure-store`, TanStack Query). Cette section sert aussi de **référence de conventions API** pour les sections « Mobile » des autres specs 16-x.

**Statut** : API livrée (commit `7b8f04d9`) — mobile à faire.

**Clé de suivi mobile** : `m16-11-sessions-longues-et-sures`

### Ce qui est fait côté API

- Jeton d'accès (JWT RS256) valable **30 min**, qui ne prouve que l'identité : `sub` (id utilisateur), `tenantId` (schéma du compte), `sid` (session de l'appareil), `iat`, `exp`. Tant que `keevo.jwt.legacy-claims=true` (jusqu'à 16.7), il porte aussi `role`, `tenantStatus`, `storeId`, `passwordChangeRequired` pour les apps V1 installées — **indicatifs, jamais lus par le serveur, à ne pas lire par l'app**. Plus de `firstName`, aucune liste de modules.
- Jeton de renouvellement (opaque) valable **365 jours glissants** : chaque renouvellement le remplace (rotation) et repart pour 365 jours. Un utilisateur qui ouvre l'app au moins une fois par an ne ressaisit jamais son mot de passe.
- Le serveur relit à chaque requête le rôle, la boutique, les statuts (compte, employé), l'obligation de changer le mot de passe et l'ouverture de la session (cache ≤ 30 s invalidé à chaque changement) : un droit retiré s'applique à la requête suivante.
- Une session par appareil (identifiant + libellé envoyés par l'app), listable et fermable : « Appareils connectés » (mes appareils) et, pour le propriétaire, les appareils d'un employé.
- Renouvellement limité à 10 appels/min par jeton et 300/min par IP (`RATE_LIMIT_EXCEEDED`, 429).
- Profil `GET /api/v1/auth/profile` = seule source du rôle, de la boutique, du prénom, du statut du compte, de l'obligation de changer le mot de passe et de l'onboarding (lisible même pendant un changement de mot de passe forcé).

### À utiliser

Base locale : `http://localhost:4500` ; toutes les routes sont sous `/api/v1/...` (attention : le client Bref actuel appelle `/v1/...` — la base URL Keevo ne contient pas `/api`). En-tête `Authorization: Bearer <accessToken>` sur toute route non publique. Routes publiques (aucun Bearer) : `/api/v1/auth/{register,login,refresh,logout,select-tenant,forgot-password,reset-password}`.

**Enveloppes de réponse (deux formes, cf. incohérences plus bas)** — source : `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/ApiResponseWrapper.java`, `GlobalExceptionHandler.java`, `shared/infrastructure/security/JwtAuthFilter.java` :
- Succès des contrôleurs ordinaires : `{"data": …, "timestamp": "…"}` (+ `meta` pour certaines listes paginées).
- Succès de `/auth/login`, `/auth/select-tenant`, `/auth/refresh`, `/auth/change-password`, `/auth/register`, `/auth/profile` : **JSON brut, sans `data`**.
- Erreur métier : `{"error": "<message français>", "code": "<statut HTTP>", "domainCode": "<ErrorCode>", "details": {…}, "timestamp": "…"}`.
- Erreur du filtre d'authentification (401/403 avant le contrôleur) : **uniquement** `{"domainCode": "…"}` (pas de message) → l'app porte elle-même les textes français ci-dessous.
- Toujours décider sur `domainCode` (jamais sur le texte). Catalogue complet : `keevo/backend/src/main/java/com/keevo/shared/domain/exception/ErrorCode.java` ; messages et statuts : `GlobalExceptionHandler.java` (`FR_MESSAGES`, `domainCodeToHttpStatus`).

**Connexion en deux étapes** (`keevo/backend/src/main/java/com/keevo/identity/auth/adapter/in/rest/AuthController.java`, DTO dans `.../rest/dto/`) :

| Étape | Requête | Réponse 200 (brute) |
|---|---|---|
| 1 | `POST /api/v1/auth/login` `{"phoneNumber": "+2376…" (E.164), "password": "…" (8–72)}` | `{"loginToken": "…" (5 min), "memberships": [{"tenantCode": "KV-XXXXXX", "tenantName": "…", "role": "OWNER"\|"EMPLOYEE", "schemaName": "…"}]}` |
| 2 | `POST /api/v1/auth/select-tenant` `{"loginToken", "tenantCode", "deviceId"?, "deviceLabel"?}` (≤ 256 car.) | `{"accessToken", "refreshToken", "userId", "tenantId", "role", "expiresIn" (secondes), "storeId", "passwordChangeRequired"}` |

Une seule appartenance → enchaîner l'étape 2 sans écran ; plusieurs → écran de choix d'entreprise (afficher `tenantName`, jamais `tenantCode`/`schemaName` comme libellé principal). `login` ne prend **pas** les champs appareil : ils partent à l'étape 2 et à chaque renouvellement.

**Renouvellement** : `POST /api/v1/auth/refresh` `{"refreshToken", "deviceId"?, "deviceLabel"?}` → même corps que l'étape 2 ; **stocker les deux nouveaux jetons** (l'ancien jeton de renouvellement est consommé). Règles serveur (`.../identity/auth/application/service/RefreshTokenService.java`) : jeton inconnu, expiré, révoqué ou compte retiré → 401 `REFRESH_TOKEN_INVALID` ; présentation d'un jeton déjà remplacé par un **autre** détenteur (rejeu) → toute la chaîne de l'appareil est révoquée + 401 `REFRESH_TOKEN_INVALID` ; tolérance « réponse perdue » : si l'app n'a jamais reçu la réponse d'un renouvellement, re-présenter l'ancien jeton dans les 7 jours fonctionne **une fois** (le successeur jamais reçu est écarté).

**Déconnexion** : `POST /api/v1/auth/logout` `{"refreshToken"}` → toujours 204, même avec un jeton d'accès expiré (ne pas renouveler avant).

**Changement de mot de passe** : `POST /api/v1/auth/change-password` (Bearer) `{"currentPassword", "newPassword" (8–72)}` → corps de connexion avec de **nouveaux jetons** (même session d'appareil) ; remplacer les jetons stockés.

**Profil** : `GET /api/v1/auth/profile` → `{"userId", "phoneNumber", "role", "firstName", "lastName", "storeId", "storeName", "passwordChangeRequired", "tenantStatus", "onboardingCompleted"}` (`firstName/lastName/storeId/storeName` null pour un propriétaire sans fiche employé).

**Appareils connectés** (`.../identity/auth/adapter/in/rest/DeviceSessionController.java`), réponses enveloppées `{data}` :

| Méthode | Route | Qui | Réponse `data` |
|---|---|---|---|
| GET | `/api/v1/auth/sessions` | tout utilisateur | `[{"sessionId", "deviceLabel" ("Appareil inconnu" si absent), "lastUsedAt", "current"}]` |
| DELETE | `/api/v1/auth/sessions/{sessionId}` | tout utilisateur | `null` |
| DELETE | `/api/v1/auth/sessions` | tout utilisateur (ferme toutes les autres) | `{"closed": n}` |
| GET | `/api/v1/employees/{employeeId}/sessions` | OWNER | idem liste |
| DELETE | `/api/v1/employees/{employeeId}/sessions/{sessionId}` | OWNER | `null` |
| DELETE | `/api/v1/employees/{employeeId}/sessions` | OWNER (toutes) | `{"closed": n}` |

**Codes à gérer** :

| `domainCode` | HTTP | Message français (serveur ou à porter) | Réaction attendue de l'app |
|---|---|---|---|
| `TOKEN_EXPIRED` | 401 | Session expirée, veuillez vous reconnecter | Renouveler (single-flight) puis rejouer **une fois** la requête ; rien à l'écran. Sur `/auth/select-tenant` : le `loginToken` a expiré → recommencer la connexion |
| `STORE_REASSIGNED` | 401 | Votre boutique a été modifiée, veuillez vous reconnecter | Comme `TOKEN_EXPIRED` (ne concerne que des jetons à claims hérités), puis recharger le profil |
| `SESSION_REVOKED` | 401 | Votre session a été révoquée, veuillez vous reconnecter | Effacer les jetons et l'identité en cache → écran de connexion avec ce message ; **ne pas renouveler** |
| `ACCOUNT_INACTIVE` | 401 | Ce compte a été désactivé | Idem `SESSION_REVOKED` |
| `REFRESH_TOKEN_INVALID` | 401 | Session expirée, veuillez vous reconnecter | (sur `/auth/refresh`) fin de session → écran de connexion |
| `TOKEN_INVALID` / `UNAUTHORIZED` | 401 | Jeton d'authentification invalide / Accès non autorisé | Ne pas renouveler (comme la référence) : erreur de la requête. Au démarrage, un jeton stocké illisible est effacé → écran de connexion |
| `INVALID_CREDENTIALS` | 401 | Téléphone ou mot de passe incorrect | Message sous le formulaire ; **ne pas** tenter de renouvellement (route publique) |
| `ACCOUNT_LOCKED` | 401 | Compte temporairement bloqué, réessayez dans 15 minutes | Message ; pas de renouvellement |
| `TENANT_NOT_FOUND` / `MEMBERSHIP_NOT_FOUND` | 404 | Espace de travail introuvable / (pas de message FR : porter « Vous n'avez pas accès à cette entreprise ») | Retour au choix d'entreprise |
| `PASSWORD_CHANGE_REQUIRED` | 403 | Vous devez changer votre mot de passe avant de continuer | Écran de changement de mot de passe obligatoire (seules `/auth/change-password` et `GET /auth/profile` passent) |
| `ACCOUNT_SUSPENDED` | 403 | (pas de message FR : filtre) porter « Compte suspendu : consultation seule » | Écritures refusées, lectures OK ; bandeau, pas de déconnexion |
| `ACCOUNT_DELETION_PENDING` | 403 | Suppression de compte en cours — écriture bloquée | Écritures refusées sauf `POST /api/v1/account/delete/cancel` |
| `FORBIDDEN` | 403 | Vous n'avez pas les droits nécessaires | Message ; masquer l'action (ex. employé sur les routes OWNER) |
| `DEVICE_SESSION_NOT_FOUND` | 404 | Cet appareil n'est plus connecté | Rafraîchir la liste ; aussi renvoyé par « fermer les autres » quand la session courante est inconnue |
| `EMPLOYEE_NOT_FOUND` | 404 | Employé introuvable | Message |
| `RATE_LIMIT_EXCEEDED` | 429 | Trop de requêtes, veuillez patienter | Sur `/auth/refresh` : **ne pas** déconnecter, réessayer plus tard |
| `VALIDATION_ERROR` | 422 | `error` = « Validation failed » (anglais), `details` = champ → message anglais | Ne jamais afficher tel quel : valider côté app avec des libellés français |
| `INTERNAL_ERROR` | 500 | Une erreur inattendue s'est produite | Erreur réseau « réessayer » ; ne pas déconnecter |

### À implémenter côté mobile

1. **Stockage** : `accessToken`, `refreshToken` dans `expo-secure-store` ; un `deviceId` UUID v4 généré une fois par installation et conservé en stockage sécurisé (le même que pour la synchro, `X-Device-Id` / `deviceId` du push) ; `deviceLabel` lisible « Marque Modèle · Android 15 » (ex. via `expo-device`).
2. **Connexion** : flux 2 étapes ci-dessus ; `deviceId` + `deviceLabel` envoyés à `select-tenant` et à **chaque** `refresh`.
3. **Intercepteur** (remplace la logique Bref « tout 401 → refresh ») : renouveler **seulement** sur 401 `TOKEN_EXPIRED` ou `STORE_REASSIGNED`, et proactivement avant l'envoi quand `exp` (seul champ du jeton que l'app lit) est à moins d'1 min. Un seul renouvellement à la fois (promesse partagée) : deux renouvellements parallèles se rejouent mutuellement et ferment la session. Si le jeton stocké a changé pendant que la requête était en vol, rejouer avec lui sans renouveler. Rejouer la requête d'origine **une seule fois**.
4. **Issue du renouvellement** : 400/401/403 → session terminée (effacer jetons + identité en cache, écran de connexion) ; 429, 408, 5xx, hors ligne → **garder la session** et continuer hors ligne (ventes, clôture, file de synchro ne dépendent jamais d'un jeton valide).
5. **Fin de session serveur** : 401 `SESSION_REVOKED` ou `ACCOUNT_INACTIVE` → effacer jetons et identité, écran de connexion avec le message français. Les secrets d'appareil (`deviceId`, clé de la base locale) restent.
6. **Démarrage** : jeton absent → connexion ; jeton expiré → renouveler ; injoignable → entrer quand même (session hors ligne) ; refusé → connexion. Puis ordre de routage : `passwordChangeRequired` → écran de changement de mot de passe ; onboarding non terminé → assistant ; sinon OWNER → tableau de bord, EMPLOYEE → caisse.
7. **Identité** : rôle, boutique (`storeId`, `storeName`), prénom, `tenantStatus`, `onboardingCompleted`, `passwordChangeRequired` lus **uniquement** dans `GET /auth/profile`, rechargé à chaque connexion / changement d'entreprise / renouvellement de session, et mis en cache pour l'affichage hors ligne. Jusqu'à la première réponse d'une nouvelle session, n'utiliser que `role`/`storeId`/`passwordChangeRequired` de la réponse de connexion, jamais l'identité d'un utilisateur précédent. **Ne jamais lire de droit dans le jeton.**
8. **Écran « Appareils connectés »** (Paramètres › Compte, tout rôle) : liste (appareil courant en premier, « Cet appareil »), libellé + « Dernière activité » en date locale, bouton « Déconnecter » par ligne (confirmation, puis toast « Appareil déconnecté ») et « Déconnecter tous les autres appareils » (confirmation « Cet appareil reste connecté. », toast « Toutes les autres sessions fermées »). Jamais de `sessionId` ni d'UUID à l'écran.
9. **Fiche employé (OWNER)** : section « Appareils » avec la même liste et les mêmes actions via `/api/v1/employees/{employeeId}/sessions…` ; erreurs affichées avec le message français (pas « hors ligne » par défaut).
10. **Changement de mot de passe forcé** : écran bloquant ; à la réussite, remplacer les jetons par ceux de la réponse puis recharger le profil.

### Implémentation de référence (Flutter, `keevo/app`)

- `lib/core/network/auth_interceptor.dart`, `lib/core/auth/session_refresher.dart` (single-flight, issues renewed/rejected/unreachable)
- `lib/core/device/device_descriptor.dart` (deviceId + libellé), `lib/core/router/startup_route.dart` (décision de démarrage)
- `lib/features/auth/data/session_identity.dart`, `lib/features/auth/domain/model/account_profile.dart`, `lib/features/auth/presentation/provider/session_claims_applier.dart`, `lib/features/auth/data/repository/secure_token_storage.dart`
- `lib/features/devices/` (page `connected_devices_page.dart`, `widget/employee_devices_section.dart`, `data/remote_device_sessions_datasource.dart`), hôte owner : `lib/features/team/presentation/page/edit_employee_page.dart`

### Critères d'acceptation mobile / scénarios de test

Préparation (backend local ; toujours WhatsApp noop et FCM coupé ; arrêt par `fuser -k 4500/tcp`) :

```bash
cd keevo/backend && mvn -q spring-boot:run -Dspring-boot.run.arguments="--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false --keevo.jwt.access-token-expiry-minutes=2"
BASE=http://localhost:4500
LT=$(curl -s -X POST $BASE/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"phoneNumber":"+2376XXXXXXXX","password":"<mot de passe>"}' | jq -r .loginToken)
TOKENS=$(curl -s -X POST $BASE/api/v1/auth/select-tenant -H 'Content-Type: application/json' \
  -d "{\"loginToken\":\"$LT\",\"tenantCode\":\"KV-XXXXXX\",\"deviceId\":\"curl-poste\",\"deviceLabel\":\"Poste de test\"}")
AT=$(echo "$TOKENS" | jq -r .accessToken)
curl -s $BASE/api/v1/auth/sessions -H "Authorization: Bearer $AT" | jq .data
```

1. **Renouvellements silencieux** — Given l'app connectée sur un backend dont le jeton d'accès dure 2 min, When l'owner navigue ~6 min (au moins deux expirations), Then aucune demande de mot de passe, les écrans se chargent, et `GET /auth/sessions` montre l'appareil avec un `lastUsedAt` qui avance.
2. **Appareil courant listé** — Given l'app connectée, When on ouvre « Appareils connectés », Then l'appareil apparaît en premier avec son libellé (ex. « Xiaomi 23106RN0DA · Android 15 ») et la mention « Cet appareil », sans UUID.
3. **Session fermée à distance** — Given l'app connectée et une session curl du même owner, When `curl -X DELETE $BASE/api/v1/auth/sessions -H "Authorization: Bearer $AT"` (ferme toutes les autres) ou `DELETE …/sessions/{sessionId de l'app}`, Then à la requête suivante de l'app (401 `SESSION_REVOKED`) elle revient à l'écran de connexion avec « Votre session a été révoquée, veuillez vous reconnecter ».
4. **Employé coupé par l'owner** — Given un employé connecté sur l'appareil, When l'owner ferme sa session depuis la fiche employé (ou `DELETE $BASE/api/v1/employees/{employeeId}/sessions`), Then l'appareil de l'employé revient à la connexion à sa requête suivante.
5. **Hors ligne prolongé** — Given l'app connectée, When le réseau est coupé plusieurs jours (ou le backend arrêté), Then l'app démarre et vend hors ligne sans écran de connexion ; au retour du réseau, renouvellement et synchro sans mot de passe.
6. **Renouvellement limité** — Given > 10 renouvellements/min du même jeton (`curl` en boucle sur `/auth/refresh`), Then 429 `RATE_LIMIT_EXCEEDED` ; l'app, elle, ne se déconnecte pas sur un 429.
7. **Rejeu** — Given un jeton de renouvellement R1 échangé contre R2 (curl), puis R2 échangé contre R3, When R1 est re-présenté à `/auth/refresh`, Then 401 `REFRESH_TOKEN_INVALID` et toute la session de cet appareil est fermée : le détenteur de R3 reçoit `SESSION_REVOKED` à sa requête suivante et revient à la connexion. (Re-présenter R1 alors que R2 n'a jamais servi est au contraire accepté une fois : tolérance « réponse perdue ».)
8. **Mot de passe forcé** — Given un employé créé avec mot de passe temporaire, When il se connecte, Then l'app ouvre directement le changement de mot de passe (aussi après redémarrage), et toute autre route renvoie 403 `PASSWORD_CHANGE_REQUIRED` ; après changement, l'app continue avec les nouveaux jetons.
9. **Droits côté serveur** — Given un utilisateur dont l'owner change le rôle ou qu'il désactive, When l'app fait sa requête suivante, Then refus immédiat (403 / 401 `ACCOUNT_INACTIVE`) et l'app réagit selon le tableau ; après rechargement du profil, le rôle affiché change.

### Incohérences API à connaître

- Deux formes de succès : routes `/auth/*` (login, select-tenant, refresh, change-password, register, profile) en JSON brut ; toutes les autres sous `data`.
- Les erreurs du filtre d'authentification n'ont que `domainCode` (pas `error`), et certaines (`ACCOUNT_SUSPENDED`, `MEMBERSHIP_NOT_FOUND`) n'ont aucun message français côté serveur.
- `VALIDATION_ERROR` (422) renvoie « Validation failed » et des messages de champ en anglais.
- `TOKEN_EXPIRED` est aussi renvoyé par `/auth/select-tenant` (jeton de connexion de 5 min expiré) : ce n'est pas un cas de renouvellement.
- Le jeton porte encore des claims hérités (`role`, `storeId`…) tant que 16.7 n'a pas coupé le drapeau : les ignorer, ils disparaîtront.
