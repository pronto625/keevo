package com.keevo.inventory.stock.adapter.in.web;

import com.keevo.inventory.stock.adapter.in.StockReadScopes;
import com.keevo.inventory.stock.adapter.in.web.dto.CrossStoreAvailabilityResponseDto;
import com.keevo.inventory.stock.adapter.in.web.dto.StoreProductStockEntryDto;
import com.keevo.inventory.stock.adapter.in.web.dto.StoreStockSummaryDto;
import com.keevo.inventory.stock.domain.port.in.GetCrossStoreAvailabilityQuery;
import com.keevo.inventory.stock.domain.port.in.GetCrossStoreAvailabilityUseCase;
import com.keevo.inventory.stock.domain.port.in.GetMultiStoreOverviewQuery;
import com.keevo.inventory.stock.domain.port.in.GetMultiStoreOverviewUseCase;
import com.keevo.inventory.stock.domain.port.in.GetStoreStockDetailQuery;
import com.keevo.inventory.stock.domain.port.in.GetStoreStockDetailUseCase;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.RequiresEntitlement;
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
 * GET /api/v1/stock/overview                                         → all active stores summary
 * GET /api/v1/stock/stores/{storeId}/products?page&size&sortLowFirst → store product stock list
 * GET /api/v1/stock/products/{productId}/availability                → cross-store availability
 * </pre>
 *
 * Stories 3.2 & 3.4. Story 16.13 (reprise 10/11/2026, D1): a store member (MANAGER, EMPLOYEE, VIEWER) only reads
 * their own store — overview and availability limited to it, another store's detail refused (403 « Vous ne pouvez
 * consulter que le stock de votre boutique. »); the OWNER reads every store.
 */
@Tag(name = "Multi-Store Stock", description = "Centralized multi-store stock overview")
@RestController
@RequestMapping("/api/v1/stock")
@PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE','VIEWER')")
public class MultiStoreStockController {

    private final GetMultiStoreOverviewUseCase     overviewUseCase;
    private final GetStoreStockDetailUseCase       detailUseCase;
    private final GetCrossStoreAvailabilityUseCase availabilityUseCase;
    /** Story 22.19 (D19) — {@code null} (tests built by hand): the multi-store entitlement is always required. */
    private final com.keevo.shared.module.EntitlementService entitlements;
    private final com.keevo.store.api.StoreQueryPort stores;

    public MultiStoreStockController(GetMultiStoreOverviewUseCase overviewUseCase,
                                     GetStoreStockDetailUseCase detailUseCase,
                                     GetCrossStoreAvailabilityUseCase availabilityUseCase) {
        this(overviewUseCase, detailUseCase, availabilityUseCase, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MultiStoreStockController(GetMultiStoreOverviewUseCase overviewUseCase,
                                     GetStoreStockDetailUseCase detailUseCase,
                                     GetCrossStoreAvailabilityUseCase availabilityUseCase,
                                     com.keevo.shared.module.EntitlementService entitlements,
                                     com.keevo.store.api.StoreQueryPort stores) {
        this.overviewUseCase     = overviewUseCase;
        this.detailUseCase       = detailUseCase;
        this.availabilityUseCase = availabilityUseCase;
        this.entitlements        = entitlements;
        this.stores              = stores;
    }

    /**
     * Story 22.19 (D19) — the stock of one store is core stock reading (module {@code inventory}). One rule (same as
     * {@code GET /products?storeId=}): a member with an assigned store reads that store only (403 otherwise); the
     * owner, or a member without an assigned store, reads any store — the company's default store freely, any other
     * one with the {@code multi-store} offer. Without an entitlement service (tests built by hand) no offer is checked.
     */
    private void assertStoreReadable(UUID storeId) {
        UUID own = com.keevo.shared.infrastructure.security.CurrentActor.assignedStoreIdOrNull();
        if (own != null) {
            if (!own.equals(storeId)) {
                throw com.keevo.shared.domain.exception.DomainException.userFacing(
                        com.keevo.shared.domain.exception.ErrorCode.FORBIDDEN,
                        "Vous ne pouvez consulter que le stock de votre boutique.");
            }
            return;
        }
        if (entitlements == null || stores == null) {
            return;
        }
        String tenant = com.keevo.shared.infrastructure.persistence.TenantContext.requireCurrent();
        if (stores.defaultStore(tenant).filter(storeId::equals).isPresent()) {
            return;
        }
        entitlements.assertEnabled(tenant, "multi-store");
    }

    @Operation(summary = "Get stock summary for all active stores + warehouse")
    @RequiresEntitlement(value = "multi-store", access = RequiresEntitlement.Access.READ)
    @GetMapping("/overview")
    public ResponseEntity<ApiResponseWrapper<List<StoreStockSummaryDto>>> getOverview() {
        List<StoreStockSummaryDto> dtos = overviewUseCase
                .execute(new GetMultiStoreOverviewQuery(StockReadScopes.current()))
                .stream()
                .map(StoreStockSummaryDto::from)
                .toList();
        return ResponseEntity.ok(ApiResponseWrapper.ok(dtos));
    }

    @Operation(summary = "Get paginated product stock list for a specific store")
    @RequiresEntitlement(value = "inventory", access = RequiresEntitlement.Access.READ)
    @GetMapping("/stores/{storeId}/products")
    public ResponseEntity<ApiResponseWrapper<Page<StoreProductStockEntryDto>>> getStoreDetail(
            @PathVariable UUID storeId,
            @RequestParam(defaultValue = "0")     int page,
            @RequestParam(defaultValue = "25")    int size,
            @RequestParam(defaultValue = "true")  boolean sortLowFirst,
            @RequestParam(defaultValue = "false") boolean lowOnly) {

        if (size < 1 || size > 100) size = 25; // safety clamp
        assertStoreReadable(storeId); // Story 22.19 (D19)

        Page<StoreProductStockEntryDto> result = detailUseCase
                .execute(new GetStoreStockDetailQuery(storeId, page, size, sortLowFirst, lowOnly,
                        StockReadScopes.current()))
                .map(StoreProductStockEntryDto::from);

        return ResponseEntity.ok(ApiResponseWrapper.ok(result));
    }

    @Operation(summary = "Get product stock availability across all active stores")
    @RequiresEntitlement(value = "multi-store", access = RequiresEntitlement.Access.READ)
    @GetMapping("/products/{productId}/availability")
    public ResponseEntity<ApiResponseWrapper<CrossStoreAvailabilityResponseDto>> getProductAvailability(
            @PathVariable UUID productId) {
        var result = availabilityUseCase.execute(new GetCrossStoreAvailabilityQuery(productId, StockReadScopes.current()));
        return ResponseEntity.ok(ApiResponseWrapper.ok(CrossStoreAvailabilityResponseDto.from(result)));
    }
}
