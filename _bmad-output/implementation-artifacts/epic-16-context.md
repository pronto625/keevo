# Epic 16 Context: Mobile Frontend Hardening & Parity

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Fix the mobile (Flutter) defects found by the V1 conformity and coherence audits so the V1 app on `deploy` is solid and shippable while the modular rewrite continues elsewhere. The epic covers push-notification hygiene and iOS display, dark mode and colour contrast, catalog search and list quality, sync-state feedback, client behaviour when the server rejects queued offline operations, error-state recovery, and a sweep of residual UX bugs. It is a V1 release blocker. Most stories are tag A (patch V1 now); a few are tag C (patch V1 now, the rewrite re-applies the fix later).

## Stories

- Story 16.1: FCM token cleanup on logout (done)
- Story 16.2: iOS notifications (Darwin settings) and foreground FCM display (in progress)
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
- Story 16.17: Contrast of feedback fills — SnackBars, speed-dial, disabled CTAs, dark error colour (done)

## Requirements & Constraints

- **Push notifications (FCM).** Every push the backend sends (stock alerts, sales trends, end-of-day report, employee-action notices to the owner, Super Admin global announcement `GLOBAL_ANNOUNCEMENT`) must be visible on Android **and iOS**, in foreground and background. Background: the OS tray shows the FCM `notification` payload natively. Foreground: FCM shows nothing by itself, so the app must display a local notification (title/body from `message.notification`) via `flutter_local_notifications`. On iOS this requires Darwin initialization settings and alert/badge/sound permission.
- **Permission is never a blocker.** Notification permission is requested at first login; if denied, the app continues normally ("Sérénité par défaut" — calm by default, no aggressive notifications).
- **Desktop guard.** On Linux/Windows FCM is unavailable: skip all Firebase/FCM calls (init, token, delete) gracefully, no crash.
- **Tap → deep link.** Tapping a notification (foreground local notification, background `onMessageOpenedApp`, cold start `getInitialMessage()`) navigates to `data['deepLink']` via GoRouter when non-empty. Every received message is also stored in the local `notifications` Drift table (bell badge, notifications page, 30-day retention).
- **Token hygiene.** On logout the device token is deleted on the backend (`DELETE /api/v1/devices/token`) and locally, each step best-effort and independent (16.1). Open item routed to 16.2: the token read at logout may differ from the one registered with the backend — settle it when FcmService registration is reworked, or reject with a recorded reason.
- **Contrast (WCAG 2.1 AA), light AND dark.** Text ≥ 4.5:1, large text/icons ≥ 3:1; alerts are colour + icon + text; touch targets ≥ 48 dp; 200% font scaling; dark mode follows the system.
- **Routed-items rule.** A story with an item list is done only when every item is resolved or explicitly rejected with the reason recorded next to it in the epics file. Nothing goes to `deferred-work.md`.
- **Zero lost sales / offline-first.** Sync never silently drops an operation; refused money/stock operations go to quarantine (16.15). Offline gate: day 5 warning, day 6 critical, day 7+ writes blocked.
- **Process.** Flutter-first; a backend change needs a justification in the story. Every commit touching `keevo/app/` bumps `pubspec.yaml`. UI text is French, human, non-technical.

## Technical Decisions

- **FCM wiring.** `FcmService` (`fcm_service.dart`) owns init, token get/refresh/delete, the `onMessage` foreground handler, tap handling and local-notification setup; it currently builds only `AndroidInitializationSettings`. 16.2 adds `DarwinInitializationSettings` into `InitializationSettings(iOS: …)` and requests alert/badge/sound permissions through `flutter_local_notifications`. Android channel stays `keevo_notifications`, importance HIGH, LED `#3B5BDB`. Expected TDD names: `shouldInitializeDarwinSettingsOnIos()`, `shouldRequestAlertBadgeSoundPermissions()`.
- **Payload contract.** Backend `NotificationPayload` maps `title`/`body` to the FCM `notification` block and `type`, `deepLink` + all metadata to the `data` block. The `type` drives the icon in the notification list; employee-action types are `EMPLOYEE_PRODUCT_CREATED(_BATCH)` / `EMPLOYEE_STOCK_MODIFIED(_BATCH)`.
- **iOS project config.** `GoogleService-Info.plist` and the push-notifications capability are added manually per deployment (not committed secrets); device platform values are `ANDROID`, `IOS`, `LINUX`, `WINDOWS`.
- **Providers.** `fcmServiceProvider` and `remoteDeviceTokenDataSourceProvider` already exist; tests drive them through a `ProviderContainer` with Mocktail overrides.
- **Colour tokens only.** Colours outside `lib/core/theme/app_theme.dart` come from `colorScheme`, the `KeevoColors` extension or named `AppTheme` tokens; guard tests forbid literals and weak feedback fills. Foreground follows its fill (`onPrimary`, `onError`, `onBrand` on fixed brand/strong fills). SnackBars use fixed fills with `onBrand` content; success/error/warning feedback uses `successStrong`/`errorStrong`/`warningStrong`.
- **Write/sync strategy.** Online: backend first then local cache; offline: Drift + `sync_queue` in one transaction (repository layer). Rejection policy single source: `core/sync/sync_rejection_policy.dart`. Local search uses the shared fold and LIKE escaping helpers.
- **Stack/testing.** Flutter, Riverpod 3 (codegen), Drift (encrypted), go_router, Firebase Messaging, flutter_local_notifications; feature-first layout, shared widgets in `core/widget/`. Colour widget tests pump both `AppTheme.light()` and `AppTheme.dark()`; `GoogleFonts.config.allowRuntimeFetching = false` in tests.

## UX & Interaction Patterns

- **Notifications.** Calm by default; push reserved for real events (critical stock, reports, employee actions, admin announcements). Tap on a notification lands directly on the relevant screen. AppBar bell with unread badge; notifications page with type icon, title, body, relative time, bold-when-unread, "Tout marquer comme lu".
- **Feedback.** Success SnackBar with ✅ + haptic, 3 s; error SnackBar with ❌ and "Réessayer", not auto-dismissed; attention = persistent in-page warning banner; offline = persistent top banner. Error states offer "Réessayer"; destructive actions need confirmation.
- **Sync indicator:** green "Mis à jour", amber "En ligne (sync J-X)" day 5, red "⚠ Sync urgente" day 6, with Semantics labels.

## Cross-Story Dependencies

- 16.2 builds on Story 8-0 (FCM integration, notifications table, bell, deep links) and on 16.1 (token cleanup on logout); it inherits 16.1's open token-freshness item.
- Remediation stories 14.4 (FR82 global push) and 14.10 (owner notified of employee product/stock actions) rely on 16.2 for their notifications to appear on iOS and in foreground.
- 16.17 followed 16.3 (token rules, `onBrand`, strong tokens, colour guards); 16.11/16.12/16.13 share the feedback surfaces; 16.15 builds on 16.12; 16.16 on 16.14 and 16.4.
- The modular rewrite must re-apply every fix in this epic.
