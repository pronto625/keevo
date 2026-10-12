---
title: 'v1s-16-19 Dashboard weekly buckets keyed by the local Monday (year end, DST)'
type: 'bugfix'
created: '2026-10-12'
status: 'done'
baseline_revision: 'e5ceaebb60ad7eab224a0e56b657c8c7e2a6756f'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** The owner's 12-week revenue chart loses sales. Offline (Drift), `getWeeklyCA` / `getStoreWeeklyCA` group by `strftime('%Y-%W', day)` while slots are keyed `'<Monday year>-<sqliteWeekW(Monday)>'`: in a week starting in December, sales from 1 January land in `Y+1-00`, match no slot and vanish. Slot Mondays (and the `getStore{Top,Worst}Products` week start) are built by `Duration` subtraction, which shifts a day/hour across DST. Online, the backend `DashboardService` (GET /api/v1/dashboard/summary, used first by the app) groups weekly/daily/monthly/yearly with `DATE_TRUNC`/`DATE()` in the DB session time zone (UTC), not WAT: a Monday 00:30 WAT sale counts in the previous week (same for days, 1st of month, 1 January).

**Approach:** Key every weekly bucket by the local Monday date (`YYYY-MM-DD`) on both sides: SQL `date(day, '-' || ((strftime('%w', day) + 6) % 7) || ' days')`, Dart slots `DateTime(y, m, d - k*7)` from a calendar Monday. Inject the clock (`DateTime Function() now`) into `LocalDashboardDatasource`. Backend: bucket charts on `occurred_at AT TIME ZONE 'Africa/Lagos'` (precedent: `JdbcProfitabilityRepository`) with window starts computed in WAT from an injected `Clock`.

## Boundaries & Constraints

**Always:** weeks start Monday; slot list stays 12 entries oldest→newest with `DailyCA.date` = local Monday 00:00; calendar-field arithmetic only (no `Duration` day math) in the touched code; default clock `DateTime.now` / `Clock` bean so production wiring is unchanged; bump `keevo/app/pubspec.yaml` 1.1.30+29 → 1.1.31+30.

**Never:** change the DashboardSummary JSON contract (field names, `YYYY-MM-DD` period strings); add backend zero-filling or Flyway migrations; touch rolling 7-day top/worst windows beyond calendar construction; run full test suites (chunked script only).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Year-straddling week (app) | now = Wed 2026-01-07; sales 2025-12-30 (500) and 2026-01-02 (300) | slot Monday 2025-12-29 amount = 800 | — |
| Every week 2024→2035 (app) | each Monday of 2024-2035 | SQL Monday key == Dart Monday for every day of the week | — |
| DST (app, TZ=Europe/Paris run) | now in the week after 2026-03-29 / 2026-10-25 | 12 slots are Monday 00:00 local, 7 calendar days apart | test is meaningful only in a DST zone; still green in UTC |
| Monday 00:30 WAT (backend) | sale 2026-01-04T23:30Z (Mon 2026-01-05 00:30 WAT) | weekly period `2026-01-05`; daily `2026-01-05` | — |
| Year-straddling week (backend) | sales 2025-12-30 and 2026-01-02 WAT | one weekly row `2025-12-29` with the sum | — |

</intent-contract>

## Code Map

- `keevo/app/lib/features/dashboard/data/datasource/local_dashboard_datasource.dart` -- `getWeeklyCA` ~L346 / `getStoreWeeklyCA` ~L462 (`%Y-%W` SQL + Duration slots), `sqliteWeekW` ~L383 (only caller is its parity test), `getStoreTopProducts` ~L533 / `getStoreWorstProducts` ~L574 (`now.subtract(Duration(days: weekday-1))`), all `DateTime.now()` calls; `_localDay()` L12 = local day expression to reuse.
- `keevo/app/lib/features/dashboard/presentation/provider/dashboard_providers.dart:25` -- constructs the datasource (default clock, no change needed).
- `keevo/app/test/features/dashboard/data/datasource/local_date_bucketing_test.dart` -- sqlite override setUp, `insertSale(raw string)`, `sqliteWeekW` parity test L170 (replace by Monday-key parity).
- `keevo/app/test/features/dashboard/data/datasource/local_dashboard_datasource_test.dart` -- tests computing `today` from `DateTime.now()` (L34-L223): pass a fixed clock.
- `keevo/backend/src/main/java/com/keevo/reporting/dashboard/application/service/DashboardService.java` -- `execute` (WAT boundaries via `LocalDate.now(wat)`), `getDailyCA`/`getWeeklyCA`/`getMonthlyCA`/`getYearlyCA` L189-L240 (`DATE_TRUNC(.., occurred_at)`, `NOW()`).
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/config/ClockConfig.java` -- `Clock` bean (UTC) to inject.
- `keevo/backend/src/test/java/com/keevo/reporting/dashboard/application/service/DashboardServiceTest.java` -- mock test, constructor update.
- `keevo/backend/src/test/java/com/keevo/shared/infrastructure/persistence/NativeSqlPostgresIntegrationTest.java` -- pattern for a `@DataJpaTest` on real PG (`insertSale` into public `sales`).
- Read-only: backend weekly reports (`WeeklyReportScheduler`, `ReportController.triggerWeekly`, `WeeklyReportWindowResolver`) use `LocalDate` + `previousOrSame(SUNDAY)` in WAT — no week-number keying, not affected.

## Tasks & Acceptance

**Execution:**
- `keevo/app/lib/features/dashboard/data/datasource/local_dashboard_datasource.dart` -- add `{DateTime Function()? now}` ctor param, replace every `DateTime.now()` by `_now()`; add a `_localMonday(col)` SQL helper and a `@visibleForTesting static DateTime mondayOf(DateTime)` (calendar); weekly queries select/group by the Monday key, window `occurred_at >= oldest slot Monday 00:00`, slots `DateTime(m.year, m.month, m.day - (11 - i) * 7)`, key `_d(slot)`; store top/worst week start via `mondayOf`; delete `sqliteWeekW` -- fixes both items and the 15-8 midnight window.
- `keevo/app/test/features/dashboard/data/datasource/local_date_bucketing_test.dart` -- year-end week test (fixed clock), store variant, Monday-key parity 2024-2035 (SQL helper vs `mondayOf`), slot-generation test over every day 2024-2035 (12 Mondays, 00:00, 7 calendar days apart); use injected clock instead of real `now`.
- `keevo/app/test/features/dashboard/data/datasource/local_dashboard_datasource_test.dart` -- inject a fixed clock.
- `keevo/backend/.../DashboardService.java` -- inject `Clock`; compute today from it; chart queries use `(occurred_at AT TIME ZONE 'Africa/Lagos')` for grouping and a WAT-computed `Timestamp` lower bound (weekly: Monday 11 weeks before this week's Monday).
- `keevo/backend/src/test/.../DashboardServiceTest.java` -- pass a fixed clock.
- `keevo/backend/src/test/java/com/keevo/reporting/dashboard/application/service/DashboardServicePostgresIntegrationTest.java` -- new, extends `PostgresIntegrationTest`: Monday 00:30 WAT and year-straddling week rows.
- `keevo/app/pubspec.yaml` -- 1.1.31+30.

**Acceptance Criteria:**
- Given offline mode and a week spanning 31 Dec/1 Jan, when the owner opens the weekly chart (all stores or one store), then the week's bar equals the sum of all its sales.
- Given the app runs in a DST time zone, when the 12 slots are built on any day 2024-2035, then every slot is a Monday at 00:00 and consecutive slots are 7 calendar days apart.
- Given online mode, when GET /api/v1/dashboard/summary is called, then weekly/daily/monthly/yearly buckets follow WAT calendar days and a year-straddling week is a single row.

## Spec Change Log

## Review Triage Log

### 2026-10-12 — Review pass
- verdicts: 25 findings — high 0, medium 7, low 15, false 3, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind) Weekly lower bound compared as raw text drops a UTC `Z` row at 00:30 local on the oldest slot Monday — regression vs the old now-84d bound; fixed: `_prefilter` = 1 calendar day earlier on the 7 key-mapped chart queries (slot lookup ignores extra keys).
  - `[low]` `[patch]` (blind) No test for the `Z` storage form at the oldest Monday — added "a Z-stored 00:30 local sale on the oldest slot Monday counts" (fails under TZ=Africa/Lagos without the fix).
  - `[low]` `[reject]` (blind) Monday-key parity test only exercises the non-`Z` branch — the `Z` branch is `_localDay`'s, already covered by the UTC-row tests; the Monday wrap applies to the same day string. Not worth extra setup.
  - `[low]` `[patch]` (blind) Local-day CASE expression repeated in SELECT and GROUP BY — `GROUP BY period`.
  - `[low]` `[reject]` (blind) DST slot test cannot fail on a UTC/WAT runner — needs CI infra (TZ matrix); target market has no DST; runs under TZ=Europe/Paris and Africa/Lagos recorded in Verification.
  - `[low]` `[reject]` (blind) `getDashboardSnapshot` reads the clock once per sub-query — pre-existing, sub-millisecond midnight window; fix adds parameters to every method.
  - `[low]` `[patch]` (blind) `localMondaySql` exported as a top-level lib function — now a `@visibleForTesting static` on the class.
  - `[medium]` `[patch]` (blind) Backend all-stores series (`storeId == null`) never checked on PostgreSQL — containment assertions added for weekly/daily/monthly/yearly in `DashboardServicePostgresIntegrationTest`.
  - `[low]` `[patch]` (blind) Excluded side of the backend weekly window untested — Sunday 23:30 WAT sale before the oldest Monday asserted absent (`2025-10-13`).
  - `[low]` `[reject]` (blind) `chart(String unit)` concatenation / `ZoneId wat = WAT` alias — private method, internal literals only; cosmetic.
  - `[medium]` `[patch]` (blind) Online (remote) weekly/monthly/yearly series not zero-filled — online chart showed only weeks with sales vs 12 bars offline; fixed in `dashboard_providers.dart` (`zeroFillWeeklyCA/MonthlyCA/YearlyCA`, store daily, calendar `_zeroFillDailyCA`, `dashboardClockProvider`) + 3 provider tests. Per-store N+1 chart queries: pre-existing, low, rejected (few stores per tenant).
  - `[medium]` `[patch]` (edge) Same `Z` row / text-bound defect as row 1 — same fix.
  - `[low]` `[reject]` (edge) Backend chart query has no upper bound (future-dated sales) — identical to the old queries; clock-skewed future sales are rare and harmless.
  - `[low]` `[reject]` (edge) Zones whose DST jump skips midnight make `DateTime(y,m,d)` 01:00 — slots are keyed by `_d(date)` so charts stay correct; only the slot test's `hour == 0` assertion would fail in such a zone.
  - `[low]` `[reject]` (verification-gap) DST-safety of `weeklySlots` unprotected in the normal run — same as row 5.
  - `[medium]` `[patch]` (verification-gap) Summary-level backend charts never checked on real SQL — same as row 8.
  - `[medium]` `[patch]` (verification-gap other) Weekly text lower bound vs `Z` rows — same as row 1.
  - `[low]` `[reject]` (verification-gap other) Backend no upper bound — same as row 13.
  - `[low]` `[reject]` (intent) DST expectation lives in the device zone, test runs in runner zone — same as row 5.
  - `[low]` `[reject]` (intent) Parity test compares generated strings, not stored `occurred_at` — end-to-end year-straddle is covered by the two datasource tests and the `Z` test.
  - `[medium]` `[patch]` (intent) Owner-visible chart (provider/remote path) untested and remote series sparse — same as row 11.
  - `[false]` `[reject]` (intent) Backend change answers a different defect — deliberate (spec Intent: UTC vs WAT bucketing is the backend form of the shifted-Monday defect); Monday 00:30 and 1 Jan 00:30 tests distinguish old from new.
  - `[false]` `[reject]` (intent) Mobile scope beyond named items — required by the spec's Always rule (no Duration day math in touched code); window semantics covered by the oldest-Monday tests.
  - `[low]` `[reject]` (intent) Clock injection reaches the datasource only — providers now take `dashboardClockProvider`; `withAsOf(DateTime.now())` is a display timestamp.
  - `[false]` `[reject]` (intent) Interim AC not checkable from the diff — items are ticked in the epic brief at finalization.

## Design Notes

- Monday key instead of `%W`: a `'YYYY-MM-DD'` Monday is unique across years, so no year-split; it is also exactly what the backend returns (`TO_CHAR(DATE_TRUNC('week', ..), 'YYYY-MM-DD')`), keeping both sources consistent.
- `sqliteWeekW` is removed (no production caller left); its 2024-2035 parity test becomes a Monday-key parity test, preserving the 15-8 coverage intent.
- DST: CI runs in UTC; the slot test is written to fail under a DST zone with the old code and is run once locally with `TZ=Europe/Paris`.
- Backend stays sparse (JSON contract unchanged); the app zero-fills remote series onto the local slot shapes in `dashboard_providers.dart` (review pass).
- Chart lower bounds are a coarse prefilter one calendar day early: `occurred_at` is compared as text and synced rows are stored in UTC (`Z`); the zero-filled slot keys do the exact cut.

## Verification

**Commands:**
- `cd keevo/app && flutter test --concurrency=4 test/features/dashboard/data/datasource/` -- 0 failures; then `TZ=Europe/Paris flutter test test/features/dashboard/data/datasource/local_date_bucketing_test.dart` -- 0 failures.
- `run-tests-chunked.sh app test/core test/features/dashboard test/features/reports test/features/profitability` -- all OK.
- `MAVEN_OPTS=-Xmx1g mvn -o test -Dtest=DashboardServiceTest,DashboardServicePostgresIntegrationTest,DashboardControllerTest -DforkCount=1 -DargLine=-Xmx1536m` then `run-tests-chunked.sh backend reporting shared` -- 0 failures.
- `flutter analyze` -- no new errors/warnings.

## Auto Run Result

Status: done

**Summary:** Weekly dashboard buckets are keyed by the local Monday (`YYYY-MM-DD`) on both the Drift SQL side and the Dart slot side, so a week straddling 31 Dec / 1 Jan is one bucket (item 1) and slots are built with calendar fields, DST-safe (item 2). `LocalDashboardDatasource` takes an injected clock (15-8 midnight window removed from its tests). Backend `DashboardService` (online path, used first by the app) had the equivalent defect — buckets cut in the DB session zone (UTC) instead of WAT, shifting Monday/1st-of-month/1 January boundaries by an hour — now grouped on `occurred_at AT TIME ZONE 'Africa/Lagos'` with WAT bounds from an injected `Clock`. Backend weekly reports (`WeeklyReportScheduler`, `ReportController.triggerWeekly`) use `LocalDate` windows in WAT: not affected. The app zero-fills the sparse remote series so online and offline charts have the same 12/12/5/30 slots. App 1.1.31+30.

**Files changed:**
- `keevo/app/lib/features/dashboard/data/datasource/local_dashboard_datasource.dart` -- Monday-keyed weekly SQL + slots, `mondayOf`/`weeklySlots`/`localMondaySql`, injected clock, calendar arithmetic, 1-day prefilter on chart bounds; `sqliteWeekW` removed.
- `keevo/app/lib/features/dashboard/presentation/provider/dashboard_providers.dart` -- `dashboardClockProvider`, zero-fill of remote weekly/monthly/yearly/store-daily series, calendar `_zeroFillDailyCA`.
- `keevo/app/test/features/dashboard/...` -- year-end week (all/store), oldest-Monday (`+01:00` and `Z`), Monday-key parity 2024-2035, slot generation 2024-2035, provider zero-fill tests; fixed clocks.
- `keevo/backend/.../DashboardService.java` -- WAT bucketing via one `chart(unit, from, storeId)`, `Clock` injected.
- `keevo/backend/src/test/.../DashboardServicePostgresIntegrationTest.java` (new), `DashboardServiceTest.java` (clock).
- `keevo/app/pubspec.yaml` -- 1.1.31+30.

**Review:** 1 pass, 4 layers, 25 findings — patched 6 entries (3 medium: Z-row lower bound, backend all-stores PG coverage, online zero-fill; 3 low: GROUP BY alias, static test hook, window exclusion test); deferred 0; rejected 13 rows (reasons in the triage log). Follow-up review recommended: true — the online zero-fill (`dashboard_providers.dart`) maps WAT-keyed backend periods onto device-local slots and was added during the patch round, reviewed only through its own tests.

**Verification:** datasource tests 25/25 under UTC, Africa/Lagos and Europe/Paris; `run-tests-chunked.sh app`: test/core 446, dashboard 94, reports 42, profitability 45 — all OK; targeted mvn DashboardServiceTest 3, DashboardServicePostgresIntegrationTest 3, DashboardControllerTest 3 — 0 failures; `run-tests-chunked.sh backend reporting shared`: 223 / 391, 0 failures; `flutter analyze` 612 issues, no warning/error in touched files (pre-existing warnings elsewhere).

**Residual risks:** DST behaviour is only proven by local TZ runs (CI runs one zone); a device set to a zone other than WAT would key remote WAT periods onto local slots (same dates in Cameroon); backend series remain sparse by contract.
