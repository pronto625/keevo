package com.keevo.commerce.sale.domain.port.out;

import java.util.UUID;

/**
 * ProductStatusPort — driven port to query product status across domain boundaries.
 * Story 4.3 — used by SaleValidationCascadeService to check if products are ACTIVE.
 */
public interface ProductStatusPort {
    String getProductStatus(UUID productId);
}
