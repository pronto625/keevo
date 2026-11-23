package com.keevo.catalog.product.adapter.in.api;

import com.keevo.catalog.api.ProductStockCounterPort;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Story 22.19 (D11) — the catalog's {@link ProductStockCounterPort}: one conditional UPDATE of the product row. */
@Component
class ProductStockCounterAdapter implements ProductStockCounterPort {

    private final ProductRepository products;

    ProductStockCounterAdapter(ProductRepository products) {
        this.products = products;
    }

    @Override
    public void syncStockQuantity(String tenantSchema, UUID productId, int total) {
        TenantContext.requireCurrent(tenantSchema);
        products.updateStockQuantity(productId, total);
    }
}
