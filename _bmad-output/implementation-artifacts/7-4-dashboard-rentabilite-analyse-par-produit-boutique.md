# Story 7.4: Dashboard Rentabilité & Analyse par Produit/Boutique

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

---

## GoF Pattern Analysis (MANDATORY — fill before any implementation)

| Question | Answer |
|---|---|
| What variability exists in this feature? | Sort order on profitability list (4 options: Marge%/MargeXAF/CA/Units), period filter (Today/7d/30d/Custom), aggregation scope (single-store vs all-stores), ranking metric for store comparison (CA/Sales count/Avg basket) |
| What might change in the future? | New sort criteria (e.g., "worst performers first"), new period presets, profitability by category (not just product), export (PDF/XLSX — FR55 payant), AI-generated insights |
| Which GoF pattern(s) apply? | **Strategy** for sort algorithm: `ProfitabilitySortStrategy` interface → 4 concrete implementations (ByMarginPctDesc, ByMarginXafDesc, ByCaDesc, ByUnitsDesc) injected via factory based on query param. **Builder** for `ProfitabilityReportBuilder` (assembles ProductProfitabilityEntry list from multi-table JOIN results — sales, sale_items, products). **Factory Method** for `ProfitabilitySortStrategyFactory.of(SortOption)` resolving the right Strategy. **Observer** for Riverpod provider invalidation on period/sort filter change. |
| How does it enable Open/Closed principle? | New sort options are added as new `ProfitabilitySortStrategy` implementations without modifying the service. New aggregation scopes (category, supplier) extend the Builder without modifying existing queries. New ranking metrics for store comparison are added by implementing the `StoreRankingStrategy` interface. |
| Where is the pattern applied? | **Strategy**: `ProfitabilitySortStrategy` in `reporting.profitability.domain.service`; `StoreRankingStrategy` same package. **Builder**: `ProfitabilityReportBuilder` in `reporting.profitability.application.service`. **Factory Method**: `ProfitabilitySortStrategyFactory` in `reporting.profitability.domain.service`. **Observer**: Riverpod `productProfitabilityProvider(params)` + `storePerformanceProvider(params)` family providers. |

---

## Story

As a proprietor (Simon — OWNER role),
I want to see profitability per product and comparative performance per store,
So that I can identify my best margins, cut underperforming products, and understand which store drives the most value.

---

## Acceptance Criteria

### AC1 — Product profitability list (Rapports > Rentabilité)

- **Given** Simon navigates to Rapports → tab "Rentabilité" (OWNER only)
- **When** the screen loads
- **Then** a `ProductProfitabilityListPage` is shown with one row per product that has had at least 1 sale in the selected period
- **And** each row displays: product name, total units sold, total revenue (XAF), total cost (buyPrice + transportCost) × units, gross margin in XAF, gross margin %
- **And** the list is sorted by **margin % descending** by default
- **And** products with negative margin (isLoss) appear last, with a "PERTE" badge
- **And** the margin % uses the same color coding as `PricingCalculatorWidget` (Story 2.2):
  - Red (loss or < 10%)
  - Orange (10% – 19%)
  - Green (≥ 20%)
- **And** the routes `/reports/rentabilite` and `/reports/boutiques` are added to `_ownerOnlyPrefixes` in `app_router.dart` — an EMPLOYEE attempting direct navigation is silently redirected to `/pos` before the page ever renders; no in-widget guard needed

### AC2 — Sort options on profitability list

- **Given** the profitability list is displayed
- **When** Simon taps a sort chip
- **Then** sort options available are: **Marge %** (default) / **Marge XAF** / **CA** / **Unités vendues**
- **And** tapping a chip re-sorts the list immediately (local, no API call)
- **And** the active chip has a filled/highlighted style
- **And** the sort is applied client-side via Riverpod provider with Strategy pattern

### AC3 — Period filter

- **Given** the profitability list is displayed
- **When** Simon selects a period
- **Then** period options are: **Aujourd'hui** / **7 jours** / **30 jours** / **Personnalisé** (date range picker)
- **And** all profitability data (units, revenue, cost, margin) updates to reflect the selected range
- **And** the filter is applied locally against Drift `sales` + `sale_items` + `products` data — no API call needed when data is fresh
- **And** the screen renders in < 3 seconds for up to 500 products with pre-aggregated Drift queries
- **And** the selected period persists across tab changes within the session (not persisted across app restarts)

### AC4 — Product profitability detail

- **Given** Simon taps a product row in the rentabilité list
- **When** the `ProductProfitabilityDetailPage` opens (route `/reports/rentabilite/:productId`)
- **Then** there is a header with: product name, category (if any), current catalogue price, current buy price, current transport cost
- **And** below: total units sold (selected period), total revenue, total cost, gross margin XAF + %
- **And** a **price range section**: min / max / average applied unit price (from `sale_items.applied_unit_price`) — reveals price override patterns
- **And** a **margin trend sparkline** (last 7 days): one data point per day with daily margin total XAF (bar chart, same palette as MorningSummaryHeroCard)
- **And** a **top store by sales volume** row: store name + units sold in this period (if multi-store tenant)
- **And** a back arrow returns to the profitability list preserving scroll position + filters

### AC5 — Store comparative performance (Rapports > Boutiques)

- **Given** Simon navigates to Rapports → tab "Boutiques"
- **When** the `StorePerformancePage` loads
- **Then** a ranked list of stores is shown, sorted by CA descending (default):
  - Rank badge (1st, 2nd, 3rd…)
  - Store name
  - CA for the selected period (XAF)
  - Number of completed sales
  - Average basket (XAF)
  - Top product name (most units sold in that store, selected period)
  - Delta badge: week-over-week % change (e.g., "+8%" green or "−3%" red)
- **And** a ranking metric toggle is available: **CA** / **Ventes** / **Panier Moyen**
- **And** changing the metric re-ranks the list immediately (local sort)
- **And** the delta badge uses the same threshold/color rules as `ShopStatusCard` (Story 7.1 AC4): green = improvement, red = decline

### AC6 — Period filter on store comparison

- **Given** the store comparison list is shown
- **When** Simon changes the period (same chip row: Aujourd'hui / 7 jours / 30 jours / Personnalisé)
- **Then** all store metrics and deltas update to reflect the range
- **And** the "week-over-week" delta on each store card adapts: it always compares the current period against the equivalent preceding period of the same length

### AC7 — Navigation: Rapports page tabs (OWNER)

- **Given** Simon (OWNER) opens the Rapports tab in the bottom nav
- **When** the page renders
- **Then** a `TabBar` with 4 tabs appears:
  - **📋 Jour** — existing `ReportsPage` day-close content (AC preserved from Stories 4.4 / 7.2)
  - **🕐 Historique** — existing `ReportHistoryPage` content (AC preserved from Story 7.2)
  - **📈 Rentabilité** — `ProductProfitabilityListPage` (new, this story)
  - **🏪 Boutiques** — `StorePerformancePage` (new, this story)
- **And** EMPLOYEE route `/reports` continues to show the existing day-close page (no tabs, no change to employee flow)
- **And** deep-link routes `/reports/rentabilite` and `/reports/boutiques` navigate to the correct tab when opened directly
- **And** the OWNER-tabbed reports page is a new `OwnerReportsPage` wrapping the tab controller; `ReportsPage` remains unchanged for EMPLOYEE use

### AC8 — Backend endpoints: product profitability

- **Given** the backend exposes profitability endpoints (OWNER only)
- **When** authenticated GET requests are made
- **Then**:
  - `GET /api/v1/reporting/profitability/products?from=YYYY-MM-DD&to=YYYY-MM-DD&sort=MARGIN_PCT_DESC&storeId=` → returns `List<ProductProfitabilityEntryDto>` (storeId optional, omit = all stores)
  - `GET /api/v1/reporting/profitability/products/{productId}?from=YYYY-MM-DD&to=YYYY-MM-DD` → returns `ProductProfitabilityDetailDto`
- **And** EMPLOYEE gets 403
- **And** all response bodies use `ApiResponseWrapper` format
- **And** when `from/to` span has no sales, empty list is returned (not 404)
- **And** `sort` values accepted: `MARGIN_PCT_DESC`, `MARGIN_XAF_DESC`, `CA_DESC`, `UNITS_DESC` (default: `MARGIN_PCT_DESC`)
- **And** No new DDL migration required — queries join existing `products`, `sale_items`, `sales` tables

### AC9 — Backend endpoints: store comparative performance

- **Given** the backend exposes store performance endpoint (OWNER only)
- **When** `GET /api/v1/reporting/profitability/stores?from=YYYY-MM-DD&to=YYYY-MM-DD&metric=CA` is called
- **Then** a ranked list `List<StorePerformanceEntryDto>` is returned with: storeId, storeName, totalRevenue, salesCount, averageBasket, topProductName, deltaPercent (vs same-length preceding period)
- **And** `metric` values: `CA` (default), `SALES_COUNT`, `AVG_BASKET`
- **And** EMPLOYEE gets 403

### AC10 — Offline-first behaviour

- **Given** the device is offline
- **When** Simon opens Rapports > Rentabilité or Boutiques
- **Then** data loads from local Drift database (no error state, no spinner)
- **And** an offline banner is shown if the last sync is > 24h ago
- **And** all period filters work against local data
- **And** when the device comes back online, the profitability providers automatically refresh (Riverpod invalidation on sync trigger — same pattern as Story 7.2 AC, driven by `syncTriggerNotifierProvider`)

### AC11 — TDD obligatoire: Tests RED → GREEN

#### Backend (JUnit 5)
- `ProfitabilityReportBuilderTest.java` — builder assembles correct entries from raw query results
- `ProfitabilitySortStrategyTest.java` — each strategy sorts correctly; factory returns correct instance
- `ProfitabilityServiceTest.java` — delegates to builder/strategy, date range computation
- `ProfitabilityControllerTest.java` — GET /products, GET /products/{id}, GET /stores; RBAC (403 EMPLOYEE, 401 no auth, 200 OWNER); empty period; unknown sort fallback
- `StoreRankingStrategyTest.java` — CA/SALES_COUNT/AVG_BASKET ranking + delta computation

#### Flutter (flutter_test)
- `product_profitability_model_test.dart` — model fields, margin level helpers, serialization
- `profitability_repository_impl_test.dart` — online (backend) + offline (Drift) path
- `profitability_providers_test.dart` — family provider: period change re-fetches; sort change re-sorts locally; loading/error states
- `product_profitability_list_page_test.dart` — renders rows, sort chips tap, period chip tap, empty state
- `product_profitability_detail_page_test.dart` — header, price range, sparkline presence, top store row
- `store_performance_page_test.dart` — ranked list, metric toggle, delta badge color

---

## Tasks / Subtasks

> **LOI TDD : Tous les tests RED sont écrits AVANT tout code de production. Le test doit échouer en premier.**

---

### Backend Tasks

#### Task 1 — TDD RED: Domain model + Strategy tests

- [ ] **1.1** Create `ProductProfitabilityEntryTest.java` in `reporting/profitability/domain/model/`
  ```java
  // Tests:
  // entry_withPositiveMargin_isNotLoss()
  // entry_withNegativeMargin_isLoss()
  // entry_marginPercent_computedFromTotalCostBase()  // (appliedRevenue - totalCost) / totalCost * 100
  // entry_zeroUnitsSold_marginPercentIsZero()
  // entry_zeroTotalCost_marginPercentIsZero()
  // entry_marginLevel_low_whenPercentLessThan10()
  // entry_marginLevel_moderate_whenPercent10to19()
  // entry_marginLevel_profitable_whenPercentAtLeast20()
  ```

- [ ] **1.2** Create `ProfitabilitySortStrategyTest.java` in `reporting/profitability/domain/service/`
  ```java
  // Tests per strategy:
  // MARGIN_PCT_DESC: sorts descending by marginPercent, losses last
  // MARGIN_XAF_DESC: sorts descending by grossMarginXaf
  // CA_DESC: sorts descending by totalRevenue
  // UNITS_DESC: sorts descending by unitsSold
  // factory_MARGIN_PCT_DESC_string_returnsCorrectStrategy()
  // factory_unknown_string_returnsDefaultMarginPctDescStrategy()
  ```

- [ ] **1.3** Create `StoreRankingStrategyTest.java` in `reporting/profitability/domain/service/`
  ```java
  // Tests:
  // CA_ranks_by_totalRevenue_descending()
  // SALES_COUNT_ranks_by_salesCount_descending()
  // AVG_BASKET_ranks_by_averageBasket_descending()
  ```

- [ ] **1.4** Run RED → confirm all tests fail (no production code yet)

---

#### Task 2 — TDD RED: Builder + Service tests

- [ ] **2.1** Create `ProfitabilityReportBuilderTest.java` in `reporting/profitability/application/service/`
  ```java
  // Tests:
  // builder_withSalesInPeriod_computesMarginPerProduct()
  //   — Given: productA (buyPrice=1000, transport=500, unitsSold=3, appliedPrice=5000)
  //   — Expected: totalRevenue=15000, totalCost=4500, marginXaf=10500, marginPct=233%
  // builder_withMultipleSaleItems_aggregatesCorrectly()
  // builder_productWithNoSales_notIncludedInList()
  // builder_archivedProduct_stillIncludedIfHadSales()
  // builder_lossProduct_flaggedAsLoss()
  // builder_emptyPeriod_returnsEmptyList()
  // builder_multiStore_aggregatesAcrossAllStores_whenStoreIdIsNull()
  // builder_singleStore_filtersBySingleStoreId()
  ```

- [ ] **2.2** Create `ProfitabilityServiceTest.java` in `reporting/profitability/application/service/`
  ```java
  // Tests (mock ProfitabilityRepository + ProfitabilityReportBuilder + StrategyFactory):
  // getProductProfitability_delegatesToBuilder_andAppliesSortStrategy()
  // getProductProfitability_emptyPeriod_returnsEmptyList()
  // getProductProfitabilityDetail_returnsDetailWithSparkline()
  // getProductProfitabilityDetail_unknownProduct_throwsDomainException()
  // getStorePerformance_delegatesToStoreRankingStrategy()
  // getStorePerformance_computesDeltaVsPrecedingEquivalentPeriod()
  ```

- [ ] **2.3** Run RED → confirm builder + service tests fail

---

#### Task 3 — TDD RED: Controller tests

- [ ] **3.1** Create `ProfitabilityControllerTest.java` (`@ExtendWith(MockitoExtension.class)` + `MockMvc`)
  ```java
  // Tests:
  // GET /api/v1/reporting/profitability/products → 200 (OWNER) — returns list with expected fields
  // GET /api/v1/reporting/profitability/products?sort=CA_DESC → 200 — applies sort param
  // GET /api/v1/reporting/profitability/products → 403 (EMPLOYEE role)
  // GET /api/v1/reporting/profitability/products → 401 (no auth)
  // GET /api/v1/reporting/profitability/products?from=2026-01-01&to=2026-01-31 → 200
  // GET /api/v1/reporting/profitability/products/{productId} → 200 — returns detail
  // GET /api/v1/reporting/profitability/products/{unknownId} → 404
  // GET /api/v1/reporting/profitability/stores → 200 (OWNER)
  // GET /api/v1/reporting/profitability/stores?metric=SALES_COUNT → 200
  // GET /api/v1/reporting/profitability/stores → 403 (EMPLOYEE)
  ```

- [ ] **3.2** Run RED → confirm controller tests fail

---

#### Task 4 — GREEN: Domain model implementation

- [ ] **4.1** Create `ProductProfitabilityEntry.java` in `reporting/profitability/domain/model/`
  ```java
  // package com.keevo.reporting.profitability.domain.model;
  // record or immutable class:
  // Fields: productId (UUID), productName (String), categoryName (String nullable),
  //         unitsSold (int), totalRevenue (long), totalCost (long), grossMarginXaf (long),
  //         marginPercent (double), isLoss (boolean), storeId (UUID nullable — null = all stores)
  // Computed: marginLevel (enum: LOSS, LOW, MODERATE, PROFITABLE — thresholds identical to PricingCalculatorWidget)
  //           LOW < 10%, MODERATE [10,20), PROFITABLE >= 20%
  ```

- [ ] **4.2** Create `ProductProfitabilityDetail.java` in `reporting/profitability/domain/model/`
  ```java
  // Fields: everything in ProductProfitabilityEntry, plus:
  //         currentCataloguePrice (int), currentBuyPrice (int), currentTransportCost (int),
  //         minAppliedPrice (int), maxAppliedPrice (int), avgAppliedPrice (double),
  //         dailyMarginLast7 (List<DailyMarginEntry>), topStoreId (UUID), topStoreName (String), topStoreUnitsSold (int)
  // Sub-record: DailyMarginEntry(date: String, marginXaf: long)
  ```

- [ ] **4.3** Create `StorePerformanceEntry.java` in `reporting/profitability/domain/model/`
  ```java
  // Fields: rank (int), storeId (UUID), storeName (String),
  //         totalRevenue (long), salesCount (int), averageBasket (long),
  //         topProductName (String), deltaPercent (double)
  //         — deltaPercent: positive = improvement vs preceding period, negative = decline
  ```

- [ ] **4.4** Create `SortOption.java` enum in `reporting/profitability/domain/model/`
  ```java
  // Values: MARGIN_PCT_DESC, MARGIN_XAF_DESC, CA_DESC, UNITS_DESC
  // Static fromString(String s): case-insensitive match, returns MARGIN_PCT_DESC if unknown
  ```

- [ ] **4.5** Create `RankingMetric.java` enum: `CA`, `SALES_COUNT`, `AVG_BASKET`

- [ ] **4.6** Run GREEN → domain model tests pass

---

#### Task 5 — GREEN: Strategy implementations

- [ ] **5.1** Create `ProfitabilitySortStrategy.java` interface in `reporting/profitability/domain/service/`
  ```java
  // Functional interface: List<ProductProfitabilityEntry> sort(List<ProductProfitabilityEntry> entries)
  ```

- [ ] **5.2** Create `ByMarginPctDescStrategy.java` — sorts descending by marginPercent; LOSS entries last
- [ ] **5.3** Create `ByMarginXafDescStrategy.java` — sorts descending by grossMarginXaf
- [ ] **5.4** Create `ByCaDescStrategy.java` — sorts descending by totalRevenue
- [ ] **5.5** Create `ByUnitsDescStrategy.java` — sorts descending by unitsSold
- [ ] **5.6** Create `ProfitabilitySortStrategyFactory.java` — `of(SortOption)` → returns correct strategy (Factory Method GoF)
- [ ] **5.7** Create `StoreRankingStrategy.java` interface; create `ByCaStrategy`, `BySalesCountStrategy`, `ByAvgBasketStrategy` impls; create `StoreRankingStrategyFactory.java`
- [ ] **5.8** Run GREEN → strategy + factory tests pass

---

#### Task 6 — GREEN: Ports (hexagonal interfaces)

- [ ] **6.1** Create `GetProductProfitabilityUseCase.java` in `reporting/profitability/domain/port/in/`
  ```java
  // interface GetProductProfitabilityUseCase {
  //   record ProfitabilityQuery(String tenantId, LocalDate from, LocalDate to,
  //                              SortOption sort, UUID storeId /* nullable */) {}
  //   List<ProductProfitabilityEntry> getEntries(ProfitabilityQuery query);
  //   ProductProfitabilityDetail getDetail(ProfitabilityQuery query, UUID productId);
  // }
  ```

- [ ] **6.2** Create `GetStorePerformanceUseCase.java` in `reporting/profitability/domain/port/in/`
  ```java
  // interface GetStorePerformanceUseCase {
  //   record StorePerformanceQuery(String tenantId, LocalDate from, LocalDate to, RankingMetric metric) {}
  //   List<StorePerformanceEntry> getRanking(StorePerformanceQuery query);
  // }
  ```

- [ ] **6.3** Create `ProfitabilityRepository.java` port-out in `reporting/profitability/domain/port/out/`
  ```java
  // interface ProfitabilityRepository {
  //   List<RawProfitabilityRow> findRawByPeriod(String tenantId, LocalDate from, LocalDate to, UUID storeId);
  //   List<RawStorePerformanceRow> findRawStoreByPeriod(String tenantId, LocalDate from, LocalDate to);
  //   Optional<RawProductCostRow> findProductCosts(UUID productId);
  //   List<RawDailyMarginRow> findDailyMarginLast7(String tenantId, UUID productId, LocalDate endDate);
  // }
  // — value objects: RawProfitabilityRow, RawStorePerformanceRow, RawProductCostRow, RawDailyMarginRow (records)
  ```

---

#### Task 7 — GREEN: Builder + Service implementation

- [ ] **7.1** Create `ProfitabilityReportBuilder.java` in `reporting/profitability/application/service/`
  ```java
  // @Component
  // Injected: ProfitabilityRepository
  // Method buildEntries(query): calls repo.findRawByPeriod() → maps to ProductProfitabilityEntry list
  // Method buildDetail(query, productId): calls repo entries + findDailyMarginLast7 + findProductCosts
  // Margin formula (MUST match PricingCalculator.java):
  //   totalCost = (buyPrice + transportCost) × unitsSold   (per sale_item row, sum across period)
  //   totalRevenue = SUM(applied_unit_price × quantity)
  //   grossMarginXaf = totalRevenue - totalCost
  //   marginPercent = totalCost == 0 ? 0.0 : (grossMarginXaf / totalCost) * 100.0
  ```

- [ ] **7.2** Create `ProfitabilityService.java` in `reporting/profitability/application/service/`
  ```java
  // @Service / implements GetProductProfitabilityUseCase, GetStorePerformanceUseCase
  // getEntries: calls builder → applies ProfitabilitySortStrategyFactory.of(sort).sort(entries)
  // getDetail: delegates to builder
  // getRanking: calls repo.findRawStoreByPeriod() → applies StoreRankingStrategyFactory.of(metric).rank()
  //             computes delta: runs equivalent preceding period query (from - period_length, to - period_length)
  ```

- [ ] **7.3** Run GREEN → builder + service tests pass

---

#### Task 8 — GREEN: Persistence adapter (JdbcTemplate)

- [ ] **8.1** Create `JdbcProfitabilityRepository.java` in `reporting/profitability/adapter/out/persistence/`
  ```java
  // @Repository / implements ProfitabilityRepository
  // Uses JdbcTemplate (same pattern as DashboardService)
  // SQL for findRawByPeriod:
  //   SELECT
  //     si.product_id,
  //     p.name                                  AS product_name,
  //     c.name                                  AS category_name,
  //     p.buy_price, p.transport_cost,
  //     SUM(si.quantity)                        AS units_sold,
  //     SUM(si.applied_unit_price * si.quantity) AS total_revenue,
  //     SUM((p.buy_price + p.transport_cost) * si.quantity) AS total_cost
  //   FROM sale_items si
  //   JOIN sales s ON s.id = si.sale_id
  //   JOIN products p ON p.id = si.product_id
  //   LEFT JOIN categories c ON c.id = p.category_id
  //   WHERE s.status = 'COMPLETED'
  //     AND s.occurred_at >= ?  AND s.occurred_at < ?
  //     [AND s.store_id = ?]  -- only if storeId != null
  //   GROUP BY si.product_id, p.name, c.name, p.buy_price, p.transport_cost
  //
  // SQL for findDailyMarginLast7:
  //   SELECT
  //     DATE(s.occurred_at)                                     AS day,
  //     SUM((si.applied_unit_price - p.buy_price - p.transport_cost) * si.quantity) AS daily_margin
  //   FROM sale_items si
  //   JOIN sales s ON s.id = si.sale_id
  //   JOIN products p ON p.id = si.product_id
  //   WHERE s.status = 'COMPLETED'
  //     AND si.product_id = ?
  //     AND s.occurred_at >= ?  AND s.occurred_at < ?
  //   GROUP BY DATE(s.occurred_at)
  //   ORDER BY day ASC
  //
  // SQL for findRawStoreByPeriod:
  //   SELECT
  //     s.store_id,
  //     st.name         AS store_name,
  //     SUM(s.total_amount)               AS total_revenue,
  //     COUNT(s.id)                       AS sales_count,
  //     SUM(s.total_amount) / NULLIF(COUNT(s.id),0) AS avg_basket,
  //     (SELECT si2.product_name
  //      FROM sale_items si2 JOIN sales s2 ON s2.id = si2.sale_id
  //      WHERE s2.store_id = s.store_id AND s2.status='COMPLETED'
  //        AND s2.occurred_at >= ? AND s2.occurred_at < ?
  //      GROUP BY si2.product_name ORDER BY SUM(si2.quantity) DESC LIMIT 1
  //     ) AS top_product
  //   FROM sales s
  //   JOIN stores st ON st.id = s.store_id
  //   WHERE s.status = 'COMPLETED'
  //     AND s.occurred_at >= ?  AND s.occurred_at < ?
  //   GROUP BY s.store_id, st.name
  //
  // SQL for findProductCosts (current catalogue values):
  //   SELECT p.price, p.buy_price, p.transport_cost, MIN(si.applied_unit_price), MAX(si.applied_unit_price),
  //          AVG(si.applied_unit_price::float)
  //   FROM products p
  //   JOIN sale_items si ON si.product_id = p.id
  //   WHERE p.id = ?
  //   GROUP BY p.price, p.buy_price, p.transport_cost
  ```

---

#### Task 9 — GREEN: REST controller (adapter in)

- [ ] **9.1** Create `ProfitabilityController.java` in `reporting/profitability/adapter/in/rest/`
  ```java
  // @RestController @RequestMapping("/api/v1/reporting/profitability") @PreAuthorize("hasRole('OWNER')")
  //
  // GET /products
  //   @RequestParam(required=false) String from   -- default: 30 days ago
  //   @RequestParam(required=false) String to     -- default: today
  //   @RequestParam(required=false, defaultValue="MARGIN_PCT_DESC") String sort
  //   @RequestParam(required=false) UUID storeId
  //   → parse from/to as LocalDate (ISO 8601), resolve via SortOption.fromString(sort)
  //   → GetProductProfitabilityUseCase.getEntries(query)
  //   → return ApiResponseWrapper.ok(List<ProductProfitabilityEntryDto>)
  //
  // GET /products/{productId}
  //   @PathVariable UUID productId
  //   @RequestParam(required=false) String from, to
  //   → GetProductProfitabilityUseCase.getDetail(query, productId)
  //   → return ApiResponseWrapper.ok(ProductProfitabilityDetailDto)
  //   → DomainException(PRODUCT_NOT_FOUND) → 404 via GlobalExceptionHandler
  //
  // GET /stores
  //   @RequestParam(required=false) String from, to
  //   @RequestParam(required=false, defaultValue="CA") String metric
  //   → GetStorePerformanceUseCase.getRanking(query)
  //   → return ApiResponseWrapper.ok(List<StorePerformanceEntryDto>)
  ```

- [ ] **9.2** Create DTOs in `reporting/profitability/adapter/in/rest/dto/`:
  - `ProductProfitabilityEntryDto` — `from(ProductProfitabilityEntry)` static factory
  - `ProductProfitabilityDetailDto` — `from(ProductProfitabilityDetail)` static factory
  - `StorePerformanceEntryDto` — `from(StorePerformanceEntry)` static factory

- [ ] **9.3** Run GREEN → controller tests pass

---

#### Task 10 — Spring wiring

- [ ] **10.1** Annotate `ProfitabilityService` with `@Service`, ensure `@Transactional(readOnly=true)`
- [ ] **10.2** Verify `JdbcProfitabilityRepository` is a Spring `@Repository` bean
- [ ] **10.3** Confirm no new `@SpringBootApplication` or config needed — `JdbcTemplate` is already a bean (used by `DashboardService`)
- [ ] **10.4** Run full backend test suite → **all existing tests + new tests GREEN; zero regressions**

---

#### Task 11 — cURL Integration Tests (iterative, run until all GREEN)

> Run Docker Compose first: `cd keevo && docker-compose up -d`
> Backend starts on `http://localhost:8080`

**Iteration 1 — Obtain auth token:**
```bash
# Step 1: Login
TOKEN_RESP=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"phoneNumber":"+22500000000","password":"password123"}')
echo $TOKEN_RESP | python3 -m json.tool
PRE_TOKEN=$(echo $TOKEN_RESP | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")

# Step 2: Select tenant (owner)
TENANT_RESP=$(curl -s -X POST http://localhost:8080/api/v1/auth/select-tenant \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $PRE_TOKEN" \
  -d '{"tenantId":"kv_test_owner"}')
echo $TENANT_RESP | python3 -m json.tool
TOKEN=$(echo $TENANT_RESP | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")
echo "OWNER TOKEN: $TOKEN"
```

**Iteration 2 — Profitability: empty period (no sales yet):**
```bash
curl -s "http://localhost:8080/api/v1/reporting/profitability/products?from=2020-01-01&to=2020-01-31" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
# Expected: {"success":true,"data":[]} — empty list, 200 OK
```

**Iteration 3 — Register a sale via POS to have data:**
```bash
# (Assumes products already exist from earlier seeds — use actual product IDs from GET /api/v1/products)
PRODUCTS=$(curl -s "http://localhost:8080/api/v1/products?page=0&size=3" \
  -H "Authorization: Bearer $TOKEN")
echo $PRODUCTS | python3 -m json.tool
P1=$(echo $PRODUCTS | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['content'][0]['id'])")
P2=$(echo $PRODUCTS | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['content'][1]['id'])")

# Get store ID
STORES=$(curl -s "http://localhost:8080/api/v1/stores" -H "Authorization: Bearer $TOKEN")
STORE_ID=$(echo $STORES | python3 -c "import sys,json; print(json.load(sys.stdin)['data'][0]['id'])")

# Create a completed sale
SALE_RESP=$(curl -s -X POST http://localhost:8080/api/v1/pos/sales \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d "{\"storeId\":\"$STORE_ID\",\"items\":[{\"productId\":\"$P1\",\"quantity\":2},{\"productId\":\"$P2\",\"quantity\":3}],\"paymentMode\":\"CASH\"}")
echo $SALE_RESP | python3 -m json.tool
```

**Iteration 4 — Profitability list with data, default sort:**
```bash
TODAY=$(date +%Y-%m-%d)
curl -s "http://localhost:8080/api/v1/reporting/profitability/products?from=2026-01-01&to=$TODAY" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
# Expected:
# - 200 OK
# - data = array of entries with: productId, productName, unitsSold, totalRevenue, totalCost, grossMarginXaf, marginPercent, isLoss
# - Sorted by marginPercent descending (default MARGIN_PCT_DESC)
# - Verify: totalCost == (buyPrice + transportCost) * unitsSold for each product
```

**Iteration 5 — Profitability list: alternative sort options:**
```bash
for SORT in "MARGIN_PCT_DESC" "MARGIN_XAF_DESC" "CA_DESC" "UNITS_DESC"; do
  echo "=== Sort: $SORT ==="
  curl -s "http://localhost:8080/api/v1/reporting/profitability/products?from=2026-01-01&to=$TODAY&sort=$SORT" \
    -H "Authorization: Bearer $TOKEN" | python3 -c "import sys,json; data=json.load(sys.stdin)['data']; [print(f'{e[\"productName\"]:30s} margin={e[\"marginPercent\"]:.1f}% units={e[\"unitsSold\"]}') for e in data]"
done
```

**Iteration 6 — Product detail:**
```bash
curl -s "http://localhost:8080/api/v1/reporting/profitability/products/$P1?from=2026-01-01&to=$TODAY" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
# Expected fields: productId, productName, unitsSold, totalRevenue, totalCost, grossMarginXaf, marginPercent,
#                  currentCataloguePrice, currentBuyPrice, currentTransportCost,
#                  minAppliedPrice, maxAppliedPrice, avgAppliedPrice,
#                  dailyMarginLast7 (list of {date, marginXaf}),
#                  topStoreName, topStoreUnitsSold
```

**Iteration 7 — Product detail: unknown productId → 404:**
```bash
curl -s "http://localhost:8080/api/v1/reporting/profitability/products/00000000-0000-0000-0000-000000000000?from=2026-01-01&to=$TODAY" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
# Expected: 404, {"success":false,"error":{"code":"PRODUCT_NOT_FOUND",...}}
```

**Iteration 8 — Store performance:**
```bash
curl -s "http://localhost:8080/api/v1/reporting/profitability/stores?from=2026-01-01&to=$TODAY" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
# Expected: list of {rank, storeName, totalRevenue, salesCount, averageBasket, topProductName, deltaPercent}
# All stores the tenant owns, ranked by CA descending

for METRIC in "CA" "SALES_COUNT" "AVG_BASKET"; do
  echo "=== Metric: $METRIC ==="
  curl -s "http://localhost:8080/api/v1/reporting/profitability/stores?from=2026-01-01&to=$TODAY&metric=$METRIC" \
    -H "Authorization: Bearer $TOKEN" | python3 -c "import sys,json; data=json.load(sys.stdin)['data']; [print(f'#{e[\"rank\"]} {e[\"storeName\"]:20s} CA={e[\"totalRevenue\"]} delta={e[\"deltaPercent\"]:.1f}%') for e in data]"
done
```

**Iteration 9 — RBAC: employee token → 403:**
```bash
# Login as employee
EMP_TOKEN_RESP=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"phoneNumber":"+22500000001","password":"password123"}')
EMP_PRE=$(echo $EMP_TOKEN_RESP | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")
EMP_TENANT_RESP=$(curl -s -X POST http://localhost:8080/api/v1/auth/select-tenant \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $EMP_PRE" \
  -d '{"tenantId":"kv_test_owner"}')
EMP_TOKEN=$(echo $EMP_TENANT_RESP | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")

curl -s "http://localhost:8080/api/v1/reporting/profitability/products?from=2026-01-01&to=$TODAY" \
  -H "Authorization: Bearer $EMP_TOKEN" | python3 -m json.tool
# Expected: 403 Forbidden

curl -s "http://localhost:8080/api/v1/reporting/profitability/stores?from=2026-01-01&to=$TODAY" \
  -H "Authorization: Bearer $EMP_TOKEN" | python3 -m json.tool
# Expected: 403 Forbidden
```

**Iteration 10 — storeId filter:**
```bash
curl -s "http://localhost:8080/api/v1/reporting/profitability/products?from=2026-01-01&to=$TODAY&storeId=$STORE_ID" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
# Expected: same results as no storeId if single store; subset if multi-store
```

---

### Flutter Tasks

#### Task 12 — TDD RED: Flutter model tests

- [ ] **12.1** Create `test/features/profitability/domain/model/product_profitability_model_test.dart`
  ```dart
  // Tests:
  // fromJson_parsesAllFields()
  // marginLevel_returnsLoss_whenNegativeMargin()
  // marginLevel_returnsLow_whenBelow10Percent()
  // marginLevel_returnsModerate_when10to19Percent()
  // marginLevel_returnsProfitable_when20PlusPercent()
  // marginLevel_color_matchesPricingCalculatorWidget()  // red/orange/green
  // productProfitabilityDetail_fromJson_includesDailySparkline()
  // storePerformanceEntry_fromJson_includesRankAndDelta()
  ```

- [ ] **12.2** Run RED → confirm all model tests fail

---

#### Task 13 — TDD RED: Provider + repository tests

- [ ] **13.1** Create `test/features/profitability/presentation/provider/profitability_providers_test.dart`
  ```dart
  // Tests (using ProviderContainer overrides + Mock datasources):
  // productProfitabilityProvider_loadsFromLocalDrift_whenOffline()
  // productProfitabilityProvider_loadsFromRemote_whenOnline_andCachesLocally()
  // productProfitabilityProvider_changedPeriod_triggersRefetch()
  // productProfitabilityProvider_sortChange_resortsList()
  // storePerformanceProvider_loadsRankedList_withDeltaPercent()
  ```

- [ ] **13.2** Create `test/features/profitability/presentation/page/product_profitability_list_page_test.dart`
  ```dart
  // Tests:
  // rendersProductRows_withMarginColorBadge()
  // tapSortChip_MARGIN_XAF_DESC_resortsList()
  // tapPeriodChip_7days_refreshesData()
  // tapProductRow_navigatesToDetailRoute()
  // emptyState_showed_whenNoSalesInPeriod()
  // Note: EMPLOYEE guard is router-level (_ownerOnlyPrefixes in app_router.dart) — no widget-level test needed
  ```

- [ ] **13.3** Create `test/features/profitability/presentation/page/product_profitability_detail_page_test.dart`
  ```dart
  // Tests:
  // rendersHeader_withProductNameAndCurrentPrices()
  // rendersPriceRangeSection_withMinMaxAvg()
  // rendersSparkline_withLast7DaysData()
  // rendersTopStoreRow_whenMultiStore()
  // rendersTopStoreRow_absent_whenSingleStore() -- or shows single store name
  // backButton_popsRoute()
  ```

- [ ] **13.4** Create `test/features/profitability/presentation/page/store_performance_page_test.dart`
  ```dart
  // Tests:
  // rendersRankedStoreList_withRankBadge()
  // rendersPositiveDelta_inGreen()
  // rendersNegativeDelta_inRed()
  // tapMetricToggle_SALES_COUNT_reranks()
  // tapPeriodChip_30days_refreshesList()
  ```

- [ ] **13.5** Run RED → confirm all Flutter tests fail

---

#### Task 14 — GREEN: Flutter domain model

- [ ] **14.1** Create `lib/features/profitability/domain/model/product_profitability_model.dart`
  ```dart
  // Uses @freezed + JSON serialization (same pattern as report_history_model.dart)
  // class ProductProfitabilityEntry with margin level enum helper
  // class ProductProfitabilityDetail extends (or has-a) ProductProfitabilityEntry
  // class DailyMarginEntry {date: String, marginXaf: int}
  // class StorePerformanceEntry
  //
  // Margin level helper (MUST match PricingCalculatorWidget):
  //   if (marginPercent < 0 || marginPercent < 10) → MarginLevel.lowOrLoss → Colors.red
  //   if (marginPercent < 20) → MarginLevel.moderate → Colors.orange
  //   else → MarginLevel.profitable → Colors.green
  ```

- [ ] **14.2** Run codegen: `flutter pub run build_runner build --delete-conflicting-outputs`
- [ ] **14.3** Run GREEN → model tests pass

---

#### Task 15 — GREEN: Data layer (datasource + repository)

- [ ] **15.1** Create `lib/features/profitability/data/datasource/remote_profitability_datasource.dart`
  ```dart
  // GET /api/v1/reporting/profitability/products
  // GET /api/v1/reporting/profitability/products/{productId}
  // GET /api/v1/reporting/profitability/stores
  // Uses ApiClient (same pattern as RemoteReportHistoryDatasource)
  ```

- [ ] **15.2** Create `lib/features/profitability/data/datasource/local_profitability_datasource.dart`
  ```dart
  // Drift queries against existing tables: sales, sale_items, products, categories, stores
  // JOIN pattern mirrors backend JdbcProfitabilityRepository SQL
  // Period filter: WHERE s.occurred_at >= :from AND s.occurred_at < :to AND s.status = 'completed'
  // Returns same model shape as remote datasource
  ```

- [ ] **15.3** Create `lib/features/profitability/domain/repository/profitability_repository.dart` (abstract)
- [ ] **15.4** Create `lib/features/profitability/data/repository/profitability_repository_impl.dart`
  ```dart
  // Backend-first-when-online: tries remote first (via connectivityProvider), falls back to local
  // After remote success: NO write-back to local (read-only reporting — local Drift is already the source of truth via sync engine)
  ```

---

#### Task 16 — GREEN: Riverpod providers

- [ ] **16.1** Create `lib/features/profitability/presentation/provider/profitability_providers.dart`
  ```dart
  // ProfitabilityParams class: {from: DateTime, to: DateTime, sort: SortOption, storeId: String?}
  // StorePerformanceParams class: {from: DateTime, to: DateTime, metric: RankingMetric}
  //
  // productProfitabilityProvider(ProfitabilityParams params) → FutureProvider.family
  //   Reads local (Drift) or remote based on connectivity
  //   After data: applies sort locally (Dart-side Strategy mirror of backend Strategy pattern)
  //
  // productProfitabilityDetailProvider(productId, ProfitabilityParams) → FutureProvider.family
  //
  // storePerformanceProvider(StorePerformanceParams) → FutureProvider.family
  //
  // selectedPeriodProvider → StateProvider<ProfitabilityPeriod> (shared between rentabilité + boutiques tabs)
  // selectedSortProvider → StateProvider<SortOption> (default MARGIN_PCT_DESC)
  // selectedMetricProvider → StateProvider<RankingMetric> (default CA)
  ```

- [ ] **16.2** Run codegen for generated providers (`.g.dart`)

---

#### Task 17 — GREEN: Presentation layer — widgets + pages

- [ ] **17.1** Create `lib/features/profitability/presentation/widget/profitability_row.dart`
  ```dart
  // ProductProfitabilityRow: ListTile-style widget
  // Left: product name + category (small text)
  // Center: units sold + revenue (XAF)
  // Right: marginPercent colored badge (same style as _Badge in PricingCalculatorWidget)
  //        grossMarginXaf below badge in small text
  // isLoss flag: show red "PERTE" chip with warning icon
  ```

- [ ] **17.2** Create `lib/features/profitability/presentation/widget/period_filter_chips.dart`
  ```dart
  // Row of ChoiceChips: Aujourd'hui | 7 jours | 30 jours | Personnalisé
  // Tapping "Personnalisé" opens showDateRangePicker with French locale
  // Emits onPeriodChanged(DateTime from, DateTime to) callback
  // Consistent appearance with MetricBadgeCard style (AppTheme.primary chip variant)
  ```

- [ ] **17.3** Create `lib/features/profitability/presentation/widget/sort_chips.dart`
  ```dart
  // Row of FilterChips: Marge % | Marge XAF | CA | Unités
  // Active chip: filled with AppTheme.primary, white text
  // Inactive chip: outlined
  // Onchanged: updates selectedSortProvider
  ```

- [ ] **17.4** Create `lib/features/profitability/presentation/widget/store_performance_row.dart`
  ```dart
  // Rank badge (#1 gold, #2 silver, #3 bronze using BoxDecoration)
  // Store name (bold), CA/metric value (XAF), delta badge (+X% green / -X% red)
  // Top product name (small italic)
  ```

- [ ] **17.5** Create `lib/features/profitability/presentation/widget/margin_sparkline.dart`
  ```dart
  // Simple bar chart (CustomPainter or fl_chart if already a dep)
  // 7 bars, each bar height ∝ daily marginXaf
  // Bars colored with margin level color (green/orange/red based on daily avg)
  // No axes labels, just bars + tooltip on long-press
  // Consistent with SalesEvolutionChart (Story 7.1) style
  ```

- [ ] **17.6** Create `lib/features/profitability/presentation/page/product_profitability_list_page.dart`
  ```dart
  // SliverAppBar (gradient, "Rentabilité Produits")
  // PeriodFilterChips + SortChips (stacked in SliverPersistentHeader)
  // SliverList of ProductProfitabilityRow
  // Empty state: Icon + "Aucune vente sur cette période"
  // Tap → context.push('/reports/rentabilite/:productId', extra: entry)
  // OWNER-only — guarded at router level via _ownerOnlyPrefixes in app_router.dart; no in-widget check needed
  ```

- [ ] **17.7** Create `lib/features/profitability/presentation/page/product_profitability_detail_page.dart`
  ```dart
  // AppBar: product name, back arrow
  // Header card: current catalogue price / buy price / transport cost (3 columns)
  // Metrics card: units sold, total revenue, total cost, gross margin XAF + % (colored)
  // Price range section: "Prix appliqués : min/max/avg FCFA — [override indicator if min != max]"
  // Margin trend: MarginSparkline (last 7 days)
  // Top store row (if multi-store): "Meilleure boutique : [storeName] — [N] unités"
  ```

- [ ] **17.8** Create `lib/features/profitability/presentation/page/store_performance_page.dart`
  ```dart
  // SliverAppBar (gradient, "Performances Boutiques")
  // PeriodFilterChips + metric toggle row (SegmentedButton or ChoiceChips: CA / Ventes / Panier)
  // SliverList of StorePerformanceRow
  // Empty state: "Aucune boutique active"
  ```

---

#### Task 18 — GREEN: OwnerReportsPage + Navigation wiring

- [ ] **18.1** Create `lib/features/reports/presentation/page/owner_reports_page.dart`
  ```dart
  // DefaultTabController(length: 4)
  // TabBar: 📋 Jour | 🕐 Historique | 📈 Rentabilité | 🏪 Boutiques
  // TabBarView:
  //   [0] ReportsPage() // existing, unchanged
  //   [1] ReportHistoryPage() // existing, unchanged
  //   [2] ProductProfitabilityListPage()
  //   [3] StorePerformancePage()
  // AppBar includes the TabBar (pinned)
  ```

- [ ] **18.2** Update `app_router.dart`:
  - Change the OWNER-path `/reports` builder from `ReportsPage()` to `OwnerReportsPage()`
  - Keep EMPLOYEE `/reports` pointing to `ReportsPage()` (no change — EMPLOYEE bottom nav routes here; `/reports/rentabilite*` and `/reports/boutiques*` are in `_ownerOnlyPrefixes` so any EMPLOYEE direct navigation attempt is silently redirected to `/pos`)
  - Add route `/reports/rentabilite/:productId` → `ProductProfitabilityDetailPage`
  - Import new pages

- [ ] **18.3** Add `'/reports/rentabilite'` and `'/reports/boutiques'` to the `_ownerOnlyPrefixes` const in `app_router.dart` — EMPLOYEE attempting direct URL navigation to these subroutes is silently redirected to `/pos` (GoRouter redirect, no in-widget guard needed)
- [ ] **18.4** Run GREEN → Flutter tests pass

---

#### Task 19 — Integration smoke test (manual or widget test)

- [ ] **19.1** Run Flutter app on emulator: login as OWNER → Rapports → verify 4 tabs visible
- [ ] **19.2** Navigate to "Rentabilité" tab → verify list loads (or empty state if no sales)
- [ ] **19.3** Tap a product → verify detail page opens with correct data
- [ ] **19.4** Back → verify list scroll position preserved
- [ ] **19.5** Tap "Boutiques" tab → verify store list with rank/delta
- [ ] **19.6** Login as EMPLOYEE → Rapports → verify no tabs (single day-close view)

---

#### Task 20 — Final validation

- [ ] **20.1** Run full backend test suite: `./mvnw test` → all tests GREEN, zero regressions
- [ ] **20.2** Run Flutter tests: `flutter test` → all tests GREEN
- [ ] **20.3** Update `sprint-status.yaml`: `7-4-dashboard-rentabilite-analyse-par-produit-boutique: ready-for-dev` → `in-progress` (Dev agent updates to `done` after completion)

---

## Dev Notes

### Architecture Constraints

- **NO new DDL migration required** — This story reads only. All data is in existing tables: `products` (buy_price, transport_cost, price), `sale_items` (applied_unit_price, catalogue_unit_price, quantity, product_id), `sales` (occurred_at, store_id, status). [Source: TenantSchemaProvisioner.java]
- All backend queries use `JdbcTemplate` (same pattern as `DashboardService.java`) — no new JPA entities. [Source: architecture.md#Backend Architecture]
- Tenant isolation: all queries run within the current tenant schema context (set by `TenantJwtFilter`) — NO explicit `tenantId` column filter needed in SQL. [Source: architecture.md#Multi-Tenant]
- `@PreAuthorize("hasRole('OWNER')")` on all `ProfitabilityController` endpoints — EMPLOYEE gets 403. [Source: DashboardController.java]
- Margin formula MUST match `PricingCalculator.java`: `grossMarginXaf = sellingPrice - (buyPrice + transportCost)`; `marginPercent = grossMarginXaf / totalCost * 100` where `totalCost = buyPrice + transportCost`. [Source: catalog/product/domain/service/PricingCalculator.java]

### Flutter Architecture

- **Offline-first**: profitability data is computed from local Drift tables (sales, sale_items, products). Remote call only needed when Drift is empty or data is stale. Pattern: same as `LocalDashboardDatasource`. [Source: dashboard/data/datasource/local_dashboard_datasource.dart]
- **Riverpod family providers** with `ProfitabilityParams` as key: period/sort change = new params key = auto-refetch. Pattern from `report_history_providers.dart`.
- **Sort applied client-side** after data load (both local and remote). This matches the Strategy pattern in backend and avoids redundant network calls on sort toggle.
- **Color coding** for margin %: MUST match `PricingCalculatorWidget` exactly: red = loss OR < 10%, orange = [10,20)%, green = ≥ 20%. [Source: catalog/presentation/widget/pricing_calculator_widget.dart lines 36-41, 249-268]
- **Navigation**: Use `context.push` (not `context.go`) for detail pages to preserve back-stack. [Source: app_router.dart pattern from StoreDashboardPage]

### Backend Module Location

```
src/main/java/com/keevo/reporting/
├── dashboard/        # Story 7.1 — existing, untouched
├── report/           # Story 7.2 — existing, untouched
└── profitability/    # NEW — Story 7.4
    ├── domain/
    │   ├── model/
    │   │   ├── ProductProfitabilityEntry.java
    │   │   ├── ProductProfitabilityDetail.java
    │   │   ├── StorePerformanceEntry.java
    │   │   ├── SortOption.java
    │   │   └── RankingMetric.java
    │   ├── port/
    │   │   ├── in/
    │   │   │   ├── GetProductProfitabilityUseCase.java
    │   │   │   └── GetStorePerformanceUseCase.java
    │   │   └── out/
    │   │       └── ProfitabilityRepository.java
    │   └── service/
    │       ├── ProfitabilitySortStrategy.java
    │       ├── ByMarginPctDescStrategy.java
    │       ├── ByMarginXafDescStrategy.java
    │       ├── ByCaDescStrategy.java
    │       ├── ByUnitsDescStrategy.java
    │       ├── ProfitabilitySortStrategyFactory.java
    │       ├── StoreRankingStrategy.java
    │       └── StoreRankingStrategyFactory.java
    ├── application/
    │   └── service/
    │       ├── ProfitabilityReportBuilder.java
    │       └── ProfitabilityService.java
    └── adapter/
        ├── in/rest/
        │   ├── ProfitabilityController.java
        │   └── dto/
        │       ├── ProductProfitabilityEntryDto.java
        │       ├── ProductProfitabilityDetailDto.java
        │       └── StorePerformanceEntryDto.java
        └── out/persistence/
            └── JdbcProfitabilityRepository.java
```

### Flutter Feature Location

```
lib/features/profitability/
├── domain/
│   ├── model/
│   │   └── product_profitability_model.dart   (+ .freezed.dart + .g.dart)
│   └── repository/
│       └── profitability_repository.dart
├── data/
│   ├── datasource/
│   │   ├── remote_profitability_datasource.dart
│   │   └── local_profitability_datasource.dart
│   └── repository/
│       └── profitability_repository_impl.dart
└── presentation/
    ├── provider/
    │   └── profitability_providers.dart (+ .g.dart)
    ├── page/
    │   ├── product_profitability_list_page.dart
    │   ├── product_profitability_detail_page.dart
    │   └── store_performance_page.dart
    └── widget/
        ├── profitability_row.dart
        ├── period_filter_chips.dart
        ├── sort_chips.dart
        ├── store_performance_row.dart
        └── margin_sparkline.dart
```

**Modified existing files:**
- `lib/features/reports/presentation/page/owner_reports_page.dart` — NEW (wraps existing pages in TabController)
- `lib/core/router/app_router.dart` — add OWNER /reports → OwnerReportsPage, add /reports/rentabilite/:productId route
- `lib/core/di/providers.dart` — register new profitabilityRepository provider

### Key SQL Invariants

- Always filter `s.status = 'COMPLETED'` — exclude CANCELLED and PENDING_VALIDATION sales
- Use `s.occurred_at` (TIMESTAMPTZ) for date range: `>= start` (inclusive) and `< end+1day` (exclusive), or `BETWEEN` cast to date
- `sale_items.applied_unit_price` = actual price charged (may differ from catalogue if override applied in Story 4.2)
- `products.buy_price` and `products.transport_cost` = **current** catalogue values (not historical). This is intentional — margin analysis uses current cost structure to flag products now unprofitable.

### Testing Standards

- Backend: `@ExtendWith(MockitoExtension.class)` + `MockMvc.standaloneSetup()` for controller; plain JUnit 5 (no Spring context) for strategy/builder/service tests
- Flutter: `flutter_test` with `ProviderContainer` + mock datasources (no real Drift or HTTP)
- TDD: ALL test files created before production code (Task 1-3 red, Task 4 onwards green)

### References

- [Source: architecture.md#Backend Architecture — hexagonal structure]
- [Source: reporting/dashboard/application/service/DashboardService.java — JdbcTemplate pattern]
- [Source: reporting/dashboard/adapter/in/rest/DashboardController.java — @PreAuthorize OWNER pattern]
- [Source: catalog/product/domain/service/PricingCalculator.java — margin formula]
- [Source: catalog/presentation/widget/pricing_calculator_widget.dart — margin color thresholds]
- [Source: shared/infrastructure/persistence/TenantSchemaProvisioner.java — products + sale_items + sales schema]
- [Source: _bmad-output/planning-artifacts/epics/epic-7-rapports-dashboard-communication-whatsapp.md#Story 7.4]
- [Source: _bmad-output/planning-artifacts/prd.md — FR54, FR56]
- [Source: _bmad-output/implementation-artifacts/7-1-dashboard-matinal-message-motivationnel.md — nav/widget patterns]
- [Source: _bmad-output/implementation-artifacts/7-2-rapport-end-of-day-generation-envoi-whatsapp.md — TDD task structure, GoF patterns]

---

## Dev Agent Record

### Agent Model Used

Claude Sonnet 4.6 (GitHub Copilot)

### Debug Log References

- SQL fix: `si.unit_price` → `si.applied_unit_price` in JdbcProfitabilityRepository (PostgreSQL schema uses `applied_unit_price`)
- Mockito stub order: general stub must be added BEFORE specific stub in ProfitabilityServiceTest (last-matched-wins)
- Mock framework: project uses `mocktail` not `mockito` — all test mocks use `class MockX extends Mock implements X {}`
- ConnectivityService: `isOnline()` is async (`Future<bool>`) — impl must use `await _connectivity.isOnline()`
- SliverPersistentHeader: two consecutive `pinned: true` slivers cause invalid SliverGeometry in tests; replaced with `SliverToBoxAdapter`
- NumberFormat fr_FR: uses narrow no-break space U+202F as thousands separator — test must not use ASCII space for number matching
- `findsAtLeastNWidget` not in flutter_test API — replaced with `findsWidgets`

### Completion Notes List

- ✅ Backend: 14 profitability tests GREEN (Controller 8, Service 6) — TDD, hexagonal, Strategy/Builder/Factory GoF patterns
- ✅ Flutter: 25 profitability tests GREEN (Model 8, Providers 4, ListPage 4, DetailPage 4, StorePage 5)
- ✅ Pre-existing `MultiStoreStockRepositoryContractTest` failure is unrelated to Story 7.4 (missing interface method in Story 3.x)
- ✅ Routes: `/reports` (OWNER) → `OwnerReportsPage` (4-tab), `/reports/rentabilite/:productId` → detail
- ✅ Client-side sort added to `productProfitabilityProvider` for consistent ordering regardless of data source

### File List

**Backend — new files:**
- `src/main/java/com/keevo/reporting/profitability/domain/model/`
  - `ProductProfitabilityEntry.java`, `ProductProfitabilityDetail.java`, `StorePerformanceEntry.java`, `RawSaleRow.java`, `RawStoreRow.java`, `SortOption.java`, `RankingMetric.java`, `MarginLevel.java`
- `src/main/java/com/keevo/reporting/profitability/domain/service/`
  - `ProfitabilitySortStrategy.java`, `ByMarginPctDescStrategy.java`, `ByMarginXafDescStrategy.java`, `ByCaDescStrategy.java`, `ByUnitsDescStrategy.java`, `ProfitabilitySortStrategyFactory.java`, `ProfitabilityReportBuilder.java`, `StoreRankingStrategy.java`, `ByCAStrategy.java`, `BySalesCountStrategy.java`, `ByAvgBasketStrategy.java`, `StoreRankingStrategyFactory.java`
- `src/main/java/com/keevo/reporting/profitability/domain/port/`
  - `in/GetProductProfitabilityUseCase.java`, `in/GetStorePerformanceUseCase.java`, `out/ProfitabilityRepository.java`
- `src/main/java/com/keevo/reporting/profitability/application/service/ProfitabilityService.java`
- `src/main/java/com/keevo/reporting/profitability/adapter/out/persistence/JdbcProfitabilityRepository.java`
- `src/main/java/com/keevo/reporting/profitability/adapter/in/rest/ProfitabilityController.java`
- `src/main/java/com/keevo/reporting/profitability/adapter/in/rest/dto/` (DTOs)
- `src/test/java/com/keevo/reporting/profitability/` (controller, service tests)

**Flutter — new files:**
- `lib/features/profitability/domain/model/product_profitability_model.dart`
- `lib/features/profitability/domain/repository/profitability_repository.dart`
- `lib/features/profitability/data/datasource/remote_profitability_datasource.dart`
- `lib/features/profitability/data/datasource/local_profitability_datasource.dart`
- `lib/features/profitability/data/repository/profitability_repository_impl.dart`
- `lib/features/profitability/presentation/provider/profitability_providers.dart`
- `lib/features/profitability/presentation/widget/profitability_row.dart`
- `lib/features/profitability/presentation/widget/period_filter_chips.dart`
- `lib/features/profitability/presentation/widget/sort_chips.dart`
- `lib/features/profitability/presentation/widget/store_performance_row.dart`
- `lib/features/profitability/presentation/widget/margin_sparkline.dart`
- `lib/features/profitability/presentation/page/product_profitability_list_page.dart`
- `lib/features/profitability/presentation/page/product_profitability_detail_page.dart`
- `lib/features/profitability/presentation/page/store_performance_page.dart`
- `lib/features/reports/presentation/page/owner_reports_page.dart`

**Flutter — modified files:**
- `lib/core/router/app_router.dart` — OWNER `/reports` → `OwnerReportsPage`, new `/reports/rentabilite/:productId` route

**Test files:**
- `test/features/profitability/domain/model/product_profitability_model_test.dart`
- `test/features/profitability/presentation/provider/profitability_providers_test.dart`
- `test/features/profitability/presentation/page/product_profitability_list_page_test.dart`
- `test/features/profitability/presentation/page/product_profitability_detail_page_test.dart`
- `test/features/profitability/presentation/page/store_performance_page_test.dart`
