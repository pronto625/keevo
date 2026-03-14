# Story 3.2: Vue Centralisée des Stocks Multi-Boutiques

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a proprietor (Simon),
I want a single consolidated view of my stock levels across all stores and the warehouse,
So that I can immediately spot where stock is critical, abundant, or imbalanced without navigating store by store.

## Acceptance Criteria

**AC1 — Centralized stock overview screen**
- **Given** Simon navigates to Catalogue > "Vue Globale" (icon button in app bar)
- **When** the global stock screen loads (route `/stock/overview`)
- **Then** a list of all active stores + warehouse is shown as expandable cards
- **And** each card shows:
  - Store name + type icon (🏪 Boutique / 🏭 Warehouse)
  - Total distinct product count (number of SKUs with any stock in that store)
  - Total stock value in XAF (`SUM(stock_levels.quantity × products.price)` per store)
  - A "stock bas" badge count (number of products where `quantity > 0 AND minimum_threshold > 0 AND quantity ≤ minimum_threshold`)
- **And** when **online**: data is fetched from the backend AND simultaneously written to local Drift — UI always shows the freshest server data
- **And** when **offline** (or network error): data loads from local Drift cache — fully functional without network
- **And** a "Valeur totale" footer shows the combined stock value across all stores

**AC2 — Expandable store card with paginated product list**
- **Given** Simon taps a store card to expand it
- **When** the store stock detail unfolds
- **Then** a list of all products with stock in that store is shown with:
  - Product name + variant (if any)
  - Current quantity
  - Alert threshold (e.g., "Seuil : 5")
  - Status badge: `✅ Normal` (qty > threshold OR no threshold set) / `⚠ Bas` (qty ≤ threshold AND threshold > 0) / `🔴 Critique` (qty == 0)
- **And** products with `Bas` or `Critique` status are sorted to the top automatically
- **And** the expanded list is paginated (25 items per page) with a "Voir plus" button

**AC3 — Cross-store product search**
- **Given** Simon wants to compare the stock of one product across all stores
- **When** he types a product name in the search bar at the top of the overview page
- **Then** results show the product once per store/warehouse where it has a stock entry, with: product name, store name, quantity, isLow status
- **And** the search is real-time against local Drift data (debounce 300ms, offline-capable)
- **And** stores where that product has zero stock (no stock_levels row) are NOT shown in results
- **And** tapping a result row navigates to the store card section (scrolls to and expands it)

**AC4 — Auto-refresh within 3 seconds after sync completion**
- **Given** the centralized stock view is open and a sync completes (push or pull)
- **When** the sync status transitions to "completed"
- **Then** the overview data re-fetches from the backend (background refresh) within 3 seconds
- **And** any store with stock changes since the last view shows a subtle "↻ Mis à jour" indicator (shown for 5 seconds then fades)

**AC5 — Responsive layout for desktop**
- **Given** Simon is on a desktop (expanded breakpoint ≥ 840px)
- **When** he views the centralized stock screen
- **Then** stores are displayed in a responsive grid: single column on compact (<600px), 2 columns on medium (600–840px), 3 columns on expanded (≥840px)
- **And** the same data, search, and expand interactions are available as on mobile — no feature degradation

---

## GoF Pattern Analysis (MANDATORY — fill before any implementation)

| Question | Answer |
|---|---|
| What variability exists in this feature? | Store aggregation metrics may evolve (e.g., add turnover rate, cost value vs sale value); sort order strategies (low-stock first, alphabetical, value descending) |
| What might change in the future? | Multi-currency, grouping by region/zone, custom metric columns, additional filter presets |
| Which GoF pattern(s) apply? | **Composite** (StoreStockSummary = composite root aggregating StoreProductStockEntry leaves); **Observer** (sync completion event → auto-refresh of stock overview via Riverpod state watch) |
| How does it enable Open/Closed principle? | `MultiStoreStockRepository` is a closed interface — adding a new metric (e.g., turnover) only extends the query and DTO, not callers. Observer decouples sync engine from stock UI. |
| Where is the pattern applied? | Composite: `StoreStockSummary.java` (root) + `StoreProductStockEntry.java` (leaf) in domain; Observer: sync completion → `ref.invalidate(globalStockOverviewProvider)` in Flutter Riverpod |

---

## Tasks / Subtasks

> **⚠️ TDD STRICT — RED → GREEN → REFACTOR. Write the failing test FIRST, then implement. No exceptions.**

---

### BACKEND — Full TDD (Spring Boot, Hexagonal Architecture)

> **Module placement**: `catalog/stock/` — where all stock infrastructure lives (StockLevelRepository, StockLevel, StockMovement, etc.)
> Domain: `com.keevo.catalog.stock`
> All new tests go in `src/test/java/com/keevo/catalog/stock/`

---

- [ ] **Task 1 — New domain projections (Composite pattern)**

  > These are read-only projections (not full entities with lifecycle). They aggregate data across `stores`, `stock_levels`, and `products` tables.

  - [ ] 1.1 — Write `StoreStockSummaryTest.java` FIRST (unit, no framework):
    ```java
    @Test void summary_shouldComputeIsLowCount_correctly()
    @Test void summary_shouldHoldAllFields()
    ```
  - [ ] 1.2 — Create `StoreStockSummary.java` in `catalog/stock/domain/model/`:
    ```java
    package com.keevo.catalog.stock.domain.model;

    import com.keevo.store.store.domain.model.StoreType;
    import java.util.UUID;

    /**
     * StoreStockSummary — Composite root (GoF: Composite).
     * Aggregated stock snapshot for a single store across all products.
     * Story 3.2.
     */
    public record StoreStockSummary(
        UUID storeId,
        String storeName,
        StoreType storeType,
        int productCount,       // distinct SKUs with any stock_level row in this store
        long totalValueXaf,     // SUM(quantity × price) across all products in this store
        int lowStockCount       // count of products where qty <= minimumThreshold AND threshold > 0
    ) {}
    ```

  - [ ] 1.3 — Write `StoreProductStockEntryTest.java` FIRST:
    ```java
    @Test void entry_isLow_whenQuantityBelowThreshold()
    @Test void entry_isCritical_whenQuantityIsZero()
    @Test void entry_isNormal_whenThresholdIsZero()
    ```
  - [ ] 1.4 — Create `StoreProductStockEntry.java` in `catalog/stock/domain/model/`:
    ```java
    package com.keevo.catalog.stock.domain.model;

    import java.util.UUID;

    /**
     * StoreProductStockEntry — Composite leaf (GoF: Composite).
     * Stock level for one product (or variant) in one store.
     * Story 3.2.
     */
    public record StoreProductStockEntry(
        UUID productId,
        String productName,
        UUID variantId,         // nullable — only for variant products
        String variantLabel,    // nullable — e.g., "Taille L / Rouge"
        UUID storeId,
        int quantity,
        int minimumThreshold
    ) {
        /** True when stock is at or below the configured alert threshold.
         *  Returns false when threshold is 0 (no alert configured). */
        public boolean isLow() {
            return minimumThreshold > 0 && quantity <= minimumThreshold;
        }

        /** True when stock is zero — critical regardless of threshold. */
        public boolean isCritical() {
            return quantity == 0;
        }

        /** Status label for the frontend badge. */
        public StockStatus status() {
            if (isCritical()) return StockStatus.CRITIQUE;
            if (isLow())      return StockStatus.BAS;
            return StockStatus.NORMAL;
        }
    }
    ```
  - [ ] 1.5 — Create `StockStatus.java` enum in `catalog/stock/domain/model/`:
    ```java
    public enum StockStatus { NORMAL, BAS, CRITIQUE }
    ```

---

- [ ] **Task 2 — New use case ports**

  - [ ] 2.1 — Create `GetMultiStoreOverviewQuery.java` in `catalog/stock/domain/port/in/`:
    ```java
    /** Query to retrieve aggregated stock summary for all active stores. */
    public record GetMultiStoreOverviewQuery() {}
    ```
  - [ ] 2.2 — Create `GetMultiStoreOverviewUseCase.java` in `catalog/stock/domain/port/in/`:
    ```java
    public interface GetMultiStoreOverviewUseCase {
        List<StoreStockSummary> execute(GetMultiStoreOverviewQuery query);
    }
    ```
  - [ ] 2.3 — Create `GetStoreStockDetailQuery.java` in `catalog/stock/domain/port/in/`:
    ```java
    /**
     * Query to retrieve paginated product stock list for a specific store.
     * @param storeId    target store UUID
     * @param page       0-based page index
     * @param size       page size (default 25)
     * @param sortLowFirst true → low/critical stock entries first, then alphabetical
     */
    public record GetStoreStockDetailQuery(UUID storeId, int page, int size, boolean sortLowFirst) {}
    ```
  - [ ] 2.4 — Create `GetStoreStockDetailUseCase.java` in `catalog/stock/domain/port/in/`:
    ```java
    import org.springframework.data.domain.Page;

    public interface GetStoreStockDetailUseCase {
        Page<StoreProductStockEntry> execute(GetStoreStockDetailQuery query);
    }
    ```

---

- [ ] **Task 3 — New out port: MultiStoreStockRepository**

  - [ ] 3.1 — Write `MultiStoreStockRepositoryContractTest.java` (interface contract, documents required methods):
    ```java
    @Test void repository_shouldDefineGetStoreOverviews()
    @Test void repository_shouldDefineGetStoreStockDetail()
    ```
  - [ ] 3.2 — Create `MultiStoreStockRepository.java` in `catalog/stock/domain/port/out/`:
    ```java
    package com.keevo.catalog.stock.domain.port.out;

    import com.keevo.catalog.stock.domain.model.StoreProductStockEntry;
    import com.keevo.catalog.stock.domain.model.StoreStockSummary;
    import org.springframework.data.domain.Page;
    import org.springframework.data.domain.Pageable;

    import java.util.List;
    import java.util.UUID;

    /**
     * MultiStoreStockRepository — out port for multi-store stock aggregation queries.
     *
     * <p>Implementation uses native SQL (JdbcTemplate) to avoid cross-module
     * JPA entity imports (stores/products/stock_levels are in separate modules).
     * Story 3.2.
     */
    public interface MultiStoreStockRepository {

        /**
         * Returns aggregated stock summary for ALL active stores in the current tenant.
         * Native SQL: JOIN stores + stock_levels + products, GROUP BY store.
         */
        List<StoreStockSummary> getStoreOverviews();

        /**
         * Returns paginated product stock entries for a specific store.
         * Sort: low/critical first (when sortLowFirst), then alphabetical by product name.
         */
        Page<StoreProductStockEntry> getStoreStockDetail(UUID storeId, boolean sortLowFirst, Pageable pageable);
    }
    ```

---

- [ ] **Task 4 — Application services**

  - [ ] 4.1 — Write `GetMultiStoreOverviewServiceTest.java` FIRST (unit, Mockito):
    ```java
    @Test void execute_shouldReturnAllActiveStoresSummaries()
    @Test void execute_shouldReturnEmptyList_whenNoStores()
    @Test void execute_shouldDelegateToRepository()
    ```
  - [ ] 4.2 — Create `GetMultiStoreOverviewService.java` in `catalog/stock/application/usecase/`:
    ```java
    @Service
    public class GetMultiStoreOverviewService implements GetMultiStoreOverviewUseCase {
        private final MultiStoreStockRepository repo;

        public GetMultiStoreOverviewService(MultiStoreStockRepository repo) {
            this.repo = repo;
        }

        @Override
        @Transactional(readOnly = true)
        public List<StoreStockSummary> execute(GetMultiStoreOverviewQuery query) {
            return repo.getStoreOverviews();
        }
    }
    ```

  - [ ] 4.3 — Write `GetStoreStockDetailServiceTest.java` FIRST (unit, Mockito):
    ```java
    @Test void execute_shouldReturnPagedEntries_forValidStore()
    @Test void execute_shouldSortLowStockFirst_whenFlagSet()
    @Test void execute_shouldDelegateToRepository_withCorrectPageable()
    ```
  - [ ] 4.4 — Create `GetStoreStockDetailService.java` in `catalog/stock/application/usecase/`:
    ```java
    @Service
    public class GetStoreStockDetailService implements GetStoreStockDetailUseCase {
        private final MultiStoreStockRepository repo;

        public GetStoreStockDetailService(MultiStoreStockRepository repo) {
            this.repo = repo;
        }

        @Override
        @Transactional(readOnly = true)
        public Page<StoreProductStockEntry> execute(GetStoreStockDetailQuery query) {
            Pageable pageable = PageRequest.of(query.page(), query.size());
            return repo.getStoreStockDetail(query.storeId(), query.sortLowFirst(), pageable);
        }
    }
    ```

---

- [ ] **Task 5 — JdbcTemplate adapter (MultiStoreStockRepositoryAdapter)**

  > **Critical**: Uses native SQL via `JdbcTemplate` — no cross-module JPA entity imports.
  > All tables (`stores`, `stock_levels`, `products`) live in the current tenant schema (set by `TenantContext` ThreadLocal).
  > `price` in `products` is `INTEGER` (XAF whole number).

  - [ ] 5.1 — Write `MultiStoreStockRepositoryAdapterTest.java` (`@DataJpaTest` + `JdbcTemplate`):
    ```java
    @Test void getStoreOverviews_returnsOneRow_perActiveStore()
    @Test void getStoreOverviews_computesLowStockCount_correctly()
    @Test void getStoreStockDetail_returnsProductsForStore()
    @Test void getStoreStockDetail_sortsLowStockFirst_whenFlagSet()
    ```
  - [ ] 5.2 — Create `MultiStoreStockRepositoryAdapter.java` in `catalog/stock/adapter/out/persistence/`:
    ```java
    package com.keevo.catalog.stock.adapter.out.persistence;

    import com.keevo.catalog.stock.domain.model.*;
    import com.keevo.catalog.stock.domain.port.out.MultiStoreStockRepository;
    import com.keevo.store.store.domain.model.StoreType;
    import org.springframework.data.domain.*;
    import org.springframework.jdbc.core.JdbcTemplate;
    import org.springframework.stereotype.Component;
    import org.springframework.transaction.annotation.Transactional;

    import java.util.*;

    /**
     * MultiStoreStockRepositoryAdapter — JdbcTemplate native SQL implementation.
     *
     * <p>Uses JdbcTemplate (not JPA) to avoid cross-module entity imports:
     *   stores     (store/store module)
     *   products   (catalog/product module — in shared entity but native SQL is cleaner)
     *   stock_levels (shared entity)
     *
     * <p>All SQL runs in the current tenant schema (set by TenantContext ThreadLocal
     * via MultiTenantConnectionProvider — same as all other queries in the app).
     *
     * Story 3.2. GoF: Adapter.
     */
    @Component
    public class MultiStoreStockRepositoryAdapter implements MultiStoreStockRepository {

        private static final String SQL_STORE_OVERVIEW = """
            SELECT
                s.id           AS store_id,
                s.name         AS store_name,
                s.type         AS store_type,
                COUNT(DISTINCT sl.product_id)                                        AS product_count,
                COALESCE(SUM(sl.quantity * p.price), 0)                             AS total_value_xaf,
                COUNT(CASE WHEN p.minimum_threshold > 0
                            AND sl.quantity <= p.minimum_threshold THEN 1 END)       AS low_stock_count
            FROM stores s
            LEFT JOIN stock_levels sl ON sl.store_id = s.id
            LEFT JOIN products p     ON p.id = sl.product_id
                                    AND p.archived = false
            WHERE s.is_active = true
            GROUP BY s.id, s.name, s.type
            ORDER BY
                CASE WHEN s.type = 'WAREHOUSE' THEN 0 ELSE 1 END ASC,
                s.created_at ASC
            """;

        private static final String SQL_STORE_DETAIL_BASE = """
            SELECT
                p.id                AS product_id,
                p.name              AS product_name,
                sl.variant_id       AS variant_id,
                NULL                AS variant_label,
                sl.store_id         AS store_id,
                sl.quantity         AS quantity,
                p.minimum_threshold AS minimum_threshold
            FROM stock_levels sl
            JOIN products p ON p.id = sl.product_id
            WHERE sl.store_id = ?
              AND p.archived = false
            """;

        private static final String SQL_STORE_DETAIL_SORT_LOW =
            SQL_STORE_DETAIL_BASE +
            "ORDER BY " +
            "  CASE WHEN p.minimum_threshold > 0 AND sl.quantity <= p.minimum_threshold THEN 0 " +
            "       WHEN sl.quantity = 0 THEN 1 " +
            "       ELSE 2 END ASC, " +
            "  p.name ASC ";

        private static final String SQL_STORE_DETAIL_SORT_NAME =
            SQL_STORE_DETAIL_BASE + "ORDER BY p.name ASC ";

        private static final String SQL_STORE_DETAIL_COUNT =
            "SELECT COUNT(*) FROM stock_levels sl " +
            "JOIN products p ON p.id = sl.product_id " +
            "WHERE sl.store_id = ? AND p.archived = false";

        private final JdbcTemplate jdbc;

        public MultiStoreStockRepositoryAdapter(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        @Transactional(readOnly = true)
        public List<StoreStockSummary> getStoreOverviews() {
            return jdbc.query(SQL_STORE_OVERVIEW, (rs, rowNum) -> new StoreStockSummary(
                UUID.fromString(rs.getString("store_id")),
                rs.getString("store_name"),
                StoreType.valueOf(rs.getString("store_type")),
                rs.getInt("product_count"),
                rs.getLong("total_value_xaf"),
                rs.getInt("low_stock_count")
            ));
        }

        @Override
        @Transactional(readOnly = true)
        public Page<StoreProductStockEntry> getStoreStockDetail(
                UUID storeId, boolean sortLowFirst, Pageable pageable) {

            String baseSql  = sortLowFirst ? SQL_STORE_DETAIL_SORT_LOW : SQL_STORE_DETAIL_SORT_NAME;
            String pagedSql = baseSql + " LIMIT " + pageable.getPageSize()
                                      + " OFFSET " + pageable.getOffset();

            List<StoreProductStockEntry> content = jdbc.query(pagedSql,
                (rs, rowNum) -> {
                    String vid = rs.getString("variant_id");
                    return new StoreProductStockEntry(
                        UUID.fromString(rs.getString("product_id")),
                        rs.getString("product_name"),
                        vid != null ? UUID.fromString(vid) : null,
                        null, // variantLabel — TODO Story 3.3+ (variants)
                        UUID.fromString(rs.getString("store_id")),
                        rs.getInt("quantity"),
                        rs.getInt("minimum_threshold")
                    );
                },
                storeId.toString());

            Long total = jdbc.queryForObject(SQL_STORE_DETAIL_COUNT, Long.class, storeId.toString());
            return new PageImpl<>(content, pageable, total != null ? total : 0L);
        }
    }
    ```

  > **Security note**: The `store_id` parameter is passed as a JDBC parameter (`?`), not via string concatenation — no SQL injection risk. The `LIMIT`/`OFFSET` values come from Spring's `Pageable` (validated non-negative integers) — safe.

---

- [ ] **Task 6 — REST adapter: DTOs + MultiStoreStockController**

  - [ ] 6.1 — Create `StoreStockSummaryDto.java` in `catalog/stock/adapter/in/web/dto/`:
    ```java
    package com.keevo.catalog.stock.adapter.in.web.dto;

    import com.keevo.catalog.stock.domain.model.StoreStockSummary;
    import com.keevo.store.store.domain.model.StoreType;
    import java.util.UUID;

    /**
     * REST response for a store's aggregated stock summary.
     * Story 3.2.
     */
    public record StoreStockSummaryDto(
        UUID storeId,
        String storeName,
        StoreType storeType,
        int productCount,
        long totalValueXaf,
        int lowStockCount
    ) {
        public static StoreStockSummaryDto from(StoreStockSummary s) {
            return new StoreStockSummaryDto(
                s.storeId(), s.storeName(), s.storeType(),
                s.productCount(), s.totalValueXaf(), s.lowStockCount()
            );
        }
    }
    ```

  - [ ] 6.2 — Create `StoreProductStockEntryDto.java` in `catalog/stock/adapter/in/web/dto/`:
    ```java
    public record StoreProductStockEntryDto(
        UUID productId,
        String productName,
        UUID variantId,
        String variantLabel,
        UUID storeId,
        int quantity,
        int minimumThreshold,
        boolean isLow,
        boolean isCritical,
        String status  // "NORMAL" | "BAS" | "CRITIQUE"
    ) {
        public static StoreProductStockEntryDto from(StoreProductStockEntry e) {
            return new StoreProductStockEntryDto(
                e.productId(), e.productName(), e.variantId(), e.variantLabel(),
                e.storeId(), e.quantity(), e.minimumThreshold(),
                e.isLow(), e.isCritical(), e.status().name()
            );
        }
    }
    ```

  - [ ] 6.3 — Write `MultiStoreStockControllerTest.java` (`@WebMvcTest`) FIRST:
    ```java
    @Test void GET_stock_overview_shouldReturn200_withStoreList()
    @Test void GET_stock_overview_shouldReturn401_withoutAuth()
    @Test void GET_stock_stores_productId_detail_shouldReturn200_withPagedProducts()
    @Test void GET_stock_stores_invalidUUID_shouldReturn400()
    ```
  - [ ] 6.4 — Create `MultiStoreStockController.java` in `catalog/stock/adapter/in/web/`:
    ```java
    package com.keevo.catalog.stock.adapter.in.web;

    import com.keevo.catalog.stock.adapter.in.web.dto.StoreProductStockEntryDto;
    import com.keevo.catalog.stock.adapter.in.web.dto.StoreStockSummaryDto;
    import com.keevo.catalog.stock.application.usecase.GetMultiStoreOverviewUseCase;
    import com.keevo.catalog.stock.application.usecase.GetStoreStockDetailUseCase;
    import com.keevo.catalog.stock.domain.port.in.GetMultiStoreOverviewQuery;
    import com.keevo.catalog.stock.domain.port.in.GetStoreStockDetailQuery;
    import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
    import io.swagger.v3.oas.annotations.Operation;
    import io.swagger.v3.oas.annotations.tags.Tag;
    import org.springframework.data.domain.Page;
    import org.springframework.http.ResponseEntity;
    import org.springframework.security.access.prepost.PreAuthorize;
    import org.springframework.web.bind.annotation.*;

    import java.util.List;
    import java.util.UUID;

    /**
     * MultiStoreStockController — centralized multi-store stock overview endpoints.
     *
     * <pre>
     * GET /api/v1/stock/overview                                → all active stores summary
     * GET /api/v1/stock/stores/{storeId}/products?page&size&sortLowFirst → store product stock list
     * </pre>
     *
     * Story 3.2.
     */
    @Tag(name = "Multi-Store Stock", description = "Centralized multi-store stock overview")
    @RestController
    @RequestMapping("/api/v1/stock")
    @PreAuthorize("hasRole('USER')")
    public class MultiStoreStockController {

        private final GetMultiStoreOverviewUseCase overviewUseCase;
        private final GetStoreStockDetailUseCase detailUseCase;

        public MultiStoreStockController(GetMultiStoreOverviewUseCase overviewUseCase,
                                         GetStoreStockDetailUseCase detailUseCase) {
            this.overviewUseCase = overviewUseCase;
            this.detailUseCase   = detailUseCase;
        }

        @Operation(summary = "Get stock summary for all active stores + warehouse")
        @GetMapping("/overview")
        public ResponseEntity<ApiResponseWrapper<List<StoreStockSummaryDto>>> getOverview() {
            List<StoreStockSummaryDto> dtos = overviewUseCase
                    .execute(new GetMultiStoreOverviewQuery())
                    .stream()
                    .map(StoreStockSummaryDto::from)
                    .toList();
            return ResponseEntity.ok(ApiResponseWrapper.ok(dtos));
        }

        @Operation(summary = "Get paginated product stock list for a specific store")
        @GetMapping("/stores/{storeId}/products")
        public ResponseEntity<ApiResponseWrapper<Page<StoreProductStockEntryDto>>> getStoreDetail(
                @PathVariable UUID storeId,
                @RequestParam(defaultValue = "0")    int page,
                @RequestParam(defaultValue = "25")   int size,
                @RequestParam(defaultValue = "true") boolean sortLowFirst) {

            if (size < 1 || size > 100) size = 25; // safety clamp

            Page<StoreProductStockEntryDto> result = detailUseCase
                    .execute(new GetStoreStockDetailQuery(storeId, page, size, sortLowFirst))
                    .map(StoreProductStockEntryDto::from);

            return ResponseEntity.ok(ApiResponseWrapper.ok(result));
        }
    }
    ```

    > **Security**: UUID path variable is type-safe (Spring auto-rejects malformed UUIDs with 400). `size` clamped to [1,100]. No user-controlled string concatenated into SQL.

---

- [ ] **Task 7 — cURL Integration Test Script**

  Create `keevo/scripts/curl-tests-story-3-2.sh`:

  ```bash
  #!/usr/bin/env bash
  # ======================================================
  # Story 3.2 — Vue Centralisée des Stocks Multi-Boutiques
  # cURL E2E integration tests
  # Run: bash curl-tests-story-3-2.sh
  # All steps must show ✅ before story is marked done
  # ======================================================
  set -euo pipefail
  BASE="http://localhost:8080"
  PY='python3 -c'

  # ── Step 1 — Auth: register + 2-step login ───────────────────────────────
  RESP=$(curl -s -X POST "$BASE/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d '{"phone":"+237611000042","password":"Test1234!","firstName":"Simon","lastName":"Overview"}')
  LOGIN_TOKEN=$(echo "$RESP" | $PY "import sys,json; print(json.load(sys.stdin)['data']['loginToken'])")
  TENANTS=$(curl -s -X GET "$BASE/api/v1/auth/tenants" -H "Authorization: Bearer $LOGIN_TOKEN")
  TENANT_ID=$(echo "$TENANTS" | $PY "import sys,json; print(json.load(sys.stdin)['data'][0]['tenantId'])")
  SEL=$(curl -s -X POST "$BASE/api/v1/auth/select-tenant" \
    -H "Authorization: Bearer $LOGIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"tenantId\":\"$TENANT_ID\"}")
  JWT=$(echo "$SEL" | $PY "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
  [[ -n "$JWT" && "$JWT" != "null" ]] && echo "✅ Step 1 — Auth OK" || { echo "❌ Step 1 FAILED"; exit 1; }

  # ── Step 2 — GET /api/v1/stock/overview (no products yet = empty state OK) ─
  OV=$(curl -s -X GET "$BASE/api/v1/stock/overview" -H "Authorization: Bearer $JWT")
  IS_ARRAY=$(echo "$OV" | $PY "import sys,json; d=json.load(sys.stdin); print(isinstance(d.get('data',[]), list))")
  [[ "$IS_ARRAY" == "True" ]] && echo "✅ Step 2 — GET /stock/overview returns list" || { echo "❌ Step 2 FAILED"; exit 1; }

  # ── Step 3 — Complete onboarding so storeId is provisioned ──────────────
  # Only run if no stores are in the overview (fresh tenant)
  STORE_COUNT=$(echo "$OV" | $PY "import sys,json; print(len(json.load(sys.stdin).get('data', [])))")
  if [[ "$STORE_COUNT" -lt 1 ]]; then
    # Trigger onboarding to seed default store
    echo "⚠ Step 3 — No stores yet (onboarding needed). Triggering onboarding..."
    # Run onboarding (simplified: sector + shop name)
    curl -s -X POST "$BASE/api/v1/onboarding/complete" \
      -H "Authorization: Bearer $JWT" \
      -H "Content-Type: application/json" \
      -d '{"sectorType":"GENERAL","shopName":"Boutique Simon","city":"Yaoundé"}' > /dev/null || true
    OV=$(curl -s -X GET "$BASE/api/v1/stock/overview" -H "Authorization: Bearer $JWT")
  fi
  STORE_COUNT=$(echo "$OV" | $PY "import sys,json; print(len(json.load(sys.stdin).get('data', [])))")
  [[ "$STORE_COUNT" -ge 1 ]] && echo "✅ Step 3 — At least 1 store in overview (count=$STORE_COUNT)" || { echo "❌ Step 3 FAILED"; exit 1; }
  FIRST_STORE_ID=$(echo "$OV" | $PY "import sys,json; print(json.load(sys.stdin)['data'][0]['storeId'])")

  # ── Step 4 — Create a product and add stock ──────────────────────────────
  PROD=$(curl -s -X POST "$BASE/api/v1/products" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d '{"name":"Chaussures Nike","price":25000,"buyPrice":15000,"stockQuantity":0,"categoryId":null}')
  PROD_ID=$(echo "$PROD" | $PY "import sys,json; print(json.load(sys.stdin)['data']['id'])")
  [[ -n "$PROD_ID" && "$PROD_ID" != "null" ]] && echo "✅ Step 4a — Product created (id=$PROD_ID)" || { echo "❌ Step 4a FAILED"; exit 1; }

  # Add stock entry: 10 units
  SE=$(curl -s -X POST "$BASE/api/v1/products/$PROD_ID/stock/entry" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"storeId\":\"$FIRST_STORE_ID\",\"quantity\":10,\"notes\":\"Arrivage Mars\"}")
  QTY_AFTER=$(echo "$SE" | $PY "import sys,json; print(json.load(sys.stdin)['data']['quantityAfter'])")
  [[ "$QTY_AFTER" == "10" ]] && echo "✅ Step 4b — Stock entry: 10 units" || { echo "❌ Step 4b FAILED (qty=$QTY_AFTER)"; exit 1; }

  # ── Step 5 — GET /stock/overview reflects the new stock ──────────────────
  OV2=$(curl -s -X GET "$BASE/api/v1/stock/overview" -H "Authorization: Bearer $JWT")
  PROD_COUNT=$(echo "$OV2" | $PY "
import sys,json
d=json.load(sys.stdin)['data']
store = next((s for s in d if s['storeId'] == '$FIRST_STORE_ID'), None)
print(store['productCount'] if store else -1)
")
  TOTAL_VAL=$(echo "$OV2" | $PY "
import sys,json
d=json.load(sys.stdin)['data']
store = next((s for s in d if s['storeId'] == '$FIRST_STORE_ID'), None)
print(store['totalValueXaf'] if store else -1)
")
  [[ "$PROD_COUNT" -ge 1 ]] && echo "✅ Step 5a — productCount >= 1 (count=$PROD_COUNT)" || { echo "❌ Step 5a FAILED (count=$PROD_COUNT)"; exit 1; }
  [[ "$TOTAL_VAL" -ge 250000 ]] && echo "✅ Step 5b — totalValueXaf >= 250000 (10 × 25000 = $TOTAL_VAL XAF)" || { echo "❌ Step 5b FAILED (val=$TOTAL_VAL)"; exit 1; }

  # ── Step 6 — Set threshold so low_stock triggers ─────────────────────────
  curl -s -X PATCH "$BASE/api/v1/products/$PROD_ID/threshold" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d '{"minimumThreshold":15}' > /dev/null  # threshold=15, stock=10 → BAS
  OV3=$(curl -s -X GET "$BASE/api/v1/stock/overview" -H "Authorization: Bearer $JWT")
  LOW_COUNT=$(echo "$OV3" | $PY "
import sys,json
d=json.load(sys.stdin)['data']
store = next((s for s in d if s['storeId'] == '$FIRST_STORE_ID'), None)
print(store['lowStockCount'] if store else -1)
")
  [[ "$LOW_COUNT" -ge 1 ]] && echo "✅ Step 6 — lowStockCount >= 1 (threshold=15, qty=10 → BAS, count=$LOW_COUNT)" || { echo "❌ Step 6 FAILED (lowCount=$LOW_COUNT)"; exit 1; }

  # ── Step 7 — GET /stock/stores/{storeId}/products ────────────────────────
  DET=$(curl -s -X GET "$BASE/api/v1/stock/stores/$FIRST_STORE_ID/products?page=0&size=25&sortLowFirst=true" \
    -H "Authorization: Bearer $JWT")
  DET_TOTAL=$(echo "$DET" | $PY "import sys,json; print(json.load(sys.stdin)['data']['totalElements'])")
  [[ "$DET_TOTAL" -ge 1 ]] && echo "✅ Step 7a — store detail: totalElements >= 1 (total=$DET_TOTAL)" || { echo "❌ Step 7a FAILED"; exit 1; }
  FIRST_STATUS=$(echo "$DET" | $PY "import sys,json; print(json.load(sys.stdin)['data']['content'][0]['status'])")
  [[ "$FIRST_STATUS" == "BAS" || "$FIRST_STATUS" == "CRITIQUE" ]] && echo "✅ Step 7b — sortLowFirst: first item is $FIRST_STATUS" || { echo "❌ Step 7b FAILED (status=$FIRST_STATUS)"; exit 1; }

  # ── Step 8 — Pagination: size=1 → totalPages >= 1 ───────────────────────
  DET_P=$(curl -s -X GET "$BASE/api/v1/stock/stores/$FIRST_STORE_ID/products?page=0&size=1" \
    -H "Authorization: Bearer $JWT")
  TOTAL_PAGES=$(echo "$DET_P" | $PY "import sys,json; print(json.load(sys.stdin)['data']['totalPages'])")
  [[ "$TOTAL_PAGES" -ge 1 ]] && echo "✅ Step 8 — Pagination: totalPages=$TOTAL_PAGES" || { echo "❌ Step 8 FAILED"; exit 1; }

  # ── Step 9 — Create 2nd store + add stock in it ──────────────────────────
  STORE2=$(curl -s -X POST "$BASE/api/v1/stores" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d '{"name":"Boutique Bonanjo","type":"STORE"}')
  STORE2_ID=$(echo "$STORE2" | $PY "import sys,json; print(json.load(sys.stdin)['data']['id'])")
  curl -s -X POST "$BASE/api/v1/products/$PROD_ID/stock/entry" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"storeId\":\"$STORE2_ID\",\"quantity\":5,\"notes\":\"Transfert test\"}" > /dev/null
  OV4=$(curl -s -X GET "$BASE/api/v1/stock/overview" -H "Authorization: Bearer $JWT")
  OV4_COUNT=$(echo "$OV4" | $PY "import sys,json; print(len(json.load(sys.stdin)['data']))")
  [[ "$OV4_COUNT" -ge 2 ]] && echo "✅ Step 9 — 2 stores in overview (count=$OV4_COUNT)" || { echo "❌ Step 9 FAILED (count=$OV4_COUNT)"; exit 1; }

  # ── Step 10 — Invalid UUID → 400 ─────────────────────────────────────────
  BAD_UUID=$(curl -s -o /dev/null -w "%{http_code}" \
    -X GET "$BASE/api/v1/stock/stores/not-a-uuid/products" \
    -H "Authorization: Bearer $JWT")
  [[ "$BAD_UUID" == "400" ]] && echo "✅ Step 10 — Invalid UUID → 400" || { echo "❌ Step 10 FAILED (http=$BAD_UUID)"; exit 1; }

  # ── Step 11 — No auth → 401 ──────────────────────────────────────────────
  NO_AUTH=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$BASE/api/v1/stock/overview")
  [[ "$NO_AUTH" == "401" ]] && echo "✅ Step 11 — 401 without auth" || { echo "❌ Step 11 FAILED (http=$NO_AUTH)"; exit 1; }

  echo ""
  echo "✅✅✅ All Story 3.2 cURL integration tests passed ✅✅✅"
  ```

---

### FLUTTER — Full TDD (Clean Architecture, Riverpod)

> **Feature folder**: `lib/features/inventory/`
> This feature uses the existing Drift tables: `stores`, `stock_levels`, `products` (no schema change — schemaVersion stays at 7).
> Follow the exact same file structure as `features/stores/` (most recent CRUD feature with provider pattern).

---

- [ ] **Task 8 — Domain models (Freezed)**

  - [ ] 8.1 — Write `store_stock_summary_model_test.dart` FIRST:
    ```dart
    test('StoreStockSummaryModel holds all fields', ...)
    test('StoreStockSummaryModel.fromJson parses correctly', ...)
    ```
  - [ ] 8.2 — Create `lib/features/inventory/domain/model/store_stock_summary_model.dart`:
    ```dart
    import 'package:freezed_annotation/freezed_annotation.dart';

    part 'store_stock_summary_model.freezed.dart';
    part 'store_stock_summary_model.g.dart';

    /// StoreStockSummaryModel — aggregated stock snapshot for one store.
    /// Mirrors StoreStockSummary backend domain model.
    /// Story 3.2. GoF: Composite root.
    @freezed
    class StoreStockSummaryModel with _$StoreStockSummaryModel {
      const factory StoreStockSummaryModel({
        required String storeId,
        required String storeName,
        required String storeType,      // 'STORE' | 'WAREHOUSE'
        required int productCount,
        required int totalValueXaf,     // int: XAF is whole numbers
        required int lowStockCount,
        @Default(false) bool isUpdated, // transient — UI indicator after sync
      }) = _StoreStockSummaryModel;

      factory StoreStockSummaryModel.fromJson(Map<String, dynamic> json) =>
          _$StoreStockSummaryModelFromJson(json);
    }
    ```

  - [ ] 8.3 — Write `store_product_stock_model_test.dart` FIRST:
    ```dart
    test('StoreProductStockModel.isLow is true when qty <= threshold AND threshold > 0', ...)
    test('StoreProductStockModel.isCritical is true when qty == 0', ...)
    test('StoreProductStockModel.status returns BAS when isLow', ...)
    ```
  - [ ] 8.4 — Create `lib/features/inventory/domain/model/store_product_stock_model.dart`:
    ```dart
    @freezed
    class StoreProductStockModel with _$StoreProductStockModel {
      const StoreProductStockModel._();

      const factory StoreProductStockModel({
        required String productId,
        required String productName,
        String? variantId,
        String? variantLabel,
        required String storeId,
        required int quantity,
        @Default(0) int minimumThreshold,
        required String status,   // 'NORMAL' | 'BAS' | 'CRITIQUE'
        required bool isLow,
        required bool isCritical,
      }) = _StoreProductStockModel;

      factory StoreProductStockModel.fromJson(Map<String, dynamic> json) =>
          _$StoreProductStockModelFromJson(json);
    }
    ```
  - [ ] 8.5 — Run codegen: `dart run build_runner build --delete-conflicting-outputs`

---

- [ ] **Task 9 — Domain repository interface**

  - [ ] 9.1 — Write `multi_store_stock_repository_test.dart` (contract test):
    ```dart
    test('MultiStoreStockRepository defines getOverview()', ...)
    test('MultiStoreStockRepository defines getStoreStockDetail()', ...)
    test('MultiStoreStockRepository defines searchAcrossStores()', ...)
    ```
  - [ ] 9.2 — Create `lib/features/inventory/domain/repository/multi_store_stock_repository.dart`:
    ```dart
    import '../model/store_product_stock_model.dart';
    import '../model/store_stock_summary_model.dart';

    abstract class MultiStoreStockRepository {
      /// Returns aggregated stock summary for all active stores.
      /// Loads from local Drift cache; background fetch from remote when online.
      Future<List<StoreStockSummaryModel>> getOverview();

      /// Returns paginated product stock list for a specific store.
      /// [sortLowFirst]: if true, BAS/CRITIQUE entries come first.
      Future<List<StoreProductStockModel>> getStoreStockDetail(
          String storeId, {int page = 0, int size = 25, bool sortLowFirst = true});

      /// Cross-store search against local Drift data only (offline-capable).
      /// Returns all (product, store) pairs where product name matches [query].
      Future<List<StoreProductStockModel>> searchAcrossStores(String query);
    }
    ```

---

- [ ] **Task 10 — Local Drift datasource (join queries)**

  > The Drift `AppDatabase` already has `stores`, `stock_levels`, and `products` tables.
  > We use `customSelect` with raw SQL for multi-table JOINs.
  > No schema migration required (schemaVersion stays at **7**).

  - [ ] 10.1 — Write `local_multi_store_stock_datasource_test.dart` FIRST (using in-memory Drift):
    ```dart
    test('getStoreOverviews returns one entry per active store', ...)
    test('getStoreStockDetail returns products for a given storeId', ...)
    test('searchAcrossStores returns results matching name filter', ...)
    test('upsertStockLevels writes remote entries into the stock_levels table', ...)
    ```
  - [ ] 10.2 — Create `lib/features/inventory/data/datasource/local_multi_store_stock_datasource.dart`:
    ```dart
    import 'package:drift/drift.dart';
    import '../../../../core/storage/app_database.dart';
    import '../../domain/model/store_product_stock_model.dart';
    import '../../domain/model/store_stock_summary_model.dart';

    /// LocalMultiStoreStockDataSource — Drift-backed multi-store stock aggregation.
    ///
    /// Uses customSelect (raw SQL) to JOIN stores + stock_levels + products.
    /// The same tables are used in different Drift modules (catalog, stores) —
    /// custom SQL avoids Drift cross-table type-safeness issues.
    /// Story 3.2.
    class LocalMultiStoreStockDataSource {
      final AppDatabase _db;
      LocalMultiStoreStockDataSource(this._db);

      static const _overviewSql = '''
        SELECT
          s.id           AS store_id,
          s.name         AS store_name,
          s.type         AS store_type,
          COUNT(DISTINCT sl.product_id)                                   AS product_count,
          COALESCE(SUM(sl.quantity * p.price), 0)                        AS total_value_xaf,
          COUNT(CASE WHEN p.minimum_threshold > 0
                      AND sl.quantity <= p.minimum_threshold THEN 1 END) AS low_stock_count
        FROM stores s
        LEFT JOIN stock_levels sl ON sl.store_id = s.id
        LEFT JOIN products p      ON p.id = sl.product_id
                                  AND p.archived = 0
        WHERE s.is_active = 1
        GROUP BY s.id, s.name, s.type
        ORDER BY
          CASE WHEN s.type = 'WAREHOUSE' THEN 0 ELSE 1 END ASC,
          s.created_at ASC
      ''';

      Future<List<StoreStockSummaryModel>> getStoreOverviews() async {
        final rows = await _db.customSelect(_overviewSql).get();
        return rows.map((r) => StoreStockSummaryModel(
          storeId:       r.read<String>('store_id'),
          storeName:     r.read<String>('store_name'),
          storeType:     r.read<String>('store_type'),
          productCount:  r.read<int>('product_count'),
          totalValueXaf: r.read<int>('total_value_xaf'),
          lowStockCount: r.read<int>('low_stock_count'),
        )).toList();
      }

      Future<List<StoreProductStockModel>> getStoreStockDetail(
          String storeId, {int page = 0, int size = 25, bool sortLowFirst = true}) async {

        final offset = page * size;
        final orderClause = sortLowFirst
            ? "CASE WHEN p.minimum_threshold > 0 AND sl.quantity <= p.minimum_threshold THEN 0 "
              "WHEN sl.quantity = 0 THEN 1 ELSE 2 END ASC, p.name ASC"
            : "p.name ASC";

        final sql = '''
          SELECT p.id AS product_id, p.name AS product_name,
                 sl.variant_id, sl.store_id, sl.quantity, p.minimum_threshold
          FROM stock_levels sl
          JOIN products p ON p.id = sl.product_id
          WHERE sl.store_id = ? AND p.archived = 0
          ORDER BY $orderClause
          LIMIT $size OFFSET $offset
        ''';

        final rows = await _db.customSelect(sql, variables: [Variable.withString(storeId)]).get();
        return rows.map((r) => _mapEntry(r)).toList();
      }

      Future<List<StoreProductStockModel>> searchAcrossStores(String query) async {
        if (query.trim().isEmpty) return [];
        const sql = '''
          SELECT p.id AS product_id, p.name AS product_name,
                 sl.variant_id, sl.store_id, sl.quantity, p.minimum_threshold
          FROM stock_levels sl
          JOIN products p ON p.id = sl.product_id
          JOIN stores   s ON s.id = sl.store_id AND s.is_active = 1
          WHERE p.name LIKE ? AND p.archived = 0
          ORDER BY p.name ASC, s.name ASC
        ''';
        final rows = await _db.customSelect(
          sql, variables: [Variable.withString('%$query%')]).get();
        return rows.map((r) => _mapEntry(r)).toList();
      }

      StoreProductStockModel _mapEntry(QueryRow r) {
        final qty = r.read<int>('quantity');
        final threshold = r.read<int>('minimum_threshold');
        final isLow = threshold > 0 && qty <= threshold;
        final isCritical = qty == 0;
        final status = isCritical ? 'CRITIQUE' : (isLow ? 'BAS' : 'NORMAL');
        return StoreProductStockModel(
          productId:        r.read<String>('product_id'),
          productName:      r.read<String>('product_name'),
          variantId:        r.readNullable<String>('variant_id'),
          variantLabel:     null,
          storeId:          r.read<String>('store_id'),
          quantity:         qty,
          minimumThreshold: threshold,
          status:           status,
          isLow:            isLow,
          isCritical:       isCritical,
        );
      }

      /// Upsert stock level rows received from a remote fetch into the local Drift cache.
      /// Called by [MultiStoreStockRepositoryImpl.getStoreStockDetail] when online.
      /// Uses raw SQL INSERT OR REPLACE to handle both insert and update.
      Future<void> upsertStockLevels(String storeId, List<StoreProductStockModel> entries) async {
        for (final e in entries) {
          await _db.customStatement(
            'INSERT OR REPLACE INTO stock_levels '
            '(product_id, store_id, variant_id, quantity, minimum_threshold) '
            'VALUES (?, ?, ?, ?, ?)',
            [e.productId, storeId, e.variantId, e.quantity, e.minimumThreshold],
          );
        }
      }
    }
    ```

    > **SQLite note**: SQLite stores booleans as integers (0/1). `p.archived = 0` and `s.is_active = 1` are the correct SQLite equivalents of Dart/backend `false`/`true`. Drift's `customSelect` runs directly against SQLite; there is no boolean→int auto-conversion for raw SQL.

---

- [ ] **Task 11 — Remote datasource**

  - [ ] 11.1 — Write `remote_multi_store_stock_datasource_test.dart` FIRST (mock Dio):
    ```dart
    test('getOverview() calls GET /api/v1/stock/overview', ...)
    test('getStoreStockDetail() calls GET /api/v1/stock/stores/{id}/products with params', ...)
    ```
  - [ ] 11.2 — Create `lib/features/inventory/data/datasource/remote_multi_store_stock_datasource.dart`:
    ```dart
    import 'package:dio/dio.dart';
    import '../../domain/model/store_product_stock_model.dart';
    import '../../domain/model/store_stock_summary_model.dart';

    class RemoteMultiStoreStockDataSource {
      final Dio _dio;
      RemoteMultiStoreStockDataSource({required Dio dio}) : _dio = dio;

      Future<List<StoreStockSummaryModel>> getOverview() async {
        final resp = await _dio.get('/api/v1/stock/overview');
        final List<dynamic> data = resp.data['data'] as List;
        return data.map((e) => StoreStockSummaryModel.fromJson(e as Map<String, dynamic>)).toList();
      }

      Future<({List<StoreProductStockModel> content, bool hasMore})> getStoreStockDetail(
          String storeId, {int page = 0, int size = 25, bool sortLowFirst = true}) async {
        final resp = await _dio.get(
          '/api/v1/stock/stores/$storeId/products',
          queryParameters: {'page': page, 'size': size, 'sortLowFirst': sortLowFirst},
        );
        final pageData = resp.data['data'] as Map<String, dynamic>;
        final List<dynamic> contentJson = pageData['content'] as List;
        final content = contentJson
            .map((e) => StoreProductStockModel.fromJson(e as Map<String, dynamic>))
            .toList();
        final hasMore = !(pageData['last'] as bool? ?? true);
        return (content: content, hasMore: hasMore);
      }
    }
    ```

---

- [ ] **Task 12 — Repository implementation**

  - [ ] 12.1 — Write `multi_store_stock_repository_impl_test.dart` FIRST (mocktail):
    ```dart
    test('getOverview() fetches from remote and returns fresh data when online', ...)
    test('getOverview() falls back to local Drift cache when remote throws', ...)
    test('getStoreStockDetail() fetches from remote, upserts to Drift, returns remote data when online', ...)
    test('getStoreStockDetail() falls back to local Drift data when remote throws', ...)
    test('searchAcrossStores() always delegates to local datasource only', ...)
    ```
  - [ ] 12.2 — Create `lib/features/inventory/data/repository/multi_store_stock_repository_impl.dart`:
    ```dart
    class MultiStoreStockRepositoryImpl implements MultiStoreStockRepository {
      final LocalMultiStoreStockDataSource _local;
      final RemoteMultiStoreStockDataSource _remote;

      MultiStoreStockRepositoryImpl({
        required LocalMultiStoreStockDataSource local,
        required RemoteMultiStoreStockDataSource remote,
      })  : _local  = local,
            _remote = remote;

      @override
      Future<List<StoreStockSummaryModel>> getOverview() async {
        try {
          // Online: fetch fresh aggregated data from backend and return it directly.
          // (Overview is computed from stock_levels; it cannot be stored as-is in Drift.
          //  When getStoreStockDetail is called online, individual rows are upserted locally.)
          return await _remote.getOverview();
        } catch (_) {
          // Offline / network error: compute from local Drift cache.
          return _local.getStoreOverviews();
        }
      }

      @override
      Future<List<StoreProductStockModel>> getStoreStockDetail(
          String storeId, {int page = 0, int size = 25, bool sortLowFirst = true}) async {
        try {
          // Online: fetch fresh data from backend.
          final result = await _remote.getStoreStockDetail(
              storeId, page: page, size: size, sortLowFirst: sortLowFirst);
          // Simultaneously write individual stock_levels rows into local Drift cache.
          await _local.upsertStockLevels(storeId, result.content);
          // Return fresh remote data — UI shows server truth.
          return result.content;
        } catch (_) {
          // Offline / network error: return local Drift data.
          return _local.getStoreStockDetail(storeId, page: page, size: size, sortLowFirst: sortLowFirst);
        }
      }

      @override
      Future<List<StoreProductStockModel>> searchAcrossStores(String query) {
        // Search is always local-only: real-time debounced queries (300ms) must be instant.
        return _local.searchAcrossStores(query);
      }
    }
    ```

---

- [ ] **Task 13 — Riverpod providers**

  - [ ] 13.1 — Write `global_stock_provider_test.dart` FIRST (ProviderContainer + mocktail):
    ```dart
    test('globalStockOverviewProvider builds successfully', ...)
    test('globalStockOverviewProvider returns StoreStockSummaryModel list', ...)
    test('storeStockDetailProvider returns products for storeId', ...)
    test('stockSearchProvider debounces and searches across stores', ...)
    ```
  - [ ] 13.2 — Create `lib/features/inventory/presentation/provider/global_stock_provider.dart`:
    ```dart
    import 'package:riverpod_annotation/riverpod_annotation.dart';
    import '../../../../core/di/providers.dart';
    import '../../data/datasource/local_multi_store_stock_datasource.dart';
    import '../../data/datasource/remote_multi_store_stock_datasource.dart';
    import '../../data/repository/multi_store_stock_repository_impl.dart';
    import '../../domain/model/store_product_stock_model.dart';
    import '../../domain/model/store_stock_summary_model.dart';
    import '../../domain/repository/multi_store_stock_repository.dart';

    part 'global_stock_provider.g.dart';

    // ── Infrastructure ─────────────────────────────────────────────────────

    final localMultiStoreStockDsProvider = Provider<LocalMultiStoreStockDataSource>((ref) {
      return LocalMultiStoreStockDataSource(ref.watch(appDatabaseProvider));
    });

    final remoteMultiStoreStockDsProvider = Provider<RemoteMultiStoreStockDataSource>((ref) {
      return RemoteMultiStoreStockDataSource(dio: ref.watch(dioProvider));
    });

    final multiStoreStockRepositoryProvider = Provider<MultiStoreStockRepository>((ref) {
      return MultiStoreStockRepositoryImpl(
        local:  ref.watch(localMultiStoreStockDsProvider),
        remote: ref.watch(remoteMultiStoreStockDsProvider),
      );
    });

    // ── Overview provider ──────────────────────────────────────────────────

    @riverpod
    Future<List<StoreStockSummaryModel>> globalStockOverview(GlobalStockOverviewRef ref) {
      return ref.watch(multiStoreStockRepositoryProvider).getOverview();
    }

    // ── Store detail provider (keyed by storeId) ───────────────────────────

    @riverpod
    Future<List<StoreProductStockModel>> storeStockDetail(
        StoreStockDetailRef ref, String storeId) {
      return ref.watch(multiStoreStockRepositoryProvider)
          .getStoreStockDetail(storeId, sortLowFirst: true);
    }

    // ── Search (local, debounced in UI) ────────────────────────────────────

    final stockSearchQueryProvider = StateProvider<String>((ref) => '');

    @riverpod
    Future<List<StoreProductStockModel>> stockSearchResults(StockSearchResultsRef ref) {
      final query = ref.watch(stockSearchQueryProvider);
      if (query.trim().length < 2) return Future.value([]);
      return ref.watch(multiStoreStockRepositoryProvider).searchAcrossStores(query);
    }
    ```
  - [ ] 13.3 — Run codegen: `dart run build_runner build --delete-conflicting-outputs`

---

- [ ] **Task 14 — GlobalStockOverviewPage + widgets**

  - [ ] 14.1 — Write widget tests FIRST (`testWidgets`):
    ```dart
    testWidgets('GlobalStockOverviewPage shows circular progress while loading', ...)
    testWidgets('GlobalStockOverviewPage renders StoreStockCard for each store', ...)
    testWidgets('GlobalStockOverviewPage shows empty state when no stores', ...)
    testWidgets('GlobalStockOverviewPage search bar filters results', ...)
    testWidgets('StoreStockCard shows store name and type icon', ...)
    testWidgets('StoreStockCard expands to show product list on tap', ...)
    testWidgets('StoreStockCard shows lowStockCount badge', ...)
    ```

  - [ ] 14.2 — Create `lib/features/inventory/presentation/widget/store_stock_card.dart`:
    ```dart
    /// StoreStockCard — Expandable card showing aggregated stock for one store.
    ///
    /// Header row: [icon] [storeName] [type badge] [product count] [low badge] [total value]
    /// Expanded: list of StoreProductStockTile widgets (paginated).
    /// GoF: Composite leaf widget.
    class StoreStockCard extends ConsumerStatefulWidget { ... }
    ```
    > - Store type icon: `Icons.warehouse_rounded` for WAREHOUSE, `Icons.storefront_rounded` for STORE
    > - Low stock badge: `Badge(label: Text('$lowStockCount'))` with `Colors.orange` background
    > - Total value: formatted as `${NumberFormat.compact().format(totalValueXaf)} XAF`
    > - Expanded section: uses `AnimatedCrossFade` for smooth expand/collapse

  - [ ] 14.3 — Create `lib/features/inventory/presentation/widget/store_product_stock_tile.dart`:
    ```dart
    /// Single product-stock row inside an expanded StoreStockCard.
    ///
    /// Layout: [product name] [qty chip] [status badge]
    /// Status badge colors:
    ///   NORMAL   → Green (colorSuccess)
    ///   BAS      → Orange (#FCC419)
    ///   CRITIQUE → Red (colorError)
    class StoreProductStockTile extends StatelessWidget { ... }
    ```

  - [ ] 14.4 — Create `lib/features/inventory/presentation/page/global_stock_overview_page.dart`:
    ```dart
    /// GlobalStockOverviewPage — centralized multi-store stock view.
    ///
    /// Route: /stock/overview
    /// Access: Catalogue page app bar "Vue globale" button + Settings > Gestion.
    ///
    /// Layout:
    ///   - SliverAppBar with "Vue Stock" title + gradient header
    ///   - Search bar (TextField, debounce 300ms via Timer)
    ///   - [if search active] Search results list (StoreProductStockTile per result)
    ///   - [if no search] Responsive grid of StoreStockCard:
    ///       compact (<600px):  1 column
    ///       medium (600-840px): 2 columns (SliverGrid crossAxisCount=2)
    ///       expanded (≥840px): 3 columns (SliverGrid crossAxisCount=3)
    ///   - Footer: "Valeur totale: {sum} XAF" (sum across all stores)
    ///
    /// AC4 — Observable (GoF: Observer):
    ///   The provider is invalidated when the app comes back to foreground
    ///   (use WidgetsBindingObserver.didChangeAppLifecycleState or
    ///    watch a syncStatusProvider that triggers invalidation on completion).
    class GlobalStockOverviewPage extends ConsumerStatefulWidget { ... }
    ```
    > **Responsive grid**: Use `LayoutBuilder` to get `constraints.maxWidth`, then:
    > ```dart
    > int crossAxisCount(double width) => width >= 840 ? 3 : (width >= 600 ? 2 : 1);
    > ```
    > Use `SliverGrid` with `SliverGridDelegateWithFixedCrossAxisCount` when items count > 1.
    > On 1-column layout, use `SliverList` instead (cards look better as full-width list).
    >
    > **Search debounce**: Use `Timer` (cancel + restart on each keystroke, 300ms delay).
    > When search term has ≥2 chars, show `stockSearchResultsProvider`; otherwise show overview.
    >
    > **AC4 — sync auto-refresh**: Watch the sync status; when sync completes:
    > ```dart
    > ref.listen(syncStatusProvider, (prev, next) {
    >   if (next == SyncStatus.completed) {
    >     ref.invalidate(globalStockOverviewProvider);
    >   }
    > });
    > ```
    > Check what the sync status provider is called in `features/sync_indicator/` and match exactly.

---

- [ ] **Task 15 — Navigation: add "Vue Globale" access points**

  - [ ] 15.1 — Add action button to `catalog_page.dart` app bar:
    ```dart
    // In the Row containing the sync button, add:
    Container(
      decoration: BoxDecoration(
        color: Colors.white.withOpacity(0.2),
        borderRadius: BorderRadius.circular(16),
      ),
      child: IconButton(
        icon: const Icon(Icons.warehouse_outlined, color: Colors.white),
        tooltip: 'Vue stock multi-boutiques',
        onPressed: () => context.push('/stock/overview'),
      ),
    ),
    ```
    > Add BEFORE the existing sync button so it's to the left. Both buttons stay in the same Row.

  - [ ] 15.2 — Add entry to `settings_page.dart` under the "Gestion" section (after "Mes boutiques"):
    ```dart
    const _Divider(),
    _SettingsTile(
      icon: Icons.inventory_2_rounded,
      iconColor: const Color(0xFF20C997),
      iconBg: const Color(0xFFD3F9D8),
      title: 'Stock multi-boutiques',
      subtitle: 'Vue centralisée des stocks par boutique',
      onTap: () => context.push('/stock/overview'),
    ),
    ```

  - [ ] 15.3 — Add route to `app_router.dart`:
    ```dart
    // Outside ShellRoute (full-screen page, no bottom nav needed)
    GoRoute(
      path: '/stock/overview',
      builder: (_, __) => const GlobalStockOverviewPage(),
    ),
    ```

---

- [ ] **Task 16 — Flutter test suite validation**

  - [ ] 16.1 — Run `flutter test --reporter=expanded` — all N tests must pass (zero failures).
  - [ ] 16.2 — Run `flutter analyze lib/features/inventory/` — zero warnings.
  - [ ] 16.3 — Verify codegen artifacts up-to-date: `store_stock_summary_model.freezed.dart`, `store_stock_summary_model.g.dart`, `store_product_stock_model.freezed.dart`, `store_product_stock_model.g.dart`, `global_stock_provider.g.dart`.

---

## Dev Notes

### Architecture Compliance

- **Backend module**: All new classes go in `com.keevo.catalog.stock.*` — not `inventory.stock.*` (that scaffold is for physical inventory counting, Story 6.x). The cross-store stock aggregation is naturally an extension of the existing stock level infrastructure.
- **Cross-module JPA issue**: `stores` is in `store.store.adapter.out.persistence`, `products` in `shared.infrastructure.persistence`. Using `JdbcTemplate` native SQL avoids cross-module JPA entity imports in `MultiStoreStockRepositoryAdapter` — this is intentional and architecturally correct.
- **TenantContext**: `JdbcTemplate` inherits the datasource connection that has already set `search_path = kv_xxxxxx` via `MultiTenantConnectionProvider`. Native SQL table names (`stores`, `products`, `stock_levels`) resolve correctly within the tenant schema automatically.
- **MCP port purity**: `GetMultiStoreOverviewQuery` and `GetStoreStockDetailQuery` are pure Java records. No `HttpServletRequest`, no Spring types in use case interfaces. `@PreAuthorize` stays in controller layer.
- **Page wrapper**: `Page<T>` from Spring Data is acceptable in port/in use case returns for this story — it carries pagination metadata that the controller needs to relay to the client. The `Page` type is from Spring Data JPA, not Spring MVC, so it's acceptable at the application layer boundary.
- **GoF Observer — Flutter**: The sync status provider is in `features/sync_indicator/`. Check the exact provider name (likely `syncStatusProvider` or `connectivityProvider`) and wire `ref.listen()` to call `ref.invalidate(globalStockOverviewProvider)` on sync completion.

### Online + Offline Simultaneous Strategy

**When online (happy path):**
- `getOverview()` → calls `GET /api/v1/stock/overview` → returns fresh aggregated data from server. The aggregated overview (productCount, totalValueXaf, lowStockCount) cannot be written back to Drift directly (it's a computed view over `stock_levels`), so the local cache is indirectly kept by `getStoreStockDetail`.
- `getStoreStockDetail()` → calls remote → **simultaneously** upserts each returned `stock_levels` row into the local Drift table via `upsertStockLevels()` → returns the fresh remote result to the UI.
- This means: after any online detail view, the local Drift cache reflects the server state for that store.

**When offline (or network error):**
- `getOverview()` → falls back to `_local.getStoreOverviews()` — SQL aggregation over cached `stock_levels`.
- `getStoreStockDetail()` → falls back to `_local.getStoreStockDetail()` — shows whatever was last cached.

**Search is always local-only**: `searchAcrossStores()` never calls the remote. It must be instant (<300ms debounce), which requires a synchronous Drift query — no network round-trip.

**No new Drift tables**: The `stock_levels` table already exists (schemaVersion=7). `upsertStockLevels()` does `INSERT OR REPLACE` into the existing table — no migration needed.

---

### DDL — No Schema Changes Required

- **schemaVersion stays at 7** — Story 3.2 adds no new tables or columns.
- All data is already in: `stores` (v7), `stock_levels` (v5), `products` (with `minimum_threshold` from v5).
- The `stock_levels.store_id` references `stores.id` implicitly (no FK exists yet — no FK needed for this story).
- The native SQL JOINs work because all three tables are in the same tenant schema.

### SQLite vs PostgreSQL Differences

For Drift local queries:
- SQLite booleans are `0`/`1` integers → use `p.archived = 0` and `s.is_active = 1` in raw SQL.
- SQLite doesn't support `ILIKE` → use `LIKE` (case-insensitive behavior depends on column collation, acceptable for French product names).
- SQLite `COALESCE(SUM(...), 0)` behaves identically to PostgreSQL for this query.

For backend (PostgreSQL):
- `p.archived = false` (native boolean), `s.is_active = true`.
- `ILIKE` available but not used (search is Flutter local-only).
- `COUNT(CASE WHEN ... THEN 1 END)` is standard SQL — works in both.

### File Structure — New Files

```
Backend (catalog/stock/):
  domain/model/
    StoreStockSummary.java              ← NEW
    StoreProductStockEntry.java         ← NEW
    StockStatus.java                    ← NEW (enum)
  domain/port/in/
    GetMultiStoreOverviewQuery.java     ← NEW
    GetMultiStoreOverviewUseCase.java   ← NEW
    GetStoreStockDetailQuery.java       ← NEW
    GetStoreStockDetailUseCase.java     ← NEW
  domain/port/out/
    MultiStoreStockRepository.java      ← NEW
  application/usecase/
    GetMultiStoreOverviewService.java   ← NEW
    GetStoreStockDetailService.java     ← NEW
  adapter/out/persistence/
    MultiStoreStockRepositoryAdapter.java ← NEW (JdbcTemplate)
  adapter/in/web/
    MultiStoreStockController.java      ← NEW
    dto/
      StoreStockSummaryDto.java         ← NEW
      StoreProductStockEntryDto.java    ← NEW

Tests (src/test/java/com/keevo/catalog/stock/):
  domain/model/
    StoreStockSummaryTest.java          ← NEW
    StoreProductStockEntryTest.java     ← NEW
  domain/port/out/
    MultiStoreStockRepositoryContractTest.java ← NEW
  application/usecase/
    GetMultiStoreOverviewServiceTest.java ← NEW
    GetStoreStockDetailServiceTest.java   ← NEW
  adapter/out/persistence/
    MultiStoreStockRepositoryAdapterTest.java ← NEW
  adapter/in/web/
    MultiStoreStockControllerTest.java   ← NEW

Scripts:
  keevo/scripts/curl-tests-story-3-2.sh  ← NEW

Flutter (lib/features/inventory/):
  domain/
    model/
      store_stock_summary_model.dart         ← NEW
      store_stock_summary_model.freezed.dart ← NEW (codegen)
      store_stock_summary_model.g.dart       ← NEW (codegen)
      store_product_stock_model.dart         ← NEW
      store_product_stock_model.freezed.dart ← NEW (codegen)
      store_product_stock_model.g.dart       ← NEW (codegen)
    repository/
      multi_store_stock_repository.dart      ← NEW
  data/
    datasource/
      local_multi_store_stock_datasource.dart  ← NEW
      remote_multi_store_stock_datasource.dart ← NEW
    repository/
      multi_store_stock_repository_impl.dart   ← NEW
  presentation/
    provider/
      global_stock_provider.dart     ← NEW
      global_stock_provider.g.dart   ← NEW (codegen)
    page/
      global_stock_overview_page.dart ← NEW
    widget/
      store_stock_card.dart           ← NEW
      store_product_stock_tile.dart   ← NEW

Modified files:
  lib/features/catalog/presentation/page/catalog_page.dart ← ADD warehouse icon button
  lib/features/settings/presentation/page/settings_page.dart ← ADD stock tile
  lib/core/router/app_router.dart ← ADD /stock/overview route
```

### Testing Standards Summary

| Layer | Test type | Framework |
|---|---|---|
| Domain models | Unit (pure Java) | JUnit 5 |
| Application services | Unit (mock deps) | JUnit 5 + Mockito |
| JdbcTemplate adapter | Integration (@DataJpaTest) | JUnit 5 + H2 in-mem |
| REST controller | Slice (@WebMvcTest) | MockMvc + Mockito |
| Flutter domain model | Unit | `flutter_test` |
| Flutter local datasource | Integration (in-memory Drift) | `flutter_test` |
| Flutter repository impl | Unit (mocktail) | `flutter_test` |
| Flutter providers | Unit (ProviderContainer + mocktail) | `flutter_test` + `riverpod` |
| Flutter widgets | Widget test | `flutter_test` |
| E2E backend | cURL script | bash + python3 |

### References

- Stock level store join pattern → [Source: story 2-3 `StockLevelRepository.java`]
- JdbcTemplate tenant-scoped queries → [Source: architecture.md#Backend Architecture]
- Composite GoF pattern → [Source: architecture.md#GoF Design Patterns]
- Drift custom SELECT → [Source: story 2-3 `LocalStockDataSource`]
- Responsive grid (LayoutBuilder + SliverGrid) → [Source: ux-design-specification.md#Platform Strategy]
- `ApiResponseWrapper` pattern → [Source: `StockController.java`, `StoreController.java`]
- `Page<T>` Spring Data pagination → [Source: `GetStockMovementHistoryUseCase.java`]
- SQLite boolean integer → [Source: story 3-1 `local_store_datasource.dart` which uses `isActive = 1`]

---

## Dev Agent Record

### Agent Model Used

Claude Sonnet 4.6

### Debug Log References

### Completion Notes List

- Code review session 1: H1 (responsive grid), H2 (pagination), H3 (tap-to-navigate), H4 (widget tests), C1 (file list), M2 (sort order), M3 (status) fixed
- sort fix: Drift `getStoreStockDetail` now uses `AND sl.quantity > 0` in CASE (matches backend logic)
- AC2: `_ProductList` → `ConsumerStatefulWidget` with page-based loading + "Voir plus" button
- AC3: search result tile tap sets `highlightedStoreIdProvider` → matching `StoreStockCard` auto-expands + scrolls into view via `Scrollable.ensureVisible`
- AC5: `SliverLayoutBuilder` → 1/2/3 column grid based on `crossAxisExtent` (<600/600–840/≥840)

### File List

**Backend — new files**
- `keevo/backend/src/main/java/com/keevo/catalog/stock/domain/model/StoreStockSummary.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/domain/model/StoreProductStockEntry.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/domain/model/StockStatus.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/domain/port/in/GetMultiStoreOverviewQuery.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/domain/port/in/GetMultiStoreOverviewUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/domain/port/in/GetStoreStockDetailQuery.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/domain/port/in/GetStoreStockDetailUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/domain/port/out/MultiStoreStockRepository.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/application/usecase/GetMultiStoreOverviewService.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/application/usecase/GetStoreStockDetailService.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/out/persistence/MultiStoreStockRepositoryAdapter.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/in/web/MultiStoreStockController.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/in/web/dto/StoreStockSummaryDto.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/in/web/dto/StoreProductStockEntryDto.java`
- `keevo/backend/src/test/java/com/keevo/catalog/stock/domain/model/StoreStockSummaryTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/stock/domain/model/StoreProductStockEntryTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/stock/domain/port/out/MultiStoreStockRepositoryContractTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/stock/application/usecase/GetMultiStoreOverviewServiceTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/stock/application/usecase/GetStoreStockDetailServiceTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/stock/adapter/out/persistence/MultiStoreStockRepositoryAdapterTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/stock/adapter/in/web/MultiStoreStockControllerTest.java`
- `keevo/scripts/curl-tests-story-3-2.sh`

**Flutter — new files**
- `keevo/app/lib/features/inventory/domain/model/store_stock_summary_model.dart`
- `keevo/app/lib/features/inventory/domain/model/store_stock_summary_model.freezed.dart`
- `keevo/app/lib/features/inventory/domain/model/store_stock_summary_model.g.dart`
- `keevo/app/lib/features/inventory/domain/model/store_product_stock_model.dart`
- `keevo/app/lib/features/inventory/domain/model/store_product_stock_model.freezed.dart`
- `keevo/app/lib/features/inventory/domain/model/store_product_stock_model.g.dart`
- `keevo/app/lib/features/inventory/domain/repository/multi_store_stock_repository.dart`
- `keevo/app/lib/features/inventory/data/datasource/local_multi_store_stock_datasource.dart`
- `keevo/app/lib/features/inventory/data/datasource/remote_multi_store_stock_datasource.dart`
- `keevo/app/lib/features/inventory/data/repository/multi_store_stock_repository_impl.dart`
- `keevo/app/lib/features/inventory/presentation/provider/global_stock_provider.dart`
- `keevo/app/lib/features/inventory/presentation/provider/global_stock_provider.g.dart`
- `keevo/app/lib/features/inventory/presentation/page/global_stock_overview_page.dart`
- `keevo/app/lib/features/inventory/presentation/widget/store_stock_card.dart`
- `keevo/app/lib/features/inventory/presentation/widget/store_product_stock_tile.dart`
- `keevo/app/test/features/inventory/domain/model/store_stock_summary_model_test.dart`
- `keevo/app/test/features/inventory/domain/model/store_product_stock_model_test.dart`
- `keevo/app/test/features/inventory/domain/repository/multi_store_stock_repository_test.dart`
- `keevo/app/test/features/inventory/data/datasource/local_multi_store_stock_datasource_test.dart`
- `keevo/app/test/features/inventory/data/datasource/remote_multi_store_stock_datasource_test.dart`
- `keevo/app/test/features/inventory/data/repository/multi_store_stock_repository_impl_test.dart`
- `keevo/app/test/features/inventory/presentation/provider/global_stock_provider_test.dart`
- `keevo/app/test/features/inventory/presentation/widget/store_product_stock_tile_test.dart`
- `keevo/app/test/features/inventory/presentation/widget/store_stock_card_test.dart`
- `keevo/app/test/features/inventory/presentation/page/global_stock_overview_page_test.dart`

**Flutter — modified files**
- `keevo/app/lib/core/router/app_router.dart` — added `/stock/overview` GoRoute
- `keevo/app/lib/features/catalog/presentation/page/catalog_page.dart` — added warehouse icon button
- `keevo/app/lib/features/settings/presentation/page/settings_page.dart` — added Stock multi-boutiques tile
