---
title: '24.7 — Identity bridge and collision cases of the Bref migration'
type: 'feature'
created: '2026-11-11'
status: 'done'
baseline_revision: 'd3ec4691a09d97a76b44ffc148d6553ffeae767e'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-24-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-24-2-migration-identite-boutiques-membres.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** 24.2 blocks every Bref owner or member whose phone, email or username already belongs to a Keevo account (`ACCOUNT_EXISTS`). A merchant who uses both apps would be left out of the cutover or end up with two identities (R2). Bref owners who sign in with Google or Apple are imported without their social identity (note `OWNER_SOCIAL_SIGN_IN`), so on day one they could not sign in the way they used to.

**Approach:** Add an identity bridge to the import check:
- a pure decision per Bref account: create, link, invite (member), skip (member) or block;
- one `findMatches` query in `identity.api` that names the matching Keevo accounts.

Linked accounts keep their Keevo account and gain a membership on the imported tenant. A member matched by an unconfirmed phone gets a 19.6b invitation. Ambiguous identifiers block the owner before any write, with listed cases. Bref `socialId`/`provider` become `public.user_authenticators` rows.

## Boundaries & Constraints

**Always:**
- **Matching order:** the E.164 phone first, then the email.
  - A phone match is proven when Bref confirmed the phone (`isVerified`), or when the same Keevo account also matches by a proven email.
  - An email match is proven only when the email is verified on both sides.
- **Owner:**
  - proven match → the Keevo account is kept: hash, identifiers and `users.role` are untouched, and no account is inserted. The OWNER membership and role are added on the imported tenant, so the owner sees two companies at login (19.6);
  - unproven match → `IDENTITY_UNPROVEN`.
- **Member:**
  - proven match → linked: membership with the mapped role, and an employee record with `id` = Bref id and `user_id` = the Keevo account, no password change asked;
  - phone match, unconfirmed → a PENDING invitation (19.6b): identifier PHONE, 7 days, no message, visible once the company is ACTIVE. A member deactivated in Bref is skipped (note);
  - email typed by the owner, unproven → the account is created without that email (note). It is blocked only when no other identifier remains.
- **Blocking before any write:**
  - phone and email of two different Keevo accounts, or two Bref accounts on one Keevo account → `IDENTITY_AMBIGUOUS`;
  - Bref username held by another account → `USERNAME_TAKEN`;
  - Bref username equal to another account's phone → `USERNAME_IS_PHONE`;
  - phone used as another account's username → `ACCOUNT_EXISTS`;
  - Google/Apple identity of another account → `SOCIAL_TAKEN`;
  - inactive or platform-admin target → `LINK_TARGET_UNAVAILABLE`;
  - id already taken by an account this tool did not import → `ACCOUNT_ID_TAKEN`.
- **Records:**
  - a linked or invited account is recorded under the pseudo target `linked_users`, never `users` (so the 24.9 rollback cannot delete it);
  - invitations are recorded under `membership_invitations` and identities under `user_authenticators`;
  - a re-run follows the records, not the current Keevo identifiers.
- **Social identities:** Bref `google`/`apple` + non-blank `socialId` (255 characters at most) → `GOOGLE`/`APPLE` authenticator on the account behind the Bref account (created or linked). Inserted only when nobody holds it and the account has no identity of that provider. Otherwise the import adds a note and never replaces an identity.
- **Set-based:** one match query, one statement per table, no per-row query.
- **Privacy:** no personal value in cases, notes, logs or the report.

**Never:**
- Changing an existing Keevo account (password, phone, email, username, names, `users.role`).
- Sending a message (invitation, welcome).
- Linking by username or by social identity alone (both only block when they point elsewhere).
- Refactoring the shared pipeline core (24.4 and 24.5 work in parallel).
- Rollback and activation (24.9).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Owner phone known, confirmed | Keevo account A has the phone; Bref `isVerified` | COMPLETED, `accountsLinked` 1, A's hash unchanged, OWNER on new tenant, login lists 2 companies, note `OWNER_LINKED` | — |
| Owner unconfirmed | Phone held by A, Bref `isVerified=false` | BLOCKED `IDENTITY_UNPROVEN`, nothing written | 201 BLOCKED |
| Phone vs email | Phone → A, email → B | BLOCKED `IDENTITY_AMBIGUOUS` | 201 BLOCKED |
| Member proven email | Email verified both sides → B | Membership MANAGER on B, employee `user_id`=B, no `pcr`, note `MEMBER_LINKED` | — |
| Member phone unconfirmed | Phone → C | PENDING invitation for C (PHONE, EMPLOYEE), no membership, `membersInvited` 1 | — |
| Member typed email | Email → D unverified, has username | Account created without email, note `MEMBER_EMAIL_DROPPED` | — |
| Username taken / = phone | Bref username held by E, or numeric = E's phone | BLOCKED `USERNAME_TAKEN` / `USERNAME_IS_PHONE` | 201 BLOCKED |
| Google owner | `provider=google`, `socialId` | `user_authenticators` GOOGLE row on the owner's account, `authenticatorsImported` 1 | — |
| Social held elsewhere | Identity linked to another account | BLOCKED `SOCIAL_TAKEN` | 201 BLOCKED |
| Re-run | Same owner | All creation counts 0 (incl. linked, invited, authenticators), DB unchanged | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/identity/api/LegacyIdentityImportPort.java`:
  - `findMatches` → `AccountMatch(kind, userId, active, emailVerified, platformAdmin)`. `findConflicts` becomes a default method derived from it;
  - `createOwnerTenant(owner, linkedAccountId, name)`;
  - `ImportedMember.linkedAccountId` and `userId()`;
  - `inviteMembers`, `importAuthenticators`;
  - `LegacyAccount` social fields, `withId`, `withoutEmail`;
  - `ConflictKind` gains `PHONE_IS_USERNAME` and `SOCIAL`.
  - The 24.2 constructors are kept.
- `.../identity/legacyimport/application/LegacyIdentityImportService.java`: linked owner (no insert), memberships remapped to Bref ids, invitations (7 days, the 19.6b TTL), authenticators.
- `.../identity/legacyimport/application/port/LegacyIdentityStore.java` and `adapter/out/persistence/JdbcLegacyIdentityStore.java`:
  - `MATCHES_SQL`: the 24.2 UNION ALL plus the user id, `PHONE_IS_USERNAME` and `SOCIAL` (index `uq_user_authenticators_provider_subject`);
  - `INSERT_INVITATIONS_SQL`;
  - `INSERT_AUTHENTICATORS_SQL`: data-modifying CTE; the outer read sees the pre-insert holder;
  - employees: `user_id` comes from its own array.
- `.../migration/domain/service/IdentityBridge.java` (new): the decision rules (pure).
- `.../migration/domain/model/IdentityResolution.java` (new): `CREATE` | `LINK` | `INVITE` | `SKIP` | `BLOCK`.
- `.../migration/domain/service/BrefSnapshotValidator.java`:
  - new `validate(snapshot, identities, rules)`. The Bref field rules apply only to created accounts, the social identity joins the duplicate check, and two Bref accounts on one Keevo account block;
  - the 24.2 signature is kept as an adapter.
- `.../migration/domain/service/BrefMappings.java`: `socialProvider`, social fields in `toLegacy`.
- `.../migration/application/BrefOwnerImportService.java`: `check()` resolves each account (from the records first, then through the bridge) and stores the decisions in the run. The check also reads `linked_users` and `membership_invitations`.
- `.../migration/application/ImportRun.java`:
  - `identities` / `identity`;
  - `accountId(brefUserId)`: the mapping later steps must use for Bref user references;
  - counters `accountsLinked`, `membersInvited`, `authenticatorsImported`.
- `.../migration/application/step/IdentityStep.java`: linked owner and its `linked_users` record.
- `.../migration/application/step/MembersStep.java`: create / link / invite / skip.
- `.../migration/application/step/SocialAccountsStep.java` (new, order 55).
- `.../migration/domain/model/{BlockedCase,ImportNote,MigrationRecord}.java`: the new cases, notes and targets.
- Read-only references:
  - `V74__membership_invitations.sql` and `V82` (invitation shape, `decline_count` default);
  - `V48`/`V73` (authenticators; 19.3b allows one identity per provider);
  - `JdbcMembershipInvitationRepository.PENDING_FOR_USER_BASE` (`t.status = 'ACTIVE'`).

## Tasks & Acceptance

**Execution:**
- `identity/api` + `identity/legacyimport/**`: matches, linked owner/member, invitations and authenticators, as in the Code Map.
- `migration/domain/**`: `IdentityBridge`, `IdentityResolution`, the validator overload, mappings, cases, notes and targets.
- `migration/application/**`: check resolution, `ImportRun` additions, the identity, members and social steps.
- Tests:
  - `IdentityBridgeTest`: every rule and the validator effects;
  - `BrefOwnerImportFullContextTest`: link, invite, dropped email, Google identity, 2 companies at login, re-run, and the blocked cases with nothing written;
  - service test expectations.
- No Flyway migration: the existing tables and indexes cover every new access path. No endpoint change. `docs/entitlements/endpoints.md` is unchanged.

**Acceptance Criteria:**
- Given an owner linked to an existing Keevo account, when the import completes, then `migration_records` hold a `linked_users` row to that account and no `users` row with that account's id.
- Given any blocked identity case, when the batch is returned, then its cases carry only codes, French labels and Bref handles, never a phone, email or username.
- Given a re-run after Keevo identifiers changed, when it runs, then the decisions recorded the first time are reused (no new link, invitation or account).

## Spec Change Log

## Review Triage Log

### 2026-11-11 — Review pass
- verdicts: 39 findings — high 0, medium 12, low 23, false 4, maybe-false 0 (the 7 intent-alignment divergences are descriptive; they are logged as rejects with their reason)
- findings:
  - **Design layer (FUS-NFR11)**
    - `[low]` `[reject]` One identity per provider is only checked (`NOT EXISTS`), not enforced by a unique `(user_id, provider)` index.
      - The race needs the person to link Google from the app during the cutover downtime, when the app points at Bref.
      - The 19.3b link path relies on the same check (pre-existing).
      - A V87 index on prod data needs its own duplicate audit.
    - `[low]` `[patch]` `ImportRun.identity` silently fell back to CREATE. It now throws when no decision exists, so a person never gets a second account by omission.
    - `[low]` `[reject]` The 24.2 shims (`findConflicts` default, 4-argument `validate`) are kept on purpose: 24.4 and 24.5 work in parallel on the same module and their tests call the 24.2 signature.
    - `[low]` `[reject]` Nit: the `distinct` key could be `LinkedHashSet<BlockedCase>`. Cosmetic.
    - `[low]` `[reject]` Nit: the 24.7 counters are initialised in a separate loop on purpose, to keep the cherry-pick of the parallel 24.4/24.5 counter lists conflict-free.
  - **Blind hunter**
    - `[medium]` `[defer→routed 24.9]` Invitations expire 7 days after the import while the company is still suspended. Activation (24.9) re-dates them in one statement (`invited_at`, `expires_at`, `last_sent_at`). Routed item added in the epic.
    - `[medium]` `[patch]` The inviter was the operator. The invitation now carries the owner's Keevo account (`run.accountId(owner)`), so the 19.6b acceptance notice goes to the owner. Asserted in the full-context test.
    - `[medium]` `[patch]` `accountId()` mapped an invited member to an account that is not yet a member. It now returns `null` when an invitation record exists. The routed 24.4 item says so.
    - `[low]` `[reject]` An invited member has no employee record with the Bref id. 19.6b creates the employee on acceptance by design (consent). How 24.4 attributes history is covered by its routed item (`accountId` is `null`).
    - `[low]` `[reject]` A recorded invitation is not re-checked when it was declined. A declined invitation must not be re-sent by a re-run (19.6b consent); the owner can re-add the member.
    - `[false]` `[reject]` « A unique phone collision silently skips the user, and the membership points at a missing user. » `insertAccounts` is `ON CONFLICT (id)`, so a phone collision raises, the step's transaction rolls back and the batch is `FAILED`. A re-run re-checks.
    - `[medium]` `[defer→routed 24.9]` Rollback of the new targets: the authenticators added to pre-existing accounts must be deleted, `linked_users` never. Invitations and memberships cascade with the tenant. Routed item added.
    - `[low]` `[reject]` Duplicate identities within one call: the validator refuses duplicate social identities and two Bref accounts on one Keevo account before the port is called.
    - `[low]` `[defer→routed 24.9]` `last_sent_at = now()` holds the first 19.6b message for 24 h. It is folded into the 24.9 re-dating item (reset at activation).
    - `[low]` `[reject]` The 7-day TTL is copied: `MembershipInvitationService.TTL` is package-private in another package, and the copy names its source.
    - `[low]` `[reject]` Unused shims: same as the design-layer row.
    - `[medium]` `[patch]` Missing tests. Added full-context coverage for SKIP, `SOCIAL_NOT_LINKED` through the real SQL, and `SOCIAL_TAKEN` / `ACCOUNT_EXISTS` / `LINK_TARGET_UNAVAILABLE` from the database. The other suggestions are covered at unit level.
    - `[false]` `[reject]` « Soft-deleted or anonymised accounts could be linked. » `public.users` has no soft-delete column. A deleted account is deactivated (`is_active=false`), which gives `LINK_TARGET_UNAVAILABLE`.
    - `[false]` `[reject]` « A member whose only identifier was the dropped email is accepted. » `IdentityBridge` blocks with `IDENTITY_UNPROVEN` when no phone or username remains (tested in `members()`).
  - **Edge-case hunter**
    - `[medium]` `[defer→routed 24.9]` Invitation expiry while suspended: same root cause as the blind-hunter row.
    - `[medium]` `[patch]` A linked member's name longer than 100 characters made the employee insert fail. Names are now cut to 100 code points.
    - `[medium]` `[patch]` A lone `linked_users` record (invitation not inserted) read as a LINK on a re-run. The record is now written only with its invitation.
    - `[low]` `[patch]` An inactive member matching an unavailable Keevo account blocked the owner. SKIP is now decided before the availability check.
    - `[low]` `[patch]` A member's unproven email on another account (phone matched elsewhere) blocked as `IDENTITY_AMBIGUOUS`. It is now ignored for members, because the email is not carried when linking or inviting.
    - `[low]` `[reject]` Re-run of a dropped-email member whose email is over 254 characters. This needs two improbable conditions together; a direct fix would need a recorded flag.
    - `[low]` `[patch]` A SKIP member counted in the « two Bref accounts on one Keevo account » check. Only LINK and INVITE count now.
    - `[low]` `[reject]` An invited member's Google identity is not carried. This is decision D7 (consent first); the person links it from the profile (19.3b).
    - `[low]` `[reject]` (claim) SKIP and dropped-email decisions are re-resolved on a re-run. Nothing was written for them, so a fresh decision is the right one; the spec's claim covers recorded writes.
  - **Verification gap**
    - `[medium]` `[patch]` Records-first re-run was not pinned. The test now deactivates the linked manager's account before the re-run and asserts COMPLETED, zero writes and no case.
    - `[medium]` `[patch]` Authenticator outcomes other than INSERTED were not exercised. Added `socialIdentityNotReplaced`: no second GOOGLE row, note, count 0.
    - `[medium]` `[patch]` The SOCIAL / PHONE_IS_USERNAME arms and the availability columns were never run against the database. Added to `collisionBlocksBeforeAnyWrite`.
      - This exposed a real defect: a phone compared to usernames could never match, since usernames cannot hold « + ».
      - Fixed: the phone is now compared through its E.164 digits and its 9-digit national form, two index-backed arms.
    - `[medium]` `[patch]` The SKIP branch was not exercised end to end. It is now in the main full-context test: no invitation, no membership, note.
  - **Intent alignment** (descriptive)
    - `[low]` `[reject]` An owner matched by a phone Bref never confirmed is blocked rather than linked. This is decision D2, conservative per the common rules (a wrong link hands a company to someone else); real Bref owners sign up by OTP (`isVerified`).
    - `[low]` `[reject]` Members are linked only with proof, otherwise invited (19.6b), skipped or created without the email. These are decisions D3–D4; the epic's routed item names « invitation ou liaison selon la règle 19.6b ».
    - `[low]` `[reject]` A member's username also blocks the owner. The batch is atomic per owner (24.2 rule).
    - `[low]` `[reject]` Extra blocking cases. Each guards an unsafe link (decision D5).
    - `[low]` `[reject]` Operational surfaces are absent from the patch. They are covered by the spec, the epic, sprint-status and the live check (Auto Run Result).
    - `[false]` `[reject]` « No V87. » Not needed: every access path uses an existing index.
    - `[low]` `[reject]` Shared core files are touched. The changes are additive (new constants, enum values, an overload, the decisions map); nothing was refactored.

## Design Notes

**Patterns:**
- **Specification / decision function:** `IdentityBridge.resolve` is pure and unit-tested in isolation. The validator and the steps only consume its `IdentityResolution`.
- **Pipeline:** the social identities are a new `ImportStep` (55), so the 24.2 core is not touched.
- **Facade:** the identity module's `LegacyIdentityImportPort`. The migration module never writes identity tables itself.

**Decisions (taken by default, conservative, 11/11/2026):**
- **D1 Proof.**
  - Phone: Bref `isVerified` (OTP sign-up), or the same account's email proven.
  - Email: verified on both sides. This is Bref's own social-linking rule (`auth.service.ts findOrCreateSocialUser`: unverified or member emails are never linked).
  - The Keevo phone needs no `phone_verified_at`: rows from before 19.1 have none, and the phone is the Keevo sign-in.
- **D2 Unproven owner → block.** The owner is the company's identity, so a manual check is preferred to a wrong link.
- **D3 Unproven member phone → 19.6b invitation** (consent), not a direct link.
  - No message: the person sees the invitation once the company is active.
  - Validity: the 19.6b 7 days. The cutover (ADR-10) activates on the same day, and the owner can re-add the member afterwards.
- **D4 Unproven member email** (typed by the owner in Bref) → dropped, not linked and not blocking, unless it was the only identifier.
- **D5 Username and social identity never link.** They only block when they point to another account. Username collisions block the owner as the epic AC says.
- **D6 Linked accounts are never modified.** The Bref password, names and username are not carried over: the person signs in with the Keevo credentials (note). `users.role` is unchanged (memberships carry the role, 19.6).
- **D7 Social.** One identity per provider and account (19.3b). If the account already holds another identity of that provider, the import adds a note and does not replace it. An invited member's identity waits for the acceptance (not imported). Apple: the subject is team-scoped; the RN app replacing Bref Point keeps the same Apple team (residual risk, runbook 24.13).

**Cost:**
- Check: one match query (7 index-backed lookups over the unnested accounts).
- Owner link: the same statements as 24.2 minus the user insert.
- Members: three statements plus one for invitations.
- Social: one statement (CTE).
- Records: one insert per step.
- No loop issues a query.

## Verification

**Commands:**
- `wt-pkgs.sh <backend> t247 migration identity architecture shared/module`: expected all green.
- Live:
  - restore the 03/11 dump in `bref_src_t247` (read-only user);
  - in `keevo_dev_t247`, create Keevo accounts that collide with the dump's owner and members (phone, email, username, member);
  - backend on port 4647 with the JDBC source, then import, re-run and the blocked variant, reporting aggregates only;
  - drop both databases afterwards.

## Auto Run Result

Status: done (code commit `91e3ac63`).

**Summary.** Identity bridge of the Bref migration. The `ACCOUNT_EXISTS` block of 24.2 is replaced by a decision per Bref account (`IdentityBridge`):
- **create**: a new account;
- **link**: the Keevo account is kept and gets an OWNER or member membership plus an employee record;
- **invite**: a 19.6b PENDING invitation from the owner, without message;
- **skip**;
- **block**: listed cases.

Bref Google/Apple identities are imported into `user_authenticators`. Re-runs follow the records. `ImportRun.accountId` maps Bref user references for the later steps.

**Patches after review.**
- Inviter = owner.
- `accountId` = `null` for an invited member.
- `linked_users` written only with its invitation.
- Names cut to 100.
- SKIP decided before the availability check and left out of the target-uniqueness check.
- A member's unproven email is no ambiguity.
- No silent CREATE default.
- Phone compared to usernames in its E.164 digits and national form. Before this fix the 24.2 check never matched.

**Files.**
- `identity/api/LegacyIdentityImportPort` (matches, linked owner/member, invitations, authenticators)
- `identity/legacyimport/**` (service, store port, JDBC store)
- `migration/domain/model/{IdentityResolution (new), BlockedCase, ImportNote, MigrationRecord}`
- `migration/domain/service/{IdentityBridge (new), BrefSnapshotValidator, BrefMappings}`
- `migration/application/{BrefOwnerImportService, ImportRun}`
- `step/{IdentityStep, MembersStep, SocialAccountsStep (new)}`
- Tests: `IdentityBridgeTest` (new), `BrefOwnerImportFullContextTest` (+3 scenarios, versioned synthetic data), `BrefOwnerImportServiceTest`.
- No Flyway migration (V87 not used), no endpoint (`endpoints.md` unchanged).

**Review.** 39 findings: 0 high, 12 medium, 23 low, 4 false.
- Patched: 13 rows (9 medium, 4 low), plus one more defect found while writing a test (the phone-as-username form).
- Routed: 4 rows to 24.9 (3 items: invitation re-dating at activation, `last_sent_at`, rollback of authenticators on pre-existing accounts).
- Rejected with reasons (Triage Log): 22.
- deferred-work: 0.

Routed items:
- Bref user references through `accountId` → 24.4;
- rollback and activation of the new targets → 24.9;
- Apple team continuity and manual resolution of the blocking cases → 24.13.

**Verification (all green):** migration 46, identity 968, architecture 173 (1 skipped, pre-existing).

**Live check.** Dump of 03/11 restored in `bref_src_t247` (read-only role) → `keevo_dev_t247`, port 4647, WhatsApp noop, FCM off. Aggregates only.
- **Setup:** Keevo account registered through the API with the owner's phone, a verified account with the owner's email, an account holding the admin member's username.
- **Run 1:** BLOCKED (`IDENTITY_AMBIGUOUS`, `USERNAME_TAKEN`), 0 record, no account with the Bref id.
- **Run 2** (email and username collisions removed by hand): COMPLETED.
  - accountsLinked 1, usersCreated 3 (members), memberships 4, plus 24.3 catalogue;
  - Keevo password hash unchanged, OWNER on the imported company (SUSPENDED);
  - login with the Keevo password lists 2 companies;
  - records `linked_users` 1, no `users` record on the pre-existing account;
  - 3/3 member bcrypt hashes identical to Bref.
- **Run 3:** COMPLETED, alreadyImported 23, nothing written.
- **Logs:** no owner phone or email in the import logs. The only occurrences were the dev DEBUG traces of the test's own register/login requests, and the log was deleted.
- Both databases and the role were dropped.

**Follow-up review recommended:** `true`. Nine medium rows were patched, several on the write path: the inviter, the invitation records, `accountId` and the phone-as-username SQL. The re-dating of invitations at activation is not exercised until 24.9.

**Residual risks:**
- Invitations expire 7 days after the import until 24.9 re-dates them.
- The Apple `sub` is valid only under the same Apple team (24.13).
- One identity per provider is enforced by a check, not by an index.

## Mobile (React Native — dépôt AI/bref)

> Conventions : spec-16-11 ; protocole de synchronisation : spec-16-10 (non répétés ici).

### Statut

API livrée (commit `91e3ac63`, branche de la story 24.7, en attente de fusion). **Aucun nouvel endpoint** : la story décide comment un compte Bref arrive dans Keevo quand la personne y a déjà un compte.

### Ce qui est fait côté API

- **Propriétaire Bref déjà inscrit dans Keevo** (même téléphone confirmé, ou même e-mail vérifié des deux côtés) :
  - son compte Keevo est conservé : mot de passe Keevo, identifiants et nom inchangés ;
  - l'entreprise importée s'ajoute à ses entreprises : il en voit **deux** à la connexion.
- **Membre Bref déjà inscrit dans Keevo** :
  - identité prouvée : il est rattaché à l'entreprise importée et **se connecte avec ses identifiants Keevo**, pas avec l'identifiant ni le mot de passe Bref ;
  - sinon : il reçoit une **invitation** (19.6b), visible une fois l'entreprise activée.
- **Compte Google / Apple de Bref** : il est relié au compte Keevo. La connexion Google/Apple fonctionne dès l'activation.

### À utiliser

Parcours existants, sans changement :
- sélecteur d'entreprise (19.6, `memberships` du login) ;
- invitations en attente (19.6b : liste, accepter, refuser) ;
- connexion Google / Apple (19.3, `/api/v1/auth/social/google|apple`).

### À implémenter côté mobile

1. Rien de nouveau. Vérifier seulement ce qui suit :
   - un compte qui a deux entreprises affiche le sélecteur ;
   - les invitations en attente apparaissent après la connexion ;
   - aucun écran ne mentionne la migration de façon technique.

### Implémentation de référence

Aucune dans `keevo/app`.

### Critères d'acceptation mobile / scénarios de test

- **Given** un owner Bref qui avait déjà un compte Keevo avec le même téléphone, **When** il se connecte après l'activation, **Then** l'app lui propose ses deux entreprises.
- **Given** un vendeur Bref dont le téléphone appartient déjà à un compte Keevo, **When** il se connecte à Keevo après l'activation, **Then** l'invitation de l'entreprise importée s'affiche et l'accepter l'ajoute à l'équipe.
- **Given** un owner Bref connecté par Google, **When** il choisit « Continuer avec Google » dans la nouvelle app, **Then** il arrive sur son compte sans code.

### Clé de suivi mobile

`m24-7-bridge-identite-collisions`

## Dashboard super-admin (autre dépôt)

> Conventions admin : section « Dashboard super-admin » de `spec-16-4-console-super-admin-modules.md`.

### Statut

API livrée (commit `91e3ac63`). Mêmes routes que 24.2. Le lot porte de nouveaux compteurs, cas et remarques.

### Ce qui est fait côté API

Un lot d'import peut maintenant :
- rattacher des comptes existants ;
- inviter des membres ;
- reprendre des comptes Google/Apple ;
- bloquer pour des cas d'identité à résoudre à la main.

### À utiliser

`POST /api/v1/admin/migration/bref/owners/{ownerId}/imports` et `GET /api/v1/admin/migration/batches/{batchId}` (`MigrationBatchResponse`) :
- **Nouveaux compteurs :**
  - `accountsLinked` : « Comptes Keevo existants rattachés » ;
  - `membersInvited` : « Membres invités » ;
  - `authenticatorsImported` : « Comptes Google/Apple repris ».
- **Nouveaux cas** (afficher `label`, jamais `sourceId`) : `IDENTITY_AMBIGUOUS`, `IDENTITY_UNPROVEN`, `LINK_TARGET_UNAVAILABLE`, `USERNAME_TAKEN`, `USERNAME_IS_PHONE`, `SOCIAL_TAKEN`. Le libellé de `ACCOUNT_EXISTS` change (« … sans rapprochement possible »).
- **Nouvelles remarques :** `OWNER_LINKED`, `MEMBER_LINKED`, `MEMBER_INVITED`, `MEMBER_INACTIVE_NOT_INVITED`, `MEMBER_EMAIL_DROPPED`, `SOCIAL_NOT_LINKED`. Le libellé de `OWNER_SOCIAL_SIGN_IN` change.

### À implémenter côté dashboard

1. Afficher les trois nouveaux compteurs avec les libellés ci-dessus.
2. Les cas et remarques s'affichent par leur `label`, comme pour 24.2. Aucune donnée personnelle n'y figure.

### Critères d'acceptation dashboard

- **Given** un owner Bref dont le téléphone confirmé est déjà dans Keevo, **When** le super-admin lance l'import, **Then** le lot « Terminé » montre « Comptes Keevo existants rattachés : 1 » et la remarque correspondante.
- **Given** un téléphone et un e-mail appartenant à deux comptes Keevo différents, **Then** le lot « Bloqué » affiche le cas « Rapprochement impossible : plusieurs comptes Keevo correspondent : … ».

### Clé de suivi dashboard

`d24-7-bridge-identite-collisions`
