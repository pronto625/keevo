package com.keevo.commerce.sale.adapter.in.rest;

import com.keevo.shared.infrastructure.security.CurrentActor;
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
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
        // Fails closed (FORBIDDEN) for an EMPLOYEE token without an assigned store.
        UUID assignedStoreId = CurrentActor.assignedStoreId();
        boolean isEmployee = CurrentActor.isEmployee();
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
        UUID actorId = CurrentActor.actorId();
        UUID assignedStoreId = CurrentActor.assignedStoreId();
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
        if (!CurrentActor.isOwner()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only OWNER can cancel pending sales");
        }
        UUID actorId = CurrentActor.actorId();
        UUID assignedStoreId = CurrentActor.assignedStoreId();
        cancelPendingSaleUseCase.cancelPendingSale(
                new CancelPendingSaleCommand(id, actorId, assignedStoreId, request.justification()));
        return ResponseEntity.ok(ApiResponseWrapper.ok(Map.of("message", "Sale cancelled")));
    }

    @PostMapping("/{id}/correct")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponseWrapper<Map<String, String>>> correctSale(
            @PathVariable UUID id, @Valid @RequestBody CorrectSaleRequestDto request) {
        // Defense-in-depth: explicit role check (testable with standaloneSetup)
        if (!CurrentActor.isOwner()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only OWNER can correct a sale");
        }
        correctSaleUseCase.correctSale(new CorrectSaleCommand(
                id, CurrentActor.actorId(), CurrentActor.assignedStoreId(), request.justification(), request.itemQuantities(),
                request.correctionId()));
        return ResponseEntity.ok(ApiResponseWrapper.ok(Map.of("message", "Sale corrected")));
    }
}
