package com.keevo.shared.application.port;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * AuditPort — Domain port interface for recording and querying immutable audit trail entries.
 *
 * <p>Pure Java — NO Spring/JPA imports.
 * Implementation lives in the adapter layer (shared/audit/adapter/out/persistence/).
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
    default void record(
            UUID actorId,
            String tenantId,
            String action,
            String entityType,
            UUID entityId,
            String valueBefore,
            String valueAfter
    ) {
        record(new AuditEntry(actorId, tenantId, action, entityType, entityId,
                valueBefore, valueAfter, null, null, null, null, null));
    }

    /**
     * Record an enriched immutable audit event (Story v1s-18-6).
     * Every field after {@code valueAfter} is nullable.
     */
    void record(AuditEntry entry);

    /**
     * Filtered, newest-first query (Story v1s-18-6). Ordered {@code occurred_at DESC, id DESC}.
     *
     * @param query  filters (null fields are ignored)
     * @param cursor opaque keyset cursor from a previous {@link AuditSlice#nextCursor()};
     *               when non-null, {@code page} is ignored
     * @param page   zero-based offset page (legacy paging), used only without cursor; null = 0
     * @param size   page size
     * @throws IllegalArgumentException when {@code cursor} is malformed
     */
    AuditSlice search(AuditQuery query, String cursor, Integer page, int size);

    /**
     * Number of entries matching {@code query} — same filters as {@link #search} (Story v1s-18-2,
     * audit-log export). Implementations that back the export must override it.
     */
    default long count(AuditQuery query) {
        throw new UnsupportedOperationException("count is not supported by this AuditPort");
    }

    /** One entry of the current tenant by id, with resolved actor and store names. */
    Optional<AuditEntryRecord> findById(UUID id);

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
    default AuditPage findByEntityTypeAndEntityId(String entityType, UUID entityId, int page, int size) {
        AuditSlice slice = search(new AuditQuery(null, null, null, null, null, entityType, entityId),
                null, page, size);
        return new AuditPage(slice.entries(), slice.hasMore());
    }

    /**
     * Query audit entries (paginated) filtered by entityType only.
     * Results are sorted {@code occurredAt DESC}.
     *
     * @param entityType entity type filter (required)
     * @param page       zero-based page index
     * @param size       number of entries per page
     * @return paginated result with entries and hasMore flag
     */
    default AuditPage findByEntityType(String entityType, int page, int size) {
        AuditSlice slice = search(new AuditQuery(null, null, null, null, null, entityType, null),
                null, page, size);
        return new AuditPage(slice.entries(), slice.hasMore());
    }

    /**
     * Retrieve a paginated slice of the complete audit log for the current tenant.
     * Results are sorted {@code occurredAt DESC}.
     * Tenant isolation is guaranteed by TenantContext + schema routing.
     *
     * @param page zero-based page index
     * @param size number of entries per page
     * @return paginated result with entries and hasMore flag
     */
    default AuditPage findAll(int page, int size) {
        AuditSlice slice = search(AuditQuery.none(), null, page, size);
        return new AuditPage(slice.entries(), slice.hasMore());
    }

    /**
     * Paginated query result — entries for the requested page and a flag
     * indicating whether a next page exists.
     */
    record AuditPage(List<AuditEntryRecord> entries, boolean hasMore) {}

    /**
     * Enriched write command (Story v1s-18-6).
     *
     * @param storeId      store the action happened in (nullable)
     * @param entityLabel  readable target label at action time (never a UUID; nullable)
     * @param deviceId     originating device (nullable)
     * @param source       {@code ONLINE} | {@code SYNC} | {@code SYSTEM} (nullable for legacy callers)
     * @param metadataJson JSON object text with pure context (nullable)
     */
    record AuditEntry(
            UUID actorId,
            String tenantId,
            String action,
            String entityType,
            UUID entityId,
            String valueBefore,
            String valueAfter,
            UUID storeId,
            String entityLabel,
            String deviceId,
            String source,
            String metadataJson
    ) {}

    /**
     * Read filters — every field is optional; {@code from} and {@code to} are both inclusive.
     * {@code q} (Story v1s-18-7) is a case-insensitive substring of the object label
     * (wildcards matched literally).
     */
    record AuditQuery(
            Instant from,
            Instant to,
            UUID storeId,
            UUID userId,
            String action,
            String entityType,
            UUID entityId,
            String q
    ) {
        /** Pre v1s-18-7 shape — no object search. */
        public AuditQuery(Instant from, Instant to, UUID storeId, UUID userId,
                          String action, String entityType, UUID entityId) {
            this(from, to, storeId, userId, action, entityType, entityId, null);
        }

        public static AuditQuery none() {
            return new AuditQuery(null, null, null, null, null, null, null, null);
        }
    }

    /** Result of {@link #search}: {@code nextCursor} is non-null iff {@code hasMore}. */
    record AuditSlice(List<AuditEntryRecord> entries, boolean hasMore, String nextCursor) {}

    /**
     * Keyset position {@code (occurredAt, id)}; encoded as Base64URL of {@code "<ISO instant>|<uuid>"}.
     */
    record AuditCursor(Instant occurredAt, UUID id) {

        public String encode() {
            String raw = occurredAt.toString() + "|" + id;
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        /** @throws IllegalArgumentException when the cursor is not a valid encoded position */
        public static AuditCursor decode(String cursor) {
            if (cursor == null || cursor.isBlank() || cursor.length() > 200) {
                throw new IllegalArgumentException("Malformed audit cursor");
            }
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int sep = raw.indexOf('|');
                if (sep <= 0) throw new IllegalArgumentException("Malformed audit cursor");
                return new AuditCursor(Instant.parse(raw.substring(0, sep)),
                        UUID.fromString(raw.substring(sep + 1)));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Malformed audit cursor", e);
            }
        }
    }

    /**
     * AuditEntryRecord — port-layer aggregate snapshot of a single audit log entry.
     *
     * <p>Returned by query methods; mapped to response DTOs by the controller layer.
     * Enrichment fields (Story v1s-18-6) are null for rows written before V14, except
     * {@code actorName}, which is resolved at read time. {@code metadata} is raw JSON text.
     * {@code actorRole} (Story v1s-18-2) is the actor's {@code public.users.role}
     * ({@code OWNER} / {@code EMPLOYEE}), null when unknown.
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
            Instant occurredAt,
            UUID storeId,
            String storeName,
            String entityLabel,
            String deviceId,
            String source,
            String metadata,
            String actorName,
            String actorRole
    ) {
        /** v1s-18-6 shape — without the actor's platform role. */
        public AuditEntryRecord(UUID id, String entityType, UUID entityId, String action,
                                String valueBefore, String valueAfter, UUID userId,
                                String actorPhone, Instant occurredAt, UUID storeId,
                                String storeName, String entityLabel, String deviceId,
                                String source, String metadata, String actorName) {
            this(id, entityType, entityId, action, valueBefore, valueAfter, userId, actorPhone,
                    occurredAt, storeId, storeName, entityLabel, deviceId, source, metadata,
                    actorName, null);
        }

        /** Legacy shape (pre v1s-18-6) — enrichment fields null. */
        public AuditEntryRecord(UUID id, String entityType, UUID entityId, String action,
                                String valueBefore, String valueAfter, UUID userId,
                                String actorPhone, Instant occurredAt) {
            this(id, entityType, entityId, action, valueBefore, valueAfter, userId, actorPhone,
                    occurredAt, null, null, null, null, null, null, null, null);
        }
    }
}
