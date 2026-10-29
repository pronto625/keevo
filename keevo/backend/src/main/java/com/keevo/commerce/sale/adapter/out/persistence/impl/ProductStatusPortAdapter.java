package com.keevo.commerce.sale.adapter.out.persistence.impl;

import com.keevo.catalog.api.ProductQueryPort;
import com.keevo.commerce.sale.domain.port.out.ProductStatusPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * ProductStatusPortAdapter — resolves a product's status for the sale domain.
 * Story 4.3 — bridges the sale domain to the catalog, through its public API since 12.2a.
 */
@Component
public class ProductStatusPortAdapter implements ProductStatusPort {

    private final ProductQueryPort productQuery;

    public ProductStatusPortAdapter(ProductQueryPort productQuery) {
        this.productQuery = productQuery;
    }

    @Override
    public String getProductStatus(UUID productId) {
        return productQuery.findStatusName(TenantContext.getCurrentTenant(), productId).orElse("UNKNOWN");
    }
}
