---
title: '19.6b — Adding an existing account: invitation with consent, identifier privacy, recovery channel'
type: 'feature'
created: '2026-11-10'
status: 'done'
baseline_revision: 'cf9402ba20aa80e6cbf096de59e3d01b2d71810c'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-19-6-multi-roles-multi-entreprises.md'
warnings: [oversized]
deferred:
  - summary: >-
      No retention for invitation rows that never became memberships (declined, cancelled, expired).
    evidence: |-
      They keep the typed phone/username and names of people who refused, forever. Routed to 19-11 (platform retention job), 2026-11-10.
    location: >-
      keevo/backend/src/main/resources/db/migration/V74__membership_invitations.sql
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Since 19.6, an owner (or manager) who types the phone or username of someone who already has a Bref Point account attaches that account to his company immediately, without the person's consent, learns from `existingAccount` that the identifier is registered, and every attempt sends a message. The attached member's other identifiers (the phone when added by username, the username when added by phone) then show on the owner's member card, list and export, and a shared member with only a username has no way to recover a forgotten password (no owner may reset it any more).

**Approach:** Replace the immediate attach with an invitation the person accepts or declines (from the login step with the `loginToken`, or from an open session). Reveal nothing about the account to the inviting company (same neutral answer whatever the account's state, no account data), throttle invitations, show the inviting company only the identifier it typed, and require a recovery channel of the person's own (phone, or verified email) before an account joins a second company.

## Decisions (product, taken by default — privacy-protective)

- **D1 Consent:** an existing account is never attached by an owner; it is invited (`public.membership_invitations`), and only the person's acceptance creates the membership + employee row. Invitations expire after 7 days; the owner (or the manager of the store) may cancel a pending one.
- **D2 Existence oracle:** the identifier's existence cannot be hidden from someone trying to create it (uniqueness — the public `/auth/register` already answers `USER_ALREADY_EXISTS` for a phone). Everything else is hidden: `POST /employees` answers the same `202 {invitationPending:true}` for an invitable account, a pending invitation (no new message), a recent refusal (no message), the platform administrator and an inactive account (nothing created, nothing sent). No name, other identifier, company or account state is ever returned. The plan limit is checked before the lookup, so a full plan answers `PLAN_LIMIT_EXCEEDED` whatever the identifier.
- **D3 Anti-spam / probing:** at most one message per (company, person) per invitation (re-adding a pending invitation sends nothing); after a refusal the same company cannot re-invite for 30 days (silent); at most 20 invitations created or renewed per company per rolling 24 h → 429 `RATE_LIMIT_EXCEEDED` « Trop d'invitations envoyées aujourd'hui : réessayez demain ».
- **D4 Phone and username both given** and at least one is already used → 409 `USER_ALREADY_EXISTS` « Ce téléphone ou cet identifiant est déjà utilisé : indiquez seulement le téléphone ou seulement l'identifiant de la personne » (whether they are the same account or not is not disclosed).
- **D5 Confidentiality:** a member who joined through an invitation shows the inviting company only the identifier it typed: invited by username → phone `""` on the card/list, empty in the employees export and the audit actor phone; invited by phone → username `null`. Members created by the company keep today's view.
- **D6 Recovery:** accepting requires the account to have a phone or a verified email (`ACCOUNT_RECOVERY_REQUIRED` 409 otherwise — the person's current company owner can add a phone first). An account that is « shared » only because it is OWNER here and has no recovery channel of its own stays manageable (password, identifiers) by the owners of that company — otherwise nobody could ever reset it.
- **D7 Notifications:** invitation message to the person on WhatsApp (phone) else verified email, after commit, asynchronous, never SMS: « {Entreprise} vous invite à rejoindre son équipe sur Bref Point en tant que {rôle}. Ouvrez l'application pour accepter ou refuser. L'invitation expire dans 7 jours. Si vous ne connaissez pas cette entreprise, ignorez ce message. » On acceptance, one inbox notification to the inviter in his company (`MEMBERSHIP_INVITATION_ACCEPTED`, « {Prénom Nom} a rejoint votre équipe »). Refusals notify nobody.

## Boundaries & Constraints

**Always:**
- `POST /api/v1/employees` order: validation → per-tenant lock + plan limit (both paths) → identifier lookup (D4) → new identifier: today's creation, 201 unchanged → existing account: active/inactive membership here → 409 `MEMBER_ALREADY_IN_COMPANY` / `MEMBER_INACTIVE_IN_COMPANY` (the company knows its members); else the daily cap (D3) then the invitation upsert (D1–D3) → `202 {employee:null, temporaryPassword:"", existingAccount:true, invitationPending:true}`. New-account responses get `invitationPending:false`.
- Invitation upsert is one statement keyed by `(tenant_id, user_id)`: insert, or renew a row that is CANCELLED, ACCEPTED (member removed since), expired PENDING, or DECLINED more than 30 days ago; a live PENDING or recent DECLINED row is left untouched (no message).
- Invitee endpoints act only on the caller's own PENDING, unexpired invitations of an ACTIVE company; anything else → 404 `INVITATION_NOT_FOUND` « Cette invitation n'existe plus ou a expiré ».
- Acceptance (one transaction in the inviting company's schema): invitation row locked; membership absent (else 409 `MEMBER_ALREADY_IN_COMPANY`); recovery channel (D6); plan limit under the same per-tenant lock as creation (`PLAN_LIMIT_EXCEEDED`, the invitation stays pending); membership with the invited role + `Employee.createForExistingAccount` (typed names and store) + `EmployeeCreatedEvent` (actor = the person); invitation ACCEPTED. Password and `users.role` untouched.
- Errors added: `INVITATION_NOT_FOUND` (404), `ACCOUNT_RECOVERY_REQUIRED` (409), French messages in `ErrorMessages`.
- Viewer write guard allow-list gains the two session-side POSTs (a VIEWER elsewhere may answer an invitation).

**Never:**
- No membership or employee row before acceptance, no message on a re-add, no SMS, no account data (names, identifiers other than typed, companies, account state) in any owner-facing response, no change to brand-new member creation, no change to V1 responses other than additive fields (V1 2.0.1 got 409 before 19.6; it now gets a 202 without `employee`, which it reports as an error — documented).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Phone of an active account, no link with A | owner A adds | 202 neutral, PENDING row, one WhatsApp/email | — |
| Same phone re-added | live PENDING | 202 neutral, no message | — |
| Declined 3 days ago | re-add | 202 neutral, nothing sent | — |
| Declined 40 days ago / cancelled / expired | re-add | 202, row renewed, one message | — |
| SUPER_ADMIN or inactive account | add | 202 neutral, no row, no message | — |
| Phone + username, one used | add | — | 409 `USER_ALREADY_EXISTS` (D4) |
| 21st invitation in 24 h | add existing | — | 429 `RATE_LIMIT_EXCEEDED` |
| Plan full | add any identifier | — | `PLAN_LIMIT_EXCEEDED` |
| Person lists (loginToken or session) | | pending ones: company name, role + label, expiry | — |
| Person accepts | has phone | 200 membership {tenantCode, tenantName, role, memberRole}; member listed in A; inviter inbox | — |
| Person accepts | username only | — | 409 `ACCOUNT_RECOVERY_REQUIRED` |
| Accept someone else's / expired / cancelled | | — | 404 `INVITATION_NOT_FOUND` |
| Accept while plan full | | — | `PLAN_LIMIT_EXCEEDED`, stays PENDING |
| Person declines | | 200, DECLINED, nobody notified | — |
| Owner lists / cancels | MANAGER: own store only | 200 / 204 | 404 `INVITATION_NOT_FOUND` |
| Member joined by username | owner lists members / export / audit | phone `""` / empty | — |
| Member joined by phone | owner lists members | username `null` | — |
| Username-only OWNER here, no other company | other owner resets password | allowed | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/identity/employee/application/service/CreateEmployeeService.java` -- 19.6 find-or-attach (`findExistingAccount`, `attachExistingAccount`) → plan check first, D4, invitation branch.
- `.../identity/employee/application/service/MemberAddedNotifier.java` (+ `MemberAddedNotifierTest`) -- replaced by `MembershipInvitationNotifier` (same after-commit + `notificationExecutor` + WhatsApp→email fallback pattern).
- `.../identity/employee/application/service/SharedAccountGuard.java` -- D6 nuance (OWNER-here without recovery channel and no outside membership → not shared); needs `UserRepository` only on that branch.
- `.../identity/employee/adapter/in/rest/EmployeeRoleResolver.java` -- `resolveAll`: one extra set query for accepted invitations' identifier kind (D5).
- `.../identity/employee/adapter/out/export/EmployeesExportDatasetProvider.java`, `.../shared/audit/adapter/out/persistence/AuditLogRepositoryAdapter.java` -- phone hidden for USERNAME-invited members (LEFT JOIN on the unique `(tenant_id,user_id)` key, tenant from `current_schema()` like the existing `am` join).
- `.../identity/employee/adapter/in/rest/EmployeeController.java` + `dto/TempPasswordResponseDto.java`, `domain/model/CreateEmployeeResult.java` -- 202 branch, `invitationPending`; owner `GET /employees/invitations`, `DELETE /employees/invitations/{id}` with `EmployeeTeamScope.listScope()`.
- `.../identity/auth/application/service/LoginTokens.java` -- reuse through a new public port `identity/auth/domain/port/in/LoginTokenVerifier` (implementation in the same package).
- `.../identity/auth/domain/model/User.java` -- `hasRecoveryChannel()`.
- `.../shared/infrastructure/security/SecurityConfig.java` (`PUBLIC_PATHS`), `ViewerWriteGuardFilter.ALLOWED_WRITES` (+ `ViewerAllowListPinTest`).
- `.../shared/domain/exception/ErrorCode.java`, `shared/infrastructure/web/ErrorMessages.java`, `GlobalExceptionHandler.java` -- two codes.
- `PostgresTransactionLockAdapter` / `TransactionLockPort`, `EmployeeCountPort`, `TenantPlanQuery`, `PlanLimitCheck` -- plan limit reused by creation and acceptance (extracted `EmployeePlanLimit`).
- `TenantContext` + `SchemaAwareMultiTenantConnectionProvider` -- search_path fixed at connection acquisition: acceptance switches `TenantContext` BEFORE its transaction starts (façade + `@Transactional` inner component), restores it in `finally` (pattern of `ModuleLifecycleService.history`).
- Tests touched: `CreateEmployeeServiceExistingAccountTest`, `CreateEmployeeServiceTest`, `CreateEmployeeServiceUsernameTest`, `EmployeeControllerTest`, `MultiCompanyMemberFullContextTest`, `EmployeeSharedAccountTest`.

## Tasks & Acceptance

**Execution:**
- `keevo/backend/src/main/resources/db/migration/V74__membership_invitations.sql` -- table (FKs tenant/user CASCADE, invited_by informational without FK, `last_sent_at` message cooldown, CHECKs on role/kind/status), unique `(tenant_id,user_id)`, indexes `(user_id,status)`, `(tenant_id,invited_at)`, partial `(tenant_id, invited_at DESC) WHERE status = PENDING`.
- `identity/employee/domain/model/MembershipInvitation.java`, `domain/port/out/MembershipInvitationRepository.java`, `adapter/out/persistence/JdbcMembershipInvitationRepository.java` -- upsert (RETURNING), count since, pending by user (JOIN tenants ACTIVE), pending by tenant (+store), lock for user, mark responded, cancel, accepted kinds by users.
- `identity/employee/application/service/EmployeePlanLimit.java` -- lock + check extracted from `CreateEmployeeService`.
- `CreateEmployeeService` -- new order, D2–D4, invitation branch, notifier.
- `MembershipInvitationNotifier` (replaces `MemberAddedNotifier`) -- invitation message, acceptance inbox.
- `identity/employee/application/service/MembershipInvitationService.java` (+ `InvitationAcceptance` transactional component), port in `domain/port/in/MembershipInvitationUseCase.java` -- owner list/cancel, invitee list/accept/decline.
- `identity/employee/adapter/in/rest/InvitationController.java` -- `POST /api/v1/auth/invitations/pending|{id}/accept|{id}/decline` (body `{loginToken}`), `GET /api/v1/account/invitations`, `POST /api/v1/account/invitations/{id}/accept|decline`; `EmployeeController` owner endpoints.
- `SharedAccountGuard`, `User.hasRecoveryChannel`, `EmployeeRoleResolver`, export and audit SQL -- D5/D6.
- Error codes, security paths, viewer allow-list, OpenAPI, `docs/entitlements/endpoints.md` regenerated.
- Tests -- unit for each matrix row (service + guard + resolver), JDBC repository on PostgreSQL (upsert transitions, cap count, filters), full-context journey (invite by phone → login → pending list → accept → picker lists both → shared protections still hold; invited by username → phone hidden; decline; cancel; recovery refused).

**Acceptance Criteria:**
- Given an owner of A who types the phone of someone who owns B, when he adds her, then nothing is attached, he receives the neutral 202, she receives one invitation and, once she accepts from the app, she sees A and B at login with their roles and keeps her password.
- Given any owner, when he adds identifiers of existing accounts in any state, then no response tells him more than « an invitation is pending if the person can receive it ».
- Given the full backend suite run package by package, then it is green.

## Spec Change Log

## Review Triage Log

### 2026-11-10 — Review pass
- verdicts: 45 findings — high 1, medium 12, low 22, false 4, maybe-false 0 (+ 6 descriptive intent-alignment observations logged below)
- findings:
  - `[high]` `[patch]` (patterns H, edge 1) lock order inverted between acceptance (invitation row, then advisory lock) and re-invitation (advisory lock, then row via upsert) → 40P01 deadlock — `EmployeePlanLimit` split into `lock()`/`check()`; acceptance takes the per-company lock first; order asserted in `MembershipInvitationServiceTest`.
  - `[medium]` `[patch]` (patterns M1, blind 11) accept loaded the whole pending list to find one schema — single keyed query `pendingTenantSchema(id, userId, now)`.
  - `[medium]` `[patch]` (patterns M2) hand-written tenant switch, no schema validation — `TenantSchema.validate` added; `admin.crosstenant.TenantScope` cannot be reused from `identity` (module dependency direction), kept inline and documented.
  - `[low]` `[reject]` (patterns L1) role label map duplicated with audit/export labels — those live in `shared.infrastructure.export` (adapter layer); a domain copy of four labels is cheaper than a cross-layer dependency.
  - `[low]` `[patch]` (patterns L2) company pending list scanned answered rows — partial index `(tenant_id, invited_at DESC) WHERE status = 'PENDING'` in V74.
  - `[low]` `[reject]` (patterns L3) two identical current-tenant subqueries in the audit select — each is one InitPlan on a unique index, evaluated once per statement.
  - `[low]` `[reject]` (patterns L4) `UpdateEmployeeService` re-reads the user in the guard for an OWNER — one primary-key read on a rare owner action.
  - `[low]` `[reject]` (patterns L5) EXISTS always runs in the guard overload — one indexed probe, now needed for the invitation rule anyway.
  - `[low]` `[patch]` (patterns L6) person's pending list unbounded — `LIMIT 50`.
  - `[medium]` `[patch]` (edge 2, blind 2) cancel + re-add loop sent unlimited messages (cap counts one row per pair) — `last_sent_at` column: at most one invitation message per (company, person) per 24 h whatever the renewals; Postgres test covers the loop.
  - `[medium]` `[patch]` (edge 3) re-add of a live invitation with another role/store was ignored — the upsert now renews a live PENDING row with what was typed (no new message within 24 h).
  - `[medium]` `[patch]` (edge 4, blind 5) invited store deleted/deactivated before acceptance — store checked through `StoreQueryPort` (active), else the invitation is cancelled and « La boutique prévue pour vous n'existe plus : demandez une nouvelle invitation ».
  - `[medium]` `[patch]` (edge 5) a MANAGER listed/cancelled the owner's MANAGER invitations of his store — store scope restricted to EMPLOYEE/VIEWER invitations in both SQL statements (19.5 rule).
  - `[medium]` `[patch]` (edge 6, blind 10) membership already present at acceptance left the invitation pending forever; plan error shown before the member check — active member: invitation consumed, company answered (idempotent); inactive: invitation cancelled + `MEMBER_INACTIVE_IN_COMPANY` « Vous avez été désactivé… »; member check now before the plan check.
  - `[medium]` `[patch]` (edge 7, blind 1) admin `createMember` with no last name → NOT NULL violation (500) — `""` stored for a missing last name.
  - `[low]` `[reject]` (edge 8) V1 sync push with a full plan gets `PLAN_LIMIT_EXCEEDED` instead of `USER_ALREADY_EXISTS` — both are refusals the V1 app reports; plan first is the D2 privacy rule.
  - `[low]` `[patch]` (edge 9, blind 9) `invitedBy` documented as nulled on deletion while V74 has no FK — Javadoc corrected (informational, no FK by design: support accounts, test actors).
  - `[low]` `[reject]` (edge 10) the company's pending list differs for an inactive/admin/recently-declining account — accepted residual of D2 (documented): only the absence of a row, no account data; the refusal case is the person's choice.
  - `[low]` `[reject]` (edge 11) phone + username both given answers 409 when either is used — uniqueness oracle inherent to any identifier creation (public `/auth/register` answers the same), D2/D4.
  - `[low]` `[reject]` (blind 3) 429 returned for a no-op re-add when the cap is reached — needs ≥20 invitations the same day; the neutral answer cannot be computed without the upsert.
  - `[medium]` `[patch]` (blind 4) an invitation-joined account became resettable by the inviting company once the person left her other companies — `SharedAccountGuard`: an ACCEPTED invitation keeps the account shared (one unique-key EXISTS); test added.
  - `[low]` `[reject]` (blind 6) no audit rows for invite/cancel/decline — invitations change no company data; acceptance publishes `EmployeeCreatedEvent` (actor = the person who acted).
  - `[low]` `[false]` (blind 7) 202 without employee breaks V1 — V1 2.0.1 got 409 for any existing phone before 19.6 (19.6 never shipped); both outcomes land in its generic error path (`AsyncValue.guard`), documented in the Mobile section.
  - `[medium]` `[defer]` (blind 8) no retention for answered/expired invitation rows (phone of people who refused) — routed to 19-11 (owner of the set-based multi-tenant purge job).
  - `[low]` `[reject]` (blind 12) username-only account gets a silent invitation it cannot accept — same neutral treatment as other accounts; the person sees it at sign-in with the recovery message.
  - `[low]` `[reject]` (blind 13) no push to an open session; « 7 jours » duplicated in text — the app polls `GET /account/invitations` (mobile section); TTL constant and text change together in one class.
  - `[low]` `[patch]` (blind 14, vg 4, vg 5) missing tests: admin 202 path, export/audit phone hiding — adapter tests for `createMember`, export and audit Postgres cases added. loginToken 401 paths reuse `LoginTokens` already covered by select-tenant/create-company tests (reject for that part).
  - `[medium]` `[patch]` (vg 1) `EmployeeRoleResolverTest` lacked the new port, PHONE/USERNAME masking untested — mock + `invitedMembers_onlyTheTypedIdentifier`.
  - `[medium]` `[patch]` (vg 2) `CreateEmployeeServiceUsernameTest` still asserted the 19.6 attach — rewritten as `usernameTaken_invitesExistingAccount`.
  - `[medium]` `[patch]` (vg 3) sync-push adapter stub did not pin `invitationAllowed=false` — stub now matches the 8-arg command (fails if the flag is dropped).
  - `[low]` `[false]` (vg other) suite not green — the three listed tests were updated before the run; suite green (see Verification).
  - `[low]` `[false]` (intent 1) literal « never reveal existence » not met — D2: impossible with unique identifiers and the V1 temporary-password contract; everything else hidden; decision listed for the user.
  - `[low]` `[reject]` (intent 2, 3, 4) members attached by the 19.6 immediate attach (no consent, no masking, possibly username-only) are not migrated — 19.6 never reached production (refonte not live; local/dev data only), no population to repair.
  - `[low]` `[false]` (intent 5) decisions only in the untracked spec — the spec is committed with the story and the decisions are in the final report.
  - `[low]` `[reject]` (intent 6) consent UI outside the patch — API-only repo; mobile and dashboard keys `m19-6b` / `d19-6b`.

## Design Notes

**Patterns.** Invitation as a small state machine (PENDING → ACCEPTED | DECLINED | CANCELLED, expiry by time) persisted in one row per (company, person) with an atomic conditional upsert (state transitions as SQL guards, no read-then-write race); Façade (`MembershipInvitationService`) + Unit of Work in a separate transactional component run under the target tenant context; Specification kept in `SharedAccountGuard`; Strategy-free notifier reusing the 19.6 after-commit/async channel fallback. `LoginTokenVerifier` is a port so the employee context does not reach into auth's application internals.

**Lock order (review).** Every path takes the per-company advisory lock of member arrivals first, then a row lock (invitation upsert or `FOR UPDATE`): no deadlock between an acceptance and a re-invitation. Message cooldown carried by `last_sent_at` (one message per pair per 24 h). An invitation-joined account stays « shared » (`SharedAccountGuard`, accepted invitation). Stale invitations (store gone, member deactivated) are cancelled at acceptance.

**Complexity (FUS-NFR11).** Add existing: unchanged lookups + one `COUNT` on `(tenant_id, invited_at)` + one upsert on the unique key. Owner list: one indexed query per page (bounded by pending invitations of a company). Invitee list: one query on `(user_id,status)` joined to tenants by PK. Accept: lock by PK, membership lookup by unique key, plan count (existing single query), two inserts, one update. Member list enrichment: +1 set query `= ANY(?)` on the unique key (no per-row loop). Export/audit: one LEFT JOIN per row on the unique key, tenant id resolved once per statement.

## Verification

**Commands:**
- `scratchpad/wt-pkgs.sh <worktree>/keevo/backend t196b identity shared architecture admin` (package by package) -- expected: 0 failures.

**Manual checks:**
- Local backend on 4596 / `keevo_dev_t196b`: owner B registered, owner A adds B's phone → 202; B logs in → `POST /auth/invitations/pending` lists A → accept → `select-tenant` A works; owner A's member list shows the member.

## Auto Run Result

Status: done

**Summary.** Adding the phone or username of an existing account never attaches it any more: the account is invited (`public.membership_invitations`, V74) and only the person's acceptance creates the membership and the employee row. `POST /api/v1/employees` answers the same neutral `202 {employee:null, temporaryPassword:"", existingAccount:true, invitationPending:true}` for any existing account (platform administrator, inactive account, pending invitation, recent refusal: nothing stored or sent); the plan ceiling is checked before the lookup; at most 20 invitations per company per 24 h and one message per person per 24 h (`last_sent_at`). The person answers from the login step (`/api/v1/auth/invitations/**`, loginToken) or an open session (`/api/v1/account/invitations/**`); the company lists and cancels (`/api/v1/employees/invitations`, a manager: his store's EMPLOYEE/VIEWER invitations). Acceptance runs in the inviting company's schema (per-company lock first, then the invitation row), requires a phone or a verified email (`ACCOUNT_RECOVERY_REQUIRED`), an active store and room in the plan; stale invitations are cancelled. The company sees only the identifier it typed (card, list, export, audit actor phone); an invitation-joined account stays the person's own (`MEMBER_ACCOUNT_SHARED`); an OWNER-only account without recovery channel stays resettable by its company's owners. Super-admin member creation follows the same rule (202); the V1 sync push refuses as before 19.6.

**Decisions (product, by default).** D1 consent by invitation (7 days); D2 identifier existence is inherent to uniqueness, everything else hidden; D3 anti-spam (cap 20/24 h per company, 1 message/24 h per person, 30-day silence after a refusal); D4 both identifiers + one used → one generic 409; D5 only the typed identifier visible; D6 recovery channel required to join a second company, OWNER-only accounts without channel stay owner-resettable; D7 WhatsApp else verified email, never SMS, inviter's inbox on acceptance, no notification on refusal.

**Files.** New: V74, `MembershipInvitation`, `MembershipInvitationRepository` + `JdbcMembershipInvitationRepository`, `MembershipInvitationUseCase`, `MembershipInvitationService`, `InvitationAcceptance`, `MembershipInvitationNotifier` (replaces `MemberAddedNotifier`), `EmployeePlanLimit`, `InvitationController` + DTOs, `LoginTokenVerifier`(+service). Changed: `CreateEmployeeService`, `CreateEmployeeCommand`/`Result`, `EmployeeController`, `TempPasswordResponseDto`, `EmployeeRoleResolver`, `SharedAccountGuard`, `User.hasRecoveryChannel`, `EmployeeCommandAdapter`, `CreatedMember`, admin `TenantWriteService`/controller/views, employees export + audit SQL, error codes `INVITATION_NOT_FOUND` (404) / `ACCOUNT_RECOVERY_REQUIRED` (409), `SecurityConfig`, `ViewerWriteGuardFilter`, `docs/entitlements/endpoints.md`. Separate commit: `keevo/contracts/export-datasets.json` regenerated (pre-existing 21-5 drift).

**Review.** 5 layers, 45 findings: 1 high, 12 medium, 22 low, 4 false (+ intent observations). Patched: 1 high (lock-order deadlock), 11 medium, 6 low. Deferred: 1 medium → 19-11 (retention of answered invitations). Rejected: the rest, reasons in the triage log.

**Follow-up review: recommended (true)** — 1 high and 11 medium patched; unverified risk: the concurrent acceptance/re-invitation lock order is reasoned and order-asserted in unit tests, not exercised under real concurrency; V1 2.0.1 behaviour on the 202 is reasoned from its source (generic error), not run on a device.

**Verification.** Packages one by one: identity 891, architecture 147, shared 1 355, admin 690, reporting 462, sync 679 — 0 failures. Live `keevo_dev_t196b` on 4596 (WhatsApp noop): owner A adds B's phone → 202, re-add → 202 with a single stub message, both identifiers → 409 generic, A's pending list shows the typed phone; B's login → pending invitation → accept → 200 (A, EMPLOYEE), second answer → 404; A's team lists B; A's password regeneration on B → 409 `MEMBER_ACCOUNT_SHARED`; bad loginToken → 401; V74 applied. Database dropped, backend stopped.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit b70f0610). Conventions : spec-16-11 ; synchro : spec-16-10. Remplace le point 1 et le rattachement immédiat décrits dans la section mobile de 19.6.

**Ce qui est fait côté API :** ajouter le téléphone ou l'identifiant d'une personne qui a déjà un compte n'en fait plus un membre : elle reçoit une invitation (WhatsApp, sinon email vérifié) qu'elle accepte ou refuse dans l'app. Le patron ne voit que ce qu'il a saisi. Pour rejoindre une deuxième entreprise, le compte doit avoir un téléphone ou un email vérifié. Un membre arrivé par invitation garde toujours la main sur son compte : sur sa fiche, mot de passe et téléphone répondent `MEMBER_ACCOUNT_SHARED` (409), comme en 19.6.

**À utiliser :**
1. `POST /api/v1/employees` (inchangé pour un nouveau compte : 201). Compte existant → **202** `{"data":{"employee":null,"temporaryPassword":"","existingAccount":true,"invitationPending":true}}` — même réponse quel que soit l'état du compte. Erreurs : 409 `MEMBER_ALREADY_IN_COMPANY` / `MEMBER_INACTIVE_IN_COMPANY` (inchangés) ; 409 `USER_ALREADY_EXISTS` « Ce téléphone ou cet identifiant est déjà utilisé : indiquez seulement le téléphone ou seulement l'identifiant de la personne » (les deux champs remplis) ; 429 `RATE_LIMIT_EXCEEDED` « Trop d'invitations envoyées aujourd'hui : réessayez demain » ; `PLAN_LIMIT_EXCEEDED` vérifié avant tout.
2. `GET /api/v1/employees/invitations` (patron : toutes ; gérant : sa boutique) → `[{id, firstName, lastName, role, roleLabel, storeId, identifierKind, identifier, invitedAt, expiresAt}]`. `DELETE /api/v1/employees/invitations/{id}` → 204 ; 404 `INVITATION_NOT_FOUND`.
3. Étape de connexion (avant le choix d'entreprise, aussi pour un compte sans entreprise) : `POST /api/v1/auth/invitations/pending` `{loginToken}` → `[{id, companyName, role, roleLabel, invitedAt, expiresAt}]` ; `POST /api/v1/auth/invitations/{id}/accept` `{loginToken}` → `{tenantCode, tenantName, role, memberRole}` puis `POST /auth/select-tenant` avec le même `loginToken` ; `POST /api/v1/auth/invitations/{id}/decline` `{loginToken}` → 204.
4. Session ouverte (n'importe quel rôle, lecteur compris) : `GET /api/v1/account/invitations`, `POST /api/v1/account/invitations/{id}/accept` (200, même corps), `POST /api/v1/account/invitations/{id}/decline` (204). La session en cours ne change pas : pour ouvrir la nouvelle entreprise, se reconnecter et la choisir.
5. Erreurs à l'acceptation : 404 `INVITATION_NOT_FOUND` « Cette invitation n'existe plus ou a expiré » (retirer de la liste) ; 409 `ACCOUNT_RECOVERY_REQUIRED` « Pour rejoindre une autre entreprise, votre compte doit avoir un numéro de téléphone ou une adresse email vérifiée : demandez au responsable de votre entreprise d'ajouter votre numéro » ; 403 `PLAN_LIMIT_EXCEEDED` « Cette entreprise a atteint la limite de membres de son plan… » (l'invitation reste en attente) ; 404 `INVITATION_NOT_FOUND` « La boutique prévue pour vous n'existe plus : demandez une nouvelle invitation » et 409 `MEMBER_INACTIVE_IN_COMPANY` « Vous avez été désactivé dans cette entreprise : demandez à son responsable de vous réactiver » (invitation annulée : la retirer) ; déjà membre → 200 (l'entreprise est renvoyée) ; 401 `TOKEN_EXPIRED` (loginToken de 5 min : reconnecter). Côté patron : réajouter une personne déjà invitée met à jour l'invitation (rôle, boutique, noms) sans nouveau message ; au plus un message par personne et par 24 h ; un gérant ne voit et n'annule que les invitations Employé / Lecteur de sa boutique.
6. Notification boîte de réception du patron qui a invité : type `MEMBERSHIP_INVITATION_ACCEPTED`, « {Prénom Nom} a rejoint votre équipe ». Le type `MEMBERSHIP_ADDED` de 19.6 n'est plus émis.

Code backend : `keevo/backend/src/main/java/com/keevo/identity/employee/adapter/in/rest/InvitationController.java`, `EmployeeController.java`, `application/service/MembershipInvitationService.java`, `InvitationAcceptance.java`.

**À implémenter côté mobile :**
1. Formulaire d'ajout de membre : sur 202, message « Une invitation a été envoyée. {Prénom} rejoindra l'équipe dès qu'il l'aura acceptée. » ; aucun écran de mot de passe ; ne jamais afficher « cette personne a déjà un compte ».
2. Écran Équipe : section « Invitations en attente » (nom saisi, rôle en français, « expire le … »), action « Annuler l'invitation ».
3. Après connexion (avant le sélecteur) et au démarrage d'une session : si des invitations existent, écran « {Entreprise} vous invite comme {rôle} » avec « Accepter » / « Refuser » ; après acceptation à la connexion, proposer d'ouvrir l'entreprise (select-tenant) ; en session, proposer « Ouvrir maintenant » (reconnexion).
4. Gérer `ACCOUNT_RECOVERY_REQUIRED` avec le message serveur. Fiche membre : le téléphone peut être vide (membre invité par identifiant) — afficher « Non communiqué ».
5. Pas de cache hors ligne des invitations (action en ligne uniquement) ; aucune donnée technique à l'écran.

**Critères d'acceptation mobile / scénarios de test :**
- Given la patronne de B, When le patron de A ajoute son numéro, Then il voit « Une invitation a été envoyée » et l'invitation en attente ; elle reçoit un WhatsApp ; à sa connexion elle voit l'invitation, l'accepte, puis choisit A (vendeuse) ou B (patronne).
- Given une invitation refusée, When le même patron réinvite, Then même message côté patron, rien côté invitée.
- Given un compte avec seulement un identifiant, When il accepte, Then le message de récupération s'affiche.
- Mise en place : deux `POST /auth/register`, puis `POST /api/v1/employees` sur A avec le téléphone du second, puis `POST /auth/login` du second et `POST /api/v1/auth/invitations/pending`.

**Clé de suivi mobile :** `m19-6b-compte-existant-consentement`.

## Dashboard super-admin (autre dépôt)

**Statut :** API livrée (commit b70f0610).

**Ce qui est fait côté API :** `POST /api/v1/admin/tenants/{tenantId}/members` (20.13) suit la même règle : un téléphone qui a déjà un compte crée une invitation (la personne accepte dans l'app).

**À utiliser :** réponse **202** `{"data":{"employeeId":null,"userId":null,"temporaryPassword":null,"existingAccount":true,"member":null,"invitationPending":true}}` ; 201 inchangé pour un nouveau compte, avec `invitationPending:false` (champ additif). Erreurs identiques à `POST /api/v1/employees` (429 `RATE_LIMIT_EXCEEDED`, 409 `MEMBER_ALREADY_IN_COMPANY`…). Code : `keevo/backend/src/main/java/com/keevo/admin/tenantwrite/application/service/TenantWriteService.java`.

**À implémenter côté dashboard :** sur 202, afficher « Invitation envoyée : la personne doit l'accepter dans l'application » et ne pas ouvrir de fiche membre ; aucune donnée du compte invité n'est disponible.

**Critères d'acceptation :** Given un téléphone déjà inscrit, When le super-admin ajoute ce membre, Then le message d'invitation s'affiche et aucun membre n'apparaît avant l'acceptation.

**Clé de suivi dashboard :** `d19-6b-compte-existant-consentement`.
