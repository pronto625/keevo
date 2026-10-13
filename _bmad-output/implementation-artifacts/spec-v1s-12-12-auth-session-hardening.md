---
title: 'v1s-12-12 — Auth & session hardening (JWT filter, revocation cache, password reset, device ownership on sync)'
type: 'bugfix'
created: '2026-10-11'
status: 'done'
baseline_revision: 'efbbf81dcf4a9f152dd1fc8d35b556be9552f3d8'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
warnings: [oversized, multiple-goals]
deferred:
  - summary: >-
      Auth/session concurrency (attempt cap, consume-once, guarded device upsert) is only tested sequentially.
    evidence: |-
      AuthSessionPostgresIntegrationTest calls tryClaimAttempt/markConsumed/upsert one after another; the guarantees rest on single-statement conditional UPDATEs (row lock). Routed to v1s-15-11 (multi-connection Testcontainers tests).
    location: >-
      keevo/backend/src/test/java/com/keevo/identity/auth/adapter/out/persistence/AuthSessionPostgresIntegrationTest.java
    severity: low
  - summary: >-
      No running context-load test resolves the new Clock bean used by three auth components.
    evidence: |-
      Only @SpringBootTest classes (SectorTemplateFactoryTest, FlywayBaselineIntegrationTest) fail in the known baseline (localhost:5444 refused); unit tests build the components by hand. Routed to v1s-15-11.
    location: >-
      keevo/backend/src/main/java/com/keevo/shared/infrastructure/config/ClockConfig.java
    severity: low
---

<intent-contract>

## Intent

**Problem:** 24 review findings (12.2 / 12.3 / 12.5 / 13.2 / 14.5 / 14.11 / 14.12 / UI-3) left the auth and session seams soft: an unbounded, race-prone revocation cache with an untestable TTL; a forgot-password flow that holds a DB connection during the WhatsApp call, lets concurrent guesses exceed the 5-attempt cap, never purges tokens, keeps PII in an event and never evicts rate-limit buckets; no JWT clock-skew policy; HTML 403s; a SQL error swallowed inside the login transaction; unpurged refresh tokens; a TOCTOU on device ownership with no security log; phones stored with or without `+`; no length cap on password DTOs (bcrypt); and a SUSPENDED tenant that becomes ACTIVE by requesting then cancelling its deletion.

**Approach:** Fix each finding at its seam with the smallest change that closes it, reusing existing patterns (afterCommit WhatsApp from `AccountDeletionService`, atomic compare-and-set UPDATEs like `SaleSpringRepository.updateStatusIfCurrent`, scheduled cleanup like `SyncErrorLogCleanupJob`, the `Clock` bean, `NativeSqlPostgresIntegrationTest` for SQL). Items already closed by earlier code or not worth the change are ticked as "rejected" with the reason in the epic brief.

## Boundaries & Constraints

**Always:** keep the existing HTTP contracts (`{"domainCode": ...}` from `JwtAuthFilter`, `ApiResponseWrapper` elsewhere); anti-enumeration timing of forgot-password unchanged (the OTP send still runs before the HTTP response, just after commit); NFR12 (≤5 min cross-instance revocation) unchanged; revocation stays fail-open on DB errors (12.2 decision); every new SQL uses fully-qualified `public.` names; new Flyway migration = `V10__...`; inject `java.time.Clock` (existing bean) wherever time drives a test.

**Never:** no Redis / distributed cache; no new auth endpoints; no app (Flutter) change; no change to token formats or TTLs; do not merge the EMPLOYEE employees query with the revocation lookup (see Design Notes); no destructive data migration (phone normalisation skips rows that would collide).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Future iat | access token with `iat` > now + 60 s | 401 `UNAUTHORIZED` | JwtException path |
| Small skew | token expired 30 s ago / iat 30 s ahead | accepted (60 s skew) | — |
| Bad tenantId, OWNER | OWNER token, `tenantId = "x; drop"` | 401 `TOKEN_INVALID`, no SQL | — |
| Employee query DB error | `queryForMap` throws DataAccessException | 500 JSON `{"domainCode":"INTERNAL_ERROR"}`, logged | no stack to client |
| Access denied | authenticated EMPLOYEE on `/api/v1/admin/**` | 403 JSON ApiResponseWrapper `FORBIDDEN` | — |
| Concurrent wrong OTP | 10 parallel wrong codes on one token | attempts never exceeds 5; extra → `CODE_LOCKED` | atomic UPDATE |
| Double reset | 2 parallel correct codes | one succeeds, other `INVALID_OR_EXPIRED_CODE` | CAS on consumed_at |
| Forgot-password commit | user exists | token committed, then OTP sent; WhatsApp failure logged, request still 200 | afterCommit try/catch |
| Phone variants | `237699…` vs `+237699…` | same user (login, register uniqueness, reset, employee create/update, rate-limit key) | — |
| Suspended → delete | tenant SUSPENDED, POST `/account/delete` | 403 `ACCOUNT_SUSPENDED`, status unchanged | DomainException |
| Device owned by other user | push/pull with foreign deviceId | 403 `DEVICE_ID_MISMATCH` + WARN security log (actor, owner, tenant, path) | — |
| Device race | owner row inserted between check and upsert | upsert does not change `user_id`; WARN logged | guarded ON CONFLICT |
| tenant_preferences missing | schema without the table, inside login tx | `onboardingCompleted=false`, no SQL error, refresh token saved | to_regclass probe |
| Oversized password | 73+ chars on register/login/change/set/reset | 400 `VALIDATION_FAILED` (never 500 from bcrypt) | — |

</intent-contract>

## Code Map

- `keevo/backend/pom.xml` -- add `com.github.ben-manes.caffeine:caffeine` (version managed by Boot 3.5).
- `.../identity/auth/adapter/out/persistence/impl/CachedTokenRevocationAdapter.java` -- ConcurrentHashMap cache L40, get L47-57 (non-atomic, no bound), unused logger L32. `JdbcTokenRevocationAdapter` (delegate, fail-open, returns null = never revoked).
- `.../shared/infrastructure/security/JwtAuthFilter.java` -- tenant validation for all roles already L142-148 (D3 closed by 12-5); revocation L159-166; EMPLOYEE `queryForMap ... LIMIT 1` L200 (D10: >1 row impossible, but DataAccessException uncaught → servlet 500).
- `.../shared/infrastructure/security/JwtTokenProvider.java` -- `parseToken()` L204: no clock skew, no iat check.
- `.../shared/infrastructure/persistence/TenantSchema.java` -- `validate()` puts the raw claim in the exception message.
- `.../shared/infrastructure/security/SecurityConfig.java` -- `accessDeniedHandler` `res.sendError(403)`; `GlobalExceptionHandler.handleAccessDenied` L161 is the JSON shape to mirror.
- `.../identity/auth/application/service/RequestPasswordResetService.java` -- `@Transactional` L58, `sendOtp` L97 inside tx; event L100 carries phone.
- `.../identity/auth/domain/model/PasswordResetRequestedEvent.java` -- drop `phoneNumber`; consumer `AuditEventListener.on(PasswordResetRequestedEvent)` L896 uses only userId; test `AuditEventListenerTest` L334.
- `.../identity/auth/application/service/PasswordResetRateLimiter.java` -- two unbounded maps L30-33, `System.currentTimeMillis`.
- `.../identity/auth/application/service/ResetPasswordService.java` -- read-modify-write attempts L94-97, consume via `save` L118-119, length check `> 128` L105 (bcrypt in Spring Security 6.5 throws above 72 bytes).
- `.../identity/auth/domain/port/out/PasswordResetTokenRepository.java`, `.../adapter/out/persistence/impl/PasswordResetTokenRepositoryAdapter.java`, `.../adapter/out/persistence/jpa/PasswordResetTokenSpringRepository.java` -- add atomic attempt claim, CAS consume, purge.
- `.../identity/auth/domain/port/out/RefreshTokenRepository.java`, `RefreshTokenRepositoryAdapter.java`, `jpa/RefreshTokenSpringRepository.java` -- add purge of expired rows.
- `keevo/backend/src/main/resources/db/migration/` -- V1..V9 exist; V6 (reset tokens: phone+created idx, user_id idx), V8 (refresh rotation columns), refresh_tokens has only PK + UNIQUE(token_hash). `users.phone_number` UNIQUE `uk_users_phone_number`.
- `.../sync/sync/application/service/SyncErrorLogCleanupJob.java` -- daily cleanup pattern to mirror.
- `.../identity/auth/application/service/SessionTokenIssuer.java` -- `onboardingCompleted()` L91-99 catches the SQL error inside the caller tx (Postgres aborts it). Tests: `SessionTokenIssuerTest` L53/L86 mock `contains("tenant_preferences")`, `SelectTenantServiceTest` L70.
- `.../sync/sync/adapter/in/rest/SyncController.java` -- `verifyDeviceOwnership` L75, push L106-135, pull L163-169. `UserSyncStateRepository` + `UserSyncStateRepositoryAdapter` (UPSERT / UPSERT_ON_PULL, `device_id` is the PK).
- Phone entry points: `AuthenticationService` L60, `RegistrationService` L66/L80, `CreateEmployeeService` L68/L83, `UpdateEmployeeService` L79-90, `RequestPasswordResetService` L60, `ResetPasswordService` L77. App sends `+<dial><national>`.
- Password DTOs: `identity/auth/adapter/in/rest/dto/{RegistrationRequest,LoginRequest,ChangePasswordRequestDto,ResetPasswordRequestDto}.java`, `identity/employee/adapter/in/rest/dto/SetPasswordRequestDto.java`.
- `.../identity/auth/application/service/AccountDeletionService.java` -- `requestDeletion()` L62 accepts a SUSPENDED tenant; `cancelDeletion()` L116 restores ACTIVE. `ErrorCode.ACCOUNT_SUSPENDED` → 403 already mapped.
- Test pattern for real SQL: `keevo/backend/src/test/java/com/keevo/shared/infrastructure/persistence/NativeSqlPostgresIntegrationTest.java` (`@DataJpaTest` + Testcontainers postgres:16-alpine, Flyway migrated).

## Tasks & Acceptance

**Execution:**
- `pom.xml` -- add Caffeine dependency.
- `CachedTokenRevocationAdapter.java` -- Caffeine `Cache<String, Optional<Instant>>` (`expireAfterWrite` 300 s, `maximumSize` 100 000, ticker from injected `Clock`); `get(key, loader)` (atomic, one load per key under concurrency); invalidate on revoke immediately AND again `afterCommit` when a transaction synchronization is active; debug logs on miss/invalidate. Constructor `(JdbcTokenRevocationAdapter, Clock)`.
- `CachedTokenRevocationAdapterTest.java` -- mutable test Clock: same key hit before TTL, reload after advancing past TTL; parallel misses → delegate called once; null cutoff cached; revoke inside a synchronization invalidates again after commit; revokeAllSessionsEverywhere invalidates every tenant of the user.
- `JwtTokenProvider.java` -- `clockSkewSeconds(60)`; reject `iat` later than now + 60 s with a `JwtException` subclass. Tests in `JwtTokenProviderTest`.
- `JwtAuthFilter.java` -- catch `DataAccessException` on the EMPLOYEE query → log error, 500 `INTERNAL_ERROR` JSON. `JwtAuthFilterTest`: OWNER invalid tenantId → 401 no SQL; DB error → 500 JSON.
- `TenantSchema.java` -- exception message without the raw value (length only); adjust any test asserting it.
- `SecurityConfig.java` -- inject `ObjectMapper`; accessDeniedHandler writes JSON `ApiResponseWrapper.error("Vous n'avez pas les droits nécessaires", "FORBIDDEN", "FORBIDDEN", null)` with status 403. Test in `SecurityConfigAdminPathEnforcementTest` (body is JSON with domainCode FORBIDDEN).
- `shared/domain/PhoneNumbers.java` (new) -- `normalize(String)`: trim, drop spaces/`-`/`.`/`(`/`)`, prepend `+` if missing; null stays null. Unit test. Apply at the phone entry points listed in the Code Map (local variable, before lookups/uniqueness/persist) and as the rate-limiter key.
- `PasswordResetRequestedEvent.java` + `RequestPasswordResetService.java` + `AuditEventListenerTest.java` -- event = `(userId, occurredAt)`; OTP sent in `afterCommit` (try/catch, error logged without phone), direct send when no synchronization is active. Update `RequestPasswordResetServiceTest`.
- `PasswordResetRateLimiter.java` -- Caffeine caches per window (`expireAfterWrite` = window, `maximumSize` 100 000, ticker from Clock); bucket counts within its life. Spring constructor takes `Clock`. `PasswordResetRateLimiterTest` covers window reset via clock and normalised key.
- Reset-token repository (port + adapter + Spring repo) -- `boolean tryClaimAttempt(UUID id, int maxAttempts)` (`attempts = attempts + 1 WHERE id AND consumed_at IS NULL AND attempts < max`), `boolean markConsumed(UUID id, Instant at)` (`WHERE consumed_at IS NULL`), `int purgeExpiredBefore(Instant cutoff)`.
- `ResetPasswordService.java` -- after expiry/lock checks, claim an attempt atomically BEFORE bcrypt compare (0 rows → `CODE_LOCKED`); mismatch → `INVALID_OR_EXPIRED_CODE` (claim already committed, `noRollbackFor` kept); success path consumes via `markConsumed` (false → `INVALID_OR_EXPIRED_CODE`) before any password write; password rule 8..72 UTF-8 bytes + one digit. Update `ResetPasswordServiceTest`.
- Refresh-token repository (port + adapter + Spring repo) -- `int purgeExpiredBefore(Instant cutoff)`.
- `identity/auth/application/service/AuthTokenCleanupJob.java` (new) -- `@Scheduled(cron = "0 30 3 * * *")`, purges reset tokens and refresh tokens whose `expires_at` < now − 1 day (Clock); logs counts; one failure does not skip the other. Unit test.
- `db/migration/V10__auth_token_hygiene.sql` -- partial index reset tokens `(phone_number, created_at DESC) WHERE consumed_at IS NULL`; index reset tokens `(expires_at)`; refresh_tokens indexes `(user_id)`, `(replaced_by_id) WHERE replaced_by_id IS NOT NULL`, `(expires_at)`; prefix `+` on `public.users.phone_number` and `public.password_reset_tokens.phone_number` lacking it, skipping users whose `+` form already exists.
- `SessionTokenIssuer.java` -- probe `SELECT to_regclass(?) IS NOT NULL` with `"schema".tenant_preferences` before the EXISTS query; no catch of SQL errors inside the caller tx. Update `SessionTokenIssuerTest` / `SelectTenantServiceTest` mocks.
- `UserSyncStateRepository` + adapter -- `upsert`/`upsertOnPull` return `boolean` (row written); `ON CONFLICT (device_id) DO UPDATE ... WHERE public.user_sync_state.user_id = EXCLUDED.user_id`.
- `SyncController.java` -- WARN security log on `DEVICE_ID_MISMATCH` (actorId, ownerId, tenantId, deviceId, endpoint); WARN when a guarded upsert writes nothing. Tests in `SyncControllerTest`/`SyncControllerPullGateTest`.
- Login/registration/employee DTOs -- `@Size(max = 72)` on every password field (`min = 8` where a new password is set).
- `AccountDeletionService.java` -- `requestDeletion` on a SUSPENDED tenant → `DomainException(ACCOUNT_SUSPENDED)`. Test in `AccountDeletionServiceTest`.
- `keevo/backend/src/test/java/com/keevo/identity/auth/adapter/out/persistence/AuthSessionPostgresIntegrationTest.java` (new, pattern of `NativeSqlPostgresIntegrationTest`) -- attempt claim stops at 5, consume CAS once, purge queries, guarded device upsert, V10 phone normalisation and collision skip.
- `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md` -- tick all 24 items of Story 12.12 with the decision.

**Acceptance Criteria:**
- Given any of the 24 Story 12.12 items, when the story is done, then its box is ticked with either the fix or an explicit rejection reason.
- Given the backend suite, when run, then no new failure/error beyond the known baseline (7 failures + 29 errors in 10 persistence classes) and all touched test classes pass.
- Given a revoked user on this instance, when they call the API with an older token right after the revoking transaction commits, then they get 401 `SESSION_REVOKED` (no stale cache re-population).

## Spec Change Log

## Review Triage Log

### 2026-10-11 — Review pass
- verdicts: 44 findings — high 0, medium 12, low 25, false 7, maybe-false 0
- findings:
  - `[false]` `[reject]` (intent) remedies differ from the brief's wording (72 vs 128, atomic UPDATE, PK + guarded ON CONFLICT, two rejections) — each alternative is recorded with its reason in the epic tick; the AC accepts "resolved or rejected with reason".
  - `[low]` `[patch]` (intent) epic-12-context 12.12 bullets contradicted the decisions (merge, no disclosure, 8–128, E.164) — context bullets aligned with what was done.
  - `[low]` `[patch]` (intent) '+<digits>' is not full E.164 validation — CreateEmployeeService now validates `^\+?[0-9]{8,15}$` like update/register; dial code comes from the app, country validation out of scope.
  - `[low]` `[defer]` (intent) concurrency items tested sequentially — routed to v1s-15-11.
  - `[low]` `[reject]` (intent) no end-to-end filter test for post-commit invalidation — covered at adapter level incl. in-flight load test added by patch; filter only calls the port.
  - `[medium]` `[patch]` (intent) to_regclass only tested on mocks — real-Postgres cases added (missing table + follow-up write, existing table).
  - `[low]` `[patch]` (intent) raw client deviceId in SECURITY logs — `sanitizeForLog` (control chars → '_', cap 64).
  - `[low]` `[reject]` (intent) Clock not injected in iat check / ResetPasswordService — wall-clock offsets suffice for those tests; injection there adds surface for no defect.
  - `[false]` `[reject]` (intent) JSON 403 / suspended deletion — auditor found intent and test surfaces aligned.
  - `[low]` `[reject]` (intent) ticks reference "(v1s-12-12)" not a commit hash — commit SHA recorded in sprint-status at finalize.
  - `[medium]` `[patch]` (blind) 72 cap counted chars, multi-byte password → BCrypt IAE → 500 — `PasswordLimits.exceedsBcryptLimit` (UTF-8 bytes) checked before any BCrypt call in register/login/change/set/reset; tests with 41×"é".
  - `[medium]` `[patch]` (blind) correct code + weak password burned attempts — new-password rule moved before `tryClaimAttempt`; test.
  - `[low]` `[patch]` (blind) AdminAccountInitializer used raw ADMIN_PHONE — normalised before lookup/create.
  - `[low]` `[reject]` (blind) V10 skipped/space-formatted rows unreachable — a collision needs both forms registered (entry points enforced digit patterns); spaced phones could never log in before either (LoginRequest E.164 pattern); documented as manual merge.
  - `[low]` `[patch]` (blind) normalize("abc") → "+abc" on employee create — create now validates the phone pattern; "00" prefix rejected as cosmetic (app always sends '+').
  - `[medium]` `[patch]` (blind) revoke-everywhere prefix scan skipped in-flight loads — per-user key index + per-key `invalidate` (waits for in-flight load), now and after commit; test.
  - `[medium]` `[patch]` (blind) DB failure cached as "never revoked" for 5 min — loader uses throwing `loadTokensValidAfter`, failure logged and not cached; test.
  - `[low]` `[reject]` (blind) Clock only partly injected / isTokenExpired change — same as intent row; no defect.
  - `[low]` `[patch]` (blind) `@ConditionalOnMissingBean` on user @Configuration — condition removed.
  - `[low]` `[defer]` (blind) concurrency tested sequentially — grouped with intent row, routed to v1s-15-11.
  - `[low]` `[patch]` (blind) "OTP sent" logged before the afterCommit send — logged inside the runnable after success.
  - `[low]` `[patch]` (blind) cleanup job / filter error logs dropped stack trace — exception passed as last argument (WhatsApp failure log keeps message only to avoid phone PII).
  - `[false]` `[reject]` (blind) second tap spends an hourly slot — identical to the pre-existing WindowBucket semantics (both windows counted every request).
  - `[false]` `[reject]` (blind) null phones share a "null" bucket — DTOs are @NotBlank + @Pattern; null never reaches the limiter.
  - `[false]` `[reject]` (blind) exception after markConsumed burns token (user not found) — `password_reset_tokens.user_id` FK to users; the user always exists.
  - `[low]` `[reject]` (blind) empty 401 entry-point body / hard-coded French 403 text — JwtAuthFilter answers JSON 401 on every protected path before the entry point; text equals FR_MESSAGES["FORBIDDEN"].
  - `[low]` `[patch]` (blind) malformed bold marker on D3 tick + context status — markdown fixed; statuses updated at finalize.
  - `[medium]` `[patch]` (edge) multi-byte password 500 — grouped with blind row (PasswordLimits).
  - `[medium]` `[patch]` (edge) weak password burns attempts — grouped with blind row.
  - `[low]` `[reject]` (edge) claim refused on concurrently consumed token reports CODE_LOCKED — only on a race with a successful reset/new request; message-only difference.
  - `[low]` `[reject]` (edge) >100k distinct phones/hour could evict a live bucket — W-TinyLFU keeps frequently hit keys; per-IP limiting is outside the item (unbounded growth was the finding).
  - `[low]` `[reject]` (edge) wall-clock ticker vs NTP step back — steps are rare, worst case extends a TTL by the step size; tests need the Clock-driven ticker.
  - `[false]` `[reject]` (edge) push ops applied before race detected — ops are authorised by the actor's JWT/tenant; the device row is bookkeeping and is not reassigned.
  - `[false]` `[reject]` (edge) pull returns data after race — pull is scoped by the JWT actor/tenant, not the device.
  - `[low]` `[patch]` (edge) accessDeniedHandler on committed response — early return when `res.isCommitted()`.
  - `[low]` `[patch]` (edge) ClockConfig condition — grouped with blind row.
  - `[medium]` `[patch]` (edge) in-flight load vs revoke-everywhere — grouped with blind row.
  - `[medium]` `[patch]` (verification) unprefixed phone saved normalised on register untested — RegistrationServiceTest captures save → '+237600000001'.
  - `[medium]` `[patch]` (verification) employee-create normalisation untested — CreateEmployeeServiceTest cases (normalised uniqueness + save, invalid rejected).
  - `[medium]` `[patch]` (verification) to_regclass probe not run on Postgres — grouped with intent row (real-PG cases).
  - `[low]` `[defer]` (verification) no running context-load test for the Clock bean — baseline @SpringBootTest classes fail on env; routed to v1s-15-11.
  - `[medium]` `[patch]` (verification) weak password burns attempts — grouped with blind row.
  - `[low]` `[patch]` (verification) admin tenant search not normalised — `owner.phone_number IN (raw, normalised)`; AdminTenantServiceTest asserts params.
  - `[low]` `[reject]` (verification) sync gate tests log a race WARN from Mockito's default `false` — test-log noise only, no behaviour.

### 2026-10-13 — Review pass (follow-up)
- verdicts: 35 findings — high 0, medium 0, low 25, false 10, maybe-false 0
- findings:
  - `[false]` `[reject]` (intent) oversized password answers 422 `VALIDATION_ERROR`/`VALIDATION_FAILED` or 401, not 400 — carried: deviation accepted at first review (Design Notes), invariant "never 500" holds.
  - `[low]` `[defer]` (intent) "10 parallel / 2 parallel" rows tested sequentially — carried: routed to v1s-15-11.
  - `[low]` `[reject]` (intent) forgot-password afterCommit simulated by hand, no HTTP 200 test — afterCommit ordering is Spring-guaranteed; the unit test proves the send runs only in afterCommit and that a failure is swallowed.
  - `[low]` `[reject]` (intent) future-iat check not Clock-driven — carried: wall-clock offsets suffice, no defect.
  - `[low]` `[patch]` (intent) login phone normalisation untested — grouped with verification row; `AuthenticationServiceTest.should_normalise_unprefixed_phone_before_lookup` added.
  - `[low]` `[reject]` (intent) suspended-delete / employee DB error tested below the endpoint — the 403/500 mappings are the existing handler's, covered by `GlobalExceptionHandlerTest` / `JwtAuthFilterTest`.
  - `[false]` `[reject]` (intent) diff bundles other stories' changes — deliberate for the follow-up: diff = baseline→current code on the story's files; those hunks belong to v1s-13-9/15-9/18-1/18-6, reviewed in their own passes.
  - `[false]` `[reject]` (intent) `ClockConfig` created although the intent says "existing bean"; extra additions (employee phone pattern, admin search, TenantSchema message) — carried: recorded in Design Notes / first-pass patches.
  - `[false]` `[reject]` (blind) diff base looks wrong — same as intent row above.
  - `[low]` `[reject]` (blind) V10 skipped colliding rows become unreachable — carried: collision needs both forms registered; documented manual merge.
  - `[low]` `[reject]` (blind) `normalize` turns `00237…`/`0699…` into `+00…`/`+0…` — carried: the app always sends `+<dial><national>`; cosmetic for hand-crafted calls.
  - `[low]` `[reject]` (blind) login normalisation dead behind `LoginRequest` E.164 pattern; CreateEmployee checks the raw pattern before normalising — defensive only, no user-visible harm (the app never sends spaces).
  - `[low]` `[reject]` (blind) Caffeine tickers use wall-clock `clock.millis()` — carried: NTP steps rare, worst case extends a TTL by the step.
  - `[low]` `[reject]` (blind) `JwtTokenProvider` ignores the Clock bean — carried (same as intent row).
  - `[false]` `[reject]` (blind) no negative test for the 60 s skew past `exp` — `JwtTokenProviderTest` "parseToken throws ExpiredJwtException for token expired beyond the 60 s skew" exists.
  - `[low]` `[reject]` (blind) `SECURITY` logger has no dedicated appender — it logs at WARN through the root appender, so probing is visible in production logs; a separate appender is ops configuration, not a defect.
  - `[low]` `[reject]` (blind) 401 entry point empty body / 403 body untested / duplicated French text — carried for 401 and text; the 403 JSON body is asserted by `SecurityConfigAdminPathEnforcementTest`.
  - `[low]` `[reject]` (blind) rate limiter per instance; null phone shares a bucket — single backend instance today (ShedLock is a guard, not a scale-out); null is carried false (DTO @NotBlank).
  - `[false]` `[reject]` (blind) hourly bucket counts minute-rejected taps — carried: identical to the pre-existing semantics.
  - `[false]` `[reject]` (blind) `OWNER_GUARD` does not prevent a push applied under a foreign device — carried: ops are authorised by the actor's JWT/tenant; the device row is bookkeeping.
  - `[low]` `[reject]` (blind) forgot-password timing still differs (WhatsApp call on the request thread) — pre-existing; the intent explicitly keeps the timing unchanged.
  - `[low]` `[patch]` (blind) `AdminAccountInitializer` normalisation untested — grouped with verification row; `unprefixedAdminPhoneIsNormalised` added.
  - `[low]` `[patch]` (verification) login normalisation regression gap — test added (unprefixed + spaced input → lookup on `+22670000001`).
  - `[low]` `[patch]` (verification) admin phone normalisation regression gap — test added (exact `findByPhoneNumber("+237600000000")`, saved phone canonical).
  - `[low]` `[reject]` (edge) wall-clock ticker vs NFR12 — carried.
  - `[low]` `[reject]` (edge) >100k phones evict a live bucket — carried.
  - `[false]` `[reject]` (edge) null phone bucket — carried.
  - `[low]` `[reject]` (edge) leading 0/00 stored as `+0…` — carried (same as blind row).
  - `[low]` `[reject]` (edge) V10 skipped rows silent — carried.
  - `[false]` `[reject]` (edge) `AdminTenantService.forceDelete` `queryForObject` throws when the row vanished — tenants are soft-deleted (status `DELETED`), the row always exists.
  - `[low]` `[reject]` (edge) SUSPENDED tenant force-deleted, routine fails, admin cancels → ACTIVE — SUPER_ADMIN-only explicit action (v1s-15-9 code), the admin can re-suspend; not reachable by the owner.
  - `[false]` `[reject]` (edge) push race applies the batch — carried.
  - `[low]` `[patch]` (edge) OTP send failure logs `e.getMessage()`, which provider errors (Twilio "The 'To' number +…") can fill with the phone — now logs the exception class only.
  - `[low]` `[reject]` (edge) double-submit loser sees `CODE_LOCKED` — carried: message-only difference on a race.
  - `[low]` `[reject]` (edge) Clock-driven TTL claim vs non-monotonic ticker — carried (grouped with ticker row).

## Design Notes

- **Password cap = 72, not 128:** Spring Security 6.5 `BCrypt.hashpw` throws `IllegalArgumentException("password cannot be more than 72 bytes")`; a 128 cap would still 500. DTOs cap chars at 72; `ResetPasswordService` checks UTF-8 bytes.
- **Suspended → deletion (PO decision):** forbid the request (403 `ACCOUNT_SUSPENDED`). The JWT filter already blocks writes for tokens carrying SUSPENDED; this closes the stale-token path without a new column. Erasure of a suspended tenant stays available through SUPER_ADMIN `forceDelete`.
- **OWNER revocation round-trip (rejected as described):** the Caffeine cache gives one coalesced DB load per (user, tenant) per 5 min; merging it into the EMPLOYEE `employees` query would drop the fail-open policy and the cache for EMPLOYEE and couple a tenant table with `public`.
- **Device enumeration (rejected):** device ids are client UUIDv4 (`rest_sync_service.dart:266`, 122 random bits); probing is infeasible, and a generic error would hide a real misconfiguration from the legitimate owner. The new security log makes probing visible.
- **D3 / D10:** D3 already fixed by 12-5 (`TenantSchema.validate` for all roles) — covered by a new OWNER test. D10: `LIMIT 1` + `UNIQUE(user_id, tenant_id)` make >1 row impossible; remaining DB errors now map to JSON 500.
- **Phone normalisation:** canonical form `+<digits>` (what the app sends). The V10 update skips a row whose `+` form already exists (no unique violation); such a pair needs a manual merge.

- **Deviations accepted at review:** DTO-level oversized passwords return the codebase's bean-validation response (422 `VALIDATION_ERROR`), service-level checks `VALIDATION_FAILED` — the matrix's "400" was a spec slip, the invariant (never 500) holds and is now byte-accurate via `shared/domain/PasswordLimits`. No `Clock` bean existed: `shared/infrastructure/config/ClockConfig` (systemUTC) added. `min = 8` stays in services for reset/set-password (existing 422 `VALIDATION_FAILED` contract).

## Verification

**Commands:**
- `cd keevo/backend && mvn -q test -Dtest='CachedTokenRevocationAdapterTest,JwtTokenProviderTest,JwtAuthFilterTest,SecurityConfigAdminPathEnforcementTest,PhoneNumbersTest,RequestPasswordResetServiceTest,ResetPasswordServiceTest,PasswordResetRateLimiterTest,AuthTokenCleanupJobTest,SessionTokenIssuerTest,SelectTenantServiceTest,SyncController*Test,AccountDeletionServiceTest,AuditEventListenerTest,AuthSessionPostgresIntegrationTest,UpdateEmployeeServiceTest,CreateEmployeeServiceTest,RegistrationServiceTest,AuthenticationServiceTest'` -- expected: all green.
- `cd keevo/backend && mvn test` -- expected: only the known baseline failures.

## Auto Run Result

Status: done

**Summary:** all 24 Story 12.12 items closed (21 fixed, 1 already closed by v1s-12-5 + new test, 2 rejected with reasons) — Caffeine revocation cache (bounded, Clock-driven, coalesced loads, per-key + post-commit invalidation, failures not cached), JWT 60 s skew + future-iat rejection, JSON 403 / JSON 500 in the filter chain, sanitised TenantSchema message, OTP after commit, atomic attempt claim + CAS consume, bounded rate limiter, PII-free event, daily token purge + V10 indexes and phone backfill, to_regclass probe, guarded device upsert + SECURITY logs, canonical '+<digits>' phones at every entry point, 72-byte password cap, SUSPENDED tenants cannot request deletion.

**Files:** backend only — `pom.xml` (Caffeine); identity/auth (CachedTokenRevocationAdapter, JdbcTokenRevocationAdapter, reset-token / refresh-token ports, adapters and Spring repos, RequestPasswordResetService, ResetPasswordService, PasswordResetRateLimiter, SessionTokenIssuer, AuthenticationService, RegistrationService, AccountDeletionService, new AuthTokenCleanupJob, PasswordResetRequestedEvent, DTOs); identity/employee (Create/Update/ChangePassword/SetEmployeePassword services, SetPasswordRequestDto); shared (PhoneNumbers, PasswordLimits, ClockConfig, TenantSchema, JwtAuthFilter, JwtTokenProvider, SecurityConfig, AdminAccountInitializer); admin (AdminTenantService search); sync (SyncController, UserSyncStateRepository + adapter); `V10__auth_token_hygiene.sql`; tests incl. new AuthSessionPostgresIntegrationTest, AuthTokenCleanupJobTest, PhoneNumbersTest, PasswordDtoSizeConstraintTest, MutableTestClock.

**Review:** 44 findings — 12 medium / 25 low / 7 false. Patched: 12 medium entries (grouped into 7 fixes) + 11 low. Deferred: 2 low → v1s-15-11 (parallel concurrency tests, Clock context-load). Rejected: see triage log with reasons.

**Follow-up review recommended: true** — more than two medium entries were patched after review; the specific unverified risk is the reworked revocation-cache invalidation (per-user key index + uncached failures), new code exercised only by adapter unit tests.

**Verification:** targeted classes green; full `mvn test`: 1997 tests, 7 failures + 29 errors, all in the 10 known baseline persistence classes (no new failure); AuthSessionPostgresIntegrationTest 10/10 on Testcontainers PostgreSQL 16.

**Residual risks:** V10 leaves colliding/space-formatted phones for manual merge; rate-limit buckets can be evicted under a >100k-number flood; cross-instance revocation still ≤5 min (NFR12).

### Follow-up review pass — 2026-10-13

**Summary:** fresh 4-layer review (blind, edge, verification-gap, intent) of baseline→current code on the story's files. Orchestrator-flagged risk (revocation-cache invalidation reworked after review, tested only at adapter level) is now covered at the filter surface: `JwtAuthFilterRevocationCacheTest` drives the real `JwtAuthFilter` over the real `CachedTokenRevocationAdapter` + `JdbcTokenRevocationAdapter` (only `JdbcTemplate` mocked): warm cache → revoke inside an open synchronization → concurrent request re-caches the old cutoff → commit → next request 401 `SESSION_REVOKED`. Mutation-checked: disabling the afterCommit invalidation makes it fail (200 instead of 401).

**Files changed:** `RequestPasswordResetService.java` (OTP failure log without provider message — PII); tests `AuthenticationServiceTest` (login normalisation), `AdminAccountInitializerTest` (unprefixed ADMIN_PHONE), new `JwtAuthFilterRevocationCacheTest`.

**Review findings:** 35 (0 high, 0 medium, 25 low, 10 false). Patched: 4 low entries (login normalisation test, admin normalisation test, OTP log PII; grouped rows share fixes). Deferred: 1 carried (concurrency → v1s-15-11). Rejected: see the 2026-10-13 triage entry, mostly carried from the first pass.

**Follow-up review recommended: false** — follow-up pass, no high patched (patched: 0 high, 0 medium, 3 low entries).

**Verification:** `JwtAuthFilterRevocationCacheTest, AuthenticationServiceTest, AdminAccountInitializerTest, RequestPasswordResetServiceTest, CachedTokenRevocationAdapterTest, JwtAuthFilterTest` — 66 tests green; batches `identity` 362 / 0 failure, `shared` 468 / 0 failure.

**Residual risks:** unchanged from the first pass (V10 manual merge, rate-limit eviction under flood, ≤5 min cross-instance revocation).
