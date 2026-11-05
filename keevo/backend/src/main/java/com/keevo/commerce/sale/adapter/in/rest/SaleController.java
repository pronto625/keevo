package com.keevo.commerce.sale.adapter.in.rest;

import com.keevo.shared.domain.MemberRoles;
import com.keevo.commerce.sale.adapter.in.rest.dto.RecordSaleRequestDto;
import com.keevo.commerce.sale.adapter.in.rest.dto.RecordSaleResponseDto;
import com.keevo.commerce.sale.domain.model.SaleFactory;
import com.keevo.commerce.sale.domain.model.SaleStatus;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase.RecordSaleCommand;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase.SaleItemCommand;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.RequiresEntitlement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sales")
@PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE','VIEWER')")
public class SaleController {

    private final RecordSaleUseCase recordSaleUseCase;

    public SaleController(RecordSaleUseCase recordSaleUseCase) {
        this.recordSaleUseCase = recordSaleUseCase;
    }

    @RequiresEntitlement("pos")
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<RecordSaleResponseDto>> recordSale(
            @Valid @RequestBody RecordSaleRequestDto request) {

        UUID actorId = extractActorId();
        // Story 16.11 — role and store come from the server-side access state, never the token.
        String role = CurrentActor.role();
        UUID jwtStoreId = CurrentActor.assignedStoreIdOrNull();

        // storeId guard: EMPLOYEE must sell only in their assigned store
        if (MemberRoles.isStoreMember(role) && jwtStoreId == null) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Employee must have an assigned store");
        }

        // Use JWT storeId for the sale (authoritative source)
        UUID storeId = jwtStoreId;
        if (storeId == null && "OWNER".equals(role)) {
            // OWNER without storeId in JWT — accept from request body
            storeId = request.storeId();
        }
        if (storeId == null) {
            throw new DomainException(ErrorCode.FORBIDDEN, "No store context available");
        }

        SaleStatus requestedStatus = request.status() != null
                ? SaleStatus.valueOf(request.status()) : null;

        var command = new RecordSaleCommand(
                request.saleId(),
                actorId,
                storeId,
                request.clientId(),
                request.paymentMode(),
                request.mobileMoneyRef(),
                request.discountAmount(),
                requestedStatus,
                request.items().stream()
                        .map(i -> new SaleItemCommand(
                                i.itemId(),
                                i.productId(), i.variantId(), i.productName(),
                                i.catalogueUnitPrice(), i.appliedUnitPrice(), i.quantity()))
                        .toList()
        );

        recordSaleUseCase.recordSale(command);

        // Build response — totalAmount = subtotal − discountAmount
        int subtotal = command.items().stream()
                .mapToInt(i -> i.appliedUnitPrice() * i.quantity())
                .sum();
        int totalAmount = subtotal - command.discountAmount();

        String responseStatus = requestedStatus == SaleStatus.PENDING_VALIDATION
                ? "PENDING_VALIDATION" : "COMPLETED";

        var response = new RecordSaleResponseDto(
                request.saleId(),
                responseStatus,
                totalAmount,
                request.discountAmount(),
                java.time.Instant.now()
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(response));
    }

    private UUID extractActorId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

}
