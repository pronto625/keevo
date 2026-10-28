package com.keevo.inventory.stock.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.inventory.stock.domain.event.StockThresholdChangedEvent;
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
 * <p>Uses the copy-with pattern ({@code Product.withMinimumThreshold}) to
 * preserve immutability in the domain entity. A real change is recorded in the
 * action history (StockThresholdChangedEvent → STOCK_THRESHOLD_UPDATED).
 *
 * Story 2.3.
 */
@Service
public class SetStockThresholdUseCase {

    private final ProductRepository productRepository;
    private final ApplicationEventPublisher eventPublisher;

    public SetStockThresholdUseCase(ProductRepository productRepository,
                                    ApplicationEventPublisher eventPublisher) {
        this.productRepository = productRepository;
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
    public Product execute(UUID productId, int threshold, UUID actorId) {
        if (threshold < 0) {
            throw new IllegalArgumentException("Threshold cannot be negative");
        }

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + productId));

        int previous = product.getMinimumThreshold();
        Product updated = product.withMinimumThreshold(threshold);
        Product saved = productRepository.save(updated);

        if (previous != threshold) {
            eventPublisher.publishEvent(new StockThresholdChangedEvent(
                    productId, product.getName(), previous, threshold,
                    actorId, TenantContext.getCurrentTenant(), Instant.now()));
        }
        return saved;
    }
}
