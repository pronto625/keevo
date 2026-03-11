package com.keevo.catalog.contact.adapter.in.web.dto;

import com.keevo.catalog.contact.domain.entity.Supplier;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for Supplier API endpoints (Story 2.5).
 */
public record SupplierResponseDto(
        UUID id,
        String name,
        String phone,
        String email,
        boolean archived,
        Instant createdAt,
        Instant updatedAt
) {
    public static SupplierResponseDto fromDomain(Supplier supplier) {
        return new SupplierResponseDto(
                supplier.id(),
                supplier.name(),
                supplier.phone(),
                supplier.email(),
                supplier.archived(),
                supplier.createdAt(),
                supplier.updatedAt()
        );
    }
}
