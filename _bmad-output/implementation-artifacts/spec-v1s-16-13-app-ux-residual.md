---
title: 'v1s-16-13 — Residual app UX defects (auth/router, banners, lists, pickers)'
type: 'bugfix'
created: '2026-10-12'
status: 'done'
baseline_revision: '169727c3087dafd5996018758d6505bce6ba4ed0'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
warnings: [oversized, multiple-goals]
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 16.13 collects 23 pre-existing app UX defects routed from deferred-work (auth/router exits and redirects, stacked banners, SnackBar flood, sync indicator details, list/picker glitches, plan-limit labels). Some were fixed since by UI-3 / 16-x stories; the rest are still live.

**Approach:** Close every item: (a) already fixed → tick with file/test proof; (b) obsolete or contradicting a product decision → explicit rejection with reason; (c) otherwise fix it in the smallest local way, with a focused test, respecting the UI-3 design system (theme tokens, `SoftIconButton`, existing banner widgets).

## Boundaries & Constraints

**Always:** French UI copy (NFR26). Theme tokens only (no new hard-coded colors). Keep 16-14 LIKE escaping and 16-16 search folding (`core/storage/search_fold.dart`) in any touched search. Bump `keevo/app/pubspec.yaml` 1.1.25+24 → 1.1.26+25. Every item in the epic brief gets ticked with its disposition.

**Never:** No backend change. No English/i18n layer (NFR26: V1 is French-only; i18n is V2). No new packages (no SIM/locale detection lib). No reactivation of the PENDING_VALIDATION flow. No full test suites (chunked runs only). Do not touch deferred-work.md.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Owner-only route, role pref missing | session, `kUserRoleKey` null, path `/dashboard` | redirect `/splash` (re-applies JWT claims) | storage error → `/splash` (unchanged) |
| `/auth/login` with session | session present | redirect `/splash` (wizard / forced pw change / role decided there) | — |
| `/auth/reset-password` deep link | extra null or no non-empty `phoneNumber` | redirect `/auth/forgot-password` | — |
| Concurrent SESSION_REVOKED | N `onAccountSuspended` calls within 10 s | one SnackBar | — |
| Days since last sync, clock skew | last sync in the future | 0 | — |
| Days since last sync, app left open | crossing a 24 h boundary | provider re-evaluates without a sync | timer cancelled on dispose |
| Stacked banners | several conditions true | only the highest-priority banner shows | — |
| Draft promotion stock | `"abc"`, `"-3"`, `"1.5"` | inline error, Valider disabled | empty → 0 (allowed) |
| Custom period picker | stored range outside [firstDate, lastDate] | initial range clamped, picker opens | — |
| Contacts search | query + « Voir les archivés » on | archived matches included; sync refresh keeps the query | — |

</intent-contract>

## Code Map

- `keevo/app/lib/core/router/app_router.dart:168-197` -- `globalAuthRedirect`: role null passes owner-only routes (line 191). `/auth/login` redirect (240-253) sends a session straight to dashboard/pos. `/auth/reset-password` builder (272-278) accepts a missing extra. `/terms` route (306-309); public paths set (173-181).
- `keevo/app/lib/core/router/startup_route.dart` -- read-only: splash decides wizard → forced pw change → role; `applyClaims` rewrites `kUserRoleKey`.
- `keevo/app/lib/features/auth/presentation/page/auth_page.dart:429-484` -- CGU/Privacy `TextSpan`s without recognizer; `initialCountryCode: 'CM'` (334). Back nav already deterministic (`_hasBack`/`_onBack`, 102-113).
- `keevo/app/lib/features/onboarding/presentation/page/terms_page.dart` -- legal text + acceptance zone; gets a read-only mode.
- `keevo/app/lib/features/auth/presentation/page/{forgot_password_page.dart:157, reset_password_page.dart:37}`, `team/presentation/page/create_employee_page.dart:113,167` -- 'CM' literals; employee limit hard-coded 5.
- `keevo/app/lib/features/stores/presentation/page/stores_list_page.dart:101-102` -- store limit hard-coded 3 (FREE = 1, `PlanType.FREE(1, 500, 3)` backend).
- `keevo/app/lib/features/settings/presentation/widget/plan_limit_bottom_sheet.dart` -- sheet copy says "plan gratuit"; owns FREE limits.
- `keevo/app/lib/features/settings/presentation/provider/{subscription_info_provider.dart, account_status_provider.dart:75-82}` -- `valueOrNull` keeps the previous tenant's plan during reload.
- `keevo/app/lib/features/auth/presentation/provider/{session_claims_applier.dart:41-80, sign_out_service.dart:36-73}` -- auth transitions; add subscription invalidation.
- `keevo/app/lib/features/auth/presentation/provider/auth_provider.dart:124-131` -- `onAccountSuspended` SnackBar per call.
- `keevo/app/lib/core/scaffold/main_shell.dart:356-361` + banners `settings/.../suspension_banner.dart`, `account/.../deletion_pending_banner.dart`, `sync_indicator/.../sync_warning_banner.dart`, `core/scaffold/offline_gate_banner.dart` (dismiss state local to the widget).
- `keevo/app/lib/core/sync/sync_gate_provider.dart:11-17` -- no clamp, not time-reactive; invalidated by `sync_trigger_notifier.dart:125,212`.
- `keevo/app/lib/features/sync_indicator/presentation/widget/sync_indicator.dart:113-200` -- loading branch renders `SyncStatus.online`; no Semantics on 'Sync urgente' / blocked.
- `keevo/app/lib/features/catalog/presentation/page/csv_import_page.dart:93-196` -- `_mapping` kept on cancel / new file → `DropdownButtonFormField` value not in items.
- `keevo/app/lib/features/catalog/presentation/widget/product_card.dart:497-560` -- promote dialog: `int.tryParse(...) ?? 0`.
- `keevo/app/lib/features/contact/presentation/{page/client_list_page.dart:91-163, page/supplier_list_page.dart, provider/contact_provider.dart:64-139,143+}` + `data/datasource/local_{client,supplier}_datasource.dart` `search()` (hard `archived = false`), repos/interfaces `search(String)`.
- `keevo/app/lib/features/profitability/presentation/widget/period_filter_chips.dart:75-89`, `pos/presentation/page/sales_history_page.dart:255-265` -- initial range not clamped to [firstDate, lastDate].
- `keevo/app/lib/features/auth/presentation/page/{tenant_picker_page.dart:~95, password_change_page.dart:71}` -- no exit (PopScope canPop false).
- Already fixed (evidence): logout guard `settings_page.dart:355-366` (`_signOutInProgressProvider`, 1041827); HF-2 `reports_page.dart:34-52` + `test/features/reports/presentation/page/reports_page_test.dart:63`; back nav `auth_page_navigation_test.dart:58-80` (1041827).
- Obsolete: PENDING_VALIDATION is never created by the app (`record_sale_notifier.dart:116` always `COMPLETED`; no navigation to `/pos/pending`; spec-v1s-13-10 notes it unreachable since 2026-05-03).

## Tasks & Acceptance

**Execution:**
- `keevo/app/lib/core/router/app_router.dart` -- role null on owner-only → `/splash`; non-OWNER non-null role → `/pos`; `/auth/login` with session → `/splash` (extract `@visibleForTesting loginRouteRedirect`); `/auth/reset-password` route redirect to `/auth/forgot-password` when phone extra missing/empty (extract `resetPasswordRedirect`); add public `/terms/view` route → `TermsPage(readOnly: true, section: ...)`.
- `keevo/app/lib/features/onboarding/presentation/page/terms_page.dart` -- `readOnly` + optional `scrollToPrivacy`: hides progress dots, reading hint and acceptance zone; back pops (fallback `/auth/register`).
- `keevo/app/lib/features/auth/presentation/page/auth_page.dart` -- `TapGestureRecognizer`s (disposed) on the CGU / Privacy spans → `context.push('/terms/view')` / `?section=privacy`; use `kDefaultPhoneCountryCode`.
- `keevo/app/lib/core/storage/app_constants.dart` -- `kDefaultPhoneCountryCode = 'CM'`; replace the 3 literals.
- `keevo/app/lib/features/settings/presentation/widget/plan_limit_bottom_sheet.dart` -- `kFreePlanLimits` (stores 1, products 500, employees 3); `limit` optional, defaults to the FREE value of `entity`; callers in create_employee_page / stores_list_page stop passing wrong literals.
- `keevo/app/lib/features/settings/presentation/provider/account_status_provider.dart` -- ignore the subscription while loading/error (no stale previous value).
- `keevo/app/lib/features/auth/presentation/provider/{session_claims_applier.dart, sign_out_service.dart}` -- invalidate `subscriptionInfoProvider` on new session and sign-out.
- `keevo/app/lib/core/widget/notice_throttle.dart` (new) + `auth_provider.dart` -- one suspended-session SnackBar per 10 s window (injectable clock).
- `keevo/app/lib/core/scaffold/shell_banners.dart` (new) + `main_shell.dart` + `offline_gate_banner.dart` -- one banner at a time, priority: suspended > deletion pending > offline gate critical > sync critical failure > offline gate warning (not dismissed) > trial expired; dismiss state moved to `offlineGateDismissedTodayProvider`.
- `keevo/app/lib/core/sync/sync_gate_provider.dart` -- clamp ≥ 0; self-invalidate at the next day boundary (timer cancelled on dispose).
- `keevo/app/lib/features/sync_indicator/presentation/widget/sync_indicator.dart` -- neutral loading (outline dot, « Vérification… »); `Semantics` labels on stale-critical and blocked states.
- `keevo/app/lib/features/catalog/presentation/page/csv_import_page.dart` -- reset `_mapping` on cancel; on a new file keep only mappings whose header exists.
- `keevo/app/lib/features/catalog/presentation/widget/product_card.dart` -- digits-only input, inline error for invalid values, Valider disabled while invalid.
- `keevo/app/lib/features/contact/...` -- `search(query, {includeArchived})` through datasource/repo/interface; notifiers remember the query and reapply it on refresh/sync/archive toggle; list pages move archived/sync buttons into pinned `SliverAppBar.actions`.
- `keevo/app/lib/core/widget/date_range_clamp.dart` (new) -- `clampDateRange(range, first, last)`; used by period_filter_chips and sales_history_page.
- `keevo/app/lib/features/auth/presentation/page/{tenant_picker_page.dart, password_change_page.dart}` -- tenant picker back → `/auth/login`; password change « Se déconnecter » → `SignOutService.signOut()` then `/auth/login`.
- Tests: extend `test/core/router/global_auth_redirect_test.dart`, `test/core/sync/days_since_last_sync_provider_test.dart`, `test/features/sync_indicator/sync_indicator_test.dart`, contact provider/datasource tests; add tests for throttle, date clamp, shell banner priority, reset redirect, terms read-only, promote validation, plan limit labels, logout double tap.
- `keevo/app/pubspec.yaml` -- version 1.1.26+25.
- `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md` -- tick all 23 items with disposition.

**Acceptance Criteria:**
- Given an OWNER on the FREE plan at its limit, when creating an employee, then the sheet reads « limite de 3 employés »; for stores « 1 boutique ».
- Given a session is revoked by N concurrent requests, when the interceptor reports it, then exactly one SnackBar is shown.
- Given suspension + deletion pending + offline warning, when the shell renders, then only the suspension banner shows; clearing it reveals the deletion banner.
- Given the register form, when the user taps « Conditions d'utilisation », then a read-only terms page opens and back returns to the form.
- Given the tenant picker or forced password change, when the user wants to leave, then a visible action returns to login (password change signs out first).
- Given every item of the 16.13 brief, when the story closes, then each is ticked with « résolu », « déjà couvert (preuve) » or « rejeté (raison) ».

## Spec Change Log

## Review Triage Log

### 2026-10-12 — Review pass
- verdicts: 39 findings — high 2, medium 2, low 24, false 11, maybe-false 0
- findings:
  - `[high]` `[patch]` (blind) splash failure → `/auth/login` → `loginRouteRedirect` → `/splash` loop while a session exists — fixed: splash fallback passes a `splashFailed` marker, login redirect shows the form when present; test added.
  - `[false]` `[reject]` (blind) forced-pw « Se déconnecter » skips flush, unsynced sales wiped — the local DB is kept on sign-out and another account signing in gets `PreviousAccountDataException` (confirm dialog) before any wipe (`session_claims_applier.dart` `_isolateFromPreviousAccount`).
  - `[low]` `[reject]` (blind) `accountStatusProvider` hides trial-expired while the subscription reloads/fails — the provider is network-only and refetched only on auth transitions / subscription-page retry; a brief hide is the intended trade-off against showing another tenant's plan; fixing needs tenant-keyed state (complexity).
  - `[false]` `[reject]` (blind) suspended outranks deletion pending and hides its cancel action — `tenantStatus` is single-valued (SUSPENDED and DELETION_PENDING are exclusive) and a SUSPENDED tenant's writes, cancel included, are refused by `JwtAuthFilter.java:179-184`.
  - `[low]` `[reject]` (blind) dismissed-today flag not reset at midnight while the app stays open — identical to the pre-change widget state (computed once in `initState`); rare, fix needs a midnight timer.
  - `[low]` `[reject]` (blind) plan-limit sheet ignores the server limit — no page receives it (`onPlanLimitExceeded` is not wired in `dioProvider`); only FREE has limits, constant mirrors `PlanType.FREE`; plumbing the server value is more than a direct correction.
  - `[low]` `[patch]` (blind) tenant picker exits active during selection; deprecated `onPopInvoked` — fixed: disabled while loading, `onPopInvokedWithResult`.
  - `[low]` `[patch]` (blind) archive flag duplicated / unused `includeArchived` getter; out-of-order search results — unused getters removed; stale-response race rejected (local SQLite queries, sub-ms, guard adds complexity).
  - `[low]` `[reject]` (blind) fragile tests: real-time day-boundary test, out-of-scope schema test fix, double-tap test without prod change — the timer test has a 150 ms margin on a 400 ms wait; the schema 27→28 fix is a stale 16-15 expectation blocking the dashboard batch (recorded); the double-tap test is the « déjà couvert » proof; whitespace line removed.
  - `[low]` `[patch]` (blind) missing tests: throttle wiring, terms read-only fallback, csv dropdown value — throttle wiring test added (see verification-gap); others rejected (fallback = one `go`, dropdown guard covered by the file-picked test).
  - `[low]` `[patch]` (blind) stray blank line before `dioProvider`, import order in sales_history_page — fixed.
  - `[high]` `[patch]` (edge) splash/login loop — same as blind #1.
  - `[medium]` `[patch]` (edge) `fcm.getToken()` throwing offline skips `clearSessionSecrets`, user bounced back to forced password change — fixed in `SignOutService` (FCM steps in try/catch); test added.
  - `[low]` `[patch]` (edge) tenant picker back during in-flight selection — same as blind #7.
  - `[low]` `[reject]` (edge) dismissed flag across midnight — same as blind #5.
  - `[false]` `[reject]` (edge) day timer lags after deep sleep / clock change — when the timer fires late the provider recomputes from wall-clock `now`; every sync also invalidates it.
  - `[low]` `[reject]` (edge) out-of-order search results on rapid typing — same as blind #8.
  - `[low]` `[reject]` (edge) notifier kept alive by checkout keeps a hidden query when the list page reopens — needs checkout and the OWNER-only contact list open together; fix adds public surface.
  - `[low]` `[reject]` (edge) trial banner hidden while same-tenant refresh fails — same as blind #3.
  - `[low]` `[patch]` (edge) `NoticeThrottle` suppresses after the clock moves backwards — fixed (`!now.isBefore(last)`), test added.
  - `[false]` `[reject]` (edge) unknown entity → « limite de 0 » — every caller passes 'stores'/'products'/'employees'.
  - `[low]` `[patch]` (edge) reset-password builder cast `Map<String, dynamic>` throws for another Map type — cast to `Map?`.
  - `[medium]` `[patch]` (edge, claim) forced-pw sign-out exit can fail and trap the user — same root cause as edge #2.
  - `[low]` `[patch]` (verification-gap) throttle wiring untested — extracted `@visibleForTesting` notice function + widget test (5 calls → 1 SnackBar).
  - `[low]` `[patch]` (verification-gap) date picker call sites untested — period_filter_chips widget test with an out-of-range stored range.
  - `[low]` `[patch]` (verification-gap) dismissed-today read-back untested — seeded-pref tests (today hidden / yesterday shown).
  - `[low]` `[patch]` (verification-gap) account status with failed reload + previous value untested — test added.
  - `[low]` `[patch]` (verification-gap) ShellBanners sync-failure input untested — widget test added.
  - `[low]` `[patch]` (verification-gap) contacts clear button untested — page tests added.
  - `[low]` `[patch]` (verification-gap) `/terms/view` test re-declares the route — production builder extracted and reused by the test.
  - `[false]` `[reject]` (intent a) epic checkboxes not ticked — done at finalize (step order), not a code defect.
  - `[false]` `[reject]` (intent b) CGU links open an in-app page instead of /docs/politique — no `/docs/politique` route or published URL exists; the in-app legal text is the accepted CGU (TermsPage).
  - `[false]` `[reject]` (intent c) role null → `/splash` instead of login — splash falls back to login when signed out and re-applies claims otherwise (Design Notes).
  - `[low]` `[reject]` (intent d) employee limit not read from the API — same as blind #6.
  - `[false]` `[reject]` (intent e) one banner at a time vs stacked-with-order — both readings define precedence; one-at-a-time chosen and recorded in Design Notes.
  - `[low]` `[patch]` (intent f) throttle wiring untested — same as verification-gap #1.
  - `[false]` `[reject]` (intent g) route wiring of extracted redirect functions untested — each route delegates in one line to the tested function (read in diff).
  - `[false]` `[reject]` (intent h) country-code detection / i18n rejected — recorded with reasons (NFR26, Cameroon target).
  - `[false]` `[reject]` (intent i) « already covered » items lack proof in diff — proof cited (files/tests/commit) in Design Notes and in the epic ticks.

## Design Notes

- Item dispositions: **already covered** — logout re-entrancy (guard + progress dialogs, 1041827; test added here), HF-2 currentUserId (reports_page + test), back in login mode (auth_page_navigation_test). **Closed by decision** — OTP single field (AC7 amended 2026-10-11). **Rejected** — legacy pending-sale flow (unreachable, see Code Map), English auth pages (NFR26), SIM/locale country detection (target market is Cameroon; Cameroonian devices often report fr_FR, which would pre-select France; the picker lets the user change country — constant centralized instead).
- Role null → `/splash`, not `/auth/login`: login with a session redirects to splash anyway; the splash re-applies the JWT claims (rewrites the role) or falls back to login when signed out — no loop.
- Banner priority puts account-blocking states first, then the time-bound deletion (has an action), then offline-gate severity; trial-expired is informational. One banner at a time keeps the content visible on small screens.

## Verification

**Commands:**
- `cd keevo/app && flutter analyze` -- expected: no new error/warning vs ~630 infos baseline.
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh app test/core test/features/<touched dir>` one directory at a time -- expected: all OK except known `offline_gate_banner_test` baseline failure (re-check it, the banner is touched).

## Auto Run Result

Status: done

**Summary:** all 23 items of Story 16.13 closed — 17 resolved in code (item 23 partly: its English-pages part rejected per NFR26), 3 already covered (proof cited), 1 closed by the AC7 decision, 2 rejected with reason (legacy pending-sale flow, SIM/locale country detection); dispositions ticked in `epics-remediation-audit.md`. App version 1.1.26+25.

**Files changed (keevo/app):**
- `lib/core/router/app_router.dart` — role-null guard, `/auth/login` → splash (+ splash-failure marker), reset-password redirect, `/terms/view` (`termsViewPage`).
- `lib/core/scaffold/shell_banners.dart` (new), `main_shell.dart`, `offline_gate_banner.dart` — single prioritized banner, dismiss state in a provider.
- `lib/core/sync/sync_gate_provider.dart` — clamp + day-boundary re-evaluation.
- `lib/core/widget/notice_throttle.dart`, `date_range_clamp.dart` (new) — SnackBar throttle, picker range clamp.
- `lib/core/storage/app_constants.dart` — `kDefaultPhoneCountryCode`.
- `lib/features/auth/...` — CGU links, tenant-picker / password-change exits, throttled suspended notice, subscription invalidation, FCM-safe sign-out.
- `lib/features/onboarding/.../terms_page.dart` — read-only mode.
- `lib/features/settings/...` — FREE plan limits, account status ignores stale subscription.
- `lib/features/sync_indicator/.../sync_indicator.dart` — neutral loading, Semantics.
- `lib/features/catalog/...` — csv mapping reset, promotion stock validation.
- `lib/features/contact/...` — archived-aware search, query kept, pinned actions.
- `lib/features/pos/.../sales_history_page.dart`, `profitability/.../period_filter_chips.dart` — clamped picker range.
- `lib/features/team/...`, `stores/...` — plan-limit callers, country constant.
- tests: 6 new files, ~20 extended; `test/features/dashboard/.../users_first_name_migration_test.dart` stale schemaVersion 27 → 28 (left by v1s-16-15).

**Review:** 39 findings — patched: high 1 (splash/login loop, reported twice), medium 1 (FCM failure skipped session clear, reported twice), low 15 (tenant-picker exit while loading + deprecated API, throttle clock-back, reset cast, unused getters, style nits, 7 test-coverage gaps); deferred 0; rejected: 11 false + 9 low, each with its reason in the Review Triage Log.

**Follow-up review recommended:** true — one high (splash failure → login loop) was patched in this pass; unverified risk: the splash-failure marker path is only covered at the redirect-function level, not by a real GoRouter run with a failing `resolveStartupRoute`. Patched counts: high 1, medium 1, low 15.

**Verification:** `flutter analyze` — 632 issues, no new error/warning (all warnings pre-existing). Chunked runs (`run-tests-chunked.sh app`, one directory at a time): test/core +384 −1 (known baseline `offline_gate_banner_test` amber colour), auth +86, contact +37, settings +43, profitability +45, onboarding +26, catalog +148, sync_indicator +62, team +89, stores +45, pos +212, account +20, reports +42, dashboard +85 — all OK.

**Residual risks:** day-boundary timer test uses ~400 ms of real time; contact search trims the query; the offline-gate « dismissed today » flag does not roll over at midnight while the app stays open (pre-existing behaviour, rejected low).
