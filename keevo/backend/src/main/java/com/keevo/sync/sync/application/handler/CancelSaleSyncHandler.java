package com.keevo.sync.sync.application.handler;

import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase.CancelPendingSaleCommand;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class CancelSaleSyncHandler extends AbstractSyncOperationHandler {

    private final CancelPendingSaleUseCase cancelPendingSaleUseCase;

    public CancelSaleSyncHandler(CancelPendingSaleUseCase cancelPendingSaleUseCase) {
        this.cancelPendingSaleUseCase = cancelPendingSaleUseCase;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("CANCEL_SALE");
    }

    @Override
    protected void validate(SyncOperation operation) {
        Map<String, Object> p = operation.payload();
        if (p.get("saleId") == null) {
            throw new IllegalArgumentException("Missing required field: saleId");
        }
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        Map<String, Object> p = operation.payload();

        UUID saleId = UUID.fromString((String) p.get("saleId"));
        String justification = (String) p.get("justification");

        cancelPendingSaleUseCase.cancelPendingSale(
                new CancelPendingSaleCommand(saleId, actorId, null, justification));

        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                saleId.toString(), null);
    }
}
