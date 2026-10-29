package com.keevo.sync.sync.application.handler;

import com.keevo.commerce.api.CloseDayInput;
import com.keevo.commerce.api.DayClosureCommandPort;
import com.keevo.commerce.api.DayClosureView;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.api.StoreQueryPort;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class DayClosureSyncHandler extends AbstractSyncOperationHandler {

    private final DayClosureCommandPort dayClosureCommandPort;
    private final StoreQueryPort storeQuery;

    public DayClosureSyncHandler(DayClosureCommandPort dayClosureCommandPort, StoreQueryPort storeQuery) {
        this.dayClosureCommandPort = dayClosureCommandPort;
        this.storeQuery = storeQuery;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("CREATE_DAY_CLOSURE");
    }

    @Override
    public String moduleId() {
        return "pos";
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
        UUID assignedStoreId = extractAssignedStoreId();
        if (assignedStoreId != null && !assignedStoreId.equals(storeId)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Employee cannot close another store's day");
        }
        // v1s-16-15: a closure for a store the server does not know is refused (quarantined
        // on the device, logged in sync_error_log) instead of being stored as an orphan.
        if (storeQuery.findById(tenantId, storeId).isEmpty()) {
            throw new DomainException(ErrorCode.STORE_NOT_FOUND, "Store not found: " + storeId);
        }

        // Keep the device's closure id (otherwise the pull adds a second closure row next to
        // the local one) and the time it was made (otherwise the push day gets closed).
        UUID closureId = parseUuid(p.get("id"));
        if (closureId == null) closureId = parseUuid(operation.entityId());
        Instant closedAt = resolveClientTime(p.get("closedAt"), operation.clientTimestamp());

        DayClosureView closure = dayClosureCommandPort.closeDay(tenantId,
                new CloseDayInput(storeId, actorId, false, closureId, closedAt));

        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                closure.id().toString(), null);
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
