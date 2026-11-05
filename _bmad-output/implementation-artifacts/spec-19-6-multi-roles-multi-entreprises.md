---
title: '19.6 — One person, several roles in several companies (add an existing user as member, protect shared accounts, role from the membership)'
type: 'feature'
created: '2026-11-05'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
warnings: [oversized]
deferred: []
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

## Design Notes

**Patterns.** Find-or-attach (idempotent attach of an existing aggregate to a new membership) inside the existing creation Façade; a « shared account » policy evaluated by one repository query and applied as a guard in the account-wide use cases (Specification); role resolution centralised on the membership (single source of truth, the global column demoted to informational). Notification fire-and-forget after commit through existing ports.

**Complexity (FUS-NFR11).** Creation: +1 lookup (phone or username, unique indexes) and +1 `EXISTS` for the membership. Shared-account guard: one `EXISTS` on `user_tenant_memberships(user_id)` (index on user_id or PK prefix — check and add an index on `user_id` if absent). Deactivation: one `UPDATE … WHERE user_id = ? AND tenant_id = ?` for refresh tokens. No loops.

## Verification

**Commands:**
- `cd keevo/backend && mvn -o -q test -Dtest='CreateEmployeeService*Test,*SharedAccount*Test,DeactivateEmployeeService*Test,SetEmployeePassword*Test,RegeneratePassword*Test,UpdateEmployeeService*Test,ChangePasswordService*Test,CreateCompanyService*Test,SocialSignInService*Test,*MultiCompany*Test'` -- expected: green.
- Full suite package by package (scratchpad `run-pkgs-wait.sh`) -- expected: 0 failures.

**Manual checks:**
- Local backend: owner B registered; owner A adds B's phone → 201 `existingAccount:true`; B logs in → two memberships; owner A password reset on B → 409.
