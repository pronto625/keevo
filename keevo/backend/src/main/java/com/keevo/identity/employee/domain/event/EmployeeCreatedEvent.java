package com.keevo.identity.employee.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * EmployeeCreatedEvent — Published after successful employee creation.
 *
 * <p>GoF Pattern: Observer — published via ApplicationEventPublisher,
 * consumed by AuditEventListener.
 *
 * <p>Story 3.5 — AC1.
 */
public record EmployeeCreatedEvent(
        UUID actorId,
        UUID targetUserId,
        UUID employeeId,
        UUID storeId,
        Instant occurredAt
) {}
