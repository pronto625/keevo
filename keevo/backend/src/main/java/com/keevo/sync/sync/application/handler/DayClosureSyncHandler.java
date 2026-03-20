package com.keevo.sync.sync.application.handler;

import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase.CloseDayCommand;
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
public class DayClosureSyncHandler extends AbstractSyncOperationHandler {

    private final CloseDayUseCase closeDayUseCase;

    public DayClosureSyncHandler(CloseDayUseCase closeDayUseCase) {
        this.closeDayUseCase = closeDayUseCase;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("CREATE_DAY_CLOSURE");
    }

    @Override
    protected void validate(SyncOperation operation) {
        if (operation.payload().get("storeId") == null) {
            throw new IllegalArgumentException("Missing required field: storeId");
        }
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        Map<String, Object> p = operation.payload();
        UUID storeId = UUID.fromString((String) p.get("storeId"));

        closeDayUseCase.closeDay(new CloseDayCommand(storeId, actorId, tenantId, false));

        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                storeId.toString(), null);
    }

    /**
     * DAY_ALREADY_CLOSED maps to DUPLICATE (not REJECTED) — the day was already closed.
     */
    @Override
    protected SyncOperationResult mapDomainException(SyncOperation op, DomainException e) {
        if (e.getErrorCode() == ErrorCode.DAY_ALREADY_CLOSED) {
            return new SyncOperationResult(op.operationId(), SyncOperationStatus.DUPLICATE, null, null);
        }
        return super.mapDomainException(op, e);
    }
}
