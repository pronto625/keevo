package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.stock.application.usecase.AdjustStockUseCase;
import com.keevo.catalog.stock.application.usecase.RecordStockEntryUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class StockAdjustSyncHandler extends AbstractSyncOperationHandler {

    private final AdjustStockUseCase adjustStockUseCase;
    private final RecordStockEntryUseCase recordStockEntryUseCase;

    public StockAdjustSyncHandler(AdjustStockUseCase adjustStockUseCase,
                                  RecordStockEntryUseCase recordStockEntryUseCase) {
        this.adjustStockUseCase = adjustStockUseCase;
        this.recordStockEntryUseCase = recordStockEntryUseCase;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("STOCK_ADJUST", "RECORD_STOCK_ENTRY");
    }

    @Override
    protected void validate(SyncOperation operation) {
        Map<String, Object> p = operation.payload();
        if (p.get("productId") == null || p.get("storeId") == null || p.get("quantity") == null) {
            throw new IllegalArgumentException("Missing required stock fields");
        }
        requireIntegralInt(p.get("quantity"), "quantity");
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        Map<String, Object> p = operation.payload();
        UUID productId = UUID.fromString((String) p.get("productId"));
        UUID variantId = p.get("variantId") != null ? UUID.fromString((String) p.get("variantId")) : null;
        UUID storeId = UUID.fromString((String) p.get("storeId"));
        int quantity = requireIntegralInt(p.get("quantity"), "quantity");
        String notes = (String) p.get("notes");

        // Story v1s-12-8 AC3: mirror StockController scope check (FR36)
        UUID assignedStoreId = extractAssignedStoreId();
        if (assignedStoreId != null && !assignedStoreId.equals(storeId)) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "EMPLOYEE cannot modify stock for another store");
        }

        // The device queues its local movement id as entityId: reusing it lets the pull update
        // that movement instead of adding a second copy to the history. Some callers queue the
        // product id instead, which is not a movement id.
        UUID movementId = parseUuid(operation.entityId());
        if (productId.equals(movementId)) movementId = null;

        if ("RECORD_STOCK_ENTRY".equals(operation.operationType())) {
            recordStockEntryUseCase.execute(productId, variantId, storeId, quantity, actorId, notes, movementId);
        } else {
            adjustStockUseCase.execute(productId, variantId, storeId, quantity, actorId, notes, movementId);
        }

        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED, null, null);
    }
}
