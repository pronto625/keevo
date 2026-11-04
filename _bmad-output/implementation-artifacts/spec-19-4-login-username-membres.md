---
title: '19.4 — Username + password sign-in for members, members created with a username and no phone'
type: 'feature'
created: '2026-11-04'
status: 'done'
baseline_revision: '813a3731895cba36ec1380e48b8636b41d0fd69e'
review_loop_iteration: 0
followup_review_recommended: true
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

### 2026-11-05 — Review pass
- verdicts: 24 findings — high 0, medium 3, low 19, false 2, maybe-false 0 (grouped rows list every reviewer finding they cover)
- findings:
  - `[low]` `[reject]` (patterns 1) username pre-check loads a full row — one unique-index lookup; an exists port adds surface for nothing measurable.
  - `[low]` `[reject]` (patterns 2) Javadoc calls the if/else a Strategy — wording only.
  - `[medium]` `[patch]` (patterns N+1) GET /employees runs 3 queries per employee on the path this story touched — tenant once + bulk users/memberships (3 queries total).
  - `[low]` `[patch]` (blind 1, edge 1) blank phoneNumber with username fails @Pattern — blank phone treated as absent.
  - `[low]` `[reject]` (blind 2, edge 4) ACCOUNT_LOCKED reveals a username exists — same as the phone path; hiding the lock would mislead real members.
  - `[medium]` `[patch]` (blind 3) no per-IP brake on /auth/login (spraying 6-char passwords, mass lockout) — per-IP limiter on failed logins, 50/h, 429.
  - `[low]` `[patch]` (blind 4) technical google-/apple- usernames accepted at login — treated as unknown.
  - `[low]` `[patch]` (blind 5) lockout log useless for username accounts — user id logged.
  - `[medium]` `[patch]` (blind 6, edge 6, edge 9, intent V1) phoneNumber "" → null for phoneless members breaks the 19.1 V1 contract — "" restored for known users, 19.1 test restored.
  - `[low]` `[reject]` (blind 7) no self-service reset / rename / sync-push username for username-only members — owner reset exists (regenerate/set password); rename and offline creation not in the story.
  - `[low]` `[patch]` (blind 8) Usernames.key dead code — removed.
  - `[low]` `[patch]` (blind 9, edge 3) no upper bound on username / username-path password — @Size(50), password ≤ 200.
  - `[low]` `[reject]` (blind 10) phone-only creation still uses save (500 on concurrent duplicate phone) — pre-existing, pre-check covers the normal case.
  - `[low]` `[patch]` (blind 11, vg 1, vg 2) test gaps: message on wrong password, dummy bcrypt on noPassword/superAdmin, $.username, blank phone, Math.random — tests added.
  - `[low]` `[patch]` (blind 12) OpenAPI silent on username — @Schema and 401 text documented.
  - `[low]` `[patch]` (edge 2) neither identifier + short password gives the length error — validator skips without phone.
  - `[low]` `[patch]` (edge 5) lost race on a username-only request answers the phone error — USERNAME_ALREADY_EXISTS.
  - `[low]` `[reject]` (edge 7) missing phone no longer gives details.phoneNumber « obligatoire » — V1 always sends a phone; the 422 code is unchanged.
  - `[low]` `[reject]` (edge 8) employees sync delta has no username — the spec adds it only where the delta carries phoneNumber, which it does not.
  - `[false]` `[reject]` (intent R1b) single identifier field to classify — separate fields settle the 19-1 disambiguation item by construction.
  - `[low]` `[reject]` (intent R2b) Bref role whitelist and 6-char minimum not mirrored — Keevo owners keep phone + password; any non-blank password accepted for imports.
  - `[low]` `[reject]` (intent R4) 3–50 at creation vs Bref 1–50 — imported short usernames still log in (lookup does not validate).
  - `[low]` `[reject]` (intent R6) no test that the temporary password is absent from later reads — unchanged V1 behaviour.
  - `[false]` `[reject]` (intent, Bref /auth/login/credentials alias) — the unified app is rebuilt on Keevo endpoints (epic 23); no Bref client calls Keevo.

## Design Notes

**Patterns.** Strategy-by-field for the account lookup inside the existing `AuthenticationService` Façade (one `Function<command, Optional<User>>` chosen from the command, the rest of the flow shared — no second login service). Value-object style validator `Usernames` shared by creation and future rename. Unique constraint as the source of truth for concurrency (pre-check for a friendly error, index violation mapped to the same error).

**Complexity (FUS-NFR11).** Login: 1 lookup on a unique functional index, 1 bcrypt, 1 save of lockout state (existing), 1 memberships JOIN. Creation: +1 indexed username lookup over today's flow. No loops.

## Verification

**Commands:**
- `cd keevo/backend && mvn -o -q test -Dtest='AuthenticationService*Test,CreateEmployeeService*Test,Usernames*Test,AuthController*Test,EmployeeController*Test,*Username*Test'` -- expected: green.
- Full suite package by package (scratchpad `run-pkgs-wait.sh`) -- expected: 0 failures.

**Manual checks:**
- Local backend: owner creates a username-only member, member logs in by username (200), wrong password 401, V1-style phone login unchanged.

## Auto Run Result

Status: done

**Summary.** `POST /api/v1/auth/login` accepts `{username, password}` as an alternative to `{phoneNumber, password}` (exactly one, blank = absent): case-insensitive `findByUsername` on `uq_users_username_lower`, existing lockout and two-step flow, any bcrypt `$2a$/$2b$/$2y$` hash checked without rehash, 1–200-char passwords on the username path (Bref 6-char passwords), dummy bcrypt for unknown/technical/password-less/super-admin usernames, « Identifiant ou mot de passe incorrect ». Failed logins are limited per IP (50/h, both paths, 429). `POST /api/v1/employees` creates members with a username and no phone (3–50 Bref characters, `google-`/`apple-` reserved, 409 `USERNAME_ALREADY_EXISTS`, conflict-safe insert). `username` added to employee and profile responses (technical usernames hidden; phone stays `""` for V1). Employee list enrichment in 3 queries instead of 3 per employee. Pre-existing bug fixed: `/auth/change-password` now reports `passwordChangeRequired:false`.

**Files.** New: `Usernames`, `LoginPasswordLength` validator, `LoginRateLimiter`; changed: `LoginRequest`, `AuthenticateUserCommand`, `AuthController`, `AuthenticationService`, `UserRepository` (+ adapter: `findByUsername`, `findAllByIds`), `UserMembershipRepository.findByTenantIdAndUserIdIn`, `CreateEmployeeRequestDto`/`Command`/`Service`, `EmployeeController`, `EmployeeRoleResolver.resolveAll`, `EmployeeResponseDto`, `UserProfileData`/`Response`/`Service`, `EmployeeRepositoryAdapter` (saveAndFlush), error code `USERNAME_ALREADY_EXISTS` 409, `application.yml`.

**Review.** 24 entries (5 layers): 0 high, 3 medium, 19 low, 2 false. Patched: 3 medium (per-IP failed-login limit, V1 `""` phone contract, employee list N+1) and 12 low (blank phone, technical usernames at login, lockout log, bounds, OpenAPI, race error, dead code, validator order, tests) plus the change-password flag bug. Rejected: 9, reasons in the triage log. Nothing routed.

**Follow-up review: recommended (true)** — 3 medium patches; unverified risk: the per-IP failed-login limit is in memory per instance and keyed on the last `X-Forwarded-For` entry (prod: Traefik directly on the server, no CDN — checked read-only on `oracle`).

**Verification.** Targeted classes green. Full suite package by package: 5 481 tests, 0 failures. Live `keevo_dev`: username-only member created 201 (`phoneNumber:""`, `username`), same username other case 409, `apple-x1` 422, login by username in upper case 200 (hash `$2a$` unchanged), wrong password 401 « Identifiant ou mot de passe incorrect », both identifiers 422, phone login 200, list shows username / `""` / EMPLOYEE.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit 0238c034). Conventions : spec-16-11 ; synchro : spec-16-10.

**Ce qui est fait côté API :** connexion des membres par identifiant + mot de passe (comme l'écran « identifiants » de Bref) ; création de membres avec un identifiant et sans téléphone.

**À utiliser :**
1. `POST /api/v1/auth/login` body `{"username":"jean.dupont","password":"…"}` (ou `{"phoneNumber","password"}`, jamais les deux) → `200 {"loginToken","memberships"}` comme aujourd'hui, puis `select-tenant` ; si `passwordChangeRequired` → `POST /auth/change-password`. Erreurs : 401 `INVALID_CREDENTIALS` « Identifiant ou mot de passe incorrect » ; 423/`ACCOUNT_LOCKED` après 5 échecs (15 min) ; 429 `RATE_LIMIT_EXCEEDED` « Trop de requêtes, veuillez patienter » ; 422 `VALIDATION_FAILED` « Indiquez un numéro de téléphone ou un identifiant ».
2. `POST /api/v1/employees` body `{"firstName","lastName","username"?,"phoneNumber"?,"storeId"}` (au moins un identifiant) → `201` avec le mot de passe temporaire (affiché une seule fois). Erreurs : 409 `USERNAME_ALREADY_EXISTS` « Cet identifiant est déjà utilisé » ; 409 `USER_ALREADY_EXISTS` (téléphone) ; 422 « Identifiant invalide (3 à 50 lettres, chiffres, point, tiret ou souligné) ».
3. Réponses employés et `/auth/profile` : champ `username` (null si aucun) ; `phoneNumber` vaut `""` quand le membre n'a pas de téléphone.

Code backend : `keevo/backend/src/main/java/com/keevo/identity/auth/application/service/AuthenticationService.java`, `identity/employee/application/service/CreateEmployeeService.java`, `identity/auth/domain/model/Usernames.java`.

**À implémenter côté mobile :**
1. Écran de connexion « Identifiant et mot de passe » (Bref) envoyant `username` ; l'écran téléphone envoie `phoneNumber`. Ne jamais deviner le type à partir de la saisie.
2. Formulaire « Ajouter un membre » : identifiant ou téléphone (au moins un), afficher le mot de passe temporaire une seule fois avec un bouton copier.
3. Liste d'équipe : afficher l'identifiant quand il n'y a pas de téléphone ; ne pas afficher de valeur vide.
4. Messages d'erreur de l'API affichés tels quels (français).

**Implémentation de référence :** écran identifiants de `AI/bref` (`/auth/login/credentials` côté Bref).

**Critères d'acceptation mobile / scénarios de test :**
- Given un membre créé avec l'identifiant `awa_k`, When il se connecte avec `AWA_K` et le mot de passe temporaire, Then il doit changer son mot de passe puis arrive sur l'accueil de sa boutique.
- Given un identifiant déjà pris, Then « Cet identifiant est déjà utilisé ».
- Mise en place : `curl -X POST localhost:4500/api/v1/employees -H 'Authorization: Bearer <owner>' -H 'Content-Type: application/json' -d '{"firstName":"Awa","lastName":"K","username":"awa_k","storeId":"<id>"}'`.

**Clé de suivi mobile :** `m19-4-login-username-membres`.

