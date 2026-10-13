---
title: 'v1s-14-2 — FR44 seller leaderboard (OWNER-only)'
type: 'feature'
created: '2026-10-12'
status: 'done'
baseline_revision: '7ab8cb99cbb29063f090446c2a5ff39deb450f45'
review_loop_iteration: 1
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-14-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** FR44 promises the owner a seller leaderboard, but nothing ranks sellers: no backend endpoint, no app screen. Only stores are ranked (`StoreRankingStrategy`, Story 7.4).

**Approach:** Add `GET /api/v1/reporting/leaderboard` (OWNER-only, EMPLOYEE → 403) that aggregates COMPLETED sales per seller in a WAT window, optionally scoped to one store, and ranks them with a Strategy mirroring `StoreRankingStrategy` (CA / sales count / avg basket). In the app, add an OWNER-only « Équipe » tab in Rapports with period chips, metric toggle and a Drift fallback when the backend is unreachable.

## Boundaries & Constraints

**Always:**
- Only `sales.status = 'COMPLETED'` counts (CANCELLED excluded); window `[from 00:00 WAT, to+1 00:00 WAT)` on `occurred_at`, same as `JdbcProfitabilityRepository`. Default period when params are absent = today in WAT computed from the injected `Clock`.
- Seller = `sales.employee_id` (it holds the actor's **user id**). Name = latest `employees` row for that user (`first_name last_name`), else `Propriétaire` when the public user role is OWNER, else `Vendeur inconnu`. Reuse `ExportValues.actorJoins/actorNameSelect`.
- Active employees (`employees.status='ACTIVE'`, in the store scope when `storeId` is given) with no sale in the window appear with 0/0/0 (Story 4.6: "all employees ranked"). The owner appears only if he sold in the window.
- Ranking is deterministic: metric desc, then revenue desc, then name asc (case-insensitive), then sellerId; ranks 1..n with no gaps.
- Amounts are XAF integers (`long`), `averageBasket = totalRevenue / salesCount` (integer division, 0 when no sale).
- Flutter: OWNER-only tab; employees never get the tab (EmployeeReportsPage untouched). Metric toggle re-ranks locally without re-fetching (same as store performance AC5). Errors via `AppErrorWidget` with retry; no hardcoded colors (AppTheme / ColorScheme tokens, medal tokens for top 3); French UI.
- Online = backend first; a `DioException` **with** a response (4xx/5xx, incl. 403) is rethrown; only transport failures fall back to Drift.

**Never:**
- No employee-visible leaderboard and no « Afficher le leaderboard aux employés » setting (UX16 V1 = owner-only) — routed to refonte story 17-2.
- No refonte-native work (17-2), no Premium gate, no new Flyway migration, no Drift schema change, no change to the existing store-performance endpoint/semantics.
- No full test suites (`mvn test` / `flutter test` all at once) — chunked runs only.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Rank by CA | OWNER, 2 employees with sales | List sorted by totalRevenue desc, ranks 1,2 | — |
| Metric toggle | `metric=SALES_COUNT` / `AVG_BASKET` | Sorted by that metric, ties by revenue then name | Unknown metric → CA (`RankingMetric.fromString`) |
| Cancelled sale | Seller has 1 COMPLETED + 1 CANCELLED | Only the COMPLETED sale counted | — |
| WAT boundary | Sale at 23:30 UTC on D-1 (= 00:30 WAT D) | Counted for day D, not D-1 | — |
| Store scope | `storeId` given | Only that store's sales and that store's active employees | — |
| Idle employee | Active employee, no sale | Row with 0 sales, 0 XAF, ranked after sellers | — |
| Owner sold | Sale with owner's user id | Row named `Propriétaire` | — |
| No data | No sale, no employee | `data: []` → app empty state | — |
| Employee caller | ROLE_EMPLOYEE | 403, use case never invoked | App never shows the tab |
| Bad dates | `from > to` | 400 `VALIDATION_ERROR` | DomainException |
| Offline | Device offline / backend unreachable | Same ranking computed from Drift + offline banner | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/reporting/profitability/domain/service/StoreRankingStrategy.java` + `StoreRankingStrategyFactory.java` + `ByCaStoreStrategy.java` (`assignRanks`) -- pattern to mirror for sellers.
- `keevo/backend/src/main/java/com/keevo/reporting/profitability/domain/model/RankingMetric.java` -- reuse as-is (CA, SALES_COUNT, AVG_BASKET; `fromString` falls back to CA).
- `keevo/backend/src/main/java/com/keevo/reporting/profitability/adapter/in/rest/ProfitabilityController.java` -- class-level `@PreAuthorize("hasRole('OWNER')")`, `ApiResponseWrapper.ok`, `TenantContext.getCurrentTenant()`, date-param parsing style.
- `keevo/backend/src/main/java/com/keevo/reporting/profitability/adapter/out/persistence/JdbcProfitabilityRepository.java` -- JdbcTemplate + WAT `Timestamp` window (lines ~70-110 `findRawStoreByPeriod`).
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/export/ExportValues.java:83-94` -- `actorJoins(userIdExpr, alias)` / `actorNameSelect(alias)` (employee name or `Propriétaire`).
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/config/ClockConfig.java` -- `Clock` bean (UTC); convert with `LocalDate.now(clock.withZone(WAT))`.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java:480` -- `sales(employee_id = user id, store_id, total_amount, status, occurred_at)`; `:548` `employees(user_id, store_id, first_name, last_name, status)`.
- `keevo/backend/src/test/java/com/keevo/reporting/export/adapter/in/rest/ExportControllerSecurityTest.java` -- `@WebMvcTest` + `@Import(SecurityConfig)` + mocked `JwtAuthFilter` pattern for the 403 test.
- `keevo/backend/src/test/java/com/keevo/shared/infrastructure/persistence/NativeSqlPostgresIntegrationTest.java` -- `@DataJpaTest extends PostgresIntegrationTest` pattern for real-SQL tests (public baseline has sales/employees/users).
- `keevo/app/lib/features/profitability/` -- data/domain/presentation stack to extend: `remote_profitability_datasource.dart`, `local_profitability_datasource.dart` (Drift aggregation, `getStorePerformance` ~L253), `profitability_repository(_impl).dart`, `profitability_providers.dart` (`rankStoresBy`), `store_performance_page.dart` (layout, `_MetricToggle`, `_OfflineBanner`), `store_performance_row.dart` (medal colors `AppTheme.medalGold/Silver/Bronze`), `period_filter_chips.dart` (`PeriodSelection`).
- `keevo/app/lib/core/storage/sales_table.dart`, `employees_table.dart`, `users_table.dart` -- Drift columns (`sales.employee_id`, `employees.user_id/first_name/last_name/store_id/status`, `users.role`).
- `keevo/app/lib/features/reports/presentation/page/owner_reports_page.dart` -- OWNER tab hub (4 tabs → 5); `EmployeeReportsPage` in same file stays untouched.
- `keevo/app/test/features/profitability/**` -- existing test layout to mirror.

## Tasks & Acceptance

**Execution:**
- `keevo/backend/src/main/java/com/keevo/reporting/leaderboard/domain/model/SellerLeaderboardEntry.java` -- record(rank, sellerId UUID, sellerName, owner boolean, salesCount int, totalRevenue long, averageBasket long) -- domain row.
- `.../reporting/leaderboard/domain/service/SellerRankingStrategy.java`, `BySellerRevenueStrategy.java`, `BySellerSalesCountStrategy.java`, `BySellerAvgBasketStrategy.java`, `SellerRankingStrategyFactory.java` -- Strategy + Factory mirroring the store ones, shared tie-break + `assignRanks` -- AC ranking.
- `.../reporting/leaderboard/domain/port/in/GetSellerLeaderboardUseCase.java` (query record: tenantId, from, to, storeId nullable, metric) and `.../domain/port/out/SellerLeaderboardRepository.java` (`RawSellerRow(sellerId, sellerName, owner, salesCount, totalRevenue)`; `findRawSellers(from, to, storeId)`) -- ports first.
- `.../reporting/leaderboard/adapter/out/persistence/JdbcSellerLeaderboardRepository.java` -- one SQL: COMPLETED sales grouped by `employee_id` in the WAT window (+ store filter) UNION active employees (store-scoped) without sale; names via `actorJoins/actorNameSelect`, fallback `Vendeur inconnu`; owner flag from public user role.
- `.../reporting/leaderboard/application/service/SellerLeaderboardService.java` -- `@Service @Transactional(readOnly=true)`; computes averageBasket, applies factory strategy.
- `.../reporting/leaderboard/adapter/in/rest/LeaderboardController.java` + `dto/SellerLeaderboardEntryDto.java` -- `GET /api/v1/reporting/leaderboard?from&to&storeId&metric`, `@PreAuthorize("hasRole('OWNER')")`, defaults today WAT via `Clock`, `from > to` → `DomainException(ErrorCode.VALIDATION_ERROR)` (400).
- Backend tests -- `SellerRankingStrategyTest` (`shouldRankEmployeesByRevenue`, sales-count, avg-basket, tie-break), `SellerLeaderboardServiceTest`, `LeaderboardControllerTest` (standalone: 200 + defaults + 400), `LeaderboardControllerSecurityTest` (`shouldReturn403ForEmployee`, OWNER 200), `JdbcSellerLeaderboardRepositoryPostgresIntegrationTest` (cancelled excluded, WAT boundary, store scope, idle employee, owner label).
- `keevo/app/lib/features/profitability/domain/model/seller_leaderboard_model.dart` -- `SellerLeaderboardEntry` (+ `fromJson`) and `SellerLeaderboardParams(from, to, storeId)` with value equality (family key).
- `keevo/app/lib/features/profitability/data/datasource/remote_profitability_datasource.dart` -- `fetchSellerLeaderboard(from, to, storeId)`.
- `keevo/app/lib/features/profitability/data/datasource/local_profitability_datasource.dart` -- `getSellerLeaderboard(from, to, storeId)`: Drift mirror of the backend SQL (COMPLETED, `[from, to+1)`, store filter, idle active employees, `Propriétaire` via `users.role`, else `Vendeur inconnu`).
- `keevo/app/lib/features/profitability/domain/repository/profitability_repository.dart` + `data/repository/profitability_repository_impl.dart` -- `getSellerLeaderboard` returns `SellerLeaderboardResult(entries, fromLocal)` (`fromLocal` = true whenever the Drift aggregation was served, i.e. device offline OR transport-failure fallback); rethrow `DioException` with response, fallback on transport failure.
- `keevo/app/lib/features/profitability/presentation/provider/profitability_providers.dart` -- `selectedLeaderboardPeriodProvider` (default `PeriodSelection.today`), `selectedLeaderboardMetricProvider` (default CA), `rankSellersBy(entries, metric)` (same tie-break), `sellerLeaderboardProvider` family.
- `keevo/app/lib/features/profitability/presentation/widget/offline_data_banner.dart` -- extract `_OfflineBanner` from `store_performance_page.dart`; both pages use it. Text: « Mode hors ligne — données de la dernière synchronisation affichées ».
- `keevo/app/lib/features/profitability/presentation/widget/seller_leaderboard_row.dart` + `presentation/page/seller_leaderboard_page.dart` -- UI-3 page: period chips, metric toggle (reuse/extract `_MetricToggle` as public `RankingMetricToggle`), offline banner shown when sync status is offline **or** `result.fromLocal` is true, `AppErrorWidget`, empty state « Aucune vente sur la période », rows: rank badge (medal tokens top 3), name (+ « Propriétaire » tag), sales count, CA, avg basket.
- `keevo/app/lib/features/reports/presentation/page/owner_reports_page.dart` -- add 5th tab « Équipe » (`SellerLeaderboardPage(storeId: activeStoreId)`), `TabController(length: 5)`, `isScrollable` only if needed for width.
- App tests -- model/json, repository fallback + rethrow, local datasource (Drift in-memory: cancelled excluded, store filter, idle employee, owner label), `rankSellersBy`, local datasource WAT/day-boundary case (sale at 23:59 and 00:01 local around `to`/`from` edges, incl. a UTC `…Z`-stored row), page widget (renders rows, toggle re-ranks without refetch, error → AppErrorWidget, banner shown when offline, banner shown when online but `fromLocal`, no banner when online and remote, tapping « 7 jours » re-fetches with the new `from` and leaves `selectedProfitabilityPeriodProvider` untouched, offline→online transition re-fetches), `SellerLeaderboardRow` (medal tokens ranks 1–3 vs soft tint, owner tag, vente/ventes), owner tab present / employee page without « Équipe ».
- `keevo/app/pubspec.yaml` -- version `1.1.36+35` → `1.1.37+36`.
- `_bmad-output/planning-artifacts/epics-refonte-modulaire.md` (Story 17.2) -- route the employee opt-in toggle item.

**Acceptance Criteria:**
- Given an OWNER JWT, when `GET /api/v1/reporting/leaderboard?metric=CA` is called, then sellers are ranked by revenue (`shouldRankEmployeesByRevenue`).
- Given an EMPLOYEE JWT, when the endpoint is called, then the response is 403 and the use case is never invoked (`shouldReturn403ForEmployee`).
- Given an OWNER in the app, when he opens Rapports, then an « Équipe » tab shows the leaderboard for today by default with CA / Ventes / Panier moyen toggle and period chips; given an EMPLOYEE, then no « Équipe » tab exists.
- Given the device offline, when the owner opens « Équipe », then the ranking is computed from Drift with the offline banner.
- `flutter analyze` 0 error / 0 warning; `no_hardcoded_colors_test` green.

## Spec Change Log

### 2026-10-12 — Loop 1 (bad_spec)
- **Trigger:** review pass 1 — the offline banner followed device connectivity only; when the device is online but the backend is unreachable the repository served Drift data with no banner, contradicting the matrix row "Offline | Device offline / backend unreachable | … + offline banner".
- **Amended:** Tasks — repository returns `SellerLeaderboardResult(entries, fromLocal)`; page shows the banner on `offline || fromLocal`; banner text fixed (« affichées », « synchronisation »); app tests extended (banner cases, period chip re-fetch + isolation, reconnect re-fetch, row widget, local WAT boundary).
- **Known-bad state avoided:** local (possibly stale) ranking displayed as live data.
- **KEEP:** attempt 1 is committed at `7b211d5e` on this branch (destructive revert refused by the environment, so the re-derivation starts from that tree): keep the whole backend `reporting/leaderboard` package and its 19 tests unchanged, the 400 via `InvalidRequestParameterException` (observable 400 `VALIDATION_ERROR`), the Drift SQL mirror, `rankSellersBy`, `RankingMetricToggle`/`OfflineDataBanner` extraction, the « Équipe » tab, pubspec 1.1.37+36, and the 17-2 routing. Only apply the amended items.

## Review Triage Log

### 2026-10-12 — Review pass 1
- verdicts: 28 findings — high 0, medium 3, low 14, false 11, maybe-false 0
- findings:
  - `[low]` `[patch]` (VG) Offline banner never asserted on the Équipe page — moot under bad_spec; added to amended app-test task.
  - `[low]` `[patch]` (VG) Period chips not verified to re-fetch / stay isolated from the Rentabilité period — moot; added to amended app-test task.
  - `[low]` `[patch]` (VG) Offline→online re-fetch unverified — moot; added to amended app-test task.
  - `[medium]` `[bad_spec]` (VG) Banner follows device connectivity, not data source (online + backend unreachable → Drift data, no banner) — matrix row requires the banner; spec amended (`SellerLeaderboardResult.fromLocal`), loop 1.
  - `[low]` `[reject]` (VG) Backend names latest employee by `updated_at`, Drift by `created_at` — Drift `employees` has no `updated_at` (schema change forbidden by spec); divergence only for a user with several differently-named employee rows, rare.
  - `[medium]` `[bad_spec]` (BH) Offline banner missing on transport fallback — same root cause as the VG banner finding; covered by the loop-1 amendment.
  - `[low]` `[reject]` (BH) Online result not cached, no « Dernière MAJ » label — sibling report screens (Boutiques/Rentabilité) re-aggregate Drift with the same banner; intent asks for a fallback "cohérent avec les autres écrans de rapports"; a cache layer adds a table/complexity.
  - `[low]` `[reject]` (BH) `updated_at` vs `created_at` — same as VG row above.
  - `[low]` `[patch]` (BH) No WAT/day-boundary test for the Drift query — moot; added to amended app-test task.
  - `[false]` `[reject]` (BH) 400 path not a `DomainException` — observable contract (400, domainCode `VALIDATION_ERROR`) is the matrix's; `DomainException(VALIDATION_ERROR)` maps to 422 in `GlobalExceptionHandler`, so the code meets the contract and only the spec wording would change.
  - `[false]` `[reject]` (BH) `tenantId` unused / no actor in query — identical to `ProfitabilityQuery` (tenant isolation by `search_path`), role enforced by class-level `@PreAuthorize` exactly like `ProfitabilityController`/`ExportController`; no actor-dependent logic exists.
  - `[low]` `[reject]` (BH) No `/reports/equipe` deep link — intent asks for no deep link; tab reachable from the Rapports hub; adding a route + guard entry is new surface.
  - `[medium]` `[patch]` (BH) Epic-14 context rewrite dropped requirements of still-open 14.6 (FR7/FR65/FR67 details, owner-recipient rules) — restored by the orchestrating agent (planning artifact, not code).
  - `[low]` `[patch]` (BH) Epic-14 context contradicts spec decisions D2 (period presets) / D5 (spinner) — context updated to record D2/D5.
  - `[low]` `[reject]` (BH) Several owners all labelled « Propriétaire » — multi-owner tenants are an edge case; distinguishing them needs new owner-name data that does not exist (owners have no employee row).
  - `[false]` `[reject]` (BH) `response.data!` force-unwrap — same pattern as every other method of `RemoteProfitabilityDatasource`; the endpoint always returns an `ApiResponseWrapper` body (never 204).
  - `[low]` `[reject]` (BH) « Aujourd'hui » stale past midnight on the kept-alive tab — identical behaviour on the sibling Boutiques/Rentabilité tabs; any rebuild/period tap recomputes; fixing needs a lifecycle/timer hook.
  - `[low]` `[patch]` (BH) `SellerLeaderboardRow` untested; offline banner / reconnect untested — moot; added to amended app-test task.
  - `[low]` `[patch]` (BH) Banner French « données du dernier sync affiché » — moot; corrected text added to the amended task.
  - `[low]` `[reject]` (IA) `shouldRankEmployeesByRevenue` tested on the strategy, not end-to-end — every layer is covered (strategy, service, controller, real SQL, 403 security slice); an end-to-end full-context test adds cost without a named failure mode.
  - `[false]` `[reject]` (IA) Server-side `metric` unused by the app — the app re-ranks locally with the same order (spec: toggle never re-fetches); the backend `metric` stays the API contract required by the AC.
  - `[false]` `[reject]` (IA) Dart string vs Java UUID final tie-break may differ — the app always re-ranks locally, so displayed order is consistently Dart's; only reachable when metric, revenue and name all tie.
  - `[false]` `[reject]` (IA) "toggle" may mean the employee-visibility switch (R2) — the AC itself says EMPLOYEE → 403, incompatible with an employee-visible leaderboard; UX16 = hidden for employees in V1; opt-in routed to 17-2.
  - `[false]` `[reject]` (IA) Employee-side widget test asserts pre-existing behaviour — it is a regression guard for D1, not a defect.
  - `[false]` `[reject]` (IA) Period presets differ from 4.6 (semaine/mois) — intent says "par période" without presets; D2 records the reuse of the existing chips mandated by the epic context.
  - `[false]` `[reject]` (IA) Banner driven by sync status — duplicate of the bad_spec banner finding (logged there).
  - `[false]` `[reject]` (EC) `created_at` vs `updated_at` — duplicate of VG row (rejected low there).
  - `[false]` `[reject]` (EC) Banner on transport fallback — duplicate of the bad_spec banner finding.

### 2026-10-12 — Review pass 2
- verdicts: 35 findings — high 0, medium 0, low 23, false 12, maybe-false 0
- findings:
  - `[low]` `[patch]` (VG) `fetchSellerLeaderboard` path/query/envelope untested — added `remote_profitability_datasource_test.dart` (mocked Dio).
  - `[low]` `[patch]` (VG) Rentabilité tab keeps its private banner with the old wrong text; sibling banners untested — `ProductProfitabilityListPage` switched to `OfflineDataBanner`; offline banner tests added on Rentabilité and Boutiques pages.
  - `[low]` `[patch]` (VG) Duplicate banner copy in `product_profitability_list_page.dart` — same fix as above.
  - `[low]` `[reject]` (IA) `shouldRankEmployeesByRevenue` on the strategy, not end-to-end — carried: every layer covered (strategy, service, controller, real SQL, security slice).
  - `[false]` `[reject]` (IA) App never sends `metric`; display order from Dart — carried: spec requires local re-rank without refetch; backend `metric` is the API contract.
  - `[false]` `[reject]` (IA) Drift buckets by device local day, not explicit WAT — epic context: app periods use the device time zone, accepted because target devices run in WAT; backend is explicit WAT.
  - `[false]` `[reject]` (IA) Toggle may mean employee visibility (R2) — carried: AC says EMPLOYEE → 403; opt-in routed to 17-2.
  - `[false]` `[reject]` (IA) `@PreAuthorize` instead of `CurrentActor` — carried: same as `ProfitabilityController`; no actor-dependent logic.
  - `[false]` `[reject]` (IA) No store picker on the page — D3: scope follows the active store like the Rentabilité tab.
  - `[low]` `[reject]` (IA) No deep-link route/guard test — carried: no route was asked; tab reached from the owner-only hub.
  - `[false]` `[reject]` (IA) No evidence analyze/chunked runs happened — recorded under Auto Run Result (analyze 0 warning, chunked lots green).
  - `[false]` `[reject]` (BH) NULL seller id crashes — `sales.employee_id` and `employees.user_id` are `NOT NULL` (TenantSchemaProvisioner DDL, Drift `text()` non-null).
  - `[low]` `[patch]` (BH) Idle sellers ranked 1–3 get medals — medal tokens now only when `salesCount > 0`, row test added.
  - `[low]` `[reject]` (BH) Banner says « Mode hors ligne » when online-but-unreachable — text still accurate about the data (last synchronisation); changing it means a second wording for one widget.
  - `[low]` `[reject]` (BH) No retry from an online `fromLocal` state — same as sibling report tabs; period/tab change re-fetches; pull-to-refresh is new UI.
  - `[false]` `[reject]` (BH) Gateway 5xx should fall back to Drift — spec rule (rethrow any response) mirrors the existing profitability detail path; changing it is a spec edit.
  - `[low]` `[reject]` (BH) Boutiques tab banner also ignores transport fallback — pre-existing Story 7.4 behaviour; `getStorePerformance` result has no source flag; cosmetic.
  - `[low]` `[patch]` (BH) "offline → banner" page test stubs `fromLocal:true` — added `offlineOk` + `fromLocal:false` case.
  - `[low]` `[reject]` (BH) Spec normative sections say DomainException for 400 — fix is a spec edit; observable contract (400 `VALIDATION_ERROR`) holds.
  - `[low]` `[patch]` (BH) Epic-14 context base lines contradict D2/D5 — base lines amended by the orchestrator.
  - `[low]` `[patch]` (BH) Epic-14 context still drops some guidance (Aide path, employee admin, StateError, event registry, owner-lookup DB test) + stray blank line — restored/fixed by the orchestrator.
  - `[low]` `[reject]` (BH) No upper bound on the date range — same as every reporting endpoint; app chips clamp; server clamp is new surface.
  - `[low]` `[reject]` (BH) Owner with an ACTIVE employee row / user-deactivated employee shown idle — owners have no employee row in V1 onboarding; deactivation sets `employees.status`; rare.
  - `[low]` `[reject]` (BH) Accented names sort by code unit — final tie-break only (after metric and revenue ties).
  - `[low]` `[reject]` (BH) Tab-label clipping not asserted — implementer checked 360 dp; if true it would be cosmetic; settling it needs a golden test.
  - `[false]` `[reject]` (BH) `fromJson` requires `sellerId` — backend always sends a non-null `sellerId` (UUID from NOT NULL column).
  - `[low]` `[patch]` (BH) `OwnerReportsPage` doc lists « Brouillons » — line removed.
  - `[low]` `[patch]` (BH) Security slice lacks anonymous → 401 — test added.
  - `[low]` `[reject]` (EC) `created_at` vs `updated_at` name pick — carried: Drift has no `updated_at`; schema change out of scope.
  - `[low]` `[reject]` (EC) Empty state never shown when idle employees exist — intended (D4: all active employees ranked, 0/0/0); medals no longer shown for them.
  - `[low]` `[reject]` (EC) Stale Drift data while online — duplicate of BH retry row.
  - `[low]` `[reject]` (EC) « Aujourd'hui » stale past midnight — carried.
  - `[false]` `[reject]` (EC) 200 with null `data` → TypeError — carried: endpoint always wraps in `ApiResponseWrapper`.
  - `[false]` `[reject]` (EC) 400 exception type differs from spec — carried: same status and domainCode.
  - `[false]` `[reject]` (EC) Drift mirror differs (name pick, device day) — carried/duplicate of rows above.

## Design Notes

Decisions (from FR44, UX16, Story 4.6, epic-14 context):
- D1 Employee: 403 backend + tab never rendered (UX16 V1 "caché côté employé"). Story 4.6's opt-in toggle « Afficher le leaderboard aux employés » is not in the 14.2 AC → routed to 17-2.
- D2 Periods: reuse `PeriodFilterChips` (Aujourd'hui / 7 jours / 30 jours / Personnalisé) instead of 4.6's « Cette semaine / Ce mois » — epic context mandates reusing the period chips; default Aujourd'hui as in 4.6. Own state provider so the Rentabilité tabs keep their 30-day default.
- D3 Store scope: `storeId` follows the active store like the Rentabilité tab (`activeStoreIdProvider`, null = all stores) — no new store semantics.
- D4 "Sellers" = anyone who recorded a sale (owner included, labelled `Propriétaire`) + idle active employees; inactive employees without sales are omitted.
- D5 Loading uses the same spinner as the sibling Boutiques tab (no shimmer widget exists in `core/widget`).
- D6 New `reporting/leaderboard` subpackage (hexagonal) reusing `RankingMetric`; intra-module import, no cross-domain dependency.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -o test -Dtest='SellerRankingStrategyTest,SellerLeaderboardServiceTest,LeaderboardController*Test,JdbcSellerLeaderboardRepositoryPostgresIntegrationTest' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: all green.
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh backend reporting commerce shared architecture` -- expected: 0 failure / 0 error.
- `cd keevo/app && flutter test --concurrency=4 <new/changed test files>` then `run-tests-chunked.sh app core profitability reports` -- expected: 0 failure.
- `cd keevo/app && flutter analyze` -- expected: 0 error, 0 warning.

## Auto Run Result

Status: done

**Summary:** FR44 seller leaderboard, OWNER-only. Backend `GET /api/v1/reporting/leaderboard` (new hexagonal `reporting/leaderboard` package: Strategy + Factory mirroring `StoreRankingStrategy`, one JDBC query — COMPLETED sales only, WAT window `[from 00:00, to+1 00:00)`, optional store scope, idle active employees at 0, names via `ExportValues.actorJoins`, « Propriétaire » / « Vendeur inconnu »). EMPLOYEE → 403, anonymous → 401, `from > to` → 400 `VALIDATION_ERROR`. App: 5th « Équipe » tab in the owner Rapports hub (period chips default Aujourd'hui, CA / Ventes / Panier moyen toggle re-ranking locally, active-store scope, Drift fallback with `SellerLeaderboardResult.fromLocal` driving the offline banner, `AppErrorWidget`, medal tokens only for sellers with sales). App 1.1.36+35 → 1.1.37+36.

**Files changed (main):**
- `keevo/backend/src/main/java/com/keevo/reporting/leaderboard/**` — controller, DTO, service, ports, JDBC adapter, strategies.
- `keevo/backend/src/test/java/com/keevo/reporting/leaderboard/**` — strategy (`shouldRankEmployeesByRevenue`), service, controller, security slice (`shouldReturn403ForEmployee`, 401), Postgres integration.
- `keevo/app/lib/features/profitability/domain/model/seller_leaderboard_model.dart` — entry, params, result.
- `keevo/app/lib/features/profitability/data/**` — remote fetch, Drift mirror (local-day comparison), repository with `fromLocal`.
- `keevo/app/lib/features/profitability/presentation/**` — providers (`rankSellersBy`, period/metric state), `SellerLeaderboardPage`, `SellerLeaderboardRow`, shared `OfflineDataBanner` (now used by Rentabilité, Boutiques, Équipe; text corrected) and `RankingMetricToggle`.
- `keevo/app/lib/features/reports/presentation/page/owner_reports_page.dart` — « Équipe » tab.
- `keevo/app/pubspec.yaml` — version bump.
- `_bmad-output/planning-artifacts/epics-refonte-modulaire.md` — routed item under Story 17.2.
- `_bmad-output/implementation-artifacts/epic-14-context.md` — recompiled (was stale), then repaired (14.6 requirements restored, D2/D5 recorded).

**Review:** 2 passes + 1 bad_spec loop. Pass 1: 28 findings (3 medium, 14 low, 11 false) → 1 bad_spec loop (banner must follow the data source), test/text items folded into the amended spec, context repaired. Pass 2: 35 findings (22 low, 13 false) → 9 patched (all low), 26 rejected with reasons in the triage log. Nothing deferred. Patched by verdict on the last pass: high 0, medium 0, low 9. Follow-up review recommendation: false (no high, fewer than two medium patched).

**Process note:** the bad_spec loop could not revert the working tree (destructive revert refused by the environment); attempt 1 was committed as WIP `7b211d5e` and the re-derivation applied the amendment on top, per the KEEP instructions in the Spec Change Log.

**Verification:**
- Targeted backend: 20 leaderboard tests green (strategy 5, service 2, controller 4, security 3, Postgres IT 6).
- `run-tests-chunked.sh backend reporting commerce shared architecture`: reporting 360, commerce 226, shared 452, architecture 6 — 0 failures / 0 errors.
- `run-tests-chunked.sh app test/core test/features/profitability test/features/reports`: core 469 (incl. `no_hardcoded_colors_test`), profitability 87, reports 44 — all passed.
- `flutter analyze`: 0 errors, 0 warnings (621 infos).

**Residual risks:**
- The offline name of a user with several employee rows uses Drift `created_at` (no `updated_at` column); the backend uses `updated_at`.
- The Boutiques tab banner still follows device connectivity only (pre-existing, Story 7.4).
- No retry affordance while online with local data other than changing period/tab (same as sibling tabs).
- Offline day bucketing uses the device time zone (accepted: target devices run in WAT).

**Routed:** owner setting « Afficher le leaderboard aux employés » (Story 4.6 / UX16) → refonte Story 17.2 (`17-2`).
