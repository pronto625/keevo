package com.keevo.inventory.stock.adapter.in.api;

import com.keevo.inventory.api.StockQueryPort;
import com.keevo.inventory.stock.domain.entity.StockLevel;
import com.keevo.inventory.stock.domain.port.out.StockLevelRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Implements the published {@link StockQueryPort} on the stock level repository (Story 12.2b).
 * No transaction of its own: it joins the caller's.
 */
@Component
class StockQueryAdapter implements StockQueryPort {

    private final StockLevelRepository stockLevelRepository;

    StockQueryAdapter(StockLevelRepository stockLevelRepository) {
        this.stockLevelRepository = stockLevelRepository;
    }

    @Override
    public int availableQuantity(String tenantSchema, UUID productId, UUID variantId, UUID storeId) {
        TenantContext.requireCurrent(tenantSchema);
        var level = variantId != null
                ? stockLevelRepository.findByProductVariantAndStore(productId, variantId, storeId)
                : stockLevelRepository.findByProductAndStore(productId, storeId);
        return level.map(StockLevel::getQuantity).orElse(0);
    }
}
