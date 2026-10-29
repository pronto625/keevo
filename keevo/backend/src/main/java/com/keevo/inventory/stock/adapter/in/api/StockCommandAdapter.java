package com.keevo.inventory.stock.adapter.in.api;

import com.keevo.inventory.api.StockCommandPort;
import com.keevo.inventory.api.StockMovementInput;
import com.keevo.inventory.api.StockMovementKind;
import com.keevo.inventory.stock.application.usecase.AdjustStockUseCase;
import com.keevo.inventory.stock.application.usecase.RecordStockEntryUseCase;
import com.keevo.inventory.stock.domain.entity.MovementType;
import com.keevo.inventory.stock.domain.service.StockOperationService;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Implements the published {@link StockCommandPort} by delegating to the existing stock service
 * and use cases (Story 12.2b). No transaction of its own: the delegates' {@code @Transactional}
 * joins the caller's transaction, as the former direct calls did.
 */
@Component
class StockCommandAdapter implements StockCommandPort {

    private final StockOperationService stockOperationService;
    private final AdjustStockUseCase adjustStockUseCase;
    private final RecordStockEntryUseCase recordStockEntryUseCase;

    StockCommandAdapter(StockOperationService stockOperationService,
                               AdjustStockUseCase adjustStockUseCase,
                               RecordStockEntryUseCase recordStockEntryUseCase) {
        this.stockOperationService = stockOperationService;
        this.adjustStockUseCase = adjustStockUseCase;
        this.recordStockEntryUseCase = recordStockEntryUseCase;
    }

    @Override
    public void recordMovement(String tenantSchema, StockMovementInput in) {
        TenantContext.requireCurrent(tenantSchema);
        stockOperationService.recordOperation(
                in.productId(), in.variantId(), in.storeId(),
                toMovementType(in.kind()), in.quantityChange(),
                in.actorId(), in.notes(), in.movementId(), in.allowNegative());
    }

    @Override
    public void adjustTo(String tenantSchema, UUID productId, UUID variantId, UUID storeId, int newQuantity,
                         UUID actorId, String notes, UUID movementId) {
        TenantContext.requireCurrent(tenantSchema);
        adjustStockUseCase.execute(productId, variantId, storeId, newQuantity, actorId, notes, movementId);
    }

    @Override
    public void recordEntry(String tenantSchema, UUID productId, UUID variantId, UUID storeId, int quantity,
                            UUID actorId, String notes, UUID movementId) {
        TenantContext.requireCurrent(tenantSchema);
        recordStockEntryUseCase.execute(productId, variantId, storeId, quantity, actorId, notes, movementId);
    }

    static MovementType toMovementType(StockMovementKind kind) {
        return switch (kind) {
            case SALE -> MovementType.SALE;
            case SALE_CANCELLED -> MovementType.SALE_CANCELLED;
            case STOCK_ENTRY -> MovementType.STOCK_ENTRY;
            case ADJUSTMENT -> MovementType.ADJUSTMENT;
        };
    }
}
