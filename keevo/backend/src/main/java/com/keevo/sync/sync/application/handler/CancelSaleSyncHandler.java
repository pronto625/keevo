package com.keevo.sync.sync.application.handler;

import com.keevo.commerce.sale.domain.model.SaleStatus;
import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase.CancelPendingSaleCommand;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
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

    private final CancelPendingSaleUseCase cancelPendingSaleUseCase;
    private final SaleRepository saleRepository;

    public CancelSaleSyncHandler(CancelPendingSaleUseCase cancelPendingSaleUseCase,
                                 SaleRepository saleRepository) {
        this.cancelPendingSaleUseCase = cancelPendingSaleUseCase;
        this.saleRepository = saleRepository;
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

        // Story v1s-13-5 AC5: cancelling a COMPLETED sale (with stock restoration) requires
        // an online OWNER-authenticated REST request — the sync channel does not carry a
        // verifiable role, so it must reject rather than silently widen what an offline
        // EMPLOYEE device can do (see Décision D2).
        var sale = saleRepository.findById(saleId)
                .orElseThrow(() -> new DomainException(ErrorCode.SALE_NOT_FOUND, "Sale not found: " + saleId));
        if (sale.getStatus() == SaleStatus.COMPLETED) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "Cancelling a completed sale requires an online OWNER-authenticated request");
        }

        UUID assignedStoreId = extractAssignedStoreId();
        cancelPendingSaleUseCase.cancelPendingSale(
                new CancelPendingSaleCommand(saleId, actorId, assignedStoreId, justification));

        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                saleId.toString(), null);
    }
}
