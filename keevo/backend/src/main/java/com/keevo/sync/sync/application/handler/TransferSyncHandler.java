package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.stock.domain.port.in.TransferStockCommand;
import com.keevo.catalog.stock.domain.port.in.TransferStockUseCase;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class TransferSyncHandler extends AbstractSyncOperationHandler {

    private final TransferStockUseCase transferStockUseCase;

    public TransferSyncHandler(TransferStockUseCase transferStockUseCase) {
        this.transferStockUseCase = transferStockUseCase;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("STOCK_TRANSFER");
    }

    @Override
    protected void validate(SyncOperation operation) {
        Map<String, Object> p = operation.payload();
        if (p.get("sourceStoreId") == null || p.get("destinationStoreId") == null
                || p.get("productId") == null || p.get("quantity") == null) {
            throw new IllegalArgumentException("Missing required transfer fields");
        }
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        Map<String, Object> p = operation.payload();
        var transfer = transferStockUseCase.execute(new TransferStockCommand(
                UUID.fromString((String) p.get("sourceStoreId")),
                UUID.fromString((String) p.get("destinationStoreId")),
                UUID.fromString((String) p.get("productId")),
                p.get("variantId") != null ? UUID.fromString((String) p.get("variantId")) : null,
                ((Number) p.get("quantity")).intValue(),
                actorId,
                (String) p.get("notes")));

        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                transfer.getId().toString(), null);
    }
}
