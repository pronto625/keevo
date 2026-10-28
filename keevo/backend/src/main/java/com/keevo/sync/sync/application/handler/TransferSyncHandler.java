package com.keevo.sync.sync.application.handler;

import com.keevo.inventory.stock.domain.port.in.TransferStockCommand;
import com.keevo.inventory.stock.domain.port.in.TransferStockUseCase;
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
        // Story v1s-12-11 (FR36, PO decision 2026-10-11): OWNER transfers from any store;
        // EMPLOYEE only from their JWT-assigned store — mirrors StockTransferController.transfer().
        // Checked before the use case (and its idempotent clientId replay lookup).
        Map<String, Object> p = operation.payload();
        UUID sourceStoreId = UUID.fromString((String) p.get("sourceStoreId"));
        UUID assignedStoreId = extractAssignedStoreId();
        if (assignedStoreId != null && !assignedStoreId.equals(sourceStoreId)) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "EMPLOYEE can only transfer stock from their assigned store");
        }
        // Offline-first fix: propagate the client-generated entity id as clientId so the
        // use case preserves it instead of minting a new server-side UUID (mirrors
        // CREATE_PRODUCT). Without this, the pull-merge INSERT ... ON CONFLICT(id) inserts a
        // duplicate row instead of updating the original offline record.
        UUID clientId = operation.entityId() != null ? UUID.fromString(operation.entityId()) : null;
        var transfer = transferStockUseCase.execute(new TransferStockCommand(
                sourceStoreId,
                UUID.fromString((String) p.get("destinationStoreId")),
                UUID.fromString((String) p.get("productId")),
                p.get("variantId") != null ? UUID.fromString((String) p.get("variantId")) : null,
                requireIntegralInt(p.get("quantity"), "quantity"),
                actorId,
                (String) p.get("notes"),
                clientId));

        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                transfer.getId().toString(), null);
    }
}
