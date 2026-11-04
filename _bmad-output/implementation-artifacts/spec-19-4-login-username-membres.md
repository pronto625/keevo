---
title: '19.4 — Username + password sign-in for members, members created with a username and no phone'
type: 'feature'
created: '2026-11-04'
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

**Problem:** Bref team members sign in with a username and the password their owner gave them (`POST /auth/login/credentials` in Bref); Keevo's login only accepts a phone number, and an owner cannot create a member without a phone, so imported Bref members could not sign in and Bref-style members cannot be created.

**Approach:** Make `POST /api/v1/auth/login` accept `{username, password}` as an alternative to `{phoneNumber, password}` (same response, same lockout, same two-step login), add `findByUsername` (`lower(username) = lower(?)` on `uq_users_username_lower`), and let `POST /api/v1/employees` create a member with a username and no phone (global case-insensitive uniqueness, temporary password returned once as today).

## Boundaries & Constraints

**Always:**
- Login body: exactly one of `phoneNumber` / `username` (both or neither → 422 `VALIDATION_FAILED` « Indiquez un numéro de téléphone ou un identifiant »). `phoneNumber` keeps its current pattern and messages (V1 2.0.1 sends only `phoneNumber`; its requests and responses are unchanged). Password: phone path keeps 8–72 chars; username path accepts 1–72 chars (Bref members may have 6-char passwords) and never rejects on length before the lookup.
- Username path: lookup `findByUsername` (stripped, case-insensitive). Unknown username → one dummy bcrypt `matches` (same timing as a wrong password, Bref) then `INVALID_CREDENTIALS`. Then exactly the existing `AuthenticationService` flow: lockout check (`ACCOUNT_LOCKED` after 5 failures, 15 min), no password → `INVALID_CREDENTIALS` without counting, bcrypt `matches` for `$2a$`/`$2b$`/`$2y$` hashes of any cost (no forced rehash), `loginToken` + memberships. A SUPER_ADMIN is never signed in by username (`INVALID_CREDENTIALS`).
- The two fields are separate, so an all-digit username and a phone typed without « + » are never confused: `username` is only looked up as a username, `phoneNumber` only as a phone (routed 19-1 item: settled by explicit fields; the app sends the field matching the screen used).
- Username rule (one shared validator in the domain): Bref format `^[A-Za-z0-9._-]{3,50}$` (V41 CHECK allows 1–50; creation requires ≥3 like Bref's UI), stored as typed but compared case-insensitively; reserved prefixes `google-` and `apple-` refused (routed 19.3 item) → 422 `VALIDATION_FAILED` « Identifiant invalide (3 à 50 lettres, chiffres, point, tiret ou souligné) ».
- `POST /api/v1/employees` body gains optional `username`; `phoneNumber` becomes optional; at least one required (422 otherwise, same message as login). Phone present → current rules unchanged (format, normalization, `USER_ALREADY_EXISTS` if taken). Username present → validated, then uniqueness checked with `findByUsername`; taken → 409 `USERNAME_ALREADY_EXISTS` « Cet identifiant est déjà utilisé »; a concurrent insert hitting `uq_users_username_lower` maps to the same 409 (no 500). Both may be given. Plan limits, membership, employee row, event and the once-only temporary password stay as today.
- Responses: `EmployeeResponseDto` and the employees sync delta (if it carries `phoneNumber`) gain `username` (additive; `phoneNumber` may now be null — V1 parses it as nullable). `UserProfileResponse` gains `username` if it exposes `phoneNumber`.
- Swagger and `docs/entitlements/endpoints.md` unchanged in endpoint list (no new path); error codes added to `ErrorCode`/`ErrorMessages`/`GlobalExceptionHandler`.

**Never:**
- No new login endpoint, no username change endpoint, no username for owners in this story, no change to OTP/social flows (19.2/19.3), no rehash of existing hashes, no multi-role behaviour (19.6), no change to the forgot-password flow (phone only).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Username login OK | `{username:"Jean.Dupont", password}` for `jean.dupont`, `$2b$12$` hash | 200 loginToken + memberships | — |
| Unknown username | | dummy bcrypt, then | 401 `INVALID_CREDENTIALS` |
| Wrong password ×5 | | 5th → locked | 401 then 423/`ACCOUNT_LOCKED` as phone login |
| 6-char password, username path | valid | 200 | — |
| Both / neither field | | — | 422 `VALIDATION_FAILED` |
| V1 phone login | `{phoneNumber,password}` | unchanged | unchanged |
| Create member username only | `{firstName,lastName,username:"awa_k",storeId}` | 201, `phoneNumber:null`, `username`, temp password once | — |
| Username taken (other case) | `AWA_K` exists | — | 409 `USERNAME_ALREADY_EXISTS` |
| Reserved / bad format | `apple-x`, `a b`, `ab` | — | 422 `VALIDATION_FAILED` |
| Neither phone nor username | | — | 422 |

</intent-contract>

## Code Map

- `identity/auth/adapter/in/rest/dto/LoginRequest.java` -- `@NotBlank @Pattern phoneNumber`, password 8–72; make the pair conditional (class-level validation or service check).
- `identity/auth/application/service/AuthenticationService.java` -- current flow (lookup by phone, lockout, `hasPassword`, `exceedsBcryptLimit`, `matches`, memberships, loginToken); add the username lookup branch, no duplication of the rest.
- `identity/auth/domain/port/in/AuthenticateUserCommand.java`, `AuthController.java:~195 login` -- command gains `username`.
- `identity/auth/domain/port/out/UserRepository.java`, `adapter/out/persistence/impl/UserRepositoryAdapter.java`, `jpa/UserSpringRepository.java` -- add `findByUsername` (native `lower(username) = lower(:u)`), mirror of 19.2's `findByEmail`.
- `identity/employee/application/service/CreateEmployeeService.java` -- phone checks then `User.newEmployee(phone, hash)`; add username path.
- `identity/employee/adapter/in/rest/dto/CreateEmployeeRequestDto.java`, `EmployeeResponseDto.java`, `EmployeeController.java`, `domain/port/in/CreateEmployeeCommand` -- fields.
- `identity/auth/domain/model/User.java` -- `newEmployee`, `withUsername`; V41 `users_username_format` CHECK.
- `sync/.../EmployeeDeltaProvider` (or the employees delta query) -- check whether `phoneNumber` is in the delta; add `username` next to it.
- `api-bref/src/auth/auth.service.ts:641-678` (read-only) -- Bref credentials login (dummy compare, roles, inactive).
- `shared/domain/exception/ErrorCode.java`, `ErrorMessages.java`, `GlobalExceptionHandler.java`.

## Tasks & Acceptance

**Execution:**
- `identity/auth/domain/model/Usernames.java` -- format + reserved-prefix validation, case-insensitive key.
- `UserRepository` + adapter + Spring repo -- `findByUsername`.
- `LoginRequest`, `AuthenticateUserCommand`, `AuthController`, `AuthenticationService` -- username branch per the rules.
- `CreateEmployeeRequestDto`, `CreateEmployeeCommand`, `EmployeeController`, `CreateEmployeeService`, `User` (factory accepting phone or username) -- creation per the rules; unique-violation mapping.
- `EmployeeResponseDto` (+ list/get mappers), employees sync delta, `UserProfileResponse` if relevant -- `username` field.
- `ErrorCode`/`ErrorMessages`/`GlobalExceptionHandler` -- `USERNAME_ALREADY_EXISTS` 409.
- Tests -- unit: every matrix row (AuthenticationService, CreateEmployeeService, Usernames); a `$2b$12$` hash produced by Node-style bcrypt (fixture string) validated by `BCryptPasswordEncoder` without rehash; controller tests for login validation; Postgres test for `findByUsername` case-insensitivity and the unique-index race mapping; full-context: owner creates a username-only member → member logs in by username with the temp password → select-tenant → change-password flow works; V1 phone login regression green.

**Acceptance Criteria:**
- Given an imported Bref member row (`username`, `$2b$12$` hash, no phone), when they log in with username and password, then they get the same two-step session as a phone login and their hash is unchanged.
- Given the V1 2.0.1 app, when it logs in or lists/creates employees with phones, then requests and responses are unchanged apart from the additive `username` field.
- Given the full suite run package by package, then it is green.

## Spec Change Log

## Review Triage Log

## Design Notes

**Patterns.** Strategy-by-field for the account lookup inside the existing `AuthenticationService` Façade (one `Function<command, Optional<User>>` chosen from the command, the rest of the flow shared — no second login service). Value-object style validator `Usernames` shared by creation and future rename. Unique constraint as the source of truth for concurrency (pre-check for a friendly error, index violation mapped to the same error).

**Complexity (FUS-NFR11).** Login: 1 lookup on a unique functional index, 1 bcrypt, 1 save of lockout state (existing), 1 memberships JOIN. Creation: +1 indexed username lookup over today's flow. No loops.

## Verification

**Commands:**
- `cd keevo/backend && mvn -o -q test -Dtest='AuthenticationService*Test,CreateEmployeeService*Test,Usernames*Test,AuthController*Test,EmployeeController*Test,*Username*Test'` -- expected: green.
- Full suite package by package (scratchpad `run-pkgs-wait.sh`) -- expected: 0 failures.

**Manual checks:**
- Local backend: owner creates a username-only member, member logs in by username (200), wrong password 401, V1-style phone login unchanged.
