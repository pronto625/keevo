package com.keevo.identity.employee.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * EmployeePasswordSetByOwnerEvent — Published after an owner manually sets an
 * employee's password.
 *
 * <p><strong>NOT</strong> {@link EmployeePasswordSetEvent} — that event is reserved
 * for self-service password changes by the employee themselves (Story 3.5 AC4).
 * Using a separate event type avoids semantic confusion (actorId = owner vs actorId = employee).
 *
 * <p>Story 14.11 — consumed by AuditEventListener for immutable audit trail.
 */
public record EmployeePasswordSetByOwnerEvent(
        UUID actorId,
        String tenantId,
        UUID employeeId,
        Instant occurredAt
) {}
