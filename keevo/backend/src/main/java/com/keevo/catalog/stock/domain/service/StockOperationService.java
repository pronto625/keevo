package com.keevo.catalog.stock.domain.service;

import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.catalog.stock.domain.entity.StockMovement;
import com.keevo.catalog.stock.domain.event.StockAdjustedEvent;
import com.keevo.catalog.stock.domain.event.StockThresholdBreachedEvent;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
import com.keevo.catalog.stock.domain.port.out.StockMovementRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * StockOperationService — coordinates all stock-modifying operations.
 *
 * <p>Single responsibility: manage the lifecycle of a stock change:
 * load state → validate → record movement → update level → emit events.
 *
 * <p>GoF Patterns:
 * <ul>
 *   <li><b>Domain Service</b>: encapsulates multi-entity business logic.
 *       Use cases stay thin and delegate here.</li>
 *   <li><b>Observer</b>: publishes {@link StockAdjustedEvent} and
 *       {@link StockThresholdBreachedEvent} via Spring's ApplicationEventPublisher.
 *       Audit and notification handlers react without coupling.</li>
 * </ul>
 *
 * <p><b>@Transactional</b>: all steps are atomic. If the transaction rolls back,
 * no events are published (Spring publishes after commit by default).
 *
 * Story 2.3.
 */
@Service
public class StockOperationService {

    private static final Logger log = LoggerFactory.getLogger(StockOperationService.class);

    private final StockLevelRepository stockLevelRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ProductRepository productRepository;
    private final ApplicationEventPublisher eventPublisher;

    public StockOperationService(StockLevelRepository stockLevelRepository,
                                 StockMovementRepository stockMovementRepository,
                                 ProductRepository productRepository,
                                 ApplicationEventPublisher eventPublisher) {
        this.stockLevelRepository    = stockLevelRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.productRepository       = productRepository;
        this.eventPublisher          = eventPublisher;
    }

    /**
     * Record a stock operation and update the stock level atomically.
     *
     * @param productId      target product
     * @param variantId      null for simple products
     * @param storeId        target store
     * @param movementType   operation type
     * @param quantityChange signed delta (positive=in, negative=out)
     * @param actorId        user performing the operation
     * @param notes          optional reason/notes
     * @return the saved StockMovement record
     * @throws DomainException PRODUCT_NOT_FOUND if product does not exist
     * @throws DomainException INSUFFICIENT_STOCK if quantityAfter would be negative
     */
    @Transactional
    public StockMovement recordOperation(UUID productId, UUID variantId, UUID storeId,
                                         MovementType movementType, int quantityChange,
                                         UUID actorId, String notes) {
        // 1. Load product (for name + threshold)
        var product = productRepository.findById(productId)
                .orElseThrow(() -> new DomainException(ErrorCode.PRODUCT_NOT_FOUND,
                        "Product not found: " + productId));

        // 2. Load current stock level (creating one at 0 if first operation)
        StockLevel currentLevel;
        if (variantId != null) {
            currentLevel = stockLevelRepository
                    .findByProductVariantAndStore(productId, variantId, storeId)
                    .orElse(newZeroLevel(productId, variantId, storeId));
        } else {
            currentLevel = stockLevelRepository
                    .findByProductAndStore(productId, storeId)
                    .orElse(newZeroLevel(productId, null, storeId));
        }

        int quantityBefore = currentLevel.getQuantity();
        int quantityAfter  = quantityBefore + quantityChange;

        // 3. Guard: no negative stock (except explicit adjustment to 0 via AdjustStockUseCase)
        if (quantityAfter < 0) {
            throw new DomainException(ErrorCode.INSUFFICIENT_STOCK,
                    "Stock insuffisant: avant=" + quantityBefore + " delta=" + quantityChange);
        }

        // 4. Build and persist the immutable movement record
        var now = Instant.now();
        var movement = new StockMovement(
            UUID.randomUUID(),
            productId, variantId, storeId,
            movementType,
            quantityBefore, quantityChange, quantityAfter,
            actorId, notes, now
        );
        var savedMovement = stockMovementRepository.save(movement);

        // 5. Update (or create) the stock level
        StockLevel updatedLevel = currentLevel.withQuantity(quantityAfter);
        stockLevelRepository.save(updatedLevel);

        String tenantId = TenantContext.getCurrentTenant();

        // 6. Publish stock adjusted event (Observer — decouples audit from domain)
        eventPublisher.publishEvent(new StockAdjustedEvent(
            productId, variantId, storeId,
            movementType,
            quantityBefore, quantityChange, quantityAfter,
            actorId, notes,
            tenantId, now
        ));

        // 7. Threshold check — server-side enforcement (cannot be bypassed client-side)
        int threshold = product.getMinimumThreshold();
        if (threshold > 0 && quantityAfter <= threshold) {
            eventPublisher.publishEvent(new StockThresholdBreachedEvent(
                productId,
                product.getName(),
                storeId,
                quantityAfter,
                threshold,
                actorId,
                tenantId,
                now
            ));
            log.warn("STOCK_THRESHOLD_BREACHED: product={} store={} quantity={} threshold={}",
                productId, storeId, quantityAfter, threshold);
        }

        log.info("STOCK_OPERATION: type={} product={} store={} before={} change={} after={}",
            movementType, productId, storeId, quantityBefore, quantityChange, quantityAfter);

        return savedMovement;
    }

    private StockLevel newZeroLevel(UUID productId, UUID variantId, UUID storeId) {
        return new StockLevel(UUID.randomUUID(), productId, variantId, storeId, 0, Instant.now());
    }
}
