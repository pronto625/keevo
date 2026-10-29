package com.keevo.commerce.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Sale reads for other modules (Story 12.2c). The {@code tenantSchema} parameter is checked against
 * the bound tenant ({@code TenantContext.requireCurrent}, fail-fast); the repository reads the bound
 * tenant.
 */
public interface SaleQueryPort {

    /** The sale with its items, or empty when unknown. */
    Optional<SaleView> findById(String tenantSchema, UUID saleId);
}
