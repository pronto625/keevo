package com.keevo.catalog.stock.adapter.in.web;

import com.keevo.catalog.stock.adapter.in.web.dto.StockTransferResponseDto;
import com.keevo.catalog.stock.adapter.in.web.dto.TransferStockRequestDto;
import com.keevo.catalog.stock.application.usecase.CompleteTransferService;
import com.keevo.catalog.stock.application.usecase.ExecuteTransferService;
import com.keevo.catalog.stock.application.usecase.GetTransferHistoryService;
import com.keevo.catalog.stock.domain.port.in.CompleteTransferCommand;
import com.keevo.catalog.stock.domain.port.in.GetTransferHistoryQuery;
import com.keevo.catalog.stock.domain.port.in.TransferStockCommand;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * StockTransferController — REST API for inter-store stock transfers (Story 3.3).
 *
 * <p>All endpoints require JWT auth ({@code hasRole('USER')}).
 * TenantContext is set upstream by the JWT filter.
 *
 * <pre>
 * POST /api/v1/stock/transfers              → initiate transfer (Step 1), returns 201 IN_TRANSIT
 * POST /api/v1/stock/transfers/{id}/complete → receive transfer  (Step 2), returns 200 COMPLETED
 * GET  /api/v1/stock/transfers              → paginated transfer history
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/stock/transfers")
@PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
public class StockTransferController {

    private final ExecuteTransferService executeTransferService;
    private final CompleteTransferService completeTransferService;
    private final GetTransferHistoryService getTransferHistoryService;

    public StockTransferController(ExecuteTransferService executeTransferService,
                                   CompleteTransferService completeTransferService,
                                   GetTransferHistoryService getTransferHistoryService) {
        this.executeTransferService    = executeTransferService;
        this.completeTransferService   = completeTransferService;
        this.getTransferHistoryService = getTransferHistoryService;
    }

    // ──────────────────────────────────────────────────────────────────
    // POST /api/v1/stock/transfers
    // ──────────────────────────────────────────────────────────────────

    /**
     * Step 1 — Initiate an inter-store stock transfer.
     * Deducts stock from source. Returns 201 Created with status IN_TRANSIT.
     */
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<StockTransferResponseDto>> transfer(
            @Valid @RequestBody TransferStockRequestDto request) {

        UUID actorId = extractActorId();

        var command = new TransferStockCommand(
                request.sourceStoreId(),
                request.destinationStoreId(),
                request.productId(),
                request.variantId(),
                request.quantity(),
                actorId,
                request.notes()
        );

        var result = executeTransferService.execute(command);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(StockTransferResponseDto.from(result)));
    }

    // ──────────────────────────────────────────────────────────────────
    // POST /api/v1/stock/transfers/{id}/complete
    // ──────────────────────────────────────────────────────────────────

    /**
     * Step 2 — Receive (complete) an IN_TRANSIT transfer at the destination store.
     * Credits stock at the destination. Returns 200 OK with status COMPLETED.
     */
    @PostMapping("/{id}/complete")
    public ResponseEntity<ApiResponseWrapper<StockTransferResponseDto>> complete(
            @PathVariable UUID id) {

        UUID actorId = extractActorId();
        var command  = new CompleteTransferCommand(id, actorId);
        var result   = completeTransferService.execute(command);
        return ResponseEntity.ok(ApiResponseWrapper.ok(StockTransferResponseDto.from(result)));
    }

    // ──────────────────────────────────────────────────────────────────
    // GET /api/v1/stock/transfers
    // ──────────────────────────────────────────────────────────────────

    /**
     * Paginated transfer history with optional filters.
     */
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<Map<String, Object>>> getHistory(
            @RequestParam(required = false) UUID source,
            @RequestParam(required = false) UUID destination,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        var query = new GetTransferHistoryQuery(source, destination, from, to, page, size);
        Page<StockTransferResponseDto> result =
                getTransferHistoryService.execute(query)
                                        .map(StockTransferResponseDto::from);

        // Manually flatten Page to avoid Jackson serialization issues with Unpaged pageable
        Map<String, Object> pageData = Map.of(
                "content", result.getContent(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages(),
                "number", result.getNumber(),
                "size", result.getSize()
        );

        return ResponseEntity.ok(ApiResponseWrapper.ok(pageData));
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private UUID extractActorId() {
        var principal = SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal();
        return UUID.fromString(principal.toString());
    }
}
