package com.keevo.commerce.sale.adapter.in.rest;

import com.keevo.commerce.sale.adapter.in.rest.dto.CancelSaleRequestDto;
import com.keevo.commerce.sale.adapter.in.rest.dto.CorrectSaleRequestDto;
import com.keevo.commerce.sale.adapter.in.rest.dto.PendingSaleResponseDto;
import com.keevo.commerce.sale.adapter.in.rest.dto.ValidateSaleRequestDto;
import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase.CancelPendingSaleCommand;
import com.keevo.commerce.sale.domain.port.in.CorrectSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.CorrectSaleUseCase.CorrectSaleCommand;
import com.keevo.commerce.sale.domain.port.in.GetPendingSalesUseCase;
import com.keevo.commerce.sale.domain.port.in.ValidateSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.ValidateSaleUseCase.ValidateSaleCommand;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.AuthDetails;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sales")
public class PendingSaleController {

    private final GetPendingSalesUseCase getPendingSalesUseCase;
    private final ValidateSaleUseCase validateSaleUseCase;
    private final CancelPendingSaleUseCase cancelPendingSaleUseCase;
    private final CorrectSaleUseCase correctSaleUseCase;

    public PendingSaleController(GetPendingSalesUseCase getPendingSalesUseCase,
                                 ValidateSaleUseCase validateSaleUseCase,
                                 CancelPendingSaleUseCase cancelPendingSaleUseCase,
                                 CorrectSaleUseCase correctSaleUseCase) {
        this.getPendingSalesUseCase = getPendingSalesUseCase;
        this.validateSaleUseCase = validateSaleUseCase;
        this.cancelPendingSaleUseCase = cancelPendingSaleUseCase;
        this.correctSaleUseCase = correctSaleUseCase;
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    public ResponseEntity<ApiResponseWrapper<List<PendingSaleResponseDto>>> getPendingSales() {
        UUID assignedStoreId = extractAssignedStoreId();
        boolean isEmployee = isEmployeeRole();
        if (isEmployee && assignedStoreId == null) {
            throw new AccessDeniedException("EMPLOYEE store assignment is missing");
        }
        var sales = (assignedStoreId != null)
                ? getPendingSalesUseCase.getPendingSalesByStore(assignedStoreId)
                : getPendingSalesUseCase.getPendingSales();
        var dtos = sales.stream()
            .map(s -> isEmployee ? PendingSaleResponseDto.fromForEmployee(s) : PendingSaleResponseDto.from(s))
                .toList();
        return ResponseEntity.ok(ApiResponseWrapper.ok(dtos));
    }

    @PostMapping("/{id}/validate")
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    public ResponseEntity<ApiResponseWrapper<Map<String, String>>> validateSale(
            @PathVariable UUID id,
            @Valid @RequestBody ValidateSaleRequestDto request) {
        UUID actorId = extractActorId();
        UUID assignedStoreId = extractAssignedStoreId();
        validateSaleUseCase.validateSale(new ValidateSaleCommand(
                id, actorId, assignedStoreId, request.justification(), request.productIdRemappings(),
                request.initialStockEntries()));
        return ResponseEntity.ok(ApiResponseWrapper.ok(Map.of("message", "Sale validated")));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponseWrapper<Map<String, String>>> cancelSale(
            @PathVariable UUID id,
            @Valid @RequestBody CancelSaleRequestDto request) {
        // Defense-in-depth: explicit role check (testable with standaloneSetup)
        if (!isOwnerRole()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only OWNER can cancel pending sales");
        }
        UUID actorId = extractActorId();
        UUID assignedStoreId = extractAssignedStoreId();
        cancelPendingSaleUseCase.cancelPendingSale(
                new CancelPendingSaleCommand(id, actorId, assignedStoreId, request.justification()));
        return ResponseEntity.ok(ApiResponseWrapper.ok(Map.of("message", "Sale cancelled")));
    }

    @PostMapping("/{id}/correct")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponseWrapper<Map<String, String>>> correctSale(
            @PathVariable UUID id, @Valid @RequestBody CorrectSaleRequestDto request) {
        // Defense-in-depth: explicit role check (testable with standaloneSetup)
        if (!isOwnerRole()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only OWNER can correct a sale");
        }
        correctSaleUseCase.correctSale(new CorrectSaleCommand(
                id, extractActorId(), extractAssignedStoreId(), request.justification(), request.itemQuantities()));
        return ResponseEntity.ok(ApiResponseWrapper.ok(Map.of("message", "Sale corrected")));
    }

    private UUID extractActorId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private boolean isEmployeeRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_EMPLOYEE".equals(a.getAuthority()));
    }

    private boolean isOwnerRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_OWNER".equals(a.getAuthority()));
    }

    /**
     * AC5: Returns the store UUID embedded in JWT details for EMPLOYEE tokens,
     * or null for OWNER tokens (OWNER sees all stores).
     *
     * <p>Story 14.10: adapted to {@link AuthDetails}.
     */
    private UUID extractAssignedStoreId() {
        Object details = SecurityContextHolder.getContext().getAuthentication().getDetails();
        return (details instanceof AuthDetails ad) ? ad.storeId() : null;
    }
}
