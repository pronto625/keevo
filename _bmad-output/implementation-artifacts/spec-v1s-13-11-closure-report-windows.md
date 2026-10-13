---
title: 'v1s-13-11 — Day-closure and weekly-report windows (PO decisions), Clock in CloseDayService, post-closure cancel/correct warning'
type: 'bugfix'
created: '2026-10-11'
status: 'done'
baseline_revision: 'ecb40a73f1abfc01be72a09e275b2b31e1a88379'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-13-context.md'
warnings: [oversized]
deferred:
  - summary: >-
      A sale stamped before a report's receivedUntil but committed after the report queries is excluded from that report and the next one.
    evidence: |-
      created_at is stamped in the inserting transaction; the next window's lateFrom = previous receivedUntil. Millisecond window at generation time; applies to closures and weekly reports. Routed to v1s-13-13-residual-check-then-act-races.
    location: >-
      keevo/backend/src/main/java/com/keevo/reporting/report/application/service/WeeklyReportWindowResolver.java
    severity: low
---

<intent-contract>

## Intent

**Problem:** Five routed items on closure/report windows: CloseDayService reads the system time directly (untestable); an invalid `eodReportTime` could silently fall back to 20:00; sales made after the evening closure were feared excluded; cancel/correct of an already closed/reported sale is silent; the weekly report generated Sunday 20:00 (window Mon 00:00 → Sun 23:59:59) misses Sunday 20:00–23:59 sales, which no later report covers (next week starts Monday 00:00), and also misses offline sales synced after it.

**Approach:** Record the PO decisions (below) in the PRD and the epic, and enforce them: inject `Clock` into CloseDayService; keep the 422 validation already in place for report times and prove it for both fields; keep the "closure covers everything since the previous closure" rule (UI-3 `ClosureWindow`) and prove the 20:01–23:59 case; make the weekly window chain on the previous weekly report with the same occurrence/arrival rule as closures; allow cancel/correct after closure (FR90) but warn the owner in the app that the sent report will not change.

## Boundaries & Constraints

**Always:** PO decisions D1–D3 (Design Notes) written into `prd.md` under FR51/FR52/FR90; weekly window = `ClosureWindow` semantics (occurredAt ≤ end ∧ createdAt ≤ receivedUntil ∧ (occurredAt ≥ start ∨ createdAt > lateFrom)); report instants truncated to microseconds; no new Flyway migration (chaining derives from existing `reports.created_at` + `report_date`); app change ⇒ pubspec 1.1.18+17 → 1.1.19+18.

**Never:** blocking cancel/correct after closure (no `SALE_DAY_CLOSED`); regenerating or editing a closure or an already persisted report; changing the EOD closure cut-off, the one-closure-per-day rule or ClosureWindow; touching deferred-work.md; changing the weekly report's day/time configuration semantics or WoW delta.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Sunday trigger (config Sun 20:00) | tick Sun S 20:00, prev WEEKLY report for S-7 created at S-7 20:00:00.5 | window start = prev.createdAt+1µs, end = receivedUntil = now (S 20:00), lateFrom = prev.createdAt; report createdAt = now | none |
| Sunday 21:00 sale | sale occurred S 21:00 | not in week S report; in week S+7 report | none |
| Late offline sale | occurred Sat S-1, synced Mon S+1 | in week S+7 report (createdAt > lateFrom) | none |
| Non-Sunday trigger (config Sat) | tick Sat, S = previous Sunday, prev report for S-7 created Sat S-6 | end = S 23:59:59.999999, receivedUntil = now, start = (S-7 end-of-day)+1µs, lateFrom = prev.createdAt | none |
| No previous weekly report | first report / previous week skipped | start = Monday S-6 00:00 WAT, lateFrom = NEVER | none |
| Manual trigger | owner POST /reports/weekly/trigger | same resolver as scheduler | none |
| Invalid time | PUT /tenant/report-preferences with eodReportTime or weeklyReportTime "25:99:00" | 422 VALIDATION_ERROR, nothing stored | 422 |
| Evening sale | prev closure D 20:00, sale D 21:30, closure D+1 20:00 | covered by closure D+1 | none |
| Clock | CloseDayService with fixed Clock | closedAt/receivedAt/7-day gate use the injected clock | none |
| Cancel/correct closed sale (app) | OWNER opens COMPLETED sale with occurredAt ≤ store's last closure closedAt | warning text shown above Annuler/Corriger; actions still enabled | none |

</intent-contract>

## Code Map

Backend (`keevo/backend/src/main/java/com/keevo/`):
- `commerce/sale/application/service/CloseDayService.java:69` -- `Instant.now()` → `Instant.now(clock)`; constructor gains `Clock` (bean `shared/infrastructure/config/ClockConfig.java`, UTC).
- `commerce/sale/domain/model/ClosureWindow.java` -- read-only: rule + `NEVER`.
- `commerce/sale/application/service/DayClosureAutoScheduler.java:156` / `WeeklyReportScheduler.java` `parseTime` -- read-only fallback (DB column is `TIME`, writes validated); comment only.
- `identity/onboarding/adapter/in/web/UpdateReportPreferencesRequestDto.java` -- read-only: `@Pattern` HH:mm:ss on both times (since fbe7159), `GlobalExceptionHandler.handleValidation` → 422.
- `reporting/report/domain/port/in/GenerateWeeklyReportUseCase.java` -- `WeeklyReportCommand` gains `lateFrom`, `receivedUntil` (nullable → NEVER); keep a 6-arg convenience ctor.
- NEW `reporting/report/application/service/WeeklyReportWindowResolver.java` -- `@Component`; `Window resolve(UUID storeId, String tenantId, LocalDate weekEndSunday, Instant now)`; prev = `reportRepository.findByDateAndTenant(S-7, tenantId, WEEKLY)` filtered by store (earliest createdAt); prevEnd = min(prev.createdAt, endOfDay(S-7)); start = prevEnd+1µs else Monday 00:00 WAT; end = min(now, endOfDay(S)); lateFrom = prev.createdAt else null; receivedUntil = now. endOfDay = next day 00:00 WAT − 1µs. `now` truncated to micros.
- `reporting/report/application/service/WeeklyReportScheduler.java:124-140` -- per store: resolver → command.
- `reporting/report/adapter/in/rest/ReportController.java:168-185` -- `triggerWeekly` uses the resolver per store.
- `reporting/report/application/service/WeeklyReportGenerator.java:60-75,79-105` -- pass lateFrom/receivedUntil via the 10-arg `GenerateReportCommand`; `collectData` passes a `ClosureWindow` to the builder; override `persistReport` so `createdAt` = `receivedUntil` (when non-null).
- `reporting/report/application/service/WeeklyReportBuilder.java` -- `build(storeId, ClosureWindow, storeName, weekEndDate, tenantId, isAutomatic)`; the 6 `occurred_at >= :start AND occurred_at <= :end` predicates → ClosureWindow predicate (reuse pattern of `EndOfDayReportBuilder.java:35-47`).
- `reporting/report/domain/model/EndOfDayReport.java` -- read-only: public 16-arg ctor used to set createdAt.

App (`keevo/app/lib/`):
- `features/pos/presentation/provider/day_closure_providers.dart` -- new `lastClosureAtProvider = FutureProvider.family<DateTime?, String>` (storeId) via `localDayClosureDataSourceProvider.getLastClosure`.
- `features/pos/presentation/page/sale_detail_page.dart:161` -- when owner + COMPLETED and `!sale.occurredAt.isAfter(lastClosedAt)`, show a warning row (key `closed-day-warning`): « Cette vente fait partie d'une journée déjà clôturée : le rapport déjà envoyé ne sera pas modifié. »
- `pubspec.yaml` -- 1.1.19+18.

Docs: `_bmad-output/planning-artifacts/prd.md:399-400,467` -- decision notes.

## Tasks & Acceptance

**Execution:**
- `CloseDayService.java` + `CloseDayServiceTest` -- Clock injection; fixed-clock tests (7-day gate and closedAt from clock; evening-sale coverage via `window.covers`).
- `WeeklyReportWindowResolver.java` + NEW `WeeklyReportWindowResolverTest` -- matrix rows 1, 4, 5 and microsecond truncation.
- `GenerateWeeklyReportUseCase.java`, `WeeklyReportScheduler.java`, `ReportController.java`, `WeeklyReportGenerator.java`, `WeeklyReportBuilder.java` -- wire the window; update `WeeklyReportSchedulerTest`, `WeeklyReportGeneratorTest`, `WeeklyReportBuilderTest`, `ReportControllerTest`/`WeeklyReportControllerTest` as needed; generator test asserts persisted createdAt = receivedUntil and builder receives lateFrom.
- `WeeklyReportWindowResolverTest` (continued) -- no-gap property: resolve two consecutive weeks (Sunday 20:00 config, then a Saturday config change, and a legacy prev report), build `ClosureWindow`s and check with `ClosureWindow.covers` that sample sales (Sun 19:59, Sun 20:30, Sun 23:59, late-synced Sat sale, Mon 00:00) are each covered by exactly one window. `WeeklyReportBuilderTest` -- every aggregation query carries the ClosureWindow predicate and the `lateFrom`/`until` parameters (mocked EntityManager, existing pattern; no DB test exists in reporting).
- `ReportPreferencesControllerTest` -- add invalid `weeklyReportTime` → 422.
- `day_closure_providers.dart`, `sale_detail_page.dart`, `sale_detail_page_test.dart`, `pubspec.yaml` -- warning + widget tests (shown when closed, hidden otherwise).
- `prd.md` -- D1–D3 notes; epic brief items ticked at finalisation.

**Acceptance Criteria:**
- Given any matrix row, when the scenario runs, then the behavior matches and a test proves it.
- Given two consecutive weekly reports for a store, when the second is generated, then every COMPLETED sale is counted in exactly one of them (no gap, no overlap) regardless of trigger day/time.
- Given the full backend suite, then failures/errors equal the baseline (7 failures + 29 errors, 10 listed classes); `flutter analyze` adds no issue; `flutter test` only the known `offline_gate_banner_test` failure.

## Spec Change Log

## Review Triage Log

### 2026-10-11 — Review pass
- verdicts: 33 findings — high 0, medium 6, low 20, false 7, maybe-false 0
- findings:
  - `[false]` `[reject]` (intent) item 4 enforced only in the app, no backend guard on ValidateSaleService/CorrectSaleService — D3 deliberately chose "warn" (one of the intent's three options) and recorded the reason in prd.md FR90; a guard would contradict that decision.
  - `[low]` `[reject]` (intent) app warning uses occurredAt only, not the arrival rule — rare (offline sale made before a closure and synced after it); the app's day_closures table has no receivedAt, so a fix needs a schema change.
  - `[low]` `[reject]` (intent) warning ignores already-sent weekly reports — weekly reports are generated after the day's closure in practice, so a sale in a sent weekly report is already in a closed day; the warning text covers both.
  - `[false]` `[reject]` (intent) item 2 tested on /tenant/report-preferences instead of "PUT /tenant/preferences" — TenantPreferencesController exposes only GET /preferences and PUT /report-preferences; it is the only write path.
  - `[low]` `[reject]` (intent) item 3 test checks ClosureWindow.covers with a mocked repository — the SQL of findForClosure is unchanged pre-existing code mirroring covers(); the decision (D1) needed no code.
  - `[low]` `[reject]` (intent) weekly SQL not run against a DB; triggerWeekly uses Instant.now() — predicates are now pinned to literal strings (see VG patch); a Clock in the controller was not requested.
  - `[false]` `[reject]` (intent) epic items not ticked — ticking happens at finalisation (done in this run).
  - `[false]` `[reject]` (intent) refonte absorption surface — the refonte modules are not on this V1 branch; the brief only notes where the refonte must re-apply it.
  - `[low]` `[reject]` (edge) skipped week falls back to Monday 00:00 — fallback is matrix row 5 of the intent contract; its accidental cause (one store failing then skipped by the tenant-wide guard) is patched below; remaining cases (reports disabled, scheduler down all evening) are deliberate or exceptional.
  - `[medium]` `[patch]` (edge) tenant-wide "already generated" guard skips a store whose generation failed — fixed: guard per store in WeeklyReportScheduler + test `alreadyGeneratedForOneStoreOnly_generatesTheMissingStoreOnly`.
  - `[false]` `[reject]` (edge) manual trigger after the scheduler double-counts — AbstractReportGenerator.generateReport's idempotency guard (findByKey store/date/WEEKLY/actor null) returns the existing report; no second report is persisted.
  - `[low]` `[reject]` (edge) legacy non-Sunday report ended 23:59:59.000, new start is 23:59:59.999999+1µs — loses at most sales in the last sub-second of one transition week.
  - `[medium]` `[patch]` (edge) lastClosureAtProvider never refreshed — fixed: derived from lastClosureProvider (invalidated after pull) and the close-day notifier now invalidates lastClosureProvider.
  - `[low]` `[reject]` (edge) warning shown for a late-synced sale — same as intent row 2.
  - `[low]` `[reject]` (edge) "exactly one report" claim vs skipped weeks/re-triggers — re-triggers are idempotent (see false row); skipped weeks as edge row 1.
  - `[low]` `[reject]` (blind) skipping one week breaks the chain — same as edge row 1.
  - `[false]` `[reject]` (blind) repeated manual triggers count sales twice — idempotency guard, as above.
  - `[low]` `[reject]` (blind) in-flight sale transaction (created_at stamped before commit) can miss both reports — millisecond window shared with day closures since UI-3; a fix needs a DB/commit-ordered stamp, disproportionate here.
  - `[low]` `[reject]` (blind) report label still "Semaine du lundi" — Sunday-config window starts the previous Sunday evening; the label stays accurate to within hours and D2 is documented in the PRD.
  - `[low]` `[reject]` (blind) triggerWeekly/scheduler not using the ClockConfig bean — out of the requested Clock item (CloseDayService); test flakiness only across Sunday midnight WAT.
  - `[low]` `[reject]` (blind) persistReport override duplicates the base method — fix would add a factory parameter; no current divergence.
  - `[low]` `[reject]` (blind) 6-arg WeeklyReportCommand ctor is a trap — used by WeeklyReportGeneratorTest; null receivedUntil falls back to the base persist path (legacy behaviour).
  - `[medium]` `[patch]` (blind) app provider never refreshed — same root cause as edge row; fixed there.
  - `[low]` `[reject]` (blind) app warning ignores the arrival rule / unsynced closures — same as intent row 2.
  - `[low]` `[reject]` (blind) warning not inside the confirmation dialogs — the row sits directly above the Annuler/Corriger buttons, seen before acting.
  - `[low]` `[reject]` (blind) evening-sale test's previousWindow is hand-built — the "next closure covers it" half is the decision under test; ClosureWindow exclusivity is covered by its own tests.
  - `[low]` `[patch]` (blind) epic-13-context 13.11 bullet still says "needs PO decisions" — fixed: points to D1–D3 in prd.md and the spec.
  - `[low]` `[reject]` (blind) spec matrix lacks skipped-week/duplicate/new-store rows — fix edits this build's spec.
  - `[medium]` `[patch]` (verification-gap) weekly predicate asserted against its own constant — fixed: `windowPredicates_matchTheClosureWindowRule` compares WINDOW/WINDOW_S to literal SQL.
  - `[medium]` `[patch]` (verification-gap) real lastClosureAtProvider never runs — fixed: ProviderContainer tests in day_closure_state_provider_test.dart (value, null, refresh after invalidate).
  - `[medium]` `[patch]` (verification-gap other) warning can go stale — same root cause as edge provider row; fixed there.
  - `[low]` `[reject]` (verification-gap other) warning for a late-synced sale — same as intent row 2.
  - `[false]` `[reject]` (verification-gap other) manual weekly trigger double-counts — idempotency guard, as above.

### 2026-10-13 — Review pass (follow-up)
- verdicts: 37 findings — high 0, medium 4, low 27, false 6, maybe-false 0
- findings:
  - `[low]` `[reject]` (intent) D2 proven on `ClosureWindow.covers` + SQL strings, never on a database — the predicate constants are shared and string-pinned; a Testcontainers run of 7 weekly aggregations is disproportionate for a follow-up.
  - `[low]` `[patch]` (intent) manual trigger used `Instant.now()` instead of the Clock — `ReportController` takes the shared `Clock` bean; fixed-clock test asserts weekEnd/receivedUntil.
  - `[low]` `[reject]` (intent) D3 warning on `occurredAt` only, not the arrival rule — the warning is advisory and errs on the safe side (a late-synced sale may show it while its report is still pending); the app has no server-received time for sales.
  - `[low]` `[patch]` (intent) cache→warning path not tested end to end — grouped with the close-day refresh test below.
  - `[false]` `[reject]` (intent) weekly guard per store outside scope — required to keep the D2 chain intact (a failed store is retried).
  - `[false]` `[reject]` (intent) other stories' changes in the diff — follow-up diff = baseline→current code.
  - `[false]` `[reject]` (intent) PRD edits / pubspec bump absent — PRD already carries D1–D3; the review diff filtered `.yaml` (the story bumped it). This follow-up bumps 1.1.41+40 → 1.1.42+41.
  - `[medium]` `[patch]` (blind) manual weekly trigger had no "already generated" guard → a second report for the same week re-counted its sales — controller skips stores whose WEEKLY report for that Sunday exists (same per-store guard as the scheduler; resend endpoint for re-delivery); test.
  - `[medium]` `[patch]` (blind) one missing weekly report broke the chain (fallback Monday 00:00, lateFrom null → Sunday tail and late syncs in no report) — resolver chains on the store's latest WEEKLY report within 8 weeks; tests for a missing week and for a report beyond the look-back.
  - `[low]` `[reject]` (blind) sales received before the store's first weekly report are never counted late — first-report edge only (before any report there is no previous window to be late against); same as the first day closure.
  - `[low]` `[reject]` (blind) warning ignores the arrival rule — same as the intent row.
  - `[low]` `[reject]` (blind) warning reads the local closure cache (other device / auto-close not pulled yet) — refreshed on every sync pull and on local close; advisory only.
  - `[false]` `[reject]` (blind) pubspec not bumped — same as the intent row.
  - `[low]` `[patch]` (blind) Clock half done (controller `Instant.now`, scheduler `Clock.systemDefaultZone()`) — controller as above; `WeeklyReportScheduler` now has a single constructor taking the `Clock` bean.
  - `[low]` `[reject]` (blind) `WeeklyReportGenerator.persistReport` duplicates the base method — refactor with no defect.
  - `[low]` `[reject]` (blind) `created_at` not `updatable = false` — no code path rewrites it (the delivery save keeps the loaded value).
  - `[low]` `[reject]` (blind) three window types — cosmetic.
  - `[low]` `[reject]` (blind) predicate test checks a string — same as the intent row.
  - `[low]` `[reject]` (blind) OR predicate may defeat the `(store_id, occurred_at)` index — per-store weekly volumes are small; no measured regression.
  - `[low]` `[reject]` (blind) resolver loads the tenant's weekly reports per store (N×N) — a handful of stores per tenant; a per-store query adds port surface for no measured cost.
  - `[low]` `[reject]` (blind) 409 widget-test comments vs assertions — the bounded-wait test asserts no re-read before the timeout and a re-read after; the conflict snackbar is covered by the failing-pull test.
  - `[low]` `[patch]` (blind) `CloseDayNotifier` refresh of the last closure untested — `close_day_notifier_test.dart` "closeDay refreshes lastClosureAtProvider" (mutation-checked).
  - `[low]` `[reject]` (blind) controller depends on the concrete resolver — existing pattern in this module; no defect.
  - `[false]` `[reject]` (blind) `parseTime` fallback silent — both schedulers already `log.warn` in the catch branch.
  - `[low]` `[patch]` (verification) close-day refresh — same patch as above.
  - `[low]` `[patch]` (verification) « Réessayer » on the sale detail error state never tapped — widget test asserts the sale is re-read.
  - `[low]` `[reject]` (verification other) warning rule vs `ClosureWindow` — same as the intent row.
  - `[low]` `[reject]` (edge) late-arrival warning — same.
  - `[low]` `[reject]` (edge) closure from another device not yet pulled — same as the cache row.
  - `[low]` `[reject]` (edge) a timed-out pull keeps running and may finish after the re-read — the next sync/pull invalidation refreshes the page; a repeated action gets a fresh 409 at worst.
  - `[medium]` `[patch]` (edge) chain gap when the week S-7 has no report — grouped with the blind chain row.
  - `[low]` `[reject]` (edge) a store whose generation fails all the configured day is not retried the next days — with the look-back its sales land in the next week's report (no loss).
  - `[low]` `[defer]` (edge) sale committed after the report queries with `created_at` < `receivedUntil` is lost — millisecond window; routed to v1s-13-13-residual-check-then-act-races.
  - `[low]` `[reject]` (edge) legacy previous report bounded at 23:59:59 vs .999999 — one-time legacy transition, sub-second window.
  - `[medium]` `[patch]` (edge claim) "every sale in exactly one weekly report" false on a broken chain — grouped with the chain row.
  - `[low]` `[reject]` (edge claim) warning claim — same as the intent row.
  - `[false]` `[reject]` (edge claim) pubspec — same as the intent row.

## Design Notes

PO decisions (taken autonomously from FR51/FR52/FR90 and the UI-3 closure rule):
- **D1 — EOD window:** a closure (auto at `eodReportTime`, or manual) covers every sale since the previous closure of the store, plus late-synced ones (ClosureWindow). Sales after the closure belong to the next closure — nothing is excluded; the 23:59 cut-off is rejected (it would force a second closure the same day, against one-closure-per-day).
- **D2 — Weekly window:** the weekly report covers the sales since the previous weekly report of the store (same occurrence/arrival rule); it ends at its generation time, or at Sunday 23:59:59 when generated after the week. First report: since Monday 00:00. Chaining uses the previous report's `created_at` (= its receivedUntil), so no migration and legacy reports chain too (their Sunday 20:00 gap lands in the next week).
- **D3 — Cancel/correct after closure:** allowed (FR90 has no time limit; blocking would make the morning-after fix impossible with a 20:00 auto-close). Closures and sent reports are immutable snapshots, never regenerated; the app warns the owner before acting; the action stays audited (SALE_CANCELLED / SaleCorrectedEvent).
- Item 2 was already enforced (fbe7159 `@Pattern` on both times, 422); the scheduler fallback is now only defensive (TIME column).

## Verification

**Commands:**
- `cd keevo/backend && mvn -q test -Dtest='CloseDayServiceTest,WeeklyReport*Test,ReportControllerTest,ReportPreferencesControllerTest,DayClosureAutoSchedulerTest'` -- expected: green.
- `cd keevo/backend && mvn test` -- expected: baseline only.
- `cd keevo/app && flutter analyze && flutter test` -- expected: no new issue; only known failure.

## Auto Run Result

**Summary:** PO decisions D1–D3 recorded in prd.md (FR51/FR52/FR90) and enforced. CloseDayService takes the shared `Clock`. The EOD window keeps the UI-3 ClosureWindow rule (evening sales go to the next closure, proven by test). Report-time validation (422) was already in place (fbe7159) and is now tested for both fields; the schedulers' fallback is documented as defensive. The weekly report now chains on the store's previous weekly report with the closure occurrence/arrival rule (`WeeklyReportWindowResolver`, persisted createdAt = receivedUntil, no migration), closing the Sunday 20:00–23:59 and late-sync gaps; the scheduler's already-generated guard is per store. Cancel/correct after closure stays allowed (FR90); the app warns the owner on the sale detail page. App 1.1.19+18.

**Files changed:**
- `CloseDayService.java` -- injected Clock.
- `WeeklyReportWindowResolver.java` (new), `GenerateWeeklyReportUseCase.java`, `WeeklyReportScheduler.java`, `ReportController.java`, `WeeklyReportGenerator.java`, `WeeklyReportBuilder.java` -- chained weekly window, per-store guard.
- `DayClosureAutoScheduler.java` -- comment on defensive fallback.
- App `day_closure_providers.dart`, `sale_detail_page.dart`, `pubspec.yaml` -- closed-day warning, provider refresh.
- Tests: `CloseDayServiceTest`, `WeeklyReportWindowResolverTest` (new), `WeeklyReportBuilderTest`, `WeeklyReportGeneratorTest`, `WeeklyReportSchedulerTest`, `ReportControllerTest`, `WeeklyReportControllerTest`, `ReportPreferencesControllerTest`, app `sale_detail_page_test`, `day_closure_state_provider_test`.
- Docs: `prd.md`, `epic-13-context.md`.

**Review findings:** 33 (0 high, 6 medium, 20 low, 7 false). Patched: 4 medium entries (per-store weekly guard; stale closed-day provider; weekly predicate pinned to literal SQL; real provider test) and 1 low (epic context wording). Deferred: none. Rejected: see triage log (warn-not-block is decision D3; skipped-week fallback is contract row 5; manual re-trigger is idempotent; late-synced-sale warning and sub-second/in-flight edges rare and costly to fix).

**Follow-up review recommended:** true — 4 medium entries patched (counts: medium 4, low 1). Unverified risk: the review-time changes (per-store guard in `WeeklyReportScheduler`, `lastClosureAtProvider` derived from `lastClosureProvider` + new invalidation in the close-day notifier) were not reviewed by an independent pass.

**Verification:** backend full `mvn test` after patches = 2170 tests, 7 failures + 29 errors, exactly the 10 baseline classes; targeted classes green. `flutter analyze` = 630 issues (baseline count); `flutter test` = 1345 passed, 1 failed (known `offline_gate_banner_test`). `keevo/backend/target` deleted afterwards.

**Residual risks:** skipped weekly report (reports disabled / scheduler down all evening) restarts the next week at Monday 00:00; app warning uses occurredAt only (a late-synced sale may be flagged); sales committed in the milliseconds around a report's arrival cut-off (shared with closures).

### Follow-up review pass — 2026-10-13

**Summary:** fresh 4-layer review of the post-review corrections. `ReportController.triggerWeekly` and `WeeklyReportScheduler` now use the shared `Clock` bean. Two D2 gaps closed: the manual trigger no longer generates a second report for a week already reported (it re-counted the same sales), and the weekly window chains on the store's latest report within 8 weeks, so a missing week (backend down on the configured day, reports disabled, store inactive) no longer drops the Sunday tail and late-synced sales. App: tests for the close-day refresh of the closed-day warning and the « Réessayer » button; app 1.1.42+41.

**Files changed:** `ReportController.java` (Clock + guard), `WeeklyReportScheduler.java` (Clock bean), `WeeklyReportWindowResolver.java` (look-back chain); tests `WeeklyReportControllerTest`, `ReportControllerTest`, `WeeklyReportWindowResolverTest`; app `close_day_notifier_test.dart`, `sale_detail_page_test.dart`, `pubspec.yaml`.

**Review findings:** 37 (0 high, 4 medium, 27 low, 6 false). Patched: 2 medium entries (manual-trigger guard, chain look-back) + 4 low entries (Clock, close-day refresh test, retry test, controller clock test). Deferred: 1 low → v1s-13-13-residual-check-then-act-races (sale committed after the report queries). Rejected: see the 2026-10-13 triage entry.

**Follow-up review recommended: false** — follow-up pass, no high patched (patched: 0 high, 2 medium, 4 low).

**Verification:** report tests (`WeeklyReportControllerTest` 7, `ReportControllerTest` 10, `WeeklyReportSchedulerTest` 13, `WeeklyReportWindowResolverTest` 11, `WeeklyReportGeneratorTest` 10) green; batches `reporting` 366, `commerce` 226, app `test/features/pos` 235, `test/features/reports` 44 — 0 failure; `flutter analyze` 0 error / 0 warning.

**Residual risks:** a weekly report that covers missed weeks is labelled with its own Sunday (it carries several weeks of sales); late-arrival warnings on the app are conservative; the ms commit window until v1s-13-13.
