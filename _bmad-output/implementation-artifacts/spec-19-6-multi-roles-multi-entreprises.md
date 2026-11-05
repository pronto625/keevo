---
title: '19.6 — One person, several roles in several companies (add an existing user as member, protect shared accounts, role from the membership)'
type: 'feature'
created: '2026-11-05'
status: 'done'
baseline_revision: '39477d8df2f0ef3c75f28f920393ef69389c22f5'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
warnings: [oversized]
deferred:
  - summary: >-
      Adding an existing account: consent, identifier confidentiality, recovery of shared username-only members.
    evidence: |-
      Immediate attach lets any owner learn whether a phone/username is registered and attach without consent; attached accounts expose their other identifiers; shared username-only members have no password recovery. Routed to new key 19-6b (product decisions).
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Adding a person who already has an account (by phone or username) as a member fails with `USER_ALREADY_EXISTS`, so someone cannot be owner in one company and employee in another, nor one account for two companies. Several decisions still read the global `users.role`, which is meaningless once a person holds different roles in different companies, and owner actions on a member (password reset, phone change, deactivation) act on the whole account, so with shared accounts an owner of company A could take over the person's access to company B.

**Approach:** When an owner (or a MANAGER within 19.5's rules) adds a member whose phone or username already belongs to an account, add a membership + employee row to that account in the current company (password untouched, the person notified) instead of failing. Make every role decision read the membership of the current company (never `users.role`, except SUPER_ADMIN detection). Restrict account-wide owner actions to accounts that belong to the current company only. Settle the two routed items (create-company and social linking judged on memberships).

## Boundaries & Constraints

**Always:**
- Add existing user (`POST /api/v1/employees`): after validation, look up the account by normalized phone, then by username (19.4). Found:
  - already a membership (active or inactive) in this company → 409 `MEMBER_ALREADY_IN_COMPANY` « Cette personne fait déjà partie de l'entreprise » (an inactive one is reactivated through the existing reactivate endpoint, not here);
  - a SUPER_ADMIN account → 409 `USER_ALREADY_EXISTS` (unchanged);
  - phone and username given and they match two different accounts → 409 `USER_ALREADY_EXISTS`;
  - otherwise: plan limits as today, membership with the requested role (19.5 rules for MANAGER actors), employee row with the request's first/last name and store, no password change, `passwordChangeRequired` false for this employee row, `EmployeeCreatedEvent` published; response 201 with `temporaryPassword: null` and an additive `existingAccount: true` (false for new accounts).
  - notification: one in-app notification in the new company's inbox for that user (19.9 inbox, type `MEMBERSHIP_ADDED`, French title « Vous avez rejoint {nom de l'entreprise} », body with the role label), plus a best-effort platform message after commit — WhatsApp text via `WhatsAppSender.sendReport` when the account has a phone and WhatsApp is configured, else email via `EmailSender` when it has a verified email; failures logged without PII, never fail the request. No SMS (cost).
- Role from the membership: token/session role in `ChangePasswordService` comes from the membership of the current company (like `SelectTenantService`/refresh), not `users.role`. `CreateCompanyService` refuses only when the user already has an active OWNER membership (routed 19.2 item: an employee elsewhere may create his own company). `SocialSignInService.link` links by email when the account is not SUPER_ADMIN and has at least one OWNER membership or no membership at all (routed 19.3 item), else `EMAIL_ALREADY_EXISTS`. A repository query answers « has active OWNER membership » / « has any membership » in one statement each. No remaining authorization decision reads `User.getRole()` except SUPER_ADMIN checks (grep proves it; a test or ArchUnit rule keeps it).
- `users.role` stays written as today for compatibility (new accounts, role changes) but is informational; adding an existing user never changes it.
- Shared-account protection: an account is « shared » when it has a membership in another company (any role, active or not) or is OWNER in this company. For a shared account, these owner/manager actions on the member answer 409 `MEMBER_ACCOUNT_SHARED` « Ce membre utilise aussi Bref Point ailleurs : il gère lui-même son mot de passe et ses identifiants »: set password, regenerate password, change phone or username in `PATCH /employees/{id}`. Name and store changes stay allowed (they live on the employee row). Deactivation of a shared account deactivates only this company's membership and revokes only this company's sessions/refresh tokens (refresh tokens are per device session and tenant — revoke those whose tenant is this company), never the other companies'. For non-shared accounts behaviour is unchanged.
- Tenant picker: `/auth/login` (and OTP/social verify) memberships list every active membership with `tenantName` and role (`role` V1 vocabulary + `memberRole`), already in place — covered by a test with two memberships of different roles.
- Error codes added to `ErrorCode`/`ErrorMessages`/`GlobalExceptionHandler` (409 each).

**Never:**
- No change to how a brand-new member is created, to OWNER/EMPLOYEE/MANAGER/VIEWER permissions (19.5), to plans. No merge of two existing accounts (24.7), no « invite and accept » flow, no owner creating a second company through create-company when he already owns one, no SMS notification, no change to V1 response shapes beyond additive fields.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Phone of an owner of company B added as EMPLOYEE in A | | 201, `existingAccount:true`, `temporaryPassword:null`, membership + employee row in A, password unchanged, inbox + WhatsApp | — |
| Username of a member of B added in A | | 201 as above | — |
| Already member of A (active or inactive) | | — | 409 `MEMBER_ALREADY_IN_COMPANY` |
| Phone → account X, username → account Y | | — | 409 `USER_ALREADY_EXISTS` |
| SUPER_ADMIN phone | | — | 409 `USER_ALREADY_EXISTS` |
| Plan full | | — | existing `PLAN_LIMIT_EXCEEDED` |
| Owner A resets password / changes phone of a shared member | | — | 409 `MEMBER_ACCOUNT_SHARED` |
| Owner A renames / moves store of a shared member | | 200 | — |
| Owner A deactivates a shared member | | A membership inactive, A sessions revoked, B sessions intact | — |
| Login of a person with 2 memberships | | memberships [A: EMPLOYEE, B: OWNER] | — |
| Employee of A calls create-company | no OWNER membership | 201 | — |
| Owner calls create-company | has OWNER membership | — | 409 `COMPANY_ALREADY_EXISTS` |
| Google email of an account that is EMPLOYEE in A and OWNER in B | verified email | linked | — |
| Google email of an account EMPLOYEE only | | — | 409 `EMAIL_ALREADY_EXISTS` |

</intent-contract>

## Code Map

- `identity/employee/application/service/CreateEmployeeService.java:~100-170` -- phone/username uniqueness checks, `insertIfAbsent`, membership + employee creation, temp password; add the existing-account branch.
- `identity/employee/application/service/{SetEmployeePasswordService,RegeneratePasswordService}.java` -- global `withPasswordHash` + `revokeAllByUserId`; add shared-account refusal.
- `identity/employee/application/service/UpdateEmployeeService.java:60-98` -- phone/username change on the global user; refuse for shared accounts.
- `identity/employee/application/service/DeactivateEmployeeService.java:79-106` -- membership deactivate (per tenant) but `refreshTokenRepository.revokeAllByUserId` (global) — restrict to this tenant's refresh tokens for shared accounts (refresh tokens carry the tenant: check `RefreshTokenJpaEntity`).
- `identity/employee/application/service/ChangePasswordService.java:98-134` -- token role from `updatedUser.getRole()`; switch to the membership role of `TenantContext`.
- `identity/auth/application/service/CreateCompanyService.java:~45-55`, `SocialSignInService.java:~150-160` (`link`) -- global-role checks to replace.
- `identity/auth/domain/port/out/UserMembershipRepository.java` + adapter -- add `hasActiveOwnerMembership(userId)`, `hasAnyMembership(userId)`, `hasMembershipOutside(userId, tenantId)`.
- `messaging/notification` inbox port (`PushNotifier.notifyUsers(tenantId, userIds, payload)` 19.9) and `shared/capability/notification/{WhatsAppSender,EmailSender}` -- notification.
- `identity/employee/adapter/in/rest/dto/{CreateEmployeeRequestDto, TempPasswordResponseDto or create response}` -- `existingAccount`.
- `identity/employee/adapter/in/rest/EmployeeTeamScope.java` (19.5) -- MANAGER rules still apply to the added member.
- `shared/domain/exception/ErrorCode.java`, `ErrorMessages.java`, `GlobalExceptionHandler.java`.

## Tasks & Acceptance

**Execution:**
- `UserMembershipRepository` (+ adapter) -- three one-statement queries.
- `CreateEmployeeService` (+ response DTO) -- existing-account branch per the rules; notification after commit (inbox via `PushNotifier`, WhatsApp/email best effort).
- `SetEmployeePasswordService`, `RegeneratePasswordService`, `UpdateEmployeeService` -- `MEMBER_ACCOUNT_SHARED` for shared accounts.
- `DeactivateEmployeeService` (+ refresh token repository method by user and tenant) -- tenant-only revocation for shared accounts.
- `ChangePasswordService` -- membership role.
- `CreateCompanyService`, `SocialSignInService` -- membership-based rules (routed items).
- Error codes, OpenAPI on `POST /employees`.
- Tests -- unit for every matrix row; Postgres tests for the three membership queries and tenant-scoped refresh revocation; full-context: owner of B is added to A as EMPLOYEE by phone → logs in → picker lists A (EMPLOYEE) and B (OWNER) → select A works, select B works → owner A's password reset refused → owner A deactivates him → B session still valid; a test (grep-based or ArchUnit) that `User.getRole()` is only read for SUPER_ADMIN checks in main code.

**Acceptance Criteria:**
- Given a person who is owner of company B, when the owner of A adds her phone as employee, then she keeps one account and one password, sees both companies at login with their roles, and nothing done by A's owner can change her password, identifiers or access to B.
- Given the V1 2.0.1 app, when an owner creates members with new phones, then behaviour and responses are unchanged apart from additive fields.
- Given the full suite run package by package, then it is green.

## Spec Change Log

## Review Triage Log

### 2026-11-05 — Review pass
- verdicts: 27 findings — high 2, medium 5, low 20, false 0, maybe-false 0 (grouped rows list every reviewer finding they cover)
- findings:
  - `[high]` `[patch]` (orchestrator pre-check, intent a) V1 casts `temporaryPassword as String`: null for an attached account crashes the V1 owner app after commit — "" returned, `existingAccount` always present.
  - `[high]` `[patch]` (blind 1, edge 8, vg 1) role change on a shared account revokes all refresh tokens (logs her out of B) and rewrites `users.role` — tenant-scoped revocation, global role untouched for shared accounts; tests added.
  - `[medium]` `[patch]` (patterns 1, blind 2, edge 1, vg other) concurrent attaches: membership check before the per-tenant lock → unique-key 500 — check moved after the lock.
  - `[medium]` `[patch]` (blind 3, edge 2) phone matches an account, username matches none (or reverse): the other identifier is silently dropped — refused 409 with a French detail.
  - `[medium]` `[patch]` (blind 6, edge 3) inactive account attached and notified — refused 409.
  - `[medium]` `[patch]` (vg other) leaderboard `is_owner` and audit `actorRole` read global `users.role` in SQL — read from the membership of the current tenant; arch/grep rule extended.
  - `[low]` `[patch]` (patterns 2) shared rule duplicated in DeactivateEmployeeService — guard overload reused.
  - `[low]` `[reject]` (patterns 3) isShared could be one query — every query is indexed; one extra round trip on rare owner actions.
  - `[low]` `[patch]` (patterns 4, edge 5) notifier sends on the request thread — platform sends on notificationExecutor.
  - `[low]` `[reject]` (patterns, pre-existing) deactivation loads all owners to count them — pre-existing, bounded by owners per tenant (few).
  - `[medium]` `[patch]` (blind 4, intent d) account-existence oracle and attach without consent — product decision routed to new key 19-6b.
  - `[low]` `[patch]` (blind 5) attached account's other identifiers visible to the adding owner — routed to 19-6b (confidentiality decision).
  - `[low]` `[patch]` (blind 7) no password recovery for a shared username-only member — routed to 19-6b.
  - `[low]` `[reject]` (blind 8) member-added WhatsApp is free text (fails with Meta outside 24 h) and embeds the owner's company name — free-text Meta templates are 19-9c; the name is owner-entered business data already shown in the app.
  - `[low]` `[patch]` (blind 9) MEMBER_ALREADY_IN_COMPANY also for a deactivated member — new code MEMBER_INACTIVE_IN_COMPANY pointing to reactivation; « Bref Point » is the final brand (ADR-02).
  - `[low]` `[patch]` (blind 10) ChangePasswordService MEMBERSHIP_NOT_FOUND for SUPER_ADMIN untested — test added (system-tenant membership).
  - `[low]` `[patch]` (blind 11) CreateCompanyService `.orElse(true)` maps a missing user to COMPANY_ALREADY_EXISTS — TOKEN_INVALID; active-OWNER rule identical in create-company and social link.
  - `[low]` `[patch]` (blind 12, edge 6, edge 7) ArchUnit rule fragile (line 0, working directory) and blind to SQL — fixed and SQL check added.
  - `[low]` `[patch]` (blind 13) full-context test leaves schemas behind — cleanup added.
  - `[low]` `[patch]` (blind 14) mocked guard never verified — verify-before-write assertions and inactive-elsewhere case.
  - `[low]` `[reject]` (blind 15) native revoke without clearAutomatically — no entity of that table is loaded in the calling transactions; caller also revokes access sessions.
  - `[low]` `[patch]` (edge 4) WhatsApp failure does not fall back to email — email tried on failure.
  - `[low]` `[patch]` (vg 2) existingAccount false never asserted — controller tests.
  - `[low]` `[reject]` (intent b) tenant-picker UI not exercised — API-only repo; login payload covered by the full-context test.
  - `[low]` `[reject]` (intent c) no add-by-email — the add-member surface takes phone or username (19.4); email accounts are found through those.
  - `[low]` `[reject]` (intent e) WhatsApp/email delivery only unit-tested — end-of-epic device test.
  - `[low]` `[reject]` (intent f, g) shared-account guard beyond the AC and active-OWNER reading — required to keep « nothing done by A's owner can change her access to B »; inactive OWNER membership is not owning.

## Design Notes

**Patterns.** Find-or-attach (idempotent attach of an existing aggregate to a new membership) inside the existing creation Façade; a « shared account » policy evaluated by one repository query and applied as a guard in the account-wide use cases (Specification); role resolution centralised on the membership (single source of truth, the global column demoted to informational). Notification fire-and-forget after commit through existing ports.

**Complexity (FUS-NFR11).** Creation: +1 lookup (phone or username, unique indexes) and +1 `EXISTS` for the membership. Shared-account guard: one `EXISTS` on `user_tenant_memberships(user_id)` (index on user_id or PK prefix — check and add an index on `user_id` if absent). Deactivation: one `UPDATE … WHERE user_id = ? AND tenant_id = ?` for refresh tokens. No loops.

## Verification

**Commands:**
- `cd keevo/backend && mvn -o -q test -Dtest='CreateEmployeeService*Test,*SharedAccount*Test,DeactivateEmployeeService*Test,SetEmployeePassword*Test,RegeneratePassword*Test,UpdateEmployeeService*Test,ChangePasswordService*Test,CreateCompanyService*Test,SocialSignInService*Test,*MultiCompany*Test'` -- expected: green.
- Full suite package by package (scratchpad `run-pkgs-wait.sh`) -- expected: 0 failures.

**Manual checks:**
- Local backend: owner B registered; owner A adds B's phone → 201 `existingAccount:true`; B logs in → two memberships; owner A password reset on B → 409.

## Auto Run Result

Status: done

**Summary.** Adding a member whose phone or username already has an account attaches that account to the company (membership + employee row, password and global role untouched, `temporaryPassword:""` for V1, `existingAccount:true`), under the per-tenant lock; partial identifier matches, inactive accounts, SUPER_ADMIN → 409; already member → `MEMBER_ALREADY_IN_COMPANY` / `MEMBER_INACTIVE_IN_COMPANY`. The person gets an inbox notification and a best-effort WhatsApp (else verified email) message, sent asynchronously. Shared accounts (membership elsewhere or OWNER here) are protected: password set/regenerate and phone change → 409 `MEMBER_ACCOUNT_SHARED`; deactivation and role change revoke only this company's sessions and never rewrite `users.role`. Roles come from the membership of the current company everywhere (change-password tokens, leaderboard `is_owner`, audit `actorRole`, export actor labels); create-company and social linking judged on memberships (routed 19.2/19.3 items done). An ArchUnit + SQL rule forbids reading the global role except for SUPER_ADMIN.

**Files.** New: `SharedAccountGuard`, `MemberAddedNotifier`, tests (`CreateEmployeeServiceExistingAccountTest`, `EmployeeSharedAccountTest`, `MemberAddedNotifierTest`, `MultiCompanyMembershipPostgresIntegrationTest`, `MultiCompanyMemberFullContextTest`, `UserGlobalRoleReadArchTest`). Changed: `CreateEmployeeService`, `ChangeEmployeeRoleService`, `DeactivateEmployeeService`, `SetEmployeePasswordService`, `RegeneratePasswordService`, `UpdateEmployeeService`, `ChangePasswordService`, `CreateCompanyService`, `SocialSignInService`, `UserMembershipRepository`(+adapter), `RefreshTokenRepository`(+tenant-scoped revoke), `EmployeeController`, `TempPasswordResponseDto`, `JdbcSellerLeaderboardRepository`, `AuditLogRepositoryAdapter`, `ExportValues`, error codes `MEMBER_ALREADY_IN_COMPANY`, `MEMBER_INACTIVE_IN_COMPANY`, `MEMBER_ACCOUNT_SHARED` (409).

**Review.** 27 entries (5 layers): 2 high, 5 medium, 20 low. Patched: 2 high (V1 `temporaryPassword` null crash, role change on shared accounts), 4 medium (lock order, partial matches, inactive accounts, global-role SQL reads) and 13 low. Routed: 1 medium + 2 low → new key 19-6b (consent, identifier confidentiality, recovery — product decisions). Rejected: 8, reasons in the triage log. Two suite failures after the export change (interpolation gate, export fixture without membership) fixed.

**Follow-up review: recommended (true)** — 2 high patches; unverified risk: the V1 owner app path with `temporaryPassword:""` (shows an empty password sheet) only reasoned from V1 source, not run on a device; WhatsApp/email notification only unit-tested.

**Verification.** Full suite package by package: 5 651 tests, 0 failures (reporting/shared/commerce/admin re-run after the export fix). Live `keevo_dev`: owner B's phone added to company A → 201 `temporaryPassword:''`, `existingAccount:true`; second add → 409 `MEMBER_ALREADY_IN_COMPANY`; B's login lists A (EMPLOYEE/EMPLOYEE) and B (OWNER/OWNER); owner A's password regeneration on B → 409 `MEMBER_ACCOUNT_SHARED`; B's global role still OWNER.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit 89e4782d). Conventions : spec-16-11 ; synchro : spec-16-10.

**Ce qui est fait côté API :** une même personne peut appartenir à plusieurs entreprises avec des rôles différents ; ajouter quelqu'un qui a déjà un compte le rattache à l'entreprise ; ses identifiants et son mot de passe restent à lui.

**À utiliser :**
1. `POST /api/v1/employees` : réponse `201 {"temporaryPassword","existingAccount","employee"}` ; `existingAccount:true` → `temporaryPassword` vaut `""` : ne pas afficher d'écran de mot de passe, afficher « {prénom} a rejoint votre entreprise avec son compte existant ». Erreurs 409 : `MEMBER_ALREADY_IN_COMPANY` « Cette personne fait déjà partie de l'entreprise » ; `MEMBER_INACTIVE_IN_COMPANY` « Cette personne a été désactivée : réactivez-la » (proposer « Réactiver ») ; `USER_ALREADY_EXISTS` (téléphone et identifiant de deux comptes différents, ou compte désactivé, avec le détail en français).
2. Sur un membre partagé : réinitialiser / définir le mot de passe et changer le téléphone → 409 `MEMBER_ACCOUNT_SHARED` « Ce membre utilise aussi Bref Point ailleurs : il gère lui-même son mot de passe et ses identifiants » (masquer ces actions après ce refus) ; nom et boutique restent modifiables.
3. Connexion : `memberships` liste toutes les entreprises avec `role` et `memberRole` → sélecteur d'entreprise (déjà en place) ; notification « Vous avez rejoint {entreprise} » dans la boîte de l'entreprise rejointe.
4. Un vendeur d'une entreprise peut créer sa propre entreprise (`POST /auth/create-company`, 19.2).

Code backend : `keevo/backend/src/main/java/com/keevo/identity/employee/application/service/CreateEmployeeService.java`, `SharedAccountGuard.java`, `MemberAddedNotifier.java`.

**À implémenter côté mobile :**
1. Écran de sélection d'entreprise : nom et rôle (« Propriétaire », « Gérant », « Vendeur », « Lecteur ») de chaque entreprise ; mémoriser la dernière choisie ; changer d'entreprise depuis les réglages (déconnexion de session puis `select-tenant`).
2. Formulaire d'ajout de membre : gérer `existingAccount` et les trois 409 ci-dessus.
3. Fiche membre : gérer `MEMBER_ACCOUNT_SHARED`.
4. Aucune donnée technique à l'écran (pas de code d'entreprise brut si le nom existe).

**Critères d'acceptation mobile / scénarios de test :**
- Given une patronne d'une entreprise B ajoutée comme vendeuse dans A, When elle se connecte, Then elle choisit A (vendeuse) ou B (patronne) et chaque espace montre ses propres données.
- Given le patron de A sur sa fiche, When il veut réinitialiser son mot de passe, Then le message de compte partagé s'affiche.
- Mise en place : deux `POST /auth/register`, puis `POST /api/v1/employees` sur A avec le téléphone du second.

**Clé de suivi mobile :** `m19-6-multi-roles-multi-entreprises`.

