package com.keevo.reporting.profitability.adapter.in.rest;

import com.keevo.reporting.profitability.adapter.in.rest.dto.ProductProfitabilityDetailDto;
import com.keevo.reporting.profitability.adapter.in.rest.dto.ProductProfitabilityEntryDto;
import com.keevo.reporting.profitability.adapter.in.rest.dto.StorePerformanceEntryDto;
import com.keevo.reporting.profitability.domain.model.RankingMetric;
import com.keevo.reporting.profitability.domain.model.SortOption;
import com.keevo.reporting.profitability.domain.port.in.GetProductProfitabilityUseCase;
import com.keevo.reporting.profitability.domain.port.in.GetProductProfitabilityUseCase.ProfitabilityQuery;
import com.keevo.reporting.profitability.domain.port.in.GetStorePerformanceUseCase;
import com.keevo.reporting.profitability.domain.port.in.GetStorePerformanceUseCase.StorePerformanceQuery;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * ProfitabilityController — REST adapter for profitability reporting (OWNER only).
 *
 * <pre>
 * GET /api/v1/reporting/profitability/products            → product list
 * GET /api/v1/reporting/profitability/products/{id}       → product detail
 * GET /api/v1/reporting/profitability/stores              → store comparison
 * </pre>
 *
 * Story 7.4, Task 9.1.
 */
@Tag(name = "Profitability", description = "Profitability & store comparative reporting (OWNER)")
@RestController
@RequestMapping("/api/v1/reporting/profitability")
@PreAuthorize("hasRole('OWNER')")
public class ProfitabilityController {

    private static final int DEFAULT_PERIOD_DAYS = 30;

    private final GetProductProfitabilityUseCase profitabilityUseCase;
    private final GetStorePerformanceUseCase storePerformanceUseCase;

    public ProfitabilityController(GetProductProfitabilityUseCase profitabilityUseCase,
                                   GetStorePerformanceUseCase storePerformanceUseCase) {
        this.profitabilityUseCase = profitabilityUseCase;
        this.storePerformanceUseCase = storePerformanceUseCase;
    }

    // ── GET /products ─────────────────────────────────────────────────────────

    @Operation(summary = "Product profitability list for a date range (OWNER only)")
    @GetMapping("/products")
    public ResponseEntity<ApiResponseWrapper<List<ProductProfitabilityEntryDto>>> getProductProfitability(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "MARGIN_PCT_DESC") String sort,
            @RequestParam(required = false) UUID storeId) {

        LocalDate dateTo   = to   != null ? LocalDate.parse(to)   : LocalDate.now();
        LocalDate dateFrom = from != null ? LocalDate.parse(from)  : dateTo.minusDays(DEFAULT_PERIOD_DAYS - 1);

        var query = new ProfitabilityQuery(
                TenantContext.getCurrentTenant(), dateFrom, dateTo,
                SortOption.fromString(sort), storeId);

        var entries = profitabilityUseCase.getEntries(query).stream()
                .map(ProductProfitabilityEntryDto::from)
                .toList();

        return ResponseEntity.ok(ApiResponseWrapper.ok(entries));
    }

    // ── GET /products/{productId} ─────────────────────────────────────────────

    @Operation(summary = "Product profitability detail (OWNER only)")
    @GetMapping("/products/{productId}")
    public ResponseEntity<ApiResponseWrapper<ProductProfitabilityDetailDto>> getProductDetail(
            @PathVariable UUID productId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {

        LocalDate dateTo   = to   != null ? LocalDate.parse(to)   : LocalDate.now();
        LocalDate dateFrom = from != null ? LocalDate.parse(from)  : dateTo.minusDays(DEFAULT_PERIOD_DAYS - 1);

        var query = new ProfitabilityQuery(
                TenantContext.getCurrentTenant(), dateFrom, dateTo,
                SortOption.MARGIN_PCT_DESC, null);

        var detail = profitabilityUseCase.getDetail(query, productId);
        return ResponseEntity.ok(ApiResponseWrapper.ok(ProductProfitabilityDetailDto.from(detail)));
    }

    // ── GET /stores ───────────────────────────────────────────────────────────

    @Operation(summary = "Store comparative performance ranking (OWNER only)")
    @GetMapping("/stores")
    public ResponseEntity<ApiResponseWrapper<List<StorePerformanceEntryDto>>> getStorePerformance(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "CA") String metric) {

        LocalDate dateTo   = to   != null ? LocalDate.parse(to)   : LocalDate.now();
        LocalDate dateFrom = from != null ? LocalDate.parse(from)  : dateTo.minusDays(DEFAULT_PERIOD_DAYS - 1);

        var query = new StorePerformanceQuery(
                TenantContext.getCurrentTenant(), dateFrom, dateTo,
                RankingMetric.fromString(metric));

        var entries = storePerformanceUseCase.getRanking(query).stream()
                .map(StorePerformanceEntryDto::from)
                .toList();

        return ResponseEntity.ok(ApiResponseWrapper.ok(entries));
    }
}
