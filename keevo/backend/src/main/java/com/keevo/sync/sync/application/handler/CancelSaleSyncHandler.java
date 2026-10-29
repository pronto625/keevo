package com.keevo.sync.sync.application.handler;

import com.keevo.commerce.api.CancelSaleInput;
import com.keevo.commerce.api.SaleCommandPort;
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
public class CancelSaleSyncHandler extends AbstractSyncOperationHandler {

    private final SaleCommandPort saleCommandPort;

    public CancelSaleSyncHandler(SaleCommandPort saleCommandPort) {
        this.saleCommandPort = saleCommandPort;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("CANCEL_SALE");
    }

    @Override
    public String moduleId() {
        return "pos";
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
        // Story 11.4: same rule as REST (PendingSaleController.cancelSale is OWNER-only) — the
        // offline queue must not let an EMPLOYEE cancel what the online API refuses. Role read
        // from the authenticated actor (JWT), never from the payload; fails closed when absent.
        if (!isOwnerRole()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "OWNER role required to cancel a sale");
        }
        Map<String, Object> p = operation.payload();

        UUID saleId = UUID.fromString((String) p.get("saleId"));
        String justification = (String) p.get("justification");

        // Story v1s-13-5 AC5: cancelling a COMPLETED sale (with stock restoration) requires
        // an online OWNER-authenticated REST request — the sync channel does not carry a
        // verifiable role, so it must reject rather than silently widen what an offline
        // EMPLOYEE device can do (see Décision D2). Story 13.9: the guard lives in the use case
        // (pendingOnly=true) so the status check and the cancellation are one atomic claim —
        // a sale validated concurrently is refused with FORBIDDEN too.
        UUID assignedStoreId = extractAssignedStoreId();
        saleCommandPort.cancelSale(tenantId,
                new CancelSaleInput(saleId, actorId, assignedStoreId, justification, true));

        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                saleId.toString(), null);
    }
}
