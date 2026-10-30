package com.keevo.shared.application.port.sync;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.security.CurrentActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
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

    private static final Logger log = LoggerFactory.getLogger(AbstractSyncOperationHandler.class);

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
            // 12.2e: e.g. an IllegalStateException from TenantContext.requireCurrent (tenant claim ≠ bound
            // tenant) — the device still sees INTERNAL_ERROR, the cause is kept in the server log.
            log.error("[sync] operation {} ({}) failed unexpectedly", op.operationId(), op.operationType(), e);
            return new SyncOperationResult(op.operationId(), SyncOperationStatus.REJECTED, null, "INTERNAL_ERROR");
        }
    }

    protected SyncOperationResult mapDomainException(SyncOperation op, DomainException e) {
        return new SyncOperationResult(op.operationId(), SyncOperationStatus.REJECTED, null,
                e.getErrorCode() != null ? e.getErrorCode().name() : e.getDomainCode());
    }

    /**
     * Returns the EMPLOYEE's assigned store UUID, or {@code null} for OWNER. Fails closed
     * when there is no authenticated actor (v1s-12-10). Delegates to {@link CurrentActor}.
     */
    protected UUID extractAssignedStoreId() {
        return CurrentActor.assignedStoreId();
    }

    /** Delegates to {@link CurrentActor#isOwner()}. */
    protected boolean isOwnerRole() {
        return CurrentActor.isOwner();
    }

    /**
     * v1s-12-10: the actor's role as the use cases expect it ({@code "OWNER"} /
     * {@code "EMPLOYEE"}), or {@code null} when unauthenticated. Always read from the
     * authenticated actor, never from the payload. Delegates to {@link CurrentActor#role()}.
     */
    protected String actorRole() {
        return CurrentActor.role();
    }

    /**
     * v1s-13-8 / v1s-15-9: a bare Number-to-int narrowing silently truncated 1.5 → 1
     * and wrapped values beyond the int range. The value must be a JSON number, integral and
     * within int range (a whole double such as 5.0 is accepted as 5).
     *
     * @throws IllegalArgumentException {@code "Invalid <field>: …"} otherwise (→ REJECTED)
     */
    protected static int requireIntegralInt(Object raw, String field) {
        if (!(raw instanceof Number n)) {
            throw new IllegalArgumentException("Invalid " + field + ": must be a number");
        }
        BigDecimal value;
        try {
            value = (n instanceof BigDecimal bd) ? bd : new BigDecimal(n.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + field + ": " + n);
        }
        try {
            return value.stripTrailingZeros().intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Invalid " + field + ": must be a whole number in int range");
        }
    }

    /** {@link #requireIntegralInt} for optional fields: {@code null} stays {@code null}. */
    protected static Integer optionalIntegralInt(Object raw, String field) {
        return raw == null ? null : requireIntegralInt(raw, field);
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
