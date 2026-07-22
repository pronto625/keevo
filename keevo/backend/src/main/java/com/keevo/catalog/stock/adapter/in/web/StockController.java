package com.keevo.catalog.stock.adapter.in.web;

import com.keevo.catalog.product.adapter.in.web.dto.ProductResponseDto;
import com.keevo.catalog.stock.adapter.in.web.dto.AdjustStockRequestDto;
import com.keevo.catalog.stock.adapter.in.web.dto.RecordStockEntryRequestDto;
import com.keevo.catalog.stock.adapter.in.web.dto.SetThresholdRequestDto;
import com.keevo.catalog.stock.adapter.in.web.dto.StockLevelResponseDto;
import com.keevo.catalog.stock.adapter.in.web.dto.StockMovementResponseDto;
import com.keevo.catalog.stock.application.usecase.AdjustStockUseCase;
import com.keevo.catalog.stock.application.usecase.GetCurrentStockUseCase;
import com.keevo.catalog.stock.application.usecase.GetStockMovementHistoryUseCase;
import com.keevo.catalog.stock.application.usecase.RecordStockEntryUseCase;
import com.keevo.catalog.stock.application.usecase.SetStockThresholdUseCase;
import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.AuthDetails;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * StockController — REST API for stock management (Story 2.3).
 *
 * <p>All endpoints require JWT auth ({@code hasRole('USER')}).
 * TenantContext is set upstream by the JWT filter — no manual management needed.
 *
 * <pre>
 * GET    /api/v1/products/{productId}/stock             → current levels
 * POST   /api/v1/products/{productId}/stock/entry       → receive stock
 * POST   /api/v1/products/{productId}/stock/adjust      → set absolute quantity
 * PATCH  /api/v1/products/{productId}/threshold         → set alert threshold
 * GET    /api/v1/products/{productId}/stock/history     → paginated movements
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/products/{productId}")
@PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
public class StockController {

    private final GetCurrentStockUseCase getCurrentStockUseCase;
    private final RecordStockEntryUseCase recordStockEntryUseCase;
    private final AdjustStockUseCase adjustStockUseCase;
    private final SetStockThresholdUseCase setStockThresholdUseCase;
    private final GetStockMovementHistoryUseCase getStockMovementHistoryUseCase;

    public StockController(GetCurrentStockUseCase getCurrentStockUseCase,
                           RecordStockEntryUseCase recordStockEntryUseCase,
                           AdjustStockUseCase adjustStockUseCase,
                           SetStockThresholdUseCase setStockThresholdUseCase,
                           GetStockMovementHistoryUseCase getStockMovementHistoryUseCase) {
        this.getCurrentStockUseCase        = getCurrentStockUseCase;
        this.recordStockEntryUseCase       = recordStockEntryUseCase;
        this.adjustStockUseCase            = adjustStockUseCase;
        this.setStockThresholdUseCase      = setStockThresholdUseCase;
        this.getStockMovementHistoryUseCase = getStockMovementHistoryUseCase;
    }

    // ──────────────────────────────────────────────────────────────────
    // GET /api/v1/products/{productId}/stock
    // ──────────────────────────────────────────────────────────────────

    /**
     * Returns all stock levels for the product across all stores.
     * Response includes minimumThreshold and isLow per level.
     */
    @GetMapping("/stock")
    public ResponseEntity<ApiResponseWrapper<List<StockLevelResponseDto>>> getCurrentStock(
            @PathVariable UUID productId) {

        var snapshot = getCurrentStockUseCase.execute(productId);
        int threshold = snapshot.product().getMinimumThreshold();

        List<StockLevelResponseDto> dtos = snapshot.stockLevels().stream()
                .map(level -> StockLevelResponseDto.from(level, threshold))
                .toList();

        return ResponseEntity.ok(ApiResponseWrapper.ok(dtos));
    }

    // ──────────────────────────────────────────────────────────────────
    // POST /api/v1/products/{productId}/stock/entry
    // ──────────────────────────────────────────────────────────────────

    /**
     * Records an incoming stock delivery (STOCK_ENTRY movement).
     * Story 12.6 — EMPLOYEE scoped to assigned store (FR36).
     */
    @PostMapping("/stock/entry")
    public ResponseEntity<ApiResponseWrapper<StockMovementResponseDto>> recordStockEntry(
            @PathVariable UUID productId,
            @Valid @RequestBody RecordStockEntryRequestDto request) {

        UUID assignedStoreId = extractAssignedStoreId();
        if (assignedStoreId != null && !assignedStoreId.equals(request.storeId())) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "EMPLOYEE cannot record stock entry for another store");
        }

        UUID actorId = actorIdFromContext();
        var movement = recordStockEntryUseCase.execute(
                productId, request.variantId(), request.storeId(),
                request.quantity(), actorId, request.notes());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(StockMovementResponseDto.from(movement)));
    }

    // ──────────────────────────────────────────────────────────────────
    // POST /api/v1/products/{productId}/stock/adjust
    // ──────────────────────────────────────────────────────────────────

    /**
     * Sets the stock to an absolute quantity (ADJUSTMENT movement).
     * Story 12.6 — EMPLOYEE scoped to assigned store (FR36).
     */
    @PostMapping("/stock/adjust")
    public ResponseEntity<ApiResponseWrapper<StockMovementResponseDto>> adjustStock(
            @PathVariable UUID productId,
            @Valid @RequestBody AdjustStockRequestDto request) {

        UUID assignedStoreId = extractAssignedStoreId();
        if (assignedStoreId != null && !assignedStoreId.equals(request.storeId())) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "EMPLOYEE cannot adjust stock for another store");
        }

        UUID actorId = actorIdFromContext();
        var movement = adjustStockUseCase.execute(
                productId, request.variantId(), request.storeId(),
                request.newQuantity(), actorId, request.notes());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(StockMovementResponseDto.from(movement)));
    }

    // ──────────────────────────────────────────────────────────────────
    // PATCH /api/v1/products/{productId}/threshold
    // ──────────────────────────────────────────────────────────────────

    /**
     * Sets the minimum stock threshold for alert evaluation.
     */
    @PatchMapping("/threshold")
    public ResponseEntity<ApiResponseWrapper<ProductResponseDto>> setThreshold(
            @PathVariable UUID productId,
            @Valid @RequestBody SetThresholdRequestDto request) {

        var product = setStockThresholdUseCase.execute(productId, request.minimumThreshold());
        return ResponseEntity.ok(ApiResponseWrapper.ok(ProductResponseDto.fromDomain(product)));
    }

    // ──────────────────────────────────────────────────────────────────
    // GET /api/v1/products/{productId}/stock/history
    // ──────────────────────────────────────────────────────────────────

    /**
     * Returns paginated stock movement history with optional filters.
     *
     * @param type    optional movement type filter
     * @param from    optional ISO-8601 start timestamp
     * @param to      optional ISO-8601 end timestamp
     * @param storeId optional store filter
     * @param page    0-based page (default 0)
     * @param size    page size (default 20)
     */
    @GetMapping("/stock/history")
    public ResponseEntity<ApiResponseWrapper<Page<StockMovementResponseDto>>> getHistory(
            @PathVariable UUID productId,
            @RequestParam(required = false) MovementType type,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<StockMovementResponseDto> dtos = getStockMovementHistoryUseCase
                .execute(productId, type, from, to, storeId, page, size)
                .map(StockMovementResponseDto::from);

        return ResponseEntity.ok(ApiResponseWrapper.ok(dtos));
    }

    // ──────────────────────────────────────────────────────────────────

    private UUID actorIdFromContext() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    /**
     * Story 12.6 — Returns the store UUID embedded in JWT details for EMPLOYEE tokens,
     * or null for OWNER tokens (OWNER is never scoped).
     *
     * <p>Story 14.10: adapted to {@link com.keevo.shared.infrastructure.security.AuthDetails}.
     */
    private UUID extractAssignedStoreId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object details = auth.getDetails();
        if (details instanceof AuthDetails ad && ad.storeId() != null) {
            return ad.storeId();
        }
        boolean isEmployee = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_EMPLOYEE".equals(a.getAuthority()));
        if (isEmployee) {
            throw new DomainException(ErrorCode.FORBIDDEN, "EMPLOYEE token missing assigned store");
        }
        return null;
    }
}
