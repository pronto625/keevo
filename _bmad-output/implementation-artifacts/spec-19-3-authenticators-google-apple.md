---
title: '19.3 — Google and Apple sign-in (server-side ID token verification, link by verified email, two-step login)'
type: 'feature'
created: '2026-11-04'
status: 'done'
baseline_revision: '73805c48b85ba72c5bcdf0be86012ef233ce318c'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-19-2-authenticator-otp.md'
warnings: [oversized]
deferred:
  - summary: >-
      Social link rule judged on global users.role, not memberships.
    evidence: |-
      Equivalent until one person holds several roles. Routed to 19.6.
    location: >-
      keevo/backend/src/main/java/com/keevo/identity/auth/application/service/SocialSignInService.java
    severity: medium
  - summary: >-
      Bref social_id/provider not imported into user_authenticators.
    evidence: |-
      Without these rows imported Bref owners fall back to email linking. Routed to 24.7.
    severity: medium
  - summary: >-
      No nonce, no link/unlink endpoints, no Sign in with Apple revocation on account deletion.
    evidence: |-
      Replay of a captured ID token until exp; 409 has no recovery path; App Store requires revocation. Routed to new key 19-3b.
    severity: medium
  - summary: >-
      google-/apple- username prefixes not reserved.
    evidence: |-
      No username choice exists yet. Routed to 19.4.
    severity: low
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

### 2026-11-04 — Review pass
- verdicts: 31 findings — high 0, medium 8, low 22, false 1, maybe-false 0 (grouped rows list every reviewer finding they cover)
- findings:
  - `[low]` `[reject]` (patterns 1) known-subject path does 2 round trips — both on unique indexes; a JOIN finder adds a port method for one saved query.
  - `[low]` `[patch]` (patterns 2, edge 2, blind 8) `addEmailIfMissing` check-then-save can hit the unique email index (500) — one conditional UPDATE … WHERE NOT EXISTS lower(email).
  - `[low]` `[reject]` (patterns 3, blind 7) cache expiry blocks sign-ins behind one JWKS fetch — about once an hour per provider, bounded by timeouts.
  - `[low]` `[patch]` (patterns 4, edge 8) duplicate verifier for a provider silently overrides — putIfAbsent + IllegalStateException.
  - `[medium]` `[patch]` (intent a) link rule uses global `users.role`, intent says « not only a member » (memberships) — equal today (19.2 refuses non-OWNER company creation); routed to 19.6 for the multi-role case.
  - `[low]` `[reject]` (intent b) pre-19.2 owners with an unverified email get 409 — Bref's rule (account email must be proven); V1 owners register by phone.
  - `[low]` `[reject]` (intent c) real audiences not in the diff — deployment configuration (deploy note: Keevo + com.brefpoint.app, Google Android/iOS/web ids).
  - `[low]` `[reject]` (intent d) never exercised against real Google/Apple JWKS — end-of-epic device test; verifier unit-tested with local RSA keys.
  - `[false]` `[reject]` (intent e) 409 vs Bref 422 for email conflict — Keevo's existing `EMAIL_ALREADY_EXISTS` is 409; one error code family.
  - `[medium]` `[patch]` (intent f) Bref `social_id` not imported into `user_authenticators` — import path belongs to 24.7 (routed).
  - `[low]` `[patch]` (edge 1) `sub` > 255 chars → insert fails (500) — refused as SOCIAL_TOKEN_INVALID.
  - `[low]` `[reject]` (edge 3, blind 3, vg other) locked/inactive account not refused — lockout protects the password factor (same as 19.2 OTP); membership activity checked at select-tenant.
  - `[medium]` `[patch]` (edge 4) on JWKS failure previous keys trusted indefinitely — kept at most 24 h past expiry; empty key sets ignored.
  - `[medium]` `[patch]` (edge 5, blind 1 azp part) Google multi-aud token with foreign `azp` accepted — `azp` must be a configured client id when `aud` has several values.
  - `[low]` `[patch]` (edge 6) truncation can leave a lone surrogate — back off one char.
  - `[low]` `[reject]` (edge 7) ip-max ≤ 0 → everything 429 — operator configuration, default 30.
  - `[low]` `[patch]` (vg 1) Apple fallback to Bref's single `APPLE_BUNDLE_ID` untested — SocialAuthConfig tests added.
  - `[low]` `[patch]` (vg 2) « previous keys kept » on failed refresh unpinned — jwksDownAfterCachedKeys test.
  - `[low]` `[patch]` (vg 3, blind 13 alg part) missing exp / non-RS256 / alg none checks untested — tests added.
  - `[low]` `[reject]` (vg 4) TTL clamp untested — only extra fetches on unusual max-age; no wrong result.
  - `[medium]` `[patch]` (blind 1 nonce part) no nonce, a captured ID token can be replayed until exp — needs the app to send a nonce: routed to new key 19-3b (with 23.1).
  - `[low]` `[reject]` (blind 2) `email_verified` trusted for non-gmail domains — Bref parity; linking also requires the Keevo-side email to be proven.
  - `[low]` `[reject]` (blind 4) no audit event / notice on link — log only, as Bref; account security notices are not in scope.
  - `[medium]` `[patch]` (blind 5) no link/unlink endpoints, no Apple token revocation on account deletion (App Store) — routed to 19-3b.
  - `[medium]` `[patch]` (blind 6) identity injects messaging's `channelRestTemplate` — identity owns its RestTemplate in SocialAuthConfig.
  - `[low]` `[reject]` (blind 9) transient 409 USER_ALREADY_EXISTS on concurrent first logins — rare, retry succeeds.
  - `[low]` `[reject]` (blind 10) createdAt uses Instant.now() — no behaviour depends on it.
  - `[low]` `[patch]` (blind 11) technical usernames can be squatted — no flow lets users pick a username yet; reserved prefixes routed to 19.4.
  - `[medium]` `[patch]` (blind 12) per-IP limit counts successful sign-ins (CGNAT) — only refused tokens count.
  - `[low]` `[reject]` (blind 13 rest) JWKS non-2xx/empty body, limiter reset, nbf/iat tests — RestTemplate throws on non-2xx; jjwt enforces nbf; low value.
  - `[low]` `[reject]` (blind 14) iat bound, JWKS size, last_used_at — provider tokens are short-lived; extra columns not needed now.

## Design Notes

**Patterns.** Strategy (`SocialTokenVerifier` per provider, one JWKS-backed implementation parameterised by issuer/JWKS URL/audiences); cache-aside JWKS with TTL from `Cache-Control` and a throttled refresh on unknown `kid` (the only invalidation needed: providers rotate keys by adding a new `kid`); Façade use case for resolution, reusing 19.2's `insertIfAbsent` idempotent insert. A separate authenticator table (not columns on `users`) lets one person hold Google and Apple at once, which Bref's single `social_id` column could not.

**Complexity (FUS-NFR11).** Per sign-in: 0 network calls when the JWKS is cached (1 otherwise), 1 lookup on the `(provider, subject)` unique index, at most 1 `lower(email)` lookup (unique index), at most 1 user insert + 1 authenticator insert (+1 re-read on conflict), 1 memberships JOIN. No loops over rows; memory bounded (two small key sets, bounded Caffeine).

## Verification

**Commands:**
- `cd keevo/backend && mvn -o -q test -Dtest='*Social*Test,*Authenticator*Test,AuthControllerTest'` -- expected: green.
- Full suite package by package (scratchpad `run-pkgs-wait.sh`) -- expected: 0 failures.

**Manual checks:**
- Local backend: `POST /api/v1/auth/social/google` with a garbage token → 401 `SOCIAL_TOKEN_INVALID`; with no audiences configured → 401; other auth endpoints unchanged.

## Auto Run Result

Status: done

**Summary.** `POST /api/v1/auth/social/google` and `/social/apple` verify ID tokens locally (RS256 against the provider JWKS, cached by `max-age`, throttled refetch on unknown `kid`, previous keys kept at most 24 h on outage; issuer, audience among `GOOGLE_CLIENT_IDS` / `APPLE_BUNDLE_IDS` (fallback `APPLE_BUNDLE_ID`), `azp` when several audiences, expiry with 60 s skew, `sub` ≤ 255), then resolve the account by `(provider, subject)` in `public.user_authenticators` (V48), else link by verified email to an OWNER whose email is proven, else create an OWNER account without company (hidden technical username when no verified email). Answer = `/auth/login` step 1. SUPER_ADMIN refused; refused tokens limited to 30 per IP per hour per provider.

**Files.** New: V48; `SocialProvider`, `SocialIdentity`, ports `SocialTokenVerifier`, `UserAuthenticatorRepository`, `SocialSignInUseCase`/`Command`; `JdbcUserAuthenticatorRepository`; `adapter/out/social/{JwksClient, RestTemplateJwksClient, JwksSocialTokenVerifier, SocialAuthConfig}` (own RestTemplate); `SocialSignInService`, `SocialSignInRateLimiter`; DTOs. Changed: `User.newSocialAccount`, `UserRepository.setEmailIfAbsent`, `AuthController`, `SOCIAL_TOKEN_INVALID` 401, `SecurityConfig`, `application.yml`, `.env.example`, `endpoints.md`, `TokenClaimsGuardTest` allowlist.

**Review.** 31 entries (5 layers): 0 high, 8 medium, 22 low, 1 false. Patched: 4 medium in code (`azp`, bounded stale keys, own RestTemplate, failure-only IP limit) and 8 low (conditional email update, sub length, duplicate verifier, surrogate cut, tests for Apple fallback / stale keys / exp / algorithms). Routed: 4 medium → 19.6 (memberships rule), 24.7 (import Bref social ids), new key 19-3b (nonce, link/unlink endpoints, Apple revocation on deletion); 1 low → 19.4 (reserved prefixes). Rejected: 14, reasons in the triage log.

**Follow-up review: recommended (true)** — 4 medium code patches; unverified risk: real Google/Apple JWKS and tokens (Apple `email_verified` string form, Google issuer variants) only exercised with locally signed tokens.

**Verification.** Targeted classes green (52 tests after patches). Full suite package by package: 5 411 tests, 0 failures. Live `keevo_dev`: garbage Google token 401 `SOCIAL_TOKEN_INVALID`; Apple without audiences 401; empty body 422; password login unchanged; V48 indexes present.

**Deploy note.** Set `GOOGLE_CLIENT_IDS` (Android, iOS, web client ids of both apps) and `APPLE_BUNDLE_IDS` (Keevo bundle `com.keevo.keevo` and `com.brefpoint.app`); empty = provider disabled (401).

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit de la story 19.3, voir `git log --grep "feat(19-3)"`). Conventions : spec-16-11 ; synchro : spec-16-10.

**Ce qui est fait côté API :** connexion Google et Apple vérifiée par le serveur ; compte retrouvé par son identité Google/Apple, ou lié à un compte patron par email vérifié, ou créé sans entreprise.

**À utiliser :**
1. `POST /api/v1/auth/social/google` body `{"idToken":"…","firstName"?,"lastName"?}` (jeton de Google Sign-In, client id de l'app configuré côté serveur).
2. `POST /api/v1/auth/social/apple` body `{"identityToken":"…","firstName"?,"lastName"?}` (prénom/nom : Apple ne les donne qu'au premier consentement, les envoyer s'ils sont présents).
3. Réponse `200` identique à `/auth/login` : `{"loginToken","memberships":[…]}` → une entreprise : `select-tenant` ; plusieurs : sélecteur ; aucune : création d'entreprise via `POST /api/v1/auth/create-company` (19.2).
4. Erreurs : 401 `SOCIAL_TOKEN_INVALID` « Connexion Google ou Apple refusée. Réessayez » ; 409 `EMAIL_ALREADY_EXISTS` « Cette adresse e-mail est déjà utilisée » (email d'un membre ou d'un compte dont l'email n'est pas prouvé : proposer la connexion par code) ; 429 `RATE_LIMIT_EXCEEDED` « Trop de requêtes, veuillez patienter » ; 422 corps invalide.

Code backend : `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/in/rest/AuthController.java`, `application/service/SocialSignInService.java`, `adapter/out/social/JwksSocialTokenVerifier.java`.

**À implémenter côté mobile :**
1. Boutons « Continuer avec Google » / « Continuer avec Apple » (Apple sur iOS) de l'onboarding Bref, envoyant le jeton brut au backend (jamais décodé côté app pour décider).
2. Suite selon `memberships` comme 19.2 ; afficher le message `error` de l'API tel quel.
3. Sur 409, proposer « Recevoir un code » (19.2) avec l'email.
4. Hors ligne : boutons désactivés, « Connexion Internet requise ».

**Implémentation de référence :** écrans de connexion sociale de `AI/bref`.

**Critères d'acceptation mobile / scénarios de test :**
- Given un nouveau compte Google, When il se connecte, Then il arrive sur la création d'entreprise puis l'accueil.
- Given un compte créé par Google, When il se connecte avec Apple (même email vérifié), Then il retrouve la même entreprise.
- Given le serveur sans `GOOGLE_CLIENT_IDS`, Then « Connexion Google ou Apple refusée. Réessayez ».
- Mise en place : backend avec `GOOGLE_CLIENT_IDS=<client id de l'app>` et `APPLE_BUNDLE_IDS=com.brefpoint.app`.

**Clé de suivi mobile :** `m19-3-authenticators-google-apple`.

