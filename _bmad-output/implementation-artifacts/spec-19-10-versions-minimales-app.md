---
title: '19.10 — Minimum app versions (check endpoint, super-admin edit) and V1 refusal for MANAGER/VIEWER'
type: 'feature'
created: '2026-11-05'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
warnings: []
deferred: []
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

## Design Notes

**Pattern.** Table-driven policy (Specification over `AppVersion` comparisons) behind a port; public read is a stateless query; admin edit is a Command with validation. Legacy detection is an adapter concern (HTTP header) turned into a plain flag for the use cases — the domain never reads headers. Why a header and not a version check for V1: V1 2.0.1 sends no version, platform or client header and its login page ignores the server `error` text (switches on `domainCode`), so hiding the memberships is the only way it shows a sensible French message.

**Complexity.** Check: one PK read, O(1). Admin list: one query over a table of ~6 rows. Guard: in-memory filter of the memberships already loaded; no extra query on select/refresh (role already read).

## Verification

**Commands:**
- `cd keevo/backend && mvn -q -o test -Dtest='com/keevo/identity/**/*Test*' -Dsurefire.failIfNoSpecifiedTests=false` -- green; then shared, admin, architecture packages one at a time (full suite package by package before finalizing).

**Manual checks:**
- Local backend (WhatsApp noop, FCM/Expo off): curl check without identity / with keevo; super-admin PUT then check; V1-style login (no header) of a VIEWER member returns `memberships: []`, with header returns the membership.
