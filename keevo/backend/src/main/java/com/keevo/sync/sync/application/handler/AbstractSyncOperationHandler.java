package com.keevo.sync.sync.application.handler;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.AuthDetails;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import com.keevo.sync.sync.domain.port.in.SyncOperationHandler;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * AbstractSyncOperationHandler — Template Method for sync operation processing.
 * Skeleton: validate → apply. DomainException → REJECTED.
 *
 * <p>Story v1s-12-8: added shared RBAC helpers so every concrete handler can read
 * the current actor's role and assigned store from Spring Security without
 * duplicating the extraction logic.
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

    /**
     * Returns the EMPLOYEE's assigned store UUID (nullable), or {@code null} for OWNER.
     * Mirror of {@code PendingSaleController.extractAssignedStoreId()} /
     * {@code StockController.extractAssignedStoreId()}.
     */
    protected UUID extractAssignedStoreId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        Object details = auth.getDetails();
        if (details instanceof AuthDetails ad && ad.storeId() != null) {
            return ad.storeId();
        }
        boolean isEmployee = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_EMPLOYEE".equals(a.getAuthority()));
        if (isEmployee) {
            throw new DomainException(ErrorCode.FORBIDDEN, "EMPLOYEE token missing assigned store");
        }
        return null;
    }

    /** Mirror of {@code StockTransferController.isOwnerRole()}. */
    protected boolean isOwnerRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_OWNER".equals(a.getAuthority()));
    }

    /**
     * When something happened on the device: the ISO-8601 payload value, else the time the
     * operation was queued, else now. Never in the future — a device clock running ahead must
     * not move a sale or a closure into a day that has not started yet.
     */
    protected static Instant resolveClientTime(Object rawPayloadValue, Instant clientTimestamp) {
        Instant now = Instant.now();
        Instant resolved = null;
        if (rawPayloadValue instanceof String str && !str.isBlank()) {
            try {
                resolved = OffsetDateTime.parse(str).toInstant();
            } catch (DateTimeParseException e) {
                resolved = null;
            }
        }
        if (resolved == null) resolved = clientTimestamp;
        if (resolved == null || resolved.isAfter(now)) return now;
        return resolved;
    }

    /** Lenient UUID parse for optional client ids: null when absent or not a UUID. */
    protected static UUID parseUuid(Object raw) {
        if (!(raw instanceof String str) || str.isBlank()) return null;
        try {
            return UUID.fromString(str);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    protected abstract void validate(SyncOperation operation);

    protected abstract SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId);
}
