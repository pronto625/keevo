package com.keevo.shared.application.port;

import java.util.List;
import java.util.UUID;

/**
 * AuditPort — Domain port interface for recording and querying immutable audit trail entries.
 *
 * <p>Pure Java — NO Spring/JPA imports.
 * Implementation lives in the adapter layer (shared/infrastructure/persistence/impl/).
 *
 * <p>GoF Pattern: Strategy — concrete storage strategy (JPA) is injected by Spring DI.
 * Swapping to a remote audit service only requires a new AuditPort implementation.
 *
 * <p>Story 1.8 — Updated: {@code details} split into {@code valueBefore} + {@code valueAfter}.
 */
public interface AuditPort {

    /**
     * Record an immutable audit event within the caller's active transaction.
     *
     * @param actorId     UUID of the user performing the action
     * @param tenantId    Tenant schema name (e.g. "kv_abc123") — used for logging only;
     *                    routing is handled by TenantContext + SchemaAwareMultiTenantConnectionProvider
     * @param action      Action code e.g. "USER_REGISTERED", "PRODUCT_PRICE_UPDATED"
     * @param entityType  Affected entity type e.g. "User", "Product"
     * @param entityId    UUID of the affected entity
     * @param valueBefore JSON snapshot BEFORE the change (null for creation events)
     * @param valueAfter  JSON snapshot AFTER the change (null for deletion events)
     */
    void record(
            UUID actorId,
            String tenantId,
            String action,
            String entityType,
            UUID entityId,
            String valueBefore,
            String valueAfter
    );

    /**
     * Query audit entries (paginated) filtered by both entityType and entityId.
     * Results are sorted {@code occurredAt DESC}.
     *
     * @param entityType entity type filter (required)
     * @param entityId   entity UUID filter (required)
     * @param page       zero-based page index
     * @param size       number of entries per page
     * @return paginated result with entries and hasMore flag
     */
    AuditPage findByEntityTypeAndEntityId(String entityType, UUID entityId, int page, int size);

    /**
     * Query audit entries (paginated) filtered by entityType only.
     * Results are sorted {@code occurredAt DESC}.
     *
     * @param entityType entity type filter (required)
     * @param page       zero-based page index
     * @param size       number of entries per page
     * @return paginated result with entries and hasMore flag
     */
    AuditPage findByEntityType(String entityType, int page, int size);

    /**
     * Retrieve a paginated slice of the complete audit log for the current tenant.
     * Results are sorted {@code occurredAt DESC}.
     * Tenant isolation is guaranteed by TenantContext + schema routing.
     *
     * @param page zero-based page index
     * @param size number of entries per page
     * @return paginated result with entries and hasMore flag
     */
    AuditPage findAll(int page, int size);

    /**
     * Paginated query result — entries for the requested page and a flag
     * indicating whether a next page exists.
     */
    record AuditPage(List<AuditEntryRecord> entries, boolean hasMore) {}

    /**
     * AuditEntryRecord — port-layer aggregate snapshot of a single audit log entry.
     *
     * <p>Returned by query methods; mapped to response DTOs by the controller layer.
     */
    record AuditEntryRecord(
            UUID id,
            String entityType,
            UUID entityId,
            String action,
            String valueBefore,
            String valueAfter,
            UUID userId,
            String actorPhone,
            java.time.Instant occurredAt
    ) {}
}
