---
title: 'v1s-16-2 — iOS notifications (Darwin settings) + foreground FCM display'
type: 'feature'
created: '2026-10-12'
status: 'done'
baseline_revision: '6120065a681c1951d6f86e752571371b0ecd14ef'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
warnings: [oversized]
deferred:
  - summary: >-
      iOS/Android bundle id com.example.keevo cannot be registered on App Store Connect.
    evidence: |-
      PRODUCT_BUNDLE_IDENTIFIER and android applicationId are com.example.keevo; Apple rejects the com.example prefix. Routed to v1s-16-18-ios-release-identity.
    location: >-
      keevo/app/ios/Runner.xcodeproj/project.pbxproj
    severity: medium
  - summary: >-
      aps-environment=development for Release/Profile; confirm production on first archive.
    evidence: |-
      Single Runner.entitlements for all configs; Xcode export normally re-signs to production. Routed to v1s-16-18-ios-release-identity.
    location: >-
      keevo/app/ios/Runner/Runner.entitlements
    severity: low
  - summary: >-
      iOS template default icons/launch image and iPhone landscape orientations.
    evidence: |-
      Assets are Flutter defaults; Info.plist allows landscape. Routed to v1s-16-18-ios-release-identity.
    location: >-
      keevo/app/ios/Runner/Info.plist
    severity: low
---

<intent-contract>

## Intent

**Problem:** `FcmService` only builds `AndroidInitializationSettings`, so on iOS `flutter_local_notifications` is never initialised, no alert/badge/sound permission is requested through it and foreground FCM pushes (stock alerts, trends, EOD, employee-action, global announcement) are never shown (8-0 AC4). The repo has no `ios/` project at all on this branch. Routed item from 16.1: the token deleted at logout is re-read with `getToken()` and may differ from the one the backend holds.

**Approach:** Initialise the plugin with `InitializationSettings(android:…, iOS: DarwinInitializationSettings(...))`, request alert/badge/sound explicitly through `IOSFlutterLocalNotificationsPlugin.requestPermissions`, show foreground pushes with `DarwinNotificationDetails` on iOS, and set FCM foreground presentation options so iOS never shows the same push twice. Generate the missing `ios/` Runner (Flutter 3.41 template) and add push config. Put token registration inside `FcmService`, which remembers the last token the backend accepted so logout revokes it as well.

## Boundaries & Constraints

**Always:** Firebase calls go through an injectable `FcmMessagingGateway` (default = real `FirebaseMessaging`/`Firebase`) so `FcmService` is unit-testable with mocktail. Platform branching uses `defaultTargetPlatform`/`kIsWeb` (testable via `debugDefaultTargetPlatformOverride`). Desktop (Linux/Windows) and web skip FCM, with no crash. Every FCM/token step stays best-effort (exceptions logged, never surfaced). Android behaviour unchanged: the channel stays `keevo_notifications`, with HIGH importance/priority. Bump `pubspec.yaml` version.

**Never:** Commit `GoogleService-Info.plist` or any secret. Run `flutter build ios`/`pod install` (Linux host). Change backend. Introduce Riverpod into `FcmService`. Show an iOS foreground push twice (FCM native alert + local notification).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| iOS init | platform iOS, first `initialize` | plugin `initialize` with non-null `iOS` Darwin settings (no auto-prompt flags) ; `requestPermissions(alert,badge,sound)` on iOS impl ; FCM `setForegroundNotificationPresentationOptions(alert:false,badge:false,sound:false)` | init failure resets `_listenersAttached` and rethrows (existing contract) |
| Android init | platform Android | Darwin settings also passed (ignored by plugin), channel created, no iOS permission call | — |
| Foreground push iOS/Android | `RemoteMessage` with `notification` | stored locally + `show()` with both `android` and `iOS` (`DarwinNotificationDetails` presentAlert/Banner/List/Sound true) details, payload = `data.deepLink` | — |
| Data-only push | `notification == null` | stored locally, no `show()` | — |
| Desktop/web | Linux/Windows/web | initialize/getToken/deleteToken/registerCurrentToken no-op | — |
| Register token | iOS, APNs token null | skip registration (logged) | no throw |
| Register token | token obtained | backend `registerToken(token, platform)` ; `lastRegisteredToken` = token | failure logged, `lastRegisteredToken` unchanged |
| Token refresh | `onTokenRefresh(t)` | re-register; on success `lastRegisteredToken = t` | failure logged |
| Logout | current token ≠ `lastRegisteredToken` | backend delete called for both; local `deleteToken` | each step independent best-effort |

</intent-contract>

## Code Map

- `keevo/app/lib/core/notification/fcm_service.dart` -- whole FCM wiring: `initialize` (l.43-99), `_onTokenRefresh` (l.101), `_getPlatform` (l.112), `getToken/deleteToken` (l.119-136), `_initLocalNotifications` Android-only (l.138-165), `_onForegroundMessage` Android-only details (l.167-194). Static `FirebaseMessaging.*` / `Firebase.*` / `dart:io Platform` calls make it untestable today.
- `keevo/app/lib/core/scaffold/main_shell.dart:63-110` -- `_initFcm` does getToken + `registerToken` itself + duplicate `_fcmPlatform()`; to be replaced by `fcmService.registerCurrentToken()`.
- `keevo/app/lib/features/auth/presentation/provider/sign_out_service.dart:49-69` -- logout: getToken → remote delete → local delete (16.1/16.13 independent try blocks).
- `keevo/app/lib/features/notifications/presentation/provider/notification_provider.dart:30` -- `fcmServiceProvider` (constructor call, keep defaults).
- `keevo/app/lib/features/notifications/data/datasource/remote_device_token_datasource.dart` -- `registerToken({token, platform})`, `deleteToken(token)`.
- `keevo/backend/.../FcmNotificationAdapter.java:126` -- read-only evidence: backend prunes `UNREGISTERED` tokens on send.
- `test/features/auth/presentation/provider/sign_out_service_test.dart`, `test/features/settings/presentation/page/settings_page_test.dart` -- mock `FcmService` (unstubbed `lastRegisteredToken` getter returns null → unchanged behaviour).
- iOS template reference (already generated, copy it: `/tmp/claude-1000/-home-toor-Project-FreeLance-AI-Keevo/04db4194-544b-4c10-a501-504a3970eded/scratchpad/iosgen/ios/`): `flutter create --platforms=ios --org com.example --project-name keevo --empty` (bundle id `com.example.keevo`, same as Android `applicationId`), iOS 13.0 target, `FlutterImplicitEngineDelegate` AppDelegate + `SceneDelegate`.
- Package docs (pub cache): flutter_local_notifications 18.0.1 README "iOS setup" (AppDelegate `UNUserNotificationCenter.current().delegate = self as? UNUserNotificationCenterDelegate`), firebase_messaging 15.2.10 (background modes `fetch` + `remote-notification`, Push Notifications capability).

## Tasks & Acceptance

**Execution:**
- `keevo/app/lib/core/notification/fcm_service.dart` -- add `FcmMessagingGateway` (+ `FirebaseFcmMessagingGateway` default) wrapping ensureInitialized/requestPermission/setForegroundNotificationPresentationOptions/onMessage/onMessageOpenedApp/getInitialMessage/onTokenRefresh/getAPNSToken/getToken/deleteToken; inject via constructor; replace `Platform` with `defaultTargetPlatform`/`kIsWeb`; add Darwin init settings (request*Permission false), explicit iOS `requestPermissions(alert,badge,sound)`, FCM presentation options all false on iOS, `DarwinNotificationDetails` in `show`; add `registerCurrentToken()` + `lastRegisteredToken`, update it on refresh success; `platformName` getter -- AC 8-0 AC4 + routed token item.
- `keevo/app/lib/core/scaffold/main_shell.dart` -- `_initFcm` calls `registerCurrentToken()`; drop `_fcmPlatform` and now-unused imports -- single registration path.
- `keevo/app/lib/features/auth/presentation/provider/sign_out_service.dart` -- after deleting the current token remotely, also delete `fcm.lastRegisteredToken` when non-null and different (own try) -- resolves the token-freshness item.
- `keevo/app/test/core/notification/fcm_service_test.dart` -- new: `shouldInitializeDarwinSettingsOnIos`, `shouldRequestAlertBadgeSoundPermissions`, FCM presentation options, iOS/Android foreground show details, data-only no show, desktop no-op, registerCurrentToken (APNs null skip / success records / failure keeps), refresh updates lastRegisteredToken, single listener attach.
- `keevo/app/test/features/auth/presentation/provider/sign_out_service_test.dart` -- add case: stale registered token also deleted; same token deleted once.
- `keevo/app/ios/**` -- add the Flutter 3.41 iOS Runner template; `Info.plist` += `UIBackgroundModes` [`fetch`,`remote-notification`]; `Runner/Runner.entitlements` with `aps-environment=development` + `CODE_SIGN_ENTITLEMENTS` on Runner Debug/Release/Profile; `AppDelegate.swift` sets `UNUserNotificationCenter` delegate.
- `keevo/app/pubspec.yaml` -- version `1.1.28+27` → `1.1.29+28`.

**Acceptance Criteria:**
- Given an iOS target, when `FcmService.initialize` runs, then the plugin is initialised with `InitializationSettings` whose `iOS` is a `DarwinInitializationSettings` and the iOS implementation receives `requestPermissions(alert: true, badge: true, sound: true)`.
- Given a foreground FCM message with a notification block on iOS, when it arrives, then exactly one OS notification is shown (local, `DarwinNotificationDetails` with alert/banner/list/sound) and FCM's native foreground presentation is disabled.
- Given the backend accepted token A and FCM now returns B, when the user logs out, then the backend receives delete for B and for A, and the local token is deleted.
- Given the `ios/` folder, when inspected, then `Info.plist` declares `remote-notification` background mode, the Runner target references an entitlements file with `aps-environment`, and `AppDelegate` sets the notification-center delegate.

## Spec Change Log

## Review Triage Log

### 2026-10-12 — Review pass
- verdicts: 36 findings — high 0, medium 6, low 14, false 15, maybe-false 1
- findings:
  - `[medium]` `[patch]` (blind) iOS first install: APNs token arrives after `initialize`, single null check skips registration for the session — `registerCurrentToken` now polls `getAPNSToken()` 5× (`apnsRetryDelay`, default 2 s); tests late/never APNs.
  - `[medium]` `[patch]` (blind) process-wide `onTokenRefresh` re-registers a token generated after sign-out's local `deleteToken()` — `deleteToken()` sets `_signedOut`, refresh handler skips while signed out, `registerCurrentToken()` clears it; tested.
  - `[low]` `[patch]` (blind) `_lastRegisteredToken` never cleared → next user's logout re-deletes previous user's token — `deleteToken()` nulls it; tested.
  - `[low]` `[reject]` (blind) failed refresh re-registration never retried — pre-existing behaviour; next sign-in/app start re-registers through `registerCurrentToken`; a retry queue adds machinery for a rare offline-refresh window.
  - `[false]` `[reject]` (blind) spec commits a scratchpad path — the fix is editing this build's spec (rejected by rule); the `flutter create` command beside it is reproducible.
  - `[low]` `[patch]` (blind) no `ios/Podfile` — added the standard Flutter Podfile with `platform :ios, '13.0'`.
  - `[medium]` `[defer]` (blind) bundle id `com.example.keevo` not registrable on App Store Connect — pre-existing identity (Android `applicationId` identical); product decision → routed to `v1s-16-18-ios-release-identity`.
  - `[low]` `[defer]` (blind) `aps-environment=development` for Release/Profile — Xcode export normally re-signs with the distribution profile's production value; confirmation needs a real archive → routed to `v1s-16-18-ios-release-identity`.
  - `[low]` `[reject]` (blind) missing `GoogleService-Info.plist` makes `Firebase.initializeApp()` throw on iOS — by design (per-deployment file, 8-0 task 7.4); the throw is caught and logged by `MainShell._initFcm`, no crash; manual check 1.
  - `[false]` `[reject]` (blind) `ensureInitialized()` runs before the listener guard — unchanged ordering from baseline; `ensureInitialized` returns early when `Firebase.apps` is non-empty, no repeated init.
  - `[false]` `[reject]` (blind) `platformName` falls to `LINUX` on macOS — the repo has no `macos/` runner, the platform is unreachable; same fallback as the baseline `_getPlatform`.
  - `[medium]` `[patch]` (blind) local foreground notification tapped after the app was killed never deep-links — `getNotificationAppLaunchDetails()` read after init, payload pushed; tested.
  - `[low]` `[reject]` (blind) local-notification tap does not `markAsRead` — pre-existing on Android; fix needs a payload format change (JSON id+link) for a cosmetic unread badge the bell page resolves anyway.
  - `[low]` `[patch]` (blind) test gaps (onMessage callback, response tap, permission order, sign-out with null current token) — tests added; web `kIsWeb` branch is a compile-time constant, untestable under flutter_test (noted).
  - `[low]` `[patch]` (blind) AC on `ios/` only checked manually — `test/core/notification/ios_push_config_test.dart` added.
  - `[low]` `[defer]` (blind) template default icons/launch image and iPhone landscape — release identity work → routed to `v1s-16-18-ios-release-identity`.
  - `[false]` `[reject]` (blind) epic context recompile dropped palette/search guidance — the context is regenerated from planning docs, which still hold it; the stories using it are done.
  - `[false]` `[reject]` (blind) spec status vs epic context mismatch / empty logs — statuses move through the workflow by design; logs fill during review.
  - `[medium]` `[patch]` (edge) APNs arrives after `registerCurrentToken` — same root cause as the APNs poll patch above.
  - `[low]` `[patch]` (edge) `getInitialMessage` throwing leaves `onTokenRefresh` unattached — listener attached first, `getInitialMessage` wrapped in try/catch; tested.
  - `[medium]` `[patch]` (edge) `onTokenRefresh` after sign-out — same patch as `_signedOut` above.
  - `[low]` `[patch]` (edge) `lastRegisteredToken` never cleared — same patch as above.
  - `[low]` `[reject]` (edge) iOS permission / presentation-option calls throwing abort `initialize` — same rethrow contract as the baseline FCM `requestPermission`; guarding each call adds branches for an unobserved failure.
  - `[false]` `[reject]` (edge) macOS/fuchsia map to `LINUX` — no macOS/fuchsia runner in the repo.
  - `[low]` `[reject]` (edge) `onMessage.listen` without `onError` — pre-existing; FlutterFire streams do not emit errors in practice and the handler is synchronous fire-and-forget as before.
  - `[maybe-false]` `[reject]` (edge) Release/Profile entitlements hard-coded to development → ad-hoc builds get sandbox tokens — export re-signing normally sets production; if true it would be low (release config) and it is already tracked under `v1s-16-18-ios-release-identity`.
  - `[low]` `[patch]` (verification-gap) MainShell → `registerCurrentToken` wiring unverified — `fcmMessagingGatewayProvider` added, `fcm_service_provider_test.dart` checks `fcmServiceProvider` reaches `registerToken(token, 'ANDROID')`.
  - `[false]` `[reject]` (verification-gap other) "next shell build" retry claim — doc comment corrected with the APNs poll patch; claim no longer made.
  - `[false]` `[reject]` (verification-gap other) `_lastRegisteredToken` outlives session — fixed by the `deleteToken` patch (duplicate).
  - `[false]` `[reject]` (intent a) device-level display unverified — accepted by the intent ("not a blocker"), checklist in Verification.
  - `[false]` `[reject]` (intent b) per-category display not tested — display path is type-blind and the backend always sets `notification` (FcmNotificationAdapter:63/95).
  - `[false]` `[reject]` (intent c) FCM presentation set to false — documented design (Design Notes) to avoid double display; local plugin presents.
  - `[false]` `[reject]` (intent d) token item fixed beyond notification surface — the intent asks the item be resolved; this story is the FcmService rework the item named.
  - `[false]` `[reject]` (intent e) version read as "bump from 1.1.28+27" — project rule: bump on every app change; 1.1.28+27 is the baseline.
  - `[false]` `[reject]` (intent f) whole iOS Runner generated — no `ios/` existed on this branch; config could not be added otherwise.
  - `[false]` `[reject]` (intent g) test command wider than intent — verification includes the touched auth/settings suites; superset.

## Design Notes

- **No double display on iOS:** `flutter_local_notifications` handles the foreground display, the same way as on Android. It gives one tap path (`onDidReceiveNotificationResponse` → `data.deepLink`), so FCM `setForegroundNotificationPresentationOptions` is set explicitly to `false` (the FlutterFire guidance when a local plugin displays the push). Background/terminated pushes are still shown natively by iOS from the APNs `notification` block.
- **Permissions:** the Darwin init flags are `false` so that `initialize` does not prompt. The explicit `requestPermissions` call then runs before `FirebaseMessaging.requestPermission()` (still needed for Android 13 and FCM status). iOS shows the system prompt only once.
- **Token item resolution:** process-local `lastRegisteredToken` covers the realistic gap: the token was refreshed during the session and its re-registration failed, so the backend still holds the old token. Across app restarts the backend's old token is already invalid at FCM after a refresh and is pruned on `UNREGISTERED` (FcmNotificationAdapter:126). Local `deleteToken()` also invalidates the current token, so no push reaches a signed-out device.
- **iOS APNs:** on iOS, `getToken()` throws until the APNs token exists, so `registerCurrentToken` checks `getAPNSToken()` first. The next shell build or `onTokenRefresh` retries.

## Verification

**Host constraint (machine has 15 GB RAM and has crashed on full suites):** NEVER run `flutter test` on the whole project, never several suites in parallel or in background; only the targeted files below or the chunk script (one directory at a time). No `flutter build`/`flutter clean`/`pod install`.

**Commands:**
- `cd keevo/app && flutter test --concurrency=4 test/core/notification test/features/auth/presentation/provider/sign_out_service_test.dart test/features/settings/presentation/page/settings_page_test.dart` -- expected: all green
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh app test/core test/features/notifications test/features/auth test/features/settings` -- expected: 0 failure
- `cd keevo/app && flutter analyze` -- expected: no new error/warning
- `plutil`-equivalent: `python3 -c "import plistlib;plistlib.load(open(...,'rb'))"` on Info.plist and Runner.entitlements -- expected: parse OK

**Manual checks (iOS device — not possible on the Linux host; to do on a Mac):**
1. Register the iOS app `com.example.keevo` in the Firebase project, download `GoogleService-Info.plist` into `ios/Runner/` (add to Runner target), upload the APNs auth key (.p8) in Firebase Console → Cloud Messaging.
2. Xcode → Runner → Signing & Capabilities: set Team; confirm **Push Notifications** and **Background Modes (Remote notifications)** show as enabled (from entitlements/Info.plist).
3. `flutter build ios` / run on a physical iPhone (push does not work on the simulator before iOS 16 / Apple-silicon): the permission prompt appears once at first sign-in; accept.
4. App in foreground: trigger a stock alert, an EOD report, an employee action and a Super Admin global push → exactly one banner each; tap → navigates to `deepLink`; entry appears in the bell list.
5. App in background / killed: same pushes are shown by iOS; tap opens the right page (cold start via `getInitialMessage`).
6. Logout → no further pushes to the device; backend `device_tokens` row removed.
7. Optional: set the iOS app icon (`flutter_launcher_icons` `ios: true`) — the template icons are Flutter defaults.

## Auto Run Result

Status: done

**Summary:** iOS foreground notifications wired. `FcmService` initialises `flutter_local_notifications` with `DarwinInitializationSettings` (no auto-prompt), requests alert/badge/sound via `IOSFlutterLocalNotificationsPlugin.requestPermissions`, disables FCM native foreground presentation on iOS (no duplicates) and shows foreground pushes with `DarwinNotificationDetails`. Firebase calls go through an injectable `FcmMessagingGateway` (`fcmMessagingGatewayProvider`). Token registration is centralised in `registerCurrentToken()` (APNs poll on iOS), `lastRegisteredToken` lets sign-out revoke a stale backend token (routed 16.1 item resolved), and `deleteToken()` stops post-logout refresh registration. A missing `ios/` Runner was generated (Flutter 3.41 template) with push config.

**Files changed:**
- `keevo/app/lib/core/notification/fcm_service.dart` — gateway seam, Darwin init/permissions/details, registration + APNs poll, sign-out state, cold-start local tap.
- `keevo/app/lib/features/notifications/presentation/provider/notification_provider.dart` — `fcmMessagingGatewayProvider`.
- `keevo/app/lib/core/scaffold/main_shell.dart` — single registration path `registerCurrentToken()`.
- `keevo/app/lib/features/auth/presentation/provider/sign_out_service.dart` — also revokes `lastRegisteredToken`.
- `keevo/app/ios/**` — Runner template, `Podfile` (iOS 13), `Info.plist` background modes fetch/remote-notification, `Runner.entitlements` (aps-environment) + `CODE_SIGN_ENTITLEMENTS`, `AppDelegate.swift` notification-center delegate.
- Tests: `test/core/notification/fcm_service_test.dart`, `test/core/notification/ios_push_config_test.dart`, `test/features/notifications/presentation/fcm_service_provider_test.dart`, `test/features/auth/presentation/provider/sign_out_service_test.dart`.
- `keevo/app/pubspec.yaml` — 1.1.29+28.

**Review:** 1 pass, 36 findings — 12 rows patched (medium 5, low 7; merged into 9 fixes), 3 deferred → `v1s-16-18-ios-release-identity`, 21 rejected (reasons in the triage log).

**Follow-up review recommendation:** true — 2+ medium entries were patched on this first pass. The named unverified risk is the native iOS path: the hand-edited `project.pbxproj` (invented PBXFileReference id) and the delegate chaining between flutter_local_notifications and firebase_messaging under the UIScene/implicit-engine template. No Dart test can exercise either; Xcode on a device is the only check (manual checklist).

**Verification:** `run-tests-chunked.sh app test/core test/features/notifications test/features/auth test/features/settings` → core +441, notifications +16, auth +90, settings +43, 0 failures. `flutter analyze` 615 issues (baseline ~630), no new error or warning in touched files. Info.plist / Runner.entitlements parse with plistlib.

**Residual risks:** nothing iOS built or run (Linux host). The manual checklist (Verification §1-7) is still open: GoogleService-Info.plist, APNs key, signing, device pushes foreground/background, logout. `lastRegisteredToken` is process-local, so stale tokens across restarts rely on backend `UNREGISTERED` pruning.
