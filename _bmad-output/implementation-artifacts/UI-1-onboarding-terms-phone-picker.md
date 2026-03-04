# Story UI-1: Onboarding Screen, Terms & Phone Picker

**Epic**: 1 — Foundation, Infrastructure & Authentication  
**Story ID**: UI-1 (UI improvement, hors sprint planifié)  
**Status**: done  
**Date**: 2026-03-04  
**Branch**: develop  

---

## Overview

Implementation of three key first-launch UX screens for the Keevo Flutter app, inspired by the Book & Go reference UI:

1. **Onboarding welcome screen** — first-launch splash with language toggle and app feature showcase
2. **Terms & Privacy page** — scroll-to-read CGU with dual mandatory checkboxes, one-time display
3. **Register page redesign** — `IntlPhoneField` country picker pre-set to 🇨🇲 Cameroon (+237), new layout matching reference design

---

## User Flow

```
First launch:
  /splash → /onboarding → /terms → /auth/register → ...

Subsequent launches (onboarding seen, terms accepted):
  /splash → /auth/register

Edge case (onboarding seen but terms not accepted):
  /splash → /terms → /auth/register
```

State is persisted via `SharedPreferences`:
- `onboarding_seen` (bool) — set when user taps "Glisser pour commencer"
- `terms_accepted` (bool) — set when user accepts both CGU checkboxes

---

## Files Changed

### New files

| File | Description |
|---|---|
| `lib/features/onboarding/presentation/page/onboarding_page.dart` | Welcome screen with language toggle (FR/EN), Keevo logo card, feature pills, and swipe-style CTA button |
| `lib/features/onboarding/presentation/page/terms_page.dart` | Full CGU + Privacy Policy page with scroll-unlock pattern, 2 separate checkboxes, persisted acceptance |

### Modified files

| File | Change |
|---|---|
| `lib/features/auth/presentation/page/register_page.dart` | Full redesign — removed plain text field, added `IntlPhoneField` with country picker (default CM +237), new header layout ("Inscription" / "Continuez vers votre boutique"), footer with CGU links, pill-button |
| `lib/core/router/app_router.dart` | New entry point `/splash` with `_SplashRedirectPage` (reads SharedPreferences, routes to correct screen), added `/terms` route, updated `/onboarding` to use `OnboardingPage`, updated Splash redirect logic to check both `onboarding_seen` and `terms_accepted` |
| `pubspec.yaml` | Added `intl_phone_field: ^3.2.0` (country picker widget) and `shared_preferences: ^2.3.2` (first-launch persistence) |

---

## Key Design Decisions

### Terms Pattern — "Scroll to unlock"
- User must scroll to 80% of content before checkboxes become active
- Two **separate** checkboxes (CGU + Privacy Policy) — required by RGPD best practice; consents must be granular and non-bundled
- Button label adapts: *"Lisez les conditions..."* → *"Cochez les cases..."* → *"J'accepte et continuer"*
- Acceptance stored in SharedPreferences; page never shown again after first acceptance

### Phone Field
- `IntlPhoneField` provides flag + dial code + number input in one widget
- Default country: `CM` (Cameroon, +237) — primary market
- `completeNumber` (e.g. `+237600000000`) passed directly to the registration use case
- Validation delegated to the library's built-in country-aware validator

### Language Toggle (Onboarding)
- In-memory state only — locale preference via settings will be implemented in a dedicated story
- Toggles all visible strings between French and English

---

## Acceptance Criteria Met

- [x] First launch shows onboarding screen instead of directly register
- [x] User cannot proceed past terms without reading (80% scroll) and checking both boxes
- [x] Accept is persisted — terms page never shown again
- [x] Phone input shows country flag, dial code, and accepts Cameroon numbers
- [x] Subsequent launches skip onboarding and go directly to register
- [x] `flutter analyze` — 0 errors, 0 warnings (only `info` deprecation notices for `withOpacity`)

---

## Dependencies Added

```yaml
intl_phone_field: ^3.2.0   # Country picker + phone input
shared_preferences: ^2.3.2  # First-launch flag persistence
```
