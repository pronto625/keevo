package com.keevo.commerce.sale.adapter.in.rest;

import com.keevo.commerce.sale.adapter.in.rest.dto.RecordSaleRequestDto;
import com.keevo.commerce.sale.adapter.in.rest.dto.RecordSaleResponseDto;
import com.keevo.commerce.sale.domain.model.SaleFactory;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase.RecordSaleCommand;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase.SaleItemCommand;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sales")
@PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
public class SaleController {

    private final RecordSaleUseCase recordSaleUseCase;
    private final JwtTokenProvider jwtTokenProvider;

    public SaleController(RecordSaleUseCase recordSaleUseCase,
                          JwtTokenProvider jwtTokenProvider) {
        this.recordSaleUseCase = recordSaleUseCase;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @PostMapping
    public ResponseEntity<ApiResponseWrapper<RecordSaleResponseDto>> recordSale(
            @Valid @RequestBody RecordSaleRequestDto request,
            HttpServletRequest httpRequest) {

        UUID actorId = extractActorId();
        Claims claims = extractClaims(httpRequest);
        UUID jwtStoreId = jwtTokenProvider.extractStoreId(claims);
        String role = jwtTokenProvider.extractRole(claims);

        // storeId guard: EMPLOYEE must sell only in their assigned store
        if ("EMPLOYEE".equals(role) && jwtStoreId == null) {
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

        var command = new RecordSaleCommand(
                request.saleId(),
                actorId,
                storeId,
                request.clientId(),
                request.paymentMode(),
                request.mobileMoneyRef(),
                request.items().stream()
                        .map(i -> new SaleItemCommand(
                                i.productId(), i.variantId(), i.productName(),
                                i.appliedUnitPrice(), i.quantity()))
                        .toList()
        );

        recordSaleUseCase.recordSale(command);

        // Build response from command data (sale was created via SaleFactory)
        var response = new RecordSaleResponseDto(
                request.saleId(),
                "COMPLETED",
                command.items().stream()
                        .mapToInt(i -> i.appliedUnitPrice() * i.quantity())
                        .sum(),
                java.time.Instant.now()
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(response));
    }

    private UUID extractActorId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private Claims extractClaims(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        String token = authHeader.substring(7); // "Bearer " prefix
        return jwtTokenProvider.parseToken(token);
    }
}
