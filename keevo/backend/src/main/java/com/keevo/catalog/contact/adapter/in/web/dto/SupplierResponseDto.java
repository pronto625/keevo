package com.keevo.catalog.contact.adapter.in.web.dto;

import com.keevo.catalog.contact.domain.entity.Supplier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for Supplier API endpoints (Story 2.5).
 *
 * <p>Includes {@code productIds} so the mobile client can persist product links
 * without a separate GET /suppliers/{id} call.
 */
public record SupplierResponseDto(
        UUID id,
        String name,
        String phone,
        String email,
        boolean archived,
        List<UUID> productIds,
        Instant createdAt,
        Instant updatedAt
) {
    /** Full form — used by PATCH and CREATE responses (productIds known). */
    public static SupplierResponseDto fromDomain(Supplier supplier, List<UUID> productIds) {
        return new SupplierResponseDto(
                supplier.id(),
                supplier.name(),
                supplier.phone(),
                supplier.email(),
                supplier.archived(),
                productIds,
                supplier.createdAt(),
                supplier.updatedAt()
        );
    }

    /** List form — used by GET /suppliers when productIds are provided per-item. */
    public static SupplierResponseDto fromDomain(Supplier supplier) {
        return fromDomain(supplier, List.of());
    }
}
