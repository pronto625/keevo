---
title: 'v1s-16-11 — "Réessayer" on every app error screen'
type: 'bugfix'
created: '2026-10-11'
status: 'done'
baseline_revision: '37857813eb99564501475fcd34c86c7d820c229a'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** Audit M4 (audit-coherence-2026-10-11.md): only 4 of 23 `AppErrorWidget` usages pass `onRetry`, and ~12 screens render hand-rolled error states (raw `'Erreur : $e'`, hard-coded `AppTheme.errorColor`, no retry). A transient Drift/network failure leaves the user on a dead screen with no way out but leaving the page.

**Approach:** Make `AppErrorWidget` the single reusable full-page/section error state, restyled in the UI-3 Soft language, with a **required** `onRetry` and optional `title`/`message` overrides. Wire every full-page/section error branch to it with an `onRetry` that invalidates (or refreshes) the exact provider that failed, and delete the ad-hoc error widgets it replaces.

## Boundaries & Constraints

**Always:** theme tokens only (`colorScheme.*`, `SoftIconBadge`), no hex/`Colors.*`/`AppTheme.errorColor` literals in the error state; French, non-technical copy (via `appErrorMessage` or a context-specific `message`); the retry callback targets the provider (with the same family args) whose `AsyncValue` is in error; widget must not overflow in short `SliverFillRemaining`/`Expanded` slots (scrollable content); bump `keevo/app/pubspec.yaml` 1.1.19+18 → 1.1.20+19.

**Never:** touch inline/compact error states (dropdowns, pickers, `AppErrorInline`, form banners), backend, or business logic of providers; introduce a new package; change success/loading branches.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Load fails | provider emits `AsyncError` | soft error badge + title + human message + "Réessayer" button | message via `appErrorMessage` (never UUID/stack) |
| Retry succeeds | tap "Réessayer", provider now returns data | provider invalidated → loading → data rendered | — |
| Retry fails again | tap, provider throws again | error state shown again, button still tappable | no crash, no SnackBar flood |
| Tiny slot | error inside a short sliver/Expanded | content scrolls, no RenderFlex overflow | — |

</intent-contract>

## Code Map

- `keevo/app/lib/core/widget/app_error_widget.dart` -- `AppErrorWidget` (l.75, `onRetry` optional, `OutlinedButton`, plain `Icon`), `appErrorMessage`, `AppErrorInline` (keep untouched). Reuse `SoftIconBadge` from `core/widget/soft_icon_badge.dart`.
- AppErrorWidget sites without retry (provider to invalidate):
  - `sync_indicator/presentation/page/sync_conflict_log_page.dart:40` -- `syncConflictsProvider`
  - `sync_indicator/presentation/page/sync_settings_page.dart:260,338,456` -- `syncHistoryProvider`, `syncConflictsProvider`, `pendingSyncQueueProvider`
  - `inventory/presentation/page/inventory_gap_report_page.dart:97` -- `gapReportProvider(sessionId)`
  - `inventory/presentation/page/inventory_counting_page.dart:99` -- `countingProductsProvider(widget.sessionId)`
  - `inventory/presentation/page/global_stock_overview_page.dart:283,341` -- `stockSearchResultsProvider`, `globalStockOverviewProvider`
  - `inventory/presentation/page/inventory_launch_page.dart:139` -- `sessionHistoryProvider()`
  - `notifications/presentation/page/notifications_page.dart:67` -- `notificationsListProvider`
  - `pos/presentation/page/pos_page.dart:354,472` -- `frequentProductsProvider(key)`, `posSearchProvider`
  - `pos/presentation/page/pending_sales_page.dart:33` -- `pendingSalesProvider(storeId)`
  - `pos/presentation/page/sale_detail_page.dart:51` -- `saleByIdProvider(saleId)`
  - `pos/presentation/page/sales_history_page.dart:85,116` -- `currentUserIdProvider`, `salesHistoryProvider(filter)`
  - `catalog/presentation/page/catalog_page.dart:878` -- `categoriesProvider`
  - `catalog/presentation/page/categories_page.dart:209` -- `categoriesProvider`
  - `reports/presentation/page/report_detail_page.dart:51` -- `reportDetailProvider(reportId)`
  - `stores/presentation/page/stores_list_page.dart:234` -- `managedStoresProvider(_includeInactive)`
- Already-retrying AppErrorWidget sites (keep, just compile against new API): `reports_page.dart:102`, `report_history_page.dart:90`, `delete_account_page.dart:110`.
- Ad-hoc full-page/section error states to migrate:
  - `catalog/presentation/page/catalog_page.dart:558` -- `_ProductListView`, tab-switched providers `productListProvider`/`archivedProductListProvider`/`outOfStockProductListProvider`/`lowStockProductListProvider`; raw `'Erreur : $error'`
  - `inventory/presentation/page/transfer_history_page.dart:164` -- `_ErrorCard` (l.265), `transferHistoryProvider()`
  - `profitability/presentation/page/product_profitability_detail_page.dart:82` -- `_ErrorState` (l.282, keeps its `_message()` mapping as `message:`), `productProfitabilityDetailProvider(args)`
  - `profitability/presentation/page/product_profitability_list_page.dart:112`, `store_performance_page.dart:105` -- inline Column already retrying (`productProfitabilityProvider`, `storePerformanceProvider`)
  - `audit/presentation/widget/stock_history_widget.dart:107` -- `auditHistoryNotifierProvider(entityType:, entityId:)`
  - `contact/presentation/page/client_list_page.dart:219`, `supplier_list_page.dart:219` -- `_ErrorState` (l.311) retry = `_syncWithFeedback` (keep that callback)
  - `settings/presentation/page/subscription_page.dart:25` -- `subscriptionInfoProvider`, `AppTheme.errorColor` literal
  - `dashboard/presentation/page/dashboard_page.dart:34` -- `_ErrorState` (l.457), `dashboardSnapshotProvider`
  - `dashboard/presentation/page/store_dashboard_page.dart:45` -- `storeOverviewsProvider`
  - `team/presentation/page/team_page.dart:80` -- inline Column, retry `employeeListProvider.notifier.refresh()`
  - `pos/presentation/page/pending_sale_detail_page.dart:57` -- `saleByIdProvider(widget.saleId)`
  - `pos/presentation/widget/day_summary_bottom_sheet.dart:209` -- sheet's main content, raw `'Erreur: $e'`; provider `todaySummaryProvider((…))` (l.139, same record args)
- Existing test touching the widget: `test/features/reports/presentation/page/reports_page_test.dart`.

## Tasks & Acceptance

**Execution:**
- `keevo/app/lib/core/widget/app_error_widget.dart` -- `AppErrorWidget({required error, required VoidCallback onRetry, String? title, String? message})`; layout Center → SingleChildScrollView → Padding(32) → Column(min): `SoftIconBadge(icon: Icons.error_outline_rounded, color: cs.error, size: 72)`, title (default 'Un problème est survenu', `titleMedium`), message (default `appErrorMessage(error)`, `bodySmall` `onSurfaceVariant`), `FilledButton.tonalIcon(refresh_rounded, 'Réessayer')`. Update doc comment. -- one consistent, token-only error state; `required` makes forgetting retry a compile error.
- Every file listed under "AppErrorWidget sites without retry" -- pass `onRetry: () => ref.invalidate(<provider with same args>)` -- AC M4.
- Every file listed under "Ad-hoc full-page/section error states" -- replace the hand-rolled error UI with `AppErrorWidget` (keep the wrapping sliver/padding; keep existing retry callbacks; keep context titles/messages via `title:`/`message:`), delete the now-unused private error widgets and imports. -- single pattern, removes `AppTheme.errorColor` literals and technical `'Erreur : $e'` copy.
- `keevo/app/test/core/widget/app_error_widget_test.dart` -- new: renders title/message/button; tap calls `onRetry`; custom title/message; UUID error → generic message; no overflow in a 150px-high box.
- `keevo/app/test/core/widget/app_error_retry_wiring_test.dart` -- new: for at least 3 representative screens (one family provider, one sliver site, one migrated ad-hoc site — e.g. `CategoriesPage`, `NotificationsPage`/sync conflict log, `SubscriptionPage`) override the provider to fail on first call and succeed on the second; pump, tap "Réessayer", assert data rendered.
- `keevo/app/pubspec.yaml` -- version 1.1.20+19.

**Acceptance Criteria:**
- Given any screen listed in the Code Map whose main load fails, when it renders, then it shows `AppErrorWidget` with a "Réessayer" button.
- Given that error state, when the user taps "Réessayer" and the provider now succeeds, then the screen shows its data (widget tests).
- Given `grep -rn "AppErrorWidget(" lib`, then every call passes `onRetry` (enforced by `required`).
- Given `flutter analyze`, then no new error/warning; given `flutter test`, then only the known `offline_gate_banner_test` failure remains.

## Design Notes

- Out of scope, justified (compact/inline, not a screen-level dead end): dropdown/picker error branches (`product_form_page.dart:488,1252`, `create_employee_page.dart:201`, `edit_employee_page.dart:272`, `create_draft_product_bottom_sheet.dart:323`, `quick_add_product_sheet.dart:165`, `supplier_form_page.dart:455`, `employee_card.dart:40`), `AppErrorInline` sites (inventory config / transfer form sheets), `sync_detail_bottom_sheet.dart:56,89` and `sync_settings_page.dart:159` (one-line rows inside a larger screen), `sync_indicator.dart:153` (status dot), `cross_store_availability_bottom_sheet.dart:122` (deliberate offline fallback copy, not an error), `account_page.dart:51` `_ErrorBanner` (already retries), auth `_ErrorBanner`s (form submission errors), `debug/category_debug_page.dart` (debug-only).
- `onRetry` is required rather than optional: the audit finding is precisely "optional retry gets forgotten".
- Button style `FilledButton.tonalIcon` matches the UI-3 retry buttons already used on profitability/team screens.

## Verification

**Commands:**
- `cd keevo/app && flutter analyze` -- expected: no new error/warning vs baseline (~630 infos)
- `cd keevo/app && flutter test test/core/widget` -- expected: all green
- `cd keevo/app && flutter test` -- expected: only `offline_gate_banner_test` fails

## Spec Change Log

## Review Triage Log

### 2026-10-11 — Review pass
- verdicts: 21 findings — high 1, medium 3, low 9, false 6, maybe-false 2
- findings:
  - `[high]` `[patch]` (blind) POS search retry `ref.invalidate(posSearchProvider)` resets the notifier to `AsyncData([])` + empty `lastQuery` instead of re-running the search — verified in `pos_search_provider.dart:33-41`; patched: retry calls `search(lastQuery, activeStoreId)`; notifier re-run test added in `pos_search_provider_test.dart`.
  - `[low]` `[patch]` (blind) catalog `_ProductListView` uses `skipLoadingOnReload`, so the error stays on screen with no feedback during a retry — patched: error branch shows the spinner while `listAsync.isLoading`.
  - `[medium]` `[patch]` (blind) client/supplier retry = `_syncWithFeedback` (remote sync): offline/local errors show "Synchronisation impossible" and repeated taps start parallel syncs — patched: retry = `notifier.refresh()`; sync button keeps the sync.
  - `[medium]` `[patch]` (blind) day-summary sheet auto-dismiss timer (5s) can pop the sheet during a retry — patched: retry calls `_cancelAutoDismiss()` first.
  - `[maybe-false]` `[reject]` (blind) nested `SingleChildScrollView` in transfer history may steal pull-to-refresh drags — with unbounded height the inner scrollable has zero extent and non-AlwaysScrollable physics, so it does not accept drags; would need a device check, if true only low. Doubled padding (low) patched: outer `Padding(24)` removed.
  - `[low]` `[reject]` (blind) profitability detail offers "Réessayer" for PRODUCT_NOT_FOUND — retry is harmless (re-fetch, same message); a non-retryable variant would add public API surface for a rare case.
  - `[low]` `[reject]` (blind) `sync_settings_page.dart:159`, `AppErrorInline` sites, store-dashboard sections still without retry — the first two are listed and justified in Design Notes; the `SizedBox.shrink()` sub-sections are compact secondary widgets (see intent row c) and their justification is recorded in the epic item note, not by editing this spec.
  - `[low]` `[reject]` (blind) wiring tests cover 4 representative screens, not each site — every target was cross-checked against the watched provider by the verification-gap layer (all match except POS search, now fixed and tested); per-site tests would be ~30 near-duplicate tests.
  - `[low]` `[patch]` (blind) missing blank line before the last `testWidgets` — patched.
  - `[low]` `[reject]` (blind) titles vary between screens ("Erreur de chargement", "Impossible de charger les données") — cosmetic, each preserves the pre-existing screen copy.
  - `[low]` `[patch]` (blind) no screen-reader announcement — patched: content wrapped in `Semantics(liveRegion: true)`.
  - `[high]` `[patch]` (edge) POS search retry wipes the query — same root cause as row 1; patched there.
  - `[false]` `[reject]` (edge, claim) "onRetry invalidates the exact failing provider so retry reloads data" fails for POS — duplicate of row 1, claim now true after patch.
  - `[high]` `[patch]` (gap) POS search retry untested and wrong — same root cause as row 1; notifier-level test added (page-level test impractical: PosPage needs DB/sync providers).
  - `[medium]` `[patch]` (gap) `StockLevelWidget` still renders raw `'Erreur: …'` in `AppTheme.errorColor` without retry — patched: `AppErrorWidget(title: 'Stock indisponible', onRetry: invalidate stockNotifierProvider(productId))`.
  - `[false]` `[reject]` (gap, other) coverage thin — reviewer itself spot-checked all other targets and found them matching.
  - `[maybe-false]` `[reject]` (gap, other) contacts sync-based retry may not clear a local-stream error — moot: retry now calls `refresh()` (row 3).
  - `[low]` `[reject]` (intent a) tests exercise 4 of ~30 sites — same as row 8.
  - `[false]` `[reject]` (intent b) contacts retry is a remote sync, not an invalidate — fixed by row 3 patch; team `notifier.refresh()` is an explicit "refresh" allowed by the intent.
  - `[false]` `[reject]` (intent c) silent `SizedBox.shrink()` branches (store dashboard sections, sales-history employee filter, reports closure state, report-history filter, checkout, pos_page:647, draft banner) not listed — they are optional decorations inside an already-loaded screen (filters, badges, secondary cards), not screen error states; justification recorded in the epic Story 16.11 item note.
  - `[false]` `[reject]` (intent d/e) `required onRetry` only guards `AppErrorWidget` users; epic context compiled in a separate commit — descriptive observations, no defect.

### 2026-10-13 — Review pass (follow-up)
- verdicts: 36 findings — high 2, medium 4, low 18, false 11, maybe-false 1
- findings:
  - `[false]` `[reject]` (blind) diff bundles other stories (16-13/16-20/18-x, version 1.1.46+45) — the follow-up diff runs from the baseline to the current tree by design; those hunks belong to their own stories.
  - `[medium]` `[patch]` (blind) no feedback while a retry runs (`skipLoadingOnRefresh` keeps the AsyncError on screen) — `AppErrorWidget` is now stateful: the tap switches the button to « Nouvel essai… » (still tappable) until a new error instance arrives; widget test added.
  - `[low]` `[patch]` (blind) `StockLevelWidget` passes a String, so the readable French 404 message of `recordEntry` is lost — `appErrorMessage` now shows a readable String and hides technical text; test added.
  - `[low]` `[reject]` (blind) stock `error` field shared by levels/history/writes; invalidate drops the keepAlive notifier — pre-existing state shape (the old inline text also replaced the levels); rebuild reloads levels, history reloads on demand.
  - `[false]` `[reject]` (blind) full-size state in the stock section contradicts « never touch inline states » — the stock block is a section of the product detail (intent: « full-page/section »), migrated deliberately in the first pass.
  - `[low]` `[patch]` (blind) missing blank line in `app_error_retry_wiring_test.dart` — fixed.
  - `[medium]` `[patch]` (blind) patched retries untested (catalog tab, day summary, stock level, team, contacts, POS page) — widget tests added: `catalog_page_test` (low-stock tab only reloads its provider), `day_summary_bottom_sheet_test` (same args, sheet stays open past the 5 s auto-dismiss), `stock_level_widget_test`, `team_page_test`, `contact_list_pages_test` (client + supplier: refresh, no new sync), `pos_page_test` (search error → Réessayer re-runs the same query and store).
  - `[low]` `[reject]` (blind) « Réessayez plus tard » next to a « Réessayer » button — cosmetic copy, shared generic message used by SnackBars too.
  - `[low]` `[reject]` (blind) profitability detail string-matching / retry on PRODUCT_NOT_FOUND — carried: same verdict as the 2026-10-11 row (harmless re-fetch).
  - `[false]` `[reject]` (blind) retry button style not uniform with the old screens — the spec deliberately unifies on `FilledButton.tonalIcon`.
  - `[low]` `[patch]` (blind) live region wraps the button — `Semantics(liveRegion)` now wraps only title + message; test added.
  - `[low]` `[reject]` (blind) doubled insets under SafeArea — cosmetic 32 px padding.
  - `[false]` `[reject]` (blind) leftover unused imports — `flutter analyze` 0 warning.
  - `[high]` `[patch]` (edge) store dashboard retry invalidated the derived `storeOverviewsProvider`, which re-reads the cached failed `dashboardSnapshotProvider` → dead button — retry now invalidates `dashboardSnapshotProvider`; widget test through the real provider chain.
  - `[low]` `[patch]` (edge) String error → generic message — same root cause as blind row 3.
  - `[low]` `[reject]` (edge) shared stock error field — same as blind row 4.
  - `[low]` `[patch]` (edge) Spring reason phrase (`{error: 'Not Found'}`) shown — reason phrases added to `_technical`; falls back to `message` or generic; test added.
  - `[low]` `[patch]` (edge) codes with digits (`HTTP_404`) not filtered — regex accepts digits after `_`. Single-word upper-case codes rejected: would also hide French acronyms (TVA, XAF).
  - `[medium]` `[patch]` (edge) `SocketException` / `SqliteException` / `PlatformException` English `message` shown — those types skip `_messageOf`; test added.
  - `[low]` `[patch]` (edge) `Instance of 'X'` shown — added to `_technical`; test added.
  - `[low]` `[patch]` (edge) Dio `unknown` wrapping a socket error says « le serveur n'a pas pu traiter » — mapped to the connection message; `cancel` rejected (only app-initiated, never rendered in an error state).
  - `[medium]` `[patch]` (edge) no retry feedback — same as blind row 2.
  - `[low]` `[reject]` (edge) day summary auto-dismiss not restarted after a successful retry — intentional: a user interaction stops the countdown (same as `_confirmClosure`); test asserts the sheet stays open with data.
  - `[low]` `[reject]` (edge) empty `title`/`message` strings render blank — no caller passes an empty string.
  - `[maybe-false]` `[reject]` (edge) PRICE_OVERRIDDEN prices serialized as strings lose their amounts — v1s-16-20 presenter code; would need a backend payload sample; if true only low.
  - `[false]` `[reject]` (edge) TRANSFER_COMPLETED / STOCK_RECEIVED lost their French labels — no backend or app code emits these actions (grep).
  - `[false]` `[reject]` (edge, claim) contacts retry no longer syncs — deliberate first-pass patch (a local load error is not fixed by a remote sync; the sync button keeps that role); now widget-tested.
  - `[high]` `[patch]` (edge, claim) store dashboard retry targets the derived provider — same as edge row 1.
  - `[false]` `[reject]` (edge, claim) pubspec 1.1.46+45 vs 1.1.20+19 — cumulative diff; 16-11 itself bumped to 1.1.20+19.
  - `[medium]` `[patch]` (gap) six retry paths with no error → Réessayer test — same as blind row 7.
  - `[low]` `[patch]` (gap) `TimeoutException` arm untested — assertion added.
  - `[false]` `[reject]` (gap, other) review scope / out-of-date residual-risk text — scope by design; the residual-risk text is superseded by this pass's result.
  - `[false]` `[reject]` (intent) diff mixes other stories — same as blind row 1.
  - `[low]` `[reject]` (intent) account_page `_ErrorBanner`, sync devices row, cross-store sheet, debug page not migrated — carried: justified in Design Notes (already retries / one-line row / offline copy / debug-only).
  - `[medium]` `[patch]` (intent) retry verified at provider seam on 4 of ~30 sites — same as blind row 7 (11 screens now tested through the page).
  - `[low]` `[reject]` (intent) tiny-slot test uses a fixed box, not a real sliver — the layout (Center → SingleChildScrollView) is host-agnostic; existing sliver hosts are now exercised by the page tests above.

## Auto Run Result

Status: done

**Summary:** `AppErrorWidget` is now the single UI-3 Soft error state (SoftIconBadge, title/message overrides, required `onRetry`, `FilledButton.tonalIcon` "Réessayer", scroll-safe, live-region semantics). All 19 retry-less usages now invalidate their exact provider; 14 hand-rolled error states were migrated (catalog list, transfer history, profitability ×3, stock history, stock level, clients, suppliers, subscription, dashboard, store dashboard, team, pending sale detail, day summary sheet). POS search retry re-runs the last query.

**Files changed:** `keevo/app/lib/core/widget/app_error_widget.dart` (widget), 30 feature files under `keevo/app/lib/features/**` (retry wiring / migration, private error widgets deleted), `keevo/app/test/core/widget/app_error_widget_test.dart` (5 tests), `keevo/app/test/core/widget/app_error_retry_wiring_test.dart` (5 tests: error → Réessayer → data, incl. fails-twice), `keevo/app/test/features/pos/presentation/provider/pos_search_provider_test.dart` (+1 retry re-run test), `keevo/app/pubspec.yaml` (1.1.20+19).

**Review:** 1 pass, 21 findings — 8 patched (1 high root cause reported by 3 layers, 3 medium, 4 low), 0 deferred, 13 rejected (reasons in the triage log).

**Follow-up review recommended:** true — patched a high (POS search retry); the fix is covered only at notifier level, not by a PosPage widget test.

**Verification:** `flutter analyze` 630 issues (= baseline, no new error/warning); `flutter test` 1356 passed, 1 failed (known baseline `offline_gate_banner_test`).

**Residual risks:** client/supplier retry (`refresh()`) has no widget test (no test file exists for those pages); full-size error state inside the day-summary sheet and transfer-history sliver not checked on a device.

### Follow-up pass 2026-10-13

**Summary:** Store dashboard retry fixed (re-fetches the snapshot instead of the derived provider). `AppErrorWidget` gives tap feedback (« Nouvel essai… ») and announces only the explanation. `appErrorMessage` also hides IO/SQLite/platform English text, `Instance of`, HTTP reason phrases and digit codes, and shows readable String errors. Page-level retry tests added on 8 screens (POS search, clients, suppliers, catalog tab, day summary, stock level, team, store dashboard).

**Files changed:** `keevo/app/lib/core/widget/app_error_widget.dart`, `keevo/app/lib/features/dashboard/presentation/page/store_dashboard_page.dart`, tests in `test/core/widget/`, `test/features/{pos,contact,catalog,dashboard,team}/…`, `keevo/app/pubspec.yaml` 1.1.47+46.

**Review:** 36 findings — patched: 2 high (one root cause), 4 medium (2 root causes), 8 low; rejected 22 (reasons in the triage log); deferred 0.

**Follow-up review recommended:** true by the rule (a high was patched) — risk: the store-dashboard retry fix; it is covered by a widget test through the real provider chain, so a further pass is optional.

**Verification:** `flutter analyze` 0 error / 0 warning (626 infos). Lots: test/core 497 ✓, pos 247 ✓, contact 39 ✓, catalog 151 ✓, dashboard 95 ✓, team 123 ✓, audit 87 ✓, export 138 ✓, help 9 ✓, profitability 87 ✓, reports 44 ✓, settings 50 ✓, sync_indicator 63 ✓, inventory 189 ✓, notifications 16 ✓, stores 45 ✓.

**Residual risks:** none new; error state in the day-summary sheet still not checked on a device.
