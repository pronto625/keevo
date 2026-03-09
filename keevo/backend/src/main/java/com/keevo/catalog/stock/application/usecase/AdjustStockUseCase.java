package com.keevo.catalog.stock.application.usecase;

import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.catalog.stock.domain.entity.StockMovement;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
import com.keevo.catalog.stock.domain.service.StockOperationService;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * AdjustStockUseCase — sets a product's stock to an absolute quantity.
 *
 * <p>Computes the signed delta (newQuantity − currentQuantity) and records
 * an ADJUSTMENT movement. If the current level is unknown it defaults to 0.
 *
 * Story 2.3.
 */
@Service
public class AdjustStockUseCase {

    private final StockOperationService stockOperationService;
    private final StockLevelRepository stockLevelRepository;

    public AdjustStockUseCase(StockOperationService stockOperationService,
                              StockLevelRepository stockLevelRepository) {
        this.stockOperationService = stockOperationService;
        this.stockLevelRepository  = stockLevelRepository;
    }

    /**
     * @param productId      target product
     * @param variantId      null for simple products
     * @param storeId        target store
     * @param newQuantity    desired absolute quantity (must be >= 0)
     * @param actorId        user performing the adjustment
     * @param notes          required reason for audit
     * @return the recorded StockMovement
     * @throws DomainException VALIDATION_ERROR if newQuantity is negative
     */
    @Transactional
    public StockMovement execute(UUID productId, UUID variantId, UUID storeId,
                                 int newQuantity, UUID actorId, String notes) {
        if (newQuantity < 0) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "La nouvelle quantité ne peut pas être négative");
        }

        // Load current level to compute delta
        int currentQuantity;
        if (variantId != null) {
            currentQuantity = stockLevelRepository
                    .findByProductVariantAndStore(productId, variantId, storeId)
                    .map(StockLevel::getQuantity).orElse(0);
        } else {
            currentQuantity = stockLevelRepository
                    .findByProductAndStore(productId, storeId)
                    .map(StockLevel::getQuantity).orElse(0);
        }

        int delta = newQuantity - currentQuantity;

        // No-op: quantity unchanged — still record a movement for traceability
        return stockOperationService.recordOperation(
                productId, variantId, storeId,
                MovementType.ADJUSTMENT, delta,
                actorId, notes);
    }
}
