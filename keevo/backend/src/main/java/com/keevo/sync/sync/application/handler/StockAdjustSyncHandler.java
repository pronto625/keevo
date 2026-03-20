package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.stock.application.usecase.AdjustStockUseCase;
import com.keevo.catalog.stock.application.usecase.RecordStockEntryUseCase;
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
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        Map<String, Object> p = operation.payload();
        UUID productId = UUID.fromString((String) p.get("productId"));
        UUID variantId = p.get("variantId") != null ? UUID.fromString((String) p.get("variantId")) : null;
        UUID storeId = UUID.fromString((String) p.get("storeId"));
        int quantity = ((Number) p.get("quantity")).intValue();
        String notes = (String) p.get("notes");

        if ("RECORD_STOCK_ENTRY".equals(operation.operationType())) {
            recordStockEntryUseCase.execute(productId, variantId, storeId, quantity, actorId, notes);
        } else {
            adjustStockUseCase.execute(productId, variantId, storeId, quantity, actorId, notes);
        }

        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED, null, null);
    }
}
