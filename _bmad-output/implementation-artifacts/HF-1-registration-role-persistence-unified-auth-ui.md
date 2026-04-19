# Story: HF-1 — Registration Role Persistence & Unified Auth UI

## Story

**As a** new shop owner registering on Keevo,
**I want** my OWNER role to be recognized immediately after registration and onboarding,
**so that** I see the correct dashboard and owner-specific features without needing to log out and back in.

**As a** user (new or returning),
**I want** a single authentication screen that handles both registration and login,
**so that** I don't have to choose between two separate pages.

## Status

**Status:** done
**Created:** 2026-04-19
**Priority:** HIGH (BUG) + MEDIUM (UX)

## Context

### Bug: OWNER Treated as EMPLOYEE After Registration

After a new user registers and completes the onboarding wizard, they land on `/pos` with no OWNER-specific features visible. Only after logout + re-login does the OWNER role take effect.

**Root Cause (confirmed via code analysis):**

The `Registration` notifier in `auth_provider.dart` (lines 149-160) calls `registerUserUseCaseProvider.execute()` which:
- ✅ Persists JWT to `FlutterSecureStorage` via `TokenStorage.saveToken()`
- ✅ Persists userId and tenantId
- ❌ Does NOT persist role to `SharedPreferences` (`kUserRoleKey`)
- ❌ Does NOT persist phone to `SharedPreferences` (`kUserPhoneKey`)
- ❌ Does NOT set `activeStoreIdProvider`
- ❌ Does NOT invalidate `currentUserRoleProvider`

In contrast, the `Login` notifier (lines 171-196) DOES persist all of these after login.

**Impact chain:**
1. `currentUserRoleProvider` reads `kUserRoleKey` from SharedPreferences → returns `null` after registration
2. Splash redirect (app_router.dart L306): `role == 'OWNER'` is false when null → routes to `/pos` instead of `/dashboard`
3. Owner-only UI guards (settings, team, reports) check `currentUserRoleProvider` and hide features when role is null
4. After logout + re-login, `Login` notifier properly persists role → OWNER features appear

### Feature: Unified Login/Register Screen

Currently the app has two separate pages:
- `RegisterPage` at `/auth/register` — phone + password → creates account
- `LoginPage` at `/auth/login` — phone + password → authenticates

Both pages have identical form fields (phone + password) with only different labels and error messages. The user wants a single screen that:
- Shows phone + password fields
- Detects whether the phone number exists (new → register, existing → login)
- Or provides a simple toggle between register/login mode

## Acceptance Criteria

### AC1 — Role Persistence After Registration (BUG FIX)
**Given** a new user has just completed registration via `RegisterPage`,
**When** the `Registration` notifier receives a successful `RegistrationResult`,
**Then** the following are persisted to `SharedPreferences`:
- `kUserRoleKey` = `"OWNER"` (extracted from JWT claims or hardcoded, since registration always creates an OWNER)
- `kUserPhoneKey` = the phone number used for registration

AND `activeStoreIdProvider` is set to `null` (OWNER has no single store assignment)
AND `currentUserRoleProvider` and `currentUserPhoneProvider` are invalidated.

### AC2 — Correct Landing After Onboarding Wizard
**Given** a new user has completed registration + onboarding wizard,
**When** `ShopNamePage` navigates to the main app,
**Then** the user lands on `/dashboard` (OWNER landing page, not `/pos`).

### AC3 — Owner Features Immediately Visible
**Given** a freshly registered OWNER user is in the app (no re-login),
**When** they navigate to Settings, Reports, Team, or any OWNER-only feature,
**Then** all OWNER-specific UI elements are visible and functional.

### AC4 — Unified Auth Screen: Single Page
**Given** the user has passed the onboarding welcome screen and accepted terms,
**When** they arrive at the authentication screen,
**Then** they see a single page with:
- Phone number field (IntlPhoneField, country picker, default CM)
- Password field (with visibility toggle)
- A "Continuer" submit button
- A mode toggle: "Pas encore de compte ? S'inscrire" / "Déjà un compte ? Se connecter"

### AC5 — Unified Auth: Register Mode
**Given** the user is on the unified auth screen in "register" mode,
**When** they submit a phone number that does NOT exist in the system,
**Then** the registration flow executes (same as current `RegisterPage`)
AND on success, navigates to `/onboarding/sector`.

### AC6 — Unified Auth: Login Mode
**Given** the user is on the unified auth screen in "login" mode,
**When** they submit valid credentials,
**Then** the two-step login flow executes (same as current `LoginPage`)
AND on success, navigates to `/pos` or `/dashboard` or `/tenant-picker` as appropriate.

### AC7 — Unified Auth: Error Handling Preserved
**Given** the user submits on the unified auth screen,
**When** an error occurs (`USER_ALREADY_EXISTS`, `INVALID_CREDENTIALS`, `ACCOUNT_LOCKED`, `VALIDATION_ERROR`),
**Then** the appropriate French error message is displayed in the error banner.

### AC8 — Backward Compatibility: Routes
**Given** the unified auth page replaces both `/auth/register` and `/auth/login`,
**When** any code or deep link references `/auth/register` or `/auth/login`,
**Then** both routes resolve to the unified auth page (login mode for `/auth/login`, register mode for `/auth/register`).

## Technical Notes

### Bug Fix Implementation (AC1-AC3)

**File:** `app/lib/features/auth/presentation/provider/auth_provider.dart`

The `Registration` notifier (class `Registration extends _$Registration`) must be updated to mirror the persistence logic from the `Login` notifier after a successful registration:

```dart
// CURRENT (lines 149-160) — broken:
class Registration extends _$Registration {
  @override
  FutureOr<RegistrationResult?> build() => null;

  Future<void> register({required String phoneNumber, required String password}) async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(
      () => ref.read(registerUserUseCaseProvider).execute(
        phoneNumber: phoneNumber,
        password: password,
      ),
    );
  }
}

// FIXED — add post-registration persistence:
// After AsyncValue.guard(), add:
state.whenData((result) {
  if (result != null) {
    final prefs = ref.read(sharedPreferencesProvider);
    prefs.setString(kUserRoleKey, 'OWNER'); // Registration always creates OWNER
    prefs.setString(kUserPhoneKey, phoneNumber);
    // OWNER has no single store assignment
    ref.read(activeStoreIdProvider.notifier).setActiveStore(null);
    ref.invalidate(currentUserRoleProvider);
    ref.invalidate(currentUserPhoneProvider);
  }
});
```

**File:** `app/lib/features/onboarding/presentation/page/shop_name_page.dart`

After onboarding wizard completes, navigate to role-appropriate landing:

```dart
// CURRENT: context.go('/pos')  — always POS
// FIXED: route based on role
final role = prefs.getString(kUserRoleKey);
context.go(role == 'OWNER' ? '/dashboard' : '/pos');
```

### Unified Auth UI Implementation (AC4-AC8)

**Strategy:** Merge `RegisterPage` and `LoginPage` into a single `AuthPage` widget with a mode toggle.

**File:** `app/lib/features/auth/presentation/page/auth_page.dart` (NEW)

Key design decisions:
1. `AuthPage` is a `ConsumerStatefulWidget` with an `_isLoginMode` boolean state
2. Same form fields (phone + password) for both modes
3. Toggle between modes via a `TextButton` at the bottom
4. Submit calls either `registrationProvider` or `loginProvider` based on mode
5. Error mapping merges both error sets (`USER_ALREADY_EXISTS` + `INVALID_CREDENTIALS` + `ACCOUNT_LOCKED`)
6. On registration success → `/onboarding/sector`
7. On login success → `/pos` or `/dashboard` or `/tenant-picker` (existing logic)

**File:** `app/lib/core/router/app_router.dart`

Update routes:
```dart
// Both routes now build AuthPage with different initial modes
GoRoute(
  path: '/auth/login',
  redirect: /* keep existing JWT guard */,
  builder: (_, __) => const AuthPage(initialMode: AuthMode.login),
),
GoRoute(
  path: '/auth/register',
  builder: (_, __) => const AuthPage(initialMode: AuthMode.register),
),
```

**Files to delete after migration:**
- `app/lib/features/auth/presentation/page/register_page.dart`
- `app/lib/features/auth/presentation/page/login_page.dart`

### Header/Title Adaptation

| Mode | Header Label | Title Text |
|------|-------------|-----------|
| Register | "Inscription" | "Continuez vers\nvotre boutique" |
| Login | "Connexion" | "Connectez-vous à\nvotre boutique" |

### Error Messages (merged)

| Domain Code | French Message | Mode |
|-------------|---------------|------|
| `USER_ALREADY_EXISTS` | "Un compte avec ce numéro existe déjà" | Register |
| `VALIDATION_ERROR` | "Données invalides, vérifiez vos informations" | Both |
| `INVALID_CREDENTIALS` | "Numéro ou mot de passe incorrect" | Login |
| `ACCOUNT_LOCKED` | "Compte verrouillé temporairement. Réessayez dans 15 minutes." | Login |
| Default | "Erreur inattendue, réessayez" | Both |

## Existing File Inventory

### Backend (NO CHANGES NEEDED)
The backend registration endpoint already returns a JWT with `role: "OWNER"`. The bug is purely Flutter-side.

### Flutter Files to Modify

| File | Purpose | Change |
|------|---------|--------|
| `app/lib/features/auth/presentation/provider/auth_provider.dart` | Registration notifier | Add SharedPreferences persistence after registration (AC1) |
| `app/lib/features/onboarding/presentation/page/shop_name_page.dart` | Onboarding wizard finish | Route to `/dashboard` for OWNER instead of always `/pos` (AC2) |
| `app/lib/core/router/app_router.dart` | GoRouter routes | Update `/auth/login` and `/auth/register` to build `AuthPage` (AC8) |

### Flutter Files to Create

| File | Purpose |
|------|---------|
| `app/lib/features/auth/presentation/page/auth_page.dart` | Unified auth screen (AC4-AC7) |

### Flutter Files to Delete

| File | Reason |
|------|--------|
| `app/lib/features/auth/presentation/page/register_page.dart` | Replaced by `AuthPage` |
| `app/lib/features/auth/presentation/page/login_page.dart` | Replaced by `AuthPage` |

## Testing Checklist

### Manual Testing

1. **Fresh registration flow:**
   - Clear app data → Onboarding → Register → Sector → Shop name
   - Verify landing on `/dashboard` (not `/pos`)
   - Verify Settings shows OWNER-specific options (Team, Reports, Stores)
   - Verify `SharedPreferences` contains `kUserRoleKey = "OWNER"`

2. **Login flow after registration:**
   - Logout → Login with same phone/password
   - Verify landing on `/dashboard`
   - Verify tenant-picker works for multi-tenant users

3. **Unified auth page:**
   - Navigate to `/auth/register` → verify register mode shown
   - Navigate to `/auth/login` → verify login mode shown
   - Toggle between modes → verify UI updates
   - Submit in register mode with new phone → verify onboarding flow starts
   - Submit in login mode with existing phone → verify login succeeds
   - Submit with wrong password in login mode → verify error banner
   - Submit with existing phone in register mode → verify `USER_ALREADY_EXISTS` error

4. **Cold-start after registration:**
   - Register + onboard → kill app → reopen
   - Splash redirect should route to `/dashboard` (OWNER role in SharedPrefs)

### Automated Tests (if time permits)

- Unit test: `Registration` notifier persists role after success
- Widget test: `AuthPage` renders in register and login modes
- Widget test: `AuthPage` mode toggle switches UI elements

## BDD Scenarios

```gherkin
Feature: Registration Role Persistence

  Scenario: New owner sees OWNER features immediately after registration
    Given I am a new user on the registration screen
    When I register with phone "+237600000000" and password "securepass"
    And I complete the onboarding wizard
    Then I land on the "/dashboard" page
    And the Settings page shows "Équipe" option
    And the Settings page shows "Rapports" option

  Scenario: Cold start after registration preserves OWNER role
    Given I registered and completed onboarding as OWNER
    When I close and reopen the app
    Then I land on the "/dashboard" page

Feature: Unified Auth Screen

  Scenario: Default register mode from onboarding
    Given I have accepted the terms and conditions
    When I arrive at the auth screen via "/auth/register"
    Then I see "Inscription" header and "Continuez vers votre boutique" title
    And I see a "Déjà un compte ? Se connecter" link

  Scenario: Switch to login mode
    Given I am on the auth screen in register mode
    When I tap "Déjà un compte ? Se connecter"
    Then I see "Connexion" header and "Connectez-vous à votre boutique" title
    And I see a "Pas encore de compte ? S'inscrire" link

  Scenario: Register with existing phone
    Given I am on the auth screen in register mode
    When I submit phone "+237600000000" that already exists
    Then I see error "Un compte avec ce numéro existe déjà"

  Scenario: Login with correct credentials
    Given I am on the auth screen in login mode
    When I submit valid phone and password
    Then I am navigated to "/dashboard" or "/pos" based on my role
```

## Implementation Order

1. **Task 1 (BUG FIX — AC1):** Update `Registration` notifier in `auth_provider.dart` to persist role, phone, activeStore after registration. Run `build_runner` if needed.
2. **Task 2 (BUG FIX — AC2):** Update `shop_name_page.dart` to route OWNER to `/dashboard` instead of hardcoded `/pos`.
3. **Task 3 (FEATURE — AC4-AC7):** Create `auth_page.dart` with unified register/login UI and mode toggle.
4. **Task 4 (FEATURE — AC8):** Update `app_router.dart` to wire both routes to `AuthPage`.
5. **Task 5 (CLEANUP):** Delete `register_page.dart` and `login_page.dart`. Update any remaining imports.
6. **Task 6 (VERIFY):** Run `flutter analyze`, verify 0 errors in production code. Manual testing on device.

### Review Findings
<!-- Code review — 2026-04-19 -->

- [x] [Review][Patch] Password trim asymmetry register vs login in `_submit()` [auth_page.dart:_submit()]
- [x] [Review][Patch] Listener `registrationProvider` always active: stale result navigates in login mode [auth_page.dart:build()]
- [x] [Review][Defer] CGU links non-tappables (aucun GestureRecognizer sur les TextSpan) [auth_page.dart] — deferred, pre-existing (hérité du RegisterPage)
- [x] [Review][Defer] `kUserRoleKey = 'OWNER'` hardcodé sans lecture du JWT claims [auth_provider.dart:Registration.register()] — deferred, pre-existing (spec allows it — "hardcoded, since registration always creates an OWNER")
- [x] [Review][Defer] `prefs.setString()` non awaité — échec disque silencieux [auth_provider.dart + auth_page.dart] — deferred, pre-existing (pattern systémique dans le projet)
- [x] [Review][Defer] `role == 'OWNER' ? '/dashboard' : '/pos'` dupliqué en 4 endroits [app_router.dart, auth_page.dart, shop_name_page.dart×2] — deferred, out of scope for hotfix
- [x] [Review][Defer] Router redirect autorise `role == null` sur les routes OWNER-only [app_router.dart] — deferred, pre-existing
- [x] [Review][Defer] Back en mode login sans historique → `/auth/register` (comportement hérité LoginPage) [auth_page.dart] — deferred, pre-existing
- [x] [Review][Defer] `initialCountryCode: 'CM'` hardcodé sans détection de locale [auth_page.dart] — deferred, pre-existing (hérité des deux anciens écrans)

## Dev Agent Notes

- The `Registration` notifier fix is the most critical change — it's a 5-line addition that fixes a real user-facing bug.
- The `AuthPage` should reuse the exact same `IntlPhoneField` configuration (initialCountryCode: 'CM', languageCode: 'fr').
- The `_ErrorBanner` widget from `register_page.dart` can be extracted or inlined in `AuthPage`.
- The `_SubmitButton` pattern (loading state + circular indicator) should be preserved.
- On the `register_page.dart`, note the duplicate `_ErrorBanner` rendering at lines 175-176 — this is a pre-existing bug (banner shown twice). Fix it in the new `AuthPage`.
- Back button behavior: in register mode → go to `/onboarding` or previous page; in login mode → go to `/auth/register` or previous page.
- The CGU footer from `register_page.dart` (lines 197-220) should only show in register mode.

---

## Dev Agent Record

### Implementation Plan

Executed all 6 tasks in sequence per story spec.

1. **Task 1 ✔** — Added role/phone/activeStore persistence to `Registration` notifier after successful `AsyncValue.guard` call. Mirrors the existing `Login` notifier pattern.
2. **Task 2 ✔** — Added `providers.dart` import to `shop_name_page.dart`; replaced both hardcoded `router.go('/pos')` calls (in `ref.listen` callback and `_completeLocallyAndNavigate`) with `kUserRoleKey`-based routing.
3. **Task 3 ✔** — Created `auth_page.dart` with `AuthMode` enum, `ConsumerStatefulWidget`, mode toggle (`_toggleMode` invalidates both providers), merged error message mapping, dual `ref.listen` for registration/login navigation, CGU footer visible in register mode only.
4. **Task 4 ✔** — Updated `app_router.dart`: removed `login_page.dart` / `register_page.dart` imports, added `auth_page.dart` import, wired both routes to `AuthPage` with correct `initialMode`, fixed tenant-picker fallback to `AuthPage(initialMode: AuthMode.login)`.
5. **Task 5 ✔** — Deleted `register_page.dart` and `login_page.dart`. Updated `register_page_test.dart` and `login_page_test.dart` to use `AuthPage`, added `sharedPreferencesProvider` override in tests, fixed button key from `registerButton` → `submitButton`, added `/onboarding/sector` route in test router.
6. **Task 6 ✔** — `flutter analyze` returns 0 errors in production code. All 14 auth page tests pass.

### Completion Notes

- Bug fix (AC1-AC3): Registration notifier now persists `kUserRoleKey='OWNER'`, `kUserPhoneKey`, resets `activeStoreIdProvider`, and invalidates `currentUserRoleProvider`/`currentUserPhoneProvider` immediately after registration success. No re-login needed.
- Bug fix (AC2): `shop_name_page.dart` now routes OWNER to `/dashboard` and EMPLOYEE to `/pos` after onboarding wizard completion.
- Feature (AC4-AC8): `auth_page.dart` created with: identical form fields (phone + password), mode toggle button, merged error handling (5 domain codes), correct navigation for both modes, CGU footer register-only, back button logic per mode.
- Login routing in AuthPage improved over old `login_page.dart`: routes OWNER to `/dashboard`, EMPLOYEE to `/pos` (old code always routed to `/pos`).
- Pre-existing `phoneField` key fixed: used `Key('phoneField')` (not `ValueKey(_mode)`) to maintain test compatibility and good UX (phone number persists on mode toggle).

## File List

### Modified
- `keevo/app/lib/features/auth/presentation/provider/auth_provider.dart`
- `keevo/app/lib/features/onboarding/presentation/page/shop_name_page.dart`
- `keevo/app/lib/core/router/app_router.dart`
- `keevo/app/test/features/auth/presentation/page/register_page_test.dart`
- `keevo/app/test/features/auth/presentation/page/login_page_test.dart`

### Created
- `keevo/app/lib/features/auth/presentation/page/auth_page.dart`

### Deleted
- `keevo/app/lib/features/auth/presentation/page/register_page.dart`
- `keevo/app/lib/features/auth/presentation/page/login_page.dart`

## Change Log

| Date | Change |
|------|--------|
| 2026-04-19 | Bug fix AC1: Registration notifier persists OWNER role/phone to SharedPreferences immediately after registration |
| 2026-04-19 | Bug fix AC2: shop_name_page routes OWNER to /dashboard instead of hardcoded /pos |
| 2026-04-19 | Feature AC4-AC8: Created unified AuthPage replacing separate RegisterPage/LoginPage |
| 2026-04-19 | Cleanup: Deleted register_page.dart and login_page.dart; updated tests and router |
