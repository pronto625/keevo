package com.keevo.identity.employee.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * EmployeeDeactivatedEvent — Published after an employee's access is revoked.
 *
 * <p>Story 3.5 — AC7.
 */
public record EmployeeDeactivatedEvent(
        UUID actorId,
        UUID employeeId,
        UUID userId,
        Instant occurredAt
) {}
