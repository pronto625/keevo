# Epic 16 Context: Mobile Frontend Hardening & Parity

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Fix the mobile (Flutter) defects found by the V1 conformity and coherence audits so the V1 app on `deploy` is solid and shippable while the modular rewrite continues elsewhere. The epic covers push-notification hygiene and iOS display, dark mode and colour contrast, catalog search and list quality, sync-state feedback, client behaviour when the server rejects queued offline operations, error-state recovery, a sweep of residual UX bugs, iOS release identity, and correct weekly buckets on the owner dashboard. It is a V1 release blocker. Most stories are tag A (patch V1 now); tag C stories are patched in V1 now and re-applied by the rewrite later.

## Stories

- Story 16.1: FCM token cleanup on logout (done)
- Story 16.2: iOS notifications (Darwin settings) and foreground FCM display (done)
- Story 16.3: Dark mode — hard-coded colours to theme tokens, readable offline banner (done)
- Story 16.4: Catalog search debounce and wider matching (done)
- Story 16.5: SyncIndicator online-stale state (done)
- Story 16.6: `ADJUSTMENT` movement type and offline store sync_queue (done)
- Story 16.7–16.10: mark-all-read, startup sync pull, duplicate /reports route, dead DayCloseButton (done)
- Story 16.11: "Réessayer" on app error screens (done)
- Story 16.12: User feedback on sync rejections (done)
- Story 16.13: Residual app UX bugs (done)
- Story 16.14: Catalog list quality (done)
- Story 16.15: Offline sales stuck in the sync queue — quarantine (done)
- Story 16.16: Unicode case/accent-insensitive local search (done)
- Story 16.17: Contrast of feedback fills (done)
- Story 16.18: iOS release identity — bundle id, icons, orientation, APNs production (backlog)
- Story 16.19: Mobile dashboard weekly buckets — year end and DST (in progress)

## Requirements & Constraints

- **Routed-items rule.** A story with an item list is done only when every item is resolved or explicitly rejected with the reason recorded next to it in the epics file (tick the box, note the commit/decision). Nothing goes to `deferred-work.md`; new findings are routed to their owning story.
- **Dashboard accuracy.** The owner's 12-week revenue chart must count every sale of every week: a week straddling 31 Dec / 1 Jan must not lose its January part, and slot boundaries must not shift by an hour across a DST change. Cameroon (target market) has no DST, but the code must not depend on that. Weeks start on Monday. Dashboard loads stay under 3 s; local actions under 2 s.
- **iOS release.** The app must carry a registrable store identity (Apple refuses the `com.example` prefix); the reverse-DNS id is a product decision applied to Android and iOS together, before the Firebase iOS app is registered. Brand icons on iOS, orientation aligned with Android, APNs `production` on release builds.
- **Push notifications.** Every backend push must display on Android and iOS, foreground and background; permission denial never blocks the app; desktop (Linux/Windows) skips FCM gracefully; tap navigates to `data['deepLink']`; logout revokes the device token (backend + local).
- **Contrast (WCAG 2.1 AA), light AND dark.** Text ≥ 4.5:1, large text/icons ≥ 3:1; alerts = colour + icon + text; touch targets ≥ 48 dp; dark mode follows the system.
- **Zero lost sales / offline-first.** Sync never silently drops an operation; refused money/stock operations go to quarantine. Offline gate: day 5 warning, day 6 critical, day 7+ writes blocked. Local search < 500 ms on 500 products.
- **Process.** Flutter-first; a backend change needs a justification in the story. Every commit touching `keevo/app/` bumps `pubspec.yaml`. UI text is French, human, non-technical (English/i18n is V2).

## Technical Decisions

- **Weekly buckets (16.19).** Key both sides by the week's Monday date instead of `strftime('%Y-%W')` vs a Dart `'<year>-<week>'` string: SQL `date(day, '-N days')` with N = (`strftime('%w', day)` + 6) % 7; Dart slots built from calendar fields `DateTime(y, m, d - k*7)`, never `Duration` subtraction. Applies to both the all-stores and per-store weekly queries in the local dashboard datasource. `sqliteWeekW` was already aligned with SQLite `%W` by Story 15.8 (UTC day-of-year). Required test: a week summed across the year end; a DST time-zone test if CI allows.
- **iOS project (16.18).** The `ios/` Runner was generated from the Flutter template by 16.2 (Info.plist remote-notification, `Runner.entitlements` with `aps-environment`, Podfile iOS 13). `GoogleService-Info.plist` is added per deployment, not committed. Either confirm export rewrites `aps-environment` to production on the first TestFlight archive, or split entitlements per configuration.
- **FCM wiring.** `FcmService` owns init (Android + Darwin settings), token registration (`registerCurrentToken`, tracks `lastRegisteredToken`), foreground local notifications, and tap handling; a Firebase gateway is injectable for tests.
- **Colour tokens only.** Colours outside `lib/core/theme/app_theme.dart` come from `colorScheme`, `KeevoColors` or named `AppTheme` tokens; foreground follows its fill (`onPrimary`, `onError`, `onBrand` on strong fills); guard tests forbid literals and weak feedback fills.
- **Write/sync strategy.** Online: backend first then local cache; offline: Drift + `sync_queue` in one transaction. Rejection policy single source: `core/sync/sync_rejection_policy.dart`. Local search uses the shared fold (`search_fold.dart`) and LIKE escaping (`sql_like.dart`) helpers.
- **Stack/testing.** Flutter, Riverpod 3 (codegen), Drift (encrypted), go_router, Firebase Messaging, flutter_local_notifications; feature-first layout. Tests use `ProviderContainer` + Mocktail overrides and injected clocks (no real waits); colour widget tests pump `AppTheme.light()` and `AppTheme.dark()`.

## UX & Interaction Patterns

- **Feedback.** Success SnackBar ✅ 3 s; error SnackBar ❌ with "Réessayer"; error states offer "Réessayer"; offline = persistent top banner, one shell banner at a time by priority.
- **Notifications.** Calm by default; tap lands on the relevant screen; bell with unread badge.
- **Sync indicator:** green "Mis à jour", amber "En ligne (sync J-X)" day 5, red "⚠ Sync urgente" day 6, with Semantics labels.

## Cross-Story Dependencies

- 16.19 builds on Story 15.8 (`sqliteWeekW` parity fix, which routed both items here) and touches the Story 7-x dashboard charts.
- 16.18 follows 16.2 (generated iOS Runner, push wiring) and gates TestFlight/App Store delivery; the bundle-id decision also changes the Android `applicationId`.
- 16.17 followed 16.3; 16.15 builds on 16.12; 16.16 on 16.14 and 16.4. Stories 14.4 (global push) and 14.10 (owner notified of employee actions) rely on 16.2 for iOS/foreground display.
- The modular rewrite must re-apply every fix in this epic.
