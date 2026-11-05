---
title: '19.10 — Minimum app versions (check endpoint, super-admin edit) and V1 refusal for MANAGER/VIEWER'
type: 'feature'
created: '2026-11-05'
status: 'done'
baseline_revision: '2f1813fafad2b742b375a50a641474bd1768860c'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
warnings: []
deferred:
  - summary: >-
      Retiring Flutter V1 for OWNER/EMPLOYEE cannot be driven by app_versions because V1 never calls the check endpoint.
    evidence: |-
      V1 2.0.1 sends no version header and has no startup version check (tag v2.0.1). Routed to 16-7 (go-live): decide whether to widen the legacy-client guard to every role at the retirement date.
    location: >-
      keevo/backend/src/main/java/com/keevo/identity/auth/domain/model/LegacyClients.java
    severity: medium
---

<intent-contract>

## Intent

**Problem:** The super-admin cannot force or suggest an app update (needed to retire Flutter V1 and the old Bref app, FUS-FR31). Separately (routed from 19.5), a MANAGER/VIEWER using Flutter V1 2.0.1 sees a seller UI and a VIEWER loses offline sales rejected at sync — and V1 sends no version information, so it cannot be version-checked.

**Approach:** Add `public.app_versions` (per app identity and platform: min/latest version, store URL, message), an unauthenticated `GET /api/v1/app-version/check` answering both the Keevo shape and the shape the Bref RN app already in stores reads, and super-admin read/edit endpoints. New apps declare themselves with an `X-App-Version` header; an auth request without it is a legacy V1 client, and MANAGER/VIEWER memberships are refused to legacy clients (hidden from the login list, refused at select-tenant and refresh).

## Boundaries & Constraints

**Always:**
- V51 public migration: `public.app_versions(app_identity VARCHAR(30) kebab CHECK, platform VARCHAR(10) CHECK IN ('ios','android','web'), min_version VARCHAR(20) NULL, latest_version VARCHAR(20) NULL, store_url VARCHAR(500) NULL, message VARCHAR(500) NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by UUID NULL, PRIMARY KEY(app_identity, platform))`. No seed rows (no row ⇒ nothing required).
- Version parsing: one shared helper `AppVersion` — `MAJOR[.MINOR[.PATCH]]` with optional `+build` / `-pre` suffix ignored, missing parts = 0, compared numerically; anything else invalid.
- `GET /api/v1/app-version/check?identity=&platform=&version=`: public (`SecurityConfig.PUBLIC_PATHS`, `@SecurityRequirements`, `@EntitlementExempt`), per-IP limiter (120 / minute, Caffeine, like `LoginRateLimiter`) → 429 `RATE_LIMIT_EXCEEDED`. `identity` absent/blank ⇒ `bref` (the Bref RN app in stores sends none); `platform` case-insensitive `ios|android|web`; `version` required. Response body is RAW JSON (no `ApiResponseWrapper`, like `WebPushController`): `{updateRequired, updateAvailable, storeUrl, message, latestVersion, isForced, hasUpdate, changelog}` where `updateRequired = isForced = version < min_version`, `updateAvailable = hasUpdate = version < latest_version` (or updateRequired), `latestVersion = latest_version ?? version`, `changelog = message`. No row ⇒ all false, `latestVersion` = sent version, `storeUrl`/`message` null. One PK read per call.
- Super-admin (`/api/v1/admin/app-versions`, `CurrentActor.requireSuperAdmin()`, `@EntitlementExempt("super-admin")`, `ApiResponseWrapper`): `GET` lists all rows; `PUT /{identity}/{platform}` body `{minVersion, latestVersion, storeUrl, message}` (all keys required, null allowed, unknown keys refused) upserts → 201 created / 200 replaced; `DELETE /{identity}/{platform}` → 204 (404 `APP_VERSION_NOT_FOUND` if absent). Validation → 422 `APP_VERSION_INVALID` with French reason: identity not kebab (≤ 30), platform unknown, version unparsable, `minVersion > latestVersion`, `storeUrl` not `https://` or > 500, message > 500. Audited as `log.info("[ADMIN AUDIT] App version changed: actor=… identity=… platform=… before=… after=…")` (codebase admin pattern) plus `updated_by/updated_at`.
- Legacy-client guard: a request to `/auth/login`, `/auth/otp/verify`, `/auth/social/*`, `/auth/select-tenant`, `/auth/refresh` WITHOUT a non-blank `X-App-Version` header is a legacy client. For a legacy client:
  - login-type responses (memberships list) omit memberships whose role is MANAGER or VIEWER (V1 then shows its own « Votre accès a été désactivé. Contactez votre employeur. » when nothing is left, as for a user with no membership);
  - select-tenant and refresh of a MANAGER/VIEWER membership → 403 `APP_UPDATE_REQUIRED` « Ce rôle nécessite la dernière version de l'application : mettez-la à jour » (V1 ends the session on a 403 refresh).
  - OWNER and EMPLOYEE are never affected; switch `keevo.app-version.legacy-role-guard.enabled` (default `true`).
- New error codes: `APP_UPDATE_REQUIRED` (403), `APP_VERSION_INVALID` (422), `APP_VERSION_NOT_FOUND` (404) in `ErrorCode`/`ErrorMessages`/`GlobalExceptionHandler`.
- Additive only for V1: no change to any response shape for OWNER/EMPLOYEE or for clients sending the header.

**Never:**
- No server-side refusal based on `min_version` for headered clients in this story (the app enforces `updateRequired`); no change to sync push rules; no push notification of updates; no change to the Flutter app (`keevo/app`); no hard-coded store URLs or bundle ids in Java.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Forced | row bref/android min 2.1.0 latest 2.2.0; `?platform=android&version=2.0.0` | `updateRequired:true,isForced:true,updateAvailable:true,hasUpdate:true,latestVersion:"2.2.0"` | — |
| Optional | same row; `version=2.1.5` | required false, available true | — |
| Up to date | same row; `version=2.2.0+80` | all false | — |
| No row | `?identity=keevo&platform=ios&version=1.0` | all false, `latestVersion:"1.0"`, nulls | — |
| Bad input | `platform=windows` or `version=abc` or version missing | — | 422 `VALIDATION_ERROR` French |
| Admin PUT | super-admin `{minVersion:"2.1.0",latestVersion:"2.0.0",…}` | — | 422 `APP_VERSION_INVALID` « La version minimale dépasse la dernière version » |
| Admin non-SA | OWNER on `/admin/app-versions` | — | 403 |
| V1 viewer login | no header, VIEWER-only user, `/auth/login` | 200 with `memberships: []` | — |
| V1 mixed login | no header, OWNER of A + VIEWER of B | memberships list A only | — |
| V1 refresh | no header, refresh of a VIEWER session | — | 403 `APP_UPDATE_REQUIRED` |
| New app | header `X-App-Version: 2.0.0`, VIEWER | unchanged behaviour | — |
| Guard off | property false, no header, VIEWER | unchanged behaviour | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/SecurityConfig.java:57-82` -- `PUBLIC_PATHS` (add `/api/v1/app-version/check`; `JwtAuthFilter` reads it).
- `.../web/push/.../WebPushController.java:27-41` -- raw-body public endpoint precedent (`@EntitlementExempt`, plain `Map`).
- `.../identity/auth/application/service/LoginRateLimiter.java`, `AuthController.clientIp` (~L574) -- IP limiter + client IP to copy.
- `.../admin/module/adapter/in/rest/BundleAdminController.java:43-97`, `.../shared/module/application/BundleAdminService.java:80-108` -- super-admin CRUD (Map body, unknown keys refused, `[ADMIN AUDIT]`).
- `.../shared/module/application/ModuleCatalogSeeder.java:39-40,122-127` -- existing module-only semver (do not reuse; new `AppVersion` helper in `shared`).
- `.../identity/auth/adapter/in/rest/AuthController.java:218-296,601-607` -- login, select-tenant, refresh handlers; `toLoginSessionResponse` builds memberships (V1 label + real role).
- `.../identity/auth/application/service/AuthenticationService.java:91-145` (memberships L138), `SelectTenantService.java:56-80` (role L73), `RefreshTokenService.java:79-110` (role L104), OTP verify / social sign-in services returning memberships (19.2/19.3).
- `.../identity/api/Role.java:44-65` -- `isStoreMember`, `v1Label`.
- `.../shared/domain/exception/ErrorCode.java`, `ErrorMessages.java`, `GlobalExceptionHandler.java` -- new codes.
- V1 behaviour (tag `v2.0.1`): `remote_auth_datasource.dart:224-252`, `auth_page.dart:155-185` (switch on `domainCode`, ignores `error`), `login_usecase.dart:33-75` (empty list ⇒ « Votre accès a été désactivé… »), `session_refresher.dart:57-59` (400/401/403 refresh ⇒ session ends).
- Bref RN client: `/home/toor/Project/FreeLance/AI/bref/hooks/use-version-check.ts:39-55` (no identity, raw `{latestVersion,isForced,hasUpdate,changelog}`).
- Tests touching public paths / endpoints: `JwtAuthFilterTest:128`, `EndpointEntitlementCoverageTest` (regenerate `docs/entitlements/endpoints.md` with `-Dkeevo.entitlements.regenerate=true`), `ViewerNeverWritesFullContextTest:85-100`.

## Tasks & Acceptance

**Execution:**
- `db/migration/V51__app_versions.sql` -- table + CHECKs, idempotent, `SET LOCAL lock_timeout='10s'`.
- `shared/...AppVersion` -- parser/comparator + unit tests.
- app-version feature (package under `identity` or `shared`, hexagonal: port + JDBC adapter + service) -- check service, admin service, public controller (raw body, limiter), admin controller.
- Legacy-client guard -- read the header in the auth controllers (adapter concern), pass a `legacyClient` flag into the login/select/refresh/OTP/social use cases (or filter in the controller for lists); refusal in select-tenant and refresh services before tokens are issued.
- Errors, `SecurityConfig`, config property, `docs/entitlements/endpoints.md` regenerated.
- Tests: matrix rows (controller + Postgres full context), admin CRUD validation, legacy guard on login/OTP/social/select/refresh (V1 viewer, mixed memberships, header present, guard off, OWNER/EMPLOYEE untouched).

**Acceptance Criteria:**
- Given rows edited by the super-admin, when the Bref RN app in stores calls `/api/v1/app-version/check?platform=android&version=2.0.0` (no identity), then it reads `isForced`/`hasUpdate`/`latestVersion` for identity `bref`.
- Given a VIEWER signed in on V1 before deploy, when the app refreshes its token, then the session ends and the next V1 login lists no company for that role.

## Spec Change Log

## Review Triage Log

### 2026-11-05 — Review pass
- verdicts: 34 findings — high 0, medium 5, low 24, false 5, maybe-false 0 (intent-alignment observations included)
- findings:
  - Patterns/complexity (FUS-NFR11):
    - `[low]` `[patch]` admin delete = SELECT then DELETE, return value ignored (two concurrent deletes both 204 + two audit lines) — single `DELETE … RETURNING` giving the audited before-image.
    - `[low]` `[reject]` admin save reads « before » without `FOR UPDATE` — only the audit line can be off when two super-admins save the same row at the same instant; upsert itself atomic.
    - `[medium]` `[patch]` 120 checks/min per IP on the public check is low behind Cameroonian carrier NAT (forced-update signal lost on 429) — default raised to 1 200/min; 429 contract stated in the mobile section (keep the last answer).
    - `[low]` `[reject]` stored versions re-parsed on every check — O(1) on ≤ 20 chars.
  - Intent alignment (descriptive):
    - `[false]` `[reject]` no dashboard page — API-only project rule; the dashboard section carries the page.
    - `[low]` `[reject]` « avec audit » = `[ADMIN AUDIT]` log + `updated_by/updated_at` — the codebase's admin audit pattern (bundles, plan profiles).
    - `[false]` `[reject]` legacy guard is header-driven, not table-driven — the routed item offers « ou refuser leur connexion depuis V1 »; V1 sends no version, so the table cannot apply to it.
    - `[low]` `[reject]` clients without the header are treated as V1 — client contract (send `X-App-Version` on auth calls) stated in the mobile and dashboard sections; the super-admin dashboard signs in SUPER_ADMIN only (never filtered).
    - `[false]` `[reject]` V1 never shows the `APP_UPDATE_REQUIRED` text — V1 switches on `domainCode`; hiding the memberships makes it show its own French text, the clearest V1 can display.
    - `[low]` `[reject]` an already-open V1 MANAGER/VIEWER session can still queue offline sales until its next refresh — access tokens live 30 min; VIEWER sync refusal is the pre-existing 19.5 rule.
    - `[medium]` `[defer]` retiring Flutter V1 for OWNER/EMPLOYEE is impossible through the table (V1 never calls `/check`) — pre-existing V1 limitation; decide at go-live (e.g. widen the legacy guard to every role) → routed to 16-7.
  - Blind hunter:
    - `[medium]` `[patch]` row with min set and latest null: `latestVersion` = sent version, the Bref RN client checks `latestVersion` first and never shows the forced screen — `latestVersion` = parsed latest, else the minimum when an update is required, else the sent version.
    - `[low]` `[reject]` dashboard / other clients without the header — same as the intent row above.
    - `[false]` `[reject]` header value not compared to `min_version` — spec « Never »: no server-side min enforcement for headered clients in this story.
    - `[low]` `[reject]` pre-deploy access tokens and change-password issue tokens without the guard — the next refresh is refused (30-min access tokens); not worth threading the flag through change-password.
    - `[low]` `[patch]` `-pre` suffix compares equal to the release — admin input refuses `-`/`+` suffixes (sent versions keep ignoring them).
    - `[medium]` `[patch]` per-IP limit vs CGNAT — grouped with the patterns entry; proxy assumption holds (prod: Traefik direct, last `X-Forwarded-For` = client, checked 05/11).
    - `[low]` `[patch]` no caching on the public check — `Cache-Control: public, max-age=60` on the 200 answer.
    - `[low]` `[patch]` missing tests — controller-level limiter/429 test (verification-gap entry), `LegacyClients` null/unknown role; `ClientIps` and `xmax = 0` already covered (`AuthControllerClientIpTest`, `adminCrud`).
    - `[low]` `[patch]` unparsable stored latest returned raw — grouped with the first blind-hunter entry (only a parsed latest is returned).
    - `[low]` `[patch]` `MAX_LENGTH` Javadoc confusing — say 50 for sent versions, 20 for stored ones. (Delete race grouped above; save lock and `ErrorMessages` template bypass rejected: audit-only race; every `userFacing` code works this way.)
  - Edge case hunter:
    - `[low]` `[reject]` web/other clients without header — same as the intent row.
    - `[low]` `[reject]` OTP/social list filtered to empty reads as « no company » — V1 has neither OTP nor social sign-in; headered apps are never filtered; a member may create a company anyway (19.6).
    - `[low]` `[patch]` concurrent deletes — grouped with the patterns delete entry.
    - `[low]` `[reject]` concurrent first saves log « before=none » — same as the patterns save-lock row.
    - `[low]` `[patch]` garbage stored latest returned — grouped with the blind-hunter latest entry.
    - `[low]` `[patch]` uppercase `identity` refused while platform is case-insensitive — lower-case the identity.
    - `[low]` `[patch]` `HTTPS://` store URL refused — case-insensitive scheme check.
    - `[low]` `[reject]` limiter configured ≤ 0 answers 429 to everyone — operator misconfiguration only.
    - `[low]` `[reject]` test cleanup deletes every bref/keevo row — the table has no seed rows; tests own it.
    - `[low]` `[reject]` change-password bypass — same as the blind-hunter sessions row.
    - `[false]` `[reject]` refresh refused but tokens not revoked — V1 ends the session on a 403 refresh (`session_refresher.dart:57-59`), matching the AC; the access token expires within 30 min.
  - Verification gap:
    - `[medium]` `[patch]` limiter call in the controller untested (IP passed, 429 body) — `@WebMvcTest` controller test: `acquire("203.0.113.9")` for `X-Forwarded-For: 1.1.1.1, 203.0.113.9`, 429 `RATE_LIMIT_EXCEEDED` when the limiter throws.
    - `[low]` `[patch]` admin PUT non-text value untested — one 422 `APP_VERSION_INVALID` assertion for `"minVersion": 2`.

## Design Notes

**Pattern.** Table-driven policy (Specification over `AppVersion` comparisons) behind a port; public read is a stateless query; admin edit is a Command with validation. Legacy detection is an adapter concern (HTTP header) turned into a plain flag for the use cases — the domain never reads headers. Why a header and not a version check for V1: V1 2.0.1 sends no version, platform or client header and its login page ignores the server `error` text (switches on `domainCode`), so hiding the memberships is the only way it shows a sensible French message.

**Complexity.** Check: one PK read, O(1). Admin list: one query over a table of ~6 rows. Guard: in-memory filter of the memberships already loaded; no extra query on select/refresh (role already read).

## Verification

**Commands:**
- `cd keevo/backend && mvn -q -o test -Dtest='com/keevo/identity/**/*Test*' -Dsurefire.failIfNoSpecifiedTests=false` -- green; then shared, admin, architecture packages one at a time (full suite package by package before finalizing).

**Manual checks:**
- Local backend (WhatsApp noop, FCM/Expo off): curl check without identity / with keevo; super-admin PUT then check; V1-style login (no header) of a VIEWER member returns `memberships: []`, with header returns the membership.

## Auto Run Result

Status: done

**Summary.** `public.app_versions` (V51) holds, per app identity and platform, the minimum and latest versions, store URL and message. The public `GET /api/v1/app-version/check` (per-IP limit 1 200/min, `Cache-Control: public, max-age=60`) answers raw JSON with the Keevo fields and the Bref RN aliases (`isForced`, `hasUpdate`, `latestVersion`, `changelog`); a missing identity means `bref`, identity and platform are case-insensitive, `latestVersion` falls back to the minimum when an update is required. Super-admin `GET/PUT/DELETE /api/v1/admin/app-versions` (validated, release versions only, `[ADMIN AUDIT]` + `updated_by/updated_at`). Legacy-client guard: an auth request without `X-App-Version` is a V1 client; its login lists (login, OTP, social) drop MANAGER/VIEWER memberships and select-tenant/refresh of such a membership answer 403 `APP_UPDATE_REQUIRED`; switch `keevo.app-version.legacy-role-guard.enabled`.

**Files.** New: `V51__app_versions.sql`, `shared/domain/AppVersion`, `shared/infrastructure/web/ClientIps`, `identity/appversion/**` (policy, ports, JDBC store, check and admin services, rate limiter, public controller), `identity/api/AppVersionAdminPort`, `admin/appversion/.../AppVersionAdminController`, `identity/auth/domain/model/LegacyClients`; tests (`AppVersionTest`, `AppVersionCheckServiceTest`, `AppVersionAdminServiceTest`, `AppVersionCheckRateLimiterTest`, `AppVersionCheckControllerTest`, `LegacyClientsTest`, `AuthControllerLegacyClientTest`, `AppVersionFullContextTest`). Changed: `AuthController`, `SelectTenantService`/`Command`, `RefreshTokenService`/`UseCase`, `SecurityConfig` (public path), error codes, `application.yml`, `docs/entitlements/endpoints.md`, two existing tests sending the header.

**Review.** 34 findings (5 layers): 0 high, 5 medium, 24 low, 5 false. Patched: 3 medium entries (Bref forced screen with no latest version; per-IP limit raised to 1 200/min; controller-level limiter test) and 9 low (single-statement delete, release-only admin versions, `HTTPS://`, identity case, cache header, unparsable latest never returned, Javadoc, two tests). Deferred: 1 medium → 16-7 (retiring Flutter V1 for every role; V1 never calls the check). Rejected: 21 (reasons in the triage log).

**Follow-up review: recommended (true)** — 3 medium patches; unverified risk: the header contract — every new client (RN app, any web client used by a MANAGER/VIEWER) must send `X-App-Version` on auth calls or those members are hidden/refused; nothing in this repo proves the RN app sends it yet.

**Verification.** Full suite package by package: 5 876 tests, 0 failures. Live `keevo_dev` (WhatsApp noop, FCM/Expo off): check with no row → all false, `Cache-Control: max-age=60, public`; row bref/android min 2.1.0, latest null → `isForced:true, latestVersion:"2.1.0"`, store URL and message; `identity=Bref&platform=ANDROID&version=2.1.0+9` → up to date; `platform=windows` → 422 French; OWNER on `/admin/app-versions` → 403. VIEWER member created by the owner: login without header → `memberships: []`; with header → membership listed (`memberRole: VIEWER`); select-tenant without header → 403 `APP_UPDATE_REQUIRED`, with header → tokens; refresh without header → 403; OWNER login without header unchanged. Test row removed afterwards. Super-admin endpoints exercised by `AppVersionFullContextTest` only (no super-admin login used live).

**Residual risks.** When no latest version is recorded and no update is required, `latestVersion` echoes the sent text including a `+build` suffix (the Bref client then reads it as up to date — harmless). Rate-limit counters are per instance.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit `__COMMIT__`), branche `refonte/modulaire` / `develop`. Conventions générales : spec-16-11 ; protocole de synchro : spec-16-10.

**Ce qui est fait côté API :** le super-admin fixe, par application et par plateforme, la version minimale et la dernière version ; l'app demande au démarrage si elle doit se mettre à jour. Les comptes gérant et lecteur sont refusés aux applications qui ne s'identifient pas (ancienne app Keevo V1).

**À utiliser :**
1. `GET /api/v1/app-version/check?identity=bref&platform=ios|android|web&version=<version de l'app>` (sans connexion). Réponse JSON brute (pas d'enveloppe `data`) : `{updateRequired, updateAvailable, storeUrl, message, latestVersion, isForced, hasUpdate, changelog}`. Code : `keevo/backend/src/main/java/com/keevo/identity/appversion/adapter/in/rest/AppVersionCheckController.java`.
   - 422 `VALIDATION_ERROR` (plateforme ou version invalide) ; 429 `RATE_LIMIT_EXCEEDED` (trop d'appels depuis la même adresse) : dans les deux cas garder la dernière réponse connue et réessayer au prochain démarrage.
2. **En-tête obligatoire** `X-App-Version: <version>` sur `/auth/login`, `/auth/otp/verify`, `/auth/social/*`, `/auth/select-tenant` et `/auth/refresh` (le plus simple : sur toutes les requêtes). Sans lui, l'app est traitée comme l'ancienne V1 : les entreprises où la personne est gérant ou lecteur disparaissent de la liste et `select-tenant` / `refresh` répondent 403 `APP_UPDATE_REQUIRED` « Ce rôle nécessite la dernière version de l'application : mettez-la à jour ».

**À implémenter côté mobile :**
1. Au démarrage (et au retour au premier plan après longtemps) : appeler `check`. `updateRequired` → écran bloquant « Mise à jour requise » avec `message` et un bouton vers `storeUrl` (sinon le lien du store connu de l'app) ; `updateAvailable` seul → bandeau discret « Une nouvelle version est disponible ».
2. Envoyer `X-App-Version` (version de l'app, ex. `2.1.0`) sur toutes les requêtes ; `identity=bref` pour l'app Bref Point.
3. Sur 403 `APP_UPDATE_REQUIRED` : afficher le message de la réponse et proposer la mise à jour.
4. Hors ligne : ne rien bloquer si `check` n'a pas répondu ; réutiliser la dernière réponse.

**Critères d'acceptation mobile :**
- Given une version minimale 2.1.0 pour bref/android, when une app 2.0.0 démarre, then l'écran « Mise à jour requise » s'affiche avec le lien du store.
- Given un lecteur, when il se connecte avec l'en-tête `X-App-Version`, then il voit son entreprise ; sans l'en-tête, then elle n'apparaît pas.
- Mise en place : `curl "$API/api/v1/app-version/check?platform=android&version=2.0.0"`.

**Clé de suivi mobile :** `m19-10-versions-minimales-app`.

## Dashboard super-admin (autre dépôt)

**Statut :** API livrée (commit `__COMMIT__`).

**Ce qui est fait côté API :** lecture et modification des versions d'app par application (`bref`, `keevo`…) et plateforme (`ios`, `android`, `web`), avec trace dans les journaux et l'auteur de la dernière modification.

**À utiliser :**
1. `GET /api/v1/admin/app-versions` → liste `{identity, platform, minVersion, latestVersion, storeUrl, message, updatedAt}`.
2. `PUT /api/v1/admin/app-versions/{identity}/{platform}` corps `{minVersion, latestVersion, storeUrl, message}` (quatre clés obligatoires, `null` permis, pas d'autre clé) → 201 créée / 200 remplacée. 422 `APP_VERSION_INVALID` avec la raison en français (« La version doit être de la forme 2.1.0 », « La version minimale dépasse la dernière version », lien du store en `https://`, textes trop longs).
3. `DELETE /api/v1/admin/app-versions/{identity}/{platform}` → 204 ; 404 `APP_VERSION_NOT_FOUND`.
4. Si le dashboard sert un jour à des gérants ou lecteurs, il doit envoyer `X-App-Version` à la connexion (sinon ces rôles sont refusés).
Code : `keevo/backend/src/main/java/com/keevo/admin/appversion/adapter/in/rest/AppVersionAdminController.java`.

**À implémenter côté dashboard :**
1. Écran « Versions des applications » : un tableau application × plateforme (Bref Point, Keevo ; iOS, Android, Web) avec version minimale, dernière version, lien du store, message ; édition avec le message d'erreur 422 affiché tel quel ; suppression avec confirmation.

**Critères d'acceptation dashboard :**
- Given une version minimale enregistrée, when l'app appelle `check` avec une version inférieure, then `updateRequired` est vrai.

**Clé de suivi dashboard :** `d19-10-versions-minimales-app`.
