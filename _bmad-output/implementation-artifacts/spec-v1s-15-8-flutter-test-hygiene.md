---
title: 'v1s-15-8 — Flutter test hygiene (red tests, mocks, missing widget/provider tests)'
type: 'chore'
created: '2026-10-12'
status: 'done'
baseline_revision: '1e8eed12c0f0734b7902a3e0f82e4beb9fa08a46'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-15-context.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Weekly dashboard chart drops the January part of a week that starts in December, and slot Mondays shift across DST.
    evidence: |-
      SQL groups by strftime('%Y-%W', day) while slots use '<Monday year>-<sqliteWeekW(Monday)>'; Monday 2025-12-29 -> key 2025-52, sale on 2026-01-02 -> 2026-00 (no slot). Pre-existing; routed to v1s-16-19-dashboard-weekly-buckets.
    location: >-
      keevo/app/lib/features/dashboard/data/datasource/local_dashboard_datasource.dart getWeeklyCA / getStoreWeeklyCA
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Story 15.8 carries 10 deferred Flutter test items (red tests, hand-rolled SyncService fakes, missing OWNER logout / draft-failure / empty-cart / EMPLOYEE-summary / route-uniqueness / ResetPassword tests). On top, `run-tests-chunked.sh app` is not fully green (the `test/widget_test.dart` batch "fails" with "No tests were found"), and some tests rely on the real clock. Writing the missing tests surfaced two real app bugs: CheckoutPage lets an empty cart be submitted, and ResetPasswordPage shows "Mot de passe réinitialisé" and navigates to login even when the reset failed (the notifier swallows the error via `AsyncValue.guard`, the page's `on AuthException` catch is dead code).

**Approach:** Verify each item against current code; close already-covered ones with proof (item 8). Add the missing tests, replace hand-rolled fakes with mocktail, dispose containers, fix the two bugs the new tests expose (minimal lib changes), replace real-clock waits with an injected clock, and give `widget_test.dart` a real smoke test so every app batch passes with 0 failures.

## Boundaries & Constraints

**Always:** Tests only through `run-tests-chunked.sh` / targeted `flutter test --concurrency=4 <files>`, one batch at a time. No hardcoded colours outside app_theme.dart (no_hardcoded_colors_test). Lib changes stay minimal and behaviour-preserving except the two bug fixes. Bump keevo/app/pubspec.yaml 1.1.29+28 → 1.1.30+29. Every decision recorded below and next to the epic item.

**Never:** No full `flutter test` run, no build_runner unless a generated provider changes (none planned), no refactors owned by 15.10 (status enums, routeForRole, DayCloseButtonState rename). No deferred-work.md entries.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Empty cart checkout | CheckoutPage, cart empty, Espèces tapped | Label "Valider la vente", CTA InkWell.onTap == null | submit never reachable |
| Mixed cart | 1 DRAFT + 1 ACTIVE item | Label "Vente brouillon", "Valider la vente" absent | — |
| Draft apply fails | applyInitialStockEntries throws | RecordSaleError, recordSale never called, cart and initial entries kept | caught by notifier |
| Draft promote fails | real Drift DB, promoteToActive throws | RecordSaleError, no stock level/movement, product stays DRAFT | transaction rollback |
| Reset with bad code | repo throws AuthException(INVALID_OR_EXPIRED_CODE) | provider AsyncError; page shows "Code invalide ou expiré", stays on page, no success SnackBar | — |
| Reset success | repo completes | provider AsyncData; page SnackBar + /auth/login | — |
| Week key parity | every day 2024-2035 | `sqliteWeekW(day)` == SQLite `strftime('%W', day)` | — |
| EMPLOYEE summary | DaySummaryBottomSheet(employeeId: 'emp-1') | todaySummaryProvider requested only with 'emp-1', never null; provider forwards employeeId to repository | — |
| OWNER logout | SettingsPage role OWNER, FCM token present | remote + local token deleted, lands on /auth/login | — |

</intent-contract>

## Code Map

- `keevo/app/lib/core/router/app_router.dart:258` -- top-level `appRouter` (GoRoute + one ShellRoute); test walks `appRouter.configuration.routes`.
- `keevo/app/test/features/catalog/data/datasource/local_product_datasource_test.dart:24-79` -- `_NoOpSyncService`/`_RecordingSyncService`/`_ThrowingSyncService` hand-rolled; `LocalProductDataSource` only calls `queueOperation` (lib:368).
- `keevo/app/test/features/catalog/presentation/provider/product_provider_test.dart:34` -- `_NoOpSyncService` hand-rolled.
- `keevo/app/test/features/settings/presentation/page/settings_page_test.dart:72,210` -- `_buildPage(role:)` default EMPLOYEE; FCM logout group only EMPLOYEE.
- `keevo/app/test/features/pos/presentation/page/checkout_page_test.dart:181-203` -- draft label tests; `_item(productStatus:)` helper.
- `keevo/app/lib/features/pos/presentation/page/checkout_page.dart:62` -- `submitDisabled` ignores empty cart (bug).
- `keevo/app/test/features/pos/presentation/provider/record_sale_notifier_test.dart:72-99,525-566` -- `createContainer` never disposed; real-DB rollback test pattern to reuse for promote failure.
- `keevo/app/lib/features/pos/presentation/provider/record_sale_notifier.dart:120-140` -- draft transaction (apply → promote → record).
- `keevo/app/lib/features/pos/presentation/provider/day_closure_providers.dart:107` -- `todaySummaryProvider` family forwards `employeeId` to `DayClosureRepository.computeTodaySummary`.
- `keevo/app/test/features/pos/presentation/widget/day_summary_bottom_sheet_test.dart` -- only `employeeId: null` covered. ReportsPage EMPLOYEE path already covered by `test/features/reports/presentation/page/reports_page_test.dart:21-100`.
- `keevo/app/lib/features/auth/presentation/provider/auth_provider.dart:532` -- `ResetPassword` (`FutureOr<void> build()`), state via `AsyncValue.guard`.
- `keevo/app/lib/features/auth/presentation/page/reset_password_page.dart:73-96` -- `_submit` relies on a throw that never happens (bug).
- `keevo/app/lib/core/sync/sync_event_logger.dart:14,44` -- `DateTime.now()` not injectable; `test/core/sync/sync_event_entry_test.dart:74` real 10 ms sleep.
- `keevo/app/test/widget_test.dart` -- empty `main()` → batch exits non-zero ("No tests were found").
- Item 8 evidence: `test/core/sync/offline_gate_banner_test.dart:100` expects token colour (fixed by v1s-16-3, 8bf311d); `test/features/catalog/provider/category_provider_test.dart` green in the catalog batch (148 passed, baseline 2026-10-12).

## Tasks & Acceptance

**Execution:**
- `keevo/app/test/core/router/app_router_routes_test.dart` -- new: walk `appRouter.configuration.routes` recursively (GoRoute path joined with parent, ShellRoute transparent), assert full paths unique and non-empty -- item 1.
- `local_product_datasource_test.dart`, `product_provider_test.dart` -- replace hand-rolled fakes with `class _MockSyncService extends Mock implements SyncService`, stub `queueOperation` (recording via `verify`/captured args, throwing via `thenThrow`) -- item 2.
- `settings_page_test.dart` -- OWNER variant of the FCM-cleanup logout test -- item 3.
- `checkout_page.dart` -- `submitDisabled` also true when `cart.isEmpty`; `checkout_page_test.dart` -- mixed DRAFT+ACTIVE label test, empty-cart test (label + disabled even after Espèces) -- items 4, 7.
- `record_sale_notifier_test.dart` -- `addTearDown(container.dispose)` inside `createContainer`; tests: apply throws, promote throws (real Drift DB, rollback) -- items 5, 6.
- `test/features/pos/presentation/provider/today_summary_provider_test.dart` -- new: provider forwards `employeeId` ('emp-1' and null are distinct calls) to mocked repository; `day_summary_bottom_sheet_test.dart` -- EMPLOYEE sheet requests only 'emp-1' -- item 9.
- `reset_password_page.dart` -- after `reset()`, read `resetPasswordProvider`; on error show `_computeErrorMessage` (AuthException code, else generic) and stay; remove dead catch. New `test/features/auth/presentation/provider/reset_password_provider_test.dart` (initial AsyncData(null), success AsyncData, failure AsyncError<AuthException>) + page tests for both paths -- item 10.
- `sync_event_logger.dart` -- optional `DateTime Function()? now` constructor param (default `DateTime.now`); `sync_event_entry_test.dart` uses a stepping fake clock instead of `Future.delayed` -- clock hygiene AC.
- `test/core/sync/days_since_last_sync_provider_test.dart` -- day-boundary test: testWidgets fake timer + `syncGateClockProvider` override instead of a real 150 ms Timer raced by a real 400 ms delay.
- `test/core/sync/riverpod_sync_trigger_dispatcher_test.dart` -- await a Completer completed by the push instead of a real 50 ms delay.
- `test/features/pos/presentation/provider/day_closure_state_provider_test.dart` -- `closedAt = now` (was now−1 s: yesterday during the first second after midnight).
- `test/features/dashboard/data/datasource/local_date_bucketing_test.dart` -- `today` taken per test in setUp (was once at file load); new parity test `sqliteWeekW` vs SQLite `strftime('%W')` for every day 2024-2035.
- `lib/features/dashboard/data/datasource/local_dashboard_datasource.dart` -- `sqliteWeekW` (@visibleForTesting): SQLite formula `(yday0 + 7 - wdMon0) ~/ 7` on UTC calendar dates (old formula wrong for Mondays when 1 Jan is a Monday — 2029 — and DST-sensitive).
- `local_product_datasource_test.dart` NFR5 test -- wall-clock bound 500 ms → 5 000 ms pathological-regression guard (NFR5 is a device budget).
- `test/features/sync_indicator/sync_indicator_rejection_test.dart` -- `addTearDown(container.dispose)` safety net.
- `test/features/pos/presentation/page/checkout_page_test.dart` -- reset all `_CapturingRecordSale` statics at test start.
- `reset_password_page.dart` OTP field -- `FilteringTextInputFormatter.digitsOnly` before `maxLength: 6` (a pasted "123 456" was cut to "123 45", which the page test exposed).
- Unused imports removed in `test/core/storage/secure_storage_provider_test.dart`, `test/features/profitability/domain/model/product_profitability_model_test.dart`.
- `keevo/app/test/widget_test.dart` -- real smoke test: `AppTheme.light()`/`dark()` build a MaterialApp without exception -- all-batches-green AC.
- `keevo/app/pubspec.yaml` -- 1.1.30+29.
- `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md` -- tick the 10 items with resolution notes.

**Acceptance Criteria:**
- Given the 10 Story 15.8 items, when the story is done, then each is ticked in the epic with its resolution or the proof that it was already covered.
- Given `run-tests-chunked.sh app` run batch by batch (test/core, test/widget_test.dart, each test/features/*), when it completes, then every batch reports OK with 0 failures.
- Given the touched tests, when they run, then none waits on real time or depends on another test having run first.
- Given `flutter analyze`, when run, then no new error or warning.

## Spec Change Log

## Review Triage Log

### 2026-10-12 — Review pass
- verdicts: 35 findings — high 0, medium 5, low 17, false 8, maybe-false 5
- findings:
  - `[medium]` `[defer]` (blind) Weekly chart drops Jan 1+ sales of a week starting in December (`%Y-%W` vs Monday-year key) — pre-existing, not caused by this diff; routed → v1s-16-19-dashboard-weekly-buckets.
  - `[low]` `[reject]` (blind) NFR5 5000 ms guard would not catch a per-row-query regression on in-memory SQLite — true but NFR5 is a device budget (Design Notes); a query-count harness is new complexity for a test-only concern.
  - `[low]` `[patch]` (blind) Route uniqueness misses `/x/:id` vs `/x/:pid` — paths now normalised (`:param` → placeholder, no trailing slash) before comparison, self-check extended.
  - `[low]` `[reject]` (blind) Literal-before-param sibling order (`/clients/new` before `/clients/:id`) not asserted — current table is correct; an order rule adds a parser for a hypothetical regression.
  - `[medium]` `[patch]` (blind) Empty cart blocked only at the CTA; `RecordSaleNotifier.submit` records a zero-total sale if the cart empties while `_submit` awaits — guard + `EMPTY_CART` error in the notifier, unit test.
  - `[low]` `[patch]` (blind) Midnight comments overstate the fix (day_closure_state, local_date_bucketing) — comments reworded: the window shrinks, lib still reads its own now().
  - `[low]` `[patch]` (blind) Completer completed twice would throw in the dispatcher test — `if (!done.isCompleted)`.
  - `[low]` `[reject]` (blind) Reset page lacks loading/retry tests and the provider test lacks the AsyncLoading transition — pre-existing behaviour untouched by the diff; low value.
  - `[low]` `[reject]` (blind) Non-AuthException maps to the generic message through `_computeErrorMessage('')` — behaviour is correct and tested ("an unexpected error shows the generic message").
  - `[low]` `[patch]` (blind) widget_test / today_summary test names overstate what is tested — renamed (theme smoke test; "distinct requests").
  - `[false]` `[reject]` (blind) Double dispose relies on undocumented safety — Riverpod `ProviderContainer.dispose` returns early when already disposed; both files pass with in-body + teardown dispose.
  - `[false]` `[reject]` (blind) `_CapturingRecordSale` statics can leak to other tests — only that one test reads them, and it resets all three first.
  - `[low]` `[reject]` (blind) InkWell ancestor finder could match several — same pattern as the existing CTA tests; the CTA has a single InkWell ancestor.
  - `[low]` `[reject]` (blind) No deterministic pruning test for SyncEventLogger — pruning untouched; count assertion unaffected by timestamps.
  - `[maybe-false]` `[reject]` (verification-gap) NFR5 bound loosened — deliberate spec decision; would only be low (device budget measured elsewhere).
  - `[low]` `[defer]` (verification-gap) DST half of `sqliteWeekW` not exercised on CI (UTC runners) — target market has no DST; routed with the caller-side DST item → v1s-16-19.
  - `[medium]` `[defer]` (verification-gap other) Same year-crossing weekly bucket bug — grouped with the first row → v1s-16-19.
  - `[medium]` `[patch]` (edge) Cart emptied while `_submit` awaits → zero-total sale — grouped with the notifier guard above.
  - `[medium]` `[defer]` (edge) Year-straddling week keys — grouped → v1s-16-19.
  - `[low]` `[defer]` (edge) Slot Mondays built by Duration subtraction land on Sunday 23:00 after DST — pre-existing → v1s-16-19.
  - `[maybe-false]` `[reject]` (edge) Page popped during reset → disposed notifier sets state — the old code had the same await with only `on AuthException`, so nothing new; `if (!mounted) return` guards the page side; if real it is low.
  - `[low]` `[patch]` (edge) Param-name-only duplicate routes — grouped with the normalisation patch.
  - `[low]` `[patch]` (edge) `appRouter` read in `main()` at suite load — now read in `setUpAll`.
  - `[low]` `[patch]` (edge) Completer double complete — grouped with the dispatcher patch.
  - `[low]` `[reject]` (edge) Dead disabled CTA on an emptied cart without explanation — the cart cannot be emptied from the checkout page itself; UX polish out of scope.
  - `[maybe-false]` `[patch]` (edge claim) closedAt = now still flips at exact midnight — comment reworded (residual sub-ms window accepted in Design Notes).
  - `[maybe-false]` `[patch]` (edge claim) per-test today still mismatches across midnight — comment reworded.
  - `[low]` `[patch]` (edge claim) sqliteWeekW doc claims DST safety while callers are DST-sensitive — doc reworded; caller fix → v1s-16-19.
  - `[maybe-false]` `[reject]` (edge claim) NFR5 test still wall-clock — 10x margin over the budget; accepted in Design Notes.
  - `[false]` `[reject]` (intent) Item 10 not pinned at `auth_provider.dart` type — the declared `FutureOr<void>` is enforced at compile time by the page and generated provider; the regression the item guards is the runtime state, which the new tests pin.
  - `[false]` `[reject]` (intent) Item 9 sheet test overrides the provider wholesale — forwarding is covered by today_summary_provider_test; the sheet test pins the family argument, which is the sheet's own job.
  - `[false]` `[reject]` (intent) Item 7 changed lib instead of asserting current behaviour — current behaviour was the bug; Design Notes decision (c).
  - `[false]` `[reject]` (intent) Item 8 has no surface in the diff — closed with proof in the spec and epic, as the intent allows.
  - `[false]` `[reject]` (intent) Batches-green AC not evidenced by the diff — evidenced by the batch runs recorded under Auto Run Result.
  - `[false]` `[reject]` (intent) Unrequested changes (unused imports, statics reset, teardown nets) — they serve the order-dependence AC.

## Design Notes

Implementation status: every Execution task above was applied in the working tree by the orchestrating agent while investigating (baseline_revision + uncommitted diff). The implementer verifies each task against the tree and completes any gap; it must not redo or revert applied work.

Clock audit (read-only subagent, 2026-10-12) — accepted residual risk, no change: millisecond midnight windows where a test computes "today" and lib recomputes `DateTime.now()` a moment later (`local_dashboard_datasource_test.dart`, `sale_repository_offline_first_test.dart:322`, `offline_gate_banner_test.dart:166`); removing them needs a clock injected into LocalDashboardDatasource / repositories, a lib refactor out of this story's scope and not worth a key for a sub-millisecond window. No order dependence found (all statics reset, all containers disposed).

Decisions: (a) item 8 closed without change (proof above). (b) Item 9: day close is OWNER-only and both callers pass `employeeId: null`; the EMPLOYEE scope is exercised by ReportsPage (already tested) — tests added at provider level and on the sheet widget, which is the surface named by the item. (c) Fixing the two bugs is in scope: the items ask for tests "that are meaningful", and a test asserting today's behaviour would encode the bug.

## Verification

**Commands:**
- `flutter test --concurrency=4 <touched test files>` -- all pass
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh app` (in groups of batches) -- every batch OK
- `flutter analyze` -- no new error/warning

## Auto Run Result

Status: done

**Summary:** the 10 Story 15.8 items are closed (9 by tests, item 8 already resolved by v1s-16-3 — proof recorded). Every `run-tests-chunked.sh app` batch is green (the only red batch at baseline, `test/widget_test.dart`, had an empty `main()`). New tests exposed and fixed three app bugs: an empty cart could be sold (CTA + `RecordSaleNotifier` guard `EMPTY_CART`), a refused password-reset code showed the success message and left the page, and the OTP field cut a pasted "123 456" to "123 45". Real-clock waits were replaced by injected clocks / fake timers / completers, and the dashboard `sqliteWeekW` now matches SQLite `%W` for every day 2024-2035 (it was wrong for Sundays/Mondays in years starting on a Monday).

**Files changed (keevo/app):**
- `lib/features/pos/presentation/page/checkout_page.dart` -- CTA disabled on an empty cart.
- `lib/features/pos/presentation/provider/record_sale_notifier.dart` -- `submit()` refuses an empty cart (review patch).
- `lib/features/auth/presentation/page/reset_password_page.dart` -- reads the provider outcome instead of a dead catch; digits-only OTP formatter.
- `lib/core/sync/sync_event_logger.dart` -- optional injected `now`.
- `lib/features/dashboard/data/datasource/local_dashboard_datasource.dart` -- `sqliteWeekW` (@visibleForTesting) on SQLite's formula, UTC day of year.
- `pubspec.yaml` -- 1.1.30+29.
- Tests: new `test/core/router/app_router_routes_test.dart`, `test/features/auth/presentation/provider/reset_password_provider_test.dart`, `test/features/pos/presentation/provider/today_summary_provider_test.dart`; rewritten `test/widget_test.dart`; updated catalog datasource/provider (mocktail), settings (OWNER logout), checkout (mixed/empty cart, statics reset), record_sale_notifier (dispose, draft failures, empty cart), day_summary_bottom_sheet (EMPLOYEE), reset_password_page (error/success), days_since_last_sync (fake time), riverpod_sync_trigger_dispatcher (completer), sync_event_entry (injected clock), day_closure_state, local_date_bucketing (per-test today + `%W` parity), sync_indicator_rejection (teardown net); unused imports removed in 2 tests.

**Review:** 1 pass, 4 layers, 35 findings — patched 9 rows / 6 entries (1 medium: empty-cart notifier guard; low: route param normalisation + setUpAll, completer guard, comment corrections ×3, test renames); deferred 1 entry (5 rows: weekly buckets across New Year / DST, pre-existing) → `v1s-16-19-dashboard-weekly-buckets`; rejected 20 with reasons in the triage log.

**Follow-up review recommended:** false — one medium and no high patched on a first pass.

**Verification:** `run-tests-chunked.sh app`, batch by batch after the review patches: test/core 446, widget_test 2, account 20, audit 14, auth 96, catalog 148, contact 37, dashboard 86, feedback 14, identity 11, inventory 180, notifications 16, onboarding 26, pos 228, profitability 45, reports 42, settings 44, stores 45, sync_indicator 62, team 89 — all OK, 0 failure (1651 tests). `flutter analyze`: 612 issues (615 before), 0 error, 6 warnings (9 before, all pre-existing lib warnings; the 3 test warnings removed).

**Residual risks:** sub-millisecond midnight windows remain where lib reads `DateTime.now()` itself (accepted, Design Notes); NFR5 search latency is now only guarded at 5 s in unit tests (device budget); weekly chart year-crossing bug open in v1s-16-19.

