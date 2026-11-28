---
title: 'P02 — V1 accounts and login parity (employees by phone, identifiers, passwords, terms, account deletion)'
type: 'feature'
created: '2026-11-28'
status: 'review'
baseline_revision: '3f95027b97d38e4fa7f419f010e8ae85073da5fc'
backend_baseline_revision: 'd45c46701177b1b893802307d38e8931718e9001'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/planning-artifacts/parite-keevo-v1-bref/SPEC.md'
  - '{project-root}/_bmad-output/test-artifacts/parite-v1-bref-2026-11-28/lot-A-compte.md'
warnings: ['multiple-goals', 'oversized']
deferred: []
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
