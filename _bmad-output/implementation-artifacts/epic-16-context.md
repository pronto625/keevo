# Epic 16 Context: Mobile Frontend Hardening & Parity

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Fix the mobile-only (Flutter) defects found by the V1 conformity and coherence audits so that the V1 app on `deploy` is solid and shippable while the modular rewrite continues on another branch. The epic covers push-notification hygiene (FCM token cleanup, iOS foreground display), dark-mode correctness, catalog search quality, sync-state feedback (stale indicator, startup pull, rejected operations), error-state recovery ("Réessayer"), and a sweep of residual UX bugs. It is a V1 release blocker: most stories are tag A (patch V1 now) and a few are tag C (patch V1 now, the rewrite re-applies it later).

## Stories

- Story 16.1: FCM token cleanup on logout (done)
- Story 16.2: iOS notifications (Darwin settings) and foreground display
- Story 16.3: Dark mode: replace hard-coded `Colors.white` and fix offline banner contrast
- Story 16.4: Catalog search: 300 ms debounce and wider matching (done)
- Story 16.5: SyncIndicator online-stale state and daysSinceLastSync (done)
- Story 16.6: `ADJUSTMENT` movement type and offline store sync_queue (done)
- Story 16.7: "Mark all as read" broken after first use (done)
- Story 16.8: Wire onAppStartup sync pull (done)
- Story 16.9: Duplicate /reports route (done)
- Story 16.10: Dead DayCloseButton cleanup (done)
- Story 16.11: "Réessayer" buttons on app error screens (in progress)
- Story 16.12: User feedback on sync rejections
- Story 16.13: Residual app UX bugs (auth/router, banners, lists, pickers)
- Story 16.14: Catalog list quality (search escaping, dedup, pagination)

## Requirements & Constraints

- The work is almost entirely Flutter (`keevo/app`). A backend change is allowed only when the mobile fix cannot work without it, and the story must justify it (16.6 added store sync handlers this way).
- Every full-page or section error state must offer "Réessayer", which invalidates or refreshes the failed provider and reruns the load. Error messages must be human and reassuring, in French, and never technical ("Error 500"). Inline or compact error states (dropdowns, bottom sheets) that stay out of scope must be listed and justified in the story spec.
- The app must remain 100% usable offline for 7 days. Sync failures must never block core offline operations. Sync must lose no data, and every rejected operation needs a defined client behaviour: rollback or flag ghost local rows, and cancel dependent queued operations.
- Sync gate semantics: day 5 = warning (yellow/amber), day 6 = critical (red), day 7+ = blocked. Staleness is measured from the last successful sync, not from the first offline date.
- Search target: under 500 ms, debounced at 300 ms, matching name, SKU and category, with an "Aucun résultat → Créer" empty state. A LIKE substring match counts as "fuzzy" for V1; do not add a Levenshtein library.
- Accessibility: tech literacy 4/10, critical actions in 3 taps or fewer, adaptive font sizes, French UI with i18n-ready architecture, 48x48 dp minimum touch targets, WCAG AA contrast in both light and dark mode.
- Stories that carry a routed item list (16.2, 16.11 to 16.14) are done only when every item is resolved or explicitly rejected, with the commit or decision recorded next to it. Nothing goes to `deferred-work.md`: route each leftover to the story that owns it.
- Every commit that touches `keevo/app/` bumps the version in `pubspec.yaml`.

## Technical Decisions

- Stack: Flutter with Riverpod 3 (`@riverpod` codegen, `AsyncValue` for every async operation), Drift (encrypted local DB), go_router. Feature-first layout: `domain/ + data/ + presentation/`, with providers in `presentation/provider/` and the theme in `core/theme`.
- Error-handling convention: the data layer surfaces `AsyncValue.error()`. Full-screen load failures render the shared `AppErrorWidget` with `onRetry` (for example `ref.invalidate(provider)` or `ref.refresh(provider)`). Transient action failures use a SnackBar through `ref.listen`.
- After every mutation, online or offline, the notifier must call `refresh()` or `ref.invalidate()` so the UI rereads local Drift right away. One-shot `FutureProvider`s used as actions need `ref.refresh` at the call site (the 16.7 lesson).
- Write strategy: remote first when online. When offline, save locally (`synced:false`) and add a `sync_queue` entry. `SyncTriggerNotifier` pushes on reconnect and runs a pull at startup, login and tenant selection. Sync operation labels must match the backend handler literals exactly (for example `STOCK_ADJUST` as the operation, `ADJUSTMENT` as the movement type).
- Design tokens after the UI-3 rebrand (Soft/Modern):
  - Never hard-code colors such as `Colors.white` or hex literals in widgets. Use `Theme.of(context).colorScheme.*` or the `AppTheme` helpers: `AppTheme.softTint(brightness)` (not the opaque `primarySoftTint`), `softCardDecoration`, `softShadow`, `onWarning`.
  - Reuse the shared widgets (`SoftIconButton`, `SoftIconBadge`, soft cards with neutral headers, round diluted badges). Do not use saturated gradients or decorative emoji.
  - Dark-mode tokens: surface `#0D1B2A`, surfaceVariant `#1B2838`, primaryContainer `#1B3A5C`, onSurface `#F0F4F8`, onSurfaceVariant `#ADB5BD`, error `#FF6B6B`. Light-mode error is `#FA5252`.
  - Shared widgets live under `core/widget/` (singular).
- Testing: TDD with `flutter_test` widget tests and `ProviderContainer` + `mocktail` for providers. Set `GoogleFonts.config.allowRuntimeFetching = false` in tests. A retry story needs a widget test that goes error → tap "Réessayer" → data shown. `offline_gate_banner_test` (amber colour) already fails before this epic; treat it as a known baseline failure, not a regression.

## UX & Interaction Patterns

- Loading uses skeleton or shimmer screens, not a full-screen spinner. Network errors show an offline illustration with "Réessayer". A system error SnackBar uses the error color with "Réessayer" and stays until dismissed. Errors never auto-dismiss.
- Empty states: a first-time empty screen shows an illustration, encouraging text and a CTA. An empty filtered list shows "Aucun résultat" with "Effacer les filtres".
- The offline/attention banner sits at the top of the screen, uses a warning tint, and stays visible. Stacked banners need a defined precedence (16.13).
- The sync indicator in the AppBar shows a dot and a label: green "Mis à jour", amber "En ligne (sync J-X)" on day 5, red "⚠ Sync urgente" on day 6. While loading, the indicator stays neutral (no flash).
- Back navigation uses the AppBar arrow. Destructive actions require a confirmation dialog. A tap on a notification deep-links to the relevant screen.

## Cross-Story Dependencies

- 16.11 builds on the UI-3 design system and on `AppErrorWidget`. The ad hoc error states it migrates touch screens that 16.3 (dark mode) also edits, so coordinate to avoid conflicts and avoid reintroducing `Colors.white`.
- 16.3: the actual `Colors.white` count after UI-3 is about 95. Recount before starting.
- 16.12 depends on 16.6 (store queue), 16.8 (startup pull) and 14.13 (PLAN_LIMIT_EXCEEDED in the backend `ProductSyncHandler`). It also covers rejection of a local inventory session (13.8).
- 16.13 collects leftovers from 16.1 (logout re-entrancy), 16.5 (SyncIndicator details), 14.9 (banners), 14.12 (reset page deep link) and 12.7 (SESSION_REVOKED SnackBar flood).
- 16.14 extends 16.4 (search escaping, dedup by id/SKU).
- 16.2 still carries an item about FCM token freshness from 16.1.
- The modular rewrite must re-apply every fix in this epic in Flutter.
