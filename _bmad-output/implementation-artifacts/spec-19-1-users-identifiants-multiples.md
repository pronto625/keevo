---
title: '19-1 public.users accepts phone, email or username as identifier, with a profile (names, verification dates) — schema and null-safety only'
type: 'feature'
created: '2026-11-03'
status: 'in-progress'
baseline_revision: 'b17c8b10f2a42d90a78c2ec6ce02574389ba5e77'
review_loop_iteration: 0
followup_review_recommended: false
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

## Design Notes

- Why `lower()` unique indexes rather than `citext`: no extension needed, matches the `uq_products_name` precedent, and `ddl-auto: validate` keeps plain `varchar` mappings. Queries must use `lower(col) = lower(:x)` to hit the index (19.2–19.4).
- Why `""` for a missing phone on the profile: the V1 app parses `phoneNumber` as a non-null string; omitting the field would also break it. The RN app treats `""` as « no phone ».
- Owner WhatsApp recipient: choosing the oldest owner with a phone keeps today's result for every current tenant (all owners have a phone) and avoids silent loss of notifications for future email-only owners.
- Pattern: null-object at the read boundary (empty Optional / `""`) instead of null checks spread through callers; cost unchanged (same queries, two new unique indexes on a 40-row table).

## Verification

**Commands:**
- `cd keevo/backend && rm -rf target/surefire-reports && mvn -o -q test -Dtest='com/keevo/identity/**/*Test*' -Dsurefire.failIfNoSpecifiedTests=false` -- expected: 0 failures (then `shared`, `admin` packages one at a time).
- `mvn -o -q test -Dtest='FlywayBaselineIntegrationTest,*UsersMultipleIdentifiers*' -Dsurefire.failIfNoSpecifiedTests=false` -- expected: 0 failures.

**Manual checks (if no CLI):**
- Prod-copy dry run: `public.users` count and `md5(string_agg(t::text, ',' ORDER BY id))` identical before/after V41; `\d public.users` shows the new columns, indexes and CHECK.
- Redmi: existing owner logs in, « En ligne », fresh `public.user_sync_state.last_pull_at`.
