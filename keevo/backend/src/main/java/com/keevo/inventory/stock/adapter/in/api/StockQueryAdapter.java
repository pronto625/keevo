package com.keevo.inventory.stock.adapter.in.api;

import com.keevo.inventory.api.RecordedMovement;
import com.keevo.inventory.api.StockQueryPort;
import com.keevo.inventory.stock.domain.entity.StockLevel;
import com.keevo.inventory.stock.domain.port.out.StockLevelRepository;
import com.keevo.inventory.stock.domain.port.out.StockMovementRepository;
import com.keevo.inventory.stock.domain.service.StockTrackingPolicy;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Implements the published {@link StockQueryPort} on the stock level repository (Story 12.2b).
 * No transaction of its own: it joins the caller's.
 */
@Component
class StockQueryAdapter implements StockQueryPort {

    private final StockLevelRepository stockLevelRepository;
    private final StockMovementRepository stockMovementRepository;
    private final StockTrackingPolicy trackingPolicy;

    StockQueryAdapter(StockLevelRepository stockLevelRepository,
                      StockMovementRepository stockMovementRepository,
                      StockTrackingPolicy trackingPolicy) {
        this.trackingPolicy = trackingPolicy;
        this.stockLevelRepository = stockLevelRepository;
        this.stockMovementRepository = stockMovementRepository;
    }

    @Override
    public int availableQuantity(String tenantSchema, UUID productId, UUID variantId, UUID storeId) {
        TenantContext.requireCurrent(tenantSchema);
        var level = variantId != null
                ? stockLevelRepository.findByProductVariantAndStore(productId, variantId, storeId)
                : stockLevelRepository.findByProductAndStore(productId, storeId);
        return level.map(StockLevel::getQuantity).orElse(0);
    }

    @Override
    public boolean isUntracked(String tenantSchema, UUID productId) {
        TenantContext.requireCurrent(tenantSchema);
        return trackingPolicy.isUntracked(tenantSchema, productId);
    }

    @Override
    public Optional<RecordedMovement> findMovement(String tenantSchema,
                                                                                    UUID movementId) {
        TenantContext.requireCurrent(tenantSchema);
        return stockMovementRepository.findById(movementId).map(m -> new RecordedMovement(
                m.getId(), m.getProductId(), m.getVariantId(), m.getStoreId(), m.getMovementType().name(),
                m.getQuantityChange()));
    }
}
