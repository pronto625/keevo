package com.keevo.shared.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.keevo.shared.application.port.AuditPort;

import java.time.Instant;
import java.util.UUID;

/**
 * AuditEntryResponse — REST response DTO for a single audit log entry.
 *
 * <p>Story 1.8 — returned by {@link com.keevo.shared.infrastructure.web.AuditController}.
 * Story v1s-18-6 — additive enrichment fields (null on entries written before V14, except
 * {@code actorName}, resolved at read time). {@code metadata} is emitted as a JSON object
 * (raw jsonb text), not as a string.
 */
public record AuditEntryResponse(
        UUID      id,
        String    entityType,
        UUID      entityId,
        String    action,
        String    valueBefore,
        String    valueAfter,
        UUID      userId,
        String    actorPhone,
        Instant   occurredAt,
        UUID      storeId,
        String    storeName,
        String    entityLabel,
        String    deviceId,
        String    source,
        @JsonRawValue String metadata,
        String    actorName
) {

    /**
     * Map a port-layer {@link AuditPort.AuditEntryRecord} to a response DTO.
     */
    public static AuditEntryResponse fromRecord(AuditPort.AuditEntryRecord r) {
        return new AuditEntryResponse(
                r.id(),
                r.entityType(),
                r.entityId(),
                r.action(),
                r.valueBefore(),
                r.valueAfter(),
                r.userId(),
                r.actorPhone(),
                r.occurredAt(),
                r.storeId(),
                r.storeName(),
                r.entityLabel(),
                r.deviceId(),
                r.source(),
                r.metadata(),
                r.actorName()
        );
    }
}
