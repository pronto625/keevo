---
title: '19.3 — Google and Apple sign-in (server-side ID token verification, link by verified email, two-step login)'
type: 'feature'
created: '2026-11-04'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-19-2-authenticator-otp.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** Bref owners sign in with Google or Apple; Keevo cannot verify those tokens nor map a social identity to a user, so these accounts cannot sign in to the merged backend.

**Approach:** Port Bref's social login (`api-bref/src/auth/social-auth.service.ts`, `auth.service.ts` `findOrCreateSocialUser`) as `POST /api/v1/auth/social/google` and `POST /api/v1/auth/social/apple`: verify the ID token locally against the provider's JWKS (signature, issuer, audience among the configured ones, expiry), find the account by `(provider, subject)` in a new `public.user_authenticators` table, else link by email under Bref's safety rules, else create an account without company; answer exactly like `/auth/login` and 19.2's verify (`loginToken` + `memberships`; empty → `create-company`).

## Boundaries & Constraints

**Always:**
- Verification (one `SocialTokenVerifier` per provider, Strategy): RS256 signature with the key whose `kid` matches, from the JWKS (`https://www.googleapis.com/oauth2/v3/certs`, `https://appleid.apple.com/auth/keys`), cached in memory per provider with the response's `Cache-Control max-age` (default 1 h); an unknown `kid` forces one refetch at most every 60 s; JWKS fetch with the 19.9 `ProviderHttp` timeouts. `iss` ∈ {`https://accounts.google.com`, `accounts.google.com`} for Google, `https://appleid.apple.com` for Apple; `aud` must be one of the configured audiences (`keevo.auth.social.google.client-ids` ← env `GOOGLE_CLIENT_IDS`, comma list as Bref; `keevo.auth.social.apple.audiences` ← env `APPLE_BUNDLE_IDS`, comma list, falling back to Bref's single `APPLE_BUNDLE_ID`; both Keevo and `com.brefpoint.app` bundles are meant to be listed); `exp` in the future (60 s clock skew), `sub` non-blank. Empty audience list → that provider is refused (Bref: no configuration, no login). Any failure → `SOCIAL_TOKEN_INVALID` (401) « Connexion Google ou Apple refusée. Réessayez ». Google: an `email` with `email_verified` not true → same refusal (Bref). Apple: `email` is optional; `email_verified` (string or boolean) true is required to use it for linking or creation, otherwise the email is ignored.
- Account resolution (one transaction, `@Transactional`):
  1. `user_authenticators` row for `(provider, subject)` → that user. If the token carries a verified email and the user has no email and no other account has it (`findByEmail`, 19.2) → set it with `email_verified_at`.
  2. Else, verified email present → `findByEmail`. Found: link (insert authenticator, set `email_verified_at` if null) only if the user's global role is OWNER **and** `email_verified_at` is not null; otherwise `EMAIL_ALREADY_EXISTS` (409, existing message) — members' and unproven emails are never linked (FUS-FR6, Bref).
  3. Else create an account (role OWNER, no password, no phone, email if verified with `email_verified_at`, first/last names from the body or Google's `given_name`/`family_name`) and its authenticator. An Apple token without email and unknown subject creates an account with neither phone nor email: it is identified by the authenticator — the `users_identifier_present` CHECK (V41) must be satisfied, so the account gets `username = 'apple-' + first 12 hex chars of SHA-256(subject)` (Bref-format username, never shown to the user, unique index; on the rare collision append more hex).
  Concurrent first logins: authenticator insert `ON CONFLICT (provider, subject) DO NOTHING`, then re-read (19.2's pattern); user insert via 19.2's `insertIfAbsent`.
- SUPER_ADMIN accounts are never signed in or linked by a social token (`SOCIAL_TOKEN_INVALID`, like 19.2).
- `public.user_authenticators(id uuid pk, user_id uuid not null references public.users(id) on delete cascade, provider varchar(16) not null check in ('GOOGLE','APPLE'), subject varchar(255) not null, created_at timestamptz not null default now(), unique(provider, subject))` + index on `user_id` — migration `V48__user_authenticators.sql` (idempotent).
- Bodies: Google `{idToken, firstName?, lastName?}`, Apple `{identityToken, firstName?, lastName?}` (names only sent by Apple at first consent). Response `200` = `LoginSessionResponse`. Per-IP limit 30/hour per endpoint (Caffeine like 19.2's limiter) → `RATE_LIMIT_EXCEEDED` (429). Paths public in `SecurityConfig`, Swagger documented, `docs/entitlements/endpoints.md` regenerated, `application.yml` + `.env.example`.
- Logs never contain tokens, emails in clear (masked) or subjects in clear.

**Never:**
- No Google `tokeninfo` network call per login, no new Maven dependency (jjwt 0.12 already present). No password set, no linking to a non-OWNER or unverified-email account, no unlink/list endpoints (account settings later), no change to `/auth/login`, `/auth/register`, `select-tenant`, 19.2 endpoints. No Bref welcome email.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Known subject | authenticator exists | 200 loginToken + memberships | — |
| New subject, verified email of an OWNER with verified email | | authenticator linked, 200 | — |
| Verified email of an EMPLOYEE, or of an OWNER with unverified email | | — | 409 `EMAIL_ALREADY_EXISTS` |
| New subject, new email | | user + authenticator created, `email_verified_at` set, memberships [] | — |
| Apple, no email, known subject | | 200 | — |
| Apple, no email, unknown subject | | user with technical username + authenticator, memberships [] | — |
| Google email_verified false | | — | 401 `SOCIAL_TOKEN_INVALID` |
| Wrong aud / iss / expired / bad signature / unknown kid after refetch | | — | 401 `SOCIAL_TOKEN_INVALID` |
| Audiences not configured | | — | 401 `SOCIAL_TOKEN_INVALID` |
| Known subject, account has no email, token has verified email unused elsewhere | | email set + verified | — |
| SUPER_ADMIN by subject or email | | — | 401 `SOCIAL_TOKEN_INVALID` |
| 31st call from one IP in 1 h | | — | 429 `RATE_LIMIT_EXCEEDED` |

</intent-contract>

## Code Map

- `api-bref/src/auth/social-auth.service.ts`, `auth.service.ts:501-640` (read-only, `/home/toor/Project/FreeLance/AI/api-bref`) -- verification rules and `findOrCreateSocialUser` order.
- `identity/auth/application/service/VerifyOtpService.java` -- 19.2 pattern: find → link/create via `insertIfAbsent` → `LoginSessionResult(loginToken, memberships)`, SUPER_ADMIN refusal.
- `identity/auth/application/service/OtpRateLimiter.java` -- Caffeine per-IP window pattern.
- `identity/auth/domain/model/User.java` (`newOtpAccount`, `withEmail`, `withEmailVerifiedAt`, `withNames`), `domain/port/out/UserRepository.java` (`findByEmail`, `insertIfAbsent`, `lockById`), `adapter/out/persistence/impl/UserRepositoryAdapter.java`.
- `identity/auth/adapter/in/rest/AuthController.java` -- `clientIp`, `toLoginSessionResponse`, 19.2 endpoints as the style to follow.
- `shared/infrastructure/security/JwtTokenProvider.java` -- `generateLoginToken`; jjwt 0.12 usage (`Jwts.parser().keyLocator(...)` / `Jwks.setParser()` for the JWKS).
- `messaging/channel/adapter/out/external/ProviderHttp.java`, `channelRestTemplate` bean -- timeouts.
- `V41__users_multiple_identifiers.sql` -- `users_identifier_present`, `users_username_format` CHECKs, `uq_users_username_lower`.
- `shared/domain/exception/ErrorCode.java`, `shared/infrastructure/web/ErrorMessages.java`, `GlobalExceptionHandler.java`; `SecurityConfig.java:61`; last migration `V47__web_push_subscriptions.sql`.

## Tasks & Acceptance

**Execution:**
- `V48__user_authenticators.sql` -- table per the rules.
- `identity/auth/domain/model/SocialProvider.java`, `SocialIdentity.java` (subject, email, emailVerified, names) -- value types.
- `identity/auth/domain/port/out/SocialTokenVerifier.java` + `adapter/out/social/JwksSocialTokenVerifier.java` (shared JWKS cache/verification) with Google and Apple configurations -- verification per the rules.
- `identity/auth/domain/port/out/UserAuthenticatorRepository.java` + JDBC adapter -- `findUserId(provider, subject)`, `insertIfAbsent(userId, provider, subject)`.
- `identity/auth/application/service/SocialSignInService.java` + `SocialSignInUseCase`/`SocialSignInCommand(provider, token, firstName, lastName, clientIp)` -- resolution per the rules, rate limit.
- `User.java` -- factory for a social account (email or technical username, names, verified date).
- `AuthController.java` + DTOs `GoogleSignInDto`, `AppleSignInDto` -- two endpoints.
- `ErrorCode`/`ErrorMessages`/`GlobalExceptionHandler` -- `SOCIAL_TOKEN_INVALID` 401.
- `SecurityConfig.java`, `application.yml`, `.env.example`, `docs/entitlements/endpoints.md`.
- Tests -- unit: verifier with locally generated RSA keys and a stubbed JWKS fetch (valid, wrong aud, wrong iss, expired, bad signature, unknown kid → one refetch, cache reuse, no audiences, Google email_verified false, Apple email_verified string/boolean); service for every matrix row; controller tests; Postgres test for the authenticator repository (unique conflict, cascade delete) and for the technical-username creation against the V41 CHECKs; full-context test: Google sign-in with a test-signed token (JWKS stubbed) → memberships [] → `create-company` 201.

**Acceptance Criteria:**
- Given an account created by Google, when it signs in again with Apple carrying the same verified email, then the Apple authenticator is linked to the same account (one user, two authenticator rows).
- Given the V1 2.0.1 app, when it uses its existing auth endpoints, then responses are unchanged (existing tests green).
- Given the full suite run package by package, then it is green.

## Spec Change Log

## Review Triage Log

## Design Notes

**Patterns.** Strategy (`SocialTokenVerifier` per provider, one JWKS-backed implementation parameterised by issuer/JWKS URL/audiences); cache-aside JWKS with TTL from `Cache-Control` and a throttled refresh on unknown `kid` (the only invalidation needed: providers rotate keys by adding a new `kid`); Façade use case for resolution, reusing 19.2's `insertIfAbsent` idempotent insert. A separate authenticator table (not columns on `users`) lets one person hold Google and Apple at once, which Bref's single `social_id` column could not.

**Complexity (FUS-NFR11).** Per sign-in: 0 network calls when the JWKS is cached (1 otherwise), 1 lookup on the `(provider, subject)` unique index, at most 1 `lower(email)` lookup (unique index), at most 1 user insert + 1 authenticator insert (+1 re-read on conflict), 1 memberships JOIN. No loops over rows; memory bounded (two small key sets, bounded Caffeine).

## Verification

**Commands:**
- `cd keevo/backend && mvn -o -q test -Dtest='*Social*Test,*Authenticator*Test,AuthControllerTest'` -- expected: green.
- Full suite package by package (scratchpad `run-pkgs-wait.sh`) -- expected: 0 failures.

**Manual checks:**
- Local backend: `POST /api/v1/auth/social/google` with a garbage token → 401 `SOCIAL_TOKEN_INVALID`; with no audiences configured → 401; other auth endpoints unchanged.
