package com.keevo.shared.infrastructure.web.dto;

import com.keevo.shared.application.port.AuditPort;

import java.time.Instant;
import java.util.UUID;

/**
 * AuditEntryResponse — REST response DTO for a single audit log entry.
 *
 * <p>Story 1.8 — returned by {@link com.keevo.shared.infrastructure.web.AuditController}.
 *
 * <p>Maps from {@link AuditPort.AuditEntryRecord} — the port-layer aggregate.
 * This is a pure data carrier for the HTTP response layer.
 */
public record AuditEntryResponse(
        UUID      id,
        String    entityType,
        UUID      entityId,
        String    action,
        String    valueBefore,
        String    valueAfter,
        UUID      userId,
        Instant   occurredAt
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
                r.occurredAt()
        );
    }
}
