package com.keevo.commerce.sale.adapter.in.rest;

import com.keevo.commerce.sale.adapter.in.rest.dto.CancelSaleRequestDto;
import com.keevo.commerce.sale.adapter.in.rest.dto.PendingSaleResponseDto;
import com.keevo.commerce.sale.adapter.in.rest.dto.ValidateSaleRequestDto;
import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase.CancelPendingSaleCommand;
import com.keevo.commerce.sale.domain.port.in.GetPendingSalesUseCase;
import com.keevo.commerce.sale.domain.port.in.ValidateSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.ValidateSaleUseCase.ValidateSaleCommand;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sales")
@PreAuthorize("hasRole('OWNER')")
public class PendingSaleController {

    private final GetPendingSalesUseCase getPendingSalesUseCase;
    private final ValidateSaleUseCase validateSaleUseCase;
    private final CancelPendingSaleUseCase cancelPendingSaleUseCase;

    public PendingSaleController(GetPendingSalesUseCase getPendingSalesUseCase,
                                 ValidateSaleUseCase validateSaleUseCase,
                                 CancelPendingSaleUseCase cancelPendingSaleUseCase) {
        this.getPendingSalesUseCase = getPendingSalesUseCase;
        this.validateSaleUseCase = validateSaleUseCase;
        this.cancelPendingSaleUseCase = cancelPendingSaleUseCase;
    }

    @GetMapping("/pending")
    public ResponseEntity<ApiResponseWrapper<List<PendingSaleResponseDto>>> getPendingSales() {
        var sales = getPendingSalesUseCase.getPendingSales();
        var dtos = sales.stream()
                .map(PendingSaleResponseDto::from)
                .toList();
        return ResponseEntity.ok(ApiResponseWrapper.ok(dtos));
    }

    @PostMapping("/{id}/validate")
    public ResponseEntity<ApiResponseWrapper<Map<String, String>>> validateSale(
            @PathVariable UUID id,
            @Valid @RequestBody ValidateSaleRequestDto request) {
        UUID actorId = extractActorId();
        validateSaleUseCase.validateSale(new ValidateSaleCommand(
                id, actorId, request.justification(), request.productIdRemappings(),
                request.initialStockEntries()));
        return ResponseEntity.ok(ApiResponseWrapper.ok(Map.of("message", "Sale validated")));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponseWrapper<Map<String, String>>> cancelSale(
            @PathVariable UUID id,
            @Valid @RequestBody CancelSaleRequestDto request) {
        UUID actorId = extractActorId();
        cancelPendingSaleUseCase.cancelPendingSale(
                new CancelPendingSaleCommand(id, actorId, request.justification()));
        return ResponseEntity.ok(ApiResponseWrapper.ok(Map.of("message", "Sale cancelled")));
    }

    private UUID extractActorId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
