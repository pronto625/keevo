package com.keevo.catalog.stock.application.usecase;

import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.entity.StockMovement;
import com.keevo.catalog.stock.domain.service.StockOperationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * RecordStockEntryUseCase — records an incoming stock delivery (STOCK_ENTRY).
 *
 * <p>Delegates to {@link StockOperationService} which handles the full
 * load-validate-persist-event cycle.
 *
 * Story 2.3.
 */
@Service
public class RecordStockEntryUseCase {

    private final StockOperationService stockOperationService;

    public RecordStockEntryUseCase(StockOperationService stockOperationService) {
        this.stockOperationService = stockOperationService;
    }

    /**
     * @param productId  target product
     * @param variantId  null for simple products
     * @param storeId    destination store
     * @param quantity   number of units received (must be > 0)
     * @param actorId    user performing the operation
     * @param notes      optional delivery reference / notes
     * @return the recorded StockMovement
     * @throws IllegalArgumentException if quantity <= 0
     */
    @Transactional
    public StockMovement execute(UUID productId, UUID variantId, UUID storeId,
                                 int quantity, UUID actorId, String notes) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Entry quantity must be positive");
        }
        return stockOperationService.recordOperation(
                productId, variantId, storeId,
                MovementType.STOCK_ENTRY, quantity,
                actorId, notes);
    }
}
