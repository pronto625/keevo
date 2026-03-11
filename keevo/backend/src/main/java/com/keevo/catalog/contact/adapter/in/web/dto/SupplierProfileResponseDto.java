package com.keevo.catalog.contact.adapter.in.web.dto;

import com.keevo.catalog.contact.application.usecase.GetSupplierProfileUseCase;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for the supplier profile endpoint, including linked product IDs (Story 2.5).
 */
public record SupplierProfileResponseDto(
        UUID id,
        String name,
        String phone,
        String email,
        boolean archived,
        Instant createdAt,
        Instant updatedAt,
        List<UUID> productIds
) {
    public static SupplierProfileResponseDto fromResult(GetSupplierProfileUseCase.SupplierProfileResult result) {
        var s = result.supplier();
        return new SupplierProfileResponseDto(
                s.id(), s.name(), s.phone(), s.email(),
                s.archived(), s.createdAt(), s.updatedAt(),
                result.productIds()
        );
    }
}
