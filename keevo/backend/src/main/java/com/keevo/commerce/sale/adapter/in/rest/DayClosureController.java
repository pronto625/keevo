package com.keevo.commerce.sale.adapter.in.rest;

import com.keevo.commerce.sale.adapter.in.rest.dto.*;
import com.keevo.commerce.sale.domain.model.DayClosure;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase.CloseDayCommand;
import com.keevo.commerce.sale.domain.port.out.DayClosureRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * DayClosureController — REST controller for day closure operations.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 */
@RestController
@RequestMapping("/api/v1/day-closures")
@PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
public class DayClosureController {

    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");

    private final CloseDayUseCase closeDayUseCase;
    private final DayClosureRepository dayClosureRepository;
    private final JwtTokenProvider jwtTokenProvider;

    public DayClosureController(CloseDayUseCase closeDayUseCase,
                                 DayClosureRepository dayClosureRepository,
                                 JwtTokenProvider jwtTokenProvider) {
        this.closeDayUseCase = closeDayUseCase;
        this.dayClosureRepository = dayClosureRepository;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * POST /api/v1/day-closures — Manually close the day for a store.
     *
     * @return 201 Created with DayClosureResponseDto
     * @throws 409 Conflict if day already closed
     */
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<DayClosureResponseDto>> closeDay(
            @Valid @RequestBody CloseDayRequestDto request,
            HttpServletRequest httpRequest) {

        UUID actorId = extractActorId(httpRequest);
        String tenantId = extractTenantId(httpRequest);

        CloseDayCommand command = new CloseDayCommand(
                request.storeId(),
                actorId,
                tenantId,
                false // manual closure
        );

        var closure = closeDayUseCase.closeDay(command);
        var summary = closure.getSummary();

        // Build a response DTO from the closure
        DayClosureSummaryDto summaryDto = new DayClosureSummaryDto(
                summary.totalSales(),
                summary.totalRevenue(),
                summary.topProductId(),
                summary.topProductName(),
                summary.topProductQty(),
                summary.cashAmount(),
                summary.momoAmount(),
                summary.pendingSalesCount(),
                summary.pendingSalesTotal()
        );

        DayClosureResponseDto response = new DayClosureResponseDto(
                closure.getId(),
                closure.getStoreId(),
                closure.getActorId(),
                closure.getClosedAt(),
                closure.isAutomatic(),
                summaryDto
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(response));
    }

    /**
     * GET /api/v1/day-closures?storeId={storeId}&date={date} — Get closure for a store on a date.
     * Story 12.6 — OWNER-only (governance).
     *
     * @return 200 OK with DayClosureResponseDto or 404 if not found
     */
    @GetMapping
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponseWrapper<DayClosureResponseDto>> getClosure(
            @RequestParam UUID storeId,
            @RequestParam(required = false) String date,
            HttpServletRequest httpRequest) {

        // Defense-in-depth: explicit role check (testable with standaloneSetup)
        if (!isOwnerRole()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only OWNER can read day closure history");
        }

        LocalDate closureDate = date != null ? LocalDate.parse(date) : LocalDate.now(WAT);
        String tenantId = extractTenantId(httpRequest);

        List<DayClosure> closures = dayClosureRepository.findByStoreIdAndDate(storeId, closureDate);

        if (closures.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponseWrapper.error(
                            "Aucune clôture trouvée pour cette date",
                            "NOT_FOUND",
                            "CLOSURE_NOT_FOUND",
                            null
                    ));
        }

        DayClosure closure = closures.get(0);
        DayClosureResponseDto response = new DayClosureResponseDto(
                closure.getId(),
                closure.getStoreId(),
                closure.getActorId(),
                closure.getClosedAt(),
                closure.isAutomatic(),
                DayClosureSummaryDto.fromDomain(closure.getSummary())
        );

        return ResponseEntity.ok(ApiResponseWrapper.ok(response));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID extractActorId(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            Claims claims = jwtTokenProvider.parseToken(token);
            String userId = claims.getSubject();
            return UUID.fromString(userId);
        }
        throw new IllegalStateException("No valid JWT token found");
    }

    private String extractTenantId(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            Claims claims = jwtTokenProvider.parseToken(token);
            return jwtTokenProvider.extractTenantId(claims);
        }
        throw new IllegalStateException("No valid JWT token found");
    }

    private boolean isOwnerRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_OWNER".equals(a.getAuthority()));
    }
}
