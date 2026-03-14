package com.keevo.catalog.stock.adapter.in.web;

import com.keevo.catalog.stock.adapter.in.web.dto.StoreProductStockEntryDto;
import com.keevo.catalog.stock.adapter.in.web.dto.StoreStockSummaryDto;
import com.keevo.catalog.stock.domain.port.in.GetMultiStoreOverviewQuery;
import com.keevo.catalog.stock.domain.port.in.GetMultiStoreOverviewUseCase;
import com.keevo.catalog.stock.domain.port.in.GetStoreStockDetailQuery;
import com.keevo.catalog.stock.domain.port.in.GetStoreStockDetailUseCase;
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
    private final GetStoreStockDetailUseCase   detailUseCase;

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
