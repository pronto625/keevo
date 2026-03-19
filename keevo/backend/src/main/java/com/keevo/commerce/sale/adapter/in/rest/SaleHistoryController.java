package com.keevo.commerce.sale.adapter.in.rest;

import com.keevo.commerce.sale.adapter.in.rest.dto.*;
import com.keevo.commerce.sale.domain.model.Sale;
import com.keevo.commerce.sale.domain.model.SaleItem;
import com.keevo.commerce.sale.domain.port.in.GetSalesHistoryUseCase;
import com.keevo.commerce.sale.domain.port.in.GetSalesHistoryUseCase.SalesHistoryQuery;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * SaleHistoryController — REST controller for sales history queries.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 */
@RestController
@RequestMapping("/api/v1/sales/history")
@PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
public class SaleHistoryController {

    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");

    private final GetSalesHistoryUseCase getSalesHistoryUseCase;
    private final JwtTokenProvider jwtTokenProvider;

    public SaleHistoryController(GetSalesHistoryUseCase getSalesHistoryUseCase,
                                  JwtTokenProvider jwtTokenProvider) {
        this.getSalesHistoryUseCase = getSalesHistoryUseCase;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * GET /api/v1/sales/history — Get sales history for a store.
     *
     * <p>Query parameters:
     * - storeId (required): UUID of the store
     * - from (optional): Start date (YYYY-MM-DD), defaults to today - 30 days
     * - to (optional): End date (YYYY-MM-DD), defaults to today
     * - employeeId (optional): Filter by employee (OWNER only)
     * - page (optional): Page number (0-indexed), default 0
     * - size (optional): Page size, default 20, max 100
     *
     * @return 200 OK with paginated SaleHistoryPageDto
     */
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<SaleHistoryPageDto>> getSalesHistory(
            @RequestParam UUID storeId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) UUID employeeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest httpRequest) {

        // Validate size
        size = Math.min(size, 100);

        // Parse dates
        LocalDate fromDate = from != null ? LocalDate.parse(from) : LocalDate.now(WAT).minusDays(30);
        LocalDate toDate = to != null ? LocalDate.parse(to) : LocalDate.now(WAT);

        // Convert to Instant range (start of fromDate to end of toDate)
        Instant fromInstant = fromDate.atStartOfDay(WAT).toInstant();
        Instant toInstant = toDate.plusDays(1).atStartOfDay(WAT).toInstant();

        // Extract JWT claims
        Claims claims = extractClaims(httpRequest);
        UUID actorId = UUID.fromString(claims.getSubject());
        String role = jwtTokenProvider.extractRole(claims);

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"));

        SalesHistoryQuery query = new SalesHistoryQuery(
                storeId,
                employeeId,
                fromInstant,
                toInstant,
                role,
                page,
                size
        );

        Page<Sale> salesPage = getSalesHistoryUseCase.getSalesHistory(query);

        // Map to DTOs
        List<SaleHistoryItemDto> items = salesPage.getContent().stream()
                .map(this::toHistoryDto)
                .toList();

        SaleHistoryPageDto response = new SaleHistoryPageDto(
                items,
                salesPage.getNumber(),
                salesPage.getSize(),
                salesPage.getTotalElements(),
                salesPage.getTotalPages()
        );

        return ResponseEntity.ok(ApiResponseWrapper.ok(response));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Claims extractClaims(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            return jwtTokenProvider.parseToken(token);
        }
        throw new IllegalStateException("No valid JWT token found");
    }

    private SaleHistoryItemDto toHistoryDto(Sale sale) {
        List<SaleItemHistoryDto> items = sale.getItems().stream()
                .map(this::toItemHistoryDto)
                .toList();

        return new SaleHistoryItemDto(
                sale.getId(),
                sale.getStoreId(),
                sale.getEmployeeId(),
                sale.getClientId(),
                sale.getPaymentMode().name(),
                sale.getTotalAmount(),
                sale.getDiscountAmount(),
                sale.getStatus().name(),
                sale.getOccurredAt(),
                sale.getCreatedAt(),
                items
        );
    }

    private SaleItemHistoryDto toItemHistoryDto(SaleItem item) {
        return new SaleItemHistoryDto(
                item.getId(),
                item.getProductId(),
                item.getVariantId(),
                item.getProductName(),
                item.getCatalogueUnitPrice(),
                item.getAppliedUnitPrice(),
                item.getQuantity(),
                item.getSubtotal()
        );
    }
}
