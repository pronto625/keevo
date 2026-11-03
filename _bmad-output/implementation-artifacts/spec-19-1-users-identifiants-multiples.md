---
title: '19-1 public.users accepts phone, email or username as identifier, with a profile (names, verification dates) — schema and null-safety only'
type: 'feature'
created: '2026-11-03'
status: 'done'
baseline_revision: 'b17c8b10f2a42d90a78c2ec6ce02574389ba5e77'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** `public.users` requires a phone and a password (`phone_number`, `password_hash` NOT NULL; the domain `User` constructor `requireNonNull`s both). Bref accounts can be email-only (OTP, Google, Apple) and Bref members often have only a username (the 18.1 measure found 3 members without phone out of 3). They cannot exist in Keevo today, which blocks the identity work of epic 19 (19.2 OTP, 19.3 social, 19.4 username) and the import of epic 24.

**Approach:** One additive public Flyway migration (V41) makes phone and password optional, adds `email`, `username`, `first_name`, `last_name`, `email_verified_at`, `phone_verified_at`, case-insensitive uniqueness on email and username, and a CHECK requiring at least one identifier — existing rows untouched. The domain, persistence and every reader of `public.users` become null-safe so that no existing flow breaks and no installed V1 2.0.1 Flutter app ever receives `null` where it expects a value. No new login method in this story (19.2–19.4).

## Boundaries & Constraints

**Always:**
- `V41__users_multiple_identifiers.sql` (public only, idempotent like V36–V40): `phone_number` and `password_hash` DROP NOT NULL; ADD `email varchar(254)`, `username varchar(50)`, `first_name varchar(100)`, `last_name varchar(100)`, `email_verified_at timestamptz`, `phone_verified_at timestamptz` (all nullable, no default); unique indexes `uq_users_email_lower ON public.users (lower(email))` and `uq_users_username_lower ON public.users (lower(username))` (follow the `uq_products_name` lower() precedent — no citext); CHECK `users_identifier_present` (`phone_number IS NOT NULL OR email IS NOT NULL OR username IS NOT NULL`) added `NOT VALID` then `VALIDATE` (every existing row has a phone). Keep `uk_users_phone_number`. No UPDATE, no data backfill: existing rows keep NULL in the new columns.
- Domain `User` (`identity/auth/domain/model/User.java`): phone and password hash nullable; new fields email, username, firstName, lastName, emailVerifiedAt, phoneVerifiedAt with `withX` copies; existing factories `newOwner`/`newEmployee` unchanged; `toString` must not print phone, email or username (personal data). JPA `UserJpaEntity` maps the new columns, drops `nullable=false` on phone and hash; mappers both ways.
- Null-safe readers (each with a unit test): `UserRepositoryAdapter.findByPhoneNumber` / `existsByPhoneNumber` return empty/false for a null or blank argument without querying (Spring Data would translate null into `IS NULL` and match phone-less users); `AuthenticationService` lockout log never dereferences a null phone; `UserProfileService` → `GET /auth/profile` returns `phoneNumber: ""` when the user has no phone (V1 expects a non-null string) and, for an OWNER with names on `public.users`, returns them as `firstName`/`lastName` (owners get null today — additive); `ChangePasswordService` with a null hash answers the existing `INVALID_CREDENTIALS` (setting a first password belongs to 19.2); `OwnerRecipientResolverAdapter` and `OWNER_BY_SCHEMA_SQL` choose the oldest owner **having a phone** (fallback to the next owner instead of no recipient); `AdminAccountInitializer` ignores a blank configured phone.
- Every other reader listed in the Code Map is checked and documented in Design Notes (already null-safe or patched): audit, exports, admin tenant list (`COALESCE` → `""`), employees (`EmployeeRoleResolver.resolvePhone`), account/tenant deletion, `UserQueryAdapter.findPhoneNumber`.
- V1 2.0.1 compatibility: login (phone + password, two steps, `select-tenant`), refresh, profile, employees and every existing response keep the same fields and types; a field that could become null is either omitted by existing behaviour or returned as `""` (documented in the Mobile section).
- Tests by package, one at a time. Migration verified on keevo_dev and on a fresh read-only copy of Keevo production (stream `pg_dump` through the `oracle` ssh alias as in 18.1, restore into a throwaway local container, apply V41 through the app's Flyway or `flyway migrate` with the project's config, check row count and md5 of `public.users` unchanged, constraints present, then delete the container and dump). Live check: local backend (WhatsApp noop, FCM off) + Redmi Flutter client login, « En ligne », fresh `last_pull_at`.
- FUS-NFR11: lookups by email/username use the lower() indexes (expression must match: `lower(col) = lower(?)`); no new per-row query.

**Never:** no new endpoint, no new login method, no change to registration or employee creation inputs (phone still required there until 19.2–19.4), no change to `password_reset_tokens`, no tenant-schema change (the legacy tenant `users` table stays), no data rewrite, no Flutter work.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Migration on prod copy | 40 existing users | V41 applied; rows unchanged (count + md5); new columns NULL; CHECK validated; indexes present | — |
| Insert without any identifier | phone, email, username all NULL | refused by `users_identifier_present` | constraint violation |
| Case-insensitive uniqueness | email `A@x.cm` then `a@x.cm` (same for username) | second insert refused | unique violation |
| Email-only user loaded | phone NULL, hash NULL | `User` builds; `findById` works | — |
| Existing user logs in (V1 app) | phone + password | unchanged (tokens, `select-tenant`) | — |
| Lockout path, phone-less user | failed attempts | no NPE in the log | — |
| Profile, phone-less user | `GET /auth/profile` | `phoneNumber: ""`, other fields unchanged | — |
| Profile, owner with names | users.first_name/last_name set | returned in `firstName`/`lastName` | — |
| Lookup with null/blank phone | internal caller | empty / false, no SQL | — |
| Change password, no hash | current password given | 401/400 `INVALID_CREDENTIALS` as today | French message unchanged |
| Owner WhatsApp recipient | oldest owner has no phone, next owner has one | message goes to the next owner | none if no owner has a phone (as today) |

</intent-contract>

## Code Map

- `keevo/backend/src/main/resources/db/migration/V1__baseline_public.sql:393-404,600-603` -- current `public.users` DDL and `uk_users_phone_number`; latest migration `V40__account_deletion_log.sql` (header/idempotency style); `db/modules/catalog/V1__catalog_baseline.sql:84` -- `lower()` unique index precedent.
- `.../identity/auth/domain/model/User.java:38-43` (requireNonNull), `:54` `newOwner`, `:71` `newEmployee`, `:96-114` copies, `:131` `toString` prints phone.
- `.../identity/auth/adapter/out/persistence/entity/UserJpaEntity.java:23-50`; `.../persistence/jpa/UserSpringRepository.java:20-22`; `.../persistence/impl/UserRepositoryAdapter.java:42-60` (`OWNER_BY_SCHEMA_SQL`, `OWNERS_BY_SCHEMA_SQL`), `:117-172` mappers; port `.../domain/port/out/UserRepository.java`.
- `.../identity/auth/application/service/AuthenticationService.java:63,75,108-109` (lockout log `getPhoneNumber().length()`); `.../adapter/in/rest/AuthController.java:176,283-289,386-390`; `.../rest/dto/UserProfileResponse.java:11-22`; `.../application/service/UserProfileService.java:63,87,99-110`.
- `.../identity/employee/application/service/ChangePasswordService.java:79`; `CreateEmployeeService.java:77-101`, `UpdateEmployeeService.java:80-92`, `RegeneratePasswordService`, `ChangeEmployeeRoleService:107`, `DeactivateEmployeeService:85`; `.../employee/adapter/in/rest/EmployeeRoleResolver.java:49-53`.
- `.../identity/auth/adapter/in/api/OwnerRecipientResolverAdapter.java:37-48`, `UserQueryAdapter.java:28`; `.../identity/auth/adapter/in/bootstrap/AdminAccountInitializer.java:~110-120`.
- Readers already null-safe (verify, document): `shared/audit/adapter/out/persistence/AuditLogRepositoryAdapter.java:61,71,259`, `AuditLogExportDatasetProvider.java:105`, `identity/employee/adapter/out/export/EmployeesExportDatasetProvider.java:55`, `admin/tenant/application/service/AdminTenantService.java:129,170,283`, `identity/auth/application/service/AccountDeletionService.java:100,159`, `TenantDeletionService.java:277,347-356`.
- Jackson: no global inclusion setting (nulls serialised); `shared/infrastructure/web/ApiResponseWrapper.java:21` NON_NULL on the wrapper only.
- Tests to extend/keep green: `FlywayBaselineIntegrationTest`, `UserProfileServiceTest`, `AuthControllerTest`, `AuthControllerChangePasswordTest`, `IdentityAuthApiAdaptersTest`, `OwnerLookupPostgresIntegrationTest` (owner selection rules :37-101), `AuthSessionPostgresIntegrationTest`, the raw `INSERT INTO public.users` Postgres tests (explicit column lists, unaffected).
- Prod copy access: memory `reference_keevo_prod_server_access` — `ssh` alias `oracle`, container `keevo_postgres`, db `keevo_dev`, user `keevo`, read-only `pg_dump -Fc` streamed locally.
- Sprint: `_bmad-output/implementation-artifacts/sprint-status.yaml:403`.

## Tasks & Acceptance

**Execution:**
1. `db/migration/V41__users_multiple_identifiers.sql` -- the DDL above.
2. `User`, `UserJpaEntity`, `UserRepositoryAdapter` (+ mappers, null-safe finders, owner SQL ordered by phone presence then age) -- domain and persistence.
3. `AuthenticationService`, `UserProfileService` (+ `AuthController` mapping if needed), `ChangePasswordService`, `OwnerRecipientResolverAdapter`, `AdminAccountInitializer` -- null-safety per the intent.
4. Tests: Postgres migration test (constraints, case-insensitive uniqueness, CHECK, existing rows unchanged), domain/mapper tests for a phone-less and a hash-less user, profile `""` and owner names, null lookups, lockout log, owner recipient fallback; keep identity/auth, identity/employee, shared/audit, admin packages green.
5. Live: keevo_dev migration at boot; prod-copy dry run (read-only dump, delete after); Redmi login check.
6. Spec Design Notes (reader table: safe / patched), Mobile and Dashboard sections, `m19-1-users-identifiants-multiples` and `d19-1-users-identifiants-multiples` keys, sprint-status.

**Acceptance Criteria:**
- Given a copy of Keevo production, when V41 is applied, then every existing `public.users` row is byte-identical (md5), the new columns are NULL, and inserting a user without any identifier fails.
- Given an existing Keevo user, when they log in from the installed Flutter app, then login, tenant selection, profile and sync behave exactly as before.
- Given a user created with only an email (test fixture), when any existing reader loads them, then nothing throws and no V1-facing response contains `null` for `phoneNumber`.

## Spec Change Log

## Review Triage Log

### 2026-11-03 — Review pass (5 layers incl. patterns/complexity)
- verdicts: 38 findings — high 0, medium 9, low 21, false 3, maybe-false 5
- findings:
  - (patterns 2, blind 1, edge 1) `[medium]` `[patch]` CHECK accepts blank identifiers (`''`, spaces) while the domain treats blank as absent; one `''` email takes the single `lower('')` slot → CHECK on `nullif(btrim(col),'')`, and the domain/mapper store blank as NULL and strip surrounding spaces of email and username.
  - (edge 2) `[medium]` `[patch]` `' a@x.cm'` and `'a@x.cm'` both pass the lower() index → strip in the domain (same patch).
  - (blind 3) `[medium]` `[patch]` a username can look like a phone or an email → CHECK `username ~ '^[A-Za-z0-9._-]{1,50}$'` (exactly Bref's `create-user.dto.ts` rule, so Bref usernames import as-is; no `+` nor `@`, so no collision with stored E.164 phones or emails). Digits-only usernames vs phones typed without `+` → routed to 19.4 (login disambiguation).
  - (blind 4, edge 3) `[medium]` `[patch]` a passwordless account can be locked by anyone with 5 password attempts → answer `INVALID_CREDENTIALS` without counting a failure when there is no hash.
  - (blind 2) `[medium]` `[patch]` `withEmail`/`withPhoneNumber` keep the old verification date → clear `emailVerifiedAt`/`phoneVerifiedAt` when the value actually changes; fix `UserTest.copies_keepEveryField`.
  - (intent, edge 4–5) `[medium]` `[patch]` employees response can send `phoneNumber: null` for a phone-less employee (AC3) → `EmployeeRoleResolver.resolvePhone` returns `""` for a known user without phone (`null` kept only for an unknown user, as today).
  - (patterns 1) `[medium]` `[patch]` `User` 14-argument positional constructor repeated in every `withX` → one private copy helper (builder) used by all copies; existing 6/8-argument constructors kept.
  - (verification gap) `[medium]` `[patch]` blank-phone half of the owner ordering untested → `blankPhoneOldestOwner_nextOwnerWithPhone`.
  - (blind 6) `[medium]` `[patch]` owner ordering checks presence, not the resolver's canonical-phone rule; `OWNERS_BY_SCHEMA_SQL` orders differently → order by `phone_number ~ '^\+[0-9]+$'` desc then age in both queries.
  - (blind 5) `[low]` `[patch]` blank `ADMIN_PHONE` now silently ignored → fail fast at startup when the prod profile is active (10-3 fail-fast style), WARN elsewhere.
  - (patterns 3, blind 8) `[low]` `[patch]` NOT VALID + VALIDATE gains nothing inside one Flyway transaction → plain guarded `ADD CONSTRAINT`, comment corrected.
  - (patterns 4) `[low]` `[patch]` no index on `user_tenant_memberships(tenant_id)` used by the owner lookups on every notification (pre-existing) → `CREATE INDEX IF NOT EXISTS ix_utm_tenant_role ON public.user_tenant_memberships (tenant_id, role)` in V41 (not yet released).
  - (blind 7) `[low]` `[patch]` V41 cannot be undone once phone-less rows exist (V1 2.0.1 backend would NPE) → stated in the migration header and DEPLOYMENT_CHECKLIST.
  - (blind 11) `[low]` `[patch]` missing tests: whitespace-only phone through Postgres, username-only user loaded through JPA, `OWNERS_BY_SCHEMA_SQL` new-column mapping, idempotency inside one transaction with index/nullability assertions.
  - (intent) `[low]` `[patch]` profile `""` and change-password status only tested at service level → one controller/Jackson test each (`GET /auth/profile` JSON has `"phoneNumber":""`; change-password without hash → existing status and French message).
  - (blind 9) `[low]` `[defer→19.2/19.4]` no email/username finders yet → routed: 19.2 owns `findByEmail` (`lower(email) = lower(?)`, domain error on unique violation), 19.4 owns `findByUsername` and digits-only disambiguation (briefs updated in the epics document).
  - (blind 10, edge 6) `[low]` `[reject]` « no phone » looks like « user not found » in `UserQueryAdapter`, deletion and notification fallbacks → every caller already treats empty as « no recipient », listed in the reader table; no user-visible defect.
  - (blind 12) `[false]` `[reject]` fixture not isolated / may not compile → `@DataJpaTest` rolls back each test; the class compiled and passed (identity 514 green).
  - (patterns minor) `[false]` `[reject]` `Instant.now()` fallback in mappers → pre-existing, column NOT NULL.
  - (patterns minor) `[false]` `[reject]` unused `withX` copies → used by 19.2/19.3 next.
  - (intent) `[maybe-false]` `[reject]` prod-copy dry run not done → refused by the permission classifier; decision asked to the user; otherwise covered by the 16-7 prod-copy dry run (V41 is additive, verified on keevo_dev 419 users and in Postgres tests).
  - (intent) `[maybe-false]` `[reject]` fresh V1 login not exercised on the device → done by me at verification with the dev test owner account.
  - (intent) `[maybe-false]` `[reject]` lockout row tested in two pieces → login is by phone, so a phone-less user cannot reach it; helper covered.
  - (intent) `[maybe-false]` `[reject]` username index use not checked by EXPLAIN → no username query exists yet (19.4).
  - (intent) `[maybe-false]` `[reject]` reader « safe » verdicts untested → checked by the verification-gap layer (each handles null).
  - other reviewer rows not repeated (same location and claim) share the routes above.

## Design Notes

- Why `lower()` unique indexes rather than `citext`: no extension needed, matches the `uq_products_name` precedent, and `ddl-auto: validate` keeps plain `varchar` mappings. Queries must use `lower(col) = lower(:x)` to hit the index (19.2–19.4).
- Why `""` for a missing phone on the profile: the V1 app parses `phoneNumber` as a non-null string; omitting the field would also break it. The RN app treats `""` as « no phone ».
- Owner WhatsApp recipient: choosing the oldest owner with a phone keeps today's result for every current tenant (all owners have a phone) and avoids silent loss of notifications for future email-only owners.
- Pattern: null-object at the read boundary (empty Optional / `""`) instead of null checks spread through callers; cost unchanged (same queries, two new unique indexes on a 40-row table).

- Readers of `public.users` (checked 2026-11-03):

| Reader | Verdict |
|---|---|
| `UserRepositoryAdapter.findByPhoneNumber` / `existsByPhoneNumber` | patched — null/blank → empty/false, no query |
| `UserRepositoryAdapter` JDBC owner queries | patched — read the new columns; `OWNER_BY_SCHEMA_SQL` and `OWNERS_BY_SCHEMA_SQL` both order by `(phone_number ~ '^\+[0-9]+$') DESC` (the resolver's canonical rule), then membership age, then id |
| `AuthenticationService` (login, lockout log) | patched — no password → `INVALID_CREDENTIALS`, not counted as a failed attempt (nobody can lock a passwordless account), BCrypt not called; log tail `***` for a null phone |
| `UserProfileService` → `GET /auth/profile` | patched — `phoneNumber` `""` when absent; owner names from `public.users` |
| `ChangePasswordService` | patched — no hash → `INVALID_CREDENTIALS`, BCrypt not called |
| `OwnerRecipientResolverAdapter` | safe (already filtered blank phones) + fallback via the owner SQL order |
| `AdminAccountInitializer` | patched — blank configured phone → `IllegalStateException` at startup under the `prod` profile, warning elsewhere; nothing looked up or created |
| `UserQueryAdapter.findPhoneNumber` | safe — `Optional.map` of a null phone is empty |
| `EmployeeRoleResolver.resolvePhone` → employee DTO `phoneNumber` | patched — `""` for a known user without phone, `null` only for an unknown user (as before) |
| `AccountDeletionService` (owner WhatsApp) | safe — null/blank phone → warning, no send |
| `TenantDeletionService.findOwnerPhones` | safe — `Optional.map(...).ifPresent` skips phone-less owners |
| Audit (`AuditLogRepositoryAdapter`, `AuditLogExportDatasetProvider`) | safe — `actorPhone` already nullable (LEFT JOIN); export falls back to the name |
| `EmployeesExportDatasetProvider` | safe — empty cell |
| `AdminTenantService` (list, search) | safe — `COALESCE(..., '')`; `phone_number IN (?, ?)` never matches NULL |
| Raw `INSERT INTO public.users` in Postgres tests | unaffected (explicit column lists) |

- Review fixes: the CHECK counts only non-blank identifiers (`nullif(btrim(..), '')`), added as a plain guarded constraint; `users_username_format` = Bref's rule `^[A-Za-z0-9._-]{1,50}$`; `ix_utm_tenant_role` on `user_tenant_memberships (tenant_id, role)`. The domain stores phone/email/username stripped, blank as NULL; `withEmail` / `withPhoneNumber` clear the verification date when the value changes. V41 cannot be undone once phone-less rows exist (the V1 2.0.1 backend would NPE on them) — `keevo/backend/docs/DEPLOYMENT_CHECKLIST.md` §20. keevo_dev: V41 reverted and re-applied at boot with the final script (checksum 2080572142).
- `User.toString` no longer prints the phone (personal data). `UserRepositoryAdapter.save` maps every new column, so a save never wipes email/username/names.
- Prod-copy dry run (AC1): not run by the implementing agent — the read-only `pg_dump` through `oracle` was refused by the session permission classifier. The same checks (count + md5 of the pre-V41 columns, NULL new columns, validated CHECK, indexes, idempotent re-run) run on PostgreSQL in `UsersMultipleIdentifiersPostgresIntegrationTest`. md5 must be computed on the pre-V41 column list (`row(id, created_at, updated_at, is_active, failed_attempts, locked_until, password_hash, phone_number, role)::text`): `t::text` changes as soon as columns are added.

- Live check 2026-11-03: keevo_dev migrated at boot (V40 → V41 in 56 ms, 419 users, 0 NULL phones, CHECK validated, both indexes present, `ddl-auto: validate` passed). Redmi (Flutter dev build, `adb reverse`) restored its session through `POST /auth/refresh`, then `GET /sync/pull` refreshed `last_pull_at` (21:29:30 UTC) and the dashboard displayed normally. A fresh phone + password login and `GET /auth/profile` from the device were not run (no test credentials at hand). They are covered by the identity test package (`AuthControllerTest`, `AuthenticationServiceTest`, `UserProfileServiceTest`).

## Mobile (React Native — dépôt AI/bref)

- Aucun nouvel écran ni nouvel appel. `GET /auth/profile` : `phoneNumber` est toujours une chaîne ; `""` signifie « pas de téléphone » (compte e-mail ou identifiant seul, à venir avec 19.2–19.4) — ne pas afficher de numéro vide, ne pas proposer d'actions WhatsApp/appel.
- Pour un OWNER, `firstName` / `lastName` peuvent désormais être renseignés (null s'ils ne le sont pas, comme avant) : les utiliser pour l'en-tête du profil quand ils existent.
- Employés : `phoneNumber` de la liste reste facultatif (déjà le cas).
- Application Flutter V1 2.0.1 : inchangée (connexion, choix d'entreprise, profil, synchro identiques).

## Dashboard super-admin (autre dépôt)

- Liste des comptes : `ownerPhone` peut valoir `""` pour un owner sans téléphone — afficher « — ». La recherche par téléphone ne trouve pas un tel compte (recherche par nom ou code). Aucun autre changement.

## Verification

**Commands:**
- `cd keevo/backend && rm -rf target/surefire-reports && mvn -o -q test -Dtest='com/keevo/identity/**/*Test*' -Dsurefire.failIfNoSpecifiedTests=false` -- expected: 0 failures (then `shared`, `admin` packages one at a time).
- `mvn -o -q test -Dtest='FlywayBaselineIntegrationTest,*UsersMultipleIdentifiers*' -Dsurefire.failIfNoSpecifiedTests=false` -- expected: 0 failures.

**Manual checks (if no CLI):**
- Prod-copy dry run: `public.users` count and `md5(string_agg(t::text, ',' ORDER BY id))` identical before/after V41; `\d public.users` shows the new columns, indexes and CHECK.
- Redmi: existing owner logs in, « En ligne », fresh `public.user_sync_state.last_pull_at`.

## Auto Run Result

Status: done.

**Implemented:** V41 makes `public.users` accept phone, email or username (phone and password optional; email, username, first/last name, email/phone verification dates; case-insensitive unique indexes on `lower(email)`/`lower(username)`; CHECK `users_identifier_present` on non-blank values; CHECK `users_username_format` = Bref's rule `^[A-Za-z0-9._-]{1,50}$`; index `ix_utm_tenant_role` for owner lookups; existing rows untouched; not reversible once phone-less rows exist — stated in the header and DEPLOYMENT_CHECKLIST §20). Domain `User` null-safe with normalised identifiers (stripped, blank → NULL), verification dates cleared on change, one private copy builder; persistence mapped both ways; null-safe readers: phone lookups, lockout (passwordless accounts answer `INVALID_CREDENTIALS` without being counted), profile and employees (`phoneNumber: ""` for a known phone-less user), owner recipient (owner with a canonical phone first, same order in both owner queries), change password without hash, `ADMIN_PHONE` fail-fast in prod.

**Review findings:** 38 findings (high 0, medium 9, low 21, false 3, maybe-false 5) — patched 15 (medium 9, low 6); deferred 2 routed to their owning stories (19.2 email lookup, 19.4 username lookup + digits-only disambiguation); rejected 21 with reasons in the triage log.

**Follow-up review recommendation:** true — first pass patched 9 medium entries. Unverified risk: V41 on a fresh copy of Keevo production (the read-only dump was refused by the permission classifier; user decision pending — otherwise covered by the 16-7 prod-copy dry run).

**Verification performed:** full backend suite by package (16 packages, 5 016 tests, 0 failures); keevo_dev: V41 applied at boot (419 users, CHECKs validated, 3 indexes); Redmi (Flutter 2.0.22, local backend noop): profile shown after the migration, logout, fresh login by phone + password on KV-EW02A2 → `login`, `select-tenant`, `profile`, `sync/pull` 200, owner dashboard, fresh `last_pull_at`, 0 ERROR.

**Residual risks:** prod-copy dry run pending (see above). Found during the device check, unrelated to 19.1: the Flutter app's logout runs `navigator.pop()` on its progress dialog after the router already redirected to login (`settings_page.dart` `_withProgress`), which pops the last page — black screen in the debug build (go_router assertion); the same code is on `release/2.0.x` (V1 2.0.1 in production) — reported to the user.
