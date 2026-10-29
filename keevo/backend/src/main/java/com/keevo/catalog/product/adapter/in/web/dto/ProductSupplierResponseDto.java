package com.keevo.catalog.product.adapter.in.web.dto;

import com.keevo.crm.api.SupplierView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response of {@code GET /api/v1/products/{productId}/supplier} (Story 2.5 AC5).
 *
 * <p>Same fields, in the same order, as the crm supplier payload — built from the crm public API
 * (Story 12.2a) instead of the crm web DTO, so the JSON is unchanged.
 */
public record ProductSupplierResponseDto(
        UUID id,
        String name,
        String phone,
        String email,
        boolean archived,
        List<UUID> productIds,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProductSupplierResponseDto from(SupplierView s) {
        return new ProductSupplierResponseDto(s.id(), s.name(), s.phone(), s.email(), s.archived(),
                s.productIds(), s.createdAt(), s.updatedAt());
    }
}
