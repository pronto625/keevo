package com.keevo.commerce.api;

import java.util.Optional;
import java.util.UUID;

/** Sale reads for other modules (Story 12.2c), in the current tenant. */
public interface SaleQueryPort {

    /** The sale with its items, or empty when unknown. */
    Optional<SaleView> findById(UUID saleId);
}
