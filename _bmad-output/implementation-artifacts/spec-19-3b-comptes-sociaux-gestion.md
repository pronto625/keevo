---
title: '19.3b — Social accounts: nonce, link/list/unlink Google and Apple, Sign in with Apple token revocation'
type: 'feature'
created: '2026-11-10'
status: 'done'
baseline_revision: 'cf9402ba20aa80e6cbf096de59e3d01b2d71810c'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-19-3-authenticators-google-apple.md'
warnings: [oversized]
deferred:
  - summary: >-
      Accounts without any company cannot be deleted, so their Apple link is never revoked.
    evidence: |-
      The only user deletion is TenantDeletionService (orphans of a deleted tenant); a social account created
      without company has no tenant and no deletion path. App Store requires in-app deletion for every account.
      Routed to 16-6 (account deletion owner).
    location: >-
      keevo/backend/src/main/java/com/keevo/identity/auth/application/service/TenantDeletionService.java
    severity: medium
  - summary: >-
      Replay protection only applies to tokens that carry a nonce until SOCIAL_REQUIRE_NONCE is turned on.
    evidence: |-
      Apps in stores send no nonce; flipping the flag is tied to the RN sign-in screens. Routed to 23-1.
    severity: medium
---

<intent-contract>

## Intent

**Problem:** The 19.3 review routed three gaps: a captured Google/Apple ID token can be replayed until it expires (no nonce); a 409 `EMAIL_ALREADY_EXISTS` at social sign-in has no way out because a signed-in user cannot link (or list, or remove) Google/Apple; and the App Store requires that deleting an account revokes its Sign in with Apple token, which Keevo never does.

**Approach:** (1) Optional `nonce` on the sign-in bodies, checked against the token's `nonce` claim and consumed once. (2) Authenticated `GET/POST/DELETE /api/v1/auth/social/accounts…` to list, link and unlink. (3) The Apple sign-in/link body accepts the `authorizationCode`; when the revocation adapter is enabled it is exchanged for an Apple refresh token stored encrypted on the authenticator; deleting the account (orphan users of a deleted tenant) or unlinking Apple queues that token in a durable outbox drained by a job calling `appleid.apple.com/auth/revoke` through a port whose default adapter is disabled.

## Boundaries & Constraints

**Always:**
- **Nonce (both providers, same rule).** Bodies of `/auth/social/google`, `/auth/social/apple` and the two link endpoints get optional `nonce` (16–128 chars). The app sends `SHA-256(nonce)` hex to Google/Apple and the clear nonce to Keevo. Rules: nonce sent → token `nonce` claim must equal lowercase hex SHA-256 of it (case-insensitive compare), else `SOCIAL_TOKEN_INVALID`; token carries a `nonce` claim but no nonce sent → `SOCIAL_TOKEN_INVALID` (a stripped nonce never bypasses the check); neither → accepted unless `keevo.auth.social.require-nonce` (`SOCIAL_REQUIRE_NONCE`, default `false` for V1/Bref apps in stores) is true. A verified nonce is consumed once: `INSERT … ON CONFLICT DO NOTHING` into `public.social_nonces(provider, nonce, expires_at = token exp + 60 s)`; 0 rows → `SOCIAL_TOKEN_INVALID` (replay). Nonce refusals count in the 19.3 per-IP failure limit. Expired nonces purged by `AuthTokenCleanupJob` (one set-based DELETE on an `expires_at` index).
- **List** `GET /api/v1/auth/social/accounts` (any authenticated membership role, VIEWER included) → `200 [{provider:"GOOGLE"|"APPLE", label:"Google"|"Apple", linkedAt}]`, one entry per row, ordered by `linkedAt`; never the subject nor an id.
- **Link** `POST /api/v1/auth/social/accounts/google` `{idToken, nonce?}` and `/apple` `{identityToken, authorizationCode?, nonce?}`: same verification as sign-in (verifier + nonce + per-IP failure limit). Subject already linked to the caller → `200` (idempotent, Apple token refreshed if a code is given); linked to another account → 409 `SOCIAL_ACCOUNT_LINKED_ELSEWHERE` « Ce compte Google ou Apple est déjà lié à un autre compte Keevo »; caller already has another identity of that provider → 409 `SOCIAL_PROVIDER_ALREADY_LINKED` « Un compte de ce fournisseur est déjà lié. Retirez-le d'abord »; SUPER_ADMIN → `SOCIAL_TOKEN_INVALID`. Linking never changes the account's email, names or roles. Response `200` = the list.
- **Unlink** `DELETE /api/v1/auth/social/accounts/{provider}` (`google`|`apple`, case-insensitive; other → 404 path not found as `RESOURCE_NOT_FOUND`): in one transaction, lock the user row (`UserRepository.lockById`), refuse with 409 `LAST_SIGN_IN_METHOD` « Impossible de retirer votre seul moyen de connexion » when the account would keep no way in (no password hash, no phone, no email, no authenticator of another provider), queue the Apple tokens of the removed rows, delete the rows. Not linked → `204` (idempotent). Success → `204`.
- **Apple token (revocation port).** Port `AppleTokenClient`: `enabled()`, `exchange(clientId, authorizationCode) → Optional<AppleGrant(refreshToken, subject)>`, `revoke(clientId, refreshToken)`. Default adapter `DisabledAppleTokenClient` (no network). `HttpAppleTokenClient` only when `keevo.auth.social.apple.revocation.enabled=true` (`APPLE_REVOCATION_ENABLED`): client secret = ES256 JWT (`iss`=team id, `sub`=client id, `aud`=`https://appleid.apple.com`, `kid`=key id, 5 min) from `APPLE_TEAM_ID`, `APPLE_KEY_ID`, `APPLE_PRIVATE_KEY` (PKCS#8 PEM of the `.p8`, `\n` accepted); `POST /auth/token` (grant `authorization_code`) and `POST /auth/revoke` (`token_type_hint=refresh_token`), identity's own RestTemplate (connect 5 s, read 10 s). Enabled with a missing/invalid key or without `KEEVO_SECRETS_ENCRYPTION_KEY` → startup fails. The client id is the identity token's matched audience (the bundle the code was issued for); it is stored with the token.
- **Store.** After the sign-in/link transaction commits, when the adapter is enabled and an `authorizationCode` was sent: exchange (network, outside any transaction); the grant's subject must equal the token's subject, else ignored; refresh token encrypted with `SecretCipher` (context = authenticator row id) into `user_authenticators.revocation_token` + `revocation_client_id` (one UPDATE by `(provider, subject)`). Any exchange failure is logged (kind only) and never fails the sign-in.
- **Revocation outbox** `public.apple_token_revocations(id, authenticator_id, client_id, token bytea, attempts, next_attempt_at, created_at)`: rows copied set-based in the same transaction that deletes the authenticators — unlink, and `TenantDeletionService` before deleting orphaned users (`INSERT … SELECT` on `user_id = ANY(?) AND revocation_token IS NOT NULL AND NOT EXISTS membership`; the cascade then removes the authenticators). `AppleTokenRevocationJob` (every 10 min, ShedLock, no-op when the adapter is disabled): up to 100 due rows by `next_attempt_at` index, decrypt + revoke each, then one `DELETE … ANY(succeeded or undecryptable)` and one `UPDATE … ANY(failed)` with backoff `least(2^attempts min, 1 day)`; rows reaching 20 attempts are deleted with an error log.
- Logs never contain tokens, codes, nonces, subjects or emails. Paths: list/link/unlink authenticated (not public), VIEWER allow-list gets the two POST and the DELETE. Swagger documented, `endpoints.md` regenerated, `application.yml` + `.env.example` + deploy note.

**Never:** No real call to Google/Apple in tests or live checks (MockRestServiceServer / fakes). No change to existing response shapes (all body fields optional and additive). No email/name change on link. No user-level deletion endpoint (account deletion stays the tenant flow). No nonce issued by the server. No new Maven dependency.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Nonce matches | claim = sha256(nonce) | sign-in as 19.3, nonce stored | — |
| Nonce mismatch / claim without nonce | | — | 401 `SOCIAL_TOKEN_INVALID` |
| Same token + nonce replayed | nonce row exists | — | 401 `SOCIAL_TOKEN_INVALID` |
| No nonce, `require-nonce=false` | | accepted (V1/Bref) | — |
| No nonce, `require-nonce=true` | | — | 401 `SOCIAL_TOKEN_INVALID` |
| Link new Google | member signed in by code | 200 list incl. Google; next Google sign-in finds the account | — |
| Link subject of another account | | — | 409 `SOCIAL_ACCOUNT_LINKED_ELSEWHERE` |
| Link second Google identity | | — | 409 `SOCIAL_PROVIDER_ALREADY_LINKED` |
| Unlink only method | Apple-only account (technical username) | — | 409 `LAST_SIGN_IN_METHOD` |
| Unlink Apple with stored token | phone account | 204, row gone, one outbox row | — |
| Tenant deleted, orphan owner with Apple token | | authenticators gone, outbox row; job revokes then deletes it | revoke failure → attempts+1, retried later |
| Apple code exchange fails / adapter disabled | | sign-in 200, no token stored | logged |

</intent-contract>

## Code Map

- `identity/auth/application/service/SocialSignInService.java` -- 19.3 façade (rate limiter, verifier, `resolve`, `refuseSuperAdmin`); add nonce check, Apple token store after commit.
- `identity/auth/adapter/out/social/JwksSocialTokenVerifier.java` -- `verify`: expose `nonce` claim, matched audience, `exp` in `SocialIdentity`.
- `identity/auth/domain/model/SocialIdentity.java` -- record; add `audience`, `nonce`, `expiresAt` (keep a 6-arg constructor for existing tests).
- `identity/auth/domain/port/out/UserAuthenticatorRepository.java` + `adapter/out/persistence/impl/JdbcUserAuthenticatorRepository.java` -- add list, unlink-with-queue, store token, queue for orphans.
- `identity/auth/domain/port/out/UserRepository.java:63,71` -- `setEmailIfAbsent`, `lockById` (reuse).
- `identity/auth/application/service/TenantDeletionService.java:256-285` -- step 6 orphan users DELETE: queue Apple tokens just before it.
- `identity/auth/application/service/AuthTokenCleanupJob.java` -- add nonce purge.
- `identity/auth/adapter/in/rest/AuthController.java:548-607` -- sign-in endpoints, `clientIp`; DTOs `GoogleSignInDto`, `AppleSignInDto`.
- `shared/infrastructure/security/crypto/SecretCipher.java` -- AES-GCM with context (21.5).
- `shared/infrastructure/security/ViewerWriteGuardFilter.java:49` -- VIEWER allow-list (pinned by `ViewerAllowListPinTest`).
- `shared/domain/exception/ErrorCode.java`, `shared/infrastructure/web/ErrorMessages.java`, `GlobalExceptionHandler.java:286-293` -- error codes / HTTP status.
- `adapter/out/social/SocialAuthConfig.java` -- config style (`commaList`, own RestTemplate).
- Tests: `SocialSignInServiceTest`, `JwksSocialTokenVerifierTest` + `SocialTestTokens`, `UserAuthenticatorPostgresIntegrationTest`, `TenantDeletionServiceTest`, `AccountDeletionRgpdPostgresIntegrationTest`, `AuthTokenCleanupJobTest`.

## Tasks & Acceptance

**Execution:**
- `db/migration/V73__social_accounts_management.sql` -- `user_authenticators.revocation_token bytea`, `revocation_client_id varchar(255)`; `social_nonces(provider, nonce varchar(128), expires_at, pk(provider, nonce))` + `expires_at` index; `apple_token_revocations` + `next_attempt_at` index; idempotent.
- `SocialIdentity`, `JwksSocialTokenVerifier` -- new fields.
- `domain/port/out/SocialNonceRepository` + JDBC adapter -- `consume(provider, nonce, expiresAt)`, `purgeExpiredBefore`.
- `application/service/SocialNonceChecker` -- nonce rules (shared by sign-in and link).
- `domain/port/out/AppleTokenClient`, `AppleGrant`; `adapter/out/social/DisabledAppleTokenClient`, `HttpAppleTokenClient`, `AppleRevocationConfig` -- port + adapters + config validation.
- `domain/port/out/AppleRevocationQueue` + JDBC adapter -- due batch, delete/backoff set-based.
- `application/service/AppleTokenStore` -- exchange + encrypt + store (after commit).
- `application/service/SocialAccountService` + `SocialAccountUseCase` -- list/link/unlink.
- `application/service/AppleTokenRevocationJob` -- outbox drain.
- `adapter/in/rest/SocialAccountController` + DTOs -- three endpoints; DTO fields `nonce`, `authorizationCode` on sign-in DTOs; `SocialSignInCommand` gains `nonce`, `authorizationCode`.
- `TenantDeletionService` -- queue before orphan users DELETE.
- `ErrorCode`/`ErrorMessages`/`GlobalExceptionHandler`, `ViewerWriteGuardFilter`, `application.yml`, `.env.example`, `endpoints.md`.
- Tests -- verifier (nonce/audience/exp exposed); nonce checker (every nonce row); service tests link/unlink/list; HTTP adapter with MockRestServiceServer (client secret ES256 claims, form params, error → empty/exception); job (success, failure backoff, undecryptable, disabled no-op); Postgres tests: V73 repos (nonce conflict, unlink queue + delete, orphan queue on tenant deletion, cascade), controller tests incl. VIEWER POST allowed.

**Acceptance Criteria:**
- Given an account linked to Apple with a stored token, when its tenant is deleted, then its `user_authenticators` rows are gone and the revocation job calls the Apple revoke port once and empties the outbox.
- Given a member whose Google sign-in returned 409, when they sign in by code then link Google, then a later Google sign-in returns 200 for the same account.
- Given the V1 2.0.1 app and the Bref app, when they sign in without nonce nor code, then responses are unchanged.

## Spec Change Log

## Review Triage Log

### 2026-11-10 — Review pass
- verdicts: 44 findings — high 0, medium 12, low 28, false 4, maybe-false 0 (5 layers: blind 16, edge 11, verification-gap 3, intent 7, patterns 6; +1 found at the live check; rows sharing a root cause list every member)
- findings:
  - `[medium]` `[patch]` (blind 1) Apple codes of the web flow (Services ID) need `redirect_uri` at exchange — optional `APPLE_REVOCATION_WEB_REDIRECT_URIS` (`<services id>=<uri>`), sent only for those client ids; tests added.
  - `[medium]` `[patch]` (blind 2, edge 1) async exchange racing an unlink / deletion forgets a live Apple token — `storeRevocationToken` returns false on 0 rows; row gone → token queued directly (`AppleRevocationQueue.enqueue`, own context id); unit + Postgres tests.
  - `[false]` `[reject]` (blind 3a, edge 2) older stored token overwritten without revocation — revoking any refresh token revokes the user's whole authorization for the app (Apple), so the newest token covers the older ones.
  - `[low]` `[reject]` (blind 3b) one Apple exchange per sign-in with a code — async on its own pool now (patterns 1), keeps the freshest token; skipping it would keep a possibly dead token.
  - `[medium]` `[defer]` (blind 4, intent 2) accounts without company have no deletion path, so no Apple revocation — no user-level deletion exists; routed to 16-6.
  - `[false]` `[reject]` (blind 5) an unverified email is not a way in — the sign-in code by email signs into such an account and verifies it (VerifyOtpService:128).
  - `[low]` `[reject]` (blind 6) list has no `removable` flag — the app handles 409 `LAST_SIGN_IN_METHOD` with its French message; extra field not needed now.
  - `[low]` `[reject]` (blind 7) no audit / notice on link — needs an authenticated session (already full power); same decision as 19.3 for sign-in links.
  - `[low]` `[reject]` (blind 8) Google puts the nonce verbatim: the app must hash before giving it to Google too — contract stated in the Mobile section (rule from the intent: one rule for both providers).
  - `[false]` `[reject]` (blind 9) Google `azp` used without check — it is checked a few lines above (`azp == null || !audiences.contains(azp)` → refused).
  - `[low]` `[patch]` (blind 10, edge 6) blank `nonce` gave 422 although the checker treats it as absent — `@Pattern("^$|^.{16,128}$")` in the four DTOs; controller test with `""`.
  - `[low]` `[patch]` (blind 11, patterns 5) job worst case near the 30 min lock — batch 100 → 30 and lockAtMostFor PT30M → PT9M (≤ 7.5 min worst case, within the 10 min period pinned by SchedulerLockCoverageTest).
  - `[low]` `[reject]` (blind 12) given-up / undecryptable revocations leave only a log — error-level log with the row id; a dead-letter table is not justified now.
  - `[low]` `[patch]` (blind 13a) `social_nonces.provider` without CHECK — CHECK added in V73 (not yet applied anywhere shared).
  - `[low]` `[reject]` (blind 13b) `apple_token_revocations.created_at` never read — kept for diagnosis (age of a stuck row).
  - `[low]` `[reject]` (blind 13c) no Postgres concurrency test of link races — the user row lock and `ON CONFLICT` paths are unit-tested; low value.
  - `[medium]` `[patch]` (edge 3) Apple private relay email counted as a way in when unlinking Apple — `emailStaysUsable` excludes `@privaterelay.appleid.com` for an Apple unlink; test added.
  - `[low]` `[reject]` (edge 4) a concurrent sign-in may link a second identity of a provider by email — needs two different Google/Apple accounts with the same verified email at the same instant; 19.3 path unchanged.
  - `[medium]` `[patch]` (edge 5, patterns 1) exchange on the shared notification executor; CallerRuns blocks the sign-in thread — dedicated `appleTokenExecutor` (1–2 threads, queue 100, drop + log), submitted only when a code is sent.
  - `[low]` `[reject]` (edge 7) nonce consumed before a failing transaction blocks a retry with the same token — the app gets a fresh token per attempt (Mobile section).
  - `[low]` `[reject]` (edge 8) row whose client id was removed from config retried then given up — operator configuration change; logged.
  - `[low]` `[reject]` (edge 9) `delete` throwing skips `recordFailures` — both hit the same database; rows are retried next run.
  - `[low]` `[reject]` (edge 10) outbox rows pile up while disabled — rows exist only if tokens were stored while enabled.
  - `[low]` `[reject]` (edge 11) unknown client id logged as « code refused » — configuration is validated at startup; cosmetic.
  - `[medium]` `[patch]` (vg 1) link verifier inputs (nonce, IP) unasserted — service test verifies `verify(provider, token, nonce, ip)`; controller test asserts `clientIp`.
  - `[medium]` `[patch]` (vg 2) nonce refusals counting toward the IP limit untested — 30 wrong nonces then `RATE_LIMIT_EXCEEDED` test.
  - `[medium]` `[patch]` (vg 3) 409 of `SOCIAL_PROVIDER_ALREADY_LINKED` untested at HTTP level — controller test added.
  - `[low]` `[reject]` (intent 1) revocation only for tokens stored while enabled and with a code — by design (intent: disabled adapter by default); deploy note and Mobile section.
  - `[low]` `[reject]` (intent 3) exchange and revoke legs not chained in one test — each leg tested (exchange: unit + HTTP mock; deletion → queue → job: Postgres).
  - `[medium]` `[defer]` (intent 4) default config keeps no replay protection for tokens without nonce — flag flip routed to 23-1.
  - `[low]` `[reject]` (intent 5) explicit link has no role / email-proof rule — the session proves the account; that is the way out the routed item asks for.
  - `[low]` `[reject]` (intent 6) new app contract (code, nonce hash) — written in the Mobile section.
  - `[low]` `[reject]` (intent 7) no real Google/Apple call verified — required by the intent; end-of-epic device test.
  - `[low]` `[reject]` (patterns 2) `link` reads the user's links twice — ≤ 2 indexed rows.
  - `[low]` `[reject]` (patterns 3) lock + read in two statements — same pattern as CreateCompanyService.
  - `[low]` `[reject]` (patterns 4) client secret signed per call — one ES256 signature, ≤ 30 per run.
  - `[low]` `[reject]` (patterns 6) failure write in two statements — both set-based.
  - `[low]` `[reject]` (live check) a refused token on the authenticated link endpoints answers 401 — `domainCode` `SOCIAL_TOKEN_INVALID` distinguishes it from a session expiry; Mobile section says not to sign out on it.

## Design Notes

**Patterns.** Strategy/port + Null Object for Apple (`DisabledAppleTokenClient` default, `HttpAppleTokenClient` by property) — tests and environments without Apple keys never reach the network. Transactional outbox for revocation: the token copy commits atomically with the authenticator deletion (unlink or tenant deletion), the network call is retried by a ShedLock job — a crash or Apple outage never loses a revocation and never blocks a deletion. Nonce = one-time token via unique-key insert (idempotent, race-free, no read-then-write). Façade `SocialAccountService` for list/link/unlink, reusing 19.3's verifiers and rate limiter.

**Complexity (FUS-NFR11).** Sign-in: +1 indexed nonce insert when a nonce is sent; +1 network call + 1 UPDATE on the `(provider, subject)` unique index only when an Apple code is sent and revocation enabled. List: 1 query on `user_id` index. Link: 1 subject lookup + 1 per-user provider lookup + 1 insert. Unlink: 1 lock + 1 read of the user's authenticators (≤ few rows) + 1 `INSERT…SELECT` + 1 DELETE. Tenant deletion: +1 set-based `INSERT…SELECT` per tenant. Job: 1 batch SELECT (≤30, indexed) + ≤30 network calls (own executor for the sign-in exchange) + ≤3 set-based writes; nonce purge 1 DELETE.

## Verification

**Commands:**
- `wt-pkgs.sh <worktree>/keevo/backend t193b identity shared architecture` -- expected: green.

**Manual checks:**
- Local backend (DB `keevo_dev_t193b`, port 4593): garbage token with nonce → 401; `GET /auth/social/accounts` with an access token → `[]`; `DELETE /auth/social/accounts/google` not linked → 204; V73 objects present; Apple adapter disabled at startup (log).

## Auto Run Result

Status: done

**Summary.** (1) Nonce: optional clear `nonce` on Google/Apple sign-in and link bodies; the token's `nonce` claim must equal its SHA-256 hex; a token carrying a nonce claim needs it; a verified nonce is consumed once (`social_nonces`, until exp + 60 s, purged daily); tokens without nonce pass while `SOCIAL_REQUIRE_NONCE=false` (default). Nonce refusals count in the per-IP limit. (2) `GET/POST/DELETE /api/v1/auth/social/accounts…` for any role (VIEWER allow-list extended): list (provider, French label, date), link under the user's row lock (`SOCIAL_ACCOUNT_LINKED_ELSEWHERE`, `SOCIAL_PROVIDER_ALREADY_LINKED`, idempotent when already linked, no email/role change), unlink with `LAST_SIGN_IN_METHOD` guard (password, phone, usable email — not an Apple relay address when unlinking Apple —, or the other provider). (3) Apple revocation: port `AppleTokenClient` (default `DisabledAppleTokenClient`, `HttpAppleTokenClient` when `APPLE_REVOCATION_ENABLED=true`, ES256 client secret, fail-fast config); Apple `authorizationCode` exchanged after commit on a dedicated pool, refresh token stored encrypted (SecretCipher, context = row id); unlink and tenant deletion (orphan users) copy stored tokens into the `apple_token_revocations` outbox in the same transaction; a token issued while its link was being removed is queued directly; `AppleTokenRevocationJob` (10 min, ShedLock, batch 30) revokes, deletes, backs off (2^n min, ≤ 1 day), gives up after 20.

**Files.** New: V73; ports `SocialNonceRepository`, `AppleTokenClient`, `AppleRevocationQueue`, `SocialAccountUseCase`; JDBC `JdbcSocialNonceRepository`, `JdbcAppleRevocationQueue`; `SocialIdentityVerifier` (verification shared by sign-in and link), `SocialNonceChecker`, `SocialAccountService`, `AppleTokenStore`, `AppleTokenRevocationJob`; `AppleRevocationConfig`, `DisabledAppleTokenClient`, `HttpAppleTokenClient`; `SocialAccountController` + DTOs. Changed: `SocialIdentity` (audience, nonce, expiry), `JwksSocialTokenVerifier`, `SocialSignInService`, `SocialSignInCommand`, sign-in DTOs, `JdbcUserAuthenticatorRepository`, `TenantDeletionService` (outbox copy before the orphan users DELETE), `AuthTokenCleanupJob`, error codes (3 × 409), `ViewerWriteGuardFilter` + pin test, `application.yml`, `.env.example`, `endpoints.md`.

**Review.** 44 findings (5 layers + live check): 0 high, 12 medium, 28 low, 4 false. Patched: 9 medium (web `redirect_uri`, exchange/unlink race, Apple relay email, dedicated executor, 3 verification gaps, plus grouped members) and 3 low (blank nonce, batch size, nonce provider CHECK). Deferred/routed: 2 medium → 16-6 (deletion of an account without company), 23-1 (turn `SOCIAL_REQUIRE_NONCE` on). Rejected: the rest, reasons in the triage log.

**Follow-up review: recommended (true)** — two or more medium patches; unverified risk: Apple's real `/auth/token` and `/auth/revoke` (only MockRestServiceServer), the web-flow `redirect_uri` mapping and the private-relay rule are untested against Apple.

**Verification.** Targeted classes: 166 tests green after patches. Packages after the last fix: identity 922, shared 1 354 (two pin tests updated: SchedulerLockCoverageTest — job lock PT9M under its 10 min period —, TokenClaimsGuardTest — Apple client secret), architecture 146 — 0 failure. Live, rerun on a fresh database after the patches (`keevo_dev_t193b`, port 4593, WhatsApp noop, FCM off, Apple adapter disabled — startup log « Apple token revocation disabled »): garbage Google token with nonce → 401 `SOCIAL_TOKEN_INVALID`; blank nonce treated as absent (401 on the garbage token, not 422); nonce of 5 chars → 422 « le nonce doit contenir entre 16 et 128 caractères »; `GET /auth/social/accounts` → `{"data":[]}`; `DELETE …/google` not linked → 204; `DELETE …/facebook` → 404 « Ressource introuvable »; link with a garbage token → 401; list without token → 401; password login unchanged; V73 applied (CHECK `social_nonces_provider_check`, indexes `pk_social_nonces`, `ix_social_nonces_expires`, `ix_apple_token_revocations_due`, columns `revocation_token`, `revocation_client_id`).

**Deploy note.** Nothing changes until configured. To meet the App Store rule: `APPLE_REVOCATION_ENABLED=true`, `APPLE_TEAM_ID`, `APPLE_KEY_ID`, `APPLE_PRIVATE_KEY` (content of the `.p8` « Sign in with Apple » key, `\n` accepted), `KEEVO_SECRETS_ENCRYPTION_KEY` (already required by 21.5), optionally `APPLE_REVOCATION_CLIENT_IDS` (default `APPLE_BUNDLE_IDS`) and `APPLE_REVOCATION_WEB_REDIRECT_URIS` (`<services id>=<redirect uri>` for web sign-in). Missing value with the flag on → startup fails. `SOCIAL_REQUIRE_NONCE=true` only once every app in stores sends a nonce (23-1). A single Apple team is supported (all client ids signed with one key).

**Residual risks.** Accounts linked before revocation is enabled, or by apps that send no code, have no stored token (nothing to revoke); accounts without company cannot be deleted yet (16-6).

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit 7239d5a3). Conventions : spec-16-11 ; synchro : spec-16-10.

**Ce qui est fait côté API :** protection contre le rejeu des jetons Google/Apple (nonce), écran « Comptes Google et Apple » possible (lister, lier, retirer), révocation de Sign in with Apple quand le compte est supprimé ou qu'Apple est retiré (exigence App Store).

**À utiliser :**
1. Connexion (inchangée, champs ajoutés optionnels) : `POST /api/v1/auth/social/google` `{"idToken", "firstName"?, "lastName"?, "nonce"?}` ; `POST /api/v1/auth/social/apple` `{"identityToken", "firstName"?, "lastName"?, "nonce"?, "authorizationCode"?}`.
2. `nonce` : chaîne aléatoire de 16 à 128 caractères générée par l'app à chaque tentative. **Donner à Google ET à Apple le SHA-256 en hexadécimal minuscule du nonce**, envoyer au backend le nonce **clair**. Un nonce ne sert qu'une fois : après un échec, refaire la connexion Google/Apple (nouveau jeton, nouveau nonce). Chaîne vide = absent.
3. `authorizationCode` (Apple uniquement) : le code renvoyé par Sign in with Apple avec le jeton ; il permet au serveur de révoquer le lien Apple à la suppression du compte. L'envoyer à chaque connexion et liaison Apple.
4. Paramètres (session ouverte, tous rôles, lecteur compris) :
   - `GET /api/v1/auth/social/accounts` → `200 {"data":[{"provider":"GOOGLE"|"APPLE","label":"Google"|"Apple","linkedAt":"…"}]}`.
   - `POST /api/v1/auth/social/accounts/google` `{"idToken","nonce"?}` / `POST /api/v1/auth/social/accounts/apple` `{"identityToken","authorizationCode"?,"nonce"?}` → `200` même liste.
   - `DELETE /api/v1/auth/social/accounts/google|apple` → `204` (aussi si non lié).
5. Erreurs (afficher `error` tel quel) :
   - 401 `SOCIAL_TOKEN_INVALID` « Connexion Google ou Apple refusée. Réessayez » — **sur les routes `/social/accounts`, ne pas déconnecter l'utilisateur** : seul `domainCode` distingue ce refus d'une session expirée.
   - 409 `SOCIAL_ACCOUNT_LINKED_ELSEWHERE` « Ce compte Google ou Apple est déjà lié à un autre compte Keevo ».
   - 409 `SOCIAL_PROVIDER_ALREADY_LINKED` « Un compte de ce fournisseur est déjà lié. Retirez-le d'abord ».
   - 409 `LAST_SIGN_IN_METHOD` « Impossible de retirer votre seul moyen de connexion ».
   - 422 corps invalide (nonce de 1 à 15 caractères) ; 429 `RATE_LIMIT_EXCEEDED` « Trop de requêtes, veuillez patienter ».

Code backend : `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/in/rest/SocialAccountController.java`, `application/service/SocialAccountService.java`, `application/service/SocialNonceChecker.java`, `application/service/AppleTokenStore.java`.

**À implémenter côté mobile :**
1. Connexion Google/Apple : générer un nonce par tentative, hacher pour le fournisseur, envoyer le clair ; pour Apple, envoyer aussi `authorizationCode`.
2. Sur un 409 `EMAIL_ALREADY_EXISTS` à la connexion Google/Apple : proposer « Recevoir un code » (19.2), puis, une fois connecté, proposer de lier Google/Apple (Paramètres › Comptes Google et Apple).
3. Écran « Comptes Google et Apple » : liste (libellé + date « Lié le … »), bouton « Lier » par fournisseur absent, « Retirer » par fournisseur présent avec confirmation ; Apple seulement sur iOS (et web si configuré).
4. Hors ligne : écran en lecture seule (dernière liste connue), boutons désactivés, « Connexion Internet requise ».
5. Aucune donnée technique à l'écran (pas d'identifiant Google/Apple ; `provider` sert à la logique, `label` à l'affichage).

**Implémentation de référence :** aucune dans `keevo/app` (client de test Flutter non concerné).

**Critères d'acceptation mobile / scénarios de test :**
- Given un membre dont la connexion Google répond 409, When il se connecte par code puis lie Google, Then la connexion Google suivante ouvre son compte.
- Given un compte avec téléphone lié à Apple, When il retire Apple, Then la liste ne montre plus Apple (204).
- Given un compte créé par Apple sans email ni téléphone, When il retire Apple, Then « Impossible de retirer votre seul moyen de connexion ».
- Given le même jeton et nonce envoyés deux fois, Then le second est refusé (401) et l'app relance la connexion Google/Apple.
- Mise en place : backend local avec `GOOGLE_CLIENT_IDS` / `APPLE_BUNDLE_IDS` de l'app ; la révocation Apple reste désactivée hors production.

**Clé de suivi mobile :** `m19-3b-comptes-sociaux-gestion`.

## Dashboard super-admin (autre dépôt)

**Statut :** aucune API super-admin ajoutée ou modifiée par 19.3b ; rien à faire côté dashboard (pas de clé `d19-3b`).
