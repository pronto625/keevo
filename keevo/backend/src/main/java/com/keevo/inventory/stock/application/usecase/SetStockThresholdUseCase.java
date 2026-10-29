package com.keevo.inventory.stock.application.usecase;

import com.keevo.catalog.api.ProductCommandPort;
import com.keevo.catalog.api.ProductQueryPort;
import com.keevo.catalog.api.ProductView;
import com.keevo.shared.event.catalog.stock.StockThresholdChangedEvent;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * SetStockThresholdUseCase — sets the minimum stock threshold on a product.
 *
 * <p>The threshold is stored on the catalog product, changed through the catalog public API
 * (12.2a). A real change is recorded in the action history
 * (StockThresholdChangedEvent → STOCK_THRESHOLD_UPDATED).
 *
 * Story 2.3.
 */
@Service
public class SetStockThresholdUseCase {

    private final ProductQueryPort productQuery;
    private final ProductCommandPort productCommand;
    private final ApplicationEventPublisher eventPublisher;

    public SetStockThresholdUseCase(ProductQueryPort productQuery,
                                    ProductCommandPort productCommand,
                                    ApplicationEventPublisher eventPublisher) {
        this.productQuery = productQuery;
        this.productCommand = productCommand;
        this.eventPublisher = eventPublisher;
    }

    /**
     * @param productId    target product
     * @param threshold    new minimum threshold (must be >= 0)
     * @param actorId      user making the change (audit)
     * @return updated product
     * @throws DomainException PRODUCT_NOT_FOUND if product does not exist
     * @throws IllegalArgumentException if threshold is negative
     */
    @Transactional
    public ProductView execute(UUID productId, int threshold, UUID actorId) {
        if (threshold < 0) {
            throw new IllegalArgumentException("Threshold cannot be negative");
        }

        ProductView product = productQuery.findById(TenantContext.getCurrentTenant(), productId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + productId));

        int previous = product.minimumThreshold();
        ProductView saved = productCommand.changeMinimumThreshold(TenantContext.getCurrentTenant(), productId, threshold);

        if (previous != threshold) {
            eventPublisher.publishEvent(new StockThresholdChangedEvent(
                    productId, product.name(), previous, threshold,
                    actorId, TenantContext.getCurrentTenant(), Instant.now()));
        }
        return saved;
    }
}
