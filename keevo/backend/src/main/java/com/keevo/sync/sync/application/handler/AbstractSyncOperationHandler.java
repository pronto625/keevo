package com.keevo.sync.sync.application.handler;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import com.keevo.sync.sync.domain.port.in.SyncOperationHandler;

import java.util.UUID;

/**
 * AbstractSyncOperationHandler — Template Method for sync operation processing.
 * Skeleton: validate → apply. DomainException → REJECTED.
 */
public abstract class AbstractSyncOperationHandler implements SyncOperationHandler {

    @Override
    public final SyncOperationResult handle(SyncOperation op, UUID actorId, String tenantId) {
        try {
            validate(op);
            return apply(op, actorId, tenantId);
        } catch (DomainException e) {
            return mapDomainException(op, e);
        } catch (IllegalArgumentException e) {
            return new SyncOperationResult(op.operationId(), SyncOperationStatus.REJECTED, null, e.getMessage());
        } catch (Exception e) {
            return new SyncOperationResult(op.operationId(), SyncOperationStatus.REJECTED, null, "INTERNAL_ERROR");
        }
    }

    protected SyncOperationResult mapDomainException(SyncOperation op, DomainException e) {
        return new SyncOperationResult(op.operationId(), SyncOperationStatus.REJECTED, null,
                e.getErrorCode() != null ? e.getErrorCode().name() : e.getDomainCode());
    }

    protected abstract void validate(SyncOperation operation);

    protected abstract SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId);
}
