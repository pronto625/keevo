---
title: '19.2 — OTP authenticator (SMS, WhatsApp, email), account without company, company creation from a login token'
type: 'feature'
created: '2026-11-04'
status: 'done'
baseline_revision: 'a86e35784f04824a5d6a8e9261902edc13d4342b'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
warnings: [oversized]
deferred:
  - summary: >-
      create-company refuses a user whose global users.role is not OWNER (an employee elsewhere cannot create a company).
    evidence: |-
      ChangePasswordService issues tokens from User.role, so an EMPLOYEE-global owner would get EMPLOYEE tokens; relaxing it needs the role carried by the membership. Routed to 19.6 (epic brief).
    location: >-
      keevo/backend/src/main/java/com/keevo/identity/auth/application/service/CreateCompanyService.java
    severity: low
---

<intent-contract>

## Intent

**Problem:** Bref users sign in with a 6-digit code (SMS, WhatsApp or email) and no password; Keevo only knows phone + password, so a Bref account (email only, no password, sometimes no company yet) cannot sign in to the merged backend.

**Approach:** Port Bref's OTP (`api-bref/src/otp/*`: identifier normalization with an allowed country list, 5-minute code, failures counted in the database, per-identifier and per-IP limits, daily failure cap, store-review account) into `identity/auth` as `POST /api/v1/auth/otp/request` and `POST /api/v1/auth/otp/verify`. Verify ends in Keevo's existing two-step login (same body as `/auth/login`); an unknown identifier gets a new account without company, which then calls a new `POST /api/v1/auth/create-company` with its `loginToken`. Codes go out through the 19.9 platform ports (`SmsSender`, `EmailSender`) and the existing `WhatsAppSender` (Meta switch is 19-9b).

## Boundaries & Constraints

**Always:**
- Identifier = email (contains `@`, ≤254 chars, Bref regex `^[^\s@]{1,64}@[^\s@]+\.[^\s@]{2,}$`, stored and compared lowercased) or phone: `PhoneNumbers.normalize`, then a leading `+00` becomes `+`, then must match `^\+\d{8,15}$`; a valid phone whose prefix is not in `keevo.auth.otp.allowed-phone-prefixes` (default `237,241,235,243,221`, env `KEEVO_AUTH_ALLOWED_PHONE_PREFIXES`) → `PHONE_COUNTRY_NOT_SUPPORTED`. Anything else → `VALIDATION_FAILED`.
- Code: 6 digits from `SecureRandom` (100000–999999), stored only as a `PasswordEncoder` hash, valid 5 min; a new request invalidates the identifier's previous unconsumed challenges.
- Request response is identical whether or not an account exists: `200 {"expiresIn":300}`. Delivery failures are logged (masked identifier, exception class only, never the code) and never change the response.
- Channel: body field `channel` ∈ `SMS|WHATSAPP|EMAIL`, optional. Email identifier → EMAIL only (`SMS`/`WHATSAPP` with an email → `VALIDATION_FAILED`). Phone identifier: `SMS` or `WHATSAPP` → that channel only; absent → SMS **and** WhatsApp (Bref behaviour); `EMAIL` with a phone → `VALIDATION_FAILED`. Sends happen after the challenge's transaction commits, outside any transaction. OTP sends are platform sends: never `TenantMessaging`, never counted on a tenant quota.
- Messages in French by default, English when `Accept-Language` starts with `en`; brand « Bref Point ». SMS: « Votre code de connexion Bref Point : {code}. Valable 5 minutes. Ne le partagez pas. » Email: same subject/text plus a simple HTML body (code escaped, no external assets).
- Limits (Caffeine, bounded, `Clock` ticker, like `PasswordResetRateLimiter`): per identifier 3 requests / 10 min and 5 / hour; per IP 10 requests / hour (configurable under `keevo.auth.otp.*`). Daily cap: ≥20 failed verifications on an identifier in the last 24 h (sum of `attempts` in the database) blocks both request and verify. All three → `OTP_TOO_MANY_REQUESTS` (429).
- Verify locks the identifier's latest unconsumed, unexpired challenge (`FOR UPDATE`). Wrong code → `attempts + 1` committed (`noRollbackFor`); reaching 5 consumes the challenge and answers `OTP_TOO_MANY_ATTEMPTS` (429), otherwise `OTP_INVALID` (401). No challenge / expired / consumed → `OTP_INVALID`. Right code → challenge consumed, then the account is found (phone via `findByPhoneNumber`, email via the new `findByEmail` = `lower(email) = lower(?)` on `uq_users_email_lower`) or created (role OWNER, no password, no membership, the identifier as phone or email); `phone_verified_at` / `email_verified_at` set when null. Answer = `LoginSessionResponse` exactly like `/auth/login` (`loginToken` + `memberships`, empty for a new account). Account lockout (`locked_until`) is not consulted (OTP is its own factor).
- A unique violation while creating the account (concurrent registration of the same phone/email) re-reads once and uses the existing account; if still absent → `USER_ALREADY_EXISTS` (409). Routed 19-1 item: `findByEmail` + this domain error.
- `POST /api/v1/auth/create-company {loginToken, deviceId?, deviceLabel?}`: valid `login_pending` token (same errors as `select-tenant`); refused with `COMPANY_ALREADY_EXISTS` (409) if the user already has an active OWNER membership; otherwise provisions the tenant exactly like `/auth/register` (trial plan, OWNER membership, `assignOwnerRole`, `UserRegisteredEvent`) and answers `201` with `RegistrationResponse`. The provisioning steps move to one shared component used by both `RegistrationService` and this use case (no copy).
- Store-review account (Bref): `keevo.auth.otp.review-identifier` / `review-code` (env `KEEVO_OTP_REVIEW_IDENTIFIER` / `KEEVO_OTP_REVIEW_CODE`, empty by default = disabled, code must be ≥6 digits): request for that identifier skips rate limits and sends nothing; verify with that code (constant-time compare) succeeds without a challenge, WARN log with the masked identifier.
- `AuthTokenCleanupJob` also deletes challenges created more than 24 h ago (one set-based `DELETE`).
- New paths public in `SecurityConfig`, documented in Swagger and `docs/entitlements/endpoints.md`; the controller stays `@EntitlementExempt`.

**Never:**
- Never log or return the code, never store it in clear, never reveal account existence on `/otp/request`.
- No change to `/auth/login`, `/auth/register`, `/auth/select-tenant`, forgot/reset password responses (V1 2.0.1 apps). No password set on OTP accounts. No Google/Apple (19.3), no username (19.4), no multi-company rules beyond the OWNER check (19.6), no sector/store/default sale flow at creation (19.12/onboarding), no Meta templates (19-9b), no localisation beyond fr/en.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Request phone, no channel | `+237 6 99 00 11 22` | 200 `{expiresIn:300}`; SMS + WhatsApp sent after commit; challenge hashed | — |
| Request unknown email | `Ana@Ex.com`, EMAIL | 200 same body; email sent to `ana@ex.com` | — |
| Country refused | `+33612345678` | — | 422 `PHONE_COUNTRY_NOT_SUPPORTED` |
| Bad identifier / channel mismatch | `abc`, or email + `SMS` | — | 422 `VALIDATION_FAILED` |
| `0023799…` / `00237…` | normalized to `+23799…` | accepted | — |
| 4th request in 10 min / 11th per IP in 1 h | | — | 429 `OTP_TOO_MANY_REQUESTS` |
| SMS provider down | | still 200, ERROR log without code/phone | — |
| Verify right code, existing owner | | 200 `{loginToken, memberships:[1]}`, `phone_verified_at` set | — |
| Verify right code, unknown email | | 200 `{loginToken, memberships:[]}`, user created, `email_verified_at` set | — |
| Wrong code ×4 then ×5th | | 401 `OTP_INVALID` ×4, then 429 `OTP_TOO_MANY_ATTEMPTS`, challenge dead | attempts persisted |
| Expired / reused code | | — | 401 `OTP_INVALID` |
| 20 failures in 24 h | new request or verify | — | 429 `OTP_TOO_MANY_REQUESTS` |
| Create company, new account | valid loginToken | 201 `RegistrationResponse`, tenant on trial | — |
| Create company, already owner | | — | 409 `COMPANY_ALREADY_EXISTS` |
| Create company, expired token | | — | 401 `TOKEN_EXPIRED` (as select-tenant) |

</intent-contract>

## Code Map

- `api-bref/src/otp/identifier.ts`, `otp.service.ts`, `otp-messages.ts` (read-only, `/home/toor/Project/FreeLance/AI/api-bref`) -- reference behaviour: prefixes, regex, limits, 5-min TTL, `recordFailure`, daily cap, review account, fr/en texts.
- `identity/auth/application/service/RequestPasswordResetService.java` -- pattern to copy: hash via `PasswordEncoder`, invalidate previous, send in `afterCommit`, failure logged without PII.
- `identity/auth/application/service/PasswordResetRateLimiter.java`, `RefreshRateLimiter.java` -- Caffeine fixed windows, `Clock` ticker, `MAX_ENTRIES`.
- `identity/auth/application/service/AuthenticationService.java` -- `@Transactional(noRollbackFor = DomainException.class)`, `findMembershipsWithTenantInfo`, `jwtTokenProvider.generateLoginToken`.
- `identity/auth/application/service/SelectTenantService.java:~70` -- `parseAndValidateLoginToken` (reuse, do not duplicate: extract to a small shared helper if both need it).
- `identity/auth/application/service/RegistrationService.java` -- steps 3–7 (tenantFactory.create, membership, assignOwnerRole, sessionTokenIssuer.issue, UserRegisteredEvent) to extract into a shared provisioner.
- `identity/auth/application/service/SessionTokenIssuer.java:65` -- `issue(userId, tenant, role, null, device)`.
- `identity/auth/application/service/AuthTokenCleanupJob.java:45` -- add challenge purge block.
- `identity/auth/domain/model/User.java` -- 14-arg constructor; `newOwner` sets a phone + hash; add a factory for an OTP account (phone or email, no hash, verified date).
- `identity/auth/domain/port/out/UserRepository.java`, `adapter/out/persistence/impl/UserRepositoryAdapter.java`, `jpa/UserSpringRepository.java` -- add `findByEmail`.
- `identity/auth/adapter/in/rest/AuthController.java` -- new endpoints; `clientIp(HttpServletRequest)` at ~375; `toLoginSessionResponse` reuse.
- `shared/capability/notification/{SmsSender,EmailSender,WhatsAppSender}.java` -- `send(SmsMessage.of(...))`, `send(new EmailMessage(...))`, `WhatsAppSender.sendReport`; add a login-code default method on `WhatsAppSender` (existing `sendOtp` text is the reset one, keep it).
- `shared/domain/PhoneNumbers.java:20` -- normalize (keeps `+00…`).
- `shared/domain/exception/ErrorCode.java`, `shared/infrastructure/web/ErrorMessages.java`, `GlobalExceptionHandler.java` (~266–370 status switch) -- new codes.
- `shared/infrastructure/security/SecurityConfig.java:61` -- `PUBLIC_PATHS`.
- `src/main/resources/db/migration/` -- last is `V44__usage_quotas.sql`; new `V45__otp_challenges.sql` (public schema).
- `src/main/resources/application.yml` -- `keevo.auth.otp.*`; `.env.example`.

## Tasks & Acceptance

**Execution:**
- `src/main/resources/db/migration/V45__otp_challenges.sql` -- `public.otp_challenges(id uuid pk, identifier varchar(254) not null, code_hash varchar(100) not null, expires_at timestamptz not null, attempts int not null default 0, consumed_at timestamptz, created_at timestamptz not null default now())` + index `(identifier, created_at DESC)`; idempotent (`IF NOT EXISTS`) -- storage and daily-cap sum on one index.
- `identity/auth/domain/model/OtpIdentifier.java` (+ `OtpChannel`) -- value object: parse/normalize, kind PHONE/EMAIL, masked form for logs, prefix check with the configured list -- single place for Bref's normalization.
- `identity/auth/domain/port/out/OtpChallengeRepository.java` + `adapter/out/persistence/impl/JdbcOtpChallengeRepository.java` -- `invalidateActive(identifier)`, `insert`, `lockLatestActive(identifier, now)` (`FOR UPDATE`), `recordFailure(id, max)` returning attempts (consumes at max), `consume(id)`, `sumFailuresSince(identifier, since)`, `purgeCreatedBefore(cutoff)` -- JDBC, one statement each.
- `identity/auth/application/service/OtpRateLimiter.java` -- identifier 10-min + 1-hour windows, IP 1-hour window, config via `@Value` -- in-memory like the existing limiters.
- `identity/auth/application/service/OtpMessages.java` -- fr/en SMS text, email subject/text/HTML (escaped code) -- port of `otp-messages.ts` (2 locales).
- `identity/auth/application/service/RequestOtpService.java` + port `RequestOtpUseCase`/`RequestOtpCommand(identifier, channel, clientIp, locale)` -- review account, limits, daily cap, persist, after-commit sends per the channel rule.
- `identity/auth/application/service/VerifyOtpService.java` + `VerifyOtpUseCase`/`VerifyOtpCommand` -- per Always rules; returns `LoginSessionResult`.
- `identity/auth/application/service/OwnerTenantProvisioner.java` -- steps extracted from `RegistrationService` (tenant, membership, owner role, session, event); `RegistrationService` delegates to it unchanged in behaviour.
- `identity/auth/application/service/CreateCompanyService.java` + `CreateCompanyUseCase`/`CreateCompanyCommand(loginToken, device)` -- token check, OWNER-membership refusal, provisioner.
- `identity/auth/domain/model/User.java` -- `newOtpAccount(phone, email, verifiedAt)` factory.
- `identity/auth/domain/port/out/UserRepository.java` + adapter + Spring repo -- `findByEmail` via `lower(email) = lower(:email)`; `UserMembershipRepository` (or `findMembershipsWithTenantInfo`) used for the OWNER check without N+1.
- `shared/capability/notification/WhatsAppSender.java` -- `default void sendLoginCode(String phone, String text)` delegating to `sendReport`.
- `identity/auth/adapter/in/rest/AuthController.java` + DTOs `OtpRequestDto(identifier, channel)`, `OtpRequestResponseDto(expiresIn)`, `OtpVerifyDto(identifier, code)`, `CreateCompanyRequestDto(loginToken, deviceId, deviceLabel)` -- three endpoints, Swagger, `Accept-Language` and `clientIp` passed down.
- `shared/domain/exception/ErrorCode.java`, `ErrorMessages.java`, `GlobalExceptionHandler.java` -- `PHONE_COUNTRY_NOT_SUPPORTED` 422 « Ce pays n'est pas encore pris en charge », `OTP_INVALID` 401 « Code incorrect ou expiré », `OTP_TOO_MANY_ATTEMPTS` 429 « Trop d'essais. Demandez un nouveau code », `OTP_TOO_MANY_REQUESTS` 429 « Trop de demandes de code. Réessayez plus tard », `COMPANY_ALREADY_EXISTS` 409 « Vous avez déjà une entreprise ».
- `shared/infrastructure/security/SecurityConfig.java` -- add `/api/v1/auth/otp/request`, `/api/v1/auth/otp/verify`, `/api/v1/auth/create-company`.
- `identity/auth/application/service/AuthTokenCleanupJob.java` -- purge challenges older than 24 h.
- `src/main/resources/application.yml`, `.env.example`, `docs/entitlements/endpoints.md` -- config keys and env vars, endpoint list.
- Tests (`src/test/java/com/keevo/identity/auth/...`) -- unit: `OtpIdentifier` (every matrix normalization row, prefixes, `00`), `OtpRateLimiter`, `RequestOtpService` (channel rule, after-commit, failure swallowed, review account, no tenant metering), `VerifyOtpService` (every matrix verify row, unique-violation retry, verified dates), `CreateCompanyService`; integration (Testcontainers, existing auth IT style): request → read hash-free flow by stubbing the code generator or review account → verify → create-company → select/tokens usable; attempts persisted across a failed request; `RegistrationService` regression tests stay green.

**Acceptance Criteria:**
- Given a running backend, when `/otp/request` is called for an existing and a non-existing identifier, then both answers are byte-identical and no response, log line or event contains the code.
- Given a new email account created by verify, when it calls `create-company` then `select-tenant` is not needed: the 201 tokens access `GET /api/v1/auth/profile`, and the new tenant has the same trial plan and history as one created by `/auth/register`.
- Given the V1 2.0.1 app, when it uses register/login/select-tenant/forgot-password, then responses are unchanged (existing tests green).
- Given the full suite run package by package, then it is green, including `TenantSchemaInterpolationGuardTest` and architecture tests.

## Spec Change Log

## Review Triage Log

### 2026-11-04 — Review pass
- verdicts: 44 findings — high 0, medium 5, low 31, false 8, maybe-false 0
- findings:
  - `[medium]` `[patch]` (patterns 1, blind 1) bcrypt encode runs after `invalidateActive` locked rows / while a connection is held — hash computed before any SQL; bcrypt kept (14.12 parity, attempts bounded by 5/challenge + daily cap).
  - `[medium]` `[patch]` (patterns 2) `afterCommit` runs before Spring releases the JDBC connection, so provider HTTP calls hold a pooled connection on a public endpoint — DB part moved into a TransactionTemplate, sends after it returns.
  - `[low]` `[patch]` (patterns 3, blind 3, edge 9) in-memory limits are per instance and size-evictable — Javadoc states it; DB daily cap is the global bound.
  - `[low]` `[patch]` (patterns 4) purge `DELETE … WHERE created_at < ?` has no index — `ix_otp_challenges_created` added to unreleased V45.
  - `[low]` `[reject]` (patterns 5) OWNER check loads all memberships — bounded to a handful of rows per user, existing query; a dedicated EXISTS adds a port method for no measurable gain.
  - `[low]` `[reject]` (patterns 6) verified-date update via full JPA save — one row on a unique index, same pattern as every User change; targeted UPDATE adds a port method.
  - `[false]` `[reject]` (intent A) verify should return tokens directly with one tenant — the story says « loginToken puis select-tenant, ou tokens directs »; `/auth/login` never returns direct tokens and the app auto-selects with one membership; one client path chosen in the spec.
  - `[false]` `[reject]` (intent B) create-company is scope beyond the story — the story requires the app to continue to company creation and no endpoint lets a tenant-less user create one; the new endpoint is the minimal backend part (spec Always).
  - `[false]` `[reject]` (intent C) TenantMessaging not used — an OTP user has no tenant to meter; platform sends are the spec rule.
  - `[false]` `[reject]` (intent E) unique-violation error not added to `/auth/register` — the routed 19-1 item scopes it to the account creation 19.2 adds; register keeps its pre-check.
  - `[low]` `[reject]` (intent, channels) real delivery only on mocks — device test deferred to the end of epic 19 by the user.
  - `[low]` `[patch]` (intent, per-IP) per-IP limit only unit-tested — covered by the new verify per-IP unit test; full-context IP spoofing out of scope.
  - `[low]` `[patch]` (intent, additions) daily cap / purge not on the real stack — covered by the new Postgres repository tests.
  - `[medium]` `[patch]` (blind 2, edge 1, edge 2, blind 14) `/otp/verify` has no per-IP limit; the review fixed code has no challenge and no failure count, so it can be brute-forced — per-IP verify window (30/h) applied before every verify, review path included.
  - `[medium]` `[patch]` (blind 4, edge 8) create-company by a global EMPLOYEE gives an OWNER membership while `users.role` stays EMPLOYEE (`ChangePasswordService:103` issues tokens from `User.role`) — refused with `COMPANY_ALREADY_EXISTS` when the global role is not OWNER; relaxing it is 19.6 (routed).
  - `[low]` `[reject]` (blind 5) loginToken replayable for 5 min — same token and TTL as `/auth/login` (pre-existing design); create-company is serialized and refuses a second company.
  - `[low]` `[reject]` (blind 6, edge 11) two concurrent requests can leave two live codes — verify uses the latest; requests are rate-limited (3/10 min); an advisory lock adds complexity for a rare, harmless case.
  - `[low]` `[patch]` (blind 7, edge 5) WhatsApp logged as « sent » though `sendReport` returns void — log says « handed to WhatsApp provider »; delivery results come with Meta in 19-9b.
  - `[low]` `[reject]` (blind 8) « 5 minutes » hard-coded in texts — TTL is a code constant, not configurable; Bref texts identical.
  - `[false]` `[reject]` (blind 9) brand « Bref Point » hard-coded — ADR-02: Bref Point is the final single brand.
  - `[low]` `[reject]` (blind 10) OTP accounts without company never cleaned — creating one requires receiving a real code (bounded by real identifiers), Bref behaves the same.
  - `[low]` `[reject]` (blind 11) passwordless accounts and password endpoints — forgot-password sets a password for them (works); change-password needs a current one (N/A); username+password is 19.4.
  - `[medium]` `[patch]` (blind 12) SUPER_ADMIN could get a loginToken with only an SMS/WhatsApp code — SUPER_ADMIN refused with `OTP_INVALID`. Inactive-account part: `/auth/login` does not check `is_active` either; membership activity is checked at select-tenant (pre-existing) — rejected.
  - `[false]` `[reject]` (blind 13) phone lookup misses non-E.164 legacy rows — `/auth/login` uses the same exact lookup on `PhoneNumbers.normalize` (12.12 canonical form).
  - `[low]` `[reject]` (blind 15) `Instant.now()` in `newOtpAccount`/`insertIfAbsent` — milliseconds apart from the Clock; no behaviour depends on it.
  - `[low]` `[patch]` (blind 16) missing tests — repository SQL tests added; concurrency tests rejected (row lock / ON CONFLICT semantics covered by Postgres).
  - `[low]` `[reject]` (edge 3) review phone with a non-allowed prefix never works — operator configuration error, visible at first try.
  - `[low]` `[reject]` (edge 4) channel=WHATSAPP with no provider sends nothing — prod has a provider; local runs noop by rule.
  - `[low]` `[reject]` (edge 6) no SMS provider → ERROR log per request — ProdSecretsValidator warns at boot; ERROR is the right level for an undelivered code.
  - `[low]` `[reject]` (edge 7) valid code burned when USER_ALREADY_EXISTS after consume — needs a username/phone conflict the lookup cannot see; the user requests a new code.
  - `[low]` `[reject]` (edge 10) a refused request still uses in-memory quota — conservative, harmless.
  - `[low]` `[reject]` (edge 12) daily cap overshoot under concurrent wrong verifies — each challenge still capped at 5 under its row lock; overshoot bounded by live challenges (≤3/10 min).
  - `[low]` `[patch]` (vg 1) `sumFailuresSince` SQL never run against Postgres — repository test added (sum across challenges, old rows and other identifiers ignored).
  - `[low]` `[patch]` (vg 2) `insertIfAbsent` conflict path only on mocks — Postgres test with mixed-case email conflict.
  - `[low]` `[patch]` (vg 3) purge SQL never run — Postgres test (old deleted, recent kept).
  - `[false]` `[reject]` (vg other) phone path only unit-tested — reviewer itself found no regression only a phone run would catch.
  - `[false]` `[reject]` (patterns checked-fine list) index use of `sumFailuresSince`/`lockLatestActive` — confirmed fine, no defect.
  - `[low]` `[reject]` (intent, timing) no timing test for anti-enumeration — no account lookup at all on request, identical by construction.
  - `[low]` `[reject]` (intent, mixed-case stored email) — covered by the new `insertIfAbsent` Postgres test and `lower()` lookup on `uq_users_email_lower`.
  - `[low]` `[reject]` (intent, per-IP full-context) full-context test rotates IPs — per-IP rule unit-tested; X-Forwarded-For handling is the pre-existing `clientIp` (tested in `AuthControllerClientIpTest`).
  - `[low]` `[reject]` (intent, review/daily cap not full-context) — unit-tested; repository SQL now on Postgres.
  - `[low]` `[reject]` (blind, create-company concurrency test) — `lockById` is a primary-key `FOR UPDATE`; concurrency test cost outweighs value.
  - `[low]` `[reject]` (blind, afterCommit rollback test) — sends now happen only after the TransactionTemplate returns; covered by the patched unit test.
  - `[low]` `[reject]` (blind, X-Forwarded-For per-IP) — same as above.

## Design Notes

**Patterns.** Façade use cases (`RequestOtpService`, `VerifyOtpService`, `CreateCompanyService`) like the existing auth services; Value Object `OtpIdentifier` for normalization; Strategy-by-channel is just the existing ports (`SmsSender`/`EmailSender`/`WhatsAppSender`) chosen by `OtpChannel`; Extract-Collaborator `OwnerTenantProvisioner` so `/register` and `create-company` share one provisioning path (DRY, one transaction semantics). Repository port + JDBC adapter for challenges (simple rows, `FOR UPDATE` needed — clearer in SQL than JPA).

**Why a hash via `PasswordEncoder`:** same as forgot-password (14.12); bcrypt cost is paid once per request and once per verify, both rate-limited. Brute force is bounded by 5 attempts per code and 20 failures per day, not by the hash.

**Why the response mirrors `/auth/login`:** the app already handles `loginToken + memberships` (auto-select with one, picker with several); an empty list means « create your company ». No direct tokens from verify, so one client path.

**Complexity (FUS-NFR11).** Request: O(1) in-memory limit checks, 1 indexed SUM (daily cap), 1 UPDATE (invalidate), 1 INSERT, then 1–2 provider HTTP calls outside any transaction. Verify: 1 indexed SUM, 1 `SELECT … FOR UPDATE LIMIT 1` on `(identifier, created_at DESC)`, 1 UPDATE, 1 user lookup (unique index), at most 1 INSERT + 1 UPDATE, 1 memberships query (existing JOIN). Create-company: 1 memberships query + existing provisioning. Cleanup: one `DELETE … WHERE created_at < ?`. Rate-limit memory bounded (100 000 entries per window). No loops over rows or tenants.

## Verification

**Commands:**
- `cd keevo/backend && mvn -o -q test -Dtest='Otp*Test,*Otp*Test,CreateCompany*Test,Registration*Test,AuthController*Test'` -- expected: green.
- Full suite package by package with the scratchpad `run-pkgs-guard.sh` (≥4 GB free) -- expected: 0 failures.

**Manual checks:**
- Local backend (WhatsApp noop, FCM off, Expo off) with `KEEVO_OTP_REVIEW_IDENTIFIER=review@brefpoint.test KEEVO_OTP_REVIEW_CODE=246810`: request → 200; verify → empty memberships; create-company → 201; `/auth/profile` 200; `+33…` → 422; 4 requests on another identifier → 429; DB row in `public.otp_challenges` has a bcrypt hash only.

## Auto Run Result

Status: done

**Summary.** Bref's OTP sign-in ported to `identity/auth`: `POST /api/v1/auth/otp/request` (6-digit code, bcrypt hash, 5 min, SMS + WhatsApp by default for a phone, email for an email, Bref country list), `POST /api/v1/auth/otp/verify` (same body as `/auth/login`; unknown identifier → account without company, verified date set) and `POST /api/v1/auth/create-company` (tenant provisioned like `/auth/register` from a `loginToken`). Limits: 3/10 min and 5/h per identifier, 10/h per IP on request, 30/h per IP on verify, 5 attempts per code, 20 failures per 24 h per identifier (database). Store-review account (env, off by default). Routed 19-1 item done (`findByEmail` on `lower(email)`, `USER_ALREADY_EXISTS` on an unresolved conflict).

**Files.** New: `V45__otp_challenges.sql`; domain `OtpIdentifier`, `OtpChannel`, `OtpChallenge`; ports `RequestOtp*`, `VerifyOtp*`, `CreateCompany*`, `OtpChallengeRepository`; `JdbcOtpChallengeRepository`; services `RequestOtpService`, `VerifyOtpService`, `CreateCompanyService`, `OwnerTenantProvisioner` (extracted from `RegistrationService`), `LoginTokens` (shared with `SelectTenantService`), `OtpSettings`, `OtpRateLimiter`, `OtpMessages`; 4 DTOs. Changed: `AuthController`, `RegistrationService`, `SelectTenantService`, `User` (`newOtpAccount`), `UserRepository` + adapter (`findByEmail`, `insertIfAbsent`, `lockById`), `WhatsAppSender` (`sendLoginCode`), `ErrorCode`/`ErrorMessages`/`GlobalExceptionHandler` (5 codes), `SecurityConfig`, `AuthTokenCleanupJob`, `application.yml`, `.env.example`, `docs/entitlements/endpoints.md`, `TokenClaimsGuardTest` allowlist. Tests: 9 new classes incl. `OtpSignInFullContextTest` and `OtpPersistencePostgresIntegrationTest`.

**Review.** 44 findings (5 layers): 0 high, 5 medium, 31 low, 8 false. Patched: 5 medium (hash before SQL + sends outside any transaction/connection; per-IP verify limit closing review-code brute force; SUPER_ADMIN refused on OTP; create-company refused for a non-OWNER global role; repository SQL on Postgres) and 6 low (purge index, limiter Javadoc, WhatsApp log wording, 3 Postgres tests). Deferred: 1 → 19.6 (employee creating own company). Rejected: 33, each with its reason in the triage log.

**Follow-up review: recommended (true)** — 5 medium patches; the unverified risk is the reworked request path (TransactionTemplate then sends) and the new per-IP verify limit, never exercised with real providers or behind the prod proxy (X-Forwarded-For).

**Verification.** Targeted command green (≈120 tests). Full suite package by package: 5 273 tests, 0 failures (6 packages re-run after a low-memory skip caused by another session). Live `keevo_dev` (WhatsApp noop, FCM/Expo off, review account): request 200 `{expiresIn:300}` for review/unknown email/`00237…`; `+33` 422 `PHONE_COUNTRY_NOT_SUPPORTED`; `abc` and email+SMS 422 `VALIDATION_FAILED`; 4th request 429; wrong code 401 `OTP_INVALID` with `attempts=1` in DB; review verify → `loginToken`; create-company 201 `KV-2P4RD4`; `/auth/profile` 200; second create-company 409 `COMPANY_ALREADY_EXISTS`; DB holds `$2a$12$` hashes only; no code in the log.

**Residual risks.** In-memory limits per instance (documented). Real SMS/WhatsApp/email delivery checked only at the end-of-epic device test. WhatsApp still goes through Wassender/Twilio text (Meta templates in 19-9b).

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit c84780f4). Conventions : spec-16-11 ; protocole de synchro : spec-16-10.

**Ce qui est fait côté API :** connexion par code (SMS, WhatsApp, email) comme dans Bref, compte créé automatiquement pour un identifiant inconnu, création d'entreprise depuis le jeton de connexion.

**À utiliser :**
1. `POST /api/v1/auth/otp/request` — body `{"identifier":"+237699001122" | "ana@ex.com", "channel":"SMS"|"WHATSAPP"|"EMAIL" (optionnel)}`, en-tête `Accept-Language` (`en…` → textes anglais, sinon français). Sans `channel`, un téléphone reçoit le code par SMS **et** WhatsApp. Réponse `200 {"expiresIn":300}`, identique que le compte existe ou non. Erreurs : 422 `PHONE_COUNTRY_NOT_SUPPORTED` « Ce pays n'est pas encore pris en charge » (pays hors CM, GA, TD, CD, SN) ; 422 `VALIDATION_FAILED` « Données invalides » (identifiant mal formé, ou canal SMS/WhatsApp avec un email) ; 429 `OTP_TOO_MANY_REQUESTS` « Trop de demandes de code. Réessayez plus tard » (3 par 10 min et 5 par heure par identifiant, 10 par heure par appareil/IP, ou 20 codes faux en 24 h).
2. `POST /api/v1/auth/otp/verify` — body `{"identifier":"…","code":"123456"}`. Réponse `200` identique à `/auth/login` : `{"loginToken":"…","memberships":[{tenantCode,tenantName,role,schemaName}]}`. Erreurs : 401 `OTP_INVALID` « Code incorrect ou expiré » ; 429 `OTP_TOO_MANY_ATTEMPTS` « Trop d'essais. Demandez un nouveau code » (5e code faux : le code est mort) ; 429 `OTP_TOO_MANY_REQUESTS` (30 vérifications par heure par IP, ou 20 échecs en 24 h).
3. `memberships` avec 1 entreprise → appeler `POST /api/v1/auth/select-tenant` (comme aujourd'hui) ; plusieurs → sélecteur ; **vide** → écran de création d'entreprise puis `POST /api/v1/auth/create-company` body `{"loginToken":"…","deviceId":"…","deviceLabel":"…"}` → `201 {"tenantCode","token","userId","tenantId","refreshToken"}` (même forme que `/auth/register`, essai du plan le plus complet). Erreurs : 409 `COMPANY_ALREADY_EXISTS` « Vous avez déjà une entreprise » (refaire la connexion et choisir l'entreprise) ; 401 `TOKEN_EXPIRED` / `TOKEN_INVALID` (le `loginToken` dure 5 min : redemander un code).
4. Ensuite, l'onboarding existant (`POST /api/v1/onboarding/complete`) avec le jeton d'accès.

Code backend : `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/in/rest/AuthController.java` (endpoints), `application/service/RequestOtpService.java`, `VerifyOtpService.java`, `CreateCompanyService.java`, `domain/model/OtpIdentifier.java` (normalisation).

**À implémenter côté mobile :**
1. Écran « Recevoir un code » (téléphone ou email), choix du canal optionnel (SMS / WhatsApp pour un téléphone), compte à rebours de 5 min et bouton « Renvoyer le code » désactivé 60 s.
2. Écran de saisie du code à 6 chiffres ; afficher le message `error` de l'API tel quel (français) ; après `OTP_TOO_MANY_ATTEMPTS`, revenir à l'écran de demande.
3. Après vérification, suivre la logique `memberships` ci-dessus ; liste vide → création d'entreprise (nom, secteur, boutique selon l'onboarding Bref), parcours DOCUMENT par défaut quand 19.12 sera livrée.
4. Normaliser l'affichage du numéro (indicatif du pays choisi) ; ne jamais afficher de jeton, d'identifiant technique ni de code d'erreur brut.
5. Hors ligne : pas de demande de code ; message « Connexion Internet requise pour recevoir un code ».

**Implémentation de référence :** aucune dans `keevo/app` (Flutter V1 n'utilise pas l'OTP). Référence Bref : écrans de connexion OTP de `AI/bref`.

**Critères d'acceptation mobile / scénarios de test :**
- Given un numéro camerounais, When l'utilisateur demande un code puis saisit le bon code, Then il arrive sur l'accueil de son entreprise (ou sur la création d'entreprise s'il est nouveau).
- Given un numéro `+33…`, When il demande un code, Then « Ce pays n'est pas encore pris en charge » s'affiche.
- Given 5 codes faux, Then « Trop d'essais. Demandez un nouveau code » et retour à la demande.
- Mise en place locale : backend avec `KEEVO_OTP_REVIEW_IDENTIFIER=review@brefpoint.test KEEVO_OTP_REVIEW_CODE=246810`, puis `curl -X POST localhost:4500/api/v1/auth/otp/verify -H 'Content-Type: application/json' -d '{"identifier":"review@brefpoint.test","code":"246810"}'`.

**Clé de suivi mobile :** `m19-2-authenticator-otp`.

