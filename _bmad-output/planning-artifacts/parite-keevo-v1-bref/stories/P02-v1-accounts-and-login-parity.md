---
title: 'P02 — V1 accounts and login parity (employees by phone, identifiers, passwords, terms, account deletion)'
type: 'feature'
created: '2026-11-28'
status: 'done'
baseline_revision: '3f95027b97d38e4fa7f419f010e8ae85073da5fc'
backend_baseline_revision: 'd45c46701177b1b893802307d38e8931718e9001'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/planning-artifacts/parite-keevo-v1-bref/SPEC.md'
  - '{project-root}/_bmad-output/test-artifacts/parite-v1-bref-2026-11-28/lot-A-compte.md'
warnings: ['multiple-goals', 'oversized']
deferred:
  - summary: >-
      Deleted notification comes back on refresh (lot A gap 16, second half); deep links and announcements.
    evidence: |-
      notification.api.ts deleteOne only marks read; P05 description owns "Deleting a notification really deletes" and deep links. Routed to mp05.
    location: >-
      features/notification/api/notification.api.ts
    severity: low
  - summary: >-
      Account deletion confirmation by typing the owner's phone (V1 delete_account_page) and suspended / trial-expired banners.
    evidence: |-
      Lot A §5 row 78 and §6 / gap 9; P08 owns account status banners. Routed to mp08.
    location: >-
      features/profile/screens/settings-screen.tsx
    severity: low
  - summary: >-
      Account-switch cancel path (closeNewSession, pending-writes predicate) and logout push-token removal have no automated test.
    evidence: |-
      Only shouldAskBeforeSwitch is tested; the IO paths need injectable token/storage/mutation-count dependencies (no RN test harness). Routed to mp09 (switch) and mp05 (push token).
    location: >-
      lib/clear-local-data.ts, features/notification/api/notification.api.ts
    severity: medium
---

<intent-contract>

## Intent

**Problem:** A Keevo V1 employee (phone + password, no username — migration V41) cannot sign in to the React Native app ("Je suis un commercial" only sends `username`), the owner cannot save a V1 employee's record (username required in the form, never sent, no API route to set it), and several V1 account functions are missing or wrong: voluntary password change, forgot password, logout from the forced password change, terms acceptance, account deletion grace period (the app logs out and never shows the cancel window because `scheduledDeletionAt` is always null), push token kept after logout, silent loss of a previous account's unsent data, fake "Actif depuis / Dernière connexion" dates, no per-member activity, no WhatsApp invitation.

**Approach:** Fix each gap of lot A in the app (AI/bref worktree), with three additive backend changes where the API lacks data: `username` accepted by `PATCH /employees/{id}`, and `hasPassword`, `memberSince`, `lastLoginAt`, `scheduledDeletionAt` added to `GET /auth/profile`. Every other function uses existing routes.

## Boundaries & Constraints

**Always:**
- App code in `/var/tmp/keevo-e2e/bref-parite` (branch `feat/parite-keevo-v1`, app paths below relative to it); backend in `keevo/backend` on `refonte/modulaire` (paths relative to `src/main/java/com/keevo`), additive only, V1 2.0.1 compatible (existing fields and shapes unchanged).
- French labels with fr + en keys (other locales fall back); no technical data on screen (no ids, codes, enum names).
- Online-only actions (sign-in, forgot/reset password, password change, deletion cancel, member password) refuse offline with the existing French offline message instead of a spinner.
- Server answers are the truth: after a member update, the local record is overwritten with what `PATCH` + re-read returned; an optimistic value the server did not store never stays on screen.
- No real SMS/WhatsApp: backend runs with messaging off; the system share sheet (invitation) is only opened on the emulator.
- After the story the V1 fixture is unchanged: Awa Ngo `+237690000050` / `Vendeur2024`, `username` NULL, invoicing disabled for `kv_6s0qob`.

**Never:**
- No business-rule change on the server (password rule 8+ chars with a digit, 30-day deletion delay, who may delete/cancel stay as they are).
- No change to other AI/bref branches; no `.env*` or secret committed; no V1 response field removed or renamed.
- No owner password creation flow (an OTP-only owner simply does not see "Changer mon mot de passe").
- Not in scope (other stories): seller access to stock/catalogue (P06), notification deep links/announcements (P05), suspension/trial banners and subscription gauges (P08), offline outbox (P09).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Member login, local phone | "690000050" / "Vendeur2024" | `POST /auth/login {phoneNumber:"+237690000050"}` → session opened | 401 → server French message shown |
| Member login, E.164 / spaced / 00 prefix | "+237 690 00 00 50", "00237690000050", "237690000050" | same `+237690000050` | — |
| Member login, username | "Paul.Vendeur " | `{username:"paul.vendeur"}` | — |
| Ambiguous digits | "12345" (not a valid phone) | sent as username | server refuses → message |
| Edit V1 member, no username | phone present, username empty | saved without username; no "Identifiant requis" error | — |
| Edit member, new username | "awa.ngo" | `PATCH /employees/{id} {username:"awa.ngo"}` → stored, shown | 409 USERNAME_ALREADY_EXISTS "Cet identifiant est déjà utilisé" shown, local value rolled back to server's |
| Member without phone nor username | username emptied, no phone | form error "Indiquez un téléphone ou un identifiant" | not sent |
| Profile dates | `memberSince`, `lastLoginAt` from server | real dates shown | null → "—" |
| Deletion requested | owner confirms | stays signed in; banner "Suppression prévue le {date}" + "Annuler la suppression" on Accueil, Profil, Paramètres | cancel failure → message, banner stays |
| Non-owner | seller/admin in Paramètres | no "Supprimer mon compte"; banner (if pending) without cancel button | — |
| Logout with push token | token saved at sign-in | `DELETE /devices/token {token}` before `/auth/logout`, key cleared | offline/403 → ignored, logout continues (≤ 4 s) |
| Other account signs in with unsent data | `lastUserId` differs, pending writes > 0 | dialog "Données non envoyées" Continuer (erase) / Annuler (new session closed, data kept) | — |
| Forgot password | phone, then code + new password | `/auth/forgot-password` then `/auth/reset-password`; success → back to sign-in | 422 code invalid/expired/locked → server message; weak password checked before sending |

</intent-contract>

## Code Map

Backend (`keevo/backend/src/main/java/com/keevo`):
- `identity/employee/adapter/in/rest/dto/UpdateEmployeeRequestDto.java` -- add optional `username` (`@Size(max=50)`), include in `hasAtLeastOneField`.
- `identity/employee/domain/port/in/UpdateEmployeeCommand.java` -- add `username`; keep a 6-arg constructor for `adapter/in/api/EmployeeCommandAdapter.java:84` (MCP path, no username).
- `identity/employee/application/service/UpdateEmployeeService.java` -- new step before the phone step: `Usernames.requireValid` (`identity/.../Usernames.java`, 422 French message), skip when equal ignoring case, `sharedAccountGuard.refuseIfShared` (like the phone), pre-check `userRepository.findByUsername` on another account → 409 `USERNAME_ALREADY_EXISTS`, then `userRepository.updateUsername` (JDBC `UPDATE public.users SET username=?, updated_at=now() WHERE id=?`; `DuplicateKeyException` → `USERNAME_ALREADY_EXISTS`, transaction rolls back), `user = user.withUsername(...)` so a later JPA save of the phone carries it; `fieldsChanged.add("username")`.
- `identity/auth/domain/port/out/UserRepository.java`, `identity/auth/adapter/out/persistence/impl/UserRepositoryAdapter.java` -- `updateUsername(UUID, String)` (pattern of `setEmailIfAbsent`).
- `shared/infrastructure/export/AuditLabels.java:181` FIELDS -- `entry("username", "Identifiant")`.
- `identity/auth/domain/model/UserProfileData.java`, `identity/auth/application/service/UserProfileService.java`, `identity/auth/adapter/in/rest/dto/UserProfileResponse.java`, `identity/auth/adapter/in/rest/AuthController.java:636` -- additive `hasPassword` (`User.hasPassword()`), `memberSince` (membership `createdAt`, else `user.getCreatedAt()`), `lastLoginAt` (latest `USER_AUTHENTICATED` in the tenant `audit_log`, index `idx_audit_log_user_occurred`), `scheduledDeletionAt` (`Tenant.getDeletionScheduledAt()`, only when status is `DELETION_PENDING`). Keep old `UserProfileData` constructors. New out-port `identity/auth/domain/port/out/LastLoginQueryPort.java` + JDBC adapter (one indexed `MAX` query, tenant search_path as `JdbcEmployeeActivityRepository`).
- Tests: `src/test/java/com/keevo/identity/employee/...UpdateEmployeeService*Test`, `.../auth/...UserProfileService*Test`, controller/IT tests that build `UserProfileResponse` (grep `UserProfileResponse(` / `toProfileResponse`).

App (`/var/tmp/keevo-e2e/bref-parite`):
- `features/authentication/api/auth.api.ts:93` `loginWithCredentials` -- body from `toLoginIdentifier(input)`; `auth.mapper.ts:56` `toUser` -- map `memberSince`→`createdAt`, `lastLoginAt`, `hasPassword`, `tenantStatus`, `scheduledDeletionAt` (no more `new Date()`); `requestAccountDeletion`/`logout` (push token removal first); new `forgotPassword`, `resetPassword`; types in `features/authentication/types/auth.types.ts`, `repository/auth.repository.ts`, `services/auth.service.ts`, `auth.inMemory.ts`.
- `lib/utils/login-identifier.ts` (new, pure) -- `toLoginIdentifier(raw) → {phoneNumber}|{username}` with `libphonenumber-js` default country CM; `scripts/login-identifier.test.mjs`.
- `features/authentication/screens/login-by-credentials-screen.tsx` -- label "Téléphone ou identifiant", `keyboardType` default, link "Mot de passe oublié ?" → `/auth/login/forgot-password`.
- `features/authentication/screens/forgot-password-screen.tsx` + `app/auth/login/forgot-password/index.tsx` (new) -- step 1 phone (`AppPhoneField`), step 2 code (6 digits) + new password + confirm (8+ chars and a digit), resend after 60 s.
- `features/authentication/screens/change-password-screen.tsx` -- prop `mode: 'forced'|'voluntary'`; forced: add "Se déconnecter" (`useLogout`); voluntary: back button, success toast + `router.back()`. Route `app/profile/password/index.tsx` (new, register in `app/_layout.tsx` Stack).
- `features/authentication/hooks/use-request-account-deletion.ts` -- no logout; invalidate `CURRENT_USER_QUERY_KEY`. `use-restore-account-deletion.ts` unchanged.
- `components/account-recovery-watcher.tsx` → replaced by `components/account-deletion-banner.tsx` (inline banner, cancel button for owner), rendered in `features/dashboard/screens/dashboard-screen.tsx` (near `FreshnessNotice` :270), `features/profile/screens/profile-screen.tsx`, `features/profile/screens/settings-screen.tsx`; remove the watcher from `app/_layout.tsx:400`.
- `features/profile/screens/settings-screen.tsx:224` -- danger zone only for `isOwner` and only when no deletion is pending.
- `features/profile/screens/profile-screen.tsx:85-93` -- member contact row shows phone and/or username; "Actif depuis" = `createdAt` (memberSince), "Dernière connexion" = `lastLoginAt`; row "Changer mon mot de passe" when `hasPassword`.
- `features/notification/api/notification.api.ts:34` -- remember the saved token (AsyncStorage `@bref/push-token`); new `removePushToken()` (`DELETE /devices/token`, 4 s timeout, errors ignored); repository/service interfaces.
- `features/authentication/hooks/use-logout.ts` / `auth.api.ts logout` -- `removePushToken()` before `/auth/logout`.
- `lib/clear-local-data.ts:61` `switchDeviceUserIfNeeded` -- when the user changes and `queryClient.isMutating() > 0`, await `confirmPreviousAccountData(count)` (promise over `components/ui/confirm-dialog.tsx`); refusal throws `PreviousAccountDataKeptError`; login hooks (`use-login-with-credentials`, `use-verify-otp`, `use-login-with-google`, `use-login-with-apple`, `use-complete-registration`) then clear the new tokens and stay on the sign-in screen with a French message. Pure decision `shouldAskBeforeSwitch(last, next, pending)` in `lib/account-switch.ts` (new, no React Native import), tested.
- `features/user/screens/user-form-screen.tsx` -- phone field (`AppPhoneField`, optional) + username optional when a phone is given (create and edit); validation "Indiquez un téléphone ou un identifiant"; send `phone` and `username`.
- `features/user/api/user.api.ts:122` `update` -- send `username` when changed, phone in E.164 when changed; `toNewEmployee` already sends phone/username.
- `features/user/hooks/use-update-user.ts` -- `onSuccess(serverUser)` writes the server's values to the local record.
- `features/user/screens/user-detail-screen.tsx` -- login identifier = username or phone; phone row; activity card (owner/admin): last login, sales today/week (count + FCFA), session active, from `GET /employees/activity` (one call, cached `['employees','activity']`, shared with `getStats`); action "Envoyer l'invitation" → `Share.share` with `buildInviteMessage` (no password ever).
- `features/user/utils/invite-message.ts` (new, pure) + `scripts/invite-message.test.mjs`.
- `features/authentication/screens/register-screen.tsx` and `register-name-screen.tsx` -- `components/terms-acceptance.tsx` (checkbox + links), continue/Google/Apple disabled until checked.
- `constants/legal.ts` (new) -- `TERMS_URL = https://brefpoint.app/cgu`, `PRIVACY_URL = https://brefpoint.app/confidentialite` (used by `features/user/screens/plan-screen.tsx:24`); `features/help/screens/help-screen.tsx` -- section "Informations légales" opening both in the in-app browser (`expo-web-browser`).
- `lib/i18n/locales/{fr,en}/{auth,profile,user,common}.json` -- new keys.

## Tasks & Acceptance

**Execution:**
- Backend `UpdateEmployee*` + `UserRepository*` + `AuditLabels` -- username on PATCH -- V1 employees need an identifier set by the owner.
- Backend profile fields + `LastLoginQueryPort` -- real dates, password presence, deletion date -- no fake values in the app.
- Backend tests: unit tests of `UpdateEmployeeService` (set, same, taken → 409, invalid → 422, shared → 409, phone+username together keeps both) and `UserProfileService` (fields, pending vs active tenant), controller mapping; run `mvn -q test -Dtest='...'` per package.
- App `lib/utils/login-identifier.ts` + test -- matrix rows 1–4.
- App auth API/mapper/types, login screen, forgot password screen + route, change password modes + route, profile rows, settings danger zone, deletion banner, logout token removal, previous-account dialog, terms acceptance + legal links, member form/API/hook/detail/activity/invite, i18n -- per Code Map.
- `scripts/invite-message.test.mjs`, `scripts/account-switch.test.mjs` -- pure helpers.

**Acceptance Criteria:**
- Given Awa Ngo (V1, phone only) on the emulator, when she taps "Je suis un commercial" and types `690000050` / `Vendeur2024`, then she reaches the app home (or the forced password screen if the server requires it) and the server audit shows her `USER_AUTHENTICATED`.
- Given the owner opens Équipe › Awa Ngo › Modifier, when he saves without identifier, then the save succeeds; when he types `awa.ngo` and saves, then `public.users.username = 'awa.ngo'` and the detail shows it; when he types an identifier already used, then "Cet identifiant est déjà utilisé" appears and the screen shows the server value.
- Given a member with a password, when he opens Profil › Changer mon mot de passe, types the current and a new valid password, then the change succeeds, he stays signed in and can sign in again with the new password.
- Given the sign-in screen, when Awa uses "Mot de passe oublié ?" with her number, enters the code and a new password, then she can sign in with the new password (code read by test from the database).
- Given the forced password change screen, when the member taps "Se déconnecter", then he returns to the sign-in screen without a session.
- Given the owner deletes the account in Paramètres, then he stays signed in, the banner with the date 30 days later shows on Accueil/Profil/Paramètres, writes are refused with the server's French message, and "Annuler la suppression" sets the company back to `ACTIVE` and removes the banner.
- Given the register screen, when the terms box is not checked, then Continue/Google/Apple are disabled; Aide shows "Conditions d'utilisation" and "Politique de confidentialité" read-only.
- Given the owner on a member detail, then last login and sales today/this week are shown, and "Envoyer l'invitation" opens the system share sheet with a text containing the member's name, shop and login identifier and no password (emulator only).
- Given the scenarios above, then videos `videos/P02-*.mp4` and `videos/P02.md` exist, DB effects are checked, and Awa's `username` is back to NULL with password `Vendeur2024` at the end.

## Spec Change Log

## Review Triage Log

### 2026-11-28 — Review pass
- verdicts: 54 findings — high 0, medium 12, low 30, false 10, maybe-false 2
- findings:
  - `[medium]` `[patch]` NFR11: last-login query filters `action` row by row on `idx_audit_log_user_occurred` (EXPLAIN: Index Scan + Filter), O(rows of the user) per /auth/profile, javadoc claims O(log n) — core V5 partial index `idx_audit_log_user_login (user_id, occurred_at DESC) WHERE action='USER_AUTHENTICATED'`, query `ORDER BY … LIMIT 1`, javadoc fixed.
  - `[low]` `[patch]` NFR11: member edit reloads the team 2–4 times; onSettled invalidates the detail just written — `['users', id]` excluded from the onSettled invalidation (second `findEmployee` inside `update()` predates P02, left).
  - `[low]` `[patch]` NFR11: `useMemberActivity` inline `select` re-runs the find each render — wrapped in useCallback.
  - `[low]` `[defer]` Intent: gap 16 second half (deleted notification comes back) untouched — owned by P05 ("Deleting a notification really deletes"), routed to mp05.
  - `[low]` `[defer]` Intent: §5 typing the owner's phone to confirm deletion not done — routed to mp08 (account admin parity).
  - `[low]` `[reject]` Intent: temporary password shown at creation (§8 row 110) — lot A rates the owner-chosen password DIFFÉRENT (acceptable); the phone half is done (phone field on the form).
  - `[false]` `[reject]` Intent: invitation cannot be re-sent from the activity view — the invite button and the activity card are on the same member detail screen.
  - `[low]` `[defer]` Intent: suspended / trial-expired banners (§6, gap 9) not rendered — explicitly in P08 scope ("Account status banners"), routed to mp08.
  - `[low]` `[defer]` Intent: deep links and announcements (§2, §9) — P05 scope, routed to mp05.
  - `[false]` `[reject]` Intent: item 11 uses `/employees/activity` instead of `/sales/history` — the intent allows "or reporting"; this is the server's per-employee sales reporting (set-based, today/week).
  - `[low]` `[reject]` Intent: optimistic display before the server answers — the app is offline-first by product rule 2; refusals now restore the server's values and success overwrites with them; a literal "never" would forbid offline edits.
  - `[low]` `[patch]` Intent: end-to-end claims rest on pure-function tests and videos — same group as the verification-layer patches (controller, adapter and mapping tests added).
  - `[false]` `[reject]` Intent: push token not removed for devices registered before the update — `app/_layout.tsx:221` re-saves the token at every start, which now stores the key on the first launch of this version.
  - `[low]` `[patch]` Blind: "Changer mon mot de passe" offered while DELETION_PENDING but the server refuses every write except the cancel — row hidden while a deletion is scheduled (forced mode for a pending tenant is pre-existing server behaviour).
  - `[low]` `[reject]` Blind: `removePushToken` clears the key when the DELETE fails — spec matrix says offline/403 ignored; the next account signing in re-registers the same device token to itself; a retry queue is more than a direct fix and rarely met.
  - `[false]` `[reject]` Blind: "Dernière connexion" shows the current session — it is the account's last sign-in (USER_AUTHENTICATED), true by definition; V1's account page has no such field to diverge from (lot A §5).
  - `[medium]` `[patch]` Blind: index claim wrong / no adapter test — grouped with the NFR11 index row; Postgres IT added.
  - `[medium]` `[patch]` Blind: terms skipped when a company is created from the login screen via Google/Apple, asked twice on register — acceptance persisted on the device; register-organization asks only when not yet accepted; already-accepted screens start ticked.
  - `[false]` `[reject]` Blind: new keys only in fr/en — the spec states fr + en with fallback for other locales (same rule as P01).
  - `[low]` `[patch]` Blind: orphaned `profile.recoveryDialog.*` keys — removed from the 7 locales.
  - `[low]` `[patch]` Blind: no download link in invitations (`EXPO_PUBLIC_APP_DOWNLOAD_URL` never defined) — Play Store link by default; French-only text rejected (V1 invitation is French only, recipients are Cameroonian members).
  - `[false]` `[reject]` Blind: pending-writes check ignores local DB unsynced changes — the app has no WatermelonDB `synchronize`; every user write goes through a keyed react-query mutation (`lib/sdk/mutation-defaults.ts`), same definition as the logout warning.
  - `[low]` `[patch]` Blind: member creation password checked with min 6 vs server 8+digit — `isStrongPassword` used in the form and by `assertStrongPassword`.
  - `[low]` `[reject]` Blind: `toUser` writes '' dates / memberSince in updatedAt — only consumers are profile-screen (guards falsy) and reports-screen (falls back to now on falsy); nothing reads updatedAt of the current user.
  - `[low]` `[patch]` Blind: forgot-password gaps (hardware back, stale fields, username-only member, no prefill) — back handled, fields cleared, hint added; prefill rejected (one field to retype, not worth state passing).
  - `[low]` `[patch]` Blind: username identical to a valid phone routes sign-in to the phone path — form refuses such a username; return-value-0 claim false (user row loaded in the same transaction); controller 409/422 mapping covered by GlobalExceptionHandler entries already tested.
  - `[low]` `[patch]` Blind: several pieces untested — grouped with the verification-layer patches.
  - `[medium]` `[patch]` Verification: profile JSON only checks `.exists()` / hasPassword=false — exact values and a hasPassword=true fixture added.
  - `[medium]` `[patch]` Verification: PATCH /employees username never sent through the controller — username-only PATCH controller test added.
  - `[medium]` `[patch]` Verification: `JdbcLastLoginQueryAdapter` SQL untested — Postgres IT added.
  - `[medium]` `[patch]` Verification: `updateUsername` SQL and DuplicateKeyException path untested — cases added to UserUsernamePostgresIntegrationTest.
  - `[medium]` `[patch]` Verification: app profile mapping (DELETION_PENDING gate, hasPassword) untested — pure helper extracted and tested.
  - `[medium]` `[defer]` Verification: account switch cancel path (closeNewSession, predicate) untested — needs injectable token/storage/mutation deps; routed to mp09.
  - `[medium]` `[patch]` Verification: PATCH body diff untested — pure builder extracted and tested; logout token-removal test deferred to mp05 (push token lifecycle, needs AsyncStorage/apiClient injection).
  - `[false]` `[reject]` Verification (other): last login = current session — same refutation as the Blind row.
  - `[medium]` `[patch]` Edge: activity request failure shows "Jamais connecté / 0 FCFA" — error line instead of defaults.
  - `[low]` `[patch]` Edge: unchanged stored username re-validated in edit — rule skipped when unchanged.
  - `[low]` `[patch]` Edge: clearing an existing phone silently kept — French error shown.
  - `[low]` `[patch]` Edge: all-digit username equal to a valid phone — same group as the Blind row (form refusal).
  - `[false]` `[reject]` Edge: devices registered before the update — same refutation as the Intent row (`_layout.tsx:221`).
  - `[low]` `[reject]` Edge: key removed on failed DELETE — same as the Blind row.
  - `[low]` `[patch]` Edge: re-sending to the same number within 60 s — returns to the code step without a request.
  - `[low]` `[patch]` Edge: second sign-in screen stacked after reset — `router.back()` when possible.
  - `[low]` `[patch]` Edge: username-only member has no recovery path — hint to ask the owner.
  - `[low]` `[patch]` Edge: invite shown on owner/manager detail — hidden for owner and superadmin targets.
  - `[low]` `[reject]` Edge: a second confirmDialog replacing the account-switch question leaves the promise pending — nothing else opens a dialog during sign-in; a queue in the shared host is more than a direct fix.
  - `[low]` `[reject]` Edge: deletion request succeeds but the profile refetch fails — needs a network drop in the same second; the next profile read (pull-to-refresh, start) shows the banner.
  - `[low]` `[reject]` Edge: DELETION_PENDING with a null date — `AccountDeletionService.requestDeletion` always sets the date; only the legacy-import parking (+100 years) differs and it has a date too.
  - `[maybe-false]` `[reject]` Edge: audit query failure → /auth/profile 500 — audit_log is a foundation table of every tenant (ensureFoundation before each request); if true it would be low.
  - `[false]` `[reject]` Edge: "Dernière connexion" current session — same refutation as the Blind row.
  - `[false]` `[reject]` Edge: technical social username overwritten breaks social mapping — social sign-in maps through `user_authenticators (provider, subject) → user_id`, not the username.
  - `[medium]` `[patch]` Edge: company created via login Google/Apple without terms — same group as the Blind terms row.
  - `[low]` `[patch]` Edge: O(log n) claim — same group as the NFR11 index row.
  - `[maybe-false]` `[reject]` Edge: persisted paused mutations not hydrated at a cold-start login count 0 — PersistQueryClientProvider restores before the sign-in screens are usable; would be low if true.

## Design Notes

Patterns. App: **adapter/mapper** at the API boundary (`auth.api.ts`, `user.api.ts`) keeps screens on app types; **pure functions** for every decision (identifier detection, invitation text, account-switch decision) so they are unit-tested without React; **promise-returning dialog host** (`confirmDialog` wrapped) for the account-switch question, same pattern as `chooseCompany`. Backend: **ports and adapters** — a read port for the last login keeps SQL out of the service; **constraint as source of truth** for username uniqueness (pre-check for the common case, unique index + `DuplicateKeyException` mapping for the race; the whole update rolls back).

Costs. `GET /auth/profile`: +1 indexed query (`MAX(occurred_at)` on `idx_audit_log_user_occurred (user_id, occurred_at DESC)`, O(log n)); tenant and membership rows are already loaded. `PATCH /employees/{id}` with username: +1 lookup by `lower(username)` (unique index) + 1 update. Member detail: one `GET /employees/activity` (set-based query already used by the team stats, react-query cached 60 s, shared key) instead of one call per member. Logout: +1 `DELETE /devices/token` bounded by a 4 s timeout. No loops over rows or tenants; no full-list refetch on focus (queries keep their existing stale times).

Identifier rule (`toLoginIdentifier`): strip spaces, dots, dashes, parentheses; if the rest is `+digits`, `00digits` or digits only and `parsePhoneNumberFromString(…, 'CM')` is valid → `{phoneNumber: E.164}`; otherwise `{username: normalizeIdentifier(raw)}`.

## Verification

**Commands:**
- `cd keevo/backend && mvn -q test -Dtest='UpdateEmployeeService*Test'` then `-Dtest='UserProfileService*Test'` then the controller tests touched -- expected: pass (one package at a time).
- `cd /var/tmp/keevo-e2e/bref-parite && pnpm exec tsc --noEmit` -- expected: no error in changed files (43 pre-existing errors in untouched files at baseline).
- `cd /var/tmp/keevo-e2e/bref-parite && pnpm lint` -- expected: no new problem versus baseline (6).
- `cd /var/tmp/keevo-e2e/bref-parite && pnpm test` -- expected: all pass.

**Manual checks (if no CLI):**
- Release APK on `emulator-5554`, backend restarted with messaging off, scenarios filmed (`videos/P02-*.mp4`, index `videos/P02.md`), DB effects checked, screenshots read.

## Auto Run Result

**Summary.** Keevo V1 account/login parity in the RN app: V1 employees sign in by phone or username ("Je suis un commercial"), the owner gives an optional username to a phone-only member (really stored, server value written back), member phone shown and editable, voluntary password change (Profil), forgot password (code + new password), "Se déconnecter" on the forced change, terms checkbox at registration (kept on the device, also asked on the company step after a Google/Apple creation from the login screen) and read-only legal pages in Aide, account deletion keeps the owner signed in with a banner and "Annuler la suppression" (danger zone owner-only), push token removed at logout, question before erasing a previous account's unsent writes, real "Actif depuis" / "Dernière connexion", member activity card, invitation through the system share sheet (no password, Play Store link).

**Commits.**
- App (AI/bref `feat/parite-keevo-v1`, pushed to origin): `1f7220edd35cb5c591535871537b4f02a4657f31` (implementation), `c0dcff8685cc4def41754c6a0766308bcd9f0023` (server values written back), `c5400dc4173575379ab7a65392861eaa47df5b4a` (review fixes).
- Backend (`refonte/modulaire`): `d9ceb068987b07eec81c45ced793f647a6df8688` (PATCH /employees username; profile hasPassword, memberSince, lastLoginAt, scheduledDeletionAt), `4b8cc5cc5950c893f09c6c5959c3caad3e2f1958` (review: core V5 partial index `idx_audit_log_user_login`, controller and Postgres tests).

**Files changed (main).**
- Backend: `UpdateEmployeeRequestDto/Command/Service` (username step), `UserRepository(Adapter).updateUsername`, `AuditLabels` ("Identifiant"), `UserProfileData/Service/Response`, `AuthController.toProfileResponse`, `LastLoginQueryPort` + `JdbcLastLoginQueryAdapter`, `db/modules/core/V5__audit_log_user_login_index.sql`; tests `UpdateEmployeeServiceTest`, `UserProfileServiceTest`, `AuthControllerProfileTest`, `EmployeeControllerTest`, `JdbcLastLoginQueryAdapterPostgresIntegrationTest`, `UserUsernamePostgresIntegrationTest`, `FoundationMigrationsPostgresIntegrationTest`, tenant shape.
- App: `lib/utils/login-identifier.ts`, `lib/utils/profile-fields.ts`, `lib/utils/password-rule.ts`, `lib/account-switch.ts`, `lib/previous-account-dialog.ts`, `lib/clear-local-data.ts`; `features/authentication/{api,hooks,screens}` (forgot-password screen, change-password modes, login label + link, register terms); `components/{account-deletion-banner,terms-acceptance}.tsx` (watcher removed); `constants/legal.ts`; `features/notification/api` (push token kept/removed); `features/user/{api,hooks,screens,utils}` (phone/username form, PATCH builder, server write-back, activity card, invitation); profile/settings/help/dashboard screens; fr + en keys; scripts `login-identifier`, `account-switch`, `invite-message`, `profile-fields`, `employee-patch` tests.

**Review findings.** 54 rows (medium 12, low 30, false 10, maybe-false 2): 29 patch rows applied (index + adapter IT, controller/Postgres/mapping/PATCH-body tests, terms persistence and company-step gate, activity error state, form rules, forgot-password navigation, invite link/target, deletion-pending password row, orphan keys, refetch and select tidy-ups); 5 deferred (notification deletion and deep links → mp05; typed-phone deletion confirmation and suspended/trial banners → mp08; account-switch cancel-path and logout token tests → mp09 / mp05); 20 rejected with reasons in the triage log (fallback locales by spec, last login = last sign-in by definition, no WatermelonDB sync to count, devices re-save the token at each start, rare low cases needing more than a direct fix).

**Follow-up review recommendation: true.** Patched medium entries: 7 groups (index, terms gate, activity error, profile JSON, PATCH username controller, adapter SQL, updateUsername SQL, profile mapping, PATCH body); high 0. Unverified risk: the terms gate on the company step after a Google/Apple creation from the login screen was not played on a device (no Google account on the emulator); the push-token removal and the previous-account question were only code-reviewed and unit-tested (no push token on the emulator, no reliable way to keep a write pending across a session loss).

**Verification.**
- Backend, one class at a time: UpdateEmployeeServiceTest 19, UserProfileServiceTest 11, AuthControllerProfileTest 4, EmployeeControllerTest 30, JdbcLastLoginQueryAdapterPostgresIntegrationTest 2, UserUsernamePostgresIntegrationTest 5, FoundationMigrationsPostgresIntegrationTest and TenantShapePostgresIntegrationTest green.
- App: `pnpm test` 33/33; `tsc` 42 errors, all in untouched pre-existing files (baseline 43); `pnpm lint` 5 problems (baseline 6), none new.
- Device: release APK (x86_64 + arm64) of `c5400dc` on emulator-5554, backend restarted with WhatsApp noop (log: "provider noop: stub (nothing sent)"), V5 index present in `kv_6s0qob`. Videos P02-01..10 (pre-review build) and P02-11..12 (review build), index `test-artifacts/parite-v1-bref-2026-11-28/videos/P02.md`, all OK. DB: Awa `username` NULL, phone `+237690000050`, `Vendeur2024` accepted by `/auth/login`, no forced change; `kv_6s0qob` ACTIVE.

**Residual risks.** Awa's last name is "Ngono" in `keevo_e2e` (changed on 2026-11-28 04:37 UTC, before this story's run); the SPEC calls her "Awa Ngo". Bref light link colour on the terms line is the pre-existing Bref yellow (contrast tracked in mp10). iOS not tested.

