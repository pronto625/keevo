package com.keevo.admin.tenant.domain.model;

import java.time.Instant;

/**
 * AdminAuditEvent — one audit log entry in the tenant detail view.
 */
public record AdminAuditEvent(
        String id,
        String eventType,
        String actorId,
        Instant occurredAt,
        String details
) {}
